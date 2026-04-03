# Text::CSV Fix Plan

## Problem

`./jcpan -j 4 -t Text::CSV` fails. Three root causes were identified:

1. **`%_` rejected under strict vars** — PerlOnJava incorrectly rejects `%_` (a valid Perl global hash) under `use strict 'vars'`, preventing `Text::CSV_PP` from compiling.
2. **`use lib` appends instead of prepends** — `Lib.java` used `push` (append) instead of `unshift` (prepend), so `use lib qw(./lib)` in `Makefile.PL` couldn't override bundled modules.
3. **`@INC` ordering wrong** — `jar:PERL5LIB` (bundled modules) comes before `PERL5LIB` and `~/.perlonjava/lib` (user-installed), so CPAN-installed modules can never override bundled ones.

## Architecture

PerlOnJava ships a **bundled Text::CSV** (`src/main/perl/lib/Text/CSV.pm`, 557 lines) that wraps Apache Commons CSV (Java) via `TextCsv.java`. It provides basic CSV functionality but is missing ~40+ methods from the CPAN version.

The CPAN **Text::CSV 2.06** is a thin wrapper that delegates to `Text::CSV_PP` (pure Perl, 3,480 lines of code). It provides full compatibility with Text::CSV_XS including all accessors, error handling, callbacks, types, etc.

When a user installs Text::CSV via `jcpan`, the CPAN version (+ CSV_PP) should override the bundled version. The bundled version remains as a zero-install fallback for users who don't need the full CPAN feature set.

## Fix Phases

### Phase 1: Strict vars + use lib (DONE)

**Files changed:**
- `EmitVariable.java` — Added `%_` to `isBuiltinSpecialContainerVar`
- `BytecodeCompiler.java` — Same
- `Variable.java` — Added `%_` to parse-time strict vars exemptions
- `Lib.java` — Changed `push` to `unshift` with dedup, matching Perl's `lib.pm` semantics

### Phase 2: @INC ordering + blib support

#### 2a. Fix @INC initialization order

**File:** `GlobalContext.java`, `initializeGlobals()` (~line 194)

**Current order:**
```
1. -I arguments
2. jar:PERL5LIB          ← bundled (wins)
3. PERL5LIB env paths
4. ~/.perlonjava/lib      ← user-installed (loses)
```

**Correct order (matches Perl's site_perl > core pattern):**
```
1. -I arguments
2. PERL5LIB env paths     ← user override (highest priority)
3. ~/.perlonjava/lib       ← user-installed CPAN modules
4. jar:PERL5LIB            ← bundled fallback (lowest priority)
```

This mirrors Perl 5's `@INC` where `site_perl` comes before the core library.

**Impact:** After this fix, `jcpan`-installed modules automatically override bundled ones. No conflict between bundled `Text::CSV` (Apache Commons CSV) and CPAN `Text::CSV` (CSV_PP).

#### 2b. Add blib/lib population to MakeMaker

**File:** `ExtUtils/MakeMaker.pm`, `_create_install_makefile()`

The generated Makefile's test target uses `PERL5LIB="./blib/lib:./blib/arch:$$PERL5LIB"` but files are only installed to `~/.perlonjava/lib`. The `blib/lib` directory is never populated.

**Fix:** Add a `blib` target to the generated Makefile that copies `.pm` files to `blib/lib/` (mirroring the lib/ structure). This lets the test target find the module under test without relying on the system-wide install.

### Phase 3: PerlOnJava compatibility bugs for Text::CSV_PP

After Phases 1-2, the CPAN Text::CSV_PP will load. Some tests may still fail due to PerlOnJava bugs. Known risks from CSV_PP analysis:

| Priority | Feature | Risk | Used in CSV_PP |
|----------|---------|------|----------------|
| 1 | `*_ = $hashref` (glob aliasing to `%_`) | HIGH | `csv()` callback support (lines 1589, 1733) |
| 2 | `\G` anchor + `pos()` | HIGH | Core parsing engine (line 2408+) |
| 3 | `"\0"` null byte handling | HIGH | Sentinel value throughout |
| 4 | `use bytes` pragma | MEDIUM | 6 scoped uses for byte-level length |
| 5 | `overload` on ErrorDiag | MEDIUM | Error objects (line 3462) |
| 6 | `local $/`, `local $\` | MEDIUM | I/O behavior (lines 2280, 2304) |
| 7 | `utf8::is_utf8`/`encode`/`decode` | MEDIUM | ~20 calls |
| 8 | `goto LABEL` within parser | MEDIUM | 15 occurrences in `____parse` |

**Strategy:** Run the test suite after Phase 2 and triage. Many of these features may already work. Focus on failures that affect the most tests.

## Test Expectations

- **40 test files** in Text::CSV 2.06
- After Phase 2, tests that only use basic CSV operations (parse, combine, getline, print) should pass
- Tests requiring advanced features (callbacks, types, formula handling) depend on Phase 3
- `t/60_samples.t` and `t/rt99774.t` already pass

## Progress Tracking

### Current Status: Phase 2 in progress

### Completed
- [x] Phase 1: strict vars + use lib (2026-04-03)
  - Files: EmitVariable.java, BytecodeCompiler.java, Variable.java, Lib.java
  - All unit tests pass (`make` OK)

### Next Steps
1. Fix @INC ordering in GlobalContext.java
2. Add blib/lib population to MakeMaker
3. Run `make` to verify no regressions
4. Run `jcpan -j 4 -t Text::CSV` and count passing tests
5. Triage Phase 3 failures
