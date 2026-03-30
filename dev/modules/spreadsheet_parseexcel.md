# Spreadsheet::ParseExcel Fix Plan

## Overview

**Module**: Spreadsheet::ParseExcel 0.66  
**Test command**: `./jcpan -t Spreadsheet::ParseExcel`  
**Status**: 25/32 test files passing (after Phase 1)

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
| t/41_test95-97j.t | **FAIL** (31/66 ran) | `ucs2` encoding not recognized |
| t/42_test95-97j-2.t | **FAIL** (0/66 ran) | `find_encoding` returns string, not object |
| t/43_test2000J.t | **FAIL** (0/22 ran) | `ucs2` encoding not recognized |
| t/44_oem.t | **FAIL** (0/14 ran) | `ucs2` encoding not recognized |
| t/45_oem-2.t | **FAIL** (0/14 ran) | `find_encoding` returns string, not object |
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

### 2. REMAINING: `ucs2` encoding not recognized

**Affected tests**: t/41_test95-97j.t (31/66 ran), t/43_test2000J.t (0/22), t/44_oem.t (0/14)

**Error**:
```
Cannot decode string from ucs2: Unknown encoding: ucs2
    at Encode.java line 219
    Spreadsheet::ParseExcel::FmtJapan at FmtJapan.pm line 111
    Spreadsheet::ParseExcel at ParseExcel.pm line 1823
```

**Root Cause**: `Encode.java`'s `getCharset()` method doesn't recognize `ucs2`. The `CHARSET_ALIASES` map (lines 25-58) has only: `utf8`, `UTF-8`, `latin1`, `iso-8859-1`, `ascii`, `utf16`, `utf-16be`, `utf-16le`. Neither the aliases nor Java's `Charset.forName("ucs2")` know this name.

**Call chain**: `ParseExcel.pm` calls `$oBook->{FmtClass}->TextFmt($sStr, 'ucs2')` for BIFF8 Unicode strings. The data is already byte-swapped to big-endian by `_SwapForUnicode`. `FmtJapan.pm` line 111 calls `decode('ucs2', $text)`.

**Java equivalent**: `UTF-16BE` — In Perl's Encode module, `ucs2` maps to `UCS-2BE`, functionally identical to `UTF-16BE` for BMP characters (which is all Excel data).

**Fix**: Add aliases to the `CHARSET_ALIASES` static initializer in `Encode.java`:
```java
CHARSET_ALIASES.put("ucs2", StandardCharsets.UTF_16BE);
CHARSET_ALIASES.put("UCS-2BE", StandardCharsets.UTF_16BE);
CHARSET_ALIASES.put("ucs-2be", StandardCharsets.UTF_16BE);
CHARSET_ALIASES.put("UCS-2", StandardCharsets.UTF_16BE);
CHARSET_ALIASES.put("UCS-2LE", StandardCharsets.UTF_16LE);
CHARSET_ALIASES.put("ucs-2le", StandardCharsets.UTF_16LE);
```

**File**: `src/main/java/org/perlonjava/runtime/perlmodule/Encode.java`  
**Complexity**: SIMPLE — ~6 lines added to static block  
**Priority**: HIGH — fixes 3 test files (96 subtests total)

---

### 3. REMAINING: `find_encoding()` returns string instead of blessed object

**Affected tests**: t/42_test95-97j-2.t (0/66), t/45_oem-2.t (0/14)

**Error**:
```
Can't locate object method "encode" via package "x-IBM942C"
    at ParseExcel.pm line 1571
```

**Root Cause**: `Encode.java`'s `find_encoding()` method (line 299) returns the Java charset canonical name as a **plain string**:
```java
return new RuntimeScalar(charset.name()).getList();  // Returns "x-IBM942C"
```

In real Perl 5, `Encode::find_encoding()` returns a **blessed encoding object** (typically `Encode::XS` or `Encode::Unicode`) with `->encode()` and `->decode()` instance methods. The TODO at Encode.java line 298 already acknowledges this: `// TODO: Create proper encoding object`.

**Call chain**:
1. `FmtJapan.pm` line 91: `$self->{encoding} = find_encoding('cp932')` — stores the result
2. Java resolves `cp932` to `Charset.forName("cp932")` whose canonical name is `x-IBM942C`
3. `FmtJapan.pm` line 112: `$self->{encoding}->encode($text)` — tries to call `encode()` on the string `"x-IBM942C"`, interpreted as a method call on package `x-IBM942C`

**Fix**: Modify `find_encoding()` to return a blessed `Encode::Encoding` hashref, and register `encode`/`decode` methods on that package:

1. Create `Encode::Encoding` class with `encode()` and `decode()` instance methods
2. `find_encoding()` returns `bless { Name => $charset_name }, 'Encode::Encoding'`
3. `Encode::Encoding::encode($self, $string)` extracts `$self->{Name}` and calls `getCharset()` → `String.getBytes(charset)`
4. `Encode::Encoding::decode($self, $octets)` does the reverse
5. Add `use overload '""' => sub { $_[0]->{Name} }` for stringify (some code uses encoding objects as strings)

**File**: `src/main/java/org/perlonjava/runtime/perlmodule/Encode.java`  
**Complexity**: MEDIUM — ~60 lines: new methods + blessed hash creation + method registration  
**Priority**: MEDIUM — fixes 2 test files (80 subtests total), also benefits any module using `find_encoding()->encode()`

---

### 4. REMAINING: Parsing from non-file sources (filehandle, scalar ref, IO::Wrap)

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

For t/01_parse.t, parsing partially succeeds (workbook has sheets, cell data, etc.) but some OLE header properties aren't read correctly, causing `SheetCount` to be missing. For t/03_regression.t, parsing fails entirely (returns undef), likely because the filehandle isn't being converted to IO::Scalar the same way.

**Underlying issue**: PerlOnJava's `seek`/`read`/`tell` on in-memory IO handles (IO::Scalar) are broken. IO::Scalar relies on tied filehandle mechanics — `SEEK`, `READ`, `TELL` tie methods — which appear to have issues in PerlOnJava.

**Fix approach**: Fix the IO::Scalar `seek`/`read`/`tell` methods. This likely requires fixing one or more of:
- Tied filehandle dispatch for `seek`/`read`/`tell` (PerlOnJava runtime)
- OR `sysseek`/`sysread` on scalar refs (if IO::Scalar uses these)
- OR the 4-argument `read($fh, $buf, $len, $offset)` form

This is the same root cause as the IO::Stringy test failures documented in the dependency tree.

**File(s)**: PerlOnJava runtime — likely `RuntimeIO.java` or tied filehandle dispatch  
**Complexity**: HIGH — requires fixing core IO operations on tied handles  
**Priority**: MEDIUM — 5 subtest failures + 7 untested subtests; also blocks IO::Stringy tests

---

## Fix Plan

### Phase 1: Fix MakeMaker flat-layout support (COMPLETED)

| Step | Description | Status |
|------|-------------|--------|
| 1.1 | Add root-directory `.pm` scan fallback to `_install_pure_perl()` | DONE |
| 1.2 | Rebuild (`make dev`) | DONE |
| 1.3 | Clear Crypt::RC4 build state and re-install | DONE |
| 1.4 | Verify `./jperl -e 'use Crypt::RC4; print "OK\n"'` works | DONE |
| 1.5 | Re-test Spreadsheet::ParseExcel | DONE |

**Result**: 21 failing test files -> 7 failing. 4/605 subtests -> 1442/1447 subtests passing.

### Phase 2: Add `ucs2` charset alias (SIMPLE)

| Step | Description | File | Status |
|------|-------------|------|--------|
| 2.1 | Add `ucs2` → `UTF-16BE` alias to `CHARSET_ALIASES` | `Encode.java` | |
| 2.2 | Add `UCS-2`, `UCS-2BE`, `UCS-2LE` variants | `Encode.java` | |
| 2.3 | Optionally add `sjis` → `Shift_JIS` alias | `Encode.java` | |
| 2.4 | Run `make` to verify unit tests pass | - | |
| 2.5 | Re-test t/41, t/43, t/44 | - | |

**Expected result**: Fixes t/41_test95-97j.t, t/43_test2000J.t, t/44_oem.t (96 subtests)

### Phase 3: Make `find_encoding()` return blessed object (MEDIUM)

| Step | Description | File | Status |
|------|-------------|------|--------|
| 3.1 | Add `Encode::Encoding::encode()` static method | `Encode.java` | |
| 3.2 | Add `Encode::Encoding::decode()` static method | `Encode.java` | |
| 3.3 | Register methods in `initialize()` | `Encode.java` | |
| 3.4 | Modify `find_encoding()` to return `bless { Name => $name }, 'Encode::Encoding'` | `Encode.java` | |
| 3.5 | Add stringify overload on `Encode::Encoding` (some code uses encoding as string) | `Encode.java` | |
| 3.6 | Run `make` to verify unit tests pass | - | |
| 3.7 | Re-test t/42, t/45 | - | |

**Expected result**: Fixes t/42_test95-97j-2.t, t/45_oem-2.t (80 subtests)

### Phase 4: Fix IO::Scalar seek/read/tell (HIGH COMPLEXITY)

| Step | Description | File | Status |
|------|-------------|------|--------|
| 4.1 | Reproduce IO::Scalar seek/read failures in isolation | - | |
| 4.2 | Trace tied filehandle dispatch for `seek`/`read`/`tell` | PerlOnJava runtime (RuntimeIO.java?) | |
| 4.3 | Identify which tie methods (`SEEK`, `READ`, `TELL`) fail | - | |
| 4.4 | Fix tied handle dispatch or add special IO::Scalar support | PerlOnJava runtime | |
| 4.5 | Re-test IO::Stringy's own tests (`./jcpan -t IO::Stringy`) | - | |
| 4.6 | Re-test t/01_parse.t, t/03_regression.t | - | |

**Expected result**: Fixes t/01_parse.t (5 subtests), t/03_regression.t (7 untested subtests), and IO::Stringy's own tests

## Summary

| Phase | Complexity | Test files fixed | Subtests fixed | Priority |
|-------|-----------|-----------------|----------------|----------|
| 1 (DONE) | Simple | 14 | ~1437 | CRITICAL |
| 2 | Simple (~6 lines) | 3 | ~96 | HIGH |
| 3 | Medium (~60 lines) | 2 | ~80 | MEDIUM |
| 4 | High (core IO fix) | 2 | ~12 | MEDIUM |

After all phases: 32/32 test files passing (excluding 4 skipped).

## Related Documents

- `dev/modules/makemaker_perlonjava.md` — MakeMaker implementation details
- `dev/modules/test_deep.md` — similar fix plan format
