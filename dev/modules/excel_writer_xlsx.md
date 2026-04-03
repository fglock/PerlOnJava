# Excel::Writer::XLSX Fix Plan

## Overview

**Module**: Excel::Writer::XLSX 1.15
**Test command**: `./jcpan -j 8 -t Excel::Writer::XLSX`
**Status**: WIP -- ~156/503 test files pass (run incomplete, ~744 tests not yet reached)

## Dependency Tree

| Dependency | Status | Notes |
|-----------|--------|-------|
| **Archive::Zip** >= 1.30 | PARTIAL | Java-backed impl via XSLoader; missing `setErrorHandler` and possibly other methods |
| **File::Temp** >= 0.19 | PASS | Core module, works |
| **IO::File** >= 1.14 | PASS | Core module, works |

## Test Results Summary

### Current Status: ~156/503 visible test files pass (incomplete run)

Tests are in subdirectories: `t/chart/`, `t/chartsheet/`, `t/drawing/`, `t/package/`, `t/regression/`, `t/utility/`, `t/workbook/`, `t/worksheet/`

| Test Group | Total | Pass | Fail | Notes |
|-----------|-------|------|------|-------|
| t/chart/ | ~40 | ~39 | 1 | `sub_add_series.t` fails (Emoticons regex) |
| t/chartsheet/ | ~4 | 3 | 1 | `sub_write_sheet_protection.t` (password hash) |
| t/drawing/ | ~18 | 18 | 0 | All pass |
| t/package/ | ~50 | ~49 | 1 | `styles/sub_write_num_fmts.t` ('' vs undef) |
| t/regression/ | ~800+ | 0 | ALL | Blocked by `Archive::Zip::setErrorHandler` |
| t/utility/ | ? | ? | ? | Not yet reached in run |
| t/workbook/ | ? | ? | ? | Not yet reached in run |
| t/worksheet/ | ? | ? | ? | Not yet reached in run |

---

## Error Categories

### 1. `Undefined subroutine &Archive::Zip::setErrorHandler` (P0 -- blocks ~800+ tests)

**Affected tests**: All `t/regression/*.t` files
**Error**:
```
Undefined subroutine &Archive::Zip::setErrorHandler called at t/regression/....t line NN.
```

**Root Cause**: PerlOnJava's Archive::Zip is a Java-backed implementation (`ArchiveZip.java`) that doesn't implement `setErrorHandler`. The test infrastructure (`t/lib/TestFunctions.pm` line 185) calls `Archive::Zip::setErrorHandler( sub { } )` to suppress error output during ZIP comparison. This is a package-level function, not a method.

**Usage in test code**:
```perl
# t/lib/TestFunctions.pm:185
Archive::Zip::setErrorHandler( sub { } );
```

---

### 2. `\p{Emoticons}` Unicode property not supported (P1 -- ~20+ tests)

**Affected tests**: `t/chart/sub_add_series.t` (3/5 fail), plus many regression tests (masked by P0)
**Error**:
```
Regex compilation failed: Unknown character property name {Emoticons} near index 18
[^\w\.\p{Emoticons}]
```

**Root Cause**: Java's `java.util.regex` uses `\p{InEmoticons}` for the Unicode Emoticons block (U+1F600-U+1F64F), while Perl uses `\p{Emoticons}`. PerlOnJava's regex engine needs to map the Perl property name to the Java equivalent.

**Usage in module**:
```perl
# lib/Excel/Writer/XLSX/Utility.pm:237,242
if ( $sheetname =~ /[^\w\.\p{Emoticons}]/ ) { ... }
elsif ( $sheetname =~ /^[\d\.\p{Emoticons}]/ ) { ... }
```

---

### 3. Password hash produces wrong result (P3 -- 1 test file, 3 subtests)

**Affected test**: `t/chartsheet/sub_write_sheet_protection.t` (3/7 fail)
**Error**:
```
got: '<sheetProtection password="996B" content="1" objects="1"/>'
expected: '<sheetProtection password="83AF" content="1" objects="1"/>'
```

**Root Cause**: The `_encode_password` method in `Worksheet.pm` uses bitwise operations (`>>`, `<<`, `&`, `|`, `^`) to compute a 15-bit hash. PerlOnJava likely has an operator precedence or integer arithmetic difference in the expression:
```perl
$hash = ( ( $hash >> 14 ) & 0x01 ) | ( ( $hash << 1 ) & 0x7fff );
```
Need to trace intermediate values to isolate the discrepancy.

---

### 4. Empty string vs `undef` return value (P4 -- 1 test file, 1 subtest)

**Affected test**: `t/package/styles/sub_write_num_fmts.t` (1/2 fail)
**Error**:
```
got: ''
expected: undef
```

**Root Cause**: A function returns `''` instead of `undef`. Likely a PerlOnJava parity issue in how empty/undefined values are returned from XML writer methods.

---

### 5. `FileHandle->new()` not found (P2 -- ~6 tests, masked by P0)

**Affected tests**: `t/regression/autofit13.t`, `t/regression/background03.t` through `background07.t`
**Error**:
```
Can't locate object method "new" via package "FileHandle"
  at .../Excel/Writer/XLSX/Workbook.pm line 1860.
```

**Root Cause**: `Workbook.pm` calls `FileHandle->new(...)` without `use FileHandle;`. Perl core autoloads this, but PerlOnJava may not have `FileHandle.pm` in its module path or its autoload mechanism doesn't handle it.

---

## Fix Plan

### Phase 0: Fix glob() and MakeMaker (COMPLETED 2026-04-03)

| Step | Description | File | Status |
|------|-------------|------|--------|
| 0.1 | Add recursive glob expansion for directory wildcards | `ScalarGlobOperator.java` | DONE |
| 0.2 | Extract and use `test => { TESTS => ... }` parameter | `ExtUtils/MakeMaker.pm` | DONE |
| 0.3 | Verify `glob("t/*/*.t")` returns 1152 files | - | DONE |
| 0.4 | Verify `make` passes | - | DONE |

**Result**: `jcpan -t` now discovers and runs all 1247 test files instead of 0.

### Phase 1: Implement missing Archive::Zip features (TODO)

| Step | Description | File | Status |
|------|-------------|------|--------|
| 1.1 | Add `setErrorHandler` as package function accepting coderef | `ArchiveZip.java` + `Archive/Zip.pm` | TODO |
| 1.2 | Wire error handler into zip read/write operations | `ArchiveZip.java` | TODO |
| 1.3 | Verify `Archive::Zip::setErrorHandler(sub {})` works | - | TODO |
| 1.4 | Run `make` to verify unit tests pass | - | TODO |
| 1.5 | Re-run `jcpan -t` to get true regression test pass rate | - | TODO |

**Expected result**: Unblocks all ~800+ regression tests, revealing true pass/fail rate.

### Phase 2: Fix `\p{Emoticons}` regex support (TODO)

| Step | Description | File | Status |
|------|-------------|------|--------|
| 2.1 | Map Perl `\p{Emoticons}` to Java `\p{InEmoticons}` in regex engine | `RuntimeRegex.java` or regex preprocessing | TODO |
| 2.2 | Verify regex `[^\w\.\p{Emoticons}]` compiles and matches | - | TODO |
| 2.3 | Run `make` to verify unit tests pass | - | TODO |

**Expected result**: Fixes `t/chart/sub_add_series.t` and unblocks Emoticons-related regression tests.

### Phase 3: Fix password hash bitwise operations (TODO)

| Step | Description | File | Status |
|------|-------------|------|--------|
| 3.1 | Trace `_encode_password("password")` step-by-step in jperl vs perl | - | TODO |
| 3.2 | Identify and fix bitwise operation discrepancy | Runtime operator implementation | TODO |
| 3.3 | Run password encode test: `t/worksheet/worksheet_encode_password.t` | - | TODO |
| 3.4 | Run `make` to verify unit tests pass | - | TODO |

**Expected result**: Fixes 3 subtests in `t/chartsheet/sub_write_sheet_protection.t`.

### Phase 4: Fix '' vs undef and FileHandle issues (TODO)

| Step | Description | File | Status |
|------|-------------|------|--------|
| 4.1 | Investigate '' vs undef in `sub_write_num_fmts.t` | - | TODO |
| 4.2 | Ensure `FileHandle->new()` works (add stub or fix autoloading) | - | TODO |
| 4.3 | Run `make` to verify unit tests pass | - | TODO |

**Expected result**: Fixes 1 subtest + unblocks ~6 regression tests.

## Summary

| Phase | Complexity | Tests unblocked | Status |
|-------|-----------|----------------|--------|
| 0 | Medium (2 files) | ALL (1247 test files discovered) | COMPLETED |
| 1 | Simple-Medium | ~800+ regression tests | TODO |
| 2 | Simple | ~20+ tests | TODO |
| 3 | Medium | 3 subtests | TODO |
| 4 | Simple | ~7 tests | TODO |

## Related Documents

- `dev/modules/spreadsheet_parseexcel.md` -- similar module fix plan
- `dev/modules/makemaker_perlonjava.md` -- MakeMaker implementation details
