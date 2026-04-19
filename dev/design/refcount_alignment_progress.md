# Refcount Alignment Progress

Tracks the pass/fail state of `dev/sandbox/destroy_weaken/` tests after each
phase of `dev/design/refcount_alignment_plan.md`.

Run `dev/tools/destroy_semantics_report.pl --write dev/design/refcount_alignment_progress.md`
to append a new snapshot.

For richer refcount diagnostics (REFCNT delta per checkpoint), use
`dev/tools/refcount_diff.pl <script.pl>`.

Target test files that depend on this work:
- `dev/sandbox/destroy_weaken/*.t` — in-tree corpus
- DBIC `t/52leaks.t`, `t/storage/txn.t`, `t/storage/txn_scope_guard.t`
- Perl 5 core `t/op/destruct.t`, `t/op/weaken.t`

## Phase 0 baseline — Sun Apr 19 13:30:57 2026

| test | perl | jperl |
|------|------|------|
| destroy_basic.t | 18/18 | 18/18 |
| destroy_collections.t | 22/22 | 22/22 |
| destroy_edge_cases.t | 22/22 | 22/22 |
| destroy_inheritance.t | 10/10 | 10/10 |
| destroy_no_destroy_method.t | 13/13 | 13/13 |
| destroy_return.t | 24/24 | 24/24 |
| known_broken_patterns.t | 4/4 | 3/4 |
| weaken_basic.t | 34/34 | 34/34 |
| weaken_destroy.t | 24/24 | 24/24 |
| weaken_edge_cases.t | 42/42 | 42/42 |
| **TOTAL** | **213/213** | **212/213** |

## Phase 2 — Sun Apr 19 13:40:46 2026

| test | perl | jperl |
|------|------|------|
| destroy_basic.t | 18/18 | 18/18 |
| destroy_collections.t | 22/22 | 22/22 |
| destroy_edge_cases.t | 22/22 | 22/22 |
| destroy_inheritance.t | 10/10 | 10/10 |
| destroy_no_destroy_method.t | 13/13 | 13/13 |
| destroy_return.t | 24/24 | 24/24 |
| known_broken_patterns.t | 4/4 | 4/4 |
| weaken_basic.t | 34/34 | 34/34 |
| weaken_destroy.t | 24/24 | 24/24 |
| weaken_edge_cases.t | 42/42 | 42/42 |
| **TOTAL** | **213/213** | **213/213** |

## Phase 3 — Sun Apr 19 14:26:06 2026

| test | perl | jperl |
|------|------|------|
| destroy_basic.t | 18/18 | 18/18 |
| destroy_collections.t | 22/22 | 22/22 |
| destroy_edge_cases.t | 22/22 | 22/22 |
| destroy_inheritance.t | 10/10 | 10/10 |
| destroy_no_destroy_method.t | 13/13 | 13/13 |
| destroy_return.t | 24/24 | 24/24 |
| known_broken_patterns.t | 4/4 | 4/4 |
| weaken_basic.t | 34/34 | 34/34 |
| weaken_destroy.t | 24/24 | 24/24 |
| weaken_edge_cases.t | 42/42 | 42/42 |
| **TOTAL** | **213/213** | **213/213** |

## Phase 4 — Sun Apr 19 14:31:38 2026

| test | perl | jperl |
|------|------|------|
| destroy_basic.t | 18/18 | 18/18 |
| destroy_collections.t | 22/22 | 22/22 |
| destroy_edge_cases.t | 22/22 | 22/22 |
| destroy_inheritance.t | 10/10 | 10/10 |
| destroy_no_destroy_method.t | 13/13 | 13/13 |
| destroy_return.t | 24/24 | 24/24 |
| known_broken_patterns.t | 4/4 | 4/4 |
| weaken_basic.t | 34/34 | 34/34 |
| weaken_destroy.t | 24/24 | 24/24 |
| weaken_edge_cases.t | 42/42 | 42/42 |
| **TOTAL** | **213/213** | **213/213** |

## Phase 6 — CPAN validation snapshot

### DBIC (0.082844)

| test file | result | notes |
|-----------|--------|-------|
| t/storage/txn.t | 90/90 ✅ | All pass |
| t/storage/txn_scope_guard.t | 18/18 ✅ | Test 18 now passes (Phase 3 DESTROY FSM) |
| t/52leaks.t | 11/20 | 9 real fails (TODO 2 excluded). Blocked on deeper JVM-temp inflation — orthogonal to this plan |
| t/storage/error.t | 48/49 | Test 49 failed before this plan too (pre-existing) |

All other `t/*.t` and `t/storage/*.t` files: no real failures.

### Moo (2.005005)

All 71 test files pass (no real failures).

### Remaining blockers

- DBIC `t/52leaks.t` tests 12-20 require detecting unreachability for objects
  held by DBIC's internal caches/stashes. Opt-in `Internals::jperl_gc()`
  exposes a reachability sweep, but automatic triggering caused regressions
  because the walker cannot see JVM-call-stack lexicals.
- DBIC `t/storage/error.t` test 49 (callback after $schema gone) was failing
  on master before this plan — pre-existing, not in scope.

### Success metric progress

- DBIC t/storage/txn.t: ✅ 90/90
- DBIC t/storage/txn_scope_guard.t: ✅ 18/18 (was 17/18)
- DBIC t/52leaks.t: ⚠ 11/20 (was 11/20 with 9 real fails — unchanged)
- Perl core destroy semantics via sandbox: ✅ 213/213
- refcount_diff.pl on phase1_verify corpus: ✅ 10/10 match Perl
- make test-bundled-modules: ✅ no regressions
