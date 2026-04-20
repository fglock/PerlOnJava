# Phase J — Performance Investigation & Optimization

**Status:** In progress
**Branch:** `feature/phase-j-performance`
**Entry point:** full pass of `./jcpan -t DBIx::Class` (13804 tests) + TT (2920) + Moo (841) in ~1400s wallclock, heavy refcount / MortalList churn after Phases A–I.

## Guiding principles

> Apply the best technology for each hypothesis; investigate performance thoroughly.

No blind optimization. Each change is motivated by a measurement and
validated by a second measurement. The hypothesis dictates the tool.

| Hypothesis shape | Best tool | Output |
|---|---|---|
| "Which Java methods burn the most CPU during `./jcpan -t DBIC`?" | **JFR** execution sampling | Aggregated hot method list |
| "What allocates the most bytes per DBIC test?" | **JFR** `jdk.ObjectAllocationSample` + `async-profiler -e alloc` | Alloc flamegraph |
| "Do two implementations of refcount incref differ by X ns?" | **JMH** microbench (`src/jmh/`) | ns/op, error bars, JIT-stable |
| "How many MortalList flushes fire per DBIC test run?" | **Java counter + `jfr print`** of custom event | Per-test tallies |
| "Is the reachability walker's fan-out O(N) or O(N²)?" | **Custom Java AtomicLong counters** + `-verbose:gc` | Visits/call ratio |
| "What's the wall-clock overhead of the suppressFlush bytecode we added?" | **`Benchmark.pm`** on dev/bench scripts | perl-visible ns/op |
| "Does a given commit regress startup?" | `time ./jperl -e 1` × 20 (median) | ms |

`profile-perlonjava` skill already documents JFR basics.
This plan extends to JMH, async-profiler, and custom counters.

## Benchmark corpus

### Existing (`dev/bench/`)
- `benchmark_method.pl` — method dispatch
- `benchmark_closure.pl` — closure capture
- `benchmark_lexical.pl` — `my` vars
- `benchmark_global.pl` — package vars
- `benchmark_regex.pl` — pattern compile + match
- `benchmark_string.pl` — string ops
- `benchmark_eval_string.pl` — `eval STRING`
- `benchmark_memory.pl`, `benchmark_memory_delta.pl` — RSS

### New (to be added this phase)
- `benchmark_refcount_anon.pl` — anon array/hash literal churn (stresses the
  suppressFlush bytecode we just added).
- `benchmark_refcount_bless.pl` — `bless`/`DESTROY` pair rate (stresses
  MortalList deferDecrement / flush).
- `benchmark_refcount_cycle.pl` — `weaken` + cyclic `DESTROY` (stresses
  ReachabilityWalker).
- `benchmark_method_chain.pl` — `$x->a->b->c(@args)` (stresses the
  setFromList fast-path + arg-passing we just touched).
- `benchmark_real_world.pl` — a small Moo object graph + DBI-like usage
  pattern lifted from DBIC's faster test files.

### Macro runs (wallclock only, for regression gates)
- `./jcpan -t DBIx::Class` — target: beat 1145s
- `./jcpan -t Template` — target: beat 135s
- `./jcpan -t Moo` — target: beat 91s

## Tooling to add (one-time)

1. **`dev/bench/run_baseline.sh`** — runs every `benchmark_*.pl` 5×, writes a
   json blob to `dev/bench/results/<git-sha>.json`.
2. **`dev/bench/compare.pl`** — diff two baseline JSONs, flag regressions >5%.
3. **JMH harness** under `src/jmh/java/org/perlonjava/bench/` — microbenchmarks
   for `MortalList.flush`, `RuntimeScalar.setLargeRefCounted`, walker
   `findPathTo`, etc. Wired into Gradle as `./gradlew jmh`.
4. **JFR helper** — `jperl --jfr=<file>` CLI flag that adds
   `-XX:StartFlightRecording=filename=<file>,settings=profile` to the forked
   JVM. (The skill currently tells users to write the full `java` command.)
5. **Refcount counters** — optional `JPERL_REFCOUNT_STATS=1` that has
   `MortalList`, `ReachabilityWalker`, `WeakRefRegistry` emit
   `jdk.Counter`-style JFR events, so a single JFR file can answer
   "how many refcount ops did this script do?"

## Investigation queue (ordered by expected payoff)

### J1 — Baseline the corpus
Capture current numbers for every script above. Check them in under
`dev/bench/results/baseline-<sha>.json`. Nothing is "slow" until we've
compared against this.

### J2 — `suppressFlush` bytecode overhead
Our last two fixes (`f8a89abaa`, `6d37287f1`) inject Java calls in every
array/hash literal emission. Measure with `benchmark_refcount_anon.pl`
vs. a pre-phase-I commit, and with JMH on a synthetic `[a,b,c]` loop.
If overhead > 1%, consider:
- Skip emit when AST proves no element can allocate a blessed temp.
- Merge with an existing suppress on the caller's stack (counted guard).
- Emit `flushing=true` directly via a synthetic `SpillRef` holding the
  old value instead of through `suppressFlush` method calls.

### J3 — Walker fan-out
`ReachabilityWalker.sweepWeakRefs` and `findPathTo` both run BFS. On
52leaks.t they walk thousands of objects per sweep. Counter-instrument
them; if a handful of callers dominate, adding a closed-world fast path
(module-init only walks this-module's refs) could cut auto-sweep cost
significantly. JFR alloc profile on 52leaks.t will confirm whether the
walker is allocating its ArrayDeque/IdentityHashMap in the hot path.

### J4 — `MortalList.flush` hot-path
This fires on almost every reference assignment. Current impl:
- Boolean check, ArrayList iteration, per-element refcount read + cmov +
  branch + optional DESTROY dispatch.
JMH bench the empty-pending and single-blessed cases. If the empty case
costs more than ~3 ns, inline the `pending.isEmpty()` check at callsites
or hoist the `flushing` flag into a thread-local monotonic counter.

### J5 — `setLargeRefCounted` fast paths
The "both untracked" fast path at line 917–929 already handles most
`\$x`-style assignments. JFR hot-path sampling on real DBIC workload
will tell us which `setLarge*` branch dominates; we may need a
type-dispatch table rather than cascaded `if`s.

### J6 — Boolean boxing in the new suppressFlush emit
Our EmitLiteral.java code boxes a Boolean into a spill slot. JIT likely
eliminates this, but if `SpillRef` is Object-typed that's a Boxed write
the JIT can't always prove away. If JMH microbench shows > 1 ns/literal
from boxing, add a primitive-boolean spill slot type.

### J7 — Startup time
`time ./jperl -e 1` — baseline this; compare to `pr328-startup-performance.md`.
If we've regressed, likely candidates are ScalarRefRegistry init,
ModuleInitGuard setup, or eager Phase-I walker data structures.

## Out of scope for Phase J

- Rewriting the interpreter (see `dev/design/interpreter.md` / `interpreter_benchmarks.md`).
- Class pooling / method handle caching (see `dev/design/optimization_codegen.md`).
- Switch from switch-based dispatch (already benchmarked, Phase-F).

These are tracked as future phases.

## Deliverables

1. `dev/bench/results/baseline-<sha>.json` committed.
2. Four new refcount-focused benchmarks in `dev/bench/`.
3. `dev/bench/run_baseline.sh` + `compare.pl` helpers.
4. At least one JMH microbench added for the hottest method found in J1.
5. `jperl --jfr` CLI helper.
6. Per-hypothesis findings written inline below, with commit SHA and
   before/after numbers.

## Progress Tracking

### Current status
Phase-J design merged. Awaiting baseline capture (J1).

### Next steps
1. Add `dev/bench/run_baseline.sh` and run it on master + current branch.
2. Add `benchmark_refcount_anon.pl` and `benchmark_refcount_bless.pl`.
3. Capture first JFR against `./jcpan -t DBIx::Class`.

### Related docs
- `dev/design/optimization.md` — static wish-list (predates this plan).
- `dev/design/optimization_codegen.md` — codegen-side ideas (out of scope).
- `dev/design/interpreter_benchmarks.md` — interpreter backend (out of scope).
- `dev/design/pr328-startup-performance.md` — startup-specific perf work.
- `.cognition/skills/profile-perlonjava/SKILL.md` — existing JFR how-to,
  will be extended with JMH/async-profiler notes as we go.
