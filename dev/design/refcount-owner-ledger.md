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

### Current Status: Net::Async::HTTP captured-owner, hash-slice, and eval-capture finalization fixes complete

The designated issue branch, `fix/issue-1132-closure-lifetime`, includes the
handoff commits below. On 2026-09-01, the required pre-change `make` rebuilt
the classes and JAR but its four unit-test workers were SIGKILLed (exit 137)
while concurrent PerlOnJava worktrees exhausted host resources. This is an
invalid gate, not a test assertion failure; rerun a clean full gate after the
external workload has drained before changing runtime source.

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
- [x] Added `Internals::jperl_owner_trace($referent)`, a referent-filtered
  assertion-boundary snapshot of active scalar-store tokens and queued
  deferred releases. It is observational when `PJ_REFCOUNT_TRACE` is absent;
  when tracing is enabled it reports the source scalar identity, referent
  generation, acquisition site, and queue site without retaining runtime
  objects. `unit/refcount/owner_trace_snapshot.t` passes on system Perl
  (guarded skip), JVM, and interpreter. Files: `RuntimeBase.java`,
  `Internals.java`, and `owner_trace_snapshot.t`.
- [x] Rebuilt the provenance instrumentation and ran the normal socket-enabled
  `Net::Async::HTTP` `t/30timeout.t` with a scoped trace. The final assertion
  still observes three refs where Perl expects one; after the script-scope
  token drains, `$http` has the known raw count of two. Neither surplus is an
  active scalar-store owner. The shutdown report currently includes a large
  number of unrelated queued releases, so the next diagnostic step needs an
  assertion-boundary snapshot filtered to the selected referent rather than a
  broader trace or any capture-accounting adjustment.
- [x] Ran the filtered snapshot at the normal socket-enabled Net assertion
  boundaries. `t/30timeout.t` shows one active scalar-store token and no
  semantic capture owner for `$http`; `t/32remove.t` shows the same for
  `$conn`. An explicit `jperl_freetmps()` does not reduce either failing
  count. The surplus is therefore not a missed boundary drain, captured pad,
  or currently ledgered scalar-store token. Historic queued-release trace
  records also do not account for the raw count and must not be treated as
  live owners.
- [x] Added trace-only non-scalar owner kinds for method-invocant holds and
  first-bless mortal temporaries. Parallel owner metadata is now removed in
  lockstep with deferred entries during `flushAboveMark()` and
  `popAndFlush()`, eliminating false unpaired trace releases. The full
  `make` gate passes. Net tracing shows `transient=[]` at the failing boundary:
  those two owner kinds balance correctly.
- [x] Mapped `$http` through the Net timeout lifecycle: it has one active
  scalar-store token after construction, two after loop registration, four
  before loop removal (while still showing only those two active tokens), and
  three after removal (one active token). The extra two counts are introduced
  by request processing, not loop registration/removal, captured pads,
  method holds, or bless temporaries.
- [x] Added balanced trace-only tokens for scalar-reference contents,
  weak-reference promotion, closure-capture referents, and all tie-wrapper
  holds (`TiedVariableBase`, `TieHash`, `TieArray`, and `TieHandle`). The
  scalar-reference regression now enables tracing before the hold is acquired
  and verifies that its release leaves `transient=[]`; it passes on both
  execution backends. Full `make` passes.
- [x] Re-ran normal socket-enabled `Net::Async::HTTP` `t/30timeout.t` with
  the expanded ledger. On the JVM, the failing boundary still has raw count 3
  with one scalar-store token and `transient=[]`; no `unweaken` event or
  closure-capture/tie token was acquired for that referent. The interpreter
  remains balanced (two owners before loop removal, one after) and exits 0.
  These direct mutation paths are therefore not the JVM's two surplus
  request-path owners.
- [x] Added the existing semantic captured-pad owner count to the
  assertion-boundary snapshot. The focused diagnostic passes on JVM and
  interpreter; the JVM Net boundary reports `semanticCaptureOwners=0` both
  before and after loop removal. Captured-pad transfer cannot account for the
  two surplus raw counts. Focused JVM/interpreter tests and `make check-links`
  pass. Full `make` is deliberately handed to the receiving worker because a
  concurrent external workload caused the local full-gate attempt to time out
  after its unit shards completed.
- [x] Installed `Net::Async::HTTP` 0.50 and its CPAN dependency chain through
  `jcpan`. In the socket-enabled distribution environment, JVM
  `t/30timeout.t` reproduces the documented final `$http` count of 3 rather
  than 1; the interpreter reaches 1. At the exact assertion, both backends
  have one active scalar-store owner and no pending, transient, or semantic
  capture owner. The JVM's two surplus counts therefore originate in compiled
  `IO::Async::Loop`/`Notifier` cleanup before the final top-level flush, not
  captured pads or method-invocant holds. `t/32remove.t` also has broader
  connection-count drift in this newly installed environment (JVM 7/4 and
  interpreter 8/5), which remains a separate investigation.
- [x] Tested and rejected two JVM-only cleanup hypotheses against clean full
  `make` gates: including compiler-marked captures at ordinary block exit and
  flushing untracked compiled-sub return values. Neither changed the JVM
  `t/30timeout.t` count of 3. The surplus is not a deferred mortal awaiting a
  block or return boundary; continue from direct unledgered increment and
  release paths rather than widening capture or flush behavior.
- [x] Direct increment tracing found two JVM-only
  `releaseCapturedDecrement()` ownership transfers for the failing `$http`.
  Root-path tracing showed that the temporary global
  `$IO::Async::Loop::ONE_TRUE_LOOP->{notifiers}` registration triggered each
  transfer. The loop later removes that entry correctly, but the discarded
  capture token was never restored, leaving two surplus counts. Captured
  owners now always schedule their ordinary deferred decrement. Added
  `unit/refcount/captured_global_owner_release.t`: the test passes on system
  Perl and both PerlOnJava backends, while the unfixed implementation reported
  two references. The normal socket-enabled Net::Async::HTTP `t/30timeout.t`
  now passes all 25 assertions on JVM and interpreter. Full `make` passed in
  6m 08s before the final source-comment cleanup.
- [x] The remaining interpreter-only `Net::Async::HTTP` `t/32remove.t`
  difference was traced to `HASH_SLICE_SET`: the compiler-created RHS array
  and its `addToArray()` staging copies each retained a scalar-store owner
  after `RuntimeHash.setSlice()` had created the durable slot. The interpreter
  now transfers both temporary owners immediately. Added
  `unit/refcount/interpreter_hash_slice_staging_ownership.t`; it passes on
  system Perl and both PerlOnJava backends, while the unfixed interpreter
  reported 3 then 2 references instead of 2 then 1. `t/32remove.t` now passes
  its exact 4 then 1 checks on JVM and interpreter, and `t/30timeout.t` passes
  all 25 assertions on both backends. Full `make` passed on 2026-09-01.
- [x] A scope-exited typed lexical captured only by a discarded `eval STRING`
  no longer retains a stale semantic capture owner through global destruction.
  The pre-`END` reachability pass now releases that owner only after proving
  that no `END` block can reach the capture. Existing permanent core coverage
  in `perl5_t/t/run/fresh_perl.t` test 75 now passes, restoring the baseline
  73/91 result; the other 18 historical failures are unchanged. Full `make`
  passed in the isolated PR worktree on 2026-09-02.

### Handoff: issue #1132 / PR #1204

This work must be continued on the issue-linked PR
[#1204](https://github.com/fglock/PerlOnJava/pull/1204), branch
`fix/issue-1132-closure-lifetime` (not on a new owner-ledger PR). The commits
to retain are `5b693177f`, `1702edb70`, `4832540d6`, `9fcab6e81`, and
`87b4fdee3`. The receiving worker must run a clean full `make` before further
source changes or PR completion; the prior full gate was invalidated only by
an external concurrent workload and timed out after test shards completed.

The Net::Async::HTTP 0.50 distribution is installed through `jcpan` in the
local CPAN cache. JVM and interpreter `t/30timeout.t` and `t/32remove.t` now
pass. Preserve both focused ownership regressions when evolving the ledger.

### Next Steps

1. Continue the owner-source migration inventory and remove only diagnosed
   ownership heuristics with permanent system-Perl-validated regressions.
2. Keep Cookie2 formatting and content-coding exception handling separate from
   this ownership work.

### Open Questions

- Can additional compiler-created aggregate temporaries be made explicit in
  the owner ledger, rather than relying on opcode-specific transfers?
