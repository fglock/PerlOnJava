# HTML::Element / HTML-Tree 5.07 — fixing `jcpan -t HTML::Element`

## Status

**In progress.** Phases 1, 2, and 3 implemented on
`feature/html-element-fixes` (PR #559). Phase 4 (weak-ref refloop)
is being addressed on a separate branch and is out of scope here.

Before this work: `./jcpan -t HTML::Element` (HTML-Tree 5.07)
fails **10 of 23** upstream test files. After Phases 1–3: **3 of
23** still fail (`refloop.t`, `whitespace.t` — hangs on `\xA0`,
`split.t` — entity-handling diff). Investigation traced the
failures to **four distinct root causes**, three of them in
PerlOnJava itself (parser, `HTML::Parser` shim, `open`) and one in
the weak-ref subsystem.

## Goal

After this work lands:

1. `./jcpan -t HTML::Element` is green on at least 21/23 of the
   upstream test files (the two in Phase 4 may remain pending).
2. The two PerlOnJava bugs uncovered along the way (lazy-closure
   capture missing `continue {}` lexicals; HTML::Parser `tokens`
   argspec returning empty string) are fixed once and for all.
3. Reductions of both bugs land as PerlOnJava unit tests so they
   never regress.
4. `dev/cpan-reports/cpan-compatibility.md` is updated with the
   post-fix HTML-Tree result.

No new Maven dependency.

## Failure mode summary

| # | Phase | Tests affected | Where the bug lives |
|---|-------|----------------|---------------------|
| 1 | Phase 1 | `attributes.t`, `children.t`, `whitespace.t`, partially `refloop.t` | `VariableCollectorVisitor.visit(For3Node)` — missing `continueBlock` traversal |
| 2 | Phase 2 | `comment.t`, `construct_tree.t`, `parse.t`, `parsefile.t`, `split.t` | `HTMLParser.java::buildArgs` — `tokens` / `tokenpos` argspec unimplemented |
| 3 | Phase 3 | `oldparse.t` | 2-arg `open("LITERAL_STRING", $path)` indirect filehandle |
| 4 | Phase 4 | `refloop.t` tests 2/4/6 | weak-ref / DESTROY timing in `-weak` mode |

The 13 already-green test files (`00system.t`, `assubs.t`, `body.t`,
`building.t`, `clonei.t`, `doctype.t`, `escape.t`, `leaktest.t`,
`parents.t`, `subclass.t`, `tag-rendering.t`, `unicode.t`,
`00-all_prereqs.t`) confirm the core DOM and Tree functionality works.
The failures cluster around three orthogonal bugs.

---

## Phase 1 — `For3Node` `continueBlock` in `VariableCollectorVisitor`

### Symptom

```
Global symbol "$nillio" requires explicit package name
  (did you forget to declare "my $nillio"?)
  at HTML/Element.pm line 2023, near ""
```

…fired at runtime when `HTML::Element::look_down` is first called.
`./jperl -c HTML/Element.pm` succeeds; `use HTML::Element` succeeds;
the error only appears when the lazy compiler is forced to compile
the `look_down` body.

### Reduction (12 lines)

```perl
use strict; use warnings;
my $nillio = [];
sub foo {
    my @pile = (1); my @matching; my $this;
    while (defined($this = shift @pile)) { push @matching, $this }
    continue {
        unshift @pile, grep ref($_), @{ [] || $nillio };
    }
    return @matching;
}
foo();
```

Without `sub foo { ... }` (i.e. inlined at file scope) the bug
disappears, because the lazy-closure-capture path is only taken
inside subs.

### Root cause

`SubroutineParser.java:1151-1158` runs a `VariableCollectorVisitor`
over the sub body to determine which outer lexicals to capture
("selective capture optimisation" added to dodge the JVM 255-arg
constructor limit for big subs in modules like `Perl::Tidy`). That
visitor walks `For3Node` (which represents `while`/`until` loops)
**without descending into `continueBlock`**:

```java
// VariableCollectorVisitor.java:171-184
@Override
public void visit(For3Node node) {
    if (node.initialization != null) node.initialization.accept(this);
    if (node.condition      != null) node.condition.accept(this);
    if (node.increment      != null) node.increment.accept(this);
    if (node.body           != null) node.body.accept(this);
    // MISSING: if (node.continueBlock != null) node.continueBlock.accept(this);
}
```

`For1Node` (the `foreach`-style loop) gets it right at lines 165-167.
`BytecodeSizeEstimator.visit(For3Node)` (separate visitor) gets it
right at line 303. Only `VariableCollectorVisitor` is broken.

Consequence: any `my` lexical referenced **only** inside a
`while {} continue { ... }` block of a sub is filtered out of the
captured-variable list. When the lazy compiler later emits the
sub's bytecode, the variable resolves to nothing and the parser
machinery falls through to the "Global symbol …" check in
`Variable.java:382`, with `near ""` because the parser is
re-running over the sub body in a context where the file's outer
`my` declarations are no longer in scope.

### Fix

Single-line addition to `VariableCollectorVisitor.visit(For3Node)`:

```java
if (node.continueBlock != null) {
    node.continueBlock.accept(this);
}
```

### Audit

For each AST node type with sub-blocks, check that *every* visitor
in `frontend/analysis/` and `backend/bytecode/` traverses every
child. Per the grep already done:

- `BytecodeSizeEstimator` — handles For3Node continueBlock (line 303). OK.
- `EmitStatement` — for codegen. Already correct (line 463-465).
- `EmitForeach` — codegen. OK.
- `EmitBlock` — collects state-decl sigil nodes, handles For3 (line 63). OK.
- `BytecodeCompiler` — codegen. Multiple sites, all visit. OK.
- `VariableCollectorVisitor` — broken (this fix).
- `FindDeclarationVisitor` — needs to be checked. If it skips
  continueBlock too, declarations made *inside* a continue block
  could be invisible to the outer scope. Worth a targeted look.

### Test

New unit test under `src/test/resources/unit/closure/continue_block_capture.t`:

```perl
use strict; use warnings; use Test::More tests => 1;
my $captured = [42];
sub trip {
    my @out;
    while (my $x = shift) { push @out, $x }
    continue {
        push @out, @{ $captured };
    }
    return @out;
}
is_deeply([trip(1)], [1, 42], 'continue block captures outer my');
```

This will live in the existing closure test directory if there is
one, otherwise as a new file.

### Estimated size

~3 lines of production code + audit of `FindDeclarationVisitor` +
1 unit test. Single commit.

---

## Phase 2 — `HTML::Parser` `tokens` argspec returns empty string

### Symptom

```
Can't use string ("") as an ARRAY ref while "strict refs" in use
  at HTML/Parser.pm line 47.
```

Hits everything that parses HTML containing comments, declarations,
or unusual constructs — five upstream test files.

### Root cause

PerlOnJava ships its own `HTML::Parser` Java shim
(`HTMLParser.java`). The shim's default callback installer
(`HTML/Parser.pm` line 41-47) is taken straight from CPAN
`HTML::Parser` and registers a comment handler with argspec
`"self,tokens"`:

```perl
$self->handler(comment => sub {
    my ($self, $tokens) = @_;
    for (@$tokens) { $self->comment($_) }
}, "self,tokens");
```

`HTMLParser.java::buildArgs` (`switch (token)` at line 557) handles
`tagname`/`tag`/`attr`/`attrseq`/`text`/`dtext`/`is_cdata`/`self`/
`event`/`offset`/`offset_end`/`length`/`line`/`column`/`token0`,
plus quoted-string literals. It does **not** handle:

- `tokens`        — array ref of all tokens for this event
- `tokenpos`      — array ref of `[start, end]` byte offsets per token
- `token1`…`tokenN` — Nth token (sibling of the existing `token0`)

For everything in that "missing" list, the default branch at line 696
silently emits an empty-string scalar.

### Required behaviour (per `perldoc HTML::Parser`)

| Event           | `tokens` value                                                            |
|-----------------|---------------------------------------------------------------------------|
| `start`         | `[ tagname, attr1, val1, attr2, val2, ... ]`                              |
| `end`           | `[ tagname ]`                                                             |
| `text`          | `[ text ]`                                                                |
| `comment`       | `[ comment_body ]`  (one per `<!-- ... -->`; in non-strict mode multiple comments may share a single event) |
| `declaration`   | `[ token1, token2, ... ]` (per SGML declaration)                          |
| `process`       | `[ pi_body ]`                                                             |
| `default`       | `[ text ]`                                                                |
| `start_document`/`end_document` | not applicable (no tokens)                                |

`token0` is just `tokens->[0]`; `tokenN` is `tokens->[N]`. `tokenpos`
parallels `tokens` with byte-offset pairs; if the parser doesn't
track byte offsets, returning a same-length array of `[0,0]` pairs
(or `undef`) is acceptable for round-trip code, and matches what the
upstream module does when the offsets weren't requested at parse
time.

### Fix

In `HTMLParser.java::buildArgs`:

1. Add `case "tokens":` building an arrayref from `eventArgs`,
   shape depending on `eventName` per the table above. For `start`,
   the existing internal representation is
   `eventArgs = [tagname, attr_hashref, attrseq_arrayref, original_text]`,
   so flatten `attrseq` against `attr` to produce `[tag, k1, v1, k2, v2, …]`.
   For `comment`, push `eventArgs[0]` into a fresh arrayref. For
   `text`/`process`/`declaration`, single-element arrayref of the body.
   For `end`, single-element arrayref of the tagname.
2. Add `case "tokenpos":` returning a matching-length arrayref of
   `[0,0]` pairs (since byte-offset tracking is already a TODO at
   line 644). This satisfies callers that just iterate the array.
3. Replace the special-cased `case "token0":` with a general
   regex match for `tokenN` where N is `\d+`, returning the Nth
   element of the same array `tokens` would produce, or empty
   string for out-of-range. Keep `token0`'s existing process-event
   special case as a fast path / for compatibility.

### Test

A new unit test under `src/test/resources/unit/html_parser/tokens_argspec.t`
covering `comment` (the path that breaks HTML-Tree), `start`,
and `end` events with explicit `tokens` argspec; checks the
shape of the resulting array refs.

Plus, after this fix, the upstream `HTML-Tree` `t/comment.t`
should pass; track that as the integration check.

### Estimated size

~50 LOC Java + 1 unit test. Single commit.

---

## Phase 3 — 2-arg `open("LITERAL_STRING", $path)`

### Symptom

```
Modification of a read-only value attempted
  main at t/oldparse.t line 18
```

Source:

```perl
open( "INFILE", "$TestInput" ) || die "$!";
binmode INFILE;
$HTML = <INFILE>;
```

This is the antique 2-arg `open` form where the first argument is a
string naming a typeglob to autovivify (`*main::INFILE`). Real
perl handles the quoted form by looking up the symbol of the string.
PerlOnJava is treating `"INFILE"` as a constant scalar and trying to
assign the new filehandle into it, hence the read-only error.

### Reduction

```perl
open("FH", "<", "/tmp/x") or die $!;     # 3-arg variant
open("FH", "/tmp/x")     or die $!;     # 2-arg variant (oldparse.t case)
```

Bareword `open(FH, ...)` works today; the literal-string variants are
the broken ones.

### Fix sketch

In `OperatorOpen.java` (or wherever `open` codegen lives — to be
located as Phase 3 starts), at the point where the first argument is
classified, detect the case where the first argument is a constant
string literal (either `StringNode` or `ListNode` containing one
`StringNode`) and route it to the same path as a bareword filehandle.
The string value becomes the typeglob name; package qualification
follows the same rules as bareword (current package unless
already qualified with `::`).

This pattern shows up in other old Perl code too — `IO::Handle->new`
sometimes constructs a string filehandle name programmatically — so
the fix has wider value than just `oldparse.t`.

### Risk

Care needed not to break the modern 3-arg form
`open(my $fh, "<", $path)` where the first arg is a `my $fh`
declaration (lvalue). The classification has to be:

1. Bareword → typeglob lookup (already works).
2. Constant string → typeglob lookup (this fix).
3. Lvalue scalar (incl. `my`) → autovivify a new filehandle (already works).
4. Existing scalar value → use as filehandle ref or coerce.

### Estimated size

Unknown until the codegen site is read. Budget ~30-80 LOC + tests.
Single commit. Lower priority than Phase 1/2 because it only
unblocks one (cosmetically odd) test file.

---

## Phase 4 — `-weak` mode of `HTML::TreeBuilder`: object_count > 0 after $tree = undef

> **Out of scope for this PR.** weaken/DESTROY work is being done on
> a separate branch; this section is plan-only and **must not** be
> implemented here.


### Symptom

`t/refloop.t` tests 2, 4, 6:

```perl
my $tree = HTML::TreeBuilder->new_from_content('&amp;foo; &bar;');
ok(object_count() > 0);     # passes
$tree = undef;
is(object_count(), 0);      # FAIL: count stays > 0
```

`HTML::TreeBuilder->new(-weak => 1)` arranges for parent→child links
to be strong and child→parent links to be weak, so that dropping the
root drops the whole tree. PerlOnJava's `weaken`/`DESTROY`
implementation (per `dev/architecture/weaken-destroy.md`) is
documented as deterministic for blessed objects, but here it isn't
zeroing the live count.

### Plan

Treat as a separate investigation. Two likely culprits:

1. `HTML::Tree`'s `-weak` mode reaches into `Scalar::Util::weaken`
   on element references stored inside the parser state, and our
   implementation doesn't catch all the storage paths.
2. The objects are being destroyed but `object_count`'s
   `grep { defined }` over `@OBJECTS` doesn't see undef'd weak
   refs because we don't actually clear the slot when the
   referent dies.

Both should be reproducible without HTML at all using `weaken` +
manual DESTROY counters. Defer until Phases 1-3 are landed; the
investigation belongs in `dev/architecture/weaken-destroy.md` (or a
sibling) rather than here.

### Estimated size

Unknown. Possibly trivial (one-line in `weaken`'s clear-on-destroy)
or moderately invasive (refcount cycle detector tweak). To be
sized after reproduction.

---

## Bundling vs. fixing PerlOnJava

`HTML::Parser` is already shipped in PerlOnJava (`HTMLParser.java` +
`src/main/perl/lib/HTML/Parser.pm`). `HTML::TreeBuilder` /
`HTML::Element` come from CPAN via `jcpan`. There is **no plan to
bundle HTML-Tree** — its pure-Perl implementation works fine once
the four bugs above are fixed; bundling would just couple our
release cycle to CPAN's.

The work here is:

- Fix three PerlOnJava bugs that HTML-Tree happens to exercise.
- (Phase 4) Investigate one more.
- Add the new fixes to the cpan-compatibility report once landed.

---

## Progress Tracking

### Current Status: Phases 1, 2, 3 implemented on `feature/html-element-fixes`.

### Completed Phases
- [x] Phase 1 (2026-04-25): `For3Node.continueBlock` traversal in
      `VariableCollectorVisitor`. Fixed `attributes.t`, `children.t`,
      and unblocked `whitespace.t`/`refloop.t` to run further.
      Files: `VariableCollectorVisitor.java`,
      `src/test/resources/unit/closure.t`.
- [x] Phase 2 (2026-04-25): `tokens`/`tokenN`/`tokenpos` argspecs
      in `HTMLParser.java::buildArgs`. Fixed `comment.t`,
      `construct_tree.t`, `parse.t`, `parsefile.t`; nearly all of
      `split.t`. Files: `HTMLParser.java`,
      `src/test/resources/unit/html_parser_tokens.t`.
- [x] Phase 3 (2026-04-25): literal-string filehandle in
      `open("FH", ...)` — `handleTypeGlobArgument` now treats a
      `StringNode` whose value is a valid identifier the same as a
      bareword. Fixed `oldparse.t` (16/16). Files:
      `PrototypeArgs.java`,
      `src/test/resources/unit/open_string_filehandle.t`.

### Next Steps
1. Land the WIP PR (#559) once review is complete.
2. Phase 4 (`-weak` refloop) is being implemented on a separate
   branch — do **not** address here.
3. Two HTML-Tree-only follow-ups to triage in their own PRs:
   `whitespace.t` hang on `\xA0` input, and `split.t` entity diff.

### Blockers / risks
- `FindDeclarationVisitor` audit (Phase 1) might surface a related
  bug needing its own commit.
- Phase 2's `tokens` reshaping for `start` events depends on the
  exact internal `eventArgs` layout; need to confirm by reading the
  call sites in `HTMLParser.java::fireEvent`.
- Phase 4 may transitively depend on parts of the weak-ref system
  that aren't yet documented; budget a separate investigation
  phase.

## References

- Upstream source (CPAN cache):
  `~/.cpan/build/HTML-Tree-5.07-34/`
  - `lib/HTML/Element.pm` (line 74 declares `$nillio`, line 2023
    references it inside `continue {}`)
  - `t/comment.t`, `t/parse.t`, etc.
- PerlOnJava sources to touch:
  - `src/main/java/org/perlonjava/backend/bytecode/VariableCollectorVisitor.java`
  - `src/main/java/org/perlonjava/runtime/perlmodule/HTMLParser.java`
  - `src/main/perl/lib/HTML/Parser.pm` (probably untouched, but
    the symptom message points there)
- Related PerlOnJava docs:
  - `src/main/java/org/perlonjava/frontend/parser/SubroutineParser.java:1151`
    (selective capture optimisation, the consumer of the visitor)
  - `dev/architecture/weaken-destroy.md` (Phase 4 starts here)
- Skill: `.agents/skills/debug-perlonjava/SKILL.md`
- Background investigation: chat-session output of
  `./jcpan -t HTML::Element` on master @ 2c57f0469.
