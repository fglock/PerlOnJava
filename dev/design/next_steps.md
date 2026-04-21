# Post-PR-#526 Plan

**Status:** Proposal
**Supersedes:** `dev/design/archive/{phase_j_performance_plan, refcount_alignment_plan, refcount_alignment_52leaks_plan, refcount_alignment_progress, pr526_merge_validation}.md`
**Entry state:** `feature/refcount-perf-combined` (PR #526, commit `c06a554ac`) — master merged + perf stack (Phase-J + walker hardening + refcount) landed. All unit tests pass except one pre-existing failure. DBIx::Class, Template, Moo, Date::Calc, Math::Base::Convert, and Memoize all pass at expected levels.

> ⚠️ **Known shipped regressions (perf).** Several `dev/bench/`
> benchmarks run slower than system `perl` — worst is
> `benchmark_refcount_anon` at **3.94× perl** and
> `examples/life_bitpacked.pl` at **3.15× perl** (regressed from
> ~1.66× pre-merge). Correctness was prioritised for the merge.
> **See §0 — achieving parity with system perl is the top priority
> after PR #526 lands.**

## Why this doc exists

The work tracked in the archived plans is complete or subsumed:

| Archived plan | Outcome |
|---|---|
| `refcount_alignment_plan.md` | All 7 phases landed on master via PRs #508/earlier. |
| `refcount_alignment_52leaks_plan.md` | DBIC 52leaks.t fixed; `txn_scope_guard` passes. |
| `refcount_alignment_progress.md` | Sandbox tracker; superseded by `dev/sandbox/destroy_weaken/` results. |
| `phase_j_performance_plan.md` | J1/J2/J4 landed; J3 blocked, documented. |
| `pr526_merge_validation.md` | PR #526 merge complete; test matrix recorded. |

This doc collects the **concrete next steps** surfaced during that work, prioritized so a future Devin (or a human) can pick one up without re-reading the history.

---

## 0. TOP PRIORITY — performance: parity with system Perl [L, critical]

**Goal.** Every benchmark in `dev/bench/` should run at **≤ 1.0× the
wallclock time of system `perl`** (i.e. PerlOnJava at least as fast
as CPython… er, as `perl`). We're currently below parity on several
workloads, and the `life_bitpacked` example regressed sharply after
the master merge.

**Current measurements (PR #526 tip, 2026-04-21, system perl
5.42.0).**

| Benchmark | `jperl` | `perl` | ratio (jperl / perl) | parity? |
|---|---:|---:|---:|---|
| `benchmark_lexical` | 4.37 s | 10.64 s | **0.41×** | ✅ 2.4× faster |
| `benchmark_string`  | 4.29 s | 7.04 s  | **0.61×** | ✅ 1.6× faster |
| `benchmark_closure` | 8.62 s | 7.99 s  | 1.08×     | ≈ parity |
| `benchmark_method`  | 2.61 s | 1.50 s  | 1.73×     | ❌ 1.7× slower |
| `benchmark_refcount_anon` | 1.84 s | 0.47 s | **3.94×** | ❌ **worst — 4× slower** |
| `examples/life_bitpacked.pl` | 6.6 Mcells/s | 20.8 Mcells/s | **3.15×** | ❌ 3× slower (regressed from 12.5 Mcells/s pre-merge) |

Measured with `./jperl … -r none -g 500` for Life and the raw
benchmark scripts otherwise. See `dev/bench/run_baseline.sh` for the
harness (`COMPARE=perl dev/bench/run_baseline.sh` was intended to
emit side-by-side results; currently the script only records `jperl`
times, so **fixing the harness to emit `perl` times too is the first
task**).

**Status.** We shipped PR #526 with these numbers knowingly — the
merge prioritised correctness. Recovering them (especially the
`life_bitpacked` regression and `refcount_anon`) is the top priority
after PR #526 lands.

### 0.1 Fix the bench harness first [S]

- Implement the `COMPARE=perl` mode in `dev/bench/run_baseline.sh`
  so each baseline JSON contains both jperl and system-perl wallclock
  times plus the ratio. Make the side-by-side table the canonical
  output so regressions are obvious at review time.
- Add a baseline for system perl that doesn't change as jperl
  evolves (record once per major perl version); keep in
  `dev/bench/results/baseline-perl-<version>.json`.
- Add `life_bitpacked` (with `-r none -g 500`) to the baseline script
  so the regression we just flagged is tracked automatically.

### 0.2 life_bitpacked regression (cumulative across master's 143 commits) [L]

**Numbers.**

| State | Mcells/s | vs perl |
|---|---:|---:|
| Pre-master-merge (walker-hardening-j3 tip `660aa9e68`) | ~12.5 | 1.66× slower |
| Current (PR #526 head) | ~6.6 | 3.15× slower |
| System perl | ~20.8 | baseline |

**Why bisect doesn't work.** The regression is the cumulative cost of
~143 master commits. No single commit on PR #526's own history is
large enough to attribute the delta — `git bisect` on PR-#526 commits
gives no signal. Rely on profiling and analysis instead (see 0.4).

### 0.3 benchmark_refcount_anon — the worst gap [L]

4× slower than system perl — this is anon-subroutine creation +
refcount traffic, which our walker and cleanup-stack machinery
was supposed to make cheap. Pre-merge HEAD was comparable, so
some of the same master hot-paths hitting `life_bitpacked` likely
also hit this. Fix together with 0.2.

**Downstream impact — DBIC user reports:**

1. **`t/zzzzzzz_perl_perf_bug.t`** fails with exit 11. This is
   DBIC's perl-installation benchmark: it compares
   blessed-ref operations vs plain array-ref operations and
   fails if the ratio is `>= 3×`. PerlOnJava currently hits
   roughly 4× — the same gap as `benchmark_refcount_anon`.
   Fixing §0.3 will unblock this test automatically. No DBIC
   feature is broken; it's a perf-ratio diagnostic.

2. **`t/discard_changes_in_DESTROY.t`** times out under the
   default parallel harness (user report: 300s timeout). Reproduced
   locally with `jprove -j4` on DBIC — the batch hangs past 400s.
   Standalone (single jperl) the test passes cleanly in ~14s, 5
   consecutive runs, exit 0.

   It's not infinite recursion — the `DESTROY → discard_changes`
   re-entry path works correctly when run sequentially. Under
   parallel jprove, 4+ JVMs compete for CPU/RAM and the
   per-sub-call machinery cost is paid N× concurrently, so either
   the test itself slows down past the harness timeout or another
   test on the same harness slot does, starving this one. Either
   way, resolving §0.2/§0.3 (and thereby shrinking the per-call
   tax) will close this.

   *Safety check:* the test deliberately installs a DESTROY that
   re-enters DBIC via `discard_changes` and expects no infinite
   recursion. Confirmed locally that the semantic works (exit 0,
   DESTROY fires exactly once). So the failure is perf, not
   correctness.

### 0.4 benchmark_method — 1.7× slower [M]

Method dispatch slower than perl despite the 4096-entry inline
method cache. Likely cache miss rate is high on this benchmark,
or the cache probe itself costs more than a bare hash lookup
compiled by perl. Lower priority than 0.2 / 0.3 but still should
reach parity.

### 0.5 Hypotheses for 0.2 / 0.3

Ordered by expected impact on tight-loop sub-call workloads.

**Measurement methodology lesson (learned the hard way): don't speculate
on which item is the dominant cost — profile. Disabling the
`pristineArgsStack` ArrayList clone entirely recovered only ~13-16%
of the gap on `refcount_bless` / `refcount_anon`, meaning ThreadLocal
traffic + ArrayList allocation are NOT the dominant per-call cost.
At 7.75× slower, `refcount_bless` pays ~3 µs/call more than perl,
and TL.get() × 11 sites = only ~110 ns. The other 2.9 µs is
somewhere else — most likely in `bless()` → `MortalList.deferDecrement`
→ DESTROY dispatch, or in MethodHandle dispatch cost per sub call.**

1. **`pristineArgsStack` allocates per call.** The
   `EMPTY_ARGS_SNAPSHOT` fast path only covers zero-arg calls; any
   sub called with args allocates a fresh `ArrayList<RuntimeScalar>`
   even when nobody is in `package DB` to read it.

   *Attempted fix: lazy gate (`callerFromDBObserved` flag) — didn't
   ship.* This seemed attractive (snapshot only after caller() from
   package DB has been observed), but it breaks DBIC's
   `TxnScopeGuard::DESTROY` double-destroy detection: that DESTROY
   fires at scope exit and at THAT time walks caller frames reading
   @DB::args. The snapshot for the frames it walks was taken at sub
   *entry* — long before any caller-from-DB could have been observed.
   Lazy gating leaves those snapshots as the shared empty list and
   the double-destroy check silently loses.

   Real fix needs to be structural: **write-barrier on `@_`
   mutation** (shift/pop/splice/assignment to @_). Only subs that
   mutate @_ need to snapshot, and they can do it lazily at the
   first mutation. Unmutated subs let caller()'s `@DB::args`
   reconstruction just read from the live `argsStack` — it's
   unchanged from entry.

2. **`hasArgsStack` push/pop** — two ungated `ThreadLocal.get()` per
   sub invocation; not gated by "is anyone going to read this?".
3. **Deep-recursion counter** (`callDepth++`/`--`) runs on every
   sub call, with an extra compare and (on crossing) a warn-emit
   check. Not gated.
4. **Warning bits / hint hash push/pop** — master pushes
   `WarningBitsRegistry.pushCallerBits`, `pushCallerHints`,
   `HintHashRegistry.pushCallerHintHash` on every sub entry. Per-
   call `ThreadLocal.get()` traffic compounds heavily.
5. **`inTailCallTrampoline` / `tailCallReentry`** in the apply()
   iterative path — should be a no-op except when `goto &sub` fires,
   but worth confirming the generated bytecode doesn't force-load
   the ThreadLocal on the common path.
6. **`bless()` and `MortalList.deferDecrement`** — per-bless work
   including DESTROY registration, blessId cache, refCount bump,
   MortalList entry creation. Given the math above, this is
   probably the biggest single bucket for `refcount_bless`.
7. **MethodHandle dispatch** — every sub call dispatches through a
   MethodHandle. Not all call sites are monomorphic → some probes
   are paid per call.

### 0.6 Action plan (profiling, not bisect)

1. **Fix the bench harness** (0.1) so every subsequent change is
   easy to evaluate against both `jperl` and `perl`.

2. **Profile both baselines and diff.** Compare pre-merge
   `660aa9e68` vs PR #526 tip on `life_bitpacked` and
   `benchmark_refcount_anon`:

    ```bash
    git worktree add /tmp/bench-pre 660aa9e68
    (cd /tmp/bench-pre && make dev)

    async-profiler -d 30 -f /tmp/life_pre.html -- \
      /tmp/bench-pre/jperl examples/life_bitpacked.pl -r none -g 500
    async-profiler -d 30 -f /tmp/life_post.html -- \
      ./jperl examples/life_bitpacked.pl -r none -g 500
    # Diff flame graphs — anything in post > 2% and not in pre is a
    # candidate.
    ```

   Alternatively JFR (`-XX:StartFlightRecording=duration=30s,filename=…`)
   + JDK Mission Control.

3. **Static diff of the `RuntimeCode.apply()` prologue.** Compare
   the entry sequence before the sub body runs at `660aa9e68` vs
   current. Count the number of:
    - `ThreadLocal.get()` sites
    - allocations per call (ArrayList, RuntimeScalar, etc.)
    - static-method dispatches (push / pop of call state)

   Every extra item there multiplies against millions of sub calls
   per bench run.

4. **Count-based analysis.** Add temporary counters (gated on an
   env var) at suspected hot sites:

    ```java
    if (JPERL_HOT_COUNTERS) hotCounter.incrementAndGet();
    ```

   Run the workload once with counters on, dump totals. Any counter
   that fires many times per outer generation is a candidate for
   gating.

5. **Per-hypothesis gating fixes.** Use the `weakRefsExist` pattern
   from PR #523 (`2fb0bd129`, `a7165f711`) — a static flag that stays
   false in the common case and skips the expensive path.
    - (1) `pristineArgsStack`: gate on "is anyone in `package DB`
      and calling `caller()` right now?" (or on whether `@DB::args`
      has ever been read).
    - (2) `hasArgsStack`: gate on "is anyone asking for caller()[4]
      right now?".
    - (3) deep-recursion: gate `callDepth++/--` behind a per-instance
      `trackRecursion` bool flipped on only when the sub body
      references `warn 'recursion'` or crosses a configurable depth.
    - (4) caller hints / warning bits: defer push until `caller()`
      is actually invoked, rather than eagerly per call.
    - (5) tail-call trampoline: verify the generated bytecode; if
      the `if (tailCallReentry)` check forces a ThreadLocal load,
      reorder so the load only happens inside the branch that
      bumps.

6. **Iterate per change.** Don't bundle hypotheses; each gate/cache
   should be one commit so regressions are attributable.

### 0.7 Acceptance

- `./jperl examples/life_bitpacked.pl -r none -g 500` back to
  **≥ 12 Mcells/s** (recovers the PR #523 win).
- `benchmark_refcount_anon` ratio ≤ **1.5× perl** (from 3.94×).
- `benchmark_method` ratio ≤ **1.2× perl** (from 1.73×).
- All other benchmarks maintain or improve their current ratios.
- `make` still green, DBIC 52leaks + txn_scope_guard + `make
  test-bundled-modules` still passing.

Once all green, the stretch goal is **every benchmark ≤ 1.0× perl**
(full parity). We're already there on lexical / string / closure.

### 0.8 Progress log

Per-change log of §0 work since the baseline-d071692a3 snapshot.
Each entry: commit + what changed + measured effect.

| Commit | Change | refcount_bless | refcount_anon | life_bitpacked | Notes |
|---|---|---:|---:|---:|---|
| `d071692a3` | **baseline** | 7.75× | 4.55× | 2.57× | PR #526 merge tip |
| `fa8df8a2a` | RegexState EMPTY singleton (skip alloc when no regex has matched) | — | — | — | Correctness-preserving, modest measured effect. Removed ~250 sampled allocs/run. |
| `1400475d3` | NameNormalizer two-level cache (drop CacheKey alloc) | **6.63×** | **4.07×** | 2.60× | -14% on bless, -11% on anon. global also improved 1.92→1.33. |
| `b7d05b77e` | docs: §0.8 progress log | | | | |
| *(reverted)* | apply()/applySlow fast-path split | 6.56× | 4.08× | **5.38× ❌** | Bytecode size of key apply() overloads dropped from 1150/982/619 bytes to 28/99/105 — small enough for HotSpot to inline (and `-XX:+PrintInlining` confirmed inlining at hot call sites). But life_bitpacked REGRESSED 2×: 2.57× → 5.38×. Hypothesis: HotSpot's inline-budget shifted, pushing the inner compute loop over an internal size boundary. User rule: revert on regression. Finding recorded. |

**Lesson on the apply() split.** Making `apply()` inlinable does not
automatically help. HotSpot's inliner is greedy: if the caller's
inlined body grows past the `InlineSmallCode`/budget threshold, later
calls in the same caller become *non-inlinable*. Shrinking the
static/instance apply() wrappers from 600-1150 bytes down to 28-105
bytes made SOME callers faster (closure 1.21×→0.84×, i.e. now faster
than perl) but regressed life_bitpacked's main compute loop by 2×.
The overall picture was worse so the split was reverted.

Next time a similar intervention is attempted, it must be measured
against life_bitpacked specifically (and ideally profiled with
`-XX:+PrintCompilation -XX:+PrintInlining` to identify the caller
that flipped).

### 0.9 What we learned

**JFR-profiling findings so far.**

Running `JPERL_OPTS="-XX:StartFlightRecording=duration=20s,method-profiling=high"`
on a tight bless/DESTROY loop (`N=50_000_000`) identified, in order
of allocation weight (all via `jdk.ObjectAllocationSample`):

1. **`RegexState`** (~250 sampled allocs, 765 kB) — `save()` per sub
   call regardless of regex activity. ✅ fixed in `fa8df8a2a`.
2. **`NameNormalizer$CacheKey`** (~116 sampled allocs, 4 MB) —
   per-lookup CacheKey to probe the normalize-variable-name cache.
   ✅ fixed in `1400475d3`.
3. Remaining top allocators (still open):
   - `java.lang.Object[]` (~300 samples) — ArrayList / Deque
     backing arrays, likely from `pristineArgsStack` and `MortalList`.
   - `RuntimeScalar` (~249 samples) — per-iteration scalar
     wrapping of bless class name + argument.
   - `RuntimeArray` (~216 samples) — @_ per call, can't eliminate.
   - `ArrayList` (~213 samples) — pristineArgsStack snapshots.
   - `RuntimeList` (~206 samples) — return values.
   - `RuntimeHash` (~119 samples) — the `{ id => 1 }` literal.
   - `RuntimeScalarIterator` (~112 samples) — iterator wrappers
     for pass-through cases.

**Benchmark.pm overhead caveat.** When I removed Benchmark.pm and
ran a bare `for (1..N) { WithDestroy->new(1) }` loop, the ratio
dropped from **7.75× to ~2.3×**. Benchmark.pm itself inflates the
apparent gap by ~3× — mostly because its eval-generated timing
wrapper hits extra dispatch. Investigate Benchmark.pm as an
independent optimization target (it affects all Benchmark-wrapped
workloads, not just these benches).

**Key insight for future work.** Most remaining allocations look
inherent to per-call semantics (@_, return RuntimeList, etc.).
The path to recovering the 2-3× gap is probably structural rather
than allocation-by-allocation: inline small arg passing, consider
a "simple-sub" emit path that doesn't build RuntimeArray @_ when
the callee doesn't reference it, etc. See §0.5 hypotheses.

---

## 1. Correctness follow-ups

### 1.1 Numeric-overload `fallback=undef` throw path [M, high]

**Context.** PR #526 commit `1869badd2` fixed the string-compare
(`eq`/`ne`) case where `fallback=>undef` should throw "Operation 'op':
no method found" per Perl 5 semantics. The numeric arithmetic
operators still don't do this.

**Repro.**
```perl
package P; use overload '""' => sub { $_[0] };
package main; my $x = bless {}, 'P'; print $x + 1;
# real perl:   dies "Operation '+': no method found"
# jperl today: prints something like "called plus operator in P at …"
```

**Fix sketch.** Extend `throwIfFallbackDenied` (now private in
`CompareOperators`) into a shared helper under `OverloadContext`,
then call it from each numeric op in `ArithmeticOperators.java`
after `tryTwoArgumentNomethod` returns null.

**Unblocks.** `Class::C3 t/24_more_overload.t` (2/3 pass). Probable
downstream wins in any code exercising numeric overload on
fallback=undef classes.

### 1.2 `goto &func` through `next::can` [S, medium]

**Context.** `Class::C3 t/36_next_goto.t` "proxy next::can via goto"
fails: returns undef, expected 242. The iterative `goto &func`
trampoline in `RuntimeCode.apply` doesn't resolve through
`next::can` indirections the way recursive dispatch does.

**Repro.** See Class::C3 t/36_next_goto.t; minimal test:
```perl
package A; sub foo { 1 }
package B; our @ISA = ('A'); sub foo { goto &{$_[0]->next::can('foo')} }
package main; print B->foo;
# perl:  1
# jperl: (undef / error)
```

**Fix sketch.** When the `TAILCALL`'s `nextTailCode` is a
`next::can`-resolved scalar that points into a different `@ISA`
branch, the trampoline must update the `@ISA` walk state, not just
re-enter apply() at the new code ref.

### 1.3 `destroy_eval_die.t#4` — DESTROY LIFO order during eval/die unwinding [L, medium]

**Status.** Pre-existing on `660aa9e68`, not a PR #526 regression.

**Symptom.** Two guards `$g1`, `$g2` created in an eval block; when
`die` unwinds, their DESTROY callbacks fire in FIFO order (first,
second) instead of LIFO (second, first) as Perl does.

**Fix area.** `RuntimeCode.apply` RuntimeException catch → MortalList
/ MyVarCleanupStack unwind ordering. Currently:
```java
if (!(e instanceof PerlExitException)) {
    MyVarCleanupStack.unwindTo(cleanupMark);
    MortalList.flush();
}
```
`unwindTo` walks the cleanup stack — check iteration direction
matches LIFO (bottom-up popping).

### 1.4 `MRO::Compat` load-time redefine warnings [S, low]

**Symptom.** `Class::C3 t/37_mro_warn.t` fails because
`MRO::Compat.pm` (our bundle) emits "Subroutine X redefined"
warnings when loaded. The test captures warnings and expects none.

**Fix sketch.** Either guard the redefinitions in `MRO::Compat.pm`
with `no warnings 'redefine'`, or ensure our `warn`/`redefine`
machinery suppresses BEGIN-time self-loads.

---

## 2. Packaging / infrastructure follow-ups

### 2.1 Complete the Class::XSAccessor PP bundle [S, low]

PR #526 shipped a pure-Perl `Class::XSAccessor` + `::Heavy` + `::Array`.
API is covered for Moo/DBIC. Remaining gaps if we want the upstream
test suite to pass:

- `__tests__` hook (currently returns a stub boolean).
- Benchmark the PP vs Moo's own PP fallback — if XSAccessor PP is
  slower, Moo is better off detecting our stub and skipping it.

**Triage.** Only worth doing if the CPAN compatibility matrix
(`dev/cpan-reports/cpan-compatibility.md`) starts being driven from
automated runs.

### 2.2 Text-CSV bundled-runner flake [S, low]

`make test-bundled-modules` reports `module/Text-CSV/t/55_combi.t`
test 26 failure; running the same file directly with `./jperl`
produces 25119/25119 PASS. Investigate what the Gradle `testModule`
runner does differently (cwd? env? timeout mid-output?) and fix the
harness rather than the test.

---

## 3. Performance — next tier

The J-tier landed (J1/J2/J4). Things that came up but weren't chased:

### 3.1 `setLargeRefCounted` tiered path review [M, low]

Reviewed during J5, judged already tiered. Re-visit if a future
profile shows it in the top 10 — especially under heavy
`bless`-in-constructor workloads.

### 3.2 `ScalarRefRegistry` gating — extend to more registries [M, medium]

The `weakRefsExist` gate landed for `ScalarRefRegistry.registerRef`
and `MyVarCleanupStack.liveCounts` (#523 / `a7165f711`, `2fb0bd129`).
There may be other registries that can follow the same pattern:

- `WeakRefRegistry` direct-add paths
- Mortal-mark machinery for sub boundaries
- Inline method cache eviction on `@ISA` changes

**Approach.** Run a profiler on `life_bitpacked` + `arr_int` with the
current tip and look for remaining `ThreadLocal.get()` / registry
dispatch in the hot path.

### 3.3 Method-cache hit/miss counters (diagnostic) [S, low]

For inline method cache tuning, a `JPERL_METHOD_CACHE_STATS=1`
env-gated counter dump would make it easy to see if cache size 4096
is the right tradeoff across typical OO workloads.

---

## 4. CPAN compatibility tracking

- **`Class::C3`** failures (1.1, 1.2, 1.4 above) are the blockers.
  Fixing them will flip Class::C3 from FAIL to PASS, which also
  strengthens DBIx::Class (Class::C3 is a transitive dep).
- Re-run `dev/tools/cpan_random_tester.pl` after 1.1 + 1.2 land to
  spot other affected modules.

---

## Progress tracking

### Current status

| Track | State |
|---|---|
| PR #526 merge | Open; clean test suite; ready for review |
| **§0 perf parity with system perl** | **OPEN — top priority after merge; worst: refcount_anon 3.94×, life_bitpacked 3.15×** |
| 0.1 bench harness COMPARE=perl mode | Pending (prerequisite for §0) |
| 0.2 life_bitpacked regression | Pending |
| 0.3 refcount_anon 4× gap | Pending |
| 0.4 method 1.7× gap | Pending |
| 1.1 numeric-overload throw | Pending |
| 1.2 goto + next::can | Pending |
| 1.3 destroy_eval_die LIFO | Pending (pre-existing) |
| 1.4 MRO::Compat redefine warn | Pending |
| 2.1 Class::XSAccessor PP completion | Partial (API covered; test-level gaps remain) |
| 2.2 Text-CSV runner flake | Pending |
| 3.x perf next-tier | Blocked on §0 — don't add new perf work until parity is reached |

### Completed / archived

- `feature/refcount-alignment` (PR #508) — refcount phases 1-7.
- `feature/phase-j-performance` (PR #518) — J1/J2/J4.
- `feature/walker-hardening-j3` (PR #523) — walker gating + bitwise/long fast paths.
- **`feature/refcount-perf-combined` (PR #526)** — rebased superset of the above + bundled `Class::XSAccessor` PP + numeric-cmp overload fix.

### Next action

**§0 first — specifically §0.1 (fix the bench harness).** Without
side-by-side `jperl` vs `perl` numbers written to
`dev/bench/results/<sha>.json`, every subsequent perf change will
be hard to evaluate. Once that lands, follow the profiling plan
in §0.6 to tackle §0.2 (life_bitpacked) and §0.3 (refcount_anon)
together — they likely share root causes in the per-sub-call
machinery.

Only after parity is reached (or we hit the acceptance bar in §0.7)
should §1 (correctness) or §3 (new perf work) be started; §3 is
explicitly blocked.

If a human reviewer wants to pick a small correctness item while
§0 is in flight, 1.1 (numeric-overload fallback throw) is the
shortest path since it reuses the `1869badd2` helper pattern.

---

## Related docs

- **Archived plans:** `dev/design/archive/`
- **Skills:** `.agents/skills/debug-perlonjava/`, `.agents/skills/interpreter-parity/`, `.agents/skills/profile-perlonjava/`
- **Sandbox tests:** `dev/sandbox/destroy_weaken/`
- **CPAN compatibility:** `dev/cpan-reports/cpan-compatibility.md`
- **Architecture:** `dev/architecture/weaken-destroy.md`
