# Unicode Module Deep Dive - Complete Session Summary

**Date:** 2025-10-17  
**Duration:** ~30 minutes  
**Baseline:** test_20251017_142400 (198,229 OK / 11,308 NOT OK, 94.6% pass rate)

## Objective

Fix blocking error in `t/uni/lower.t` and related Unicode tests:
```
Symbol getCombinClass not allowed for export in package Unicode::Normalize
```

## Problems Identified & Fixed

### Problem 1: Incomplete @EXPORT_OK in Java Stub ✅ FIXED

**Root Cause:**  
`UnicodeNormalize.java` only defined 1 export (`normalize`) in `@EXPORT_OK`, but the real Perl module has 35 exports including `getCombinClass`.

**Solution:**  
Added all 35 exports from `perl5/dist/Unicode-Normalize/Normalize.pm` to the Java stub.

**Commit:** `3718fcee` - "Fix Unicode::Normalize @EXPORT_OK to include all 35 exports"

### Problem 2: Missing Perl Modules ✅ FIXED

**Root Cause:**  
Unicode::Normalize.pm and unicore/UCD.pl were not in the local lib, causing module loading to fail or use incomplete system versions.

**Solution:**  
Copied from perl5 source to `src/main/perl/lib/`:
- `Unicode/Normalize.pm` (20KB) - Full Perl module
- `unicore/UCD.pl` (282KB) - Generated Unicode data tables

**Commit:** `665a4be0` - "Add Unicode::Normalize and unicore/UCD.pl to local lib"

### Problem 3: Method Too Large ⚠️ IDENTIFIED

**Current Status:**  
The 282KB `UCD.pl` file contains huge data structures that exceed JVM's 64KB method bytecode limit.

**Error:**
```
Method too large: org/perlonjava/anon465.apply
```

**Known Solutions:**
1. Use `JPERL_LARGECODE=refactor` environment variable
2. Automatic method splitting (already implemented in PerlOnJava)
3. Break up large data structures in UCD.pl

## Architecture Understanding

### How XSLoader Works in PerlOnJava

1. Perl module calls `XSLoader::load('Unicode::Normalize')`
2. XSLoader.java converts to Java class name: `org.perlonjava.perlmodule.UnicodeNormalize`
3. Calls `UnicodeNormalize.initialize()` via reflection
4. Java stub provides XS functions (NFD, NFC, NFKD, NFKC)
5. Perl module provides pure Perl wrappers and utilities

### Module Loading Priority

1. Java stub initializes first (sets up @EXPORT, @EXPORT_OK)
2. Perl module loads from JAR (`src/main/perl/lib/`)
3. XSLoader connects Perl module to Java implementation
4. Both work together seamlessly

## Test Results

**Before Fixes:**
```
Symbol getCombinClass not allowed for export in package Unicode::Normalize
```

**After Fix 1:**
```
Can't locate unicore/UCD.pl in @INC
```

**After Fix 2:**
```
Method too large: org/perlonjava/anon465.apply
```

**Progress:** ✅ Unblocked original error, identified next issue

## Impact

**Tests Affected:** 6 Unicode test files
- `uni/lower.t`
- `uni/title.t`
- `uni/upper.t`
- `uni/fold.t`
- 2 others

**Current Status:**
- ✅ `getCombinClass` export error: FIXED
- ✅ Module loading: FIXED
- ⚠️  Method too large: IDENTIFIED (known issue with solutions)

## Commits

1. `3718fcee` - Fix Unicode::Normalize @EXPORT_OK (Java stub)
2. `665a4be0` - Add Perl modules to local lib

## Key Insights

1. **Java Stubs Must Be Complete:** Export lists in Java stubs must match Perl modules exactly
2. **XSLoader Integration:** PerlOnJava's XSLoader seamlessly connects Perl and Java
3. **Module Priority:** Understanding load order is critical for debugging
4. **Large Data Files:** Unicode data tables hit JVM method size limits (known issue)

## Next Steps

To fully unblock Unicode tests:
1. Apply `JPERL_LARGECODE=refactor` when running tests
2. Or implement automatic data structure splitting for UCD.pl
3. Or use alternative Unicode data loading mechanism

## ROI

- **Time:** 30 minutes
- **Issues Fixed:** 2 (export list, module loading)
- **Issues Identified:** 1 (method size - has known solutions)
- **Regressions:** 0
- **Complexity:** Medium (required understanding XSLoader architecture)
