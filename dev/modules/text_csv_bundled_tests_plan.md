# Text::CSV Bundled Module Tests Plan

## Goal

Add the original CPAN Text::CSV 2.06 test suite to `src/test/resources/module/Text-CSV/`
and make all tests pass against the **bundled** Text::CSV implementation (Java-backed via
Apache Commons CSV).

## Architecture

The bundled Text::CSV has two layers:
- **Perl side**: `src/main/perl/lib/Text/CSV.pm` — constructor, accessors, convenience methods
- **Java side**: `src/main/java/org/perlonjava/runtime/perlmodule/TextCsv.java` — core `parse()`
  and `combine()` using Apache Commons CSV

The CPAN tests were written for Text::CSV + Text::CSV_PP (pure Perl backend). The bundled
version is simpler but needs API compatibility for the tests to pass.

## Test Files

40 test files + `t/util.pl` helper + 2 CSV data files copied from Text-CSV-2.06 CPAN
distribution to `src/test/resources/module/Text-CSV/`.

## Current Status

- **Baseline run**: 2/40 pass (60_samples.t, and one other)
- **Root cause**: Most failures are due to missing methods/accessors, not core parse/combine bugs

## Implementation Phases

### Phase 1: Missing Methods (Easy) — DONE partially

Methods already added to CSV.pm:
- `version()` — returns `$VERSION`
- `status()` — returns `_STATUS` (last parse/combine result)
- `error_input()` — returns `_ERROR_INPUT` (input that caused last error)
- `is_pp()` / `is_xs()` — return 0 (neither pure Perl nor XS; Java backend)
- `module()` — returns "Text::CSV"

### Phase 2: Constructor Fixes

| Fix | Impact |
|-----|--------|
| Attribute validation in `new()` | t/81_subclass.t, t/12_acc.t |
| Global error state for class-method `error_diag()` | t/81_subclass.t |
| `ref $class \|\| $class` for object-method `new()` | t/10_base.t |
| Attribute aliases: `sep`→`sep_char`, `quote`→`quote_char`, `escape`→`escape_char`, `quote_always`→`always_quote`, `verbose_diag`→`diag_verbose` | t/12_acc.t |

### Phase 3: Accessor Methods

Many tests (especially t/12_acc.t with 245 tests) exercise getter/setter methods for every
attribute. Need to add generic accessors for:

| Method | Default | Notes |
|--------|---------|-------|
| `sep` / `sep_char` | `,` | Already exists |
| `quote` / `quote_char` | `"` | Already exists, fix undef accept |
| `escape_char` | undef | Already exists |
| `binary` | 0 | Already exists, fix setter to use `@_` |
| `auto_diag` | 0 | Already exists |
| `always_quote` | 0 | Already exists |
| `eol` | undef | Already exists |
| `allow_loose_quotes` | 0 | Need accessor |
| `allow_loose_escapes` | 0 | Need accessor |
| `allow_unquoted_escape` | 0 | Need accessor |
| `allow_whitespace` | 0 | Need accessor |
| `blank_is_undef` | 0 | Need accessor |
| `empty_is_undef` | 0 | Need accessor |
| `quote_empty` | 0 | Need accessor |
| `quote_space` | 1 | Need accessor |
| `quote_binary` | 1 | Need accessor |
| `quote_null` / `escape_null` | 1 | Need accessor |
| `decode_utf8` | 1 | Need accessor |
| `keep_meta_info` | 0 | Need accessor |
| `strict` | 0 | Need accessor |
| `strict_eol` | 0 | Need accessor |
| `formula` | 'none' | Need accessor |
| `verbatim` | 0 | Need accessor |
| `diag_verbose` | 0 | Need accessor |
| `undef_str` | undef | Need accessor |
| `comment_str` | undef | Need accessor |
| `skip_empty_rows` | 0 | Need accessor |
| `record_number` | 0 | Need accessor (read-only, incremented by getline) |
| `backend` | class method | Returns backend module name |
| `known_attributes` | class/instance | Returns list of all known attribute names |

### Phase 4: Meta Info & Flags

Tests in t/15_flags.t (229 tests) require per-field metadata tracking:
- `meta_info()` — returns arrayref of per-field flag bitmasks after parse
- `is_quoted($n)` — check if field N was quoted
- `is_binary($n)` — check if field N contained binary chars
- `is_missing($n)` — check if field N was missing

This requires changes to the Java `parse()` method to track metadata.

### Phase 5: Constants Export

t/16_import.t tests constant exports:
- Type constants: `PV` (0), `IV` (1), `NV` (2), `CSV_TYPE_PV`, `CSV_TYPE_IV`, `CSV_TYPE_NV`
- Flag constants: `CSV_FLAGS_IS_QUOTED`, `CSV_FLAGS_IS_BINARY`, `CSV_FLAGS_ERROR_IN_FIELD`, `CSV_FLAGS_IS_MISSING`

Some flag constants already exist. Need to add type constants and proper `import()` method.

### Phase 6: EOL Handling in combine/string

The CPAN Text::CSV_PP appends `eol` to `string()` output after `combine()`.
The bundled version only appends eol in `print()`. Need to change `combine()` to
include eol in the stored string.

### Phase 7: File I/O Improvements

- `eof()` method — track EOF state from last getline
- `getline_hr_all()` — read all rows as hashrefs
- Fix `print()` to die on non-ARRAY argument (not just return 0)
- Fix `csv()` to handle instance method calls (`$csv->csv(...)`)

### Phase 8: Types Support

t/30_types.t tests column type coercion:
- `types()` getter/setter
- Type constants for IV/NV/PV coercion during parse

### Phase 9: Advanced Features (if needed)

These are lower priority and may require significant work:
- Binary mode enforcement (reject `\n`/`\r` without `binary => 1`)
- Callback support (t/79_callbacks.t)
- Fragment parsing (t/78_fragment.t)
- Formula handling (t/66_formula.t)
- Strict mode (t/71_strict.t)
- Stream support (t/92_stream.t)
- UTF-8 handling (t/50_utf8.t, t/51_utf8.t)
- Comment handling (t/47_comment.t)

### Tests Expected to Need Special Handling

| Test | Issue |
|------|-------|
| t/00_pod.t | Needs Test::Pod — skip if not available |
| t/55_combi.t | 25,119 subtests — combinatorial, may be slow |
| t/70_rt.t | Contains raw non-UTF-8 bytes, 20,469 subtests |
| t/71_pp.t | Tests PP-specific internals |
| t/76_magic.t | Requires Tie::Scalar |
| t/85_util.t | Requires Encode with advanced encodings |

## Progress Tracking

### Current Status: Phase 1 in progress

### Completed
- [x] Copy CPAN test files to module/Text-CSV/ (2026-04-08)
- [x] Add version(), status(), error_input(), is_pp(), is_xs(), module()
- [x] Add attribute validation in new()
- [x] Fix quote_char/sep_char to accept undef
- [x] Add global error state for class-method error_diag()
- [x] Fix new() to handle object-method calls (ref $class || $class)

### Next Steps
1. Add all missing accessor methods (AUTOLOAD or explicit)
2. Add meta_info/is_quoted/is_binary/is_missing
3. Fix EOL in combine/string
4. Add eof(), getline_hr_all(), types()
5. Add constants export (PV, IV, NV)
6. Run tests iteratively and fix remaining failures
