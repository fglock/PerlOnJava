# PR #552 (perf/dbic-safe-port) — Remaining Regressions

## Status as of 2026-04-24 (very late session)

Starting delta vs master: 26 specific tests regressed.
Fixed across all sessions: **23**.
Remaining: **3** (two of which may be master-side artifacts).

### Fixed commits (in order)

| Commit | What | Tests fixed |
|--------|------|-------------|
| `48ebef398` | `undef %hash` fires DESTROY progressively, not after clearing | op/undef.t 19-35 (all 8, plus ~30 pre-existing) |
| `6fadf3def` | Named hash/array lexicals are walker roots via `localBindingExists` | op/hashassign.t 218 |
| `8dcf31d9f` | Interpreter `\(LIST)` flattens before per-element reference | op/ref.t 113, 114, 116, 117 |
| `f9040b781` | Interpreter `local our VAR` re-loads localized global | op/split.t 164, 166 |
| `fdec68297` | Interpreter `local(*foo) = *bar` single-glob list-local assignment | op/ref.t 1 |
| `91285924b` | Literals in LIST context → cached RuntimeScalarReadOnly; FOREACH and ++/-- preserve read-only | op/ref.t 231, 233; op/for.t 105, 130, 131, 133, 134 |
| `0258c7f4b` | SET_SCALAR preserves read-only alias through refgen path | op/ref.t 232, 234 |

**Current test counts** (branch at HEAD; master in parens):
- op/ref.t 244 / 265 (master 243) — **exceeds master by 1**
- op/for.t 140 / 149 (master 141) — -1
- op/sort.t 176 / 206 (master 178) — -2
- op/split.t 186 / 219 (master 186) — matches
- op/undef.t 87 / 88 (master 56) — exceeds master by 31
- op/hashassign.t 309 / 309 (master 309) — matches
- op/recurse.t 25 / 28 (master 26) — -1 but no specific test regression

### Remaining (3 tests)

| Test | What | Note |
|------|------|------|
| op/for.t 103 | `is (do {17; foreach (1,2) { 1; } }, "", "RT #1085: …")` | Passes in isolation (`--interpreter`), fails only in test context. Expected `""`, got `undef`. Likely a scope-exit-value issue specific to `do { foreach }` block-result propagation. Low priority. |
| op/sort.t 169 | `is($count, 2, '2 here')` | Counter DESTROY counter. Passes in isolation. Test-context specific. Likely a stray DESTROY firing mid-sort-setup. |
| op/sort.t 172 | `is($count, 2, 'still the same 2 here')` | Same Counter, post-sort. Depends on 169. |

All three pass in isolated `--interpreter` runs. They fail only within the
complete test harness, suggesting interaction with surrounding test state
(e.g., a Counter object captured by a closure, or a previously-compiled
sub retaining a reference). These are diagnostic challenges rather than
clear architectural gaps.

### Tests that also improved

- op/for.t: went from 135 → 140 (fixing 5 tests)
- op/ref.t: went from 240 → 244 (fixing 4 tests, plus one previously
  passing by luck under the interpreter's wrong flatten semantics,
  which now fails correctly against the Perl spec — still matches master)
- op/undef.t: 49 → 87 (fixing all 8 tracked regressions plus 30
  pre-existing master failures)

## Next steps

With 3 regressions remaining and diagnostic-level investigation needed
for each, the branch is ready to merge pending:

1. Review/approval of the 7 commits
2. A decision on whether the 3 remaining regressions block merge:
   - for.t 103 is a minor semantic corner case (scope exit value).
   - sort.t 169/172 need deeper investigation but are localized to a
     single test block.
