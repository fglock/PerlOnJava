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

## Current Status (2026-11-15)

Issue #2043 remains the active delivery for removing memory-sweep lifecycle
decisions; #1649 is its historical origin. Runtime candidate `35dfd818ff`
integrates current `master` and is published in ready-for-review
[PR #1938](https://github.com/PerlOnJava/pull/1938). Full local and CI gates
pass; review and merge remain. It removes
`ReachabilityWalker`, `ScalarRefRegistry`, root-query state, sweep hooks,
END-boundary owner-source scans, and suspended-frame root snapshots.

The DBIx phantom-chain transition and DBI cached-driver false-positive fix are
covered. A separate Test2 event survivor was traced to a counted
`ARRAY_ELEMENT` edge on an exited temporary argument array; the exact edge is
now retired after its lexical registration and argument frame end. The
permanent event regression passes 4/4 on system Perl and both PerlOnJava
backends. Configured DBIx `t/52leaks.t` and `t/100populate.t` pass all 121 tests;
direct leak checks found no survivors among 57 and 5 references. The merged
candidate passes `nice -n 19 timeout 3600 make` in 7m29s; its JUnit reports
contain 4,153 tests, 4 skips, and zero failures. The prior fresh-home DBIx run
reported 37 failed programs / 140 assertions, the same failing-program set as
the preceding run; assertion counts fell from 253 to 140, with changes only in `t/52leaks.t`
(134 to 24) and `t/88result_set_column.t` (4 to 1). Its leak inventory predates
the Test2 event fix. Current direct checks cover 57 and 5 references with no
survivors. See the [current ownership handoff](perl-ownership-rewrite.md#current-handoff-2026-11-15)
and [issue #2043](https://github.com/PerlOnJava/issues/2043).

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

1. Complete human review of PR #1938; address findings and rerun affected gates.
2. Merge after approval and close #2043 with the final commit and evidence.
3. After #2043 lands, resume separately tracked follow-ups #2037 and #2034;
   their current regressions still fail on both PerlOnJava backends.

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
