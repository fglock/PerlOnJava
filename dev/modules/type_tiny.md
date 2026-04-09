# Type::Tiny Support for PerlOnJava

## Overview

Type::Tiny 2.010001 is a widely-used Perl type constraint library used by Moo, Moose,
and many CPAN modules. This document tracks the work needed to make
`./jcpan --jobs 8 -t Type::Tiny` pass its test suite on PerlOnJava.

## Current Status

**Branch:** `feature/type-tiny-support`
**Module version:** Type::Tiny 2.010001 (310 test programs)

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

### Current Status: Phase 4 completed

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

### Next Steps
1. Address remaining issues (alias assignment, TIESCALAR in eval, etc.)
2. Re-run full Type::Tiny test suite to measure progress

### Open Questions
- `ArrayRef[Int] | HashRef` triggers `Can't call method "isa" on unblessed reference`
  at Type/Tiny/Union.pm line 60 — separate runtime issue, not parser-related

---

## Related Documents

- `dev/modules/moo_support.md` — Moo support (Type::Tiny's primary consumer)
- `dev/modules/xs_fallback.md` — XS fallback mechanism
