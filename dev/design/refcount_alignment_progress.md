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
