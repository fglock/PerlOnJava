# Excel::Writer::XLSX Fix Plan

## Overview

**Module**: Excel::Writer::XLSX 1.15
**Test command**: `./jcpan --jobs 8 -t Excel::Writer::XLSX`
**Status**: 1247/1247 programs pass (100%), 5115/5115 subtests pass (100%)

## Dependency Tree

| Dependency | Status | Notes |
|-----------|--------|-------|
| **Archive::Zip** >= 1.30 | PASS | Java-backed impl; `setErrorHandler` stub added, `use FileHandle ()` added |
| **File::Temp** >= 0.19 | PASS | Core module, works |
| **IO::File** >= 1.14 | PASS | Core module, works |

## Test Results Summary

### Current Status: 1247/1247 programs pass, 5115/5115 subtests pass (100%)

Tests are in subdirectories: `t/chart/`, `t/chartsheet/`, `t/drawing/`, `t/package/`, `t/regression/`, `t/utility/`, `t/workbook/`, `t/worksheet/`

| Test Group | Total | Pass | Fail | Notes |
|-----------|-------|------|------|-------|
| t/chart/ | ~47 | 47 | 0 | All pass |
| t/chartsheet/ | 4 | 4 | 0 | All pass (password tests fixed) |
| t/drawing/ | ~23 | 23 | 0 | All pass |
| t/package/ | ~50 | 50 | 0 | All pass |
| t/regression/ | ~800+ | ~800+ | 0 | All pass |
| t/utility/ | ~15 | 15 | 0 | All pass (quote_sheetname fixed) |
| t/workbook/ | ~18 | 18 | 0 | All pass |
| t/worksheet/ | ~90 | 90 | 0 | All pass |

---

## Completed Phases

### Phase 0: Fix glob() and MakeMaker (COMPLETED 2026-04-03)

| Step | Description | File | Status |
|------|-------------|------|--------|
| 0.1 | Add recursive glob expansion for directory wildcards | `ScalarGlobOperator.java` | DONE |
| 0.2 | Extract and use `test => { TESTS => ... }` parameter | `ExtUtils/MakeMaker.pm` | DONE |
| 0.3 | Verify `glob("t/*/*.t")` returns 1152 files | - | DONE |
| 0.4 | Verify `make` passes | - | DONE |

**Result**: `jcpan -t` now discovers and runs all 1247 test files instead of 0.

### Phase 1: Implement missing Archive::Zip features (COMPLETED 2026-04-03)

| Step | Description | File | Status |
|------|-------------|------|--------|
| 1.1 | Add `setErrorHandler` as stub function | `Archive/Zip.pm` | DONE |
| 1.2 | Add `use FileHandle ()` to match CPAN Archive::Zip | `Archive/Zip.pm` | DONE |
| 1.3 | Verify regression tests unblocked | - | DONE |

**Result**: Unblocked all ~800+ regression tests + 133 image/hyperlink tests that needed FileHandle.

### Phase 2: Fix `\p{Emoticons}` regex support (COMPLETED 2026-04-03)

| Step | Description | File | Status |
|------|-------------|------|--------|
| 2.1 | Add `\p{}`/`\P{}` translation inside character classes | `RegexPreprocessorHelper.java` | DONE |
| 2.2 | Add Emoticons Unicode block mapping | `UnicodeResolver.java` | DONE |
| 2.3 | Verify regex `[^\w\.\p{Emoticons}]` compiles and matches | - | DONE |

**Result**: Emoticons regex property works in patterns and character classes.

### Phase 3: Fix split scalar context for password hash (COMPLETED 2026-04-03)

| Step | Description | File | Status |
|------|-------------|------|--------|
| 3.1 | Fix JVM backend: `emitSplitArgs` with SCALAR context | `EmitOperator.java` | DONE |
| 3.2 | Fix interpreter backend: split special case | `CompileBinaryOperator.java` | DONE |
| 3.3 | Verify `split //, reverse $str` matches Perl | - | DONE |

**Result**: Password hash tests pass. `split //, reverse $str` correctly reverses the string.

### Phase 4: Fix regex Unicode semantics for UTF-8 Latin-1 strings (COMPLETED 2026-04-03)

| Step | Description | File | Status |
|------|-------------|------|--------|
| 4.1 | Use Unicode regex pattern for all UTF-8 strings (not just >U+00FF) | `RuntimeRegex.java` | DONE |
| 4.2 | Verify `\w` matches `é` with `use utf8` | - | DONE |
| 4.3 | Run uni/ tests to verify no regressions | - | DONE |

**Result**: `quote_sheetname.t` all 100 tests pass. Latin-1 accented chars match `\w` when UTF-8 flagged.

### Phase 5: Fix `open '>' \$scalar` to preserve undef (COMPLETED 2026-04-03)

| Step | Description | File | Status |
|------|-------------|------|--------|
| 5.1 | Identify root cause: `open '>', \$scalar` sets undef to `''` | `RuntimeIO.java` | DONE |
| 5.2 | Fix: only truncate if scalar was already defined | `RuntimeIO.java` | DONE |
| 5.3 | Verify `open '>', \$undef_var` keeps undef (matches Perl) | - | DONE |
| 5.4 | Verify `open '>', \$defined_var` truncates to `''` (matches Perl) | - | DONE |
| 5.5 | Run `make` to verify unit tests pass | - | DONE |
| 5.6 | Run full Excel::Writer::XLSX test suite | - | DONE |

**Result**: All 1247 programs pass, all 5115 subtests pass. 100% pass rate achieved.

---

## Summary

| Phase | Description | Tests fixed | Status |
|-------|-----------|------------|--------|
| 0 | glob() + MakeMaker | ALL (1247 discovered) | COMPLETED |
| 1 | Archive::Zip (setErrorHandler + FileHandle) | ~933 programs | COMPLETED |
| 2 | `\p{Emoticons}` regex | ~20+ tests | COMPLETED |
| 3 | split scalar context (password hash) | 3 subtests | COMPLETED |
| 4 | regex Unicode semantics (Latin-1 \w) | 2 subtests | COMPLETED |
| 5 | `open '>' \$scalar` undef preservation | 3 programs, 5 subtests | COMPLETED |

## Branch & PR

- Branch: `fix/glob-directory-wildcards`
- PR: https://github.com/fglock/PerlOnJava/pull/430

## Related Documents

- `dev/modules/spreadsheet_parseexcel.md` -- similar module fix plan
- `dev/modules/makemaker_perlonjava.md` -- MakeMaker implementation details
