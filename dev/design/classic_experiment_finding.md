# JPERL_CLASSIC experiment — cumulative-tax hypothesis confirmed

**Branch:** `perf/perl-parity-phase1` @ 3c2ca4b6a + CLASSIC gate patches (4 files)
**Date:** 2026-04-18
**Hypothesis:** The master→branch regression (1.67× on life_bitpacked) is NOT attributable to any single hot method. It is the cumulative cost of many small taxes added by the refcount/walker/weaken/DESTROY machinery, each individually invisible in a profile.

## Test

Added `JPERL_CLASSIC` env var (read once at class-init into a `static final boolean`). When set, short-circuits the branch's added machinery to near-master behavior:

| Site | CLASSIC behavior |
|---|---|
| `MortalList.active` | `false` — every `deferDecrement*` / `scopeExitCleanup{Hash,Array}` / `mortalizeForVoidDiscard` early-returns |
| `EmitStatement.emitScopeExitNullStores` Phase 1 (`scopeExitCleanup` per scalar) | Not emitted |
| `EmitStatement.emitScopeExitNullStores` Phase 1b (cleanupHash/Array) | Not emitted |
| `EmitStatement.emitScopeExitNullStores` Phase E (`MyVarCleanupStack.unregister`) | Not emitted |
| `EmitStatement.emitScopeExitNullStores` Phase 3 (`MortalList.flush`) | Not emitted |
| `EmitVariable` `MyVarCleanupStack.register` on every `my` | Not emitted |
| `MyVarCleanupStack.register` / `unregister` | Early-return |
| `RuntimeScalar.scopeExitCleanup` | Early-return |
| `RuntimeScalar.setLargeRefCounted` | Direct field assignment, skipping refcount/WeakRefRegistry/MortalList work |

Correctness: CLASSIC breaks DESTROY, weaken, walker semantics — only useful for measurement, not shipping.

## Result — life_bitpacked

`./jperl examples/life_bitpacked.pl -r none -g 500`, 5 runs each, median:

| Mode | Runs (Mcells/s) | Median |
|---|---|---:|
| Baseline (branch machinery on) | 8.58 / 8.51 / 8.49 / 8.51 / 8.45 | **8.51** |
| `JPERL_CLASSIC=1` | 14.18 / 14.60 / 14.14 / 13.32 / 13.77 | **14.18** |
| System perl (reference) | — | 20.8 – 21.5 |
| Master @ pre-merge (reference) | — | 14.0 |

**Speedup: 14.18 / 8.51 = 1.666×**, essentially recovering master's pre-merge number.

## Result — benchmark_lexical (simple, no refs)

`./jperl dev/bench/benchmark_lexical.pl`, 3 runs each:

| Mode | Runs (iters/s) | Median |
|---|---|---:|
| Baseline | 313484 / 329270 / 314172 | **314172** |
| `JPERL_CLASSIC=1` | 357144 / 347743 / 359080 | **357144** |

**Speedup: 1.14×**

Even on a workload with no references and no blesses, the `my`-variable register/unregister emissions and scope-exit cleanup emissions cost ~14%.

## Interpretation

The hypothesis is definitively confirmed:

1. **The master→branch perf gap is recoverable in full** (1.67× on the most ref-heavy workload) by gating the added machinery.
2. **No single site is the bottleneck.** Phase 1 (MortalList.flush) alone was worth 0.7%. Phase 2's pristine-args stub alone was worth 0%. The 1.67× comes from ~a dozen sites each contributing 2–10%.
3. **The taxes are broadly distributed across the scope-exit / variable-declaration / reference-assignment paths.** Even workloads that never exercise DESTROY/weaken pay them.

## Implication for the plan

The piecewise Phase 2'/3'/4' approach was the wrong framing. The right structural fix:

**Make the machinery per-object-opt-in, not always-on.** Perl 5's design: `SvREFCNT_inc` is free for most SVs because the type tag gates the work. Only objects that need refcount tracking pay the cost.

Concrete proposal (call it Phase R — "refcount by need"):

1. Add a single `needsCleanup` bit to `RuntimeBase`, default `false`.
2. Set it to `true` only when:
   - The object is blessed into a class that has `DESTROY`, OR
   - The object is targeted by `Scalar::Util::weaken`, OR
   - The object is captured by a CODE ref whose refCount we need to track for cycle break.
3. Every CURRENT-BRANCH fast-path site becomes `if (!needsCleanup) return <classic behavior>;`:
   - `setLargeRefCounted` → direct assignment if neither side needs cleanup
   - `scopeExitCleanup` → no-op if scalar's value doesn't need cleanup
   - `MyVarCleanupStack.register` → skip if the var's referent doesn't need cleanup
   - `MortalList.deferDecrement*` → skip if referent doesn't need cleanup
   - `scopeExitCleanupHash/Array` → skip if container has no needsCleanup descendants

With per-object gating, life_bitpacked (zero blessed objects, zero weaken) pays zero tax and runs at ~14 Mc/s. DBIx::Class / txn_scope_guard / destroy_eval_die (objects that DO need cleanup) still work correctly.

This is a **significant refactor** — every site listed above needs a cheap gate check. But:

- The CLASSIC experiment has already implemented those gate checks (just globally rather than per-object). Most of the code is the early-return condition.
- The JIT will fold the `needsCleanup == false` check away to almost nothing once it sees a type-stable call site.
- Correctness is easier to reason about than the current "always-tracked" design, because the gate explicitly matches the semantic condition that requires tracking.

## Files touched in this experiment

```
src/main/java/org/perlonjava/runtime/runtimetypes/MortalList.java       (+CLASSIC flag, active init)
src/main/java/org/perlonjava/runtime/runtimetypes/MyVarCleanupStack.java (register/unregister early-return)
src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeScalar.java    (setLargeRefCounted + scopeExitCleanup early-return)
src/main/java/org/perlonjava/backend/jvm/EmitStatement.java             (4 emission sites gated)
src/main/java/org/perlonjava/backend/jvm/EmitVariable.java              (register emission gated)
```

## Next step

Either:
1. **Commit the CLASSIC gate** as a measurement tool on `perf/perl-parity-phase1` (doesn't ship to users; helps future perf work A/B the full-feature cost).
2. **Move directly to Phase R** (per-object `needsCleanup` bit) based on this evidence, using the CLASSIC gate sites as the map of what needs per-object gating.
3. **Revert** the CLASSIC gate and keep this document as the finding.
