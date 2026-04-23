# Multiplicity v2: Incremental Re-implementation Plan

**Date:** 2026-04-11
**Context:** PR #480 was reverted (PR #487) due to a Scalar::Util version parsing error that broke Moo tests. This plan re-applies the same work incrementally to catch regressions early.

---

## Root Cause Analysis of the Revert

### The Scalar::Util Version Parsing Error

**Error:** `Error while parsing version number in file 'jar:PERL5LIB/Scalar/Util.pm'`

**Root cause (confirmed by commit `d87fe1885` on `feature/multiplicity-v2`):**

`RuntimeRegex.matchRegexWithTimeout()` creates a single-thread executor for alarm-based regex timeout. After the multiplicity migration, these executor worker threads have **no PerlRuntime bound** via `ThreadLocal`, so `matchRegexDirect()` throws `IllegalStateException("No PerlRuntime bound to current thread")` when it tries to access per-runtime regex state.

The trigger chain:
1. `CPAN::Module::parse_version` sets `alarm()` before calling `MM->parse_version()`
2. `MM->parse_version()` reads `Scalar/Util.pm` line by line, using regex to extract `$VERSION`
3. The regex matching goes through the alarm/timeout executor thread
4. That thread has no `PerlRuntime` bound -> exception -> caught by `eval {}` -> error message

### Additional Regressions Found Post-Merge (commit `f98ce32be`)

| Issue | Root Cause | Fix |
|-------|-----------|-----|
| alarm.t tests 1-4 | alarm scheduler thread had no PerlRuntime bound | Capture + bind PerlRuntime in alarm thread |
| goto-sub.t | JVM compilation of closures capturing @arrays/%hashes failed — interpreter stores as `RuntimeBase` but JVM expects typed params | Skip JVM compilation for non-scalar captures |
| parser.t | `LinkageError` (VerifyError) not caught for eval STRING fallback | Catch `LinkageError` in addition to `Exception` |
| attrs.t | Attributes not propagated through `JvmClosureTemplate` | Add attributes field + MODIFY_CODE_ATTRIBUTES dispatch |

### Key Lesson

**Every code path that spawns a thread must bind `PerlRuntime` to that thread.** The original PR missed at least 3: regex timeout executor, alarm scheduler, and (partially) pipe I/O threads. The big-bang approach (96 files, ~10K lines) made these impossible to isolate before merge.

---

## What Already Exists

| Branch | Contents |
|--------|----------|
| `feature/multiplicity` | Original implementation (all phases, 96 files) |
| `feature/multiplicity-opt` | Performance optimizations (Tier 1-3: local caching, batch push/pop, JvmClosureTemplate) |
| `feature/multiplicity-v2` | Revert + 2 fix commits (regex timeout thread binding, alarm/goto-sub/parser/attrs fixes) |

The code from these branches is the reference implementation. We are **re-applying the same work** in smaller, testable increments — not redesigning from scratch.

**How to use the reference branches:** Don't cherry-pick (the original commits span all subsystems at once). Instead, when implementing a phase, diff the relevant files to see the target state:

```bash
# Example: see what Phase 3 (I/O) should look like
git diff master..feature/multiplicity -- src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeIO.java
git diff master..feature/multiplicity -- src/main/java/org/perlonjava/runtime/operators/IOOperator.java

# See the PerlRuntime class (the target for all state)
git show feature/multiplicity:src/main/java/org/perlonjava/runtime/runtimetypes/PerlRuntime.java

# See the performance optimizations (apply in Phase 14)
git log --oneline feature/multiplicity-opt -- src/main/java/org/perlonjava/runtime/runtimetypes/

# See the post-revert fixes (apply in Phases 5, 12, 15)
git show feature/multiplicity-v2:src/main/java/org/perlonjava/runtime/regex/RuntimeRegex.java
```

---

## Validation Gate (Run After Every PR)

Every PR must pass ALL of these before merge:

```bash
make                    # Unit tests (must pass — non-negotiable)
make test-all           # Comprehensive tests including perl5_t
# Moo regression test (the specific failure that caused the revert):
./jperl -e 'use Scalar::Util; print $Scalar::Util::VERSION, "\n"'
# Should print: 1.63
```

Additionally, run benchmarks on PRs that touch hot paths (RuntimeCode.apply, GlobalVariable, InheritanceResolver):

```bash
./jperl dev/bench/benchmark_closure.pl
./jperl dev/bench/benchmark_method.pl
./jperl dev/bench/benchmark_lexical.pl
./jperl dev/bench/benchmark_global.pl
./jperl dev/bench/benchmark_regex.pl
```

Compare against master baselines. Acceptable budget: <5% regression on any single benchmark.

---

## Implementation Phases

### Phase 1: Thread-Safety Fixes (No Behavioral Change)

**Risk: MINIMAL** — Pure safety improvements, no new abstractions.

**Changes:**
- `EmitterMethodCreator.classCounter` -> `AtomicInteger`
- `BytecodeCompiler.nextCallsiteId` -> `AtomicInteger`
- `EmitRegex.nextCallsiteId` -> `AtomicInteger`
- `Dereference.nextMethodCallsiteId` -> `AtomicInteger`
- `EmitterMethodCreator.skipVariables` -> mark `final`
- `LargeBlockRefactorer`: replace shared `controlFlowDetector` singleton with new instance per call

**Files touched:** 4-5 files, ~20 lines changed
**Test:** `make` — zero behavioral change expected

---

### Phase 2: PerlRuntime Shell + Initialization

**Risk: LOW** — Introduces the `PerlRuntime` class but doesn't move any state yet.

**Changes:**
- Create `PerlRuntime.java` with `ThreadLocal<PerlRuntime> CURRENT`
- Only fields: `boolean globalInitialized`, `long pid`, `String cwd`
- API: `current()`, `currentOrNull()`, `initialize()`, `setCurrent()`
- `ensureRuntimeInitialized()` safety net
- Wire `PerlRuntime.initialize()` into `Main.main()` startup
- Wire into `PerlScriptExecutionTest.setUp()` and `ModuleTestExecutionTest.setUp()`

**Key constraint:** No existing static fields move yet. `PerlRuntime` is just a shell that gets created and bound to the main thread.

**Files touched:** ~4 files (PerlRuntime.java new, Main.java, 2 test files)
**Test:** `make` + `make test-all` — zero behavioral change

---

### Phase 3: De-static I/O

**Risk: LOW** — Smallest runtime subsystem, immediate value for JSR-223.

**Changes:**
- Move `RuntimeIO.stdout/stderr/stdin` into PerlRuntime
- Move `selectedHandle`, `lastWrittenHandle`, `lastAccessedHandle`, `lastReadlineHandleName`
- Add static getter/setter methods on RuntimeIO (preserving call-site signatures)
- Update EmitOperator bytecode from PUTSTATIC to INVOKESTATIC for IO fields
- Update ~15 consumer files (IOOperator, RuntimeGlob, TieOperators, etc.)

**Files touched:** ~17 files
**Test:** `make` + `make test-all` + verify `./jperl -e 'print "hello\n"'`

---

### Phase 4: De-static CallerStack + DynamicScope + Special Blocks

**Risk: LOW-MEDIUM** — These are execution-context stacks, heavily used by `local`.

**Changes (4a — CallerStack):**
- Move `CallerStack.callerStack` to PerlRuntime

**Changes (4b — DynamicScope):**
- Move `DynamicVariableManager.variableStack` to PerlRuntime
- Move `RuntimeScalar.dynamicStateStack` to PerlRuntime

**Changes (4c — All 16 local save/restore stacks):**
- `GlobalRuntimeScalar.localizedStack`
- `GlobalRuntimeArray.localizedStack`
- `GlobalRuntimeHash.localizedStack`
- `RuntimeArray.dynamicStateStack`
- `RuntimeHash.dynamicStateStack`
- `RuntimeStash.dynamicStateStack`
- `RuntimeGlob.globSlotStack`
- `RuntimeHashProxyEntry.dynamicStateStack`
- `RuntimeArrayProxyEntry.dynamicStateStackInt` + `dynamicStateStack`
- `ScalarSpecialVariable.inputLineStateStack`
- `OutputAutoFlushVariable.stateStack`
- `OutputRecordSeparator.orsStack`
- `OutputFieldSeparator.ofsStack`
- `ErrnoVariable.errnoStack` + `messageStack`

**Changes (4d — Special Blocks):**
- Move `SpecialBlock.endBlocks/initBlocks/checkBlocks` to PerlRuntime

**Files touched:** ~25 files
**Test:** `make` + `make test-all` + specific focus on `local.t`, `chomp.t`, `defer.t`, `begincheck.t`

---

### Phase 5: De-static Regex State

**Risk: LOW-MEDIUM** — 14 fields, but all follow the same accessor pattern.

**Changes:**
- Move 14 static fields from RuntimeRegex into PerlRuntime
- Add static getter/setter methods on RuntimeRegex
- Update RegexState.java, ScalarSpecialVariable.java, HashSpecialVariable.java
- **CRITICAL: Bind PerlRuntime to regex timeout executor threads** (the fix from `d87fe1885`)

**Files touched:** ~5 files
**Test:** `make` + `make test-all` + `./jperl -e 'use Scalar::Util; print $Scalar::Util::VERSION'` (the exact failure case)

---

### Phase 6: De-static InheritanceResolver + MRO

**Risk: MEDIUM** — Method caches affect performance; incorrect invalidation causes wrong dispatch.

**Changes:**
- Move `methodCache`, `linearizedClassesCache`, `packageMRO`, `overloadContextCache`, `isaStateCache`, `autoloadEnabled`, `currentMRO` to PerlRuntime
- Update DFS.java, C3.java, Mro.java, ~4 consumer files

**Files touched:** ~8 files
**Test:** `make` + `make test-all` + benchmark `method` and `closure` (cache-sensitive)

---

### Phase 7: De-static GlobalVariable (The Big One)

**Risk: HIGH** — Touches the most call sites. All Perl variable access flows through here.

**Changes:**
- Move all 17 static maps from GlobalVariable into PerlRuntime
- Add static accessor methods (getGlobalVariablesMap(), etc.)
- Update ~20 consumer files

**Strategy:** Do this in sub-phases if needed:
- 7a: Move `globalVariables`, `globalArrays`, `globalHashes` (core data)
- 7b: Move `globalCodeRefs`, `pinnedCodeRefs`, `isSubs` (subroutine data)
- 7c: Move `globalIORefs`, `globalFormatRefs` (I/O data)
- 7d: Move `stashAliases`, `globAliases`, `globalGlobs` (aliasing)
- 7e: Move `declaredGlobalVariables/Arrays/Hashes`, `packageExistsCache`, `globalClassLoader`

**Files touched:** ~25+ files
**Test:** `make` + `make test-all` after EACH sub-phase. Benchmark `global`, `lexical`, `eval_string`.

---

### Phase 8: De-static RuntimeCode Caches + Eval State

**Risk: MEDIUM** — Eval compilation state, method handle cache.

**Changes:**
- Move `evalBeginIds`, `evalCache`, `methodHandleCache`, `anonSubs`, `interpretedSubs`, `evalContext`, `evalDepth` to PerlRuntime
- Move inline method cache arrays to PerlRuntime
- Change EmitterMethodCreator/EmitSubroutine bytecode from GETSTATIC to INVOKESTATIC

**Files touched:** ~15 files
**Test:** `make` + `make test-all` + focus on `eval.t`, `parser.t`

---

### Phase 9: De-static WarningBits + HintHash Registries

**Risk: MEDIUM** — These are already ThreadLocal; migration consolidates them into PerlRuntime.

**Changes:**
- Move 7 ThreadLocal stacks from WarningBitsRegistry to PerlRuntime instance fields
- Move HintHashRegistry ThreadLocal stacks to PerlRuntime instance fields
- This eliminates separate ThreadLocal lookups, reducing per-call overhead

**Files touched:** ~3 files
**Test:** `make` + `make test-all` + benchmark `closure` (this is the 14-17 ThreadLocal hotspot)

---

### Phase 10: De-static ByteCodeSourceMapper

**Risk: LOW** — Only affects source location tracking for error messages.

**Changes:**
- Move ByteCodeSourceMapper static collections to PerlRuntime
- Create inner `SourceMapperState` class on PerlRuntime

**Files touched:** ~2 files
**Test:** `make` + `make test-all`

---

### Phase 11: Compilation Thread Safety (COMPILE_LOCK)

**Risk: MEDIUM-HIGH** — This is where the reentrant lock complexity lives.

**Changes:**
- Add `static final ReentrantLock COMPILE_LOCK` to PerlLanguageProvider
- Acquire in `compilePerlCode()` and both `EvalStringHandler.evalString()` overloads
- Move `globalInitialized` from static boolean to per-PerlRuntime
- Restructure `executePerlCode()`: compilation under lock, execution without
- Handle reentrant compilation (BEGIN blocks triggering nested require)
- Use `boolean compileLockReleased` flag pattern (with clear documentation)

**Files touched:** ~4 files (PerlLanguageProvider, EvalStringHandler, RuntimeCode)
**Test:** `make` + `make test-all` + `begincheck.t` + nested `use`/`require` tests

---

### Phase 12: Thread Binding for Background Threads

**Risk: LOW** — Targeted fixes for specific thread-spawning code paths.

**Changes:**
- Bind PerlRuntime to regex timeout executor threads (RuntimeRegex.matchRegexWithTimeout)
- Bind PerlRuntime to alarm scheduler thread (Time.java)
- Bind PerlRuntime to pipe I/O threads (PipeInputChannel, PipeOutputChannel)

**Files touched:** ~4 files
**Test:** `make` + `make test-all` + `alarm.t` + `io_pipe.t` + the Scalar::Util version test

---

### Phase 13: Per-Runtime CWD + PID

**Risk: LOW** — Isolated changes to directory/process utilities.

**Changes:**
- Add per-runtime `cwd` field, initialized from `System.getProperty("user.dir")`
- `Directory.chdir()` updates PerlRuntime CWD instead of system property
- Replace all 21 `System.getProperty("user.dir")` call sites with `PerlRuntime.getCwd()`
- Per-runtime unique PID via AtomicLong counter

**Files touched:** ~14 files
**Test:** `make` + `make test-all` + `directory.t`, `glob.t`

---

### Phase 14: Performance Optimization

**Risk: LOW-MEDIUM** — Pure optimization, no behavioral change.

Apply the optimizations from `feature/multiplicity-opt`:

**Tier 1 — Cache PerlRuntime.current() in local variables:**
- Hot methods get `PerlRuntime rt = PerlRuntime.current()` at entry, use `rt` throughout
- Targets: GlobalVariable.getGlobalVariable, RuntimeCode.apply, InheritanceResolver.findMethod

**Tier 2 — Batch push/pop operations:**
- Create `PerlRuntime.pushCallerState()` / `popCallerState()` that bundles all per-call stack operations into a single method (1 ThreadLocal lookup instead of 6+)
- Create `PerlRuntime.pushSubState()` / `popSubState()` for subroutine entry/exit

**Tier 3 — Skip unnecessary work:**
- Skip RegexState save/restore for subroutines without regex ops
- JVM-compile anonymous subs inside eval STRING (JvmClosureTemplate)

**Target:** All benchmarks within 5% of master. Closure and method dispatch (the -34% and -27% regressions) must be addressed before merge.

**Test:** `make` + full benchmark suite comparison

---

### Phase 15: JvmClosureTemplate + Eval STRING Fixes

**Risk: MEDIUM** — New code for JVM-compiling closures from eval context.

**Changes:**
- Add `JvmClosureTemplate.java` for JVM-compiled closures from eval STRING
- Skip JVM compilation for closures capturing non-scalar variables
- Catch `LinkageError` in addition to `Exception` for JVM bytecode verification failures
- Propagate attributes through JvmClosureTemplate

**Files touched:** ~4 files (new JvmClosureTemplate, BytecodeCompiler, OpcodeHandlerExtended, InterpretedCode)
**Test:** `make` + `make test-all` + `goto-sub.t`, `parser.t`, `attrs.t`

---

### Phase 16: Multiplicity Demo + Documentation

**Risk: NONE** — Non-functional additions.

**Changes:**
- Add `dev/sandbox/multiplicity/` demo files
- Update `dev/design/concurrency.md` with progress tracking
- Update `dev/design/README.md`

---

## Phase Dependency Graph

```
Phase 1 (AtomicInteger safety)     -- no deps, can land immediately
Phase 2 (PerlRuntime shell)        -- no deps
    |
    +-- Phase 3 (I/O)
    +-- Phase 4 (CallerStack + DynamicScope + SpecialBlocks)
    +-- Phase 5 (Regex state)
    +-- Phase 6 (InheritanceResolver)
    +-- Phase 7 (GlobalVariable)     -- highest risk, do last among state migrations
    +-- Phase 8 (RuntimeCode caches)
    +-- Phase 9 (WarningBits + HintHash)
    +-- Phase 10 (ByteCodeSourceMapper)
    |
    +-- Phase 11 (COMPILE_LOCK)      -- needs most state migrated first
    +-- Phase 12 (Thread binding)    -- needs regex state (Phase 5) migrated
    +-- Phase 13 (CWD + PID)         -- independent
    |
    +-- Phase 14 (Performance)       -- after all state migrations
    +-- Phase 15 (JvmClosureTemplate) -- after Phase 8
    +-- Phase 16 (Demo + docs)       -- anytime
```

Phases 3-10 can be done in any order after Phase 2, but the recommended order above goes from lowest-risk to highest-risk.

---

## Estimated Effort

| Phase | Files | Risk | Effort |
|-------|-------|------|--------|
| 1. AtomicInteger safety | 5 | Minimal | 1 hour |
| 2. PerlRuntime shell | 4 | Low | 2 hours |
| 3. I/O | 17 | Low | 3 hours |
| 4. CallerStack + DynamicScope | 25 | Low-Med | 4 hours |
| 5. Regex state | 5 | Low-Med | 2 hours |
| 6. InheritanceResolver | 8 | Medium | 3 hours |
| 7. GlobalVariable | 25+ | **High** | 6-8 hours |
| 8. RuntimeCode caches | 15 | Medium | 4 hours |
| 9. WarningBits + HintHash | 3 | Medium | 2 hours |
| 10. ByteCodeSourceMapper | 2 | Low | 1 hour |
| 11. COMPILE_LOCK | 4 | Med-High | 4 hours |
| 12. Thread binding | 4 | Low | 2 hours |
| 13. CWD + PID | 14 | Low | 2 hours |
| 14. Performance | 10 | Low-Med | 4 hours |
| 15. JvmClosureTemplate | 4 | Medium | 3 hours |
| 16. Demo + docs | 5 | None | 1 hour |

**Total: ~45-50 hours** across 16 PRs (vs. the original single PR)

---

## Key Architectural Decisions (Carry Forward from PR #480)

1. **Zero API change for callers** — Original static method signatures preserved; they delegate to `PerlRuntime.current()` internally
2. **Public accessor methods** for cross-package access (e.g., `GlobalVariable.getGlobalVariablesMap()`)
3. **`PerlRuntime.current()` throws `IllegalStateException`** if no runtime bound — every entry point must initialize
4. **`COMPILE_LOCK` is reentrant** — nested eval/require/BEGIN blocks acquire the same lock
5. **Per-runtime state, not per-thread** — a `PerlRuntime` instance IS an interpreter; it's bound to a thread via ThreadLocal but can be moved between threads
6. **Compile-time state remains static** (protected by COMPILE_LOCK) — only runtime state is per-PerlRuntime

---

## Risk Mitigation

1. **Run Moo validation after every phase** — The specific failure that caused the revert
2. **Phase 12 (thread binding) is critical** — Must land before or with Phase 5 (regex state), since regex timeout threads need PerlRuntime
3. **Phase 7 (GlobalVariable) needs the most testing** — It touches the most call sites
4. **Keep each PR small enough to revert individually** — If Phase N causes a regression, only Phase N is reverted
5. **Benchmark after Phases 6, 7, 8, 9, 14** — These are the performance-sensitive phases
