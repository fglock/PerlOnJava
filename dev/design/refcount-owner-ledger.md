# Perl-Exact Reference Ownership and Closure-Pad Lifetime

**Status:** Design in progress  
**Issue:** [#1132](https://github.com/fglock/PerlOnJava/issues/1132)  
**Related architecture:**
[Weaken & DESTROY](../architecture/weaken-destroy.md),
[Refcount alignment plan](refcount_alignment_plan.md), and
[Refcount alignment progress](refcount_alignment_progress.md)

## Objective

Align PerlOnJava's observable reference counts, weak-reference lifetime, closure
capture lifetime, cycles, and deterministic `DESTROY` behavior with system Perl.
The implementation must distinguish real Perl ownership from JVM reachability
and compiler-generated capture metadata.

Issue #1132 established the owner-ledger work. Issue #2043 is the current
delivery for removing lifecycle decisions based on memory sweeps; #1649 is its
historical origin. The design replaces selective-refcount heuristics with
explicit, auditable owner tokens.

## Current Status (2026-11-21 — handoff)

**Delivery issue:** [#2043](https://github.com/fglock/PerlOnJava/issues/2043)

**Target PR:** [#1938](https://github.com/fglock/PerlOnJava/pull/1938)
**Goal:** deliver Perl-exact owner counts, weak-reference lifetime, and deterministic `DESTROY` behavior without memory-sweep lifecycle decisions. The immediate open work is the DBIx::Class diamond-prefetch shutdown leak and the repository failures listed below.

### Checkpoint and diagnosis

The handoff branch is `wip/issue2043-handoff-20261121-154515` with a clean tree after this document update. Runtime source is checkpointed at `5bde29378c` (`wip: snapshot proxy owner recheck candidate`), preceded by `4d99626026`, which removes the local session log from version control. `log.txt` is now ignored. These are WIP preservation commits, not a passing acceptance result. The source candidate adds an identity index of untracked proxy value-owner slots on their aggregate lvalue parent. It rechecks those exact slots after cleanup-stack unregistration, active lexical-owner release, and deferred aggregate count transitions. This follows actual Perl owner release boundaries and does not trigger a broad reachability scan.

The original DBIx symptom was a ResultSet proxy whose lvalue cell survived while its parent hash had runtime `refCount=0`, no Perl owner, and no registration or persistence. The proxy owner had been accepted while the parent was live and was never revalidated after the parent died. Cleanup-stack unregistration was the boundary that made the focused DBIx reproduction pass.

### Permanent regression evidence

The DBIx tests are permanent project-owned Perl tests under `src/test/resources/unit/refcount`: `diamond_prefetch_cursor_leak.t`, `diamond_prefetch_cursor_leak_permutations.t`, and `diamond_prefetch_shutdown_leak.t`. They require `DBIC_CLASS_TEST_ROOT` and skip cleanly otherwise. Before the proxy fix, JVM and interpreter each failed four weak-witness checks (Schema, Storage, DBH, combined), while system Perl collected the witnesses. With the candidate, the shutdown case passes 768 functional checks and the permutation case passes 772 assertions on both backends; DBICTest LeakTracer reports no retained references. The duplicate-aggregate-owner and nested-aggregate-destruction regressions also pass on both backends. Focused build and DBIx `t/60core.t` passed after the frame-drain order adjustment. Logs are `/private/tmp/issue2043-proxy-owner-final-build.log`, `/private/tmp/issue2043-final-interpreter-shutdown.log`, `/private/tmp/issue2043-final-interpreter-permutations.log`, `/private/tmp/issue2043-final-jvm-shutdown.log`, `/private/tmp/issue2043-final-jvm-permutations.log`, and `/private/tmp/issue2043-dfs-array-reverse-dbix-60core.log`.


### Completed milestones

| Date | Delivered scope | Evidence |
|---|---|---|
| 2026-11-19 | Cached hot owner-kind counts, narrow identity indexes, and reusable cleanup/probe worksets | Owner-ledger unit gate passed; earlier performance measurements remain inconclusive and are not a broad speedup claim. |
| 2026-11-19 | Correct nested hash-slot `B::REFCNT` reporting during `DESTROY` | System-Perl-validated regression; DBIx `t/60core.t` and `t/101populate_rs.t` passed. |
| 2026-11-21 | Retain interpreter foreach source owners through normal and `last` exits (`4edc5faa51`) | System Perl, JVM, interpreter, and focused DBIx diamond regression passed. |
| 2026-11-21 | Release overwritten temporary call-argument owners, including Test2 Amnesty facets (`eb8907f362`) | System Perl, JVM, and interpreter regressions passed; source build succeeded. |

The last full gate was `nice -n 19 timeout 3600 make`, log `/private/tmp/issue2043-proxy-owner-restored-diagnostics-make.log`. Java production and test sources compiled, Joni tests ran, and unit shard 3 reported 684 tests with three failures. The overall make did not complete; the one-hour timeout killed it (`EXIT 124`, make reported signal 143). No PerlOnJava2 Gradle workers remained after exit. The user expects `make` to finish in about ten minutes and suspects the failures caused the stall; treat that as an investigation lead, not a proven cause. Do not repeat a one-hour wait. After fixing the actionable failures, use `nice -n 19 timeout 900 make`; if it still stalls, inspect Gradle task/worker progress and cancellation instead of extending the timeout blindly.

### Current full-gate failures

| Test | Evidence | Next action |
|---|---|---|
| `unit/refcount/destroy_eval_die.t` | System Perl passes all 10 checks. Current JVM and interpreter fail only the LIFO assertion: got `first, second`, expected `second, first`. Logs: `/private/tmp/issue2043-systemperl-destroy_eval_die.log`, `/private/tmp/issue2043-jvm-destroy_eval_die.log`, `/private/tmp/issue2043-interpreter-destroy_eval_die.log`. | Trace exception-scope owner releases and restore Perl's LIFO destruction order without regressing DBIx `t/60core.t` or the nested-aggregate LIFO regression. The existing tracked test is already a red test; do not alter it. Add another permanent test first only if diagnosis reveals a distinct behavior it does not isolate. |
| `unit/refcount/owner_trace_snapshot.t` | System Perl skips because the diagnostic is PerlOnJava-only. Current JVM and interpreter fail assertion 1: the snapshot inserts `state=LIVE`, `destroyFired`, `needsReDestroy`, and `hasWeakRefs` between `refCount=...` and `active=...`, violating the expected stable prefix. Assertions 2–3 pass. Logs: `/private/tmp/issue2043-systemperl-owner_trace_snapshot.log`, `/private/tmp/issue2043-jvm-owner_trace_snapshot.log`, `/private/tmp/issue2043-interpreter-owner_trace_snapshot.log`. | Preserve the diagnostic format consumed by the tracked test, likely by moving the extra state fields after the `active` owner list. Keep the test unchanged and run it on both backends. |
| `unit/thread_shared_queue_clone.t` | Full parallel make reported `ConcurrentModificationException`. System Perl passes; focused one-shot JVM and interpreter runs both pass. Logs: `/private/tmp/issue2043-systemperl-thread_shared_queue_clone.log`, `/private/tmp/issue2043-jvm-thread_shared_queue_clone.log`, `/private/tmp/issue2043-interpreter-thread_shared_queue_clone.log`. | Treat as unreproduced/flaky so far. Repeat the focused test under bounded, controlled repetitions and inspect the exception stack before changing code. The tracked test already covers the behavior; add coverage only if a distinct race is found. |

### Complete step-by-step delivery plan

1. **Resume from this checkpoint.** Recheck branch/status and the live state, comments, and linked work for issue #2043 and PR #1938 before editing. Keep the current WIP branch as recovery; do not push it.
2. **Use TDD for every distinct failure.** Keep existing tests unchanged. Where coverage is missing, add a focused permanent test under `src/test/resources/unit` or the owning Java unit-test tree before production changes. For Perl tests, run system Perl first and save complete output. Prove the regression on the unfixed parent and preserve the failure log.
3. **Fix the DESTROY ordering failure.** Trace the exception-unwind path from `MyVarCleanupStack.unwindTo` through deferred release draining. Make the smallest change that produces LIFO `DESTROY` order on JVM and interpreter. Re-run `destroy_eval_die.t`, `issue_2043_nested_aggregate_destroy_lifo.t`, the diamond tests, and DBIx `t/60core.t` after each change.
4. **Fix the owner-trace format failure.** Keep the tracked assertion/API stable without changing the test. Verify `owner_trace_snapshot.t` on JVM and interpreter and preserve any diagnostic fields needed by other consumers.
5. **Resolve the thread-clone report.** Reproduce the `ConcurrentModificationException` with a bounded focused run and capture the stack. If it is reproducible, add a narrower permanent test first, validate with system Perl, then fix and verify both backends. If it does not reproduce, record the repeated evidence and investigate why the parallel gate reported it.
6. **Reconfirm the proxy-owner fix.** Run the three diamond regressions and supporting refcount tests on both backends, with system-Perl validation. Preserve the pre-fix/fixed logs. Confirm the optional DBIx dependency skip behavior remains clean.
7. **Complete DBIx acceptance.** Run `t/prefetch/diamond.t` and `t/60core.t`, then resume the broader DBIx::Class gate from its first actionable failure. A previous broad DBIx run ended with 52 failing subtests; focused success is not full-module acceptance. For each #2043 behavior, add permanent test coverage first and check system Perl before the fix.
8. **Complete remaining compatibility gates.** Run the outstanding focused Moo and Template Toolkit retests and remaining CPAN acceptance checks. Stop at actionable failures, add tests first, then fix; capture full outputs, exit status, command, and source commit.
9. **Review and document the final source.** Run `git diff --check`, review all runtime/support code, verify tests are in `src/`, and remove temporary diagnostics that are not needed. Evaluate whether the existing #2043 changelog bullets cover the proxy-owner behavior; add a concise Work in progress bullet if needed. This document links directly to #2043. Run `make check-links` and offline link checking for changed Markdown.
10. **Commit and run immutable gates.** Commit the reviewed candidate before the final repository gate. Run `nice -n 19 timeout 900 make` on that exact source commit and focused acceptance gates. If any source changes follow, commit them and rerun affected tests plus `make`.
11. **Integrate with the existing PR safely.** PR #1938 was last confirmed open at remote head `75dd7d542f1f86bff930beb1c5779be74fa27955`; recheck before acting. This WIP branch diverges substantially from the PR head, so never push it directly or force-update the PR from it. Create an isolated branch/worktree from the latest PR head, transplant only intended #2043 code, regression tests, design update, and changelog changes, preserve existing PR work, resolve conflicts by inspecting both sides, and validate the exact PR candidate.
12. **Publish and cross-link.** Only after required gates pass, push to the existing PR #1938 branch and verify it remains open with the expected files. Add a reciprocal link from issue #2043 to a stable GitHub permalink for this design document at the tested PR commit; append it without replacing unrelated issue content. Verify the document-to-issue and issue-to-document links.
13. **Close delivery.** Record the final PR commit and evidence here, wait for review, and complete merge through PR #1938. After merge, verify issue #2043 is resolved and replace this handoff with the next current state or mark the phase complete.

Preflight backups of the dirty source state are `/tmp/wip-unstaged-20261121-144256.patch`, `/tmp/wip-staged-20261121-144256.patch`, `/tmp/wip-status-20261121-144256.txt`, and the later post-timeout snapshot `/tmp/wip-unstaged-20261121-154419.patch`, `/tmp/wip-staged-20261121-154419.patch`, `/tmp/wip-status-20261121-154419.txt`.

## Semantic Ownership Model

```text
RuntimeCode --semantic capture--> RuntimeScalar pad cell --strong slot--> referent
```

These edges have different meanings:

- The declaring scope and semantic closure captures own the pad cell.
- A strong pad cell owns exactly one referent token.
- A weak pad cell owns no referent token.
- Multiple closures can share one pad cell without changing the referent count.
- Conservative compiler metadata and generated JVM object fields are not Perl
  roots and own neither the pad nor its referent.

### Required invariants

1. A live pad cell owns at most one token for its current referent.
2. A strong, live pad cell owns exactly one token when its referent is tracked.
3. `semanticCaptureCount` affects pad lifetime, not referent cardinality.
4. Metadata captures never affect Perl lifetime.
5. Reassignment releases the old pad token before acquiring the new one.
6. `weaken()` releases the pad token and captures do not recreate it.
7. The final pad owner releases the token exactly once.
8. Lifecycle decisions follow owner slots directly; Java reachability does not create or retire Perl owners.
9. Java reachability alone cannot create a Perl owner.
10. Strong Perl cycles remain alive until a strong edge is explicitly broken.

## Runtime State

The scalar and closure state should make ownership explicit:

- `slotOwnsReferent`: the scalar slot owns one strong referent token. The
  existing `refCountOwned` field can initially implement this state, but its
  meaning must be narrowed and documented.
- `scopeOwnerAlive`: the declaring lexical scope still owns the pad cell.
- `semanticCaptureCount`: number of live Perl closures sharing the pad cell.
- `metadataCaptureCount`: conservative or diagnostic captures that do not
  affect Perl ownership.
- `CaptureBinding`: a `RuntimeCode` record pairing a pad cell with capture
  provenance. Each semantic binding is released exactly once.

`scopeExited` may remain during migration, but it must become derived lifecycle
state rather than an ownership heuristic.

## State Transitions

| Event | Pad ownership | Referent ownership |
|---|---|---|
| Strong lexical assignment | Scope owns pad | Pad owns one token |
| First semantic closure capture | Add pad owner | Unchanged |
| Additional closure capture | Add pad owner | Unchanged |
| Metadata capture | Unchanged | Unchanged |
| Declaring scope exits | Remove scope owner | Retain token while semantic captures remain |
| One of several closures is released | Remove one pad owner | Unchanged |
| Final closure is released after scope exit | Pad becomes dead | Release one token |
| Captured scalar is reassigned | Pad remains live | Release old token; acquire one new token |
| Captured scalar is weakened | Pad remains live but weak | Release token |
| Captured scalar is unweakened | Pad remains live and strong | Acquire one token |

A borrowed scalar promoted into a semantic captured pad acquires one pad-slot
token. Further closures sharing that pad do not acquire additional tokens.

## Exact Capture Provenance

Both execution backends must produce equivalent capture descriptors.

### JVM backend

- Attach semantic `CaptureBinding` records during compilation.
- Do not infer Perl ownership solely from reflected generated fields.
- Keep generated closure objects and compiler metadata opaque to ownership.

### Interpreter backend

- Distinguish lexicals referenced by the closure from conservative snapshots of
  all visible registers.
- Preserve `eval STRING` access to lexicals without making unrelated register
  metadata permanent owners.
- Release semantic bindings through the same `RuntimeCode.releaseCaptures()`
  path as the JVM backend.

### Threads

- Reconstruct slot ownership and semantic captures in an independent ithread
  snapshot from capture descriptors.
- Do not infer cloned ownership from the source scalar's transient
  `refCountOwned` value.

## Authoritative Owner Ledger

Tracked referents need an authoritative count separate from conservative JVM
reachability and historical selective-count drift. Every owner-producing path
must identify an owner kind:

- Scalar or pad slot
- Aggregate element
- Package global
- Glob or stash slot
- CODE storage
- Tie wrapper
- Pad constant
- Temporary call hold
- Explicit destruction rescue
- Other runtime-specific strong Perl edges

Debug validation should assert:

```text
refCount == sum(authoritative owner tokens)
```

Temporary aliases, generated fields, and conservative captures must not produce
authoritative tokens. During migration, legacy or uncertain holds must be
recorded separately so they cannot prevent weak cleanup indefinitely.

## Lifecycle Ownership and Diagnostic Queries

Weak-reference cleanup and destruction follow current owner-slot identities. A
referent remains alive while any Perl strong slot remains, including edges in
strong cycles. Releasing the final slot performs cleanup through the ordinary
container and lifecycle paths.

No root traversal, snapshot, or sweep may change reference counts, weak
observers, or destruction timing. The reachability walker and scalar registry
are removed; lifecycle code uses owner-slot identities for final-release
decisions. Temporary leases and suspended-frame owners remain explicit slots.

## Current Work

1. Replace repeated recursive target pre-scans and full incoming-ledger checks
   with cleanup driven by the exact outgoing element slots of a dying array or
   hash. Preserve untracked nested aggregates, shared live owners, weak
   observers, and destructor rescue behavior.
2. Measure owner-slot visits, queue/workset allocations, sampled allocations,
   and elapsed workload time separately on the same `Excel::Writer::XLSX`
   input. Do not infer scan savings from the workset-allocation improvement.
3. Run the hash, array, scalar-reference, and nested-frame regressions, then
   profile and complete the `Excel::Writer::XLSX` target and full CPAN
   release-acceptance gate on the final immutable candidate. Stop a gate on an
   actionable failure, preserve its log, fix the cause, and restart from a new
   immutable candidate.
4. Reconcile the validated candidate with PR #1938 and update the PR only after
   the required gates pass.

## Regression Matrix

Every new or changed Perl test must pass system Perl before PerlOnJava is used
as an implementation oracle.

### Pad and closure ownership

- Zero, one, and two closures sharing one lexical retain one referent owner.
- Independent lexical copies produce independent referent owners.
- Scope exit preserves a referent until the final closure is released.
- Releasing one of two closures does not release the shared pad token.
- The final release clears weak observers and invokes `DESTROY` exactly once.
- Nested closures and returned closures share the correct pad cell.
- Argument aliases and borrowed captures are promoted exactly once.
- Self-capturing CODE and strong closure cycles match Perl behavior.

### Mutation

- Weakening a captured slot makes it non-owning.
- Unweakening a live captured slot restores one owner.
- Reassignment releases the old referent and retains the new referent.
- Repeated reassignment cannot release a stale referent through deferred work.
- Ordinary weak references clear when no strong owner exists.

### Capture provenance

- JVM and interpreter closures report equivalent semantic captures.
- Conservative interpreter register snapshots do not retain unrelated objects.
- `eval STRING` retains only the Perl-visible lexical environment for the
  correct code-object lifetime.
- Generated JVM objects and reflected fields do not become Perl roots.
- ithread clones reconstruct independent ownership.

### Existing compatibility guards

Preserve and run:

- `unit/weak_localized_cache_lifetime.t`
- Closure-cycle, captured-scalar, weak-callback, Sub::Quote, and Sub::Defer tests
  under `unit/refcount/`
- Focused Moo and Sub::Quote tests
- DBIx::Class leak-tracer and weak-registry tests
- Exact-refcount tests using `B::REFCNT` or `Test2::Tools::Refcount`

## Historical issue #1132 ecosystem acceptance

Run on JVM and interpreter backends with `timeout` and complete output logs:

1. The issue #1132 Future reproducer prints `completed=1` without a lost-sequence
   warning.
2. `Future::Utils::repeat` prints `ready=1 result=final`.
3. `Net::Async::HTTP` `t/05redir.t` completes without warning or timeout.
4. Full Future and Net::Async::HTTP distributions complete.
5. Cookie and Bzip2 failures remain separately classified.
6. Focused Moo, Sub::Quote, and DBIx::Class leak tests remain green.

## Final Gates

- Run every new or changed Perl test with system Perl first.
- Retain evidence that the focused issue test fails on the unfixed parent.
- Run focused tests on both PerlOnJava backends.
- Run `make` with complete captured output and zero failures.
- Run `make check-links` and offline `lychee` where required.
- Verify no unexpected high-CPU PerlOnJava JVM remains.
- Integrate current `master`, rerun focused and full gates on the exact commit,
  and only then mark the issue-fix PR ready.

## Progress Tracking

### Current checkpoint

The current status and evidence are maintained in the
[ownership rewrite handoff](perl-ownership-rewrite.md#current-handoff-2026-11-15).
The candidate has removed production reachability decisions and sweep
infrastructure. The DBIx and Test2 owner transitions have focused regressions;
the merged candidate passes the focused DBIx leak tests and direct survivor
checks. CI passes on the published candidate and PR #1938 is ready for human
review. The broader 37-program failure inventory is retained as historical
integration context. #2037 and #2034 remain separate post-merge follow-ups.

### Historical handoff: issue #1132 / PR #1204

This section records the earlier issue-linked PR
[#1204](https://github.com/PerlOnJava/pull/1204) handoff. Its branch was
`fix/issue-1132-closure-lifetime`; retained commits were `5b693177f`,
`1702edb70`, `4832540d6`, `9fcab6e81`, and `87b4fdee3`. These historical
branch instructions do not define the current continuation.

The Net::Async::HTTP 0.50 distribution is installed through `jcpan` in the
local CPAN cache. JVM and interpreter `t/30timeout.t` and `t/32remove.t` now
pass. Preserve both focused ownership regressions when evolving the ledger.

### Open Questions

- Can additional compiler-created aggregate temporaries be made explicit in
  the owner ledger, rather than relying on opcode-specific transfers?
