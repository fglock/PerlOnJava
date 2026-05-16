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

## Resolved (2026-05-16): **`BUILD_PL` / `MAKEFILE`** “strict bareword” (**`-e`** / **`stat`** + **`->`**)

Perl treats **`BUILD_PL`**, **`MAKEFILE`**, … as **exported constant subs**, not ALLCAPS filehandle slots.

PerlOnJava’s **file-test** operator path and **`stat`/`lstat`** mistakenly consumed any **`^[A-Z_][A-Z0-9_]*$`** bareword as a glob handle **before** list/expression parsing, so **`CONSTANT->($path)`** and **`stat CONSTANT->(...)`** left a bare **`IdentifierNode`** behind and tripped **`strict subs`** at emit time.

**Fix:** **`FileHandle.shouldTreatAllCapsIdentifierAsBareFileHandleSlot`** — only use the legacy handle heuristic when **`NAME`** is **not** followed by **`->`** (skipping whitespace) **and **`GlobalVariable.isGlobalCodeRefDefined(CurrentPackage::NAME)`** is false. Wired from **`ParsePrimary.parseFileTestOperator`** and **`OperatorParser.parseStat`**.

**Check:** **`timeout 120 ./jperl -e '… -e BUILD_PL->($extract) …'`** and **`timeout 300 ./jperl ./04_CPANPLUS-Module.t`** (exit **0**).

---

## Resolved (2026-05-16): **`t/00`** **`_version_to_number`** (**`version` module** parity)

Upstream **`Utils::_version_to_number`** ends with **`version->parse(...)->numify`**. Failures (**`v1.5`** vs **`1.5-a`**) came from **`VersionHelper.normalizeVersion`** mixing **explicit `v`-tuple** semantics with **decimal mantissa chunking**, plus **`Version.java`** forcing **`v`** on short two-part decimals (**`1.5` → wrong tuple**) and **`numify`** padding **`!qv`** values as if **`qv`**.

**Fixes:** tuple branch for **`v…`** strings that are plain digit-dot tuples; rewrote decimal normalization to Perl’s **three-digit frac chunks**; removed bogus **`prepend v`** on **`1.x`** decimals in **`parseInternal`**; split **`numify`** padding **`qv`** vs **non‑`qv`**.

**Check:** **`timeout 180 ./jperl ./00_CPANPLUS-Internals-Utils.t`** exit **0**; **`jperl -Mversion -E`** spot checks **`v1.5`** / **`1.5`** / **`1.2345`**.

---

## Mitigated (2026-05-16): **`File::Copy`** uninitialized **`$!`** (**`warnings`**, line ~303)

Bundled **`File/Copy.pm`** used **`($! + 0, $^E + 0)`** when **`$!`** could be **undef**. Replaced with **defined‑guarded** coercion (still **Perl 5**‑compatible when errno is absent).

---

## Resolved (2026-05): **Strict + string `eval` + import / `no` — pr694 (**`has … =>`** DSL)**

### Symptoms

Failures such as **`Undefined subroutine &Some::Pkg::has`** inside **`eval q{ … use ExporterThing; has foo => (...); no ExporterThing; … }`** even though **`perl`** runs the **`has`** call with the imported CV after the stash entry was deleted (**CPANPLUS**-adjacent **`use`/`no`/DSL** ordering).

Separate regression **`unit/eval_after_stash_delete.t`** must keep **Perl** semantics: compilations that start **after** **`delete $stash{sub}`** must **not** resurrect a pinned CV.

### Cause

For **eval string**, **`Parser.parse()`** runs **`use` / `no`** immediately (BEGIN-like), then **`BytecodeCompiler.compile(ast)`** runs. By emit time the visible **`globalCodeRefs`** entry for **`&Pkg::name`** is often already gone, so **`getGlobalCodeRefForFreshLookup`** constant-pooled an **undef placeholder** for named **`&sub(...)`** sites. **Perl** still calls the **compile-time-pinned** CV.

A **GlobalVariable-only** “always prefer pinned when stash-deleted” fix broke **`eval_after_stash_delete`** (new compile after delete must see an empty slot).

### Fix

- **`SubroutineParser`**: when parsing a **direct** call **`&name(...)`** and **`GlobalVariable`** already shows a **real callable** (**not** a pure **`sub name;`** forward stub with only attributes), **`setAnnotation("parseTimeCodeRef", …)`** on the **`OperatorNode("&", …)`**.
- **`BytecodeCompiler`** embeds **`parseTimeCodeRef`** into the bytecode constant pool (**interpreter** / eval-string parity with compile-time **`&`** pinning).
- **JVM** **`EmitSubroutine.handleApplyOperator`**: **`&(bareword)`** must load the callee via **`EmitVariable` → `getGlobalCodeRef`** (runtime glob). Embedding **`parseTimeCodeRef`** with **`GlobalVariable.registerCompiledCodeRef`** was wrong for **`local *Pkg::…`** overrides (**CPANPLUS::Dist::MM** **`format_available`** / **t/20**) because the ID pins a **`RuntimeScalar`** that **`replacePinnedCodeRef`** does not update.

### Regression tests

```bash
./gradlew shadowJar    # ./jperl uses target/perlonjava-*.jar — rebuild after Java changes
timeout 120 ./jperl src/test/resources/unit/pr694_core_regressions.t
timeout 120 ./jperl src/test/resources/unit/eval_after_stash_delete.t
```

---

## Roadmap: `./jcpan -t CPANPLUS` — **detailed next steps**

_Re-run **`timeout 3600 ./jcpan -t CPANPLUS`** after substantive runtime/parser fixes and paste a fresh TAP / summary line into this doc (prior snapshot **`1267`** subtests / **`7/20`** dubious programs with CPANPLUS **0.9916** is stale)._

### 0. Routine verification (every CPANPLUS-related push)

1. **`make`** (Gradle **`shadowJar`** + unit shards — required before PR updates per **`AGENTS.md`**).
2. **Interpreter / eval regressions:**  
   `timeout 120 ./jperl src/test/resources/unit/pr694_core_regressions.t`  
   `timeout 120 ./jperl src/test/resources/unit/eval_after_stash_delete.t`
3. **Smoke require** from a CPANPLUS tree (adjust paths):  
   `PERL5LIB="…/CPANPLUS-*/blib/lib:…/CPANPLUS-*/blib/arch:$PERL5LIB" timeout 120 ./jperl -e 'require CPANPLUS::Config'`
4. **Harness:** **`timeout 3600 ./jcpan -t CPANPLUS`** — capture full log under **`jcpan/`** / build output; note first failing **`t/`** program and TAP line.

### 1. ~~**`BUILD_PL` / `MAKEFILE` barewords~~ — **Done**

See “Resolved … **`BUILD_PL` / `MAKEFILE`**” above.

### 2. ~~**`t/00`** `_version_to_number` / **`version`**~~ — **Done**

See “Resolved … **`_version_to_number`**” above.

### 3. **`t/031`** SQLite source + **`DBIx::Simple`** (**Priority: high**)

- **Symptom:** **`Can't call method "execute" on an undefined value`** in **`DBIx/Simple.pm`**, exercised via **`CPANPLUS::Internals::Source::SQLite`**.
- **Next steps:**
  1. Reproduce under **`timeout 180 ./jperl …/t/031_CPANPLUS-Internals-Source-SQLite.t`** with upstream **`PERL5LIB`** (or **`./jcpan`** unpacked tree).
  2. Inspect whether **`dbh`** / **`$self->{dbh}`** is never set vs cleared early — trace **`DBI`**, **`connect`**, and any **`DESTROY`/scope** ordering under **`jperl`** (compare **`perl`** on same script).
  3. Fix **PerlOnJava** runtime or (**only if unavoidable**) shim bundled **`DBIx::Simple`** — prefer fixing **`DBI`/`disconnect`** parity or refcount/GC quirks over changing CPANPLUS.
  4. Re-run **`t/031`** then a short **`SQLite`/`Source`** slice of **`jcpan -t CPANPLUS`**.

### 4. ~~**`t/20`** **`CPANPLUS::Dist::MM`** / **`can_load`**~~ — **Done (2026-05-16, JVM)**

- **Symptom:** **`local *CPANPLUS::Dist::MM::can_load = sub { … }`** should change **`can_load(...)`** inside **`format_available`**; **`jperl`** was still calling the pre-local CV.
- **Fix:** **`EmitSubroutine.handleApplyOperator`** no longer embeds parser **`parseTimeCodeRef`** via **`registerCompiledCodeRef`**; **`&(bareword)`** always goes through **`getGlobalCodeRef`** so **`local *glob`** (**`RuntimeGlob.dynamicSaveState`** / **`replacePinnedCodeRef`**) wins. Interpreter path still uses **`parseTimeCodeRef`** (**pr694** / stash-delete pinning).
- **Check:** **`src/test/resources/unit/cpanplus_dist_mm_can_load_local.t`** (also rebuild **`shadowJar`** before spot-checking **`./jperl -e`** — stale jars looked like **`local`** was broken).

### 5. **`File::Copy`** warnings — **Mitigated**

Occasional **`$!` + 0** noise from bundled **`File/Copy.pm`** — see mitigation above. Re-audit if **`jcpan`** output still warns on **`Copy`** paths.

### 6. Documentation + incident hygiene

- After each **`jcpan -t CPANPLUS`** run, update **this file** with: date, subtest totals, dubious-program count, and the **short list** of remaining failing **`t/`** scripts.
- Keep **`dev/modules/cpan_client.md`** in sync only when **`jcpan`** behavior or **`PERL5LIB`** layout changes.

---

## Progress tracking

| Area | Status | Notes |
|------|--------|--------|
| Empty list / **`require`** false negative | **Done** | Cloned **`CompilerOptions`**, evaluator fixes, bytecode last-stmt **`require`** parity, **`doFile`** empty-list heel when **`$@`** clean |
| **`make`** (unit shards) | **Done** | Run before pushing |
| **`jcpan -t CPANPLUS`** bootstrap | **Unblocked** | **`conf.pl`** + **`Selfupdate`** / **`Report`** no longer abort on **`Config`** |
| **`BUILD_PL` / `MAKEFILE` filetest/`stat`** | **Done** | ALLCAP bareword handle heuristic vs **`->`** / defined package sub (**`FileHandle`** helper) |
| **`t/00`** version / **`numify`** | **Done** | **`VersionHelper`**, **`perlmodule/Version`** |
| **Strict + string `eval` + import/**`no`** (pr694)** | **Done** | **`SubroutineParser`** **`parseTimeCodeRef`** → **`BytecodeCompiler`**; **`pr694_core_regressions.t`**, **`eval_after_stash_delete.t`** |
| **`File::Copy` warn + 0 `$!`** | **Mitigated** | Bundled **`File/Copy.pm`** |
| **`t/031` SQLite Source** | **Open** | **`DBIx::Simple`** `execute` on undefined (**`dbh`**) |
| **`t/20` Dist::MM / `can_load`** | **Done (JVM)** | **`EmitSubroutine`**: no **`registerCompiledCodeRef`** for **`&`** calls; **`cpanplus_dist_mm_can_load_local.t`** |

---

## Open questions

- Should the **`doFile`** empty-list coercion be tightened (e.g. only **`.pm`** paths, file size cap, circular-depth probe) vs keeping the current conservative **`compilationUnitFromRequireOrDo`** guard?
- **ASM `ArrayIndexOutOfBoundsException`** in frame compute during heavy BEGIN stacks: already falls back to interpreter — track reduction of fallback frequency?
