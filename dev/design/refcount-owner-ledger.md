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

## Current Status (2026-11-19)

Issue #2043 remains the active delivery for removing memory-sweep lifecycle
decisions; #1649 is its historical origin. Work is continuing on local WIP
branch `wip/issue2043-sweep-20261119-231323`, checkpointed at
`99237d06ef`. The focused DBIx refcount correction is uncommitted; the full
unit and release-acceptance gates are still pending, so this candidate is not
ready for UAT.

The current implementation maintains O(1) owner-kind counts for the hot
`PACKAGE_AGGREGATE_SLOT`, `ACTIVE_CODE`, and `CODE_SLOT` checks. Transient
untracked argument ownership now has a maintained count, and identity checks
iterate a live narrow index without copying the entire ledger. A focused CPAN
test, `Excel-Writer-XLSX/t/regression/chart_axis28.t`, passed with JFR enabled.
For that same test invocation, after the transient count and append buffer,
owner-slot visits were 94,206. After adding the CODE-slot index, they were
62,353. The earlier recording sampled 10 allocations through
`ownerSlotSnapshot()` (about 14.6 MB weighted); the latest recording sampled
none through that path. These are focused, unpaired samples, not a
whole-distribution throughput claim. Artifacts:
`/private/tmp/issue2043-append-buffer-chart-axis28.jfr`,
`/private/tmp/issue2043-code-slot-index-chart-axis28.jfr`, and the matching
`*-metrics-*.json` files.

The same test recorded 541 deferred-release appends and drains, 356 handoffs,
six frame-queue allocations, 318 frame-queue reuses, zero per-boundary queue
allocations, and zero boundary-order regressions. The buffer uses primitive
boundary marks and retains the existing newest-boundary-first, LIFO-within-
boundary order; an ordered fallback preserves behavior if a boundary regresses.
JFR on the focused test still shows substantial non-lifecycle startup and
compiler allocation. Full-workload allocation impact remains unproven.

The latest unfiltered unit gate passed after moving cleanup-target probes behind
the positive-count/no-current-owner guards: `nice -n 19 timeout 3600 make
test-unit`, 7m03s; log `/private/tmp/issue2043-lazy-prescan-unit.log`. The
focused `Excel-Writer-XLSX/t/regression/chart_axis28.t` test also passed. It
recorded 61,916 owner-slot visits, 541 deferred-release appends/drains, 356
handoffs, 1,467 recursive-release roots across 360 worksets, and 405
cleanup-target probe roots across 74 reusable worksets. Compared with the
preceding candidate this avoids 94 probe traversals and cuts probe workset
initializations by 41%; compared with one queue/set per probe root it uses 74
worksets instead of 499, an 85% reduction for this focused path. The probe
also replaces the separate direct weak-slot pass. Owner-slot visits stayed
near 62,000, so this does not establish a scan reduction.

Focused CPAN reruns exposed a `B::REFCNT` undercount for a nested hash-slot
value forwarded through a Perl helper during `DESTROY`: the aggregate's one
counted owner was reported, but the helper's active `@_` value had no owner
slot. `RuntimeCode.isActiveCallArgumentValue` now recognizes that active Perl
argument without counting the B/Internals probe frames or a DESTROY invocant.
The permanent system-Perl-validated regression
`refcount/b_refcount_destroy_aliased_hash_slot_argument.t` passes on both
PerlOnJava backends, and the previously failing DBIx::Class
`t/60core.t` and `t/101populate_rs.t` now pass. Logs:
`/private/tmp/issue2043-b-refcount-fixed.log`,
`/private/tmp/issue2043-b-refcount-fixed-interpreter.log`,
`/private/tmp/issue2043-dbix-60core-after-brefcount.log`, and
`/private/tmp/issue2043-dbix-101populate-after-brefcount.log`. The unfiltered
unit gate and full release-acceptance gate remain outstanding.

The focused JFR recording lasted four seconds. Its sampled
`IdentityHashMap.init` share was 11.47%, versus 8.05% in the preceding short,
unpaired recording; the samples are too variable to claim an overall JFR
allocation or throughput reduction. The deterministic pre-scan counters show
the workset reuse directly, while the broader allocation impact remains to be
measured on longer matched workloads. Artifacts:
`/private/tmp/issue2043-cleanup-probe-chart-axis28.jfr`,
`/private/tmp/issue2043-cleanup-probe-chart-axis28-metrics-29217.json`,
`/private/tmp/issue2043-cleanup-probe-chart-axis28-allocation-view.txt`, and
`/private/tmp/issue2043-cleanup-probe-unit-extended.log`. The latest guarded
probe run is at `/private/tmp/issue2043-lazy-probe-chart-axis28.jfr`,
`/private/tmp/issue2043-lazy-probe-chart-axis28-metrics-42134.json`, and
`/private/tmp/issue2043-lazy-prescan-unit.log`.

Earlier `Excel::Writer::XLSX` JFR slices attributed weighted estimates of
95.1 GB through full owner snapshots and 46.2 GB through the filtered untracked
snapshot. These are sampled estimates from separate slices, not literal heap
sizes. The profile-only CPAN run on the earlier candidate was stopped after
about 70 minutes when diagnostics had accumulated 73.5 million owner-slot
visits and 1.26 million frame/boundary queue allocations. It had no observed
test failures, stopped at `t/regression/hyperlink52.t`, and is not acceptance
evidence. Historical profiles remain at
`/private/tmp/issue2043-excel-writer-xlsx-{early,followup,indexed}.jfr`; their
sample and allocation summaries use the matching `*-samples.txt` and
`*-alloc.txt` suffixes.

The direction is to finish sweep removal while tightening these hot paths,
without a wholesale reference-count rewrite:

- Keep per-frame LIFO deferred cleanup. Measure whether release boundaries
  are monotonic as frames hand off. The current append-only buffer uses
  primitive boundary marks and a pooled frame queue; an ordered fallback
  handles unexpected regressions.
- Maintain O(1) counts for owner kinds used by common yes/no queries, updating
  them on slot add, removal, and reclassification. Cached counts now cover the
  hot `PACKAGE_AGGREGATE_SLOT`, `ACTIVE_CODE`, and `CODE_SLOT` queries. The
  transient untracked-owner yes/no check is also O(1). Traverse a live narrow
  identity index only when the exact slots are required.
- Let a dying array or hash release its own outgoing element edges directly,
  instead of repeatedly searching the referent's full incoming-owner ledger.
- Minimize allocations as a required part of sweep removal: keep queues and
  identity worksets reusable within a cleanup frame, allocate scratch lazily,
  and avoid allocating one collection per candidate element. Track exact
  workset initializations and owner-slot visits separately; use paired JFR and
  workload measurements before claiming a total allocation or speedup result.
- Measure each change separately with opt-in counters for deferred-release
  appends/drains, queue reuse/allocations, and owner slots visited, keeping
  counters off by default.
  Compare CPU samples, sampled allocations, and workload time on the same
  `Excel::Writer::XLSX` run. Remove per-boundary queue allocations; use an
  append-only buffer with integer marks only if the observed release order is
  strictly nested, otherwise use one ordered queue per frame. Treat allocation
  reduction as a required result alongside lower scan cost. Keep an optimization
  only when those costs fall and the hash, array, scalar-reference, and
  nested-frame regressions pass.

The current work has completed the transient-owner count, per-frame append
buffer, narrow CODE-slot index, reuse of recursive release and cleanup-target
probe worksets across roots from dying arrays and hashes, and a guard that
skips the probe when the aggregate is already at zero or has a current Perl
owner. The new
`AggregateOutgoingEdgeCleanupTest` covers hash-to-array and array-to-hash
cleanup, plus a nested aggregate that remains live through another Perl owner.
The exceptional positive-count/no-current-owner case still traverses nested
values, and cleanup still performs owner checks; the next phase is to use the
dying aggregate's outgoing owner slots to avoid those repeated traversals while
preserving weak-reference, shared-owner, and destructor-rescue semantics. Then
profile the full `Excel::Writer::XLSX`
target and run the CPAN release-acceptance gate on one immutable candidate,
stopping at an actionable failure and fixing it before continuing. The GitHub
API was unreachable during this update, so live issue #2043 and PR #1938 state
still needs reconciliation after access returns. See the
[ownership handoff](perl-ownership-rewrite.md) and
[issue #2043](https://github.com/PerlOnJava/issues/2043).

| Date | Phase delivered | Evidence | Remaining |
| --- | --- | --- | --- |
| 2026-11-19 | Cached hot owner-kind counts, transient-owner count, live identity indexes, pooled append-buffer cleanup, and reusable release/pre-scan worksets for dying aggregates | Unit gate and focused `chart_axis28.t` counters/JFR linked above | Remove repeated nested pre-scans and owner-slot scans; measure allocation and CPU impact on the full target; finish CPAN acceptance |
| 2026-11-19 | Correct `B::REFCNT` for nested hash-slot arguments in `DESTROY`, restoring DBIx::Class schema/source cleanup | New system-Perl-validated regression and passing direct `t/60core.t` / `t/101populate_rs.t` runs above | Run full unit and CPAN release-acceptance gates; stop at the next actionable failure and fix it |

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
