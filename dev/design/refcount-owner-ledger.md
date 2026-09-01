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

Issue #1132 is the first delivery milestone. The complete design also replaces
remaining selective-refcount heuristics with explicit, auditable owner tokens.

## New Findings

The original issue #1132 plan proposed adding one referent token for each
closure capture when the captured scalar did not already have a
`refCountOwned` token. Verification exposed two problems with that model:

1. The focused issue reproducer captures a pad scalar that already has
   `refCountOwned = true`. A borrowed-only token does not protect its referent
   from a weak-reference sweep on either backend.
2. Adding one referent token for every closure makes the issue reproducer live,
   but overcounts real Perl references. It regresses existing captured-scalar,
   weak-callback, tail-call, and exact-refcount tests.

A system Perl oracle confirms that a lexical's referent count remains one when
one or two closures share that lexical:

```text
lexical=1
one_closure=1
two_closures=1
```

The closure retains the lexical pad scalar. The pad scalar, in turn, owns one
strong edge to its referent. Multiple closures sharing the pad do not each own
the referent independently.

The current reachability walker deliberately excludes captured and
scope-exited scalars to avoid treating conservative captures as Perl roots.
Consequently, a weak sweep can destroy an issue #1132 referent even while the
captured pad retains a positive selective count. The fix therefore requires an
authoritative ownership distinction, not broader walking or unconditional
capture increments.

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
8. Weak sweeping cannot destroy a referent with authoritative strong owners.
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

## Weak Sweeping and Reachability

Weak-reference cleanup must follow these rules:

1. Never clear or destroy a referent with authoritative strong owners.
2. Do not traverse arbitrary closure fields or conservative captures as Perl
   roots.
3. Use explicit semantic pad ownership to protect captured referents.
4. Preserve strong cycles because their real strong edges keep authoritative
   counts positive, matching Perl reference counting.
5. Use `ReachabilityWalker` only for still-unmodelled roots, diagnostics, and
   migration checks—not as the source of closure semantics.
6. Gradually eliminate `WEAKLY_TRACKED` heuristics by reconstructing real
   scalar, aggregate, and package owners when an untracked referent first
   requires weak-reference accounting.

## Implementation Phases

### Phase 0: Preserve and specify

- Keep the pre-flight patches and WIP snapshot of the failed walker experiment.
- Replace the current partial capture-token implementation before runtime work
  proceeds.
- Retain the new issue regression test, but align its assertions with shared-pad
  ownership rather than per-closure tokens.
- Document invariants and owner transitions in this design.

### Phase 1: Perl oracle and owner instrumentation

- Add system-Perl-validated tests for stable `B::REFCNT` observations.
- Add owner-ledger tracing and balance assertions without changing behavior.
- Inventory every direct refcount increment, decrement, promotion, and
  `Integer.MIN_VALUE` transition.
- Record which existing counts are authoritative, conservative, or temporary.

### Phase 2: Captured-pad lifecycle

- Implement semantic versus metadata capture counts.
- Preserve the pad's single referent token after declaring-scope exit.
- Release the token when both scope ownership and semantic captures reach zero.
- Promote borrowed semantic captures to one pad-slot owner.
- Make reassignment, weakening, unweakening, and closure release generation-safe
  so deferred decrements cannot target a newly assigned referent.
- Update CODE recursion and ithread clone paths.

### Phase 3: Weak-sweep authority

- Make authoritative owner tokens protect weak referents.
- Remove captured-pad dependence on broad capture walking.
- Keep generated and conservative captures opaque.
- Assert that the walker cannot destroy a referent with authoritative owners.
- Preserve DBIx::Class, Moo, Sub::Quote, and Sub::Defer cleanup by ensuring
  metadata captures never enter the owner ledger.

Issue #1132 is deliverable after Phases 0 through 3 pass their acceptance gates.

### Phase 4: Complete owner-source migration

Audit and convert all ownership paths:

- Scalar assignments, aliases, references, localization, and returns
- Hash and array stores, removals, and clears
- Globals, stashes, globs, and CODE slots
- Arguments, temporaries, tail calls, and lvalue returns
- Tied variables and handlers
- Pad constants and installed subroutines
- Resurrection and repeated `DESTROY`
- Threads and runtime graph cloning

Direct refcount mutation outside the owner API should become forbidden except
inside the destruction state machine.

### Phase 5: Remove superseded heuristics

- Remove `WEAKLY_TRACKED` premature-clearing heuristics where owner coverage is
  complete.
- Retire class-specific and capture-specific walker exceptions superseded by
  authoritative ownership.
- Keep the walker for diagnostics and explicit graph queries.
- Add property tests for balanced owners, cycles, weak edges, and deterministic
  destruction.

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

## Issue #1132 and Ecosystem Acceptance

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
- Rebase onto current `master`, rerun focused and full gates on the exact commit,
  and only then mark the issue-fix PR ready.

## Progress Tracking

### Current Status: Deferred-release provenance is available; exact owner migration remains in progress

### Completed Work

- [x] Closure captures now retain tracked referents through captured pads, and
  weak callback targets with semantic capture owners are not cleared early.
  The focused Future regression is covered by
  `unit/refcount/closure_capture_weak_callback_slot.t`.
- [x] `IO::Handle` supplies `fileno` for normal and tied handles. Direct
  `IO::Socket->socketpair` and `IO::Async::OS->pipepair` fileno probes work on
  both backends.
- [x] Return-scope cleanup preserves IO ownership for materialized list
  returns, including the interpreter path used by `IO::Async::OS->socketpair`.
- [x] Added `unit/io_socket_method_fileno.t`. System Perl passes its 12
  assertions, including the callback-captured socket-pair shape used by
  IO::Async.
- [x] JVM explicit-return cleanup now leaves constructor-captured scalar pads
  to their enclosing frame. A callback can retain one end of
  `IO::Async::OS->socketpair` while returning the other.
- [x] The focused regression passes on JVM and interpreter; `make` passes.
  Net::Async::HTTP `t/05redir.t` and its socket/stream follow-on tests pass.
- [x] `B::SV::REFCNT` no longer applies its DBIx destruction-only aggregate
  adjustment to ordinary lexical probes. The new fresh-runtime regression
  passes on system Perl and both PerlOnJava backends.
- [x] Future's exact-count programs `10wait_all`, `11wait_any`,
  `12needs_all`, `13needs_any`, and `25retain` now pass on the JVM.
- [x] `Internals::jperl_refstate` reports active scalar-store and semantic
  captured-pad owner counts for focused ownership diagnosis.
- [x] Reproduced the remaining Net::Async::HTTP exact-count failures in the
  distribution's normal socket-enabled environment. `t/30timeout.t` reports
  3 rather than 1 references at EOF; after the script scope drains, the raw
  `$http` count is 2, so the `B::REFCNT` probe supplies the third reference.
  `t/32remove.t` similarly reports 7 rather than 4 and 4 rather than 1; its
  raw connection count is 3 after script-scope cleanup.
- [x] Scoped `PJ_REFCOUNT_TRACE` confirms these residual counts are not
  semantic captured-pad owners. It also exposed a diagnostic gap: the trace
  removes a scalar's provenance when `deferDecrementIfTracked()` queues its
  decrement, before the deferred work is applied. A residual raw count can
  therefore outlive all entries in the shutdown owner dump without revealing
  the originating scalar-store path.
- [x] Deferred owner-release tracing now retains immutable provenance from
  `deferDecrementIfTracked()` queueing until the matching drain or explicit
  runtime-state cancellation; a still-pending record is included in the
  shutdown report. The record includes the source scalar
  identity, stable referent generation, acquisition site, queue site, and
  drain site. The parallel queue metadata is trace-only, so it creates no
  runtime owner or reachability edge. Files: `RuntimeBase.java`,
  `LifecycleRuntimeState.java`, and `MortalList.java`. `make` passes; a
  `PJ_REFCOUNT_TRACE` smoke run reports the retained pending provenance.

### Next Steps

1. Use the retained trace with `jperl_refstate` to identify the two surplus
   raw `$http` owners and the three surplus connection owners. Do not alter
   capture accounting to compensate for them.
2. Re-run the Future exact-count programs and Net `t/30timeout.t` and
   `t/32remove.t` on both backends after each owner-path change.
3. Keep Cookie2 formatting and content-coding exception handling separate from
   this ownership work.

### Open Questions

- Which acquisition sites in the retained trace leave the two `$http` and three
  connection counts unbalanced after notifier removal?
