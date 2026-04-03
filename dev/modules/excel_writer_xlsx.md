# Excel::Writer::XLSX Fix Plan

## Overview

**Module**: Excel::Writer::XLSX 1.15
**Test command**: `./jcpan -j 8 -t Excel::Writer::XLSX`
**Status**: 1243/1247 programs pass (99.7%), 5110/5115 subtests pass (99.9%)

## Dependency Tree

| Dependency | Status | Notes |
|-----------|--------|-------|
| **Archive::Zip** >= 1.30 | PASS | Java-backed impl; `setErrorHandler` stub added, `use FileHandle ()` added |
| **File::Temp** >= 0.19 | PASS | Core module, works |
| **IO::File** >= 1.14 | PASS | Core module, works |

## Test Results Summary

### Current Status: 1243/1247 programs pass, 5110/5115 subtests pass

Tests are in subdirectories: `t/chart/`, `t/chartsheet/`, `t/drawing/`, `t/package/`, `t/regression/`, `t/utility/`, `t/workbook/`, `t/worksheet/`

| Test Group | Total | Pass | Fail | Notes |
|-----------|-------|------|------|-------|
| t/chart/ | ~47 | 47 | 0 | All pass |
| t/chartsheet/ | 4 | 4 | 0 | All pass (password tests fixed) |
| t/drawing/ | ~23 | 23 | 0 | All pass |
| t/package/ | ~50 | 49 | 1 | `styles/sub_write_num_fmts.t` ('' vs undef) |
| t/regression/ | ~800+ | ~800+ | 0 | All pass |
| t/utility/ | ~15 | 15 | 0 | All pass (quote_sheetname fixed) |
| t/workbook/ | ~18 | 18 | 0 | All pass |
| t/worksheet/ | ~90 | 88 | 2 | `sub_write_page_setup.t`, `sub_write_print_options.t` ('' vs undef) |

---

## Remaining Failures (4 programs, 5 subtests)

### 1. Empty string `''` vs `undef` return value (3 test files, 3 subtests)

**Affected tests**:
- `t/package/styles/sub_write_num_fmts.t` (1/2 fail)
- `t/worksheet/sub_write_page_setup.t` (1/6 fail)
- `t/worksheet/sub_write_print_options.t` (1/8 fail)

**Error pattern**:
```
#          got: ''
#     expected: undef
```

**Root Cause**: XML writer methods return `''` (empty string) instead of `undef` when there is nothing to write. In Perl, `''` and `undef` are different values - `undef` means "no value" while `''` means "empty string value". The test uses `is()` which distinguishes them.

**Investigation needed**: Trace the specific XML writer method being called (e.g., `_write_num_fmts()`, `_write_page_setup()`, `_write_print_options()`) and find where it returns `''` instead of `undef`. This is likely a PerlOnJava parity issue in:
- How subroutines return values when no explicit `return` is used
- How `$self->{_writer}->xml_data_element()` or similar XMLwriter methods behave when called with no data to write
- How the `_write_*` methods short-circuit when there are no elements to emit

**Fix approach**:
1. Run the failing test in Perl vs jperl and compare output
2. Add debug prints to the `_write_num_fmts` sub to trace where `''` vs `undef` diverges
3. Fix the runtime behavior or the specific method

### 2. Emoticons Unicode quoting in `quote_sheetname` (FIXED)

**Previously affected test**: `t/utility/quote_sheetname.t` (2/100 failed)

**Was**: `\w` in regex didn't match Latin-1 accented characters (like `é`) when the string had the UTF-8 flag set. PerlOnJava only used the Unicode-aware regex pattern for strings containing characters > U+00FF.

**Fixed in commit 3f85c7cd3**: Changed regex pattern selection to use Unicode semantics whenever the input string has the UTF-8 flag, matching Perl's behavior.

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

---

## Open Phase: Fix '' vs undef return value (TODO)

| Step | Description | File | Status |
|------|-------------|------|--------|
| 5.1 | Reproduce in jperl: run `sub_write_num_fmts.t` and trace return value | - | TODO |
| 5.2 | Compare jperl vs perl: what does the `_write_*` method return? | - | TODO |
| 5.3 | Identify PerlOnJava runtime parity issue (return value semantics) | - | TODO |
| 5.4 | Fix the runtime or codegen to return `undef` instead of `''` | - | TODO |
| 5.5 | Verify all 3 affected tests pass | - | TODO |
| 5.6 | Run `make` to verify unit tests pass | - | TODO |

**Expected result**: Fixes the last 3 failing test programs (5 subtests), achieving 100% pass rate.

---

## Summary

| Phase | Description | Tests fixed | Status |
|-------|-----------|------------|--------|
| 0 | glob() + MakeMaker | ALL (1247 discovered) | COMPLETED |
| 1 | Archive::Zip (setErrorHandler + FileHandle) | ~933 programs | COMPLETED |
| 2 | `\p{Emoticons}` regex | ~20+ tests | COMPLETED |
| 3 | split scalar context (password hash) | 3 subtests | COMPLETED |
| 4 | regex Unicode semantics (Latin-1 \w) | 2 subtests | COMPLETED |
| 5 | '' vs undef return value | 3 programs, 5 subtests | TODO |

## Branch & PR

- Branch: `fix/glob-directory-wildcards`
- PR: https://github.com/fglock/PerlOnJava/pull/430

## Related Documents

- `dev/modules/spreadsheet_parseexcel.md` -- similar module fix plan
- `dev/modules/makemaker_perlonjava.md` -- MakeMaker implementation details
