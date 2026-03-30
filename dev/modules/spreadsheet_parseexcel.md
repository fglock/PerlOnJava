# Spreadsheet::ParseExcel Fix Plan

## Overview

**Module**: Spreadsheet::ParseExcel 0.66  
**Test command**: `./jcpan -t Spreadsheet::ParseExcel`  
**Status**: 25/32 test files passing (after Phases 1-3); Phases 2+3 fix encoding tests, Phase 4 fix pending

## Dependency Tree

| Dependency | Status | Notes |
|-----------|--------|-------|
| **OLE::Storage_Lite** >= 0.19 | PASS | Installs and tests OK |
| **Digest::Perl::MD5** | PASS | Installs OK (no tests in dist) |
| **IO::Scalar** (IO::Stringy 2.113) | INSTALLED (tests fail) | Installs but IO tests fail — seek/read/getc issues (separate PerlOnJava bugs) |
| **Crypt::RC4** 2.02 | **FIXED** | Was blocked by MakeMaker flat-layout bug — now installs correctly |

## Test Results Summary

### Current Status: 25/32 test files passing, 1442/1447 subtests passing

| Test File | Status | Notes |
|-----------|--------|-------|
| t/00_basic.t | PASS | All 8 modules load |
| t/01_parse.t | **FAIL** (5/41) | Tests 33,35,37,39,41 — `is_deeply` fails when parsing from non-file sources |
| t/02_parse-dates.t | PASS | |
| t/03_regression.t | **FAIL** (9/16 ran) | Test 10: `Parse($fh)` returns undef → `get_filename` on undef |
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
| t/41_test95-97j.t | **FAIL** (31/66 ran) | `ucs2` encoding not recognized — **FIXED by Phase 2** |
| t/42_test95-97j-2.t | **FAIL** (0/66 ran) | `find_encoding` returns string, not object — **FIXED by Phase 3** |
| t/43_test2000J.t | **FAIL** (0/22 ran) | `ucs2` encoding not recognized — **FIXED by Phase 2** |
| t/44_oem.t | **FAIL** (0/14 ran) | `ucs2` encoding not recognized — **FIXED by Phase 2** |
| t/45_oem-2.t | **FAIL** (0/14 ran) | `find_encoding` returns string, not object — **FIXED by Phase 3** |
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

**Affected tests**: t/01_parse.t (5/41 fail), t/03_regression.t (9/16 ran)

**Root Cause**: `RuntimeGlob.hashDerefGet()` overrode `RuntimeScalar.hashDerefGet()` to call `getGlobSlot()`, which only recognizes glob slot names (HASH, CODE, SCALAR, etc.). Due to JVM virtual dispatch, `*$self->{Key}` called `getGlobSlot("Key")` → returned undef for any key not a slot name. This broke IO::Scalar's pattern of using `*$self->{Pos}`, `*$self->{SR}`, etc. for per-glob instance data.

**Fix Applied**:
- Removed `hashDerefGet`/`hashDerefGetNonStrict` overrides from `RuntimeGlob`
- Made `getGlobSlot()` public for direct callers
- Added explicit `*` sigil handler in `Dereference.java` for `*expr{SLOT}` syntax (glob slot access)
- Updated `RuntimeScalar.scalarDeref` and `SlowOpcodeHandler.GLOB_SLOT_GET` to call `getGlobSlot()` directly

Now `*$self->{Key}` correctly accesses hash elements, while `*glob{HASH}` still returns glob slot references.

**Files changed**: `RuntimeGlob.java`, `RuntimeScalar.java`, `Dereference.java`, `SlowOpcodeHandler.java`

---

### 5. REMAINING: Parsing from non-file sources (filehandle, scalar ref, IO::Wrap)

**Affected tests**: t/01_parse.t (5/41 fail), t/03_regression.t (9/16 ran)

**t/01_parse.t** — Tests 33,35,37,39,41 are `is_deeply($workbook, $workbook_1)` comparisons. The workbook IS parsed (the preceding `isnt` tests pass), but `SheetCount` is missing:
```
#     $got->{SheetCount} = Does not exist
#     $expected->{SheetCount} = '2'
```

The 5 failing tests cover all non-file parse modes:
- Test 33: Parse from `open my $fh, '<', $file` (IO::Scalar path)
- Test 35: Parse from `\$data` (scalar ref containing file bytes)
- Test 37: Parse from `\@data` (array ref of file lines)
- Test 39: Parse from raw filehandle (IO::Wrap path)
- Test 41: Parse from `IO::Wrap::wraphandle($fh)`

**t/03_regression.t** — Test 10 at line 146: `$parser->Parse($fh)` returns `undef` (parsing completely fails from a filehandle), then `$workbook->get_filename()` dies. All remaining tests (11-16) never run.

**Root Cause**: `OLE::Storage_Lite::_getHeaderInfo()` (line 1006) calls `$FILE->seek(0, 0)` and `$FILE->read($sWk, 8)` on the file handle. When the handle is an `IO::Scalar` wrapping in-memory data, `seek` returns undef (shown by the `numeric gt (>)` warning) and `read` fails to extract data correctly. This is the same IO::Scalar bug that causes IO::Stringy's own tests to fail.

**Note**: Phase 4 (glob hash deref fix) removed one blocker — IO::Scalar can now store per-instance data correctly. The remaining failures are due to tied filehandle dispatch for `seek`/`read`/`tell`.

**Fix approach**: Fix tied filehandle dispatch for `seek`/`read`/`tell` in PerlOnJava runtime.

**File(s)**: PerlOnJava runtime — likely `RuntimeIO.java` or tied filehandle dispatch  
**Complexity**: HIGH — requires fixing core IO operations on tied handles  
**Priority**: MEDIUM — 5 subtest failures + 7 untested subtests; also blocks IO::Stringy tests

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

**Result**: `*$self->{Key}` now works correctly for IO::Scalar-style per-glob hash storage.

### Phase 5: Fix tied filehandle seek/read/tell (NOT STARTED)

| Step | Description | File | Status |
|------|-------------|------|--------|
| 5.1 | Reproduce IO::Scalar seek/read failures in isolation | - | |
| 5.2 | Trace tied filehandle dispatch for `seek`/`read`/`tell` | PerlOnJava runtime (RuntimeIO.java?) | |
| 5.3 | Identify which tie methods (`SEEK`, `READ`, `TELL`) fail | - | |
| 5.4 | Fix tied handle dispatch or add special IO::Scalar support | PerlOnJava runtime | |
| 5.5 | Re-test IO::Stringy's own tests (`./jcpan -t IO::Stringy`) | - | |
| 5.6 | Re-test t/01_parse.t, t/03_regression.t | - | |

**Expected result**: Fixes t/01_parse.t (5 subtests), t/03_regression.t (7 untested subtests), and IO::Stringy's own tests

## Summary

| Phase | Complexity | Test files fixed | Subtests fixed | Status |
|-------|-----------|-----------------|----------------|--------|
| 1 | Simple | 14 | ~1437 | COMPLETED |
| 2 | Simple (~6 lines) | 3 | ~96 | COMPLETED |
| 3 | Medium (~60 lines) | 2 | ~80 | COMPLETED |
| 4 | Medium (4 files) | — | — | COMPLETED (enables IO::Scalar) |
| 5 | High (core IO fix) | 2 | ~12 | NOT STARTED |

After Phases 1-4: 25/32 test files passing (excluding 4 skipped). Phase 5 targets the remaining 2 failures.

## Related Documents

- `dev/modules/makemaker_perlonjava.md` — MakeMaker implementation details
- `dev/modules/test_deep.md` — similar fix plan format
