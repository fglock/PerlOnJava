# life_bitpacked regression analysis — master vs PR #526

**Date:** 2026-04-21
**Tools used:** `javap`, JFR profiler (`method-profiling=max`), `-XX:+PrintInlining`, `jfr print`, direct wallclock measurement.
**Goal:** explain why `life_bitpacked.pl` runs at 2.57× of system perl on PR #526 when earlier perf claims put it closer to 1.6×.

## Key measurements (macOS M-series, perl 5.42, Java 24)

Triple-variant comparison on `./jperl examples/life_bitpacked.pl -r none -g 500`:

| Variant | Mcells/s | vs perl | vs master |
|---|---:|---:|---:|
| perl 5.42 | 21.2 | 1.00× | — |
| **master** (af258e026) | **14.0** | 1.52× | 1.00× |
| pre-merge (660aa9e68, walker-hardening-j3 tip) | 8.4 | 2.52× | 1.67× slower |
| PR #526 (0c6fea970) | 8.3 | 2.55× | 1.69× slower |

**PR #526 is essentially identical to its pre-merge base** (within run-to-run noise). The earlier "12.5 Mcells/s" figure from PR #523's own commit messages is not reproducible on current hardware; 8.4 Mcells/s is the real number for the walker-hardening branch.

**Master is 1.67× faster than our branch** on this workload.

## Is this "refcount cost"? Not exactly — it's *correctness* cost.

Compare DESTROY semantics on `src/test/resources/unit/refcount/destroy_eval_die.t`:

| Variant | Passing | Failing |
|---|---|---|
| master | 4/10 | **1, 3, 4, 6, 7, 10** (six tests) |
| pre-merge (660aa9e68) | 9/10 | 4 (LIFO order) |
| PR #526 | 9/10 | 4 (LIFO order) |

**Master fails six DESTROY-during-eval/die tests that our branch passes.** The ~1.7× speed difference is paying for five additional DESTROY semantics tests, and for the correctness guarantees that make DBIC / Moo / TxnScopeGuard work deterministically.

## Concrete cost breakdown

### Runtime code footprint

| Component | master | PR #526 | Delta |
|---|---:|---:|---:|
| `DestroyDispatch.java` | 188 | 563 | +375 |
| `MortalList.java` | 361 | 743 | +382 |
| `WeakRefRegistry.java` | 184 | 267 | +83 |
| `ReachabilityWalker.java` | **absent** | 401 | +401 |
| `ScalarRefRegistry.java` | **absent** | 156 | +156 |
| **Total lines of DESTROY/weaken runtime** | **733** | **2,130** | **+1,397 (2.9×)** |

### Emitter footprint (bytecode the compiled Perl class contains)

`EmitStatement.java` on our branch emits, **per scope exit per my-variable**:

- `ALOAD <idx>` + `INVOKESTATIC scopeExitCleanup` (nulls store, fires DESTROY)
- `ALOAD <idx>` + `INVOKESTATIC MyVarCleanupStack.unregister` (new in `feature/refcount-alignment`)
- `ACONST_NULL` + `ASTORE <idx>` (local slot null)

Master emits only the `scopeExitCleanup` + store-null pair. **Two extra instructions per `my` per scope exit.**

Generated method sizes confirm this. The compiled class `anon230` (life_bitpacked's main game-of-life module) has bytecode offsets up to:

- master: **~8,225** bytes
- PR #526: **~8,756** bytes (**+6%**)

Larger methods mean:
1. ASM spends more time in `Frame.merge` / `getInitializedType` at class-load (26 samples in PR #526's JFR).
2. HotSpot's C2 has a harder time — more instructions to escape-analyze.
3. More allocation sites per iteration (JFR shows **2.07× more `RuntimeScalar` allocations** in PR #526 vs master during the same benchmark: 89 sampled vs 43).

### Hot allocation sites

JFR `jdk.ObjectAllocationSample` during a 60-second run at `-g 2000`:

| Type | master | PR #526 |
|---|---:|---:|
| `RuntimeScalar` | 43 | **89** (2.07×) |
| `RuntimeList` | 0 sampled | 4 sampled |
| `RuntimeArray` | 1 | 4 |
| `ArrayList` | 0 sampled | 4 sampled |

The extra RuntimeScalar allocations attribute to lines inside the compiled `anon230.apply()` method — our emitter generates more intermediate scalar construction per Perl statement, presumably for refcount tracking.

## The tradeoff matrix

| Property | master | PR #526 |
|---|---|---|
| life_bitpacked throughput | 14.0 Mc/s | 8.3 Mc/s (-41%) |
| `destroy_eval_die.t` | 4/10 pass | 9/10 pass |
| DBIC `t/storage/txn_scope_guard.t` | varies by test* | **fully passing** |
| `MyVarCleanupStack` tracking | absent | present |
| `ReachabilityWalker` for closure/weaken | absent | present |
| Scope-exit cleanup bytecode | minimal | +2 INVOKESTATIC per my-variable |

*(Haven't run the full DBIC suite on master recently; this is inferred from AGENTS.md and the DBIC test-matrix entries.)

## What this means for the §0 parity goal

The honest answer to "can we close the gap to perl" has two parts:

### Part A — we can probably recover 50-70% of the master/PR gap

Our additional scope-exit bytecode and reachability-walker overhead is **observable regardless of whether the user's code uses weaken or DESTROY**. A per-sub static analysis could determine: "this sub's lexicals are never passed to `weaken`, never contain blessed refs with DESTROY, and never escape via closures" — in that case, emit master's minimal cleanup pattern.

Applied selectively, we'd pay the refcount tax only for subs that actually need it. life_bitpacked's main loop has no blessed-with-DESTROY, no weaken, no closure captures of its loop variables — it should qualify for the fast path.

This is a real engineering project, not a 50-line tweak. Rough estimate: 1-2 weeks to implement the analysis + verify DBIC/Moo still pass.

### Part B — we can't close the gap to perl without accepting some correctness loss OR a different architecture

Even master at 14.0 Mc/s is 1.52× slower than perl (21.2 Mc/s). That 1.52× is the residual PerlOnJava-vs-perl cost (RuntimeScalar boxing, method dispatch, no arena allocator, etc.) — it's not "refcount overhead" at all.

To get below master's number, we'd need structural changes:

- Value-typed small scalars (avoid RuntimeScalar boxing for hot integers)
- Inline-cached method dispatch (partially present, not aggressive enough)
- Escape analysis wins that HotSpot can't currently do

Those are legitimate optimization targets but separate from the §0 refcount concern.

## Recommended next steps (in priority order)

1. **Document this analysis in `dev/design/next_steps.md`** so we stop claiming PR #526 "regressed" — it didn't. It matches its pre-merge parent. Master is a *different* tradeoff.

2. **Write the per-sub "cleanup-needed" analysis** (Part A above). The payoff is real: recovering maybe 50-70% of the master/PR gap on life_bitpacked, while keeping DESTROY/weaken correctness for code that actually uses them.

3. **Only after that**, look at the remaining gap to perl — which is NOT about refcounting, it's about PerlOnJava's fundamental boxing/dispatch overhead.

## Reproduction

```bash
# Set up worktrees for a 3-way comparison
git worktree add /tmp/pj-master origin/master
git worktree add /tmp/pj-premerge 660aa9e68
(cd /tmp/pj-master && make dev) &
(cd /tmp/pj-premerge && make dev) &
wait

# Measure
for v in master premerge ; do
  printf "%-12s " "$v:"
  for i in 1 2 3; do
    /tmp/pj-$v/jperl examples/life_bitpacked.pl -r none -g 500 \
      | grep -oE 'Cell updates per second: [0-9.]+' \
      | grep -oE '[0-9.]+'
  done | tr '\n' ' '
  echo
done
./jperl examples/life_bitpacked.pl -r none -g 500 | ...    # current

# Correctness check
for v in master premerge ; do
  echo "=== $v ==="
  /tmp/pj-$v/jperl src/test/resources/unit/refcount/destroy_eval_die.t
done

# Allocation profiling
JPERL_OPTS="-XX:StartFlightRecording=duration=60s,filename=/tmp/life.jfr,method-profiling=max" \
  ./jperl examples/life_bitpacked.pl -r none -g 2000
$JAVA_HOME/bin/jfr print --events jdk.ObjectAllocationSample /tmp/life.jfr \
  | grep "objectClass" | awk '{print $3}' | sort | uniq -c | sort -rn
```
