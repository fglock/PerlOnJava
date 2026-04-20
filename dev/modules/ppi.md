# PPI Module Support Plan

Tracks investigation and fixes for `./jcpan -t PPI` (PPI v1.284).

## Initial Test Run Summary

- `make test` (PPI): 35/68 test programs FAIL, 339981/371027 subtests FAIL.
- Many test files exit early ("Bad plan. You planned N tests but ran M"), indicating a
  PPI-level parse exception propagates through shared test setup and kills the run before
  most assertions even execute. Fixing a small number of root causes is expected to unblock
  very large numbers of subtests.

Failing files (representative):

| File | Tests / Failed | Notes |
|------|----------------|-------|
| t/ppi_token_whitespace.t | 6 / 6 (0 ran) | `Package::->method()` → classname is `"Package::"` instead of `"Package"` |
| t/ppi_statement.t | 23 / 23 (2 ran) | Lexer throws `Illegal state in 'while' compound statement` |
| t/ppi_statement_compound.t | 53 / 53 (2 ran) | Same lexer issue (compound statements) |
| t/ppi_token_word.t | 2017 / 2003 (14 ran) | Likely same root cause (compound/structure lexing) |
| t/ppi_token_operator.t | 3009 / 492 (2981 ran) | Partial failures |
| t/21_exhaustive.t | 9722 / 4 | Round-trip stress (near-pass) |
| t/25_increment.t | 9554 / 8131 | Large regression |
| t/ppi_statement_sub.t | 1297 / 997 | |
| t/ppi_statement_include.t | 6070 / 1928 | |
| t/ppi_token_attribute.t | 2235 / 1098 | |
| t/ppi_token_unknown.t | 2328 / 280 | |
| t/signature_details.t | 16 / 16 | Feature gap (signatures) |
| t/signatures.t | 3 / 1 | |
| t/feature_tracking.t | 14 / 12 | |

Files that already pass: `00-report-prereqs.t`, `01_compile.t`, `03_document.t`,
`09_normal.t`, `10_statement.t`, `11_util.t`, `13_data.t`, `14_charsets.t`,
`15_transform.t`, `16_xml.t`, `22_readonly.t`, `23_file.t`, `24_v6.t`, `26_bom.t`,
`27_complete.t`, `28_foreach_qw.t`, `interactive.t`, `ppi_node.t`, `ppi_normal.t`,
`ppi_token.t`, `ppi_token__quoteengine_full.t`, `ppi_token_dashedword.t`,
`ppi_token_heredoc.t`, `ppi_token_number_version.t`, `ppi_token_pod.t`,
`ppi_token_quote.t`, `ppi_token_quote_double.t`, `ppi_token_quote_interpolate.t`,
`ppi_token_quote_single.t`, `ppi_token_quotelike_regexp.t`,
`ppi_token_quotelike_words.t`, `ppi_token_regexp.t`, `ppi_statement_scheduled.t`.

## Root Causes Identified So Far

### RC1 — `Package::->method()` passes class name with trailing `::`

Minimal repro (PerlOnJava):

```perl
package Foo; sub bar { print "[$_[0]]\n" }
package main; Foo::->bar();   # prints [Foo::]  — real Perl prints [Foo]
```

Real Perl strips the trailing `::` in bareword-plus-`::` method invocation so
`Foo::->bar()` is equivalent to `Foo->bar()`. PerlOnJava keeps the `::`, so
the first argument to the method is `"Foo::"`. Downstream this breaks things
like `$class->can(...)`, `SUPER::` handling, and especially PPI which uses
`PPI::Token::Whitespace::->new(...)` heavily in its test suite.

Impact: aborts `t/ppi_token_whitespace.t` on line 1 and probably contributes
to failures in tests using the same idiom. Easy and high-value fix.

### RC2 — Compound-statement lexing: `PPI::Structure::Condition` loses its `start` brace

Minimal repro (PerlOnJava):

```perl
use PPI;
my $d = PPI::Document->new(\"while (1) { last; }");
# Doc: undef
# Err: Illegal state in 'while' compound statement
```

**Root cause (found via instrumentation):** PPI's `PPI::Node::DESTROY` is being
called on a `PPI::Structure::Condition` *while it is still reachable through
its parent Statement's `{children}` array*. `DESTROY` then does
`%$_ = ()` on every node in the subtree (that's how PPI cleans up weak-parent
cross-links), which empties the Structure's hash. When the lexer next calls
`_continues`, it sees `$LastChild->{start}` as undef, falls through to the
`while` branch, and throws `Illegal state in 'while' compound statement`.

The root cause is **not** in PPI; it is in PerlOnJava's cooperative refcount
bookkeeping for containers that hold DESTROY-tracked objects.

**Minimal PerlOnJava-only repro (no PPI):**

```perl
package Foo;
sub new { bless { a=>1 }, shift }
sub DESTROY { warn "DESTROY\n" }
package main;
my @arr;
my $obj = Foo->new;
push @arr, $obj;    # @arr[0] holds a strong ref
undef $obj;         # only the array ref should remain
# expected (real Perl): nothing happens until end of script
# actual (PerlOnJava):  DESTROY fires here
```

`$H{k} = $obj; undef $obj` shows the same bug (hash element store). There is
also a related-but-distinct bug where `return $r` from a sub in which `$r` was
hash-assigned also mis-accounts the refcount, while `$r;` (implicit return)
does not.

**Attempted fix and why it was reverted:**

Adding `RuntimeScalar.incrementRefCountForContainerStore` to `RuntimeArray.push`
(the PLAIN_ARRAY path) does fix the isolated `push @arr, $obj; undef $obj;`
repro and is necessary. However:

- It alone is not enough to fix PPI — there are at least two other paths
  (hash element store, and the explicit-return + hash-assignment path) that
  still over-decrement, and `PPI::Structure::Condition` still gets DESTROYed
  mid-lex after the push fix.
- It breaks existing refcount-parity tests that assume the pre-existing
  (under-counted) balance:
  - `src/test/resources/unit/refcount/weaken_edge_cases.t`
  - `src/test/resources/unit/refcount/destroy_collections.t`
  Those tests treat `shift`, `splice`, `%h = ()`, and various collection
  removal operations as if they decrement nothing; if `push` increments, those
  removal paths must decrement correspondingly, otherwise objects stay alive
  one slot longer than expected.

The proper fix is therefore a coordinated refcount pass across every container
mutation op, not a single-call patch. This is larger than the PPI scope and
should be tracked as its own project (dev/architecture/weaken-destroy.md
should be updated to cover container-store refcount parity, and tests under
`unit/refcount/` will need to be rebalanced at the same time).

Until that work is done, the push fix is **reverted on master**; the RC1 fix
alone lands in this iteration.

### RC3 — `use of uninitialized value` warnings from PPI::Tokenizer


Line 850 of `PPI/Tokenizer.pm`:
```perl
return 1 if not $USUALLY_FORCES{$prevprev->content} and $prevprev->content ne '->';
```

`$prevprev->content` is undef in some cases on PerlOnJava. Likely a symptom of
RC2, not an independent bug. Recheck after RC2 is fixed.

### RC4 — Signatures & feature tracking gaps

`t/signature_details.t` (16/16 fail) and `t/signatures.t` (1/3) and
`t/feature_tracking.t` (12/14) are all about Perl subroutine signatures. Lower
priority; investigate only after RC1/RC2 are resolved and the downstream test
counts stabilize.

## Implementation Plan

Order is chosen so that each step unblocks the largest number of downstream
tests before moving on, and each step is independently verifiable.

### Phase 1 — Fix `Package::->method()` stripping (RC1)

- **Where**: method-call bareword parsing / resolution. Search for `->` method
  dispatch where the invocant is a bareword with trailing `::`. Most likely in
  the parser/AST or in `RuntimeCode`/method dispatch in
  `org.perlonjava.runtime` / `org.perlonjava.parser`.
- **Behavior**: when the invocant is a bareword of the form `FOO::BAR::`,
  strip the trailing `::` before using it as the class name passed as the
  first argument of the method.
- **Tests**:
  - `./jperl -e 'package Foo; sub bar { print shift } package main; Foo::->bar()'`
    → `Foo`
  - re-run `t/ppi_token_whitespace.t` — all 6 should pass.
  - add a regression test under `src/test/resources/unit/` (e.g.
    `method_call_trailing_colons.t`) — 2 quick subtests.
- **Risk**: low. Needs to ensure `"Foo::"` as a string value (not a bareword)
  is *not* stripped (i.e. only the bareword-literal form).

### Phase 2 — Fix `PPI::Structure::Condition` start-brace drop (RC2)

Investigative steps before coding a fix:
1. Instrument `PPI::Structure::new`, `PPI::Lexer::_lex_structure` and
   `PPI::Lexer::_continues` to log object `refaddr`, `{start}`, and `{finish}`
   at each touch, and confirm whether:
   - the Structure object in `_continues` is the same refaddr as the one
     created in `new`, and
   - `{start}` disappears between construction and `_continues`.
2. If object identity is preserved but `{start}` is gone: it's a Perl-level
   bug — something is deleting or overwriting the hash slot. Trace assignments
   with `Hash::Util::FieldHash` or a tie, or just grep `$self->{start}` and
   `delete .*{start}` across installed PPI.
3. If object identity is lost: it's a PerlOnJava-level bug in hash/blessed-ref
   handling or in `Scalar::Util::weaken` / refaddr.
4. If `weaken` prematurely collects the start brace (unlikely given
   `_PARENT` weak ref, but possible): that points to our `weaken` cooperative
   refcount interacting badly with PPI's hand-rolled weak-parent table.

Fix will depend on what (1)–(4) reveal. Document the resolution back in this
file before closing the phase.

- **Tests**:
  - `./jperl -e 'use PPI; print defined(PPI::Document->new(\"while (1) { last; }")) ? "ok" : PPI::Document->errstr'`
    → `ok`
  - full PPI test run: expect `ppi_statement.t`, `ppi_statement_compound.t`,
    `ppi_token_word.t`, `ppi_token_structure.t`, `ppi_statement_sub.t`,
    `ppi_statement_include.t`, `ppi_statement_package.t`,
    `ppi_statement_variable.t`, `04_element.t`, `05_lexer.t`, `12_location.t`,
    `19_selftesting.t`, `25_increment.t`, `ppi_element.t`,
    `ppi_element_replace.t`, `ppi_lexer.t` to improve dramatically.

### Phase 3 — Re-measure, triage what remains

- Re-run `./jcpan -t PPI` end-to-end, capture a new failure summary into this
  file.
- Classify remaining failures into independent bugs with minimal repros (one
  bullet each).
- Decide which are worth fixing inside PerlOnJava vs. which are inherent
  unimplemented features (signatures, etc.).

### Phase 4 — Signatures / feature_tracking (RC4)

- Only start this after Phases 1–3. Likely feature-gap work in the parser.

## Progress Tracking

### Current Status: Phase 1 done; Phase 2 blocked on cross-cutting refcount work

### Completed Phases
- [x] **Phase 1** (2026-04-20): Fix `Package::->method()` bareword trailing-`::` stripping (RC1).
  - Files changed: `src/main/java/org/perlonjava/backend/jvm/Dereference.java`,
    `src/main/java/org/perlonjava/backend/bytecode/CompileBinaryOperator.java`.
  - Regression test: `src/test/resources/unit/method_call_trailing_colons.t` (5 tests).
  - Verified: `t/ppi_token_whitespace.t` now passes all 6 subtests.
  - `make` (full unit tests) passes.

### Next Steps

1. **Phase 2 is blocked** on a broader refcount-parity investigation. The root
   cause is in PerlOnJava's cooperative refcount (container stores do not
   increment refcount for DESTROY-tracked refs), not in PPI. Needed work,
   in order, as its own branch:
   - Make container-store ops increment refcount consistently: `push`,
     `unshift`, `splice` insertions, hash element store, array element store
     via autoviv paths.
   - Make container-remove ops decrement consistently: `pop`, `shift`,
     `splice` removals, `delete`, `%h = ()`, `@a = ()`, and array/hash
     clearing on reassignment.
   - Fix explicit `return $r` vs implicit return parity — `return $r`
     currently mis-accounts when `$r` was stored into a hash in the same
     sub.
   - Rebalance the existing `unit/refcount/*.t` tests to reflect the
     corrected semantics (every test that currently asserts "destroyed
     when collection releases it" may need to be reviewed).
   - Re-run `./jcpan -t PPI` to confirm RC2 resolves, and re-triage.

2. After Phase 2, re-measure PPI pass rate and pick up RC3 / RC4 (signatures).

### Open Questions
- Should container mutation refcount bookkeeping live inside `push`/`pop`/...
  or on the underlying `elements.add` / `elements.remove` calls in
  `RuntimeArray` / `RuntimeHash` / `RuntimeList`? Doing it at the collection
  level avoids missing paths (shift, splice, tied fallbacks, etc.) but
  touches more code.
- Is the `return $r` bug a separate issue (return copy discipline) or a
  symptom of the same container-store miscount? Needs a separate minimal
  repro without containers.
