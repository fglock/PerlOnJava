# App::perlbrew CPAN Installation Plan

## Status: Phase 1 Complete (2026-04-07)

## Goal

Make `./jcpan -t App::perlbrew` install and test cleanly on PerlOnJava.

## Dependency Chain

```
App::perlbrew 1.02
├── Module::Build::Tiny 0.044 (build_requires)
│   └── DynaLoader (build_requires) — metadata-only, not actually needed at runtime
├── CPAN::Perl::Releases (requires)
├── Devel::PatchPerl (requires)
│   └── File::pushd >= 1.00 (requires)
├── File::Which (build_requires)
├── Test2::Plugin::IOEvents (build_requires)
├── local::lib >= 2.000014 (requires)
│   └── uses `perl - args` stdin idiom in Makefile.PL
└── (test dependencies: Test2::V0, FindBin, English.pm)
```

## Test Run Summary (2026-04-07, after Phase 1 fixes)

| Module | Configure | Build | Test | Blocker |
|--------|-----------|-------|------|---------|
| Module::Build::Tiny | OK | OK | **32/32 PASS** | — |
| CPAN::Perl::Releases | OK | OK | 104/105 (1 fail) | `blib/arch` not created |
| Devel::PatchPerl | OK | OK | 28/28 pass, 3 programs fail | Missing `File::pushd` |
| File::Which | OK | OK | 14/18 (4 fail) | `catpath()` prototype bug |
| Test2::Plugin::IOEvents | OK | OK | FAIL | Test2::V0 import issue |
| local::lib | **NOT OK** | — | — | `-` stdin arg not supported |
| App::perlbrew | OK | OK | 4/73 (most crash) | Test2::V0 import + FindBin + English.pm |

---

## Phase 1: Foundation Fixes (COMPLETED 2026-04-07)

### 1.1 `$Config{startperl}` undefined ✅

**Problem:** `ExtUtils::Helpers::Unix::make_executable()` uses `$Config{startperl}` to rewrite
shebang lines. PerlOnJava didn't set this value. The module uses `use warnings FATAL => 'all'`,
so the undef substitution was a fatal error (exit 255), blocking Module::Build::Tiny.

**Fix:** Added `startperl => '#!' . $^X` and `sharpbang => '#!'` to
`src/main/perl/lib/Config.pm` near the existing `perlpath` entry.

**Files changed:** `src/main/perl/lib/Config.pm`

### 1.2 `$DynaLoader::VERSION` missing ✅

**Problem:** DynaLoader.java existed as a stub but didn't set `$DynaLoader::VERSION`. CPAN's
dependency checker couldn't confirm the module was installed. Also, no `DynaLoader.pm` file
existed on disk, so CPAN's `inst_file` lookup failed.

**Fix:**
- Added `GlobalVariable.getGlobalVariable("DynaLoader::VERSION").set("1.54")` to `DynaLoader.java`
- Created `src/main/perl/lib/DynaLoader.pm` stub with `$VERSION = '1.54'` and fallback
  `bootstrap`/`boot_DynaLoader` definitions

**Files changed:** `src/main/java/.../perlmodule/DynaLoader.java`, `src/main/perl/lib/DynaLoader.pm` (new)

---

## Phase 2: CLI and Core Module Fixes

### 2.1 Support `-` (dash) as stdin filename in argument parser

**Priority: HIGH** — Blocks `local::lib` installation entirely.

**Problem:** `perl - arg1 arg2` is a standard idiom meaning "read script from stdin with @ARGV
set to remaining args." PerlOnJava's `ArgumentParser.java` treats `-` as an empty switch flag
(silently consumed as no-op), then tries to open the next argument as a filename.

**Root cause in `ArgumentParser.java`:**
- `processArgs()` line ~191: `"-".startsWith("-")` is true, so `-` enters the switch branch
- `processClusteredSwitches()`: loop `for (j = 1; j < 1; j++)` never executes — no-op
- Next arg (e.g., `ExtUtils::MakeMaker`) treated as filename → file not found → exit 1

**Fix approach:**
1. In `processArgs()`: add `args[i].equals("-")` to the non-switch condition
2. In `processNonSwitchArgument()`: when filename is `-`, read code from stdin
3. Handle the common case where `-M` modules `exit()` during `import()` before stdin is read

**Files:** `src/main/java/org/perlonjava/app/cli/ArgumentParser.java`

**Verification:** `echo 'print "hello\n"' | ./jperl -` should print "hello"

### 2.2 Add `English.pm` core module

**Priority: MEDIUM** — Needed by App::perlbrew `t/02.format_perl_version.t`.

**Problem:** `English.pm` is a core Perl module providing human-readable aliases for special
variables (`$ERRNO` for `$!`, `$OSNAME` for `$^O`, etc.). It's missing entirely from PerlOnJava.

**Fix:** Create `src/main/perl/lib/English.pm` with the standard typeglob alias table.
Pure Perl, ~200 lines. Standard implementation uses `*alias = *special_var` assignments.

**Files:** `src/main/perl/lib/English.pm` (new)

### 2.3 Fix `File::Spec::catpath()` prototype

**Priority: MEDIUM** — Causes `File::Which` test crash.

**Problem:** `FileSpec.java` registers `catpath` with prototype `"$$"` but the method expects
4 args (invocant + 3 params: volume, directory, file). When called as a function (not method),
the wrong prototype truncates arguments.

**Additional prototype bugs in same file:**

| Method | Current | Correct | Reason |
|--------|---------|---------|--------|
| `catpath` | `"$$"` | `"$$$"` | 3 params: vol, dir, file |
| `splitpath` | `"$"` | `"$;$"` | 2nd param (no_file) is optional |
| `abs2rel` | `"$"` | `"$;$"` | 2nd param (base) is optional |
| `rel2abs` | `"$"` | `"$;$"` | 2nd param (base) is optional |

**Files:** `src/main/java/org/perlonjava/runtime/perlmodule/FileSpec.java`

### 2.4 Ensure `blib/arch` directory is created during CPAN builds

**Priority: LOW** — Causes `CPAN::Perl::Releases` test failure (1/105).

**Problem:** `blib.pm` requires both `blib/lib` and `blib/arch` to exist. PerlOnJava's
MakeMaker may not create `blib/arch` during `make`. Standard Perl's `make` always creates it.

**Investigation needed:** Check PerlOnJava's `ExtUtils::MakeMaker` build step to see if
`blib/arch` is created.

**Files:** `src/main/perl/lib/ExtUtils/MM_PerlOnJava.pm` or equivalent

---

## Phase 3: Parser and Import System Fixes

### 3.1 Test2::V0 / Test2::Util::Importer function import chain

**Priority: HIGH** — Root cause of most App::perlbrew test failures (syntax errors on
`is`, `subtest`, `prop`, etc.).

**Problem:** Functions imported via `use Test2::V0` are not recognized by the parser at compile
time. The parser treats them as barewords, and subsequent tokens are misinterpreted as infix
operators, producing syntax errors like:
- `syntax error near "(' '"` (from `is join(...)`)
- `syntax error near "( @vers "` (from `is scalar(...)`)
- `syntax error near "(qw/"` (from `is editdist(...)`)
- `syntax error near "=> sub"` (from `subtest foo => sub { }`)

**Root cause chain:**
1. `Test2::V0` imports via `Test2::Util::Importer->import_into()`
2. `optimal_import()` uses `*{"$from\::$_"}{CODE}` to extract code refs from glob slots
3. This may fail in PerlOnJava, causing the import to not register functions
4. Without registered functions, `SubroutineParser.java` line ~353 checks
   `nextTok.type != LexerTokenType.IDENTIFIER` — when the next token IS an identifier
   (like `join`, `scalar`), the unknown-sub-call path is skipped, returning a bareword
5. The expression parser loop gives unknown identifiers default precedence 24, consuming
   them as infix operators, which then fails

**Diagnosis step:** Run:
```perl
./jperl -e 'use Test2::V0; print defined(&is) ? "is: OK\n" : "is: MISSING\n"'
```
This determines whether the issue is import-side or parser-side.

**Fix approach (depends on diagnosis):**
- If import-side: fix `*{...}{CODE}` glob slot extraction or string eval in Importer.pm
- If parser-side: improve `SubroutineParser.java` to treat unknown subs followed by known
  CORE functions (join, scalar, etc.) as list operator calls

**Files:** TBD based on diagnosis

### 3.2 `isa` infix operator precedence when feature-gated

**Priority: LOW** — Affects `prop isa => 'Class'` pattern in Test2 tests.

**Problem:** `isa` is registered in `ParserTables.INFIX_OP` with precedence 15 regardless of
whether the `isa` feature is enabled. When `prop` (an imported sub) isn't recognized, `isa` is
consumed as an infix operator, causing a syntax error before `=>`.

**Fix:** In `Parser.java` expression loop, check `isFeatureCategoryEnabled("isa")` before
treating `isa` as an infix operator. Also handle `isa =>` as autoquoting (fat comma).

**Files:** `src/main/java/org/perlonjava/parser/Parser.java`

---

## Phase 4: FindBin and Test Harness

### 4.1 FindBin `$0` handling in test contexts

**Priority: MEDIUM** — Many App::perlbrew tests fail with
`Cannot find current script 'can_ok'`.

**Problem:** When tests are run via the harness, `$0` sometimes gets set to a test function
name (`can_ok`) instead of a file path. FindBin.pm then dies because it can't find a file
with that name.

**Investigation needed:** Determine how `$0` gets set to `can_ok`. Likely either:
- The test harness sets `$0` to a sub name
- PerlOnJava's `-e` handling doesn't set `$0` to `"-e"`
- The test invocation pattern uses `perl -e 'can_ok(...)'` and `$0` isn't set correctly

**Files:** `src/main/java/org/perlonjava/app/cli/ArgumentParser.java` (if `-e` handling),
`src/main/perl/lib/FindBin.pm` (if resilience fix)

### 4.2 Missing `File::pushd` dependency for Devel::PatchPerl

**Priority: LOW** — CPAN should resolve this automatically once other fixes land.

`File::pushd` is a pure-Perl module. Once `local::lib` installs, CPAN should be able to
install `File::pushd` as a transitive dependency. No PerlOnJava fix needed — just needs
the dependency chain to work.

---

## Implementation Priority Order

1. **Phase 2.1** — `-` stdin support (unblocks `local::lib`)
2. **Phase 3.1** — Test2::V0 import chain (unblocks most App::perlbrew tests)
3. **Phase 2.2** — English.pm (quick win, unblocks format_perl_version test)
4. **Phase 2.3** — catpath prototype fix (quick win, improves File::Which)
5. **Phase 4.1** — FindBin $0 investigation
6. **Phase 2.4** — blib/arch creation
7. **Phase 3.2** — isa feature gate

---

## Progress Tracking

### Current Status: Phase 2 ready to start

### Completed Phases
- [x] Phase 1: Foundation Fixes (2026-04-07)
  - Added `$Config{startperl}` and `$Config{sharpbang}` to Config.pm
  - Added `$DynaLoader::VERSION = '1.54'` to DynaLoader.java
  - Created DynaLoader.pm stub for CPAN disk-based lookups
  - Module::Build::Tiny now configures, builds, and tests (32/32) successfully

### Next Steps
1. Implement `-` stdin support in ArgumentParser.java
2. Diagnose Test2::V0 import chain
3. Add English.pm

### Open Questions
- Is the Test2::V0 failure import-side or parser-side?
- Does FindBin `$0 = 'can_ok'` come from test harness or incorrect `-e` handling?
