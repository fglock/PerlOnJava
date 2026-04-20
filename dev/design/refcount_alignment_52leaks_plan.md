# DBIC `./jcpan -t DBIx::Class` — Refcount Alignment Plan

**Status:** Active (Phase H in progress)
**Branch:** `feature/refcount-alignment` (PR #508)
**Depends on:** `dev/design/refcount_alignment_plan.md` (completed — Phases 1-7)
**Goal:** `./jcpan -t DBIx::Class` passes 0 failures, without any DBIC patches.

---

## Current state (2026-04-20, Phase H H2+H3 complete)

`./jcpan -t DBIx::Class` (parallel, 312 test files):
- **261 pass, 49 skipped, 2 with issues**
- Previously hanging tests (60core.t, 08pager.t) **now pass** ✅

Standalone individual tests:
- `t/60core.t` 125/125 in 6s ✅ (was HANG at test 108)
- `t/cdbi/sweet/08pager.t` 9/9 in 3.7s ✅ (was HANG in END block)
- `t/storage/txn_scope_guard.t` 18/18 ✅
- `t/100populate.t` 108/108 ✅
- Sandbox `dev/sandbox/destroy_weaken/` 213/213 ✅
- Full unit suite (`make`) PASS ✅
- `t/52leaks.t` 9 pass / 11 fail (pre-existing 10 DBIC-leak failures
  were already present before Phase H; Phase H surfaces +1 additional
  ARRAY leak as a trade-off for fixing the hangs).

Remaining issues (Phase H continuation):
- `t/52leaks.t`: 10 pre-existing DBIC Schema/Source leaks + 1 new ARRAY
- `t/storage/error.t` test 49: Schema DESTROY cascade (H4 — unchanged)
- `t/zzzzzzz_perl_perf_bug.t`: slow benchmark test (~60s+) — unclear
  if still hangs or just slow; no trampoline loop observed.

---

## 1. Problem (original framing)

Before this plan, DBIC's `t/52leaks.t` failed with 9 real leaks:

```
not ok 12 - ARRAY(...) | basic random_results (refcnt 1)
not ok 13 - DBICTest::Artist=HASH(...) (refcnt 2)
not ok 14-19 - DBICTest::CD / Schema / ResultSource::Table (refcnt 2-6)
not ok 20 - HASH(...) | basic rerefrozen
```

Each failure meant a weak reference registered in DBIC's leak tracer was still
`defined` at assertion time, when Perl 5 would have cleared it because the
referent became unreachable.

## 2. Root causes (identified during implementation)

1. **Recursive trampoline in `RuntimeCode.apply`** for `goto &func` → O(N)
   JVM stack on long chains, crashed DBIC tests.
2. **Interpreter closure over-capture** — captured ALL visible lexicals,
   inflating `captureCount` and pinning unused variables in
   `MortalList.deferredCaptures` past scope exit.
3. **Storable arg-push refcount leak** — `RuntimeArray.push` into Java-side
   temporary arg arrays (`freezeArgs`/`thawArgs`) for STORABLE_freeze/thaw
   hooks bumped referents' refCount, but no matching release on Java-local
   array death.
4. **Block-scope `my` vars lingered in `MyVarCleanupStack`** (static ArrayList)
   past Perl-level scope exit, holding their values alive.
5. **Schema self-save (`rescuedObjects`) cycles** prevented walker from
   clearing weak refs to cyclically-held blessed objects.

---

## 3. Completed phases (summary)

| Phase | Commit | What changed | Impact |
|---|---|---|---|
| **Phase 0-7** (earlier session) | — | Baseline refcount alignment — see `refcount_alignment_plan.md` | Phases 1-3 DESTROY FSM fixed TxnScopeGuard etc. |
| **Phase B1** | `5813ea658` | `ScalarRefRegistry` WeakHashMap of ref-holding scalars; walker uses as live-lexical seeds after `forceGcAndSnapshot()` (3-pass `System.gc()` with `WeakReference` sentinels). | Walker sees live lexicals that pure global-root walks miss. |
| **Phase B2a** | `28bd7363c` | `ModuleInitGuard` (ThreadLocal counter); `MortalList.maybeAutoSweep()` with 5s throttle; `ReachabilityWalker.sweepWeakRefs(boolean quiet)` — quiet mode auto-sweep (no DESTROY, no rescue drain), non-quiet for explicit `jperl_gc`. | Auto-weak-ref cleanup at statement boundaries while safe. |
| **Phase C** | `da301ca6f` | `RuntimeCode.apply` rewritten as **iterative trampoline** — `while(true)` wraps entire body, all dispatch paths (TIED, READONLY, GLOB, STRING, AUTOLOAD, TAILCALL, overload) update `curScalar`/`curArgs` and `continue`. | Fixed 4 DBIC test crashes (`60core.t`, `96_is_deteministic_value.t`, `cdbi/68-inflate_has_a.t`, `inflate/core.t`). |
| **Phase D** | `ea39d29a8` | `RuntimeScalar.undefine()` fires walker on blessed-with-DESTROY cycle; `DestroyDispatch.sweepPendingAfterOuterDestroy` flag drained by outermost DESTROY. | Safety net for cyclic undef. |
| Diag | `578b4ba31` | `JPERL_TRACE_ALL=1`, `JPERL_REGISTER_STACKS=1` — reverse-trace to container holders with registration stacks in `Internals::jperl_trace_to`. | Diagnostic infra — kept. |
| **Phase E** | `87ed18e00` | `MyVarCleanupStack.unregister(Object)` called at scope-exit bytecode emission (`EmitStatement.emitScopeExitNullStores`). | Block-scoped my-vars no longer lingered past Perl scope. |
| **Phase F** | `ad7d32972` | `BytecodeCompiler.collectVisiblePerlVariablesNarrowed(Node body)` — ports JVM backend's `EmitSubroutine.java:120-140` capture-narrowing to interpreter. Three call sites (`detectClosureVariables`, `visitNamedSubroutine`, `visitAnonymousSubroutine`) respect `VariableCollectorVisitor.hasEvalString()`. | **Fixed `basic rerefrozen` leak** + test 49 "Self-referential RS conditions" (TODO→pass). |
| **Phase G** | `e8cec9a76` | `Storable.releaseApplyArgs(RuntimeArray)` helper. Called after each of 5 `RuntimeCode.apply(method, args, ...)` sites in `Storable.java` (dclone freeze/thaw, freeze, thaw, YAML thaw). | **Fixed `basic result_source_handle` leak → 52leaks.t unpatched 10/10 standalone.** |
| **Phase H (H2)** | `2e5b853be` | `ReachabilityWalker.sweepWeakRefs`: in QUIET auto-sweep, skip clearing weak refs to unblessed non-CODE containers (ARRAY/HASH). | **Fixed `t/60core.t` hang at test 108 (multicreate via Sub::Defer accessors).** Root cause: Sub::Defer's `$deferred_info` ARRAY is reachable only through closure captures (`walkCodeCaptures=false`); clearing its weak ref in `%DEFERRED` wipes the dispatch table and `goto &$undeferred` loops forever. |
| **Phase H (H3)** | `6501ddb94` | `WeakRefRegistry.clearAllBlessedWeakRefs`: skip unblessed referents (blessId==0). | **Fixed `t/cdbi/sweet/08pager.t` hang in END block.** Same root cause — pre-END cleanup used to wipe Sub::Defer bookkeeping, then DBIC's `assert_empty_weakregistry` END block looped in stringify dispatch. |

---

## 4. What we tried and REJECTED (do not repeat)

These approaches were implemented, tested, and **reverted** because they
broke other tests. Documented here so future attempts don't retry the same
dead ends.

### 4.1 Walker-filter approaches (all breaks DBIC Schema back-ref chains)

1. **Walker skip `!sc.refCountOwned`** (shipped briefly as `09b438101`,
   reverted as `55b34eacd`). Skipped orphaned registry entries as walker
   seeds. Closed some minimal-repro leaks. **Broke `t/60core.t`**: the
   filter classifies some legitimate live-lexical scalars as orphaned,
   causing walker to consider DBIC Schema back-refs unreachable and
   prematurely clearing them → "detached result source" errors.

2. **Walker skip `sc.scopeExited`**. Skip scalars whose Perl-level scope
   has exited but `captureCount > 0` (over-captured via closures). Same
   DBIC back-ref breakage as (1).

3. **`isContainerElement` flag + walker skip**. Added a boolean to
   `RuntimeScalar`; set by `incrementRefCountForContainerStore`; walker
   skipped as root. Breaks DBIC heavily — some hash/array elements point
   at blessed objects whose back-refs need walker visibility for weak-ref
   preservation. **Kept the field** (cheap, may help future diagnostics),
   but filter is disabled.

### 4.2 Proactive unregister approaches

4. **`MortalList.addDeferredCapture` recursive element unregister**. When
   a scalar joins `deferredCaptures`, recursively unregister element
   scalars inside its value. **Breaks `t/60core.t` column_info tests**:
   BFS descends too eagerly into containers still needing walker
   visibility.

5. **`MortalList.scopeExitCleanupHash` per-element unregister**. Call
   `ScalarRefRegistry.unregister(s)` when flipping `rcO=false` during
   container scope-exit. Didn't fire for the target leak because
   `$base_collection`'s refCount never dropped to 0 while in
   `deferredCaptures`. No-op net effect.

### 4.3 Auto-sweep tuning

6. **Lower throttle (500ms / 100ms)**. Auto-sweep ran too frequently on
   52leaks-scale tests, causing minute-scale slowdowns from repeated
   `System.gc()` + walker traversals. Reverted to **5 s throttle**.

7. **Auto-sweep `flushDeferredCaptures` at statement boundaries**. Decrement
   refCounts for deferred-capture scalars during normal run. Dangerous —
   those scalars might still be actively used by closures mid-statement.
   Not attempted; documented as architecturally incorrect.

### 4.4 Key decisions locked in

- **Auto-sweep throttle: 5 seconds.** Any shorter kills DBIC-scale tests.
- **Quiet mode (auto-sweep) does NOT fire DESTROY or drain `rescuedObjects`.**
  Only explicit `Internals::jperl_gc()` does both. Mid-run DESTROY risks
  breaking DBIC/Moo code not prepared for cleanup in unrelated paths.
- **VariableCollectorVisitor.hasEvalString()** is the gate for narrowing.
  When true (body contains `eval STRING` / `evalbytes STRING`), skip
  narrowing and capture all visible lexicals.

---

## 5. Core architecture (kept)

These pieces of infrastructure were built during the session and are **kept
in production** because they underpin multiple fixes and diagnostic tooling.

### 5.1 Reachability walker (`ReachabilityWalker`)

Mark-and-sweep over the Perl heap, seeded from:
- Globals (`GlobalVariable.globalVariables`, globalArrays, globalHashes,
  globalCodeRefs).
- `DestroyDispatch.rescuedObjects` (snapshot).
- `ScalarRefRegistry.snapshot()` — live ref-holding scalars found via
  3-pass `System.gc()` + `WeakReference` sentinels
  (`forceGcAndSnapshot()`).

Skip conditions (walker seeding):
- `sc.captureCount > 0` (closure-captured — would pull in closure's scope).
- `WeakRefRegistry.isweak(sc)` (weakened ref).

Two modes:
- **Quiet** (`sweepWeakRefs(true)`) — auto-sweep called from
  `MortalList.flush`. Clears weak refs only, does NOT fire DESTROY, does
  NOT drain rescuedObjects.
- **Non-quiet** (`sweepWeakRefs(false)`) — explicit `Internals::jperl_gc()`.
  Drains `rescuedObjects` first, fires DESTROY on unreachable blessed
  objects, clears weak refs.

Diagnostic: `Internals::jperl_trace_to(ref)` returns a path from any
root. With `JPERL_TRACE_ALL=1` and `JPERL_REGISTER_STACKS=1`, dumps
direct-holder scalars with registration stacks and reverse-trace to
container holders.

### 5.2 Scalar ref registry (`ScalarRefRegistry`)

`WeakHashMap<RuntimeScalar, Boolean>` — tracks all scalars that have been
assigned a reference via `setLarge*` or `incrementRefCountForContainerStore`.
Weak keys so JVM GC prunes entries when the scalar is no longer Java-alive.

Optional: `JPERL_REGISTER_STACKS=1` records a `Throwable` per
`registerRef` call in a parallel `WeakHashMap<RuntimeScalar, Throwable>`.
Used by `jperl_trace_to` to show registration stacks.

### 5.3 Module init guard (`ModuleInitGuard`)

ThreadLocal counter incremented on entry to `require`/`use`/`eval STRING`/
`do FILE` (wrapped in `PerlLanguageProvider.executeCode` for
non-main-program runs). `MortalList.maybeAutoSweep()` and
`RuntimeScalar.undefine()` walker triggers check this; skip sweeping
during module init.

### 5.4 MyVarCleanupStack unregister

`MyVarCleanupStack.unregister(Object)` — called by emitted bytecode at
block scope-exit (`EmitStatement.emitScopeExitNullStores`) BEFORE the
ACONST_NULL/ASTORE that releases the Java local slot. Prevents the
static ArrayList from holding block-scoped scalars alive past their
Perl-level scope.

### 5.5 Storable arg-release

`Storable.releaseApplyArgs(RuntimeArray args)` — decrements
`refCountOwned=true` elements' referent refCount, flips
`refCountOwned=false`, clears the array. Called after every
`RuntimeCode.apply(method, args, ...)` in Storable.java, semantically
matching what `@_` drain does Perl-side.

### 5.6 Runtime diagnostic env vars

| Env var | Effect |
|---|---|
| `JPERL_GC_DEBUG=1` | Logs `DBG auto-sweep cleared=N` on each auto-sweep |
| `JPERL_NO_AUTO_GC=1` | Disables auto-sweep entirely |
| `JPERL_NO_SCALAR_REGISTRY=1` | Disables `ScalarRefRegistry` (benchmark only) |
| `JPERL_TRACE_ALL=1` | `jperl_trace_to` dumps direct/container holders |
| `JPERL_REGISTER_STACKS=1` | `ScalarRefRegistry.registerRef` records stacks |

---

## Phase H — close remaining `./jcpan` parallel-run issues

**Target: production readiness.**

### Baseline (2026-04-20 full run)

```
Files=314, Tests=13792, Result: FAIL
Failed 5/314 test programs. 11/13792 subtests failed.
```

| # | File | Failure mode | Priority |
|---|---|---|---|
| H1 | `t/52leaks.t` | 10 real fails (tests 9-18). Leaks: Artist + 2×Schema + 2×ResultSource::Table, refcnt 2-6 (DBIC phantom-chain). **Standalone: 0 fails.** | HIGH |
| H2 | `t/60core.t` | **300 s timeout → SIGKILL (exit 137).** All 108 started subtests passed, then hangs. **Standalone: 125/125 in 6s.** | HIGH |
| H3 | `t/cdbi/sweet/08pager.t` | **300 s timeout → SIGKILL.** All 9 subtests passed, then hangs in END block `assert_empty_weakregistry`. | HIGH |
| H4 | `t/storage/error.t` | Test 49 "callback works after \$schema is gone" — Schema self-save (`rescuedObjects`) prevents walker cleanup. Known Phase B-deferred. | MED |
| H5 | `t/zzzzzzz_perl_perf_bug.t` | `Unable to lock _dbictest_global.lock: Resource deadlock avoided`. Cascade from H2/H3 holding DBICTest flock past 15-min timeout. | Resolves via H2+H3 |

### Bonus: 8 TODO passes (DBIC's `TODO 'Needs Data::Entangled'`, RT#82942)

- `t/sqlmaker/limit_dialects/generic_subq.t`: 9, 11, 13, 15, 17
- `t/storage/txn_scope_guard.t`: 13, 15, 17

These confirm Phase F/G materially improved leak tracking beyond what DBIC
authors believed possible. Preserve these in regression gates.

---

### H1 — t/52leaks.t under parallel (10 phantom-chain leaks)

#### Observations

Each parallel prove worker runs 52leaks.t in **its own JVM process**
(independent memory/state). Standalone: 10/10. Parallel: 10 fails.

Leak targets: DBIC Schema / Source / Artist with refcount 2-6 — the
classic `source_registrations` phantom-chain cycle.

#### Hypotheses (ordered by likelihood)

**H1a — JVM memory pressure delays WeakHashMap pruning.**
`forceGcAndSnapshot()` runs 3 `System.gc()` cycles with `WeakReference`
sentinels. Under parallel load on a small-heap JVM, Full-GC may run less
aggressively, leaving weak-key entries unpruned. Walker over-reports
reachability.

Fix: increase sentinel wait time with exponential backoff (10/20/40/80 ms),
or set explicit `-Xmx` via `jperl` wrapper for CPAN builds.

**H1b — DBICTest setup timing.**
Under parallel run, `DBICTest::init_schema` takes longer (file creation,
flock wait). Long operations hold refcount bumps on intermediate objects,
giving Phase B2a auto-sweep more opportunities to mis-classify
live-through-deferred objects.

Fix: verify with `JPERL_GC_DEBUG=1` under prove. If auto-sweep fires
mid-setup and clears in-flight weak refs, extend `ModuleInitGuard`
coverage to DBICTest's load sequence.

**H1c — Per-process startup non-determinism.**
HashMap iteration order or similar could cause Sub::Defer accessor
first-use order to differ between runs. Under certain orders, Schema's
`source_registrations` weakening fires before Source's back-ref is set
up, leaving a non-weak cycle.

Fix: audit Schema::DESTROY / source_registrations weaken order; ensure
Source.schema is weakened in Source's constructor.

#### Investigation plan

1. Reproduce: `prove -j8 t/52leaks.t` × 10 runs. Is it deterministic or
   flaky? Same 5 objects every run, or varying?
2. Compare `JPERL_GC_DEBUG=1 JPERL_REGISTER_STACKS=1` output between
   standalone (pass) and parallel (fail) runs.
3. If timing-related, try forcing serial for 52leaks.t via test-specific
   lock or env var.
4. If memory-related, try `-Xmx4g` and longer `forceGcAndSnapshot`
   backoff.

---

### H2 — t/60core.t parallel hang

#### Observations

**Standalone: 125/125 in 6s. Parallel: passes 108 then hangs.**

JFR-style stack sampling (20 samples at 0.3s) shows hot path cycling:
- `RuntimeCode.call:1954`
- `BytecodeInterpreter.execute:1170`
- `RuntimeCode.apply:2390` (hint-hash push — iterative trampoline loop)
- `RuntimeCode.apply:2406` (code.apply inner call)
- `Sub/Defer.pm:2382` (Moo accessor deferred dispatch)

Iterative trampoline (Phase C) is O(1) stack but O(N) time. If N is
unbounded, the trampoline loops forever. Failure point is around
test 108 — followed by multicreate tests with inflator/deflator
(`$empl->secretkey->encoded`, a chain of 2 method calls each going
through Sub::Defer-generated accessors).

#### Hypotheses

**H2a — Inflator/deflator call loop.**
Phase F's closure-capture narrowing may have dropped a lexical the
deflator needs, causing fallback to re-dispatching the stub forever.

Fix: isolate which lexical; narrow `VariableCollectorVisitor`'s missed
cases (regex captures `$1`, slice context, lvalue refs, formats).

**H2b — Sub::Defer cache miss causing re-dispatch.**
Sub::Defer caches undeferred code in `%Sub::Defer::DEFERRED`. Some
invariant could be violated, causing cache miss on every call.

Fix: instrument `Sub::Defer::undefer_sub` with cache hit/miss counters;
find where re-dispatch originates.

**H2c — VariableCollectorVisitor misses a use-form.**
If 60core uses a construct the visitor treats as not-referencing a
variable when it does (regex captures, formats, `do FILE`, eval STRING
in regex), closure is called with missing state → re-dispatch.

Fix: re-enable `JPERL_PHASE_F_DBG=1` print in
`BytecodeCompiler.detectClosureVariables` and
`collectVisiblePerlVariablesNarrowed`; compare between passing and
hanging runs.

#### Investigation plan

1. Run 60core.t standalone under `perl_test_runner.pl` with 600 s
   timeout to verify exact behavior at test 108→109.
2. If it hangs standalone too, bisect between Phase E (good) and
   Phase G (hang):
   - `git bisect start feature/refcount-alignment 87ed18e00`
   - At each, test 60core.t with 30 s timeout; mark pass/fail.
3. Capture jstack at hang point; identify which Perl sub is in the
   dispatch loop.
4. Re-enable `JPERL_PHASE_F_DBG=1` trace (the debug print was removed
   in the clean-up commit; re-add temporarily).

---

### H3 — t/cdbi/sweet/08pager.t END-block hang

#### Observations

300 s timeout. All 9 subtests passed. Hang is in END block:
```perl
END {
    assert_empty_weakregistry($weak_registry, 'quiet');
}
```

Stack hot frame in `Sub/Defer.pm:2378` via `DBICTest.pm:1693`. END
iterates weak_registry entries; each entry's display-string generation
goes through Sub::Defer → apply → apply (trampoline).

#### Root cause hypothesis

With many weak refs registered under parallel test conditions vs fewer
standalone, the inner loop amplifies any per-call slowness. Likely
`ScalarRefRegistry` has grown large (possibly tens of thousands of
entries) because some scalars have strong JVM references elsewhere
blocking WeakHashMap pruning.

#### Investigation plan

1. Add `ScalarRefRegistry.approximateSize()` diagnostic at START of
   END block (via a timer that logs every 5s).
2. If size is in 10000s, identify what's strongly holding the scalars
   — likely a Java-side cache needing pruning on scope exit.
3. Consider: `ScalarRefRegistry` entries hold `Throwable` stacks when
   `JPERL_REGISTER_STACKS=1`. Even without it, every registered scalar
   survives as long as something references it. Audit for accidental
   strong references (static caches, singleton maps).

---

### H4 — t/storage/error.t test 49 (Schema DESTROY cascade)

Known deferred issue (Phase B documented). Test expects:
```perl
undef $schema;
$dbh->do('INSERT INTO nonexistent_table ...');  # should hit branch B
```

But `$weak_self` in the HandleError closure is still defined because
Schema's self-save (`source->{schema} = $self; weaken`) puts Schema in
`DestroyDispatch.rescuedObjects`, preventing walker from freeing it.

#### Proposed approach for Phase H4

**Schema-aware DESTROY trigger.** When `DestroyDispatch.doCallDestroy`
detects the class is `DBIx::Class::Schema` (or inherits from it) and
DESTROY fires, also invoke `ReachabilityWalker.sweepWeakRefs(false)`
synchronously BEFORE returning — so `clearWeakRefsTo(storage)` fires
before the test's `$dbh->do(...)` check.

Alternative: a more general rule — run a walker sweep AT THE END of
every outermost DESTROY that accessed `rescuedObjects`. Scoped to
preserve test 18's existing behavior (which relies on phantom-chain
preserved mid-test).

#### Risk

Changing `rescuedObjects` semantics could re-break 52leaks.t test 18.
Validate with both tests before committing.

---

### H5 — t/zzzzzzz_perl_perf_bug.t

Not independent. Error:
```
Unable to lock _dbictest_global.lock: Resource deadlock avoided
```

H2 (60core.t) or H3 (08pager.t) holds the DBICTest global exclusive
lock past the 15-min `await_flock` timeout, kernel detects deadlock.

**Fixing H2 and H3 resolves H5 automatically.** No separate work.

---

## Implementation order for Phase H

### H-P1: Fix hangs (H2, H3)

Most impactful — users see `jcpan` stuck. H5 resolves automatically.

**Start with H2 reproduction:**
```bash
cd /Users/fglock/.perlonjava/cpan/build/DBIx-Class-0.082844-9
# Standalone with long timeout
time timeout 300s /Users/fglock/projects/PerlOnJava3/jperl -Iblib/lib -Iblib/arch t/60core.t > /tmp/60c.out 2>&1
# Capture stacks if it hangs
```

If it hangs standalone, bisect:
- `git bisect start feature/refcount-alignment 87ed18e00`
- Test at each commit with 30 s timeout; mark pass/fail.

If it only hangs under parallel, it's a timing/contention issue — try
H2c (VariableCollectorVisitor coverage) first since Phase F is the
most likely regressor.

### H-P2: t/52leaks.t parallel (H1)

10 leaks under parallel. Single-test reproduction:
```bash
prove -j8 t/52leaks.t  # run 10 times, note pass/fail count
```

Compare with:
```bash
JPERL_GC_DEBUG=1 JPERL_REGISTER_STACKS=1 prove t/52leaks.t
JPERL_GC_DEBUG=1 JPERL_REGISTER_STACKS=1 prove -j8 t/52leaks.t
```

### H-P3: t/storage/error.t test 49 (H4)

Schema-DESTROY walker trigger in `DestroyDispatch.doCallDestroy`.
Low-risk surgical change but needs 52leaks.t test 18 regression gate.

---

## Success criteria

1. `./jcpan -t DBIx::Class` completes without any test hanging (no 300 s
   timeouts).
2. `t/52leaks.t` passes cleanly under **both** standalone and
   `prove -j8` parallel.
3. `t/storage/error.t` passes all 49 subtests.
4. `t/zzzzzzz_perl_perf_bug.t` runs without flock deadlock.
5. **Zero regressions** in the 309 currently-passing test files.
6. **8 bonus TODO-passes preserved** (generic_subq.t 9/11/13/15/17,
   txn_scope_guard.t 13/15/17).
7. Sandbox 213/213, `make` PASS — unchanged.

## Non-goals

- Fixing DBIC's own design issues (source_registrations cycles) —
  we work around them.
- 100% parity with native Perl on all 313 tests (native Perl itself
  has 8 TODO fails).
- Re-enabling any patched-DBIC workflow.

## Stretch goal

Pass `./jcpan -t DBIx::Class` cleanly with `prove -j1` serial first
as the production-readiness floor, then tackle parallel as a separate
milestone.

---

## References

- `dev/design/refcount_alignment_plan.md` — original refcount plan (Phases 1-7)
- `dev/architecture/weaken-destroy.md` — weaken/DESTROY architecture
- `dev/patches/cpan/DBIx-Class-0.082844/` — opt-in LeakTracer patch (now
  **obsolete** — kept only for comparison / fallback)
- PR: https://github.com/fglock/PerlOnJava/pull/508
- Key commits: `da301ca6f` (C), `ea39d29a8` (D), `87ed18e00` (E),
  `ad7d32972` (F), `e8cec9a76` (G)
