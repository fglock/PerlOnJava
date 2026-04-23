# Hash::Wrap `t/as_return.t` — GC-thrash / infinite-loop triage plan

**Status**: Investigation in progress. PR #536 blocked until this class of failure is resolved.

## Scope

Hash::Wrap's `t/as_return.t` (45 lines) and DBIx::Class exhibit the same class of failure: extremely high CPU + memory, no apparent forward progress, wallclock >> real-Perl expectation. User-visible symptom is "stuck" or "timeout".

This plan picks Hash::Wrap as the minimal reproducer (tight CPAN test, independent of DBIC fixtures).

## Observations (2026-04-23)

### Reproducer captured
```
/Users/fglock/projects/PerlOnJava3/dev/bench/hash_wrap_repro/
  t/as_return.t        # 45 lines, copied from Hash-Wrap-1.09
  lib/Hash/Wrap.pm     # upstream pure-Perl
```

Invoke:
```bash
cd dev/bench/hash_wrap_repro
timeout 30 ../../../jperl -Ilib t/as_return.t
```

Baseline: at 15 s the main thread has used 13 s CPU (~89 % of one core — **not** GC-thrash on my machine). Reproduces at 11+ cores on the user's original machine — same code, different GC amplification due to machine/load. Correctness-level reproducer is the same.

### First bug localised: `B::NULL::next` self-loop

`jstack` on the stuck process shows the inner loop is:

```
java.util.concurrent.ConcurrentHashMap.get(ConcurrentHashMap.java:952)
NameNormalizer.normalizeVariableName(NameNormalizer.java:144)
InheritanceResolver.findMethodInHierarchy(InheritanceResolver.java:310)
Universal.can(Universal.java:175)
RuntimeCode.callCached(RuntimeCode.java:1780)
anon1485.apply(Test2/Util/Sub.pm:577)      <-- $op->can('line') / $op->can('next')
```

Tracing upward: `Test2::Util::Sub::sub_info` walks the OP tree:

```perl
my $op = $cobj->START;
while ($op) {
    push @all_lines => $op->line if $op->can('line');
    last unless $op->can('next');       # <- termination check
    $op = $op->next;
}
```

PerlOnJava's `src/main/perl/lib/B.pm` has:

```perl
package B::NULL {
    our @ISA = ('B::OP');
    sub new { bless {}, shift }
    sub next {
        # NULL is terminal -- return self to prevent infinite loops
        return $_[0];
    }
}
```

**The comment is inverted.** Returning `$_[0]` keeps `$op` as the same B::NULL forever:

* `$op->can('line')` → true (inherited from B::OP)
* `$op->can('next')` → true (inherited from B::OP)
* `$op = $op->next` → same B::NULL
* Loop never exits, `@all_lines` grows unboundedly → GC pressure once array outgrows young gen → user sees the 13 GC threads + 25 % useful CPU.

Hash::Wrap trips this because Test2's structural compare (`meta { prop ... object { call ... } }`) calls `sub_info` on every comparison callback — one infinite loop per check.

DBIx::Class likely trips the same path (its test suite also uses Test2 deep compare, and DBIC itself uses Sub::Defer / B introspection heavily).

### Fix for the immediate infinite loop

Replace `B::NULL::next` with a sentinel that actually terminates the common walker patterns:

```perl
package B::NULL {
    our @ISA = ('B::OP');
    sub new { bless {}, shift }

    # Every method call on B::NULL returns undef (matches real Perl XS).
    # Crucially, `$null->next` returning undef terminates while($op) loops.
    sub next { return; }
    sub line { return; }
    # `can('next')` still returns true via B::OP inheritance; the
    # caller's `$op = $op->next` sets $op to undef and while($op) exits.
}
```

Before landing: audit other B.pm sentinel methods (`sibling`, `targ`, `sibparent`, `first`, `last`, etc.) for the same mistake.

## Why this is sufficient for Hash::Wrap but not the full class of problem

The B::NULL fix makes `sub_info` terminate on first invocation. Once it's terminating:

1. The test proceeds into the actual structural compare.
2. Every `is($obj, meta { ... })` still allocates deep `Test2::Compare::Delta` trees.
3. Each Delta node is a blessed hashref → traverses `RuntimeScalar.setLargeRefCounted`, `MortalList.deferDecrement*`, walker arming etc.
4. This is the *real* distributed-tax problem we already confirmed in Phase R.

With just the B::NULL fix, Hash::Wrap completes but still runs an order of magnitude slower than real Perl. That may be acceptable for the test-to-pass gate; it is not acceptable for "perf parity". The full plan below addresses both.

## Plan

Four phases. Each phase has an explicit measurement gate before moving to the next.

### Phase 0 — Unblock the test (same-day)

1. **Fix `B::NULL::next`** and audit other B.pm sentinels (see above).
2. Run Hash::Wrap `t/as_return.t` and `DBIx-Class-0.082844-68/t/storage/base.t` to completion. Record wallclock, CPU ratio, allocation rate via JFR.
3. Acceptance: both complete in finite time, produce TAP with actual pass/fail rather than timeouts. (Pass/fail counts themselves can still regress — that's Phase 1-3 territory.)
4. Commit the fix on `perf/phase-r-needs-cleanup`.

**Risk**: very low. Change is localised to the B.pm shim. Regression surface: code that relied on `$null->next == $null` for some iteration invariant. No known such code.

### Phase 1 — Establish allocation baseline

Goal: turn "slow under GC" from hand-wave into numbers.

1. JFR run on Hash::Wrap `t/as_return.t`:
   ```
   JPERL_OPTS="-XX:+FlightRecorder -XX:StartFlightRecording=\
     filename=dev/bench/results/jfr/hash_wrap.jfr,\
     settings=profile,duration=60s" \
     ./jperl -Ilib t/as_return.t
   ```
   Capture `jdk.ObjectAllocationSample` + `jdk.ObjectAllocationInNewTLAB` + `jdk.GCHeapSummary`.

2. Same run with `JPERL_CLASSIC=1` for the upper bound.

3. Top allocators (top 10 by bytes): expected candidates are `RuntimeScalar`, `RuntimeHash`, `RuntimeArray`, `MortalList$Entry`, Test2 Delta/Check/Meta classes (pure Perl packages compiled to our anon classes). Record exact numbers in `dev/design/hash_wrap_alloc_profile.md`.

4. GC metric deltas: young-gen pause %, old-gen promotions/sec, total GC time as % of wallclock. If CLASSIC drops GC time from e.g. 60 % to 10 %, we know our machinery is the allocation driver; if GC stays high under CLASSIC, the allocation source is non-PerlOnJava (upstream Test2 / Hash::Wrap pattern itself).

**Acceptance gate**: an allocation profile committed under `dev/bench/results/` that clearly identifies the top 3 allocation sites contributing >60 % of bytes.

### Phase 2 — Reduce allocation at the top-3 sites

This is concrete engineering work whose scope depends on Phase 1's findings. Candidate targets based on prior profiling work:

| Candidate | Already known from | Expected impact |
|---|---|---|
| `RuntimeList.add` → `ArrayList.grow` from initial capacity 10 | `life_bitpacked_jfr_profile.md` | 5–14 % on life_bitpacked |
| `MortalList.pending` growth (same `ArrayList.grow` pattern) | `classic_experiment_finding.md` (implicit) | varies with callsite density |
| Per-`my` `MyVarCleanupStack.register` list add | Phase R measured | already captured in `1.49×` |
| Intermediate `RuntimeScalar(integer)` boxing in comparison callbacks | `life_bitpacked_jfr_profile.md` (via `RuntimeScalarCache.getScalarInt`) | unknown for Test2 workload |

For each chosen target:

1. Minimal hack that short-circuits the allocation (even if broken) — upper-bound measurement.
2. If upper bound ≥ 5 % wallclock improvement, implement cleanly.
3. If < 5 %, document and move on (Phase 1 Lessons Learned rule).

**Acceptance gate**: Hash::Wrap wallclock within 5 × real Perl and no test failures beyond pre-existing.

### Phase 3 — Conditional machinery (the real Phase R)

`JPERL_CLASSIC=1` proved that removing the machinery globally restores master-era performance. Making the machinery *conditional on need* gives us that speedup without sacrificing DESTROY/weaken correctness.

Proposal restated here for a fresh reader:

* One `public boolean needsCleanup` on `RuntimeBase`, default `false`.
* Set to `true` on: `bless` into a class with `DESTROY`, `Scalar::Util::weaken`, closure-capture of a blessed referent (later — first cut only covers the first two).
* Every CLASSIC-gated site becomes `if (!base.needsCleanup) return <classic fast path>;`:
  - `RuntimeScalar.setLargeRefCounted`
  - `RuntimeScalar.scopeExitCleanup`
  - `MortalList.deferDecrementIfTracked` etc.
  - `MortalList.scopeExitCleanupHash` / `scopeExitCleanupArray`
  - `EmitVariable`: MyVarCleanupStack.register emission (still compile-time gated via `CleanupNeededVisitor`, that stays)

Test2's `Compare::Delta` nodes are blessed but *don't* have DESTROY — so they land on the fast path. Hash::Wrap's `A1`/`A2` wrappers are blessed but don't have DESTROY — fast path. DBIC's `ResultSet`/`ResultSource` *do* have DESTROY (via `next::can` dispatch under the hood) — slow path, correct.

**Scope**: ~30 gate sites mapped by the CLASSIC patch. Each call site gets a one-line guard. Core invariant change is on `RuntimeBase` — one new bit.

**Acceptance gate** (the PR merge gate):

| Measurement | Gate |
|---|---|
| Hash::Wrap `t/as_return.t` | passes in < 2 × real-Perl wallclock |
| DBIC full suite `./jcpan -t DBIx::Class` | zero timeouts; same pass count as commit `99509c6a0` (13 804 / 13 804) |
| `make test-bundled-modules` | still 176 / 176 |
| `make` unit tests | no new regressions beyond pre-existing `destroy_eval_die.t#4` |
| `life_bitpacked` | Phase R speedup preserved (≥ 1.3 × vs pre-merge baseline) |
| `destroy_eval_die.t` | same pass count (9 / 10 on current branch) |
| DBIx::Class `t/storage/txn_scope_guard.t` | 18 / 18 |

**Risk**: Medium. Per-object bit is simple in principle; the hard part is ensuring every *entry* into the tracked-object set correctly flips the bit. Fortunately the CLASSIC patch already identifies the gates, so we have a map.

### Phase 4 — Validation & documentation

1. Run Phase 3 acceptance gate on a clean machine. Document wallclock/CPU/GC numbers for each benchmark in `dev/bench/results/`.
2. Update `dev/design/perl_parity_plan.md` to reflect Phase R → Phase R+(refcount-by-need) progression.
3. Merge PR #536 once all gates are green.
4. File follow-up tickets for remaining ≤ 5 % per-site optimisations (none are in scope for the merge).

## Sequence / dependencies

```
Phase 0 (immediate fix) ──┐
                          ├─▶ Phase 1 (profile) ──▶ Phase 2 (alloc reductions) ──▶ Phase 3 (conditional machinery) ──▶ Phase 4 (validate + merge)
```

Phase 0 is the sole prerequisite to unblock `./jcpan -t DBIx::Class` from getting stuck in the infinite loop. Phases 2 and 3 are independent of each other — if Phase 2 alone gets us to the merge gate, Phase 3 can slip to a follow-up PR.

## Immediate next step

Apply the B::NULL fix, verify Hash::Wrap completes (doesn't need to *pass*, just complete), commit, rerun `./jcpan -t DBIx::Class` to see whether any tests that were previously timing out now progress to a proper result.
