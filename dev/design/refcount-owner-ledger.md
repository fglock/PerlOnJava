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

## Current Status (2026-11-21)

Issue #2043 remains active. PR #1938 is open at remote head
`75dd7d542f1f86bff930beb1c5779be74fa27955`; the local continuation is on
`wip/issue2043-resume-20261121-085022` at `8d6468d348`, with the latest owner
trace changes being checkpointed. This candidate is not on the PR and is not
ready for UAT. New project regressions are kept under
`src/test/resources/unit/refcount`; the temporary DBIx test under
`dev/modules` has been removed.

Two ownership fixes are already in the local history. The interpreter now
retains temporary foreach sources through normal and `last` exits. Call
arguments also release a replacement reference when a temporary scalar
argument is overwritten. The related system-Perl, JVM, and interpreter tests
pass, and the pre-fix interpreter/argument failures were recorded in the logs
listed in the completed-phase table below.

The latest full DBIx `t/prefetch/diamond.t` run passes 768/768 with system Perl.
JVM and interpreter each fail four shutdown leak assertions. A reduced
reproduction shows the resultset cache is released, but a selected
`DBICTest::BaseResultSet` remains `LIVE` at runtime refcount zero with a durable
untracked scalar owner. Its `cursor` field owns a
`DBIx::Class::Storage::DBI::Cursor`, which shares the nested query-attrs hash.
The source of that durable scalar owner is still unresolved.

A project-owned regression test now reproduces the leak:
`src/test/resources/unit/refcount/diamond_prefetch_cursor_leak.t`. It requires
`DBIC_CLASS_TEST_ROOT` pointing to a DBIx::Class checkout and skips otherwise.
The test creates 64 search permutations with different join/prefetch attribute
structures, iterates each ResultSet, and traverses the `cd -> artist` and
`artwork_to_artist -> artist` relationship chains on every returned row. After
the block exits, weak witnesses to Schema, Storage, and DBH must all be cleared.

Bisection findings for the leak trigger:
- The leak requires volume: 64+ different searches with relationship traversal.
  A single search or a few identical searches do not leak.
- The leak requires different attribute structures across searches (different
  join/prefetch hash shapes). Identical searches at any volume do not leak.
- The leak requires relationship traversal on returned rows. Calling `.all`
  without traversal does not leak even at volume.
- Pure-Perl models of the same reference graph (Schema/Source/ResultSet/Cursor
  with DESTROY rescue, weak backrefs, and `||=` caching) do not reproduce the
  leak. The bug requires something specific to DBIx's runtime behavior: likely
  the `Try::Tiny` try/catch closures in `_resolve_condition`, the complex
  attribute merging in `ResultSet::search`, or the `related_resultset` hash
  caching through `return $self->{hash}{key} = do { ... }`.
- `use Test::More` (default import) masks the leak at low volume (9 searches)
  but not at high volume (64+). `PJ_REFCOUNT_TRACE=1` also masks the leak by
  adding extra owner tracking that helps the runtime release correctly.
- Both JVM and interpreter backends fail identically.

`t/60core.t` passed after the recent frame-release ordering changes, but that
does not clear the diamond failure.

The source build succeeds. Full `make` remains blocked at Java test-source
compilation by 93 references to retired reachability-diagnostic APIs, including
`ReachabilityWalker`, `measureReachabilityQueries`, and
`temporaryRootDirectlyReferences`. Full unit and CPAN acceptance remain
outstanding, along with focused Moo and Template Toolkit retests. Temporary
owner tracing in `RuntimeBase.java` is diagnostic only and must be removed after
the retained owner is identified.

The sweep-removal design still requires direct cleanup from a dying
aggregate's outgoing owner slots, without nested target pre-scans or repeated
incoming-owner scans. After the DBIx owner is reproduced and fixed, measure
owner-slot visits, workset/queue allocations, sampled allocations, and elapsed
time on the same `Excel-Writer-XLSX/t/regression/chart_axis28.t` workload. The
earlier short JFR sample was noisy and did not establish a broad speedup.

| Date | Phase delivered | Evidence | Remaining |
| --- | --- | --- | --- |
| 2026-11-19 | Cached hot owner-kind counts, transient-owner count, narrow identity indexes, and reusable LIFO cleanup/probe worksets | Unit gate and focused `chart_axis28.t` counters/JFR recorded above; no broad performance claim | Process dying aggregate outgoing slots directly; measure allocation and scan costs on paired workloads |
| 2026-11-19 | Correct nested hash-slot `B::REFCNT` reporting during `DESTROY` | System-Perl-validated regression and passing DBIx `t/60core.t` / `t/101populate_rs.t` | Complete remaining regression and CPAN acceptance gates |
| 2026-11-21 | Retain temporary foreach source owners in the bytecode interpreter through normal and `last` exits (`4edc5faa51`) | New system-Perl/JVM/interpreter regression and DBIx diamond regression pass 7/7; pre-fix interpreter failure captured | Resolve Java test-source compile failures, then rerun `make`, focused CPAN failures, and full release acceptance |
| 2026-11-21 | Release changed temporary scalar arguments after calls, including Test2 Amnesty facets | New system-Perl/JVM/interpreter tests pass 5/5 and 3/3; pre-fix Jperl failures captured; source build succeeds | Rerun DBIx from the first observed failure, then resolve Java test-source compile failures and complete repository/CPAN acceptance |

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
