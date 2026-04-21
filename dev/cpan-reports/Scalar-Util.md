# Scalar::Util Compatibility Report for PerlOnJava

> Investigated 2026-04-13 against Scalar-List-Utils 1.70 (CPAN) with PerlOnJava bundled Scalar::Util v1.63

## Summary

| Metric | Value |
|--------|-------|
| **CPAN distribution** | Scalar-List-Utils-1.70 (PEVANS) |
| **Bundled version** | 1.63 (Java backend) |
| **Test files** | 38 |
| **Subtests run** | 816 |
| **Subtests passed** | 606 (74.3%) |
| **Subtests failed** | 210 |
| **Test files passing** | 10 / 38 |
| **Overall status** | FAIL |

## Architecture

Scalar::Util is implemented as a split Perl/Java module:

- **Perl wrapper**: `src/main/perl/lib/Scalar/Util.pm` -- thin shim, uses `XSLoader::load`
- **Java backend**: `src/main/java/org/perlonjava/runtime/perlmodule/ScalarUtil.java` (380 lines)

The CPAN distribution includes **List::Util** and **Sub::Util** alongside Scalar::Util.
Many test failures below are in List::Util or Sub::Util functions, not Scalar::Util itself.

## Function Implementation Status

All 14 standard Scalar::Util EXPORT_OK functions are declared and registered.

| Function | Status | Notes |
|----------|--------|-------|
| `blessed` | Full | Handles blessed refs and `qr//` (implicit "Regexp" blessing) |
| `refaddr` | Full | Uses `System.identityHashCode()` (JVM -- not real memory address) |
| `reftype` | Full | Handles SCALAR, REF, ARRAY, HASH, CODE, GLOB, FORMAT, REGEXP, VSTRING |
| `weaken` | Full | Cooperative reference counting on JVM GC. Well tested. |
| `unweaken` | Full | Restores strong reference |
| `isweak` | Full | Delegates to `WeakRefRegistry.isweak()` |
| `dualvar` | Full | Creates `DualVar` record with separate numeric/string values |
| `isdual` | Full | Checks for DUALVAR type; handles READONLY_SCALAR unwrapping |
| `isvstring` | **Stub** | Always returns false. VSTRING type (ID 5) exists in runtime but is never checked. |
| `looks_like_number` | Full | Delegates to `ScalarUtils.looksLikeNumber()` with fast/slow path |
| `openhandle` | Full | Checks GLOB/GLOBREFERENCE; verifies IO handle not closed; handles `*{}` overload |
| `readonly` | **Partial** | Only detects compile-time constants (`RuntimeScalarReadOnly`). Does NOT detect runtime `Internals::SvREADONLY`. |
| `set_prototype` | Full | Sets/clears prototype on CODE refs |
| `tainted` | **Stub** | Always returns false. Taint mode is not implemented in PerlOnJava. |

## Test Results by File

### Passing (10 files)

| Test File | Subtests | Notes |
|-----------|----------|-------|
| t/any-all.t | ok | List::Util any/all/none/notall |
| t/blessed.t | ok | Scalar::Util blessed |
| t/max.t | ok | List::Util max |
| t/maxstr.t | ok | List::Util maxstr |
| t/minstr.t | ok | List::Util minstr |
| t/prototype.t | ok | Sub::Util set_prototype |
| t/readonly.t | ok | Scalar::Util readonly |
| t/rt-96343.t | ok | Regression test |
| t/stack-corruption.t | ok | Stack safety |
| t/sum0.t | ok | List::Util sum0 |

### Failing (28 files)

| Test File | Ran | Failed | Root Cause |
|-----------|-----|--------|------------|
| t/00version.t | 4 | 1 | Version mismatch: LU::XS reports 1.70 vs bundled LU 1.63 |
| t/dualvar.t | 41 | 3 | `dualvar` increment and UTF-8 handling issues |
| t/exotic_names.t | 238/1560 | 120 | Sub renaming with control characters (`set_subname`); early abort |
| t/first.t | 24 | 6 | `first {}` block not called with `$_` properly |
| t/getmagic-once.t | 6 | 6 | Magic/tie get-magic not invoked correctly |
| t/head-tail.t | 42 | 2 | `head`/`tail` edge cases |
| t/isvstring.t | 3 | 1 | `isvstring` always returns false (stub) |
| t/lln.t | 19 | 1 | `looks_like_number` edge case |
| t/mesh.t | 0/8 | 0 | Crash before any tests run (mesh/zip not implemented) |
| t/min.t | 22 | 1 | `min` edge case |
| t/openhan.t | 21 | 2 | `openhandle` edge cases |
| t/pair.t | 19/29 | 3 | `pairmap`/`pairfirst` issues; early abort |
| t/product.t | 27 | 3 | `product` numeric edge cases |
| t/reduce.t | 33 | 7 | `reduce` block context / prototype issues |
| t/reductions.t | 7 | 1 | `reductions` edge case |
| t/refaddr.t | 32 | 4 | `refaddr` with overloaded/tied objects |
| t/reftype.t | 32 | 3 | `reftype` edge cases (FORMAT, LVALUE) |
| t/sample.t | 9 | 3 | `sample` not implemented or buggy |
| t/scalarutil-proto.t | 12/14 | 1 | Prototype check issues |
| t/shuffle.t | 7 | 1 | `shuffle` edge case |
| t/subname.t | 21 | 7 | `set_subname`/`subname` not fully implemented |
| t/sum.t | 18 | 3 | `sum` numeric edge cases |
| t/tainted.t | 5 | 3 | Taint mode not implemented |
| t/undefined-block.t | 18 | 18 | Undefined code block handling |
| t/uniq.t | 31 | 6 | `uniq`/`uniqstr` edge cases |
| t/uniqnum.t | 23 | 2 | `uniqnum` numeric edge cases |
| t/weak.t | 28 | 2 | Weak reference edge cases |
| t/zip.t | 0/8 | 0 | Crash before any tests run (zip not implemented) |

## Key Failure Categories

### 1. Missing/incomplete List::Util functions
`mesh`, `zip`, `sample` are not implemented or crash. `first`, `reduce`, `reductions` have block-calling issues. These are **List::Util** problems, not Scalar::Util.

### 2. Sub::Util `set_subname`/`subname` (exotic_names.t, subname.t)
Sub renaming with exotic characters (control chars, UTF-8) does not work.
`set_subname` appears non-functional -- renamed closures still report `__ANON__`.

### 3. Stubs returning incorrect values
- `isvstring`: always returns false (trivial fix available)
- `tainted`: always returns false (systemic: no taint mode)

### 4. Magic/tie get-magic (getmagic-once.t)
All 6 tests fail -- get-magic is not invoked the correct number of times.

### 5. Undefined code block handling (undefined-block.t)
All 18 tests fail -- functions don't properly die/warn when passed undefined blocks.

### 6. Version mismatch (00version.t)
Bundled version is 1.63 but CPAN test suite is 1.70. List::Util::XS reports 1.70.

## Existing Test Coverage in PerlOnJava

| Area | Test Files | Coverage |
|------|-----------|----------|
| `weaken`/`isweak`/`unweaken` | 4 files (~634 lines) | Excellent |
| `blessed` | Incidental in subroutine.t | Minimal |
| All other functions | None | No dedicated tests |

## Recommendations

1. **Fix `isvstring`** -- trivial: check `s.type == VSTRING` instead of always returning false
2. **Fix version mismatch** -- update bundled version to 1.70 or sync XS version reporting
3. **Implement `mesh`/`zip`** -- these List::Util functions crash immediately
4. **Fix `first`/`reduce` block calling** -- `$_` not set correctly in the block
5. **Improve `set_subname`** -- critical for Moose/Moo ecosystem
6. **Add unit tests** for untested Scalar::Util functions (blessed, refaddr, reftype, dualvar, etc.)
