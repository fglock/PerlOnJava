# CPANPLUS — full `./jcpan -t CPANPLUS` parity

This document tracks **PerlOnJava**/`jperl` work so **`./jcpan -t CPANPLUS`** can pass the upstream **`t/`** suite without early aborts (“did not return a true value”, missing imports, …).

Related: **`dev/modules/cpan_client.md`** (jcpan client), **`AGENTS.md`** (always **`timeout`** around **`jperl`** / **`jcpan`**).

---

## Resolved: `require` / trailing true value (2026-05)

### Symptoms

Failures such as:

```text
CPANPLUS/Config.pm did not return a true value at t/… line 4, <GEN…> line 317.
Compilation failed in require
```

Perl requires the loaded compilation unit’s **last statement** to yield a **defined** scalar; an **empty `RuntimeList`** at the boundary behaves like **undef**.

### Causes addressed in-tree

1. **`CompilerOptions` leakage (JVM)**  
   Nested subroutine compilations (`EmitSubroutine`, lazy subs in `SubroutineParser`) reused `compilationUnitFromRequireOrDo` via a shared `CompilerOptions` reference. `EmitBlock` could treat an inner sub’s body like the outer `require` file tail and mis-emit the last statement (empty list).
   - **Fix:** **`clone()`** parent options for nested JVM subs / named lazy subs and clear **`compilationUnitFromRequireOrDo`** / **`compilationUnitCallerContext`**.

2. **`eval`** units inheriting **`require`** flags  
   **`EmitEval`** and **`RuntimeCode`** eval clones now clear **`compilationUnitFromRequireOrDo`** after cloning so eval strings are not codegen’d as the outer **`require`** body.

3. **Interpreter parity after JVM `ctx.contextType = RUNTIME`**  
   Fallback compilation can leave **`currentCallContext == RUNTIME`** so the interpreter emitted the **file’s last statement** in **RUNTIME** context → empty list semantics for trailing **`1;`** relative to **`require`**.
   - **Fix:** **`BytecodeCompiler`** — for the outermost block of a **`compilationUnitFromRequireOrDo`** unit, treat the **last** statement like **`EmitBlock`** (use **`compilationUnitCallerContext`** or **scalar**) when **`currentCallContext == RUNTIME`**.

4. **`LargeBlockRefactorer` vs `require`/do file body**  
   Whole-block `sub { ... }->()` refactor must not run on the `require`/do outermost body (it would discard the compilation unit’s return value). Mitigated via `compilationUnitFromRequireOrDo` + outer-block detection (`EmitBlockJvmDepth` / `isFileLevelBlock` skips).

5. **Circularity guard (Configure ⇄ Config ⇄ Backend)**  
   `CPANPLUS::Configure` loads `CPANPLUS::Config`; `Config` pulls `CPANPLUS` → `Backend` → `Configure` again. Even after codegen fixes, `apply()` could still propagate an empty `RuntimeList` with `$@` unchanged.
   - **Fix (belt-and-suspenders):** `ModuleOperators.doFile`: for `require` of a `compilationUnitFromRequireOrDo` unit, if `result.isEmpty()` and `$@` is blank (snapshot before `$@` is cleared on success), coerce success to `scalarTrue`. The **`module_true`** feature flag still overrides as before.

Supporting plumbing: **`JavaClassInfo`** fields **`emitJvmApplyBodyFromRequireOrDo`** / **`emitBlockJvmDepth`**, **`PerlLanguageProvider`** marking **`compilationUnitCallerContext`**, **`CompilerOptions`** **javadoc**.

### Verification checklist (regression-sensitive)

```bash
make

# CPANPLUS build dir after jcpan fetched sources (adjust path/version)
PERL5LIB="/path/to/CPANPLUS-*/blib/lib:/path/to/CPANPLUS-*/blib/arch:$PERL5LIB"
cd …/CPANPLUS-*/t && timeout 120 /path/to/jperl -e 'require "./inc/conf.pl"; print "ok\n"'
```

Always wrap **`./jcpan -t CPANPLUS`**:

```bash
timeout 3600 ./jcpan -t CPANPLUS   # captures full TAP; see jcpan/build logs
```

---

## Current gap: remainder of `./jcpan -t CPANPLUS` (failed programs / snippets)

_Last full harness observation (PerlOnJava + CPANPLUS 0.9916): **`1267`** subtests executed; **`7/20`** test **programs** still failed or exited non‑zero._

### Priority 1 — **`BUILD_PL` strict bareword**

**Evidence:** **`Bareword "BUILD_PL" not allowed while "strict subs"`** in **`blib/lib/CPANPLUS/Module.pm`** (~line 674, near **`->(`**).

**Impact:** **`t/08_CPANPLUS-Backend.t`**, **`t/04_CPANPLUS-Module.t`**, **`t/07_CPANPLUS-Internals-Extract.t`**, **`t/20_CPANPLUS-Dist-MM.t`**, **`t/21_CPANPLUS-Dist-No-Build.t`** etc. Often **all planned subtests “pass”** but the process exits **255** (**dubious**) because **strict** blows up late or in a teardown path tied to **`Module.pm`**.

**Next steps:**

1. Open **`CPANPLUS/Module.pm`** at the offending line; classify `BUILD_PL` (bare hash key vs indirect object / `\&BUILD_PL`-style constructs, etc.).
2. Build a **`jperl`** one-liner (**`-Mstrict`**) isolated repro (no CPANPLUS) that matches Perl 5 semantics.
3. Fix **parser/typecheck** (**`bareword ⇒ string`**, **quoted keys**, **`{ … }`** deref contexts) until repro matches **`perl`**.
4. Re-run the five dubious tests above; verify **exit 0**.

### Priority 2 — **`t/00_CPANPLUS-Internals-Utils.t`** (scalar stringification / formatting)

**Evidence:** **`Value as expected`** — got **`1.500000`** instead of **`1.005000`** / **`1.500`**.

**Likely buckets:** `sprintf` / `%g` `%f`, `locale` / `LC_NUMERIC` parity, number → string semantics.

**Next steps:**

1. Read failing line (~133) in that test file for exact expected formatting rules.
2. Compare **`perl`** vs **`jperl`** for the helper under test (**`Module::Functions` / CPANPLUS Internals helpers**).
3. Align **PerlOnJava formatting** (**`PerlLanguageProvider`/runtime stringification**) or **`POSIX::locale`** stubs if referenced.

### Priority 3 — **`t/031_CPANPLUS-Internals-Source-SQLite.t`** + DBIx

**Evidence:** **`Can't call method "execute" on an undefined value`** at **`DBIx/Simple.pm`**, surfaced from **`CPANPLUS::Internals::Source::SQLite`**.

**Next steps:**

1. Confirm **`DBD::SQLite` / shim** **`connect`** succeeds under **`PERL5LIB`** harness.
2. Trace **`undef`** **`dbh`** — wrong **`DBIx::Simple->connect`** args vs **PerlOnJava** stubs.
3. Fix **database layer / DBIx::Simple compat** until the test skips cleanly or connects.

### Priority 4 — **`t/20_CPANPLUS-Dist-MM.t`** (log assertions)

**Evidence:** “Making format unavailable” / “Format failure logged”: TAP expects a message fragment mentioning `CPANPLUS::Dist::MM` unavailable; bundled EU::MM can change observable logs.

**Next steps:**

1. Decide whether to **simulate “format unavailable”** in tests by **unlinking stubs** vs **changing log text** (**prefer fixing runtime** only if **`perl`** emits the expected message with same stubs).
2. Align PerlOnJava `CPANPLUS::Dist::*` error strings, verbosity, and fallback paths so the test’s regex matches what stock `perl` would emit under the same layout.

### Priority 5 — noisy secondary issues

- **`File/Copy.pm`**: **uninitialized value in addition (~303)** — track down **`-U`/`undef`** math in **`copy`** / **`move`** edge case triggered by CPANPLUS tests.

---

## Progress tracking

| Area | Status | Notes |
|------|--------|--------|
| Empty list / **`require`** false negative | **Done** | Cloned **`CompilerOptions`**, evaluator fixes, bytecode last-stmt **`require`** parity, **`doFile`** empty-list heel when **`$@`** clean |
| **`make`** (unit shards) | **Done** | Run before pushing |
| **`jcpan -t CPANPLUS`** bootstrap | **Unblocked** | **`conf.pl`** + **`Selfupdate`** / **`Report`** no longer abort on **`Config`** |
| **`BUILD_PL` strict/`Module.pm`** | **Open** | Blocks several **“dubious 255”** programs |
| **Utils formatting** | **Open** | **`t/00`** mismatches |
| **`t/031` SQLite Source** | **Open** | **DBIx:** `execute` on undefined (`dbh` lifecycle) |
| **Dist/MM log expectations** | **Open** | **`t/20`** regex on log fragment |

---

## Open questions

- Should the **`doFile`** empty-list coercion be tightened (e.g. only **`.pm`** paths, file size cap, circular-depth probe) vs keeping the current conservative **`compilationUnitFromRequireOrDo`** guard?
- **ASM `ArrayIndexOutOfBoundsException`** in frame compute during heavy BEGIN stacks: already falls back to interpreter — track reduction of fallback frequency?
