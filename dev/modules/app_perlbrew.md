# App::perlbrew CPAN Installation Plan

## Status: Phase 6 complete — 57/73 tests pass (2026-04-07)

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
└── (test dependencies: Test2::V0, Test2::Tools::Spec, FindBin, English.pm)
```

## Test Run Summary (2026-04-07, after Phase 4 fixes)

| Module | Configure | Build | Test | Blocker |
|--------|-----------|-------|------|---------|
| Module::Build::Tiny | OK | OK | **32/32 PASS** | — |
| CPAN::Perl::Releases | OK | OK | 104/105 (1 fail) | `blib/arch` not created |
| Devel::PatchPerl | OK | OK | 28/28 pass, 3 programs fail | Missing `File::pushd` |
| File::Which | OK | OK | 14/18 (4 fail) | `catpath()` prototype bug *(fixed in Phase 2)* |
| Test2::Plugin::IOEvents | OK | OK | FAIL | Test2::V0 import issue *(fixed in Phase 4)* |
| local::lib | OK | OK | 26/32 pass, shell.t hangs | `-` stdin *(fixed in Phase 2)*, PATH in sub-shells |
| App::perlbrew | OK | OK | **57/73 pass** | Tied STDOUT capture (9 tests), misc (7 tests) |

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

## Phase 3: Parser and Import System Fixes (COMPLETED 2026-04-07)

### 3.1 Test2::V0 / Test2::Util::Importer function import chain ✅

**Problem:** `return` inside `map`/`grep` blocks only exited the block, not the enclosing
subroutine. This broke `Test2::Util::Importer::optimal_import()` which uses `return` inside
`map` to exit early from the import function.

**Root cause:** Map/grep blocks were compiled as `SubroutineNode` (anonymous subs), so `return`
inside them returned from the block rather than propagating to the enclosing sub.

**Fix:** Two-layer approach:
1. **Return-value markers**: Map/grep blocks annotated with `isMapGrepBlock`; `return` creates
   `RuntimeControlFlowList(RETURN, returnValue)` marker returned as block result
2. **Exception propagation**: `ListOperators.map()`/`grep()` detect RETURN markers and throw
   `PerlNonLocalReturnException`; `RuntimeCode.apply()` catches this in normal subs

**Files changed:** `PerlNonLocalReturnException.java` (new), `ControlFlowType.java`,
`RuntimeControlFlowList.java`, `ParseMapGrepSort.java`, `JavaClassInfo.java`,
`EmitSubroutine.java`, `EmitControlFlow.java`, `ListOperators.java`,
`EmitterMethodCreator.java`, `RuntimeCode.java`, `InterpretedCode.java`,
`BytecodeCompiler.java`, `BytecodeInterpreter.java`

### 3.2 `isa` infix operator precedence when feature-gated

**Priority: LOW** — Affects `prop isa => 'Class'` pattern in Test2 tests.

**Problem:** `isa` is registered in `ParserTables.INFIX_OP` with precedence 15 regardless of
whether the `isa` feature is enabled. When `prop` (an imported sub) isn't recognized, `isa` is
consumed as an infix operator, causing a syntax error before `=>`.

**Fix:** In `Parser.java` expression loop, check `isFeatureCategoryEnabled("isa")` before
treating `isa` as an infix operator. Also handle `isa =>` as autoquoting (fat comma).

**Files:** `src/main/java/org/perlonjava/parser/Parser.java`

---

## Phase 4: FindBin and Test Harness (DEFERRED → Phase 7)

### 4.1 FindBin `$0` handling — moved to Phase 7.1

### 4.2 Missing `File::pushd` dependency for Devel::PatchPerl

**Priority: LOW** — CPAN should resolve this automatically once other fixes land.

`File::pushd` is a pure-Perl module. Once `local::lib` installs, CPAN should be able to
install `File::pushd` as a transitive dependency. No PerlOnJava fix needed — just needs
the dependency chain to work.

---

## Phase 5: Test2::IPC CallerStack Fix (COMPLETED 2026-04-07)

### 5.1 INIT/CHECK/END blocks missing CallerStack entry ✅

**Priority: HIGH** — Root cause of ~40 App::perlbrew test failures.

**Problem:** Tests using `Test2::Tools::Spec` load `Test2::AsyncSubtest` → `Test2::IPC`,
which has an INIT block that calls `Test2::API::context()`. The `context()` function uses
`caller(1)` to find the calling package, but INIT blocks in PerlOnJava execute directly
from Java (`SpecialBlock.runInitBlocks()` → `RuntimeCode.apply()`) with no Perl caller
frame above them. `caller(1)` returns empty → confess "Could not find context at depth 1".

**Loading chain for failing tests:**
```
Test2::Tools::Spec
  → Test2::Workflow::Runner (line 9: use Test2::AsyncSubtest())
    → Test2::AsyncSubtest (line 5: use Test2::IPC)
      → Test2::IPC INIT block (line 26-29): context()->release()
```

**Why passing tests work:** Tests using only `Test2::V0` don't load `Test2::IPC`.

**Root cause in PerlLanguageProvider.java:**
- Line 161-177: `CallerStack.push("main", ...)` during parse, popped after parsing completes
- Line 348: `runInitBlocks()` executes AFTER CallerStack was popped
- Result: no CallerStack entry when `caller(1)` falls back to CallerStack

**Fix:** Push a CallerStack entry around INIT/CHECK/END block execution in
`PerlLanguageProvider.executeCode()`, matching Perl 5 behavior where these blocks
run from the main program scope.

```java
if (isMainProgram) {
    CallerStack.push("main", ctx.compilerOptions.fileName, 0);
    try { runInitBlocks(); } finally { CallerStack.pop(); }
}
```

**Files:** `src/main/java/org/perlonjava/app/scriptengine/PerlLanguageProvider.java`

**Verification:**
```bash
./jperl -e 'use Test2::IPC; print "ok\n"'
# Should print "ok" instead of "Could not find context at depth 1"
```

### 5.2 Other remaining App::perlbrew test failures (PARTIALLY FIXED 2026-04-07)

**Priority: MEDIUM** — After 5.1, some tests will still fail for other reasons:

| Issue | Tests affected | Root cause | Status |
|-------|---------------|------------|--------|
| `can't get_layers on tied handle` | t/12.destdir.t, t/12.sitecustomize.t | PerlIO.java line 54 | **FIXED** — returns empty list for tied handles |
| `$Config{myarchname}` undef | t/sys.t | Config.pm missing entry | **FIXED** — added `myarchname => "$os_arch-$os_name"` |
| `Subroutine new redefined` warning | All Test2::V0 tests | Universal.java `can()` for forward declarations | **FIXED** — check `isDeclared` flag so `sub new;` returns true from `can()` |
| `local(@ARGV)` + `\@ARGV` ref semantics | t/01.options.t args tests, many others | RuntimeArray in-place save/restore | **FIXED** — new GlobalRuntimeArray swaps global map entry |
| `B::SV` not implemented | t/util-looks-like.t | Test2::Util::Stash uses B:: introspection | Not yet investigated |
| PATH in sub-shells | t/shell.t (local::lib) | `local::lib` test resets PATH, jperl shell script can't find `dirname`/`java` | Not yet investigated |
| Pod::Usage / help formatting | t/command-help.t | Likely missing Pod::Usage features | Not yet investigated |

---

## Phase 6: Re-test and Fix Remaining Failures (COMPLETED 2026-04-07)

### 6.1 Re-run App::perlbrew test suite ✅

**Result:** 57/73 test files pass (from 18/73 before Phase 5 fixes).

### 6.2 TieHandle/TiedVariableBase cast fix ✅

**Problem:** When `Capture::Tiny` ties a filehandle, `tiedStore()` and `tiedFetch()` in
`RuntimeScalar.java` cast `value` to `TiedVariableBase`, but `TieHandle` extends `RuntimeIO`,
not `TiedVariableBase`. This caused `ClassCastException` crashes.

**Fix:** Added `instanceof TieHandle` checks before the `TiedVariableBase` cast.
`tiedStore()` returns the value as-is for TieHandle; `tiedFetch()` returns
`tieHandle.getSelf()`.

**Files changed:** `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeScalar.java`

### 6.3 Remaining failures categorized

16 test files still fail, in these categories:

| Category | Count | Tests | Root Cause |
|----------|-------|-------|------------|
| **Tied STDOUT capture** | 9 | `05.get_current_perl.t`, `command-available.t`, `command-compgen.t`, `command-env.t`, `command-info.t`, `command-lib.t`, `command-list.t`, `installation-perlbrew.t`, `list_modules.t` | `print` without explicit filehandle bypasses tied STDOUT — `RuntimeIO.selectedHandle` still points to original untied handle |
| **File/path ops** | 3 | `12.destdir.t`, `12.sitecustomize.t`, `20.patchperl.t` | sitecustomize.pl install fails; undefined path parameter in `App::Perlbrew::Path` |
| **Test2::Mock** | 1 | `installation2.t` | Mock `do_system` not working — `goto &$sub` or Test2::Mock limitation |
| **PATH lookup** | 1 | `http-ua-detect-non-curl.t` | Fake `curl` in `$PATH` not picked up; `File::Which` or jperl shell script resolves system curl instead |
| **B:: introspection** | 1 | `util-looks-like.t` | `B::SV` class not implemented |
| **No tests run** | 1 | `unit-files-are-the-same.t` | Skipped (no reason given) |

---

## Phase 7: Tied STDOUT and Remaining Fixes

### 7.1 Fix `selectedHandle` for tied STDOUT

**Priority: HIGH** — Root cause of 9/16 remaining failures (the single biggest blocker).

**Problem:** When `Test2::Plugin::IOEvents` ties STDOUT, `print "hello"` (no explicit
filehandle) still goes through `RuntimeIO.selectedHandle` which points to the original
untied handle. The tie never intercepts the output, so all captured output is empty.

**Complexity:** A previous fix that updated `selectedHandle` in `TieOperators.java` was
reverted because `Test2::Plugin::IOEvents::Tie::PRINT` converts output to Test2 events
(no real output). It relies on `stat(STDOUT)` inode changes to detect pipe redirects.
PerlOnJava's `stat` on filehandles returns empty/meaningless values, so
`_check_for_change()` never fires. This caused ALL print output to become events instead
of going to the TAP harness, breaking every test.

**Fix approach:** Two-part fix required:
1. Update `selectedHandle` when tying/untying the currently-selected handle
2. Implement meaningful `stat(STDOUT)` — at minimum, return a unique inode/dev so
   `_check_for_change()` can detect when the FD changes (pipe redirect detection)

**Tests affected:** `05.get_current_perl.t`, `command-available.t`, `command-compgen.t`,
`command-env.t`, `command-info.t`, `command-lib.t`, `command-list.t`,
`installation-perlbrew.t`, `list_modules.t`

### 7.2 FindBin `$0` handling in test contexts

**Priority: LOW** — Many App::perlbrew tests fail with
`Cannot find current script 'can_ok'`.

**Problem:** When tests are run via the harness, `$0` sometimes gets set to a test function
name (`can_ok`) instead of a file path. FindBin.pm then dies because it can't find a file
with that name.

### 7.3 `blib/arch` directory creation

**Priority: LOW** — Causes `CPAN::Perl::Releases` test failure (1/105).

### 7.4 `isa` infix operator feature gate

**Priority: LOW** — Affects `prop isa => 'Class'` pattern in Test2 tests.

### 7.5 `B::SV` stub for Test2::Util::Stash

**Priority: LOW** — Causes `util-looks-like.t` failure.

`Test2::Util::Stash` uses `B::svref_2object($ref)->SV` to introspect scalar references.
Options: stub `B::SV` to return a minimal object, or patch Test2::Util::Stash to skip
when B is not available.

### 7.6 File/path operation fixes

**Priority: MEDIUM** — Causes 3 test failures (`12.destdir.t`, `12.sitecustomize.t`,
`20.patchperl.t`).

- `12.destdir.t`: sitecustomize.pl not written to DESTDIR during mock install
- `12.sitecustomize.t`: `App::Perlbrew::Path->new()` receives undefined parameter
- `20.patchperl.t`: 0/1 subtests ran (need investigation)

---

## Progress Tracking

### Current Status: Phase 6 complete — 57/73 App::perlbrew tests pass

### Completed Phases
- [x] Phase 1: Foundation Fixes (2026-04-07)
  - Added `$Config{startperl}` and `$Config{sharpbang}` to Config.pm
  - Added `$DynaLoader::VERSION = '1.54'` to DynaLoader.java
  - Created DynaLoader.pm stub for CPAN disk-based lookups
  - Module::Build::Tiny now configures, builds, and tests (32/32) successfully
- [x] Phase 2: CLI and Core Module Fixes (2026-04-07)
  - 2.1: Added `-` stdin support in ArgumentParser.java (unblocked local::lib)
  - 2.2: Added English.pm core module
  - 2.3: Fixed File::Spec catpath/splitpath/abs2rel/rel2abs prototypes
- [x] Phase 3.1: Non-local return from map/grep blocks (2026-04-07)
  - Two-layer approach: return-value markers + PerlNonLocalReturnException
  - Fixed Test2::Util::Importer::optimal_import() and all map/grep return semantics
  - Commit: f97aa6c1c → rebased as e53eb6d06
- [x] Phase 4 (partial): Test2::V0 import chain now works
- [x] Phase 5.1: CallerStack for INIT/CHECK/END blocks (2026-04-07)
  - Wrapped runInitBlocks/runCheckBlocks/runEndBlocks with CallerStack.push/pop
  - Unblocked ~32 App::perlbrew tests that crashed on "Could not find context at depth 1"
  - Commit: 83a90050e
- [x] Phase 5.2 (partial): Four additional fixes (2026-04-07)
  - PerlIO.java: get_layers returns empty list for tied handles (was throwing exception)
  - Config.pm: added `myarchname` entry
  - Universal.java: `can()` returns true for forward-declared subs (`sub new;`)
  - **local @ARRAY reference semantics**: created GlobalRuntimeArray.java to swap global map
    entry instead of modifying in-place. Updated BytecodeInterpreter, EmitOperatorLocal,
    CompileAssignment. Added 5 tests to local.t.
  - Commit: 57bca797c
- [x] Phase 6: Re-test and remaining fixes (2026-04-07)
  - Re-ran `./jcpan -t App::perlbrew`: **57/73 pass** (up from 18/73)
  - Fixed TieHandle/TiedVariableBase cast error in RuntimeScalar.java (tiedFetch/tiedStore)
  - Attempted selectedHandle fix for tied STDOUT — **reverted** due to stat(STDOUT) regression
  - Categorized all 16 remaining failures (see Phase 6.3)
  - Interpreter fixes: hash assignment return values, hash warning messages,
    chop/chomp list args, hashassign.t 248→309/309

### Next Steps
1. **Phase 7.1 (HIGH):** Fix `selectedHandle` + `stat(STDOUT)` to unblock 9 tied STDOUT tests
2. **Phase 7.6 (MEDIUM):** Investigate file/path operation failures (3 tests)
3. **Phase 7.5 (LOW):** Stub `B::SV` for Test2::Util::Stash (1 test)
4. **Phase 7.2-7.4 (LOW):** FindBin, blib/arch, isa feature gate

### Open Questions
- Can `stat(filehandle)` return a synthetic inode based on the underlying FD number?
- Is the `selectedHandle` approach correct, or should `print` always go through the glob's IO?
- Can we stub B::SV enough to satisfy Test2::Util::Stash, or is full B module support needed?
