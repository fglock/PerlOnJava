# Text::CSV Bundled Module Tests Plan

## Goal

Add the original CPAN Text::CSV 2.06 test suite to `src/test/resources/module/Text-CSV/`
and make all tests pass against a bundled Text::CSV stack that uses Java as the XS backend.

## Architecture (Revised)

Instead of reimplementing hundreds of methods in our simplified CSV.pm, we now use the
**original CPAN modules** with Java replacing XS:

```
Text::CSV (CPAN 2.06 wrapper)
  └─ tries Text::CSV_XS first, falls back to Text::CSV_PP
       │
       ├─ Text::CSV_XS (our Java-backed module)
       │    └─ inherits from Text::CSV_PP
       │    └─ $VERSION = "1.61" (satisfies >= 1.60 check)
       │    └─ XSLoader::load('TextCsv') for Java parse/combine
       │    └─ overrides parse()/combine() with Java acceleration
       │
       └─ Text::CSV_PP (CPAN 2.06, 6454 lines, pure Perl)
            └─ complete implementation: all accessors, meta_info,
               callbacks, types, formula, etc.
```

**Files:**
- `src/main/perl/lib/Text/CSV.pm` — CPAN 2.06 wrapper (146 lines of code)
- `src/main/perl/lib/Text/CSV_PP.pm` — CPAN 2.06 pure-Perl backend (6454 lines)
- `src/main/perl/lib/Text/CSV_XS.pm` — our Java-backed XS replacement
- `src/main/java/org/perlonjava/runtime/perlmodule/TextCsv.java` — Java parse/combine

**Why this approach:**
- The CPAN `Text::CSV_PP` already passes 39/40 tests (52,356/52,360 subtests) on PerlOnJava
  (documented in `dev/modules/text_csv_fix_plan.md`)
- All complex logic (accessors, meta_info, callbacks, types, formula, etc.) is handled
  by the battle-tested CPAN code
- Java only needs to implement the performance-critical parse/combine operations
- No need to reimplement hundreds of methods

## Test Files

40 test files + `t/util.pl` helper + 2 CSV data files copied from Text-CSV-2.06 CPAN
distribution to `src/test/resources/module/Text-CSV/`.

## Implementation Plan

### Phase 1: Bundle CPAN Modules

1. Replace `src/main/perl/lib/Text/CSV.pm` with CPAN 2.06 wrapper
2. Copy `Text/CSV_PP.pm` from CPAN to `src/main/perl/lib/Text/`
3. Create `Text/CSV_XS.pm` that inherits from `Text::CSV_PP`:
   - `$VERSION = "1.61"` (passes Text::CSV's `>= 1.60` check)
   - Inherits all methods from CSV_PP via `@ISA`
   - Exports same constants/functions
   - Later: override parse/combine with Java acceleration

### Phase 2: Fix TextCsv.java Registration

The existing `TextCsv.java` registers methods on `Text::CSV` package. After the refactor:
- Either update it to register on `Text::CSV_XS` package
- Or disable it if Text::CSV_PP handles everything
- The XSLoader::load('TextCsv') call in old CSV.pm needs to be removed/updated

### Phase 3: Run Tests and Debug

With CPAN modules bundled, most tests should pass immediately (39/40 based on prior work).
Known remaining issue from `text_csv_fix_plan.md`:
- t/70_rt.t: 4 subtest failures (raw non-UTF-8 bytes, IO::Handle edge cases)

### Phase 4: Java Acceleration (Optional, Future)

Override `parse()` and `combine()` in `Text::CSV_XS` to delegate to Java:
- `Parse($str, $fields, $fflags)` — Java via XSLoader
- `Combine(\$str, \@fields, $useIO)` — Java via XSLoader
- `SetDiag($code, $msg)` — Java error management
- `_cache_set` / `_cache_get_eolt` — Java cache

This is optional since CSV_PP already works. Add it for performance.

## XS API Contract (for future Java implementation)

The XS module must provide these C-level functions (via XSLoader):

| XS Method | Called From | Purpose |
|-----------|------------|---------|
| `Parse($str, $fields, $fflags)` | `parse()` | Core CSV parsing |
| `Combine(\$str, \@fields, $useIO)` | `combine()`, `print()` | Core CSV combining |
| `SetDiag($code, $msg?)` | everywhere | Error diagnostics |
| `_cache_set($idx, $val)` | accessor setters | XS state cache |
| `_cache_get_eolt()` | `eol_type()` | EOL type detection |
| `_cache_diag()` | debugging | Cache dump |
| `print($io, $fields)` | `print()` | Direct IO print |
| `getline($io)` | `getline()` | Direct IO getline |
| `getline_all($io, ...)` | `getline_all()` | Batch IO getline |
| `error_input()` | `error_input()` | Last error input |

## Progress Tracking

### Current Status: Phase 1 in progress

### Completed
- [x] Copy CPAN test files to module/Text-CSV/ (2026-04-08)
- [x] Architecture decision: use CPAN Text::CSV + CSV_PP + Java-backed CSV_XS
- [x] Analyzed XS API contract from Text-CSV_XS-1.61 source

### In Progress
- [ ] Bundle CPAN Text::CSV.pm (replace our custom one)
- [ ] Bundle CPAN Text::CSV_PP.pm
- [ ] Create Text::CSV_XS.pm (inherits from CSV_PP)

### Next Steps
1. Handle TextCsv.java registration (update or disable)
2. Build and run tests
3. Debug any remaining failures
4. (Future) Add Java acceleration for parse/combine
