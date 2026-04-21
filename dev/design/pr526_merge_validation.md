# PR #526 — feature/refcount-perf-combined merge validation

Rebase of feature/walker-hardening-j3 onto master (Apr 2026 + 143 commits)
combining PRs #508 / #518 / #523 into a single up-to-date PR.

Merge commit: `5611e34b2`
Fix-up commit: `1869badd2` (overload fallback=undef regression)

## Test results

| Suite | Pre-merge (660aa9e68) | Post-merge + fix (1869badd2) | Notes |
|-------|-----------------------|------------------------------|-------|
| unit tests (`make`) | 1 fail: destroy_eval_die.t#4 | 1 fail: destroy_eval_die.t#4 | Pre-existing, unrelated |
| DBIx::Class | (not measured) | 13858/13858 (after fix) | `txn.t#90` regressed, fixed |
| Template-Toolkit | 2920/2920 | 2920/2920 | PASS |
| Moo | 841/841 | 841/841 | PASS |
| Memoize | (expected) | 4/16 files fail | All documented as known in 10631d752 (threadsafe, tie, tie_db, tie_storable) |
| Date::Calc | 3005/3005 | 3005/3005 | PASS (matches 4ddfe3434) |
| Math::Base::Convert | 5350/5350 | 5350/5350 | PASS (matches d1b5c6bc5) |
| List::MoreUtils | 1 fail: indexes.t#9 | 1 fail: indexes.t#9 | Pre-existing weak-ref issue |
| AnyEvent | env-dependent | env-dependent | Mixed; sub-test harness has local env issues unrelated to merge |
| bundled modules (`make test-bundled-modules`) | ? | 175/176 (Text-CSV 55_combi.t) | Runner flake — passes when run directly |

## Regressions found & fixed

### DBIC `t/storage/txn.t` test 90 — "One matching warning only"

**Symptom:** Test expected `@w == 1`, got `@w == 0`. The warning path
depends on eq throwing "Operation 'eq': no method found" for an
overloaded-stringify-only class (DBICTest::BrokenOverload) with
`fallback=undef`.

**Root cause:** Master's 4ddfe3434 refactored overload autogen to
direct->cmp->nomethod via `OverloadContext.tryTwoArgumentNomethod`. But
that method only throws for the explicit `fallback=>0` case. For
`fallback=>undef` (the default), it returns null and the caller falls
through to plain stringification — **silently**. Real Perl 5 throws in
this case.

HEAD's deleted `throwIfFallbackDenied` helper covered the
`fallback=>undef` case; master's refactor lost this semantic.

**Fix (1869badd2):** Keep master's newer autogen order but re-add
`throwIfFallbackDenied` after `tryTwoArgumentNomethod` returns null, so
fallback=undef still throws per Perl semantics. Verified against real
Perl 5 with a minimal reproduction.

## Pre-existing issues (NOT introduced by this PR)

1. `unit/refcount/destroy_eval_die.t` test 4 — "DESTROY fires in LIFO
   order during eval/die unwinding". Both pre- and post-merge report
   FIFO instead of LIFO order. Confirmed via a separate worktree at
   `660aa9e68`.

2. `List::MoreUtils t/pureperl/indexes.t` test 9 — "weakened away".
   Weak-ref test expects `undef`, gets a SCALAR. Present on pre-merge
   HEAD too.

3. `Text-CSV t/55_combi.t` — passes when run directly (`./jperl`) but
   the Gradle bundled-modules runner marks it as failed. Runner issue,
   not a test failure.

4. AnyEvent — mixed results with infrastructure-level failures
   (NoClassDefFoundError for WarnDie / `app.cli.Main`,
   SSL cert missing). The actual require-FILE fix from master
   (`40b535a3d` / `08_idna.t`) passes.

## Merge conflict-resolution decisions (informed by master commit messages)

| File | Decision | Rationale |
|------|----------|-----------|
| `Configuration.java` | `--theirs` | Regenerated at build time. |
| `Opcodes.java` | Renumber ours 481..484; keep master's 468..480 | Master's `b75d5e04e` added bitwise-assign opcodes; our 4 additions post-date master's range. |
| `InterpreterState.java` | Drop HEAD's `setCurrentPackage` (unused); keep master's `setCurrentPackageStatic` | Only master's method has callers (`EmitOperator` INVOKESTATIC site from `40b535a3d`). |
| `EmitOperator.java` | Take master's INVOKESTATIC emit; bind to `setCurrentPackageStatic` | Required for `require FILE` correct-package fix (`40b535a3d`). |
| `StatementResolver.java` | Auto-merge preserves master's `hoistMyFromAssignment` helper (`3bfaffda3`); manually keep HEAD's richer my/our/state commentary for if/unless (`c8d065da0`) | Both features preserved. |
| `CompareOperators.java` | Take master's eq/ne refactor; re-add `throwIfFallbackDenied` after `tryTwoArgumentNomethod` (fix in 1869badd2) | Merges master's new autogen order with HEAD's Perl-5-correct fallback=undef throw. |
| `RuntimeGlob.java` | Take master's simpler `*Pkg::{HASH}` stash lookup | `GlobalVariable.getGlobalHash` itself now strips `main::`, making HEAD's ad-hoc strip redundant (`9f38ef550`). |
| `RuntimeCode.java` (6 regions) | Iterative trampoline (HEAD) + VOID mortalize+flush (HEAD) + setFromListAliased (HEAD) + pristineArgsStack (master) + hasArgsStack (master) + EMPTY_ARGS_SNAPSHOT fast path (HEAD, re-typed to List<RuntimeScalar>) + tailCallReentry flag gating inTailCallTrampoline (new, bridges HEAD iterative ↔ master recursive semantics) | All master features preserved; no HEAD perf features lost. |

## Open follow-up

- [ ] Document the `destroy_eval_die.t` LIFO failure separately — it's
      a pre-existing refcount/scope-exit ordering issue independent of
      this merge.
- [ ] Text-CSV bundled-runner flake — investigate whether the Gradle
      `testModule` task uses an env setup that causes 55_combi.t to
      report a test 26 failure when it doesn't actually fail.
