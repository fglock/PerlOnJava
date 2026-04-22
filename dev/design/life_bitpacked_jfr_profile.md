# life_bitpacked JFR profile — what's actually hot

**Captured:** 2026-04-18 on `perf/perl-parity-phase1` @ 3c2ca4b6a
**Workload:** `./jperl examples/life_bitpacked.pl -r none -g 20000` (17.2s wall, 14.85 Mcells/s)
**Profile:** 60s JFR with `settings=profile`, ~477 ExecutionSample events, file `dev/bench/results/jfr/life_bp_long.jfr`

## Why this profile exists

Phase 1 of `perl_parity_plan.md` was rejected on its upper-bound measurement (~0.7% vs 2% gate). The conclusion was that our a-priori cost model (which assumed INVOKESTATIC dispatch of `MortalList.flush` was hot) was wrong — HotSpot had already inlined the empty-case fast path. Before committing to Phase 2's large sub-call-context refactor, we profiled first.

Phase 2 pristine-args stub experiment also showed zero improvement (same median within noise). So **the 7 ThreadLocal<Deque> sub-call stacks are NOT the bottleneck either** — the JIT handles them well.

## Hot methods (top-of-stack self-time)

| Method | Samples | % |
|---|---:|---:|
| `RuntimeScalar.getDefinedBoolean()` | 74 | **~15%** |
| `anon230.apply` (user sub body) | 70 | 14% |
| `java.util.Arrays.copyOf` (ArrayList growth) | 70 | 14% |
| `RuntimeScalarType.blessedId(RuntimeScalar)` | 55 | 11% |
| `RuntimeScalar.set(RuntimeScalar)` | 38 | 8% |
| `RuntimeList.setFromList(RuntimeList)` | 27 | 5% |
| `RuntimeScalarCache.getScalarInt(int)` | 20 | 4% |
| `RuntimeControlFlowRegistry.checkLoopAndGetAction(String)` | 12 | 2% |
| `RuntimeScalar.scopeExitCleanup` | 6 | 1% |
| `MortalList.flush` | 5 | 1% |

The user's bitwise ops (`bitwiseAnd`/`Xor`/`Or`/`shiftLeft`/`shiftRight`) together amount to ~**30 samples = 6%** — tiny compared to the dispatch/allocation overhead.

## Key insights

### 1. `getDefinedBoolean()` is the #1 self-time hit

15% of CPU is spent deciding whether a scalar is defined. This is hit heavily by things like `if ($x)` boolean truth tests, `defined($x)` guards, and `||` / `//` expressions. Any simplification (e.g., marking cached common scalars as "always defined" and short-circuiting) would pay out immediately.

### 2. ArrayList growth is the #2 self-time hit

14% of CPU is spent in `Arrays.copyOf` for ArrayList growth. Stack traces show the callers are:
- `RuntimeList.add(RuntimeBase)` — return value list building in `RuntimeCode.apply`
- `RuntimeList.add(RuntimeScalar)` — user sub assembling its return list

**This means every sub call allocates a small ArrayList that immediately grows.** Presizing or pooling could save ~14%.

### 3. `blessedId` is 11%

`RuntimeScalarType.blessedId(RuntimeScalar)` is hit 55 times. This is the per-method-call class dispatch path. On life_bitpacked there are no blessed objects in the hot path, so this is checking whether a scalar is blessed on every op that might use overloading. A fast-path for "not blessed" could matter.

### 4. `MortalList.flush` is irrelevant (1%)

Confirms Phase 1's rejection — `flush` is barely on the profile.

### 5. ThreadLocal overhead is invisible

No `ThreadLocal.get()` or `ArrayDeque.push/pop` in the hot list. JIT already inlines these. **Phase 2 of the original plan (consolidate 7 TL stacks) would not help life_bitpacked.**

## Revised candidate phases

| Phase | Hypothesis | Upper bound estimate | First test |
|---|---|---|---|
| 2' | Presize `RuntimeList` backing ArrayList to avoid grow-from-10 | **5-10%** (14% ceiling) | Change `RuntimeList`'s initial `new ArrayList<>()` to `new ArrayList<>(8)` or similar; A/B |
| 3' | Fast-path `getDefinedBoolean` for `RuntimeScalarReadOnly` / integer types | **3-5%** (15% ceiling) | Add explicit override on cached scalar types; A/B |
| 4' | Fast-path `blessedId` for non-blessed scalars | **2-4%** (11% ceiling) | Inline `blessed == null` check; A/B |

Any single one of these has a higher upper bound than Phase 1 or original Phase 2 ever could. They should each be derisked with a minimal patch + measurement before committing to implementation, same gating as Phase 1.

## What to do next

1. **Retire original Phase 2** (TL consolidation) in `perl_parity_plan.md`.
2. **Adopt Phase 2'** (RuntimeList presize) as the new Phase 2 candidate.
3. **Measurement-first rule still applies** — start every phase with a minimal hack + 5-run median; if it doesn't move the needle by 2%+, reject.

## Files

- `dev/bench/results/jfr/life_bp_long.jfr` — raw JFR, reproducible with `JPERL_OPTS="-XX:+FlightRecorder -XX:StartFlightRecording=duration=60s,filename=...,settings=profile" ./jperl examples/life_bitpacked.pl -r none -g 20000`
- `/tmp/jfr_exec.txt` — textual dump of `jdk.ExecutionSample` events (not committed; regenerate with `jfr print --events jdk.ExecutionSample ...jfr`)
