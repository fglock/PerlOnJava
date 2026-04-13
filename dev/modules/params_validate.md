# Params::Validate Support for PerlOnJava

## Overview

Params::Validate 1.31 is a widely-used Perl module for validating method/function
parameters. It has both XS and Pure Perl (PP) backends; on PerlOnJava only the PP
backend is usable. This document tracks the work needed to make the PP test suite
pass on PerlOnJava.

## Current Status

**Branch:** `feature/params-validate-support`
**Module version:** Params::Validate 1.31 (38 test programs, 2515 subtests)

### Build Notes

- PerlOnJava cannot compile XS C code, so a pure-Perl build is required.
- CPAN distroprefs automatically pass `--pp` to `Build.PL` and set
  `PARAMS_VALIDATE_IMPLEMENTATION=PP`.
- `jcpan -t Params::Validate` works out of the box (no manual flags needed).
- Distroprefs file: `src/main/perl/lib/CPAN/prefs/Params-Validate.yml`

### Results History

| Date | Programs Failed | Subtests Failed | Total Subtests | Key Fix |
|------|----------------|-----------------|----------------|---------|
| Baseline (2026-04-13) | 4/38 | 23/2515 | 2515 | -- |
| After all fixes (2026-04-13) | **0/38** | **0/2515** | 2515 | Fixes 1-4 |

---

## Completed Fixes

### Fix 1: `ref()` on GLOB-typed RuntimeScalar (t/01-validate.t, t/13-taint.t)

**Problem:** In Perl 5, `ref(*glob)` always returns `""` (empty string) because bare
globs are not references. PerlOnJava's `ref()` incorrectly analyzed which glob slots
(scalar, array, hash, code, IO, format) were populated and returned the slot type
(e.g. `"CODE"`, `"SCALAR"`) when exactly one slot was filled.

This caused `Params::Validate::PP::_get_type()` to misclassify globs. For example,
`*HANDLE` with a CODE slot was reported as having type "coderef" instead of "glob",
because `ref(*HANDLE)` returned `"CODE"` and the PP code took the `ref()` branch
instead of falling through to the `UNIVERSAL::isa(\$val, 'GLOB')` check.

**Root cause:** `ReferenceOperators.ref()` case `GLOB` (line 105) performed slot
analysis.  The slot analysis is only meaningful for `ref(\*glob)` (a *reference to* a
glob), which is handled by the `case REFERENCE:` → `GLOB` and `case GLOBREFERENCE:`
branches.

**Fix:** Replaced the entire slot-analysis block in `case GLOB:` with
`return scalarEmptyString`.

**File:** `src/main/java/org/perlonjava/runtime/operators/ReferenceOperators.java`

### Fix 2: `RuntimeScalar.createReference()` for GLOB-typed scalars (t/01-validate.t, t/13-taint.t)

**Problem:** When a glob passes through `@_`, array storage, or other copy operations,
the RuntimeGlob is wrapped inside a RuntimeScalar (type=GLOB, value=RuntimeGlob).
Java virtual dispatch then calls `RuntimeScalar.createReference()` instead of
`RuntimeGlob.createReference()`.  The former always returned type `REFERENCE`, not
`GLOBREFERENCE`, so `UNIVERSAL::isa(\*glob, 'GLOB')` returned false.

In Perl 5, `\*glob` always produces a glob reference:
```
ref(\*FH)                    → "GLOB"
UNIVERSAL::isa(\*FH, 'GLOB') → 1
```

The companion `RuntimeGlob.createReference()` already correctly sets
`type = GLOBREFERENCE`, but when a glob is wrapped in a RuntimeScalar, that code path
is never reached.

**Fix:** In `RuntimeScalar.createReference()`, added a check: if `this.type == GLOB`
and `this.value instanceof RuntimeGlob`, set `result.type = GLOBREFERENCE` and
`result.value = this.value` (the RuntimeGlob directly).

**File:** `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeScalar.java`

### Fix 3: `UNIVERSAL::isa` for "REGEXP" (t/32-regex-as-value.t)

**Problem:** `UNIVERSAL::isa(qr/foo/, "REGEXP")` returned false because the isa check
only matched the mixed-case `"Regexp"` (which is what `ref(qr/foo/)` returns), not
the uppercase `"REGEXP"` (Perl 5's internal SV type name).

Perl 5 accepts both spellings: `isa(qr//, "Regexp")` and `isa(qr//, "REGEXP")` both
return true.  Modules like Params::Validate::PP use the uppercase form in their
type-detection tables (`%isas` hash key `'REGEXP'`).

**Fix:** Added `|| argString.equals("REGEXP")` alongside the existing
`argString.equals("Regexp")` check for unblessed regex objects.

**File:** `src/main/java/org/perlonjava/runtime/perlmodule/Universal.java`

### Fix 4: `RuntimeScalarReadOnly` string boolean for "0" (t/15-case.t)

**Problem:** `for my $v ("0") { $v ? "true" : "false" }` evaluated to `"true"` in
PerlOnJava but `"false"` in Perl 5.  Only affected literal lists in `for` loops;
iterating over array variables worked correctly.

The Params::Validate test generates test cases in a BEGIN block with
`for my $ignore_case (qw( 0 1 ))` and uses `$ignore_case` in a ternary to select
the expect function.  Because `"0"` was truthy, all 18 ignore_case=0 test cases got
`$ok_sub` instead of `$nok_sub`, causing 12 tests (the case-mismatch ones) to fail.

**Root cause:** `RuntimeScalarReadOnly(String s)` pre-computed its boolean field as
`this.b = !s.isEmpty()`, which made `"0"` truthy.  Perl's boolean rules for strings
are: `""` and `"0"` are false, everything else is true.  The correct logic already
existed in `RuntimeScalar.getBooleanLarge()`: `!s.isEmpty() && !s.equals("0")`.

When `for my $v ("0")` runs, the string literal `"0"` is a `RuntimeScalarReadOnly`
instance.  The loop variable directly aliases this object (no copy is made for literal
lists), so `$v ? ...` calls `RuntimeScalarReadOnly.getBoolean()` which returned the
wrong pre-computed value.  Array iteration works because array storage copies values
into mutable `RuntimeScalar` objects which use the correct `getBooleanLarge()` path.

**Fix:** Changed the boolean pre-computation to `!s.isEmpty() && !s.equals("0")`.

**File:** `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeScalarReadOnly.java`

---

## Skipped Tests (3 programs — pre-existing, not PerlOnJava issues)

| Test File | Reason |
|-----------|--------|
| t/19-untaint.t | Requires Test::Taint (not installed) |
| t/29-taint-mode.t | Requires Test::Taint (not installed) |
| t/31-incorrect-spelling.t | Spec validation disabled by the module itself |

---

## Progress Tracking

### Completed
- [x] Investigation: identified all 4 root causes (2026-04-13)
- [x] Created design document (2026-04-13)
- [x] Fix 1: `ref()` on GLOB — always returns `""` for bare globs (2026-04-13)
- [x] Fix 2: `RuntimeScalar.createReference()` — returns GLOBREFERENCE for GLOB type (2026-04-13)
- [x] Fix 3: `UNIVERSAL::isa` — accepts "REGEXP" (uppercase) for regex objects (2026-04-13)
- [x] Fix 4: `RuntimeScalarReadOnly` — string `"0"` boolean is now false (2026-04-13)
- [x] `make` passes (all existing unit tests green)
- [x] Params::Validate PP test suite: **35/35 ok, 3 skipped, 2515/2515 subtests pass**
- [x] CPAN distroprefs added — `jcpan -t Params::Validate` works out of the box (2026-04-13)

---

## Related Documents

- `dev/modules/xs_fallback.md` — XS fallback mechanism
- `dev/modules/scalar_util.md` — Scalar::Util (dependency)
