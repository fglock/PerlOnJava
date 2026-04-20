# List::MoreUtils — Fix Plan

Target: make every subtest in `./jcpan -t List::MoreUtils` (v0.430) pass.

Initial run (master): `Files=61, Tests=4492` — 7 failing subtests across 8 test files.

```
t/pureperl/binsert.t   Failed test 19   (is_dying)
t/pureperl/bremove.t   Failed test 415  (is_dying)
t/pureperl/indexes.t   Failed test 18   (is_dying)
t/pureperl/mesh.t      Failed test 7    (is_dying)
t/pureperl/zip6.t      Failed test 5    (is_dying)
t/pureperl/mode.t      Failed tests 2, 4 (lorem mode)
t/pureperl/minmaxstr.t aborts at line 14: Undefined subroutine &POSIX::setlocale
t/pureperl/part.t      aborts at line 84: Global symbol "@long_list" requires explicit package name
```

## Root causes

All failures are PerlOnJava bugs (the test code is correct and the module's XS/PP code is pristine). There are four distinct root causes, each mapping to one or more failing tests.

### RC1 — Strict-refs not enforced on numeric-valued scalars

`RuntimeScalar.arrayDeref()` in `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeScalar.java` returns an empty `RuntimeArray` silently when `type == INTEGER` or `DOUBLE`. That branch was added to keep `1->[0]` quiet for literal constants, but it is also hit for plain scalar variables that happen to hold a number.

```perl
use strict;
my $x = 1;
my @a = @$x;    # perl: dies "Can't use string (\"1\") as an ARRAY ref"
                # jperl: silently returns ()
my $m = $#$x;   # perl: dies with same message
                # jperl: returns -1
```

The same applies symmetrically to `hashDeref()` — it throws `Not a HASH reference` instead of the strict-refs error.

Literal constants like `1->[0]` are handled by `RuntimeScalarReadOnly` which overrides `arrayDeref()` / `hashDeref()`, so fixing the base-class method only affects non-readonly values.

Affected tests (all call something like `&foo(42, ...)` with the `&` prefix disabling the prototype so the number reaches `@$_` / `$#$_` / `$_->()` inside the PP implementation):

- `binsert.t` test 19 — `&binsert(42, @even)` → `lower_bound` → `$_[0]->()` (numeric used as CODE ref)
- `bremove.t` test 415 — `&bremove(42, ...)` → same
- `indexes.t` test 18 — `&indexes(42, 4711)` → `$test->()` with numeric
- `mesh.t` test 7 — `&mesh(1, 2)` → `$#$_`
- `zip6.t` test 5 — `&zip6(1, 2)` → `$#$_`

Parallel defect in `scalarDeref()` / code-ref deref: `my $x = 42; $x->();` currently throws `Not a CODE reference` (`my $x = "42"` → `Undefined subroutine &main::42`) where Perl says:

```
Can't use string ("42") as a subroutine ref while "strict refs" in use
```

We should align message and dying behaviour so the `is_dying()` assertions accept the result and so errors from numeric scalars also die. Five of the seven failing subtests collapse into this one fix once the numeric path dies correctly (even with a slightly different message, `is_dying` only checks for any exception).

### RC2 — `my @var = ... for LIST;` not visible in enclosing block

`part.t` line 83:

```perl
my @long_list = int rand(1000) for 0 .. 1E7;
my @part      = part { $_ == 1E7 and die "Too much!"; ($_ % 10) * 2 } @long_list;
```

In real Perl, a `my` declared inside a statement-modifier loop is visible for the rest of the enclosing block (the `for` modifier re-executes the whole statement, but the `my` declaration still belongs to the surrounding scope). PerlOnJava's parser scopes the `my` only to the modifier's statement, so the second line sees an undeclared `@long_list` and `use strict` bails out at compile time.

### RC3 — `POSIX::setlocale` / `POSIX::localeconv` are declared but not implemented

`src/main/perl/lib/POSIX.pm` exports `setlocale`, `localeconv`, `LC_ALL`, `LC_COLLATE`, … but there is no implementation (neither a Perl `sub` in POSIX.pm nor a Java binding in `org/perlonjava/perlmodule/Posix.java`). Any call raises `Undefined subroutine &POSIX::setlocale`.

Affected test: `minmaxstr.t` (aborts at line 14).

### RC4 — `split /(?:\b|\s)/` leaves whitespace inside fields

```perl
split /(?:\b|\s)/, "Lorem ipsum,";
# perl:  ("Lorem", "",    "ipsum", ",")
# jperl: ("Lorem", " ",   "ipsum", ",")
```

When the split pattern is an alternation of a zero-width assertion (`\b`) and a consuming class (`\s`), jperl's split consumes the zero-width match, advances one position, then re-matches the next `\b` past the whitespace, so the space ends up inside a field instead of being treated as a separator. Real perl advances past both the zero-width match and the following consuming match.

Affected test: `mode.t` tests 2 and 4 (the Lorem sample builds a 720-ish word list via `split /(?:\b|\s)/`; expected mode is `(106, ',')`; we get `(11, …)`).

## Implementation plan

Work in branch `fix/list-moreutils`. Commit each root cause separately so a regression can be bisected.

### Step 1 — RC1: strict refs on numeric scalar dereference

Files:

- `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeScalar.java`
  - `arrayDeref()`: change `INTEGER` / `DOUBLE` arms to throw `Can't use string ("<value>") as an ARRAY ref while "strict refs" in use`.
  - `hashDeref()`: change `INTEGER` / `DOUBLE` arms to throw `Can't use string ("<value>") as a HASH ref while "strict refs" in use`.
  - Code-ref path (`codeDeref` / CODE invocation on non-reference scalar): unify the error message to the strict-refs form for `INTEGER` / `DOUBLE` / `STRING` / `BYTE_STRING`.
- `RuntimeScalarReadOnly` already overrides `arrayDeref()` and `hashDeref()` to return empty / throw `Not a HASH reference`, so `1->[0]` and `1->{a}` keep their current silent behaviour.

Verification:

```
./jperl -e 'use strict; my $x=1; my @a=@$x'         # must die
./jperl -e 'use strict; my $x=1; my %h=%$x'         # must die
./jperl -e 'use strict; my $x=1; $x->()'            # must die
./jperl -e 'print 1->[0], 1->{a}'                   # must remain silent
./jperl -e 'my $x=1; my @a=@$x'                     # no strict, still works (non-strict path)
./jperl t/op/* ...                                   # make test-unit
```

Expected to flip green: `binsert.t`, `bremove.t`, `indexes.t`, `mesh.t`, `zip6.t`.

### Step 2 — RC3: POSIX::setlocale / POSIX::localeconv / LC_* constants

Scope: minimal, enough to satisfy `minmaxstr.t` and any similar locale-using tests. We do not need a real locale; `setlocale(LC_COLLATE, "C")` must return a truthy string (`"C"`) and not raise.

Plan:

- Add stubs to `src/main/perl/lib/POSIX.pm`:
  - `sub setlocale { my ($cat, $loc) = @_; defined $loc ? $loc : 'C' }`
  - `sub localeconv { +{ decimal_point => '.', thousands_sep => ',' , grouping => '' } }`
  - `sub LC_ALL      () { 0 }`
  - `sub LC_COLLATE  () { 1 }`
  - `sub LC_CTYPE    () { 2 }`
  - `sub LC_MONETARY () { 3 }`
  - `sub LC_NUMERIC  () { 4 }`
  - `sub LC_TIME     () { 5 }`
  - `sub LC_MESSAGES () { 6 }`
  - Values only matter as unique integers; they're opaque category handles to callers.
- Document in `docs/FEATURE_MATRIX.md` that locale support is stubbed (no real locale switching).

Verification:

```
./jperl -MPOSIX=setlocale,LC_COLLATE -e 'print setlocale(LC_COLLATE, "C"), "\n"'
```

### Step 3 — RC2: `my` in statement-modifier loop

The fix is in the parser: when a statement is followed by a `for` / `foreach` / `while` / `until` / `if` / `unless` modifier, any `my` inside the statement body still belongs to the enclosing block, not a new scope introduced by the modifier.

Plan:

1. Find the statement-modifier transformation site in `org.perlonjava.parser` (likely `StatementParser`). It rewrites `EXPR for LIST;` into a `for`-loop AST.
2. Ensure the `my` variable's `OperatorNode("my", ...)` keeps its original lexical scope (the enclosing block), not the new synthetic for-loop body. Concretely: do not wrap the `EXPR` in a new block; or if a block wrapper is introduced, mark `my` declarations as hoisted to the enclosing scope.
3. Diff against a minimal reproducer:
   ```perl
   use strict;
   my @x = 1 for 1..3;
   print scalar @x, "\n";   # must print 1, not error
   ```

Verification: `./jperl` runs the reproducer, `part.t` gets past line 84, full `make test-unit` still passes.

Risk: this touches a very hot parser path. Add a focused unit test under `src/test/resources/unit/` covering `my`/`our`/`local` with each modifier (`for`, `foreach`, `while`, `until`, `if`, `unless`).

### Step 4 — RC4: split with `\b` alternation

Location: `org.perlonjava.operators.RegexOperators.split` (or wherever `split` is dispatched). The regex engine itself is fine for `m//g`; the problem is split-specific handling of zero-width matches mixed with consuming matches.

Plan:

1. Write a minimal reproducer comparison harness:
   ```perl
   for my $s ("Lorem ipsum,", "a b c", "foo-bar") {
       my @a = split /(?:\b|\s)/, $s;
       print join("|", map "[$_]", @a), "\n";
   }
   ```
   Capture expected output from system perl.
2. Identify how PerlOnJava's split loop advances `pos` after a match. The bug signature is: after a zero-width match at position P, the next search must begin at P+1 **without** re-matching a zero-width assertion at P+1 that overlaps the just-skipped character; if such a match happens, the consumed character between P and P+1 must still be treated as a separator (emit empty field) rather than ending up inside the next field.
3. The usual Perl semantic is: after a zero-width match at P, try again at P+1 with the constraint that a zero-width match at P+1 is allowed, *but* the text between P and the next match start is the next field's contents. In perl, when the next match at P+1 is also zero-width, the field between is empty. The bug is probably that PerlOnJava emits a field of length 1 (the skipped char) instead of length 0.
4. Fix the split loop's field-boundary computation.

Verification:

- Small harness matches system perl for a set of cases (including `split / /`, `split /\s/`, `split /\b/`, `split /(?:\b|\s)/`, `split //`, `split /(\s)/` with capture).
- `mode.t` passes.
- `make test-unit` still green.

### Step 5 — Re-run the full distribution tests

```
./jcpan -t List::MoreUtils
```

All 4492 subtests must pass. Rerun `make` to ensure no unit-test regressions.

## Progress tracking

### Current status

`./jcpan -t List::MoreUtils` goes from 7 failing subtests (8 test files) on master down to **1 failing subtest (`indexes.t` test 18) in 1 test file**, and that remaining failure is deferred to the parallel weaken branch (see RC6 below).

### Completed phases

- [x] **RC1** — numeric scalar strict-refs (2026-04-20) — commit `db94a5ae1`
  - `RuntimeScalar.arrayDeref()` / `hashDeref()` now throw `Can't use string ("N") as an ARRAY|HASH ref while "strict refs" in use` for `INTEGER` / `DOUBLE`.
  - `RuntimeScalarReadOnly` gains the same behavior for read-only scalars holding numeric values, while keeping `1->[0]` / `1->{a}` silent via new `arrayDerefGet` / `hashDerefGet` overrides.
  - Fixes: `binsert.t`, `bremove.t`, `mesh.t`, `zip6.t` (4 tests).
- [x] **RC3** — POSIX stubs (2026-04-20) — commit `a161fa284`
  - Adds `setlocale`, `localeconv`, `LC_ALL` / `LC_COLLATE` / `LC_CTYPE` / `LC_MONETARY` / `LC_NUMERIC` / `LC_TIME` / `LC_MESSAGES` as Perl stubs in `src/main/perl/lib/POSIX.pm`.
  - Fixes: `minmaxstr.t`.
- [x] **RC2** — `my` hoist in statement-modifier loops (2026-04-20) — commit `3bfaffda3`
  - `StatementResolver.parseStatement` for `for` / `foreach` / `while` / `until` now detects `my DECL = RHS for LIST` / `my DECL = RHS while COND` and emits a bare `my DECL;` in the enclosing scope, wrapping the loop body in a BlockNode for `while`/`until` so the inner `my` shadows the outer on each iteration (matching perl: the outer variable stays empty/undef).
  - Fixes: compile-time `Global symbol @long_list …` error in `part.t`.
- [x] **RC4** — split with zero-width vs consuming alternation (2026-04-20) — commit `c9b8e05dd`
  - `Operator.split` now probes each zero-width match via `Matcher.matches()` on progressively larger regions. When a consuming alternative also matches at the same offset, an extra empty field is emitted between the two separators and the consumed characters are skipped, matching perl's `REG_NOTEMPTY_ATSTART` retry loop.
  - Fixes: `mode.t` tests 2 and 4.
- [x] **RC5** — undef-as-subscript warning (2026-04-20) — commit `96c4f92d5`
  - `RuntimeArray.get` / `RuntimeHash.get` now emit `Use of uninitialized value in array|hash element` (category `uninitialized`) when called with an `UNDEF` index. This was exposed after RC2 unblocked the later leak-free tests in `part.t`.
  - Fixes: `part.t` tests 12 and 13.

### Deferred

- **RC6 — `Scalar::Util::weaken` on a reference to a temporary** (`indexes.t` test 18). The test does
  ```perl
  my $ref = \(indexes(sub { 1 }, 123));
  Scalar::Util::weaken($ref);
  is($ref, undef, "weakened away");
  ```
  In real perl the temporary returned by `indexes(...)` has a refcount of 1 held by `$ref`; weakening that ref drops the refcount to 0 and the temporary is freed, so `$ref` becomes undef. PerlOnJava's cooperative-refcount overlay (see `dev/architecture/weaken-destroy.md`) only tracks objects blessed into a class with `DESTROY`. For an unblessed numeric scalar like this one, weaken transitions it to `WEAKLY_TRACKED` but does not clear the weak ref at scope exit because we can't distinguish "last strong ref was this one" from "symbol table still holds a ref" without full refcounting. This is a known architectural limitation being addressed on a separate branch; this PR does not touch it.

### Final summary

- `binsert.t` ok
- `bremove.t` ok
- `mesh.t` ok
- `zip6.t` ok
- `minmaxstr.t` ok
- `mode.t` ok
- `part.t` ok
- `indexes.t` — 1 subtest still fails (RC6, deferred to weaken branch)

Run `./jcpan -t List::MoreUtils` and observe: `Files=61, Tests=4533. Failed 1/61 test programs. 1/4533 subtests failed.` (was 8 / 7 before this branch).

### Open questions
- None open for RC1–RC5.
- RC6 is tracked on the separate weaken branch.

