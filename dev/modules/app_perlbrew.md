# App::perlbrew CPAN Installation Plan

## Status: Phase 7.4 complete — 66/73 tests pass (2026-04-07)

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
| App::perlbrew | OK | OK | **65/73 pass** | Module info output (2), file path ops (2), misc (4) |

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

### 7.1 Fix `selectedHandle` for tied STDOUT ✅ (COMPLETED 2026-04-07)

**Result:** 57/73 → **65/73 tests pass** (8 tests fixed).

**Problem:** When `Test2::Plugin::IOEvents` ties STDOUT, `print "hello"` (no explicit
filehandle) still went through `RuntimeIO.selectedHandle` which pointed to the original
untied handle. The tie never intercepted the output, so all captured output was empty.

**Fix:** Updated `TieOperators.java` to maintain `selectedHandle` during tie/untie:
- In `tie()` GLOBREFERENCE case: if the glob's previous IO was `selectedHandle`, update
  `selectedHandle` to point to the new `TieHandle`
- In `untie()` GLOBREFERENCE case: if the current `TieHandle` is `selectedHandle`, restore
  `selectedHandle` to the previous (untied) value

**Why `stat(STDOUT)` fix was NOT needed:** Analysis showed that `stat(STDOUT)` returning
empty list (undef inode) is actually safe — `_check_for_change()` compares
`undef ne undef` → false → tie stays in place. The TAP formatter dups STDOUT before
IOEvents ties it (via `test2_add_callback_post_load`), so TAP output bypasses the tie.

**Files changed:** `src/main/java/org/perlonjava/runtime/operators/TieOperators.java`

**Tests fixed:** `05.get_current_perl.t`, `command-available.t`, `command-compgen.t`,
`command-env.t`, `command-lib.t`, `command-list.t`, `list_modules.t`, `20.patchperl.t`

### 7.2 Remaining failures — detailed analysis (8/73)

#### 7.2.1 `command-info.t` — Capture::Tiny + tied STDOUT interaction (1 test, 2 subtests fail)

**Symptom:** Subtests 3 and 4 (`info Data::Dumper`, `info SOME_FAKE_MODULE`) get
empty GOT. Subtests 1 and 2 (basic `info` without module) pass.

**Root cause:** The module-info code path calls `do_capture_current_perl('-le', $code)`
(perlbrew.pm line 2833) which internally uses `Capture::Tiny::capture(sub { system(...) })`.
Capture::Tiny does `local(*STDOUT)` + `_open(\*STDOUT, ">&=1")` to redirect output.
This creates a new localized STDOUT glob, but `selectedHandle` still points to the
old TieHandle from before the localization. After Capture::Tiny restores the original
glob, `selectedHandle` may be stale.

**Key code locations:**
- `App/perlbrew.pm` line 2829-2834: `do_capture_current_perl` calls Capture::Tiny
- `Capture/Tiny.pm` line 344: `local(*STDOUT), _open(\*STDOUT, ">&=1")`
- `RuntimeGlob.java` `dynamicRestoreState()` line 847: restores `this.IO` but does
  NOT check/update `RuntimeIO.selectedHandle`

**Potential fix:** In `RuntimeGlob.dynamicRestoreState()`, after restoring `this.IO`,
check if the restored IO contains a TieHandle and the glob is STDOUT, then update
`selectedHandle`:
```java
this.IO = snap.io;
if (snap.io != null && snap.io.type == RuntimeScalarType.TIED_SCALAR
    && snap.io.value instanceof TieHandle th
    && "main::STDOUT".equals(snap.globName)
    && RuntimeIO.selectedHandle != th) {
    RuntimeIO.selectedHandle = th;
}
```

**Also investigate:** Thread safety of `GlobalVariable.globalIORefs` (HashMap, not
ConcurrentHashMap) — `SystemOperator.writeToPerlStdout()` accesses it from a daemon thread.

---

#### 7.2.2 `installation-perlbrew.t` — Stat.java cannot unwrap DupIOHandle (1 test, 3 subtests fail)

**Symptom:** "Works with fish", "Works with zsh", and "Exports PERLBREW_HOME when
needed" subtests get empty output. "Works with bash" passes. All use `capture_stdout`.

**Root cause:** `Stat.java` cannot stat filehandles backed by `DupIOHandle` or
`BorrowedIOHandle`. This breaks `_check_for_change()` in Test2::Plugin::IOEvents::Tie.

**Call chain:**
1. IOEvents ties STDOUT → `stat(STDOUT)` returns empty → `$inode = undef` (saved)
2. `capture_stdout` localizes STDOUT, opens temp file via `DupIOHandle(CustomFileChannel)`
3. Inside capture, `print` goes through tied STDOUT → calls `_check_for_change()`
4. `stat(STDOUT)` on localized STDOUT → `DupIOHandle` → Stat.java only unwraps
   `LayeredIOHandle`, not `DupIOHandle` → falls through → returns empty → `$inode = undef`
5. `undef ne undef` → false → no change detected → output becomes Test2 event, not captured

**Fix location:** `Stat.java` lines 176-185 — add unwrapping for `DupIOHandle` and
`BorrowedIOHandle` (both already have `getDelegate()` methods):
```java
IOHandle innerHandle = fh.ioHandle;
boolean changed = true;
while (changed) {
    changed = false;
    if (innerHandle instanceof LayeredIOHandle lh) {
        innerHandle = lh.getDelegate(); changed = true;
    } else if (innerHandle instanceof DupIOHandle dup) {
        innerHandle = dup.getDelegate(); changed = true;
    } else if (innerHandle instanceof BorrowedIOHandle borrowed) {
        innerHandle = borrowed.getDelegate(); changed = true;
    }
}
```

**Why bash passes but fish/zsh fail:** Needs further investigation — may be an
ordering/state issue. The stat fix should resolve all subtests simultaneously.

---

#### 7.2.3 `12.destdir.t` — Capture::Tiny inside `do_install_this` (1 test, 1 subtest fails)

**Symptom:** `sitecustomize.pl installed in DESTDIR` fails — got undef, expected
`use strict;\n`.

**Root cause:** Same `selectedHandle` / Capture::Tiny interaction as 7.2.1. Inside
`do_install_this` (perlbrew.pm line 1651), `do_capture("$newperl -V:sitelib")` uses
Capture::Tiny. The localized STDOUT doesn't get `selectedHandle` pointed to it, so
`print` inside the capture goes through the old TieHandle → output becomes a Test2
event instead of being written to the capture temp file → capture returns empty →
`$sitelib = undef` → sitecustomize.pl written to wrong path.

**Fix:** Same as 7.2.1 — fix `dynamicRestoreState()` or make `setIO()` smarter about
glob identity vs IO identity.

---

#### 7.2.4 `12.sitecustomize.t` — Same root cause as 7.2.3 (1 test, 1 subtest fails)

**Symptom:** `Received an undefined entry as a parameter` at `App/Perlbrew/Path.pm`
line 18.

**Root cause:** Identical to 7.2.3. Capture::Tiny returns empty → `$sitelib = undef` →
`App::Perlbrew::Path->new(undef)` → `_joinpath(undef)` → dies with "Received an
undefined entry as a parameter".

**Fix:** Same as 7.2.1 and 7.2.3.

---

#### 7.2.5 `installation2.t` — Test2::Mock `do_system` + Capture::Tiny crash (1 test, 1 subtest fails)

**Symptom:** `do_system is called` fails, log file is empty. Mock tracking shows
`do_system` was never called.

**Root cause:** `do_install_this` calls `maybe_patchperl()` (perlbrew.pm line 1587-1589)
before reaching `do_system`. `maybe_patchperl` uses `Capture::Tiny::capture { system("patchperl --version") }`.
This hits the same selectedHandle/Capture::Tiny issue, causing `maybe_patchperl` to
crash → `do_install_this` dies before reaching the mocked `do_system`.

**Secondary concern:** Test2::Mock's tracking wrapper uses `goto &$sub` where `$sub`
is a closure-captured lexical coderef:
```perl
# Test2/Mock.pm line 434-439
$ref = sub {
    push @{$sub_tracker->{$param}} => $call;
    goto &$sub;  # tail call to actual mock sub
};
```
If `goto &$sub` with closure-captured coderefs doesn't work correctly in PerlOnJava,
the mock would fail even without the Capture::Tiny issue.

**Fix:** Primary fix is the Capture::Tiny/selectedHandle fix (7.2.1). After that, verify
`goto &$sub` with closures works.

---

#### 7.2.6 `http-ua-detect-non-curl.t` — `FileSpec.path()` uses Java env (1 test, 1 subtest fails)

**Symptom:** Expected fake curl from `t/fake-bin/curl` but got `/usr/bin/curl`.

**Root cause:** `FileSpec.java` line 357 uses `System.getenv("PATH")` instead of reading
from Perl's `%ENV`. The test modifies `$ENV{PATH}` in a BEGIN block to prepend
`t/fake-bin/`, but `System.getenv("PATH")` returns the original JVM process PATH.

**Code:**
```java
// FileSpec.java line 356-363
public static RuntimeList path(RuntimeArray args, int ctx) {
    String path = System.getenv("PATH");  // BUG: reads Java env, not Perl %ENV
    ...
}
```

**Fix:** Simple one-line change in `FileSpec.java:357`:
```java
RuntimeHash perlEnv = GlobalVariable.getGlobalHash("main::ENV");
RuntimeScalar pathScalar = perlEnv.get(new RuntimeScalar("PATH"));
String path = pathScalar.getDefinedBoolean() ? pathScalar.toString() : null;
```

**Also affects:** `ArgumentParser.java` line 258 (same `System.getenv("PATH")` for `-S` flag).

---

#### 7.2.7 `util-looks-like.t` — `B::SV` missing `SV` method (1 test, 1 subtest fails)

**Symptom:** `Can't locate object method "SV" via package "B::SV"` at
`Test2/Util/Stash.pm` line 117.

**Root cause:** `Test2::Util::Stash::get_symbol()` calls `B::svref_2object(\*glob)->SV`.
PerlOnJava's `B::svref_2object()` returns `B::SV` for GLOB refs (should return `B::GV`),
and `B::SV` has no `SV` method.

**Three things missing from `src/main/perl/lib/B.pm`:**

1. **`svref_2object` doesn't detect GLOB refs** — should return `B::GV`:
```perl
# In svref_2object, add before the SCALAR check:
if ($rtype eq 'GLOB') {
    my $name = *{$ref}{NAME} // '';
    my $pkg  = *{$ref}{PACKAGE} // 'main';
    my $gv = B::GV->new($name, $pkg);
    $gv->{ref} = $ref;  # store glob ref for SV access
    return $gv;
}
```

2. **`B::GV` needs `SV` method** — return scalar slot of glob:
```perl
package B::GV;
sub SV {
    my $self = shift;
    my $glob = $self->{ref};
    if (defined $glob) {
        local $@;
        my $sv_val = eval { ${*{$glob}} };
        if (!$@ && defined $sv_val) {
            return B::SV->new(\${*{$glob}});
        }
    }
    return B::SPECIAL->new(0);  # 0 = index for 'Nullsv'
}
```

3. **`B::SPECIAL` class needed** — must NOT inherit from `B::SV`:
```perl
package B::SPECIAL;
sub new { my ($class, $index) = @_; bless \$index, $class }
```

---

#### 7.2.8 `unit-files-are-the-same.t` — `<$var/*.t>` glob not interpolating (1 test, 0 subtests)

**Symptom:** "No tests run!" — exit 255.

**Root cause:** The test uses `<$RealBin/*.t>` to find test files. PerlOnJava's
`StringParser.parseRawString` does not interpolate variables in `<>` glob patterns.

**Parser flow for `<$RealBin/*.t>`:**
1. `parseDiamondOperator` sees `<`, next token is `$`
2. Parses `$RealBin` as a variable, checks if next is `>` — it's NOT (`/`)
3. Falls through to `parseRawString("<")`
4. `parseRawString` creates literal StringNodes — `$RealBin` is NOT interpolated
5. `handleGlobBuiltin` gets literal string `"$RealBin/*.t"` → no files match → empty
   `@test_files` → no loop iterations → "No tests run!"

**Fix location:** `StringParser.java` around line 666, add a `case "<>":` that applies
double-quote interpolation:
```java
case "<>":
    return new OperatorNode(operator,
        StringDoubleQuoted.parseDoubleQuotedString(
            parser.ctx, rawStr, true, true, false,
            parser.getHeredocNodes(), parser),
        rawStr.index);
```

In Perl 5, `<$var/*.t>` is equivalent to `glob("$var/*.t")` — `$var` IS interpolated
(double-quote semantics).

---

### Summary of fix priorities

| Priority | Tests Fixed | Fix | Complexity |
|----------|------------|-----|------------|
| **1 (easy)** | `http-ua-detect-non-curl.t` | `FileSpec.path()` read from `%ENV` instead of `System.getenv` | One line |
| **2 (easy)** | `unit-files-are-the-same.t` | Interpolate variables in `<>` glob patterns | Small parser change |
| **3 (medium)** | `util-looks-like.t` | Add GLOB detection to `svref_2object`, `SV` method to `B::GV`, `B::SPECIAL` class | ~30 lines in B.pm |
| **4 (medium)** | `command-info.t`, `12.destdir.t`, `12.sitecustomize.t`, `installation2.t`, `installation-perlbrew.t` | Fix Capture::Tiny + tied STDOUT interaction — `selectedHandle` tracking during `local(*STDOUT)` and stat unwrapping for `DupIOHandle` | Two-part fix in Stat.java + RuntimeGlob.java |

---

## Progress Tracking

### Current Status: Phase 7.1 complete — 65/73 App::perlbrew tests pass

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

- [x] Phase 7.1: selectedHandle fix for tied STDOUT (2026-04-07)
  - Updated `TieOperators.java` tie/untie to maintain `RuntimeIO.selectedHandle`
  - Analysis showed stat(STDOUT) fix was NOT needed (undef-equality is safe)
  - **65/73 pass** (up from 57/73) — 8 tests fixed
  - Also fixed: TieHandle/TiedVariableBase cast error in RuntimeScalar.java

- [x] Phase 7.2: Fix 3 more test failures (2026-04-07)
  - Priority 1: `FileSpec.path()` reads from Perl `%ENV` (+ `ArgumentParser.java` `-S` flag)
  - Priority 2: Diamond operator `<$var/*.t>` interpolation (StringParser, EmitOperator, CompileOperator)
  - Priority 3: `B::svref_2object` GLOB detection, `B::GV::SV`, `B::SPECIAL` class
  - Priority 4A: `Stat.java`/`FileTestOperator.java` unwrap `DupIOHandle`/`BorrowedIOHandle`
  - Priority 4B: `RuntimeGlob.dynamicSaveState/Restore` saves/restores `selectedHandle`
  - **68/73 pass** (up from 65/73) — 3 tests fixed
  - Commit: 803ba99e0

### Next Steps (Phase 7.3 — 5 remaining failures)
All 5 remaining failures share the same root cause: Capture::Tiny + Test2::Plugin::IOEvents
tied STDOUT interaction. The `selectedHandle` stub fix handles `open(*STDOUT, ...)` correctly,
but IOEvents' tie-based output capture needs the TieHandle to remain active for print statements.
The interaction between `local(*STDOUT)`, the TieHandle, and `selectedHandle` needs deeper
investigation — possibly requiring IOEvents to detect handle changes via a different mechanism
than `stat(STDOUT)`, or a redesign of how `selectedHandle` interacts with tied handles.

Remaining tests: `command-info.t`, `12.destdir.t`, `12.sitecustomize.t`, `installation2.t`,
`installation-perlbrew.t`

- [x] Phase 7.4: Fix backslash prototype precedence for `tied *GLOB && expr` (2026-04-07)
  - **Root cause**: PerlOnJava parsed `tied *STDOUT && $] >= 5.008` as `tied(*STDOUT && $] >= 5.008)`
    instead of `(tied *STDOUT) && ($] >= 5.008)`. This caused Capture::Tiny to skip
    `local(*STDOUT)` when STDOUT was tied (by IOEvents), corrupting `selectedHandle`.
  - **Fix**: `PrototypeArgs.java` — Added `parseBackslashArgWithComma()` that parses backslash
    prototype arguments at named-unary precedence (level 15, same as `isa`) instead of comma
    precedence (level 5). This matches Perl 5's parsing behavior where `\[$@%*]` prototypes
    consume the variable term but not comparison/logical operators.
  - **Effect**: Capture::Tiny's `local(*STDOUT)` now fires correctly when STDOUT is tied,
    `selectedHandle` is properly saved/restored through `local(*STDOUT)` scopes
  - Also cleaned up `RuntimeGlob.java` debug logging
  - **66/73 pass** (up from 65/73)

### Remaining 7 failures (Phase 7.4)
| Test | Root Cause |
|------|-----------|
| `t/command-info.t` | `Compiled at:` field empty — PerlOnJava doesn't provide compile date |
| `t/installation2.t` | Test2::Mock + Capture::Tiny crash |
| `t/command-env.t` | Missing `local::lib` dependency |
| `t/command-exec.t` | Missing `local::lib` dependency |
| `t/command-make-shim.t` | Missing `local::lib` dependency |
| `t/command-help.t` | Subprocess can't find dependencies (needs PERL5LIB) |
| `t/09.exit_status.t` | Missing `Path::Class` dependency |
