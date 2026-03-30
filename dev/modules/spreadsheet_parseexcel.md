# Spreadsheet::ParseExcel Fix Plan

## Overview

**Module**: Spreadsheet::ParseExcel 0.66  
**Test command**: `./jcpan -t Spreadsheet::ParseExcel`  
**Status**: ALL PASS — 32/32 test files (28 pass, 4 skipped), 1605 subtests

## Dependency Tree

| Dependency | Status | Notes |
|-----------|--------|-------|
| **OLE::Storage_Lite** >= 0.19 | PASS | Installs and tests OK |
| **Digest::Perl::MD5** | PASS | Installs OK (no tests in dist) |
| **IO::Scalar** (IO::Stringy 2.113) | INSTALLED (tests fail) | Installs but IO tests fail — seek/read/getc issues (separate PerlOnJava bugs) |
| **Crypt::RC4** 2.02 | **FIXED** | Was blocked by MakeMaker flat-layout bug — now installs correctly |

## Test Results Summary

### Current Status: 32/32 test files ALL PASS (28 pass, 4 skipped), 1605 subtests

| Test File | Status | Notes |
|-----------|--------|-------|
| t/00_basic.t | PASS | All 8 modules load |
| t/01_parse.t | PASS | All 41 subtests — **FIXED by Phase 4** (glob hash deref) |
| t/02_parse-dates.t | PASS | |
| t/03_regression.t | PASS | All 16 subtests — **FIXED by Phase 4** (glob hash deref) |
| t/04_regression.t | PASS | |
| t/05_regression.t | PASS | |
| t/06_regression.t | PASS | |
| t/07_cell_handler.t | PASS | |
| t/10_error_codes.t | PASS | |
| t/11_encryption.t | PASS | |
| t/20_number_format_default.t | PASS | |
| t/21_number_format_user.t | PASS | |
| t/22_number_format_datetime.t | PASS | |
| t/23_number_format_time.t | PASS | |
| t/24_row_col_sizes.t | PASS | |
| t/25_decode_rk_numbers.t | PASS | |
| t/26_localtime2excel.t | PASS | |
| t/27_localtime2excel.t | PASS | |
| t/28_int2col.t | PASS | |
| t/29_active_sheet.t | PASS | |
| t/30_sst_01.t | PASS | |
| t/32_charts.t | PASS | |
| t/41_test95-97j.t | PASS | **FIXED by Phase 2** (ucs2 alias) |
| t/42_test95-97j-2.t | PASS | **FIXED by Phase 3** (find_encoding blessed object) |
| t/43_test2000J.t | PASS | **FIXED by Phase 2** (ucs2 alias) |
| t/44_oem.t | PASS | **FIXED by Phase 2** (ucs2 alias) |
| t/45_oem-2.t | PASS | **FIXED by Phase 3** (find_encoding blessed object) |
| t/46_save_parser.t | SKIP | Needs Spreadsheet::WriteExcel |
| t/47_hyperlinks.t | PASS | |
| t/90_pod.t | SKIP | Author tests |
| t/91_minimumversion.t | SKIP | Author tests |
| t/92_meta.t | SKIP | Author tests |

---

## Error Categories

### 1. FIXED: MakeMaker fails for flat-layout distributions

**Affected module**: Crypt::RC4 2.02  
**Error**:
```
Warning: No installable files found (no .pm, .pl, .dat, etc.).
Expected structure: lib/Your/Module.pm
```

**Root Cause**: Crypt::RC4 has `RC4.pm` at the distribution root, not in `lib/Crypt/RC4.pm`. PerlOnJava's `_install_pure_perl()` in `ExtUtils/MakeMaker.pm` only scanned `lib/` and `blib/lib/` directories.

**Fix Applied**: Added fallback in `_install_pure_perl()` to scan the root directory for `.pm` files when no `lib/` or `blib/lib/` exists, using the `NAME` parameter to derive the install subdirectory (e.g., `NAME => 'Crypt::RC4'` -> installs `RC4.pm` as `Crypt/RC4.pm`).

**File changed**: `src/main/perl/lib/ExtUtils/MakeMaker.pm` (lines 225-246)

---

### 2. FIXED: `ucs2` encoding not recognized

**Affected tests**: t/41_test95-97j.t (31/66 ran), t/43_test2000J.t (0/22), t/44_oem.t (0/14)

**Fix Applied**: Added `ucs2` → `UTF-16BE` and related aliases (UCS-2BE, UCS-2LE, sjis, shiftjis, etc.) to `CHARSET_ALIASES` in `Encode.java`.

**File changed**: `src/main/java/org/perlonjava/runtime/perlmodule/Encode.java`

---

### 3. FIXED: `find_encoding()` returns string instead of blessed object

**Affected tests**: t/42_test95-97j-2.t (0/66), t/45_oem-2.t (0/14)

**Fix Applied**: Created `Encode::Encoding` class with `encode()`/`decode()` methods. `find_encoding()` now returns `bless { Name => $charset_name }, 'Encode::Encoding'` instead of a plain string.

**File changed**: `src/main/java/org/perlonjava/runtime/perlmodule/Encode.java`

---

### 4. FIXED: Glob hash deref `*$self->{Key}` broken (IO::Scalar compatibility)

**Affected tests**: t/01_parse.t (5/41 fail), t/03_regression.t (9/16 ran), and all IO::Scalar-dependent code paths

**Root Cause**: `RuntimeGlob.hashDerefGet()` overrode `RuntimeScalar.hashDerefGet()` to call `getGlobSlot()`, which only recognizes glob slot names (HASH, CODE, SCALAR, etc.). Due to JVM virtual dispatch, `*$self->{Key}` called `getGlobSlot("Key")` → returned undef for any key not a slot name. This broke IO::Scalar's pattern of using `*$self->{Pos}`, `*$self->{SR}`, etc. for per-glob instance data.

**Fix Applied**:
- Removed `hashDerefGet`/`hashDerefGetNonStrict` overrides from `RuntimeGlob`
- Made `getGlobSlot()` public for direct callers
- Added explicit `*` sigil handler in `Dereference.java` for `*expr{SLOT}` syntax (glob slot access)
- Updated `RuntimeScalar.scalarDeref` and `SlowOpcodeHandler.GLOB_SLOT_GET` to call `getGlobSlot()` directly

Now `*$self->{Key}` correctly accesses hash elements, while `*glob{HASH}` still returns glob slot references. This fixed all remaining test failures — IO::Scalar works correctly now and t/01_parse.t and t/03_regression.t pass fully.

**Files changed**: `RuntimeGlob.java`, `RuntimeScalar.java`, `Dereference.java`, `SlowOpcodeHandler.java`

---

## Fix Plan

### Phase 1: Fix MakeMaker flat-layout support (COMPLETED 2025-03)

| Step | Description | Status |
|------|-------------|--------|
| 1.1 | Add root-directory `.pm` scan fallback to `_install_pure_perl()` | DONE |
| 1.2 | Rebuild (`make dev`) | DONE |
| 1.3 | Clear Crypt::RC4 build state and re-install | DONE |
| 1.4 | Verify `./jperl -e 'use Crypt::RC4; print "OK\n"'` works | DONE |
| 1.5 | Re-test Spreadsheet::ParseExcel | DONE |

**Result**: 21 failing test files -> 7 failing. 4/605 subtests -> 1442/1447 subtests passing.

### Phase 2: Add `ucs2` charset alias (COMPLETED 2025-03)

| Step | Description | File | Status |
|------|-------------|------|--------|
| 2.1 | Add `ucs2` → `UTF-16BE` alias to `CHARSET_ALIASES` | `Encode.java` | DONE |
| 2.2 | Add `UCS-2`, `UCS-2BE`, `UCS-2LE` variants | `Encode.java` | DONE |
| 2.3 | Add `sjis` → `Shift_JIS` alias | `Encode.java` | DONE |
| 2.4 | Run `make` to verify unit tests pass | - | DONE |

**Result**: Fixes t/41_test95-97j.t, t/43_test2000J.t, t/44_oem.t (96 subtests)

### Phase 3: Make `find_encoding()` return blessed object (COMPLETED 2025-03)

| Step | Description | File | Status |
|------|-------------|------|--------|
| 3.1 | Add `Encode::Encoding::encode()` static method | `Encode.java` | DONE |
| 3.2 | Add `Encode::Encoding::decode()` static method | `Encode.java` | DONE |
| 3.3 | Register methods in `initialize()` | `Encode.java` | DONE |
| 3.4 | Modify `find_encoding()` to return `bless { Name => $name }, 'Encode::Encoding'` | `Encode.java` | DONE |
| 3.5 | Add stringify overload on `Encode::Encoding` (some code uses encoding as string) | `Encode.java` | DONE |
| 3.6 | Run `make` to verify unit tests pass | - | DONE |

**Result**: Fixes t/42_test95-97j-2.t, t/45_oem-2.t (80 subtests)

### Phase 4: Fix glob hash deref for IO::Scalar (COMPLETED 2025-03)

| Step | Description | File | Status |
|------|-------------|------|--------|
| 4.1 | Remove `hashDerefGet`/`hashDerefGetNonStrict` overrides from RuntimeGlob | `RuntimeGlob.java` | DONE |
| 4.2 | Make `getGlobSlot()` public | `RuntimeGlob.java` | DONE |
| 4.3 | Add `*` sigil handler in `handleHashElementOperator` for `*expr{SLOT}` | `Dereference.java` | DONE |
| 4.4 | Update scalarDeref callers to use `getGlobSlot()` directly | `RuntimeScalar.java` | DONE |
| 4.5 | Update GLOB_SLOT_GET opcode to use `getGlobSlot()` directly | `SlowOpcodeHandler.java` | DONE |
| 4.6 | Run `make` to verify all unit tests pass | - | DONE |

**Result**: `*$self->{Key}` now works correctly for IO::Scalar-style per-glob hash storage. This also fixed t/01_parse.t (5 failing subtests) and t/03_regression.t (7 untested subtests) — the glob hash deref bug was the root cause of the IO::Scalar failures in OLE::Storage_Lite parsing.

## Summary

| Phase | Complexity | Test files fixed | Subtests fixed | Status |
|-------|-----------|-----------------|----------------|--------|
| 1 | Simple | 14 | ~1437 | COMPLETED |
| 2 | Simple (~6 lines) | 3 | ~96 | COMPLETED |
| 3 | Medium (~60 lines) | 2 | ~80 | COMPLETED |
| 4 | Medium (4 files) | 2 | ~12 | COMPLETED |

**Final result**: 32/32 test files ALL PASS (28 pass, 4 skipped), 1605 subtests.

## Related Documents

- `dev/modules/makemaker_perlonjava.md` — MakeMaker implementation details
- `dev/modules/test_deep.md` — similar fix plan format
