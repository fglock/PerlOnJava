# Post-PR-#526 Plan

**Status:** Proposal
**Supersedes:** `dev/design/archive/{phase_j_performance_plan, refcount_alignment_plan, refcount_alignment_52leaks_plan, refcount_alignment_progress, pr526_merge_validation}.md`
**Entry state:** `feature/refcount-perf-combined` (PR #526, commit `c06a554ac`) — master merged + perf stack (Phase-J + walker hardening + refcount) landed. All unit tests pass except one pre-existing failure. DBIx::Class, Template, Moo, Date::Calc, Math::Base::Convert, and Memoize all pass at expected levels.

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
| 1.1 numeric-overload throw | Pending |
| 1.2 goto + next::can | Pending |
| 1.3 destroy_eval_die LIFO | Pending (pre-existing) |
| 1.4 MRO::Compat redefine warn | Pending |
| 2.1 Class::XSAccessor PP completion | Partial (API covered; test-level gaps remain) |
| 2.2 Text-CSV runner flake | Pending |
| 3.x perf next-tier | Not started |

### Completed / archived

- `feature/refcount-alignment` (PR #508) — refcount phases 1-7.
- `feature/phase-j-performance` (PR #518) — J1/J2/J4.
- `feature/walker-hardening-j3` (PR #523) — walker gating + bitwise/long fast paths.
- **`feature/refcount-perf-combined` (PR #526)** — rebased superset of the above + bundled `Class::XSAccessor` PP + numeric-cmp overload fix.

### Next action

Pick one of 1.1 / 1.2 / 1.4 and open a feature branch named after the
issue (e.g. `fix/numeric-overload-fallback-throw`). 1.1 reuses the
`1869badd2` helper pattern so it's the shortest path to an incremental
win.

---

## Related docs

- **Archived plans:** `dev/design/archive/`
- **Skills:** `.agents/skills/debug-perlonjava/`, `.agents/skills/interpreter-parity/`, `.agents/skills/profile-perlonjava/`
- **Sandbox tests:** `dev/sandbox/destroy_weaken/`
- **CPAN compatibility:** `dev/cpan-reports/cpan-compatibility.md`
- **Architecture:** `dev/architecture/weaken-destroy.md`
