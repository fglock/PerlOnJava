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

- `jcpan -t Params::Validate` fails because `Module::Build` tries to compile XS code.
- Manual build with `--pp` flag works: `jperl Build.PL --pp && jperl Build`
- Must set `PARAMS_VALIDATE_IMPLEMENTATION=PP` at runtime.

### Results History

| Date | Programs Failed | Subtests Failed | Total Subtests | Key Fix |
|------|----------------|-----------------|----------------|---------|
| Baseline (2026-04-13) | 4/38 | 23/2515 | 2515 | — |

### Baseline Failures (4 test programs, 23 subtests)

| Test File | Failed/Total | Root Cause |
|-----------|-------------|------------|
| t/01-validate.t | 5/94 | Glob type detection (ref + createReference) |
| t/13-taint.t | 5/94 | Same as t/01-validate.t (same test lib) |
| t/15-case.t | 12/36 | `for` loop variable aliasing with literal "0" |
| t/32-regex-as-value.t | 1/3 | `UNIVERSAL::isa($regex, "REGEXP")` case mismatch |

### Skipped Tests (3 programs)

| Test File | Reason |
|-----------|--------|
| t/19-untaint.t | Requires Test::Taint (not installed) |
| t/29-taint-mode.t | Requires Test::Taint (not installed) |
| t/31-incorrect-spelling.t | Spec validation disabled for now |

---

## Planned Fixes

### Fix 1: `ref()` on GLOB-typed RuntimeScalar (t/01-validate.t, t/13-taint.t)

**Problem:** In Perl 5, `ref(*glob)` always returns `""` (empty string) because bare
globs are not references. PerlOnJava's `ref()` incorrectly analyzes which glob slots
are populated and returns the slot type (e.g., `"CODE"`, `"SCALAR"`).

**Root cause:** `ReferenceOperators.ref()` case `GLOB` (line 105) performs slot
analysis instead of returning empty string. The slot analysis is only meaningful
for `ref(\*glob)` (GLOBREFERENCE or REFERENCE pointing to GLOB), not for bare globs.

**Fix:** In `ReferenceOperators.java`, the `case GLOB:` branch should always return
`""` (empty string), since a bare glob value is never a reference. The existing
slot-analysis logic applies to REFERENCE-to-GLOB and GLOBREFERENCE, which are
handled by the `case REFERENCE:` and `case GLOBREFERENCE:` branches respectively.

**File:** `src/main/java/org/perlonjava/runtime/operators/ReferenceOperators.java`

### Fix 2: `RuntimeScalar.createReference()` for GLOB-typed scalars (t/01-validate.t, t/13-taint.t)

**Problem:** When a glob is stored in a RuntimeScalar (type=GLOB, value=RuntimeGlob),
calling `\$scalar` invokes `RuntimeScalar.createReference()` which returns type
`REFERENCE` instead of `GLOBREFERENCE`. This causes `UNIVERSAL::isa(\*glob, 'GLOB')`
to return false.

**Root cause:** `RuntimeScalar.createReference()` always sets `type = REFERENCE`.
The companion `RuntimeGlob.createReference()` correctly sets `type = GLOBREFERENCE`,
but when a glob passes through `@_` or other copy operations, it becomes a
RuntimeScalar with type=GLOB, and the RuntimeGlob dispatch path is lost.

**Fix:** In `RuntimeScalar.createReference()`, check if `this.type == GLOB` and
`this.value instanceof RuntimeGlob`, and if so, set `result.type = GLOBREFERENCE`
and `result.value = this.value` (the RuntimeGlob itself).

**File:** `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeScalar.java`

### Fix 3: `UNIVERSAL::isa` for "REGEXP" (t/32-regex-as-value.t)

**Problem:** `UNIVERSAL::isa(qr/foo/, "REGEXP")` returns false because the isa
check only matches the mixed-case `"Regexp"`, not the uppercase `"REGEXP"`.

**Root cause:** In `Universal.isa()` line 290, the REGEX case only checks
`argString.equals("Regexp")`. Perl 5 treats both `"Regexp"` and `"REGEXP"` as
valid class names for regex objects.

**Fix:** Add `|| argString.equals("REGEXP")` to the REGEX type check.

**File:** `src/main/java/org/perlonjava/runtime/perlmodule/Universal.java`

### Fix 4: `for` loop variable aliasing with literal "0" (t/15-case.t)

**Problem:** `for my $v ("0") { $v ? "true" : "false" }` evaluates to `"true"` in
PerlOnJava but `"false"` in Perl 5. This only affects literal lists; iterating over
array variables (`for my $v (@a)`) works correctly.

The Params::Validate test generates test cases in a BEGIN block with
`for my $ignore_case (qw( 0 1 ))` and uses `$ignore_case` in a ternary. Because
`"0"` is truthy, all test cases get the wrong `$expect` function.

**Root cause:** When iterating over a literal list in a `for` loop, the loop variable
is aliased to a temporary RuntimeScalar. The boolean evaluation of this aliased
value doesn't correctly handle the string `"0"` case. Iterating over array elements
works because the RuntimeScalar objects already have correct type metadata.

**Fix:** TBD — needs investigation of how literal list for-loops bind the iterator
variable.

**File:** TBD (likely in EmitOperatorNode.java or RuntimeArray iteration code)

---

## Progress Tracking

### Completed
- [x] Investigation: identified all 4 root causes (2026-04-13)
- [x] Created design document

### In Progress
- [ ] Fix 1: `ref()` on GLOB-typed RuntimeScalar
- [ ] Fix 2: `RuntimeScalar.createReference()` GLOBREFERENCE
- [ ] Fix 3: `UNIVERSAL::isa` REGEXP uppercase
- [ ] Fix 4: `for` loop literal list aliasing

### Remaining
- [ ] Run `make` — all existing tests pass
- [ ] Re-run Params::Validate test suite
- [ ] Update results table

---

## Related Documents

- `dev/modules/xs_fallback.md` — XS fallback mechanism
- `dev/modules/scalar_util.md` — Scalar::Util (dependency)
