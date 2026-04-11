# Type::Tiny Support for PerlOnJava

## Overview

Type::Tiny 2.010001 is a widely-used Perl type constraint library used by Moo, Moose,
and many CPAN modules. This document tracks the work needed to make
`./jcpan --jobs 8 -t Type::Tiny` pass its test suite on PerlOnJava.

## Current Status

**Branch:** `feature/type-tiny-phase6`
**Module version:** Type::Tiny 2.010001 (375 test programs)
**Pass rate:** 99.7% (3029/3038 individual tests, 5 files with real failures)
**Phase:** 6 complete (2026-04-10)

### Baseline Results

The test run was cut short (SIGPIPE) but enough data was captured to identify
all major failure categories. From the partial output (~120 test programs observed):

| Category | Programs | Key Error |
|----------|----------|-----------|
| Passing | ~45 | — |
| Skipped (missing optional deps) | ~15 | Moose, Mouse, namespace::clean, etc. |
| Failing | ~60+ | See categorized issues below |

**Note:** The skipped tests are expected — Type::Tiny's only hard runtime dependency
is `Exporter::Tiny` (installed, v1.006003). Modules like Moose, Mouse, Return::Type,
etc. are optional (`suggests`/`recommends`) and tests skip gracefully without them.

---

## Categorized Issues

### Issue 1: `looks_like_number` only checks internal type (HIGH IMPACT)

**Symptom:**
```
Value "1.1" did not pass type constraint "LaxNum" (in $_[0])
```

**Reproduction:**
```perl
use Scalar::Util qw(looks_like_number);
print looks_like_number("1.1");  # PerlOnJava: "" (false!)  Perl: 1
print looks_like_number("42");   # PerlOnJava: "" (false!)  Perl: 1
print looks_like_number(1.1);    # PerlOnJava: 1            Perl: 1
```

**Root cause:** `ScalarUtil.java` `looks_like_number()` only checks if the
RuntimeScalar's internal type is INTEGER or DOUBLE. It does NOT parse strings
to see if they look like numbers. All string arguments return false.

**File:** `src/main/java/org/perlonjava/runtime/perlmodule/ScalarUtil.java` line 259

**Fix:** Parse the string value using a regex or numeric parsing logic matching
Perl's `looks_like_number` semantics (integers, floats, scientific notation,
leading/trailing whitespace, infinity, NaN, hex with `0x` prefix, binary `0b`,
octal `0` prefix).

**Tests affected:** positional.t, noninline.t, and many t/21-types/ tests
that use inline type checks with `Scalar::Util::looks_like_number()`.

---

### Issue 2: `my` scoping in `for` statement modifier (HIGH IMPACT — ~40+ tests)

**Symptom:**
```
Global symbol "$s" requires explicit package name (did you forget to declare "my $s"?)
  at Type/Tiny.pm line 610
```

**Reproduction:**
```perl
sub test {
    defined && s/[\x00-\x1F]//smg for ( my ( $s, @a ) = @_ );
    print "s=$s\n";   # PerlOnJava: s=  (empty!)   Perl: s=hello
}
test("hello", "world");
```

**Root cause:** `for (my ($s, @a) = @_)` should declare `$s` and `@a` in the
enclosing block scope. PerlOnJava scopes them only within the `for` loop's
iteration scope, so they're undefined on the next line.

This is in `_build_name_generator` (Type/Tiny.pm line 609-611):
```perl
defined && s/[\x00-\x1F]//smg for ( my ( $s, @a ) = @_ );
sprintf( '%s[%s]', $s, join q[,], ... );
```

**Fix:** In the parser/compiler, ensure that `my` declarations inside a `for`
statement modifier's list expression create variables scoped to the enclosing
block, not the `for` loop body.

**Tests affected:** This is the **single biggest blocker** — it causes a cascade
compilation failure in Type::Tiny.pm that breaks ~40+ test programs across all
categories (Type-Library, Type-Params, Type-Coercion, Type-Tiny, Types-Standard,
Types-Common, etc.). Many tests that "All subtests passed" still fail because
this error fires during done_testing/cleanup.

---

### Issue 3: Prototype `;$` with `|` infix operator (MEDIUM IMPACT — ~5+ tests)

**Symptom:**
```
syntax error at ... near "HashRef;"
```

**Reproduction:**
```perl
sub Foo (;$) { return $_[0] // "default" }
sub Bar (;$) { return $_[0] // "default" }
my $x = Foo | Bar;   # syntax error!
# Workaround: my $x = Foo() | Bar();  # works
```

**Root cause:** PerlOnJava's parser treats `|` after a `;$`-prototyped function
call as part of the argument to the function, rather than as an infix operator.
Since `|` cannot start an expression, the parser should recognize the function
was called with 0 args and parse `|` as infix bitwise-or.

**Tests affected:** 03-leak.t (uses `ArrayRef | HashRef`), arithmetic.t,
Type-Tiny-Intersection/cmp.t, and any test using the `Type1 | Type2` union syntax.

---

### Issue 4: `\my $x = \$y` alias assignment (LOW IMPACT — 2 tests)

**Symptom:**
```
Assignment to unsupported operator: \ at (eval 9) line 3
```

**Root cause:** Perl's native aliasing syntax (`\$alias = \$original`) is not
implemented in PerlOnJava. This is used by Eval::TypeTiny for the "native aliases"
code path.

**Tests affected:** aliases-native.t, Eval-TypeTiny/basic.t

**Note:** Type::Tiny gracefully falls back to tie-based or non-alias behavior
when this feature is unavailable, so fixing this is lower priority.

---

### Issue 5: TIESCALAR in string eval (LOW IMPACT — 1 test)

**Symptom:**
```
Can't locate object method "TIESCALAR" via package "Eval::TypeTiny::_TieScalar"
```

**Root cause:** The `Eval::TypeTiny::_TieScalar` package defines its TIESCALAR
method, but when used inside a string eval, PerlOnJava can't find it. This may
be a package visibility issue in eval context.

**Tests affected:** aliases-tie.t

---

### Issue 6: `builtin::export_lexically` not implemented (LOW IMPACT — 2 tests)

**Symptom:**
```
Undefined subroutine &builtin::export_lexically called
```

**Root cause:** `builtin::export_lexically` is a Perl 5.37.2+ feature that
Exporter::Tiny uses for lexical exports. Not yet implemented in PerlOnJava.

**Tests affected:** Type-Registry/lexical.t, Type-Tiny-Enum/exporter_lexical.t

---

### Issue 7: `Function 'X' not found to wrap!` (MEDIUM IMPACT — ~5 tests)

**Symptom:**
```
Function 'test1' not found to wrap! at .../v2-allowdash.t line 32
```

**Root cause:** Type::Params v2's `wrap_subs` feature looks up functions by name
in the caller's symbol table using `\&{"$pkg::$name"}`. The function may not be
visible yet at the time the wrap is attempted. This could be a compile-time vs
runtime ordering issue, or a problem with how PerlOnJava populates the symbol table
for forward-declared subs.

**Tests affected:** v2-allowdash.t, v2-listtonamed.t, v2-positional.t,
v2-positional-backcompat.t, v2-named-backcompat.t

---

### Issue 8: `matchfor(qr//)` comparison failures (LOW IMPACT — 1 test)

**Symptom:** Test::TypeTiny's `matchfor` fails when comparing with `qr//` objects.
The test expects regex matching but gets string comparison instead.

**Tests affected:** Test-TypeTiny/matchfor.t (3/6 subtests)

---

### Issue 9: Type::Tie failures (LOW IMPACT — ~4 tests)

**Symptom:** Various tied variable issues:
- STORE not triggering type checks after tie
- clone/Storable roundtrip losing tie magic
- `Can't use string ("Type::Tiny") as a HASH ref`

**Tests affected:** 01basic.t (2/17), 06clone.t (3/6), 06storable.t (3/6),
basic.t (1/1), 03prototypicalweirdness.t

---

### Issue 10: Overloaded `[]` operator / `_HalfOp` (LOW IMPACT — 3 tests)

**Symptom:** Type::Tiny::_HalfOp tests fail — double-union, extra-params,
overload-precedence each fail 1/7.

**Tests affected:** 3 _HalfOp test files (1 subtest each, rest skipped)

---

### Issue 11: Enum sorter ordering (LOW IMPACT — 1 test)

**Symptom:** `Type::Tiny::Enum->sorter` returns wrong order.

**Tests affected:** Type-Tiny-Enum/sorter.t

---

### Issue 12: Lexical sub closure (LOW IMPACT — 1 test)

**Symptom:** Closure over lexical sub doesn't capture the correct value.
```
got: 'quuux'
expected: '42'
```

**Tests affected:** Eval-TypeTiny/lexical-subs.t (1/12)

---

### Issue 13: Type::Library exportables `+Type` syntax (LOW IMPACT — 1 test)

**Symptom:** `Could not find sub '+Rainbow' exported by My::Types`

**Tests affected:** Type-Library/exportables.t

---

### Issue 14: `Type::Utils` `isa` on undef (LOW IMPACT — 1 test)

**Symptom:** `Can't call method "isa" on an undefined value at Type/Utils.pm line 159`

**Tests affected:** Type-Tiny-Enum/basic.t

---

## Fix Priority

### Phase 1: `looks_like_number` string parsing (HIGH — easy fix, big impact)

Fix `ScalarUtil.java` to parse string values as numbers. This unblocks all
type constraint validation for string inputs and fixes the LaxNum/Num/Int/etc.
inline checks used throughout the test suite.

### Phase 2: `my` scoping in `for` statement modifier (HIGH — biggest blocker)

Fix the parser/compiler to scope `my` declarations from `for (EXPR)` into the
enclosing block. This single fix should unblock ~40+ test programs that currently
fail due to the Type::Tiny.pm line 610 cascade error.

### Phase 3: Prototype `;$` with `|` infix (MEDIUM — parser fix)

Fix parsing of `Func | Func` when Func has `;$` prototype. This unblocks
type union syntax (`ArrayRef | HashRef`) and parameterized types.

### Phase 4: `Function not found to wrap!` investigation (MEDIUM)

Investigate why Type::Params v2 can't find functions in the caller's stash.
May require fixes to symbol table population timing.

### Phase 5: Remaining issues (LOW — incremental)

Address remaining issues (alias assignment, TIESCALAR in eval, matchfor,
Type::Tie, _HalfOp overloading, etc.) as time permits.

### Not planned (known limitations)

- **`builtin::export_lexically`** — Perl 5.37+ feature, out of scope
- **Moose/Mouse integration tests** — Optional deps not installed, tests skip

---

## Progress Tracking

### Current Status: Phase 6 completed — 99.7% pass rate (3029/3038)

### Results History

| Phase | Files Passed | Files Failed | Tests OK | Tests Total | Pass Rate |
|-------|-------------|-------------|----------|-------------|-----------|
| Baseline | ~45 | ~60+ | — | — | — |
| Phase 4 | 186 | 57 | — | — | — |
| Phase 5a | 318 | 13 | 2812 | 2869 | 98.0% |
| Phase 5b | 331 | 10 | 2879 | 2907 | 99.0% |
| Phase 6 | 347 | 5 | 3029 | 3038 | 99.7% |

### Completed Phases
- [x] Phase 1: `looks_like_number` string parsing (2026-04-09)
  - Fixed `ScalarUtil.java` to parse string content for numeric patterns
  - File: `src/main/java/org/perlonjava/runtime/perlmodule/ScalarUtil.java`
- [x] Phase 2: `my` scoping in `for` statement modifier (2026-04-09)
  - Fixed parser to unwrap single-element ListNode before checking for my-assignment
  - File: `src/main/java/org/perlonjava/frontend/parser/StatementResolver.java`
- [x] Phase 3: Prototype `;$` with `|` infix operator (2026-04-09)
  - Added binary-only infix operators (`|`, `^`, `==`, `!=`, `>`, `>=`,
    `..`, `...`, `=~`, `!~`, `?`, `|.`, `^.`, `&.`) to `isArgumentTerminator`
  - This matches Perl's behavior: `Foo | Bar` with `;$` prototype parses as
    `(Foo()) | (Bar())` not `Foo(| Bar)`
  - File: `src/main/java/org/perlonjava/frontend/parser/PrototypeArgs.java`
  - Test: `src/test/resources/unit/subroutine_prototype_args.t`
- [x] Phase 4: `Function not found to wrap!` / caller() in interpreter fallback (2026-04-09)
  - Root cause: `goto $variable` triggers interpreter fallback for the entire sub.
    In interpreter mode, `caller(0)` returned the wrong package because
    `ExceptionFormatter` was using CallerStack entries from compile-time contexts
    (BEGIN/use) for interpreter frames.
  - Fix: Removed CallerStack usage from interpreter frame processing entirely.
    Interpreter frames now always use tokenIndex/PC-based lookup via
    `ByteCodeSourceMapper` to get location info, avoiding contamination from
    compile-time CallerStack entries.
  - Also removed pre-scan code for compile-time CallerStack entries (no longer needed)
    and cleaned up all DEBUG_CALLER instrumentation.
  - Files: `ExceptionFormatter.java`, `CallerStack.java`, `ByteCodeSourceMapper.java`,
    `ErrorMessageUtil.java`
  - Tests now passing: v2-defaults.t (2/2), v2-positional.t (13/13),
    v2-named.t (15/15), v2-allowdash.t (20/20), v2-listtonamed.t (17/17)
- [x] Phase 5a: `~` overload, `;$` prototype, eq/ne interpreter overload (2026-04-09)
  - Added `~` (bitwise not) operator overload dispatch
  - Extended `;$` prototype parsing to accept named-unary behavior
  - Added comma as argument terminator for `;$` prototypes
  - Fixed interpreter `EQ_STR`/`NE_STR` opcodes to call `eq()`/`ne()` instead
    of `cmp()` for proper overload dispatch
  - Files: `BitwiseOperators.java`, `PrototypeArgs.java`, `BytecodeInterpreter.java`
  - Tests fixed: matchfor.t (3/6 → 6/6), multisig-custom-message.t (9/18 → 15/18)
- [x] Phase 5b: grep/map/sort context bug + looks_like_number -Inf (2026-04-09)
  - **Root cause (grep-in-eval):** Bytecode compiler propagated the outer SCALAR
    context to grep/map/sort's list operand. This collapsed `@array` to its count
    before filtering. The JVM backend always uses LIST context for the list operand.
  - **Fix:** Added `isListOp` check in `CompileBinaryOperator.java` to force
    LIST context for the right operand and SCALAR context for the left (closure)
    operand of grep/map/sort/all/any operators.
  - Also simplified ARRAY_SIZE opcode to use `operand.scalar()` uniformly.
  - Also fixed `looks_like_number` to recognize signed Inf/NaN (e.g., `-Inf`, `+NaN`).
  - Files: `CompileBinaryOperator.java`, `InlineOpcodeHandler.java`, `ScalarUtils.java`
  - Tests fixed: structured.t (100/115 → 110/115), basic.t (82/83 → 83/83),
    rt86239.t (4/6 → 6/6), rt90096.t (0/3 → 3/3), extra-params.t (4/7 → 7/7)
- [x] Phase 5c: numify overload, AUTOLOAD dispatch, interpreter grep/map outer @_ (2026-04-09)
  - **Numify overload:** `Overload.numify()` could return a STRING (e.g., "3.1" from
    a `0+` handler). `getNumberLarge()` now converts string results to proper numeric
    types, fixing `0+$obj` returning truncated integer instead of float.
  - **AUTOLOAD $AUTOLOAD persistence:** `autoloadVariableName` was permanently set on
    RuntimeCode objects after first AUTOLOAD fallback resolution. This caused
    `$self->SUPER::AUTOLOAD(@_)` to overwrite `$AUTOLOAD` with garbage. Fixed by
    skipping `$AUTOLOAD` assignment when the method name IS "AUTOLOAD" (direct call,
    not fallback dispatch).
  - **Interpreter grep/map outer @_:** Bytecode interpreter's `executeGrep`/`executeMap`
    didn't pass the enclosing sub's `@_` to grep/map blocks, unlike the JVM backend.
    Fixed by reading register 1 (always `@_`) and passing it as `outerArgs`.
  - Files: `RuntimeScalar.java`, `RuntimeCode.java`, `InlineOpcodeHandler.java`
  - Tests fixed: structured.t (110/115 → 115/115), Bitfield/basic.t (80/81 → 81/81),
    ConstrainedObject/basic.t (24/27 → 27/27), multisig-custom-message.t (15/18 → 21/21)
- [x] Phase 5d: Fix `local @_ = @_` bug in interpreter backend (2026-04-09)
  - **Root cause:** In `CompileAssignment.java`, when compiling `local @_ = @_`, the
    RHS `@_` evaluated to register 1 (the @_ register). Then `PUSH_LOCAL_VARIABLE reg1`
    cleared that same register before `ARRAY_SET_FROM_LIST` could read from it, resulting
    in `local @_ = @_` always producing an empty `@_`.
  - **Fix:** When `valueReg == regIdx` (source and destination are the same register),
    copy the RHS to a temporary register via `NEW_ARRAY` + `ARRAY_SET_FROM_LIST` before
    calling `PUSH_LOCAL_VARIABLE`.
  - **JVM backend:** Not affected — already clones the RHS list before localizing
    (see `EmitVariable.java` line 956).
  - Files: `CompileAssignment.java`
  - Tests fixed: v2-returns.t (4/5 → 5/5), structured.t (already passing),
    Type-Tiny-Bitfield/basic.t (already passing via Phase 5c AUTOLOAD fix)
  - Total test count increased from 2920 → 3166 (246 more tests now executing)
    because `local @_ = @_` fix unlocked previously-dying code paths in
    Type::Params::Alternatives multisig dispatch.

### Remaining 30 Failing Test Files (after Phase 5d, 36 individual subtest failures)

**Tests that pass all subtests but exit non-zero (4 files, 0 real failures):**

| Test | Subtests | Issue |
|------|----------|-------|
| `t/00-begin.t` | 0/0 | Return::Type version check (optional dep) |
| `Eval-TypeTiny/basic.t` | 3/3 ok | Dies after done_testing (`\=` ref alias) |
| `Type-Tiny-Enum/basic.t` | 17/17 ok | Dies after done_testing (`->values` on unblessed ref) |
| `Type-Params/v2-multi.t` | 1/1 ok | Dies after done_testing (multisig alternative fails) |

**Tests with 0 subtests (11 files, missing features/deps):**

| Test | Issue |
|------|-------|
| `Eval-TypeTiny/aliases-native.t` | `\$var = \$other` ref aliasing not supported |
| `Eval-TypeTiny/aliases-tie.t` | TIESCALAR not found (class loading issue) |
| `Type-Library/exportables.t` | `+Rainbow` sub not found (exporter edge case) |
| `Type-Registry/lexical.t` | `builtin::export_lexically` not implemented |
| `Type-Tiny-Enum/exporter_lexical.t` | `builtin::export_lexically` not implemented |
| `Type-Tiny-Intersection/cmp.t` | Syntax error `,=> Int` (parser limitation) |
| `Types-Standard/strmatch-allow-callbacks.t` | `(?{...})` code blocks in regex |
| `Types-Standard/strmatch-avoid-callbacks.t` | `(?{...})` code blocks in regex |
| `Types-Standard/tied.t` | Unsupported variable type for `tie()` |
| `Moo/coercion-inlining-avoidance.t` | Dies early (Moo coercion issue) |
| `gh1.t` | Dies early |

**Tests with actual subtest failures (15 files, 36 failures):**

| Test | Result | Root Cause |
|------|--------|-----------|
| `Error-TypeTiny-Assertion/basic.t` | 28/29 | B::Deparse output differs |
| `Eval-TypeTiny/lexical-subs.t` | 11/12 | Lexical sub without parens returns bareword |
| `Type-Library/exportables-duplicated.t` | 0/1 | Warning message format mismatch |
| `Type-Params/multisig-gotonext.t` | 1/6 | `goto &next` doesn't propagate `@_` correctly |
| `Type-Tie/01basic.t` | 15/17 | Tied array edge cases |
| `Type-Tie/06clone.t` | 3/6 | Clone::PP doesn't preserve tie magic |
| `Type-Tie/06storable.t` | 3/6 | Storable::dclone doesn't preserve tie magic |
| `Type-Tie/basic.t` | 1/2 | Unsupported tie on arrays |
| `Type-Tiny-Enum/sorter.t` | 0/1 | Custom sort with `$a`/`$b` cmp callback |
| `Type-Tiny/list-methods.t` | 0/2 | Custom sort with numeric comparator |
| `Moo/basic.t` | 4/5 | Moo isa coercion |
| `Moo/coercion.t` | 9/19 | Moo coercion inlining |
| `Moo/exceptions.t` | 13/15 | Exception `->value` metadata |
| `Moo/inflation.t` | 9/11 | Moo → Moose inflation |
| `gh14.t` | 0/1 | Deep coercion edge case |

- [x] Phase 5e: Fix `our` list in eval, empty prototype parser, eval local restore (2026-04-09)
  - **`our ($a, $b)` in eval STRING:** The list form of `our` in eval STRING reused
    the captured register from outer `my` variables without rebinding to the package
    global. Added `LOAD_GLOBAL_SCALAR/ARRAY/HASH` emission in the list `our` path to
    match the single-variable `our` handling.
  - **Empty prototype parser:** For subs with empty prototype `()`, `parsePrototypeArguments`
    was still called and incorrectly checked for consecutive commas in the outer context
    (e.g., `Num ,=> Int` seen as syntax error). Added early return for empty prototypes.
  - **eval {} local variable restoration:** The interpreter's `eval {}` block did not
    restore `local` variables at block exit. Added `evalLocalLevelStack` tracking to
    properly scope `local` variables inside eval blocks, matching Perl 5 semantics.
  - Files: `BytecodeCompiler.java`, `PrototypeArgs.java`, `BytecodeInterpreter.java`
  - Tests fixed:
    - `Type-Tiny-Enum/sorter.t` (0/1 → 1/1) — our list fix
    - `Type-Tiny/list-methods.t` (0/2 → 2/2) — our list fix
    - `Type-Tiny-Intersection/cmp.t` (0/0 → 17/17) — empty prototype fix
    - `Type-Params/multisig-gotonext.t` (1/6 → 8/8) — eval local restore fix
    - `00-begin.t` (0/0 → 1/1) — eval local restore fix

- [x] Phase 6: Tie infrastructure, lexical sub parsing, splice context (2026-04-10)
  - **Splice list context in interpreter:** Function calls in splice replacement positions
    now evaluated in LIST context (not SCALAR), matching the JVM backend behavior. The
    interpreter's `executeSplice` was calling functions in SCALAR context, causing only one
    value to be inserted instead of the full list.
  - **Lexical sub + statement modifier:** Lexical subs (`my sub name`) now recognized as
    function calls before statement modifier keywords (`if`, `unless`, `while`, `until`,
    `for`, `foreach`, `when`). Previously, `quuux if 1` treated `quuux` as a bareword.
  - **`tie(my(@arr), ...)` prototype parsing:** Backslash prototype `\[$@%*]` now correctly
    handles parenthesized `my` declarations. `my(@bar)` produces
    `OperatorNode("my", ListNode(OperatorNode("@")))` — the extra ListNode wrapper is
    now unwrapped via `unwrapMyListDeclaration()` helper.
  - **`return @tied_array` in eval STRING:** `materializeSpecialVarsInResult` was iterating
    `arr.elements` directly (the empty ArrayList backing TieArray), bypassing FETCHSIZE/FETCH.
    Now dispatches through `getList()` for tied arrays and hashes.
  - Files: `CompileOperator.java`, `SubroutineParser.java`, `PrototypeArgs.java`, `RuntimeCode.java`
  - Tests fixed:
    - `Eval-TypeTiny/lexical-subs.t` (11/12 → 12/12) — lexical sub + statement modifier
    - `Eval-TypeTiny/aliases-tie.t` (6/11 → 10/11) — tie prototype + return fix (remaining 1 is DESTROY)
    - `Type-Tie/basic.t` (1/2 → 3/3) — tie on arrays now supported
    - `Type-Tie/01basic.t` (15/17 → 17/17) — splice list context
    - `Types-Standard/tied.t` (0/0 → 27/27) — tie on arrays now supported
    - `Type-Library/exportables.t` (0/0 → 11/11) — resolved by eval require fix
    - `Type-Library/exportables-duplicated.t` (0/1 → 1/1) — eval require fix
    - `Type-Tiny-Enum/basic.t` (17/17 → 25/25) — unlocked more tests
    - `Moo/basic.t` (4/5 → 5/5), `Moo/coercion.t` (9/19 → 19/19),
      `Moo/exceptions.t` (13/15 → 15/15), `Moo/inflation.t` (9/11 → 11/11)
    - `Moo/coercion-inlining-avoidance.t` (0/0 → 14/14), `v2-multi.t` (1/1 → 5/5)

### Remaining Failing Test Files (after Phase 6)

**Tests with actual subtest failures (5 files, 9 individual failures):**

| Test | Result | Root Cause |
|------|--------|-----------|
| `Error-TypeTiny-Assertion/basic.t` | 28/29 | B::Deparse output differs (known limitation) |
| `Eval-TypeTiny/basic.t` | 11/12 | DESTROY not implemented (JVM GC) |
| `Eval-TypeTiny/aliases-tie.t` | 10/11 | DESTROY not implemented (JVM GC) |
| `Type-Tie/06clone.t` | 3/6 | Clone::PP doesn't preserve tie magic |
| `Type-Tie/06storable.t` | 3/6 | Storable::dclone doesn't preserve tie magic |

**Tests with 0 subtests / skipped (23 `!` in runner, mostly CWD or missing deps):**

| Test | Issue |
|------|-------|
| `Eval-TypeTiny/aliases-native.t` | `\$var = \$other` ref aliasing not supported |
| `Type-Registry/lexical.t` | `builtin::export_lexically` not implemented |
| `Type-Tiny-Enum/exporter_lexical.t` | `builtin::export_lexically` not implemented |
| `Types-Standard/strmatch-allow-callbacks.t` | `(?{...})` code blocks in regex |
| `Types-Standard/strmatch-avoid-callbacks.t` | `(?{...})` code blocks in regex |
| `gh1.t` | Missing `Math::BigFloat` dependency |
| Various Type-Library/*, Type-Tiny-*/basic.t | Test runner CWD issue — pass when run from Type-Tiny dir |

- [x] Phase 6b: Fix sprintf warnings, `local` restoration on `last`, spurious sprintf warning (2026-04-10)
  - **sprintf/printf warnings fired unconditionally:** All sprintf/printf warnings
    ("Invalid conversion", "Missing argument", "Redundant argument") used plain
    `WarnDie.warn()` which always emits warnings. Changed to `WarnDie.warnWithCategory()`
    with the `"printf"` category, matching Perl 5 behavior where these warnings only
    fire under `use warnings` or `use warnings "printf"`.
  - **`local` variable restoration on `last` exit (3 fixes):**
    - JVM backend (EmitStatement.java): Added `Local.localSetup/localTeardown` wrapping
      For3Node (while/for loops, bare blocks) so `last` exits that bypass the body block's
      own cleanup still restore `local` variables.
    - JVM backend (EmitControlFlow.java): Non-local `last`/`next`/`redo` now routes
      through `returnLabel` instead of direct `ARETURN`, ensuring the subroutine's
      `popToLocalLevel()` cleanup runs when `last LABEL` crosses subroutine boundaries
      (e.g., test.pl's `sub skip { local $^W=0; last SKIP }`).
    - Bytecode interpreter (BytecodeCompiler.java): Added `GET_LOCAL_LEVEL/POP_LOCAL_LEVEL`
      wrapping For3Node for both bare blocks and while/for loops, matching the JVM backend.
  - **Spurious sprintf "isn't numeric" warning:** `SprintfOperator.java` was calling
    `getDouble()` on arbitrary string arguments when checking for Inf/NaN on invalid format
    specifiers. Now only checks DOUBLE type values and known Inf/NaN string literals,
    avoiding the spurious warning.
  - Files: `SprintfOperator.java`, `WarnDie.java`, `EmitStatement.java`,
    `EmitControlFlow.java`, `BytecodeCompiler.java`
  - Test impact: `op/sprintf2.t` recovers 1 test (1651 → 1652), restoring baseline.

### Remaining Issues from `./jcpan --jobs 8 -t Type::Tiny`

| Issue | Impact | Details |
|-------|--------|---------|
| `builtin::export_lexically` | 2 tests | PerlOnJava reports `$]=5.042` so `Exporter::Tiny` takes the native lexical sub path, but `builtin::export_lexically` is not implemented. Affects `Type-Registry/lexical.t`, `Type-Tiny-Enum/exporter_lexical.t`. |
| `sprintf "%{"` warning | Cosmetic | Fixed in Phase 6b — warning now properly gated by `use warnings "printf"`. Not a test failure; `Types::Standard::Tied` has `use warnings` so the warning is correct but was previously also firing in no-warnings contexts. |
| `Math::BigFloat` missing | 1 test | Core Perl module not bundled with PerlOnJava. Only `t/40-bugs/gh1.t` requires it. Would need porting `Math::BigInt` + `Math::BigFloat` (large effort). |

### Phase 7: Clone/Storable tie preservation (completed 2026-04-10)

**Goal:** Fix `Type-Tie/06clone.t` (3/6 → 6/6) and `Type-Tie/06storable.t` (3/6 → 6/6).

Both tests create tied variables via `Type::Tie`, clone them, and verify the clone
still enforces type constraints. Tests 2/4/6 failed because the clone lost tie magic.

**7a. Replace custom Clone::PP with CPAN Clone::PP 1.08:**
- Replaced our custom 77-line `Clone/PP.pm` with CPAN Clone::PP 1.08.
- CPAN version handles ties, `clone_self` / `clone_init` hooks, depth limiting, and
  circular reference detection.
- However, Clone::PP's tie handling is too simplistic for Type::Tie (it calls
  `tie %$copy, ref $tied` without constructor arguments), so we also needed 7c.

**7b. Fix Storable::dclone to handle tied variables:**
- `Storable.java` `deepClone()` now detects `TieHash`, `TieArray`, `TieScalar` backing.
- For tied hashes/arrays: deep-clones the handler object, creates a new Tie* wrapper,
  and copies data through the tied interface (FETCH/STORE).
- For tied scalars: adds `TIED_SCALAR` case to clone the handler and re-tie.
- Fixed STORABLE_freeze/thaw hook to create the correct reference type (ARRAY vs HASH)
  for the thaw object — Type::Tie::BASE is array-based, not hash-based.
- Files: `Storable.java`

**7c. Create Java-based Clone module:**
- Created `Clone.java` as a proper Java XS implementation of `Clone::clone`.
- Handles tied hashes, tied arrays, tied scalars, blessed objects, circular references,
  and depth limiting — equivalent to the XS Clone module.
- `Clone.pm` loads it via XSLoader (falls back to Clone::PP if unavailable).
- Files: `Clone.java`

**Bundled tests added:**
- `src/test/resources/module/Clone-PP/t/` — 7 test files from CPAN Clone::PP 1.08
- Type-Tie tests are run via `./jcpan -t Type::Tiny` (not bundled; Type::Tie is part of Type-Tiny CPAN dist)

### Next Steps
1. Consider implementing scope-exit hooks for DESTROY (2 test files)
2. Consider B::Deparse output compatibility (1 test)
3. Fix test runner CWD handling for tests that reference `./lib`, `./t/lib`
4. Consider bundling `Math::BigFloat` / `Math::BigInt` (low priority, 1 test)
5. Consider implementing `builtin::export_lexically` (low priority, 2 tests)

### Open Questions
- The 23 `!` errors in the test runner are mostly CWD-related: tests use `./lib` and `./t/lib`
  which require running from the Type-Tiny distribution directory
- All 5 Moo tests pass when run from the correct CWD
- `builtin::export_lexically` would require lexical scoping machinery — complex to implement properly

---

## Related Documents

- `dev/modules/moo_support.md` — Moo support (Type::Tiny's primary consumer)
- `dev/modules/xs_fallback.md` — XS fallback mechanism
