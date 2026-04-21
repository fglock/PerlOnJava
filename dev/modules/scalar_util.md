# Scalar-List-Utils Support Plan for PerlOnJava

## Overview

**Module:** Scalar-List-Utils 1.70 (CPAN: PEVANS)
**Bundled version:** 1.63 (Java backend)
**Test command:** `./jcpan -t Scalar::Util`
**Sub-modules:** Scalar::Util, List::Util, Sub::Util

The Scalar-List-Utils distribution provides essential utility functions used by
virtually every non-trivial CPAN module. PerlOnJava implements all three
sub-modules as thin Perl wrappers backed by Java classes:

| Sub-module | Java class | Perl stub |
|-----------|-----------|-----------|
| Scalar::Util | `ScalarUtil.java` | `src/main/perl/lib/Scalar/Util.pm` |
| List::Util | `ListUtil.java` | `src/main/perl/lib/List/Util.pm` |
| Sub::Util | `SubUtil.java` | `src/main/perl/lib/Sub/Util.pm` |

## Current Status

**Branch:** `docs/cpan-reports-scalar-util-memoize`

### Results History

| Date | Programs Failed | Subtests Failed | Total Subtests | Key Fix |
|------|----------------|-----------------|----------------|---------|
| 2026-04-13 | 28/38 | 210/816 | 816 | Baseline |

### Test Results Summary

| Test File | Status | Subtests | Root Cause |
|-----------|--------|----------|------------|
| t/00version.t | FAIL | 1/4 | Version mismatch: LU::XS 1.70 vs bundled 1.63 |
| t/any-all.t | PASS | ok | |
| t/blessed.t | PASS | ok | |
| t/dualvar.t | FAIL | 3/41 | dualvar increment and UTF-8 handling |
| t/exotic_names.t | FAIL | 120/238 | set_subname with control chars; planned 1560 |
| t/first.t | FAIL | 6/24 | `$_` not aliased in caller's scope for `first` |
| t/getmagic-once.t | FAIL | 6/6 | No Perl 5-style get-magic protocol |
| t/head-tail.t | FAIL | 2/42 | Edge cases |
| t/isvstring.t | FAIL | 1/3 | **Bug 1: isvstring always returns false** |
| t/lln.t | FAIL | 1/19 | looks_like_number edge case |
| t/max.t | PASS | ok | |
| t/maxstr.t | PASS | ok | |
| t/mesh.t | FAIL | 0/8 | **Bug 2: mesh/zip not implemented** |
| t/min.t | FAIL | 1/22 | min edge case |
| t/minstr.t | PASS | ok | |
| t/openhan.t | FAIL | 2/21 | openhandle edge cases |
| t/pair.t | FAIL | 3/29 | pairmap/pairfirst issues |
| t/product.t | FAIL | 3/27 | Numeric edge cases |
| t/prototype.t | PASS | ok | |
| t/readonly.t | PASS | ok | |
| t/reduce.t | FAIL | 7/33 | Block context / prototype issues |
| t/reductions.t | FAIL | 1/7 | Edge case |
| t/refaddr.t | FAIL | 4/32 | Overloaded/tied objects |
| t/reftype.t | FAIL | 3/32 | FORMAT, LVALUE edge cases |
| t/rt-96343.t | PASS | ok | |
| t/sample.t | FAIL | 3/9 | sample edge cases |
| t/scalarutil-proto.t | FAIL | 1/14 | Prototype check issues |
| t/shuffle.t | FAIL | 1/7 | Edge case |
| t/stack-corruption.t | PASS | ok | |
| t/subname.t | FAIL | 7/21 | set_subname not fully effective |
| t/sum.t | FAIL | 3/18 | Numeric edge cases |
| t/sum0.t | PASS | ok | |
| t/tainted.t | FAIL | 3/5 | No taint mode |
| t/undefined-block.t | FAIL | 18/18 | Undefined code block handling |
| t/uniq.t | FAIL | 6/31 | uniq/uniqstr edge cases |
| t/uniqnum.t | FAIL | 2/23 | uniqnum edge cases |
| t/weak.t | FAIL | 2/28 | Weak reference edge cases |
| t/zip.t | FAIL | 0/8 | **Bug 2: zip not implemented** |

---

## Bug Details

### Bug 1: `isvstring()` Always Returns False

**Impact:** t/isvstring.t (1 failure)

**Root cause:** `ScalarUtil.java:238-243` is a stub that always returns `false`:
```java
// Placeholder for isvstring functionality
return new RuntimeScalar(false).getList();
```
The VSTRING type (constant 5) already exists in the runtime and is correctly
used by `reftype()` and `Version.java`. Only `isvstring()` doesn't check for it.

**Fix:** Check `type == VSTRING`, following the `isdual()` pattern:
```java
RuntimeScalar s = args.get(0);
if (s.type == READONLY_SCALAR) s = (RuntimeScalar) s.value;
return new RuntimeScalar(s.type == VSTRING).getList();
```

**Files:** `src/main/java/org/perlonjava/runtime/perlmodule/ScalarUtil.java`

### Bug 2: `mesh`/`zip` Functions Not Implemented in ListUtil.java

**Impact:** t/mesh.t (8 tests), t/zip.t (8 tests) -- both crash immediately

**Root cause:** The Perl stub `List/Util.pm` declares these in `@EXPORT_OK`:
```perl
zip zip_longest zip_shortest mesh mesh_longest mesh_shortest
```
But `ListUtil.java` never registers them in `initialize()`. When called, there's
no Java method to dispatch to.

**Fix:** Implement 6 new methods in `ListUtil.java`:
- `zip` / `zip_shortest` / `zip_longest` -- takes arrayrefs, returns list of arrayrefs
- `mesh` / `mesh_shortest` / `mesh_longest` -- takes arrayrefs, returns flat interleaved list

Per Perl 5 docs:
- `zip` returns list of arrayrefs (one per "row"), stopping at shortest input
- `zip_longest` pads with undef to longest input
- `zip_shortest` is an alias for `zip`
- `mesh` returns flat interleaved list, stopping at shortest input
- `mesh_longest` pads with undef to longest input
- `mesh_shortest` is an alias for `mesh`

**Files:** `src/main/java/org/perlonjava/runtime/perlmodule/ListUtil.java`

### Bug 3: `tainted()` Always Returns False (Systemic)

**Impact:** t/tainted.t (3 failures)

**Root cause:** Taint mode is not implemented in PerlOnJava. `RuntimeScalar.isTainted()`
always returns `false`. This is a systemic limitation, not fixable in Scalar::Util alone.

**Status:** Won't fix (requires taint mode implementation)

### Bug 4: `isvstring` Returns False -- Resolved by Bug 1 Fix

### Bug 5: `set_subname` Doesn't Work With Exotic Characters

**Impact:** t/exotic_names.t (120 failures), t/subname.t (7 failures)

**Root cause:** `SubUtil.set_subname()` sets `code.packageName` and `code.subName`
correctly, but `caller()` doesn't always return the renamed sub's name. The issue
is that PerlOnJava's `__ANON__` handling may override the set name for closures.

**Status:** Deferred -- needs deep investigation of RuntimeCode caller integration

### Bug 6: `getmagic-once` -- No Magic Get Protocol

**Impact:** t/getmagic-once.t (6/6 failures)

**Root cause:** PerlOnJava doesn't implement Perl 5's mg_get()/mg_set() protocol.
Tied scalars use tiedFetch/tiedStore directly; there's no "magic invocation count"
concept.

**Status:** Won't fix (architectural difference)

### Bug 7: `undefined-block` -- Missing Error for Undefined Code Blocks

**Impact:** t/undefined-block.t (18/18 failures)

**Root cause:** When `undef` is passed as a code block to `first`, `any`, `all`, etc.,
Perl 5 throws specific errors. PerlOnJava doesn't validate the code ref argument.

**Status:** Deferred

---

## Fix Order (Priority)

1. **Fix `isvstring()`** -- trivial, 1 test file
2. **Implement `mesh`/`zip`** -- medium, 2 test files (16 tests)
3. Version sync (update bundled version to match) -- optional
4. `undefined-block` error handling -- deferred
5. `set_subname` caller integration -- deferred

## Completed Fixes

_(none yet)_

## Progress Tracking

- [ ] Fix `isvstring()` to check VSTRING type
- [ ] Implement `mesh`/`zip`/`mesh_longest`/`zip_longest`/`mesh_shortest`/`zip_shortest`
- [ ] Re-run `./jcpan -t Scalar::Util` and update results

## Related Documents

- `dev/cpan-reports/Scalar-Util.md` -- detailed test investigation
- `dev/architecture/weaken-destroy.md` -- weaken/DESTROY architecture
