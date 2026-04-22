# Perl-parity plan — recovering the master-to-perl gap

**Status:** Proposal + Phase 1 execution
**Origin:** `dev/design/perl5_internals_comparison.md` identified 5 structural hot-path differences between PerlOnJava (master and below) and system perl. This doc turns that analysis into concrete phases with per-phase measurement gates.

## Scope

Close the **master-to-perl** gap — the ~1.52× speed ratio master has vs system perl on `life_bitpacked` (and the similar ratio on other sub-call-heavy benches). This is about PerlOnJava's fundamental per-sub-call overhead; it is **separate** from the walker-hardening / refcount-alignment overhead vs master (that's tracked in `life_bitpacked_regression_analysis.md` and §0 of `next_steps.md`).

## Overall measurement protocol

Every phase MUST produce evidence from the **same measurement harness** so phases can be compared:

1. **Correctness gates (hard):**
   - `make` — all unit tests pass except pre-existing `destroy_eval_die.t#4`
   - `src/test/resources/unit/refcount/destroy_eval_die.t` — same pass count as baseline
   - DBIx::Class `t/storage/txn_scope_guard.t` — 18/18
   - `src/test/resources/unit/tie_scalar.t` — 12/12
   - `src/test/resources/unit/refcount/*.t` — same pass count as baseline
   - **Any correctness regression blocks the phase.**

2. **Perf gate (per phase):**
   - A/B within a single process: 5 runs with the feature enabled, 5 runs with a `JPERL_NO_PHASE_N=1` env var disabling it (each phase defines its disable switch).
   - `life_bitpacked` with `-r none -g 500` — median Mcells/s compared.
   - Full `COMPARE=perl BENCH_RUNS=3 dev/bench/run_baseline.sh` snapshot, `baseline-<sha>.md` captured in `dev/bench/results/`.
   - **Required:** median life_bitpacked improvement ≥ 2% AND no benchmark regresses > 3% compared to pre-phase baseline.
   - **If neither condition holds, revert.** Phase stays as "attempted, didn't pan out" in this doc.

3. **Comparison anchors (always measured):**
   - System perl
   - `feature/refcount-perf-combined` HEAD before this phase started
   - Current tip (after this phase)
   - Master (once per full measurement pass, as the long-term ceiling reference)

## Phase summary & dependencies

Phases are numbered by the order in which they should ship:

| # | Change | Expected gain | Effort | Depends on |
|---|---|---:|---|---|
| 1 | ~~FREETMPS-style compare gating `MortalList.flush`~~ — **REJECTED** 2026-04-18, upper-bound ~0.7% | n/a | low | — |
| 2 | ~~Consolidate 7 TL stacks → one `PerlContext` struct~~ — **REJECTED** 2026-04-18, pristine-stub upper bound 0% | n/a | medium | — |
| 2' | ~~Presize `RuntimeList` backing ArrayList~~ — **SUPERSEDED** by Phase R (see below) | n/a | low-medium | — |
| 3' | ~~Fast-path `getDefinedBoolean`~~ — **SUPERSEDED** by Phase R | n/a | low | — |
| 4' | ~~Fast-path `blessedId`~~ — **SUPERSEDED** by Phase R | n/a | low | — |
| **R** | **Per-object `needsCleanup` gate across all branch machinery** (the real fix) | **~67% (life_bitpacked), 14% (lexical-only)** | high | — |
| 5 | Final cleanup & doc sync | — | low | R |

**2026-04-18 update:** The `JPERL_CLASSIC=1` experiment (see `dev/design/classic_experiment_finding.md`) confirmed the cumulative-tax hypothesis. Disabling the branch's added machinery globally recovers 1.67× on life_bitpacked (essentially reaching pre-merge master) and 1.14× on a lexical-only bench. The master→branch gap is not one hotspot; it is ~a dozen small taxes that cannot be fixed piecewise.

The correct structural fix (Phase R) is: add a single `needsCleanup` bit to `RuntimeBase`, set only for objects that actually need DESTROY/weaken/walker tracking, and gate every added fast-path site on that bit. The CLASSIC experiment has already mapped out exactly which sites need the gate.

Phase 2'/3'/4' (the hotspot-driven candidates from `life_bitpacked_jfr_profile.md`) are superseded — those hotspots (`getDefinedBoolean`, `ArrayList.grow`, `blessedId`) are amplified by the same machinery and will get quieter automatically once Phase R is in.

Why this order:

- **Phase 1 is standalone** — no dependencies on the other phases, minimal risk, quick measurement. Serves as a sanity check that our measurement harness is sensitive enough to detect the expected-magnitude gains.
- **Phase 2 is the keystone.** Several of the later phases become cheaper once all the caller-context state lives in one struct behind one ThreadLocal (inline refcount helpers need this; array-backed stack needs this).
- **Phase 3 reuses Phase 2's struct** — the tiny inlinable refcount helpers live in or adjacent to `PerlContext`.
- **Phase 4 is the big structural lift** — do last when the surrounding state is simplified.
- **Phase 5** is the documentation sync + any cleanup of tombstone branches / temporary opt-out env vars.

Abort early if any phase fails its perf gate. We don't pile up speculative changes.

### Lessons from Phase 1 (apply to Phases 2-4)

Phase 1 was rejected on its upper-bound measurement (~0.7% vs 2% gate) — the cost model ("INVOKESTATIC dispatch is hot") was wrong because HotSpot inlined the empty-case fast path inside `flush()`. Conclusion:

**Every remaining phase MUST derisk with a profiler sample BEFORE implementation.** The per-phase workflow is now:

1. **Upper-bound experiment first.** Patch the minimum hack that would represent the phase's theoretical best case (even if broken/unsafe) and measure life_bitpacked + bench suite. If the upper bound is < 1.5× the required gate, reject the phase without implementation.
2. **If upper-bound passes:** implement cleanly, run correctness gates, measure again.
3. **If upper-bound fails:** document in this doc and move to next phase.

This saves ~days per rejected phase vs. a full implementation + revert cycle.

---

## Phase 1 — FREETMPS-style compare gating the flush

**Status: INVESTIGATED — REJECTED (2026-04-18)**

### Result

Upper-bound experiment on `perf/perl-parity-phase1` @ 3c2ca4b6a: patched `EmitStatement.java` to emit **zero** `INVOKESTATIC MortalList.flush` calls at scope exit (gated by `JPERL_DISABLE_FLUSH_EMIT=1`). This simulates the absolute best case the Phase 1 guard could achieve — a theoretical zero-cost flush skip.

`life_bitpacked -r none -g 500`, 5 runs each, median Mcells/s:

| Variant | Runs | Median |
|---|---|---|
| Baseline (flush emitted) | 8.93 / 8.77 / 8.80 / 8.81 / 8.78 | **8.80** |
| Upper bound (no flush emitted) | 8.95 / 8.86 / 8.90 / 8.86 / 8.76 | **8.86** |

Improvement: ~0.7%. Well below the ≥2% Phase 1 gate. Within noise on a single bench.

### Why this was wrong

`MortalList.flush()`'s first instruction is `if (!active || pending.isEmpty() || flushing) return;`. HotSpot C2 inlines static call targets ≤ 35 bytes after ~10k invocations, so the "empty case" path effectively becomes three GETSTATIC-IFEQ-like checks. There is no meaningful INVOKESTATIC dispatch cost to cut once inlining takes over.

The real cost driver on life_bitpacked is **somewhere else** — most likely the sub-call context (Phase 2) and/or refcount ops (Phase 3).

### Decision

Phase 1 is closed out. No code change shipped. Moving to Phase 2.

---

### Goal (original, kept for archival)

Make the common "no mortals to free" case at scope exit effectively free.

### Background

System perl:
```c
#define FREETMPS  if (PL_tmps_ix > PL_tmps_floor) free_tmps()
```

One compare, zero overhead when the tmp stack is empty.

PerlOnJava today emits at scope exit:
```
INVOKESTATIC MortalList.flush ()V
```

…unconditionally. `MortalList.flush()` itself checks for an empty stack as its first action, but the INVOKESTATIC dispatch cost (~5 ns) is paid regardless. Over millions of sub calls in a tight loop, measurable.

### Design

Add two `public static` int fields (or thin accessors) exposing `MortalList.tmpsIx` and `MortalList.tmpsFloor`. Emit:

```
GETSTATIC MortalList.tmpsIx  I
GETSTATIC MortalList.tmpsFloor  I
IF_ICMPLE  skip_flush
INVOKESTATIC MortalList.flush ()V
skip_flush:
```

~5 bytes of bytecode replaces 3 for the call, but saves the call dispatch when the stack is empty.

`MortalList.flush()` stays unchanged — we're bypassing its INVOKESTATIC in the common case, not changing its semantics.

### Risks

- Reading the int fields is a `GETSTATIC`, which is very cheap. No correctness concern.
- If the indices are not public yet, we either expose them or add cheap static accessor helpers that the JIT can inline (a `public static int tmpsAboveFloor() { return tmpsIx - tmpsFloor; }` would be cleanest).
- Concurrent modification: `MortalList` is a ThreadLocal, so the fields are per-thread. No visibility issues.

### Opt-out

Env var `JPERL_NO_PHASE1=1` set at `EmitterMethodCreator` load-time forces the unconditional `INVOKESTATIC MortalList.flush` emission. Lets us A/B the exact same binary.

### Correctness gates

- Full `make` run green (except destroy_eval_die.t#4 pre-existing)
- destroy_eval_die.t pass count unchanged
- DBIC txn_scope_guard.t 18/18
- tie_scalar.t 12/12
- Quick DESTROY smoke: `./jperl -e 'package T; sub new { bless {}, shift } sub DESTROY { $::d++ } package main; { my $x = T->new; } print $::d'` should print `1`

### Measurement gate

- life_bitpacked: 5 runs each branch, median improvement ≥ 2%
- `refcount_bless` / `refcount_anon`: no regression > 3%
- Full bench suite snapshot committed under `dev/bench/results/`

### Abort condition

If the median is < 2% improvement OR any correctness gate fails OR any non-life_bitpacked benchmark regresses > 3% compared to the pre-phase baseline, **revert the phase**. Document the finding in this doc under "Phase 1 results".

---

## Phase 2 — Consolidate ThreadLocal stacks into `PerlContext`

### Goal

Reduce per-sub-call ThreadLocal traffic from 7 separate `TL.get()` lookups to 1. Also eliminate the `HashMap<String, RuntimeScalar>` copy in `WarningBitsRegistry.pushCallerHintHash()` for the common empty-hint-hash case.

### Background

System perl pushes ONE `PERL_CONTEXT` struct per sub call. All caller metadata (CV, retop, savearray, old pad, warning bits, hints, etc.) is in that one struct. `cxstack` is a flat array of these structs; pushing is `cxstack[cxstack_ix++] = ...`.

PerlOnJava today has 7 separate ThreadLocal stacks:

1. `RuntimeCode.argsStack`
2. `RuntimeCode.pristineArgsStack`  (our branch)
3. `RuntimeCode.hasArgsStack`
4. `WarningBitsRegistry.currentBitsStack`
5. `WarningBitsRegistry.callerBitsStack`
6. `WarningBitsRegistry.callerHintsStack`
7. `HintHashRegistry.callerSnapshotIdStack`

Each push is: `ThreadLocal.get()` + `Deque.push(value)`. Seven times per sub call.

JFR confirms the cost: 4 extra `RuntimeList` + 4 extra `ArrayList` allocations per life_bitpacked generation vs master (ArrayDeque's internal bookkeeping spills into these allocations).

### Design

Introduce `class PerlContext` in `org.perlonjava.runtime.runtimetypes`. Fields:

```java
public final class PerlContext {
    // args stacks
    public RuntimeArray[] argsStack;       int argsIx;
    public List<RuntimeScalar>[] pristineArgsStack; int pristineIx;
    public boolean[] hasArgsStack;         int hasArgsIx;

    // caller context (one array of frame records)
    public CallerFrame[] callerFrames;     int callerFramesIx;

    // mortal / savestack state
    public int tmpsIx, tmpsFloor;
    // ...
}

public static final ThreadLocal<PerlContext> CTX =
    ThreadLocal.withInitial(PerlContext::new);
```

One `TL.get()` at sub entry, one at sub exit. All the pushes are field + array operations.

`CallerFrame` combines bits, hints, hintHashId into a single record.

Existing APIs (`getCallerBitsAtFrame`, `getCallerHintsAtFrame`, `getHasArgsAt`, `getPristineArgsAt`) read from the consolidated frames array.

The separate Registries stay as pure facades (their existing static methods delegate to the consolidated struct) so external callers don't break.

### Risks

- Touches many read sites. Needs thorough testing.
- Multi-phase migration: first add `PerlContext` alongside the existing stacks, make the registries read from both (prefer PerlContext), then remove the old stacks.
- Interpreter backend (`BytecodeInterpreter`) may have direct references to some of these stacks; must be updated.

### Opt-out

`JPERL_NO_PHASE2=1` at class-load time uses the old stacks. Adds a runtime branch on the flag in each push/pop, so we can A/B.

### Correctness gates

Same as Phase 1, plus:
- Run full DBIC test suite (`jcpan -t DBIx::Class`) — expect same pass count as pre-phase
- Run TT, Moo
- `make test-bundled-modules`

### Measurement gate

- life_bitpacked: 5 runs each, median improvement ≥ 4% over Phase 1 baseline
- No bench regresses > 3%
- Allocation profile: `RuntimeList` / `ArrayList` allocation rate cut by ≥ 50%

### Abort condition

If gains are < 4% OR allocation rate doesn't drop, the consolidation is not paying for its complexity — revert to just keeping the `PerlContext` as a stub for Phase 3's benefit.

---

## Phase 3 — Inline refcount helpers

### Goal

Make `refcnt_inc` / `refcnt_dec_or_free` tiny static methods (< 20 bytes) that the JIT always inlines, moving `ScalarRefRegistry.registerRef` / `MortalList.deferDecrement` to the cold path.

### Background

Perl's SvREFCNT_inc is `++sv->refcnt` (1 store). SvREFCNT_dec is 4 instructions in the hot path with `sv_free2` on the cold path only.

PerlOnJava's equivalent goes through `setLarge()` / `scopeExitCleanup` — methods that are 100-500 bytes and fail to inline under `-XX:+PrintInlining`.

### Design

Add to `RuntimeBase` (or a new `Refcnt` class):

```java
public static void refcntInc(RuntimeBase base) {
    if (base != null && base.refCount >= 0) {
        base.refCount++;
    }
}

public static void refcntDecOrFree(RuntimeBase base) {
    if (base != null && base.refCount > 1) {
        base.refCount--;
    } else if (base != null) {
        base.refCount--;
        refcntFreeColdPath(base);  // separate method, out of line
    }
}
```

Each helper body is < 20 bytes. JIT will inline eagerly at hot call sites.

Migrate call sites in the emitter: instead of emitting `INVOKESTATIC scopeExitCleanup`, emit `INVOKESTATIC refcntDecOrFree`. `scopeExitCleanup` stays for complex cases (IO owner, capture count).

### Risks

- Correctness: any case where the "hot path" needs to do more than decrement (IO owner unregister, weakref clearing, MortalList pending entry) must still route through the cold path.
- The `ScalarRefRegistry.registerRef` we currently do at assignment time may conflict — need to understand when it's truly needed.

### Opt-out

`JPERL_NO_PHASE3=1` — emit the old INVOKESTATICs.

### Correctness gates

Same as Phase 2, plus:
- Specific DESTROY-correctness tests: `unit/refcount/*.t` all pass
- DBIC's 52leaks test still passes

### Measurement gate

- `benchmark_refcount_bless` / `benchmark_refcount_anon`: median improvement ≥ 5% over Phase 2 baseline
- life_bitpacked: no regression (this phase isn't expected to help pure numeric loops)
- JIT inlining trace: `refcntDecOrFree (N bytes)` shows `inline (hot)` at hot call sites

### Abort condition

Correctness: any regression is an immediate revert (refcount bugs are silent and bad).
Perf: if benchmark_refcount_* doesn't improve ≥ 3%, the win isn't worth the complexity.

---

## Phase 4 — Array-backed value stack

### Goal

Match Perl's `PL_stack_sp` / `PL_stack_base` model. Per-thread `Object[] stack` + `int sp` replacing the current `ArrayDeque<RuntimeArray>` for args and similar per-call value passing.

### Background

Perl's value stack is a flat `SV**` array. `PUSHs(sv)` is `*PL_stack_sp++ = sv`. `PL_stack_sp` is kept in a register across pp function bodies. Push/pop is ~1 cycle.

PerlOnJava uses `ArrayDeque<RuntimeArray>`, which:
- Boxes primitives (e.g. `hasArgsStack` push `Boolean.FALSE`)
- Requires virtual dispatch on `push`/`pop`
- Does internal resizing

### Design

In `PerlContext` (from Phase 2), replace `ArrayDeque` fields with:

```java
public RuntimeArray[] argsStack = new RuntimeArray[256];
public int argsIx;

public void pushArgs(RuntimeArray a) {
    if (argsIx == argsStack.length) argsStack = grow(argsStack);
    argsStack[argsIx++] = a;
}
```

Same for `pristineArgsStack`, `callerFrames`.

Critical: the grow check must be in a hot-inlineable function. A `UNLIKELY(argsIx == argsStack.length)` branch is what Perl does via the `markstack_grow()` out-of-line call.

### Risks

- `pushArgs`/`popArgs` have to handle both JVM and interpreter backends consistently.
- `caller()` iterates the stack by index; indexed access is actually easier than before.
- **Biggest risk:** subtle ordering bugs when arrays resize. Write a thorough stress test with deeply nested subs.

### Opt-out

Phase 2's `PerlContext` supports both the ArrayDeque and array-backed versions behind a feature flag. Env var `JPERL_NO_PHASE4=1` selects ArrayDeque.

### Correctness gates

Same as Phase 3, plus:
- Stress test: 1000+ deeply nested sub calls, verify no corruption.
- Run with `-Xss128k` to ensure the stack growth logic works correctly.

### Measurement gate

- life_bitpacked: median improvement ≥ 5% over Phase 3 baseline
- benchmark_method: median improvement ≥ 5%
- Bench ratios overall trending toward 1.0× perl

### Abort condition

As phases before: any correctness failure or insufficient perf gain triggers revert.

---

## Phase 5 — Cleanup & documentation

### Goal

Once Phases 1-4 are stable and green, remove the opt-out env vars (they've served their purpose) and update `dev/design/next_steps.md` to reflect reality.

### Activities

- Remove `JPERL_NO_PHASE1`..`JPERL_NO_PHASE4` env var branches.
- Clean up any tombstone branches (`perf/perl-parity-phase-*`).
- Update `dev/design/next_steps.md` §0 tables with final numbers.
- Close-out PR #526's §0 if the numbers justify.

---

## Cumulative expected impact

Assuming each phase delivers its expected median:

| Phase | life_bitpacked Mcells/s | vs perl |
|---|---:|---:|
| Start (PR #526 + PR #533) | 8.5 | 2.49× slower |
| After Phase 1 (+3%) | 8.8 | 2.41× slower |
| After Phase 2 (+7%) | 9.4 | 2.25× slower |
| After Phase 3 (+5% on method-heavy; pure-numeric ~unchanged) | 9.5 | 2.23× slower |
| After Phase 4 (+10%) | 10.4 | 2.04× slower |
| perl reference | 21.2 | 1.00× |

This gets us to roughly **2× perl** on life_bitpacked — significant progress but still not parity. Closing the last 2× is beyond the scope of this plan; it's the RuntimeScalar-boxing + Java-dispatch tax that would require value types / escape-analysis improvements beyond what the current JVM can offer.

For `benchmark_refcount_bless` (currently 6.6× perl), the expected trajectory is more favorable:

| Phase | benchmark_refcount_bless ratio |
|---|---:|
| Start | 6.6× perl |
| After Phase 1 | 6.4× |
| After Phase 2 | 5.8× |
| After Phase 3 | ~4.5× |
| After Phase 4 | ~3.8× |

Still more than 2× perl, reflecting that DESTROY/bless semantics need runtime machinery that C-perl embeds directly in SV.

## Progress tracking

### Phase 1 — in progress

[TO BE FILLED IN DURING EXECUTION]

### Phase 2 — pending

### Phase 3 — pending

### Phase 4 — pending

### Phase 5 — pending
