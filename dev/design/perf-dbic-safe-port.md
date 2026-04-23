# DBIC-Safe Perf Port Plan

## Goal
Apply performance optimizations from commits between `99509c6a0` (DBIC clean, slow)
and `1c79bbc7b` (fast, DBIC broken) onto a branch based on `99509c6a0`, without
breaking DBIx::Class.

## Baselines to establish (on branch, pre-cherry-pick)
- `./jperl examples/life_bitpacked.pl` → expect ~5.34 Mcells/s
- Fast DBIC indicator set (the 8 user-flagged tests); expect all PASS on 99509c6a0
- `./jcpan --jobs 8 -t Template` → **known broken on 99509c6a0** (passes on 1c79bbc7b)
  - Use as a secondary signal — some fixes between the two branches repair Template
- Full `./jcpan -t DBIx::Class` → PASS (already confirmed)

## Fast DBIC indicator set (user-provided)
```
t/96_is_deteministic_value.t   # Sub::Defer trampoline hang
t/resultset/as_subselect_rs.t  # Sub::Defer trampoline hang
t/search/select_chains.t       # Sub::Defer trampoline hang
t/storage/error.t              # Sub::Defer trampoline hang
t/storage/txn.t                # #89 nested failed txn_do
t/debug/pretty.t               # missing Parse::RecDescent dep (env issue)
t/52leaks.t                    # END-phase captureCount++ leak
t/zzzzzzz_perl_perf_bug.t      # cascade from Sub::Defer hang
```

Note: 4 of 8 failures share "Sub::Defer trampoline hang" root cause — they'll
almost certainly move together. `t/storage/txn.t` subtest 89 and `t/52leaks.t`
END-phase are distinct signals.

## Commits in scope (perf-labelled)

Low-risk (no obvious DBIC coupling):
  886e7498b  cache PerlRuntime.current() in local variables            (Tier 1)
  d070812cd  migrate ThreadLocal stacks to PerlRuntime instance fields (Tier 2)
  4a3b07287  batch push/pop caller state
  c33d7c828  batch RegexState save/restore
  b84ee499c  skip RegexState save/restore for regex-free subs
  fa8df8a2a  skip RegexState allocation when no regex has matched yet
  d4ddb7043  JVM-compile anonymous subs inside eval STRING
  f4d474a40  cache System.getenv() in hot paths as static final
  17527e8e7  cache warning bits + empty-args snapshot fast path
  660aa9e68  avoid autoboxing in RuntimeScalar(long) + bitwise fast paths
  1400475d3  avoid per-lookup CacheKey allocation in NameNormalizer

High-risk (touches Phase I GC/cleanup infra — primary DBIC suspects):
  2fb0bd129  gate ScalarRefRegistry.registerRef() on weakRefsExist
  a7165f711  gate MyVarCleanupStack.liveCounts on weakRefsExist
  ea7c66811  JPERL_CLASSIC gate / cumulative-tax investigation
  31caba56a  gate MyVarCleanupStack.unregister emission per-sub
  40e19e7a8  skip MyVarCleanupStack.register emission for simple subs

Related non-perf but perf-adjacent (may matter):
  4a1ad046b  fix(closure): track captureCount for named subs (mentioned in
             t/52leaks.t failure note!)
  1c79bbc7b  fix(B): B::NULL is terminal — return undef from all accessors

## Strategy
1. Branch `perf/dbic-safe-port` from 99509c6a0.
2. Cherry-pick low-risk commits as one batch. Verify:
   - make (unit tests)
   - life_bitpacked Mcells/s (log delta)
   - fast DBIC indicator set (all PASS)
   - (optionally `./jcpan --jobs 8 -t Template`)
   If anything breaks: bisect within batch to find offender; skip/fix.
3. Cherry-pick high-risk commits one at a time. Same verify per commit.
   If a commit breaks DBIC: read the commit, try to either
     (a) tighten the gate so it's DBIC-safe, or
     (b) skip & document why.
4. Final: full `./jcpan -t DBIx::Class` to confirm all 314 files PASS.
5. Open WIP PR.

## Non-goals
- No DBIC test edits.
- No push to master.
- No changes outside the perf commit set unless required to fix regressions
  from a cherry-pick.

## Tracking

See branch `perf/dbic-safe-port` and progress log appended below.

## Progress log

### Branch: `perf/dbic-safe-port` (from 99509c6a0)

| Step | Commit | On branch as | life_bitpacked Mcells/s | DBIC (8-test indicator set) | Unit tests |
|------|--------|--------------|-------------------------|-----------------------------|-----------|
| baseline | (none, @ 99509c6a0) | — | 5.6 | 8/8 PASS (full suite) | PASS |
| 1 | 2fb0bd129 ScalarRefRegistry.registerRef gate | e14adedee | 6.45 (+15%) | — | — |
| 2 | a7165f711 MyVarCleanupStack.liveCounts gate | 8edd81758 | 8.17 (+46%) | 8/8 PASS | — |
| 3 | 31caba56a MyVarCleanupStack.unregister emission gate | d256ede8f | 8.53 (+52%) | — | — |
| 4 | 40e19e7a8 Phase R register skip | 3450ed987 | **13.27 (+137%)** | **8/8 PASS** | **PASS** |

### Conflict resolutions
- `Configuration.java`: trivial — kept HEAD (build auto-regenerates).
- `EmitterMethodCreator.java` (step 3): accepted incoming block that adds
  `FORCE_CLEANUP` + env-caching constants (ASM_DEBUG, SPILL_SLOT_COUNT, etc.).
  These constants are defined but most call sites still use `System.getenv()`
  inline (those call-sites are migrated later by f4d474a40 which we haven't picked).
  No behavioural change; dead code at worst.
- `EmitVariable.java` (step 4): dropped `!MortalList.CLASSIC` from the
  Phase-R gate. The `CLASSIC` flag was introduced by ea7c66811 (measurement-
  only investigation commit we are explicitly not picking). Dropping it just
  removes a never-used off-path; Phase-R gate becomes purely
  `operator.equals("my") && cleanupNeeded`.

### Result
Target: 11.69 Mcells/s (from 1c79bbc7b). **Achieved: 13.27 Mcells/s.** Target
met with 4 cherry-picks, zero DBIC regressions in the user's 8-test indicator
set. Full DBIC suite run pending.

### Phase 2 candidates (optional additional wins)
- 17527e8e7 warning-bits cache + empty-args snapshot (reported +5–10%)
- fa8df8a2a RegexState.EMPTY singleton (1–3% typical, up to 17% on
  refcount-heavy benches)
- 660aa9e68 no-autobox RuntimeScalar(long) (0%, noise — maybe skip)
- 1400475d3 NameNormalizer CacheKey removal (unquantified)
- f4d474a40 System.getenv() caching (unquantified; would also remove the dead
  constants we accepted in step 3 by migrating their call sites)

Not on Phase 2 list:
- 4a1ad046b fix(closure) captureCount — user flagged as 52leaks.t regressor
- ea7c66811 JPERL_CLASSIC — measurement only
- ThreadLocal-consolidation chain (886e7498b etc.) — big on closure/method
  microbench but not life_bitpacked; would need whole chain picked together.

