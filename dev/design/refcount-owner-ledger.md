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

### Phase 1 owner-source inventory (2026-10-04)

A strict source search across `src/main/java/org/perlonjava` finds 135
non-comment lines that mutate `refCount` (17 `++`, 13 `--`, and 105 direct
assignments) and 36 lines that assign `refCountOwned`. This is a static source
inventory, not a runtime instruction count. It includes lifecycle/sentinel
transitions as well as ownership changes.

| Owner or lifecycle path | Main implementation sites | Classification |
|---|---|---|
| Scalar and lexical slots | `RuntimeScalar.setLargeRefCounted`, `undefine`, `ReferenceOperators` | Counted strong stores can set `refCountOwned` and record an active scalar owner. Some references remain untracked (`refCount == -1`); promoted values can have earlier stores that were never recorded. |
| Array and hash elements | `incrementRefCountForContainerStore`, `RuntimeArray`, `RuntimeHash`, `RuntimeList` | Ordinary stored cells can acquire counted ownership. Alias arrays, borrowed copies, list materialization, and pop/shift/delete paths transfer or deliberately omit a count; the slot flag alone does not describe these transitions. |
| Package globals, stashes, and globs | `GlobalRuntimeScalar/Array/Hash`, `RuntimeStash`, `RuntimeGlob` | Globals and stash entries are strong Perl roots, but several paths use local-binding or root-map state instead of a matching scalar-owner token. Root membership must remain represented independently. |
| Closures and CODE | `RuntimeCode`, `RuntimeScalar` capture transitions, `BytecodeInterpreter`, `SlowOpcodeHandler` | Semantic pad ownership is distinct from conservative captures and temporary invocation holds. `semanticCaptureOwners` models captured pad cells; general `activeOwners` does not. |
| Temporaries and deferred release | `MortalList`, `RuntimeCode`, `DestroyDispatch`, `RuntimeBase` | Some holds are scalar-owned and deferred; others are non-scalar dispatch/rescue holds. Their count is temporary and cannot be treated as a persistent Perl slot. |
| Weakening, sweeping, and destruction | `WeakRefRegistry`, `ReachabilityWalker`, `DestroyDispatch` | `-1`, `WEAKLY_TRACKED`, and `Integer.MIN_VALUE` are tracking or lifecycle states, not owner cardinalities. Sweeps also adjust counts when reconciling stale ownership. |
| Tied, regex, I/O, and thread wrappers | `Tie*`, `RuntimeRegex*`, `runtime/io/*`, `RuntimeGraphCloner` | These classes have resource- or runtime-specific reference holds. Thread cloning reconstructs ownership for cloned cells; it cannot be inferred from the source cell's current flag. |

This inventory confirms that neither `refCount > 0` nor `activeOwners` can
authorize skipping the AnyEvent weak-reference root walk. `refCount` combines
counted slots, untracked references, local-binding state, transient holds, and
sentinels. `activeOwners` is populated only after tracking activation and only
by selected scalar-store paths. The next audit pass must trace the exact
AnyEvent referent's owners through each owning storage class before choosing
any sweep shortcut.

The latest unchanged `AnyEvent::Tools/t/02_rw_mutex.t` trace reports repeated
scope-exit requests for `RuntimeArray` targets with `refCount=2` and
`activeOwners=0`. A diagnostic scan of the scalar registry finds 4 to 13
nonweak cells pointing at these arrays across successive requests; all show
`refCountOwned=false` and `captureRefCountOwned=0`. The cells include `$self`,
`$timer`, and captured `$t` values. Optional registration stacks include
assignments from `AnyEvent::Loop.pm` and the test's timer callbacks, as well as
array materialization paths. These are observed scalar holders, not verified
Perl owner tokens: the flags do not distinguish a shared pad, a temporary
alias, or a stale cell. The two counted references have not yet been traced to
their actual slots. Do not use the positive count or this holder list as a
fast-path until those ownership transitions are established.

The original issue #1132 plan proposed adding one referent token for each
closure capture when the captured scalar did not already have a
`refCountOwned` token. Verification exposed two problems with that model:

1. The focused issue reproducer captures a pad scalar that already has
   `refCountOwned = true`. A borrowed-only token does not protect its referent
   from a weak-reference sweep on either backend.
2. Adding one referent token for every closure makes the issue reproducer live,
   but overcounts real Perl references. It regresses existing captured-scalar,
   weak-callback, tail-call, and exact-refcount tests.
3. The existing `activeOwners` set is not an authoritative ledger. A referent
   activates it only when `weaken()` is first called, and earlier
   `recordActiveOwner()` calls are discarded while the set is null. The current
   implementation avoids backfilling from `ScalarRefRegistry` because that
   path regressed DBIx::Class leak tests. Thus an `activeOwnerCount()` of zero
   can mean either “no counted scalar owns this referent” or “its stores
   happened before tracking began.” It cannot justify skipping a complete
   sweep.
4. In the failing HTML::Tree interpreter test, temporary instrumentation sees
   two surviving `HTML::TreeBuilder` objects and two `HTML::Element` objects
   after the first tree is dropped; their observer slots still report weak.
   A minimal nested hash/array graph with a weak parent edge passes on both
   backends, so the parity regression depends on the real parser construction
   path. The weak edge itself is installed; the unresolved lifetime is an
   extra strong owner or stale owner count.

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

### Current Status: CODE-slot owner migration continues; tested checkpoint `3a3d12213` (2026-11-04)

The named-CODE batch now follows explicit slot identity through parser-side
redefinition and interpreter undef. `SubroutineParser` replaces an installed CV
through `RuntimeScalar.set()`, which acquires the replacement CODE_SLOT owner and
releases the previous CV edge. `InterpretedCode.defined()` now respects
`codeReferenceUndefined`. Compiled `defined(&name)` probes consult the live
visible CODE slot when it exists and retain their captured CV behavior when
compile-time stash deletion removed that slot.

`StashCodeLifecycleUsesOwnerIdentityTest` verifies that replacement moves the
stash owner and that slot deletion retires lifecycle state despite stale
`stashRefCount`. The Perl regressions
`named_undef_visible_code_slot.t` and
`defined_code_slot_compile_time_stash_delete.t` pass on system Perl, JVM, and
interpreter; both PerlOnJava backends were run with corrective sweeps disabled.
`JPERL_TEST_FILTER=code_slot timeout 1200 nice -n 19 make test-unit` passes,
including the Java owner test (2/2). Logs:
`/private/tmp/ownership-code-slot-focused-final-8287.log`,
`/private/tmp/named-undef-visible-code-slot-latest-jvm.log`,
`/private/tmp/named-undef-visible-code-slot-latest-interpreter.log`,
`/private/tmp/begin-jvm-code-slot-final-8287.log`, and
`/private/tmp/begin-interpreter-code-slot-final-8287.log`. The system Perl
oracle logs are `/private/tmp/named-undef-visible-code-slot-system-perl-20261104-final.log`
and `/private/tmp/defined-code-slot-compile-time-delete-system-perl-20261104.log`.

The related `deferred_capture_live_argument.t` regression is present and passes
3/3 on system Perl, JVM, and interpreter. Logs are
`/tmp/deferred-capture-live-argument-system-perl-20261104.log`,
`/tmp/deferred-capture-live-argument-jvm-20261104.log`, and
`/tmp/deferred-capture-live-argument-interpreter-20261104.log`. A source audit
of deferred releases found no production Java caller of the legacy unpaired
`MortalList.deferDecrement` hook; its current callers are Java tests. The
reachability-based count-repair and lifecycle branches in `processDeferredBase`
require a missing `PendingOwnerRelease`, while in-repository production release
paths queue an exact release token. This narrows the ordinary lifecycle
fallback surface but does not yet justify deleting the hook: its synthetic
tests and the other producer families still need an identity-based audit.

The parent `b3ad73b0d` passes the named-undef JVM test 4/4 but fails interpreter
checks 1 and 4 (2/4); checkpoint `3a3d12213` passes 4/4 on both backends. The
unfiltered unit gate exposed six failures. Five direct no-sweeps replays failed
identically on parent `b3ad73b0d` and the candidate:
`bless_tied_hash_reference.t`,
`mojolicious_promise_lifecycle_regressions.t`,
`refcount/scalar_cell_scope_owner_release.t`,
`substr_core_edge_semantics.t`, and
`custom_warning_async_suspend_state.t` (the latter skips on system Perl because
its optional Future dependencies are unavailable). These failures are not
introduced by this CODE-slot batch, but remain to be classified before final
acceptance. `threads_shared_reference_validation.t` failed in the unfiltered
gate, then passed 15/15 on system Perl and in direct no-sweeps replays on both
parent and candidate; its gate-only failure remains unexplained. The complete
unfiltered unit gate and the full ownership/HTML::Tree acceptance matrix have
not passed on this checkpoint. Parent logs for the named-undef regression are
`/private/tmp/named-undef-visible-code-slot-parent-b3-jvm.log` and
`/private/tmp/named-undef-visible-code-slot-parent-b3-interpreter.log`.

Local work is on `wip/ownership-status-20261104-103529`; code checkpoint
`3a3d12213` is followed by documentation/WIP checkpoint `5d964871b`. Local
history contains master `b43a652507d836bfbd78e3e6c250021c0b3355aa`, but the live
PR now reports base `4a5c33753f1da3f9838c44567bbba878e8f567e0`; ancestry to that
new base has not yet been checked. PR #1938 remains open as a draft at remote
head `95b7c0cd8abbec3d55e9ee377f67a9c1a3c8ca05`, and this checkpoint is not
published. Next, audit and migrate remaining count producers in batches:
reference-slot transitions and tied/container mutation; call, closure, and
deferred-release lifetimes; package/resource roots; then clone/shared storage
and cycles. Retire ordinary reachability recovery only after these producer
paths have explicit identities and focused coverage, then rebase/reconcile with
the live base and run final acceptance on one immutable candidate.

### Previous checkpoint evidence: owner-source migration at `3cbff31fb` (2026-11-02)

The END queue now keeps a real owner identity across dequeue and callback
execution. `SpecialBlock.hasPendingEndBlock()` accepts only a current queue cell
whose counted or untracked owner identity points to that CODE object. The
handoff uses an `ACTIVE_CODE` slot; it does not manufacture a positive count.
`DestroyDispatch` defers capture cleanup only for that exact queued END owner,
not for any CODE object that happens to have another owner slot. This narrowing
also restores closure, tail-call, and WebSocket cleanup that regressed under the
broader fallback.

`EndBlockOwnerIdentityTest` verifies restoration from the two current counted
cells, exact queue-edge release, active-owner transfer through invocation, and
capture lifetime. It failed on parent `403ae7ab2` because the queued END owner
was omitted from the restored count. The final no-sweeps `make` passes on
immutable `7c1fef04b` in 7m07s. Seven END lifecycle regressions pass 35/35 on
system Perl, JVM, and interpreter. Evidence:
`/private/tmp/issue-1649-endblock-parent-make-403ae7ab2.log`,
`/private/tmp/issue-1649-end-block-fullmake-dcc78bbe5.log`,
`/private/tmp/issue-1649-endblock-owner-fullmake-7c1fef04b.log`,
`/private/tmp/issue-1649-endblock-system-perl-7c1fef04b.log`,
`/private/tmp/issue-1649-endblock-jvm-7c1fef04b.log`, and
`/private/tmp/issue-1649-endblock-interpreter-7c1fef04b.log`.

Earlier lifecycle acceptance evidence remains recorded below. The package
compatibility pin, deferred-release snapshot, cleared-aggregate snapshot, and
other legacy closure cleanup paths remain under audit.

On 2026-11-02, tail-call argument cleanup now trusts the active lexical owner
identity before consulting the legacy `localBindingExists` marker. A surviving
lexical owner defers final cleanup with `refCount` still at the weak-tracking
sentinel; releasing that final lexical owner clears weak observers. The new
`TailCallActiveLexicalOwnerTest` failed on test-only parent `2268035cb` because
the cleanup path replaced the sentinel with a synthetic count of one. The
no-sweeps full `make` passes on immutable `253b3cde9` in 6m56s, and the focused
tail-call Perl file passes 5/5 on JVM and interpreter with sweeps disabled.
System Perl exits successfully but skips the runtime-specific assertions
because `Internals` is unavailable. Logs:
`/private/tmp/issue-1649-tailcall-parent-make-2268035cb.log`,
`/private/tmp/issue-1649-tailcall-owner-fullmake-253b3cde9.log`,
`/private/tmp/issue-1649-tailcall-jvm-253b3cde9.log`, and
`/private/tmp/issue-1649-tailcall-interpreter-253b3cde9.log`.

On 2026-11-02, deferred count restoration now recognizes a counted scalar cell
that remains stored in a current array or hash slot. The counted identity stays
attached to that scalar cell across storage; `isCurrentCountedScalarOwner()`
checks the container's live membership instead of retagging the identity and
disturbing pending release or resurrection state. `CountedOwnerStorageTransferTest`
failed on parent `0b0ba7f27` and passes on `549924991`; the full no-sweeps
`make` passes in 7m17s. The legacy package-root snapshot remains for
provenance-free bare deferred decrements; trace their producers before removing
that compatibility path. Logs:
`/private/tmp/issue-1649-storage-transfer-parent-make-0b0ba7f27-extended.log`
and `/private/tmp/issue-1649-storage-transfer-fullmake-549924991.log`.

On 2026-11-02, first bless now acquires a counted temporary owner identity and
carries its release token through the mortal queue. Strong expression
temporaries remain owners until their deferred boundary; a weak bless source
keeps the existing weak-alias behavior and does not remain registered as a
strong scalar owner. `BlessTemporaryOwnerProvenanceTest` failed on test-only
parent `80bf83b3a`. The first full run exposed a weak-glob alias regression;
the focused shard then passed after weak sources were handled separately. The
full no-sweeps `make` passes on immutable `3cbff31fb` in 9m37s. Logs:
`/private/tmp/issue-1649-bless-temp-parent-make-80bf83b3a.log`,
`/private/tmp/issue-1649-bless-owner-fullmake-80bf83b3a.log`,
`/private/tmp/issue-1649-bless-owner-shard0-80bf83b3a.log`, and
`/private/tmp/issue-1649-bless-owner-fullmake-3cbff31fb.log`. The raw
`RuntimeScalar.undefine()` and `RuntimeBase.releaseClosureCapture()` deferred
decrement producers remain to be audited.

The weakly tracked `RuntimeScalar.undefine()` path now leaves the sentinel in
place when current argument-alias slots retain the referent. The final alias
release carries the deferred lifecycle obligation and clears weak observers
without a root query. `WeakArgumentAliasUndefOwnerTest` failed on test-only
parent `47d4127f8` because undef changed the sentinel to `1`; the supported
shard-0 gate passes on `9addf278a` in 5m56s. The full `make` is pending for
this follow-up. Logs:
`/private/tmp/issue-1649-argument-alias-parent-shard0-47d4127f8.log` and
`/private/tmp/issue-1649-argument-alias-shard0-9addf278a.log`. This removes one
raw decrement producer; `RuntimeBase.releaseClosureCapture()` remains.

On 2026-11-02, branch `wip/detached-glob-owner-20261102-064229` passed the
full no-sweeps `make` on `2cc19ab4d` in 7m41s. The 18-file lifecycle matrix
passes 117/117 tests on each backend, covering closure and tied captures,
controller and WebSocket release (#1794), growing output (#1860), strong
cycles, weak references, resurrection, and async cancellation. HTML::Tree
5.07 passes 989/989 tests across 23 files on each backend, including
`t/refloop.t` 8/8. Logs:
`/private/tmp/issue-1649-legacy-capture-fullmake-2cc19ab4d.log`,
`/private/tmp/issue-1649-owner-lifecycle-jvm-2cc19ab4d.log`,
`/private/tmp/issue-1649-owner-lifecycle-interpreter-2cc19ab4d.log`,
`/private/tmp/issue-1649-html-tree-jvm-2cc19ab4d.log`, and
`/private/tmp/issue-1649-html-tree-interpreter-2cc19ab4d.log`.

`CapturedAggregateWeakOwnerReleaseTest` verifies that a semantic
`CLOSURE_PAD` owner preserves the weak-tracking sentinel and retries final
cleanup only after its pending release marker retires. The new
`ClearedAggregateNestedPackageOwnerNoRootWalkTest` verifies that a current
nested package owner keeps its weak observer live without root queries.
`LegacyClosureCaptureOwnerReleaseTest` verifies that a legacy semantic capture
leaves `refCount` at zero, preserves its weak observer, and completes cleanup
on final slot release with zero root queries. The first implementation used the
weak-tracking sentinel for both capture variants and failed the existing
`ViaLayerCountedOwnerLifecycleTest`, which requires zero; the final
implementation stores the deferred-final-release obligation separately from
the count. Evidence is in
`/private/tmp/issue-1649-legacy-capture-owner-parent-7290144a8.log`,
`/private/tmp/issue-1649-legacy-capture-fullmake-7290144a8.log`, and
`/private/tmp/issue-1649-legacy-capture-focused-zero-count-7290144a8.log`.
The external-root snapshot remains for zero-count candidates with no current
owner identity: removing it fails the existing
`ReachabilityQueryCostTest.repeatedAggregateScopeCleanupSharesExternalRootSnapshot`
contract, which expects one shared snapshot while cleaning three ownerless
legacy candidates. Keep that fallback until those producers are migrated or
their ownership is explained. The full issue remains open. The package
first-weaken Perl regression passes 7/7 on system Perl, JVM, and interpreter;
logs are `/private/tmp/issue-1649-package-pin-system-perl.log`,
`/private/tmp/issue-1649-package-pin-jvm-2cc19ab4d.log`, and
`/private/tmp/issue-1649-package-pin-interpreter-2cc19ab4d.log`. The compatibility pin
still bridges legacy uncounted package owners through `unweaken()`, whose
current count restoration requires a nonnegative count. Migrate that count
transition before retiring the pin. The destruction count-only resurrection
fallback was removed on `a1abf6818` after auditing current owner-slot producers;
the focused and full no-sweeps gates are recorded below. At that earlier
checkpoint, PR #1938 was an open draft at remote head `b2f44fc2d` against
`master` `4a5c33753`.

On 2026-11-02, `4b772241d` paired each tracked regex callback CODE increment
with its `REGEX_CALLBACK` owner identity. Verified count restoration now
includes that native slot, and callback release decrements only when that slot
acquired the count. An initially uncounted callback therefore cannot consume a
later count from another owner. `RuntimeRegexCallbackCountedOwnerTest` passes
both cases; the focused Gradle test and full no-sweeps `make` pass on this
commit. Logs:
`/private/tmp/issue-1649-regex-callback-focused-4b772241d.log` and
`/private/tmp/issue-1649-regex-callback-fullmake-4b772241d.log`.

On 2026-11-02, `RuntimeTiedHashIterator` gained a `TIED_HASH_ITERATOR_KEY`
owner identity for the current reference key passed to `NEXTKEY`. It releases
that identity on the next-key transition (including exceptions), exhaustion,
and every iterator reset. Tracked referents pair the slot with exactly one
count; weakly tracked referents retain the identity without a fabricated
count. `pendingKey` continues to rely on the tied handler's return owner until
the key is consumed. `RuntimeTiedHashIteratorOwnerSlotTest` passes 2/2, and the
full no-sweeps `make` passes on immutable `830c7690d` in 7m50s, including Joni,
all five unit shards, and launcher integration. Logs:
`/private/tmp/issue-1649-tied-iterator-focused-setupfix.log` and
`/private/tmp/issue-1649-tied-iterator-fullmake-830c7690d.log`.

### Ownership fallback audit (2026-11-02)

| Path | Current strong edge and release | Remaining recovery or evidence |
|---|---|---|
| Deferred counted release | `RuntimeScalar.recordCountedOwner()` installs a stable scalar-cell slot; `queueOwnerRelease()` carries its pending-release identity to `processDeferredBase()`. `isCurrentCountedScalarOwner()` now verifies the scalar through its current array/hash storage. `DeferredOwnerLedgerProducerTest` confirms ordinary nested package-array cleanup uses zero root queries while 256 unrelated aggregates are live. | `restoreReachableOwnerCount()` still walks scalar roots for bare package-rooted deferred decrements without release provenance. `ReachabilityQueryCostTest.deferredOwnerChecksShareOneRootSnapshotPerDrain` preserves this compatibility path. Trace the raw `MortalList.deferDecrement()` producers and give each exact release provenance before retiring the walk. |
| Void-context discard | `mortalizeForVoidDiscard()` queues the exact counted-slot release when present; a zero-count temporary uses a lifecycle completion token retained by the deferred queue. | The old zero-to-one count shim is removed. `VoidDiscardZeroCountLifecycleTest` checks the count remains zero while pending and finalizes once at drain; the Perl regression passes on system Perl, JVM, and interpreter. |
| Scalar overwrite | `setLargeRefCounted()` acquires the replacement count/identity before releasing the previous referent; final release checks current slots. | No root walk remains in this immediate overwrite decision. Rescued-graph cleanup also checks current Perl owner identities. |
| First weaken | `RuntimeScalar.updateUntrackedReferenceOwner()` acquires an `UNTRACKED_REFERENCE` identity for each durable strong scalar value. The containing array/hash slot and package aggregate stash slot provide the current-storage and root proofs. `WeakRefRegistry.weaken()` retires the weakened scalar's owner identity; final nested-slot release calls `onStrongOwnerSlotReleased()`. `issue_1649_package_nested_untracked_weak_owner.t` passes 7/7 on system Perl and both backends. | For package-rooted untracked aggregates, first weaken still installs `WEAKLY_TRACKED_COMPATIBILITY_PIN`, then releases it when the final nested scalar owner leaves. `unweaken()` restores a counted owner only when `refCount >= 0`; the pin currently supplies that count bridge. `PackageWeakCompatibilityPinOwnerSlotTest` asserts the count transitions. Migrate unweaken's sentinel-to-count transition before removing the pin. |
| Tied handlers | The tie wrapper acquires a `TIE_HANDLER` slot; nested handler hashes/arrays own values through their ordinary element slots. | `tied_handler_nested_weak_owner.t` verifies store-before-weaken and final nested-slot release on system Perl and both backends. `possiblyStoredInTiedHandler` is propagated as a legacy marker and still needs a separate unused-state audit. |
| PerlIO::via layer self | `ViaLayer` installs an `IO_VIA_LAYER` identity for the Java layer's retained `PUSHED` value. Counted values pair it with one direct increment; final release carries the slot token through `MortalList` and `DestroyDispatch`. | `perlio_via_self_owner_lifecycle.t` passes 3/3 on system Perl and both backends with sweeps disabled. `ViaLayerCountedOwnerLifecycleTest` preserves a live closure-pad owner, and `ViaLayerReleaseDuringDestroyTest` checks count balance during active destruction. |
| Regex callback CODE | `RuntimeRegex.setExecutableCallbacks()` retains a `REGEX_CALLBACK` native slot. If CODE is tracked, that identity is marked as owning its paired count; final callback release decrements only that count. | `RuntimeRegexCallbackCountedOwnerTest` verifies tracked count pairing, shared callback owners, and that an initially uncounted callback does not decrement a later count. The slot participates in verified count repair and native-owner resurrection checks. |
| Tied-hash iterator key | `RuntimeTiedHashIterator` holds the current key passed to `NEXTKEY` through a `TIED_HASH_ITERATOR_KEY` identity. The slot pairs with a count only for tracked referents and is released on NEXTKEY, exceptions, exhaustion, and iterator reset. `pendingKey` remains under its tied-handler return owner until consumption. | `RuntimeTiedHashIteratorOwnerSlotTest` passes 2/2; the full no-sweeps `make` passes on `830c7690d`. |
| Async localized package aggregates | `DynamicVariableManager` acquires a `SUSPENDED_FRAME` owner before `dynamicSuspendState()` detaches the active aggregate; resume reinstalls the same array/hash and releases that frame owner afterward. | `issue_1649_localized_global_aggregate_async_owner.t` verifies localized array/hash element lifetime across suspension, resume, and cancellation on both backends. The suspend path preserves the original aggregate and its element identities; no root-snapshot fallback is used here. |
| Thread snapshot roots | `RuntimeGraphCloner` assigns a `TEMPORARY_ROOT` identity to each top-level snapshot root and to the direct referent of a strong scalar root. `RootSnapshot` owns these identities through clone hooks and entry setup; thread cleanup and runtime close/reset retire them. | `WeakRefRegistry.weaken()` no longer walks the cloned root graph or writes a synthetic positive count. `ThreadSnapshotTemporaryRootOwnerTest` checks `WEAKLY_TRACKED` state and weak-observer cleanup at lease release. |
| Cleared aggregates | Scope-exit cleanup checks current Perl owner identities and pending releases before destruction. `ClearedAggregateNestedPackageOwnerNoRootWalkTest` verifies that a live nested package slot preserves its weak observer with zero root queries. | `finalizeClearedAggregateOwnerAfterScopeExit()` still consults a cached external-root snapshot for zero-count candidates with no current owner identity. `ReachabilityQueryCostTest.repeatedAggregateScopeCleanupSharesExternalRootSnapshot` asserts one shared snapshot for this legacy path. |
| Closure cleanup | Parser, JVM, and interpreter closure producers install explicit `SCALAR_CAPTURE` / `CLOSURE_PAD` slots tied to the owning CODE. Native and legacy semantic capture identities now defer zero-count cleanup without changing `refCount`; final slot release dispatches cleanup and clears weak observers. `LegacyClosureCaptureOwnerReleaseTest` verifies zero root queries. Full HTML::Tree 5.07 passes 23/23 files and 989/989 tests on both backends with sweeps disabled. | Legacy captures without complete CODE-owner identity still use END-root and scalar-root snapshots. Classify the pre-END traversal separately from ordinary lifecycle repair, then audit its remaining producers. |
| END queue and invocation | Each queued callback's current queue cell has a counted or untracked owner identity. `runEndBlocks()` acquires an `ACTIVE_CODE` identity before `shift()` and releases it after invocation; the removed array edge carries its counted owner token through deferred release. | `EndBlockOwnerIdentityTest` and seven Perl lifecycle tests pass on system Perl and both backends. The pending-queue check verifies exact cell membership and an active counted or untracked identity. The old positive-count pin and broad CODE-owner fallback are removed from this path. |
| Tail-call argument cleanup | `releaseTailCallArgElement()` releases the argument edge, then checks verified counted and uncounted owner identities before using the legacy lexical marker. | `TailCallActiveLexicalOwnerTest` fails on `2268035cb` with a manufactured count of one and passes on `253b3cde9`; the focused Perl file passes 5/5 on both backends with sweeps disabled. |
| Destruction / resurrection | Global destruction compares newly installed owner slots; ordinary destruction validates current slots and semantic captures. Counted scalar stores install identities in `recordCountedOwner()`; method-invocant, PerlIO::via, regex callback, and cloned-thread edges have explicit producers. `DestroyOwnerSlotResurrectionTest` covers counted, untracked, detached, and temporary-slot decisions with zero root queries. | `hasEscapedPerlOwner()` now accepts current Perl owner identities and semantic captures only. `DestroyCountOnlyResurrectionTest` proves a fabricated positive count is not resurrection, while an active native slot is; it fails on parent `dba24b49b`. The focused DESTROY suite passes 10/10 on `a1abf6818`; the full no-sweeps `make` passes on the same immutable commit. Logs are listed in Current Status. Continue tracing other direct count producers at their acquisition, transfer, and release sites. |

The remaining snapshot and compatibility paths are not yet proven unnecessary
for normal execution. The local source is not reconciled with PR #1938: as of
2026-11-02, the draft remains open at remote head `b2f44fc2d`, its base snapshot
is `4a5c33753`, and current `master` is `d34aa038f3b738f81e89a8127bd802673e5cd2f6`.
GitHub reports mergeability as `UNKNOWN`; preserve that remote work and
reconcile only after the ownership candidate is complete.

On 2026-11-02, `hasEscapedPerlOwner()` stopped treating a positive `refCount`
without a current owner identity as resurrection. The new
`DestroyCountOnlyResurrectionTest` fails on parent `dba24b49b` and passes after
the fix; `DestroyOwnerSlotResurrectionTest`,
`PerlReferentDestroyLifecycleTest`, and `PerlDestroyFailureOwnershipTest` also
pass, for 10/10 focused tests total. The complete no-sweeps `make` passes on
immutable `a1abf6818` in 7m30s, including Joni, all unit shards, and launcher
integration. Evidence:
`/private/tmp/issue-1649-destroy-count-only-parent-dba24b49b.log`,
`/private/tmp/issue-1649-destroy-owner-focused-a1abf6818.log`, and
`/private/tmp/issue-1649-destroy-owner-fullmake-a1abf6818.log`.

### Completed Work

- [x] On 2026-11-02, `DESTROY` resurrection now requires current Perl owner
  identities or semantic captures; a positive count without either is ignored.
  `DestroyCountOnlyResurrectionTest` fails on parent `dba24b49b`; the focused
  suite passes 10/10 and the full no-sweeps `make` passes on `a1abf6818`.
  Logs are recorded in Current Status.
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
- [x] Localized package arrays and hashes keep their element owners while an
  async frame is suspended. Resume reinstalls the same aggregate before
  releasing its `SUSPENDED_FRAME` owner; cancellation and repeated cancellation
  release values and run `DESTROY` exactly once.
- [x] A regular nested package-array store preserves a weakly observed value
  until its final owner leaves. Both backends report zero lifecycle root
  queries, snapshots, and graph-edge visits with 256 unrelated live aggregates.
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
- [x] On 2026-10-28, removed automatic, targeted, and statement-boundary weak
  cleanup scheduling from `MortalList`; legacy request methods remain no-op
  compatibility hooks. Added direct cleanup for an untracked value when its
  final scalar-cell owner exits, and kept reachability fallbacks where slot
  coverage is incomplete. `nice -n 19 make` passed with
  `JPERL_DISABLE_CORRECTIVE_SWEEPS=1` and Gradle limited to two workers.
- [x] On 2026-10-28, CODE weak-reference registration stopped walking scalar
  roots to decide whether an anonymous CODE value still has an owner. The
  existing stash, active-frame, and owner-slot checks in `clearWeakRefsTo()`
  now make that decision. Added
  `WeakCodeOwnerSlotNoReachabilityTest`; the full `make` gate passed with
  corrective sweeps disabled.
- [x] On 2026-10-29, package-rooted untracked aggregates now retire the
  temporary count rescued at first weaken when their final untracked owner
  slot leaves. Added Java owner-release coverage and six Perl assertions for
  package-array clear and package-hash delete; system Perl, both backends,
  the full no-sweeps `make`, and HTML-Tree 5.07 `t/refloop.t` (8/8 on both
  backends) passed. The first-weaken root-query entry now returns from the
  owner-slot ledger before inspecting package edges; its regression sees zero
  edges and nodes visited.
- [x] On 2026-10-29, tracked zero-count weakening now trusts surviving
  uncounted owner slots directly and enters `WEAKLY_TRACKED`; final slot release
  clears weak observers without a root walk or compatibility pin.
  `WeakUncountedOwnerSlotFastPathTest`, the no-sweeps full `make`, and the weak,
  DESTROY, resurrection, closure, cycle, and HTML-Tree 8/8 matrix pass.
- [x] On 2026-10-29, first weaken of an untracked aggregate now keeps a
  transitional `WEAKLY_TRACKED` sentinel without the generic root-graph
  fallback; its durable slot release clears the weak observer directly.
  `UntrackedAggregateFirstWeakenFastPathTest`, the no-sweeps full `make`, and
  HTML-Tree 5.07 `t/refloop.t` (8/8 on both backends) pass. The package-root
  rescue proof remains for the legacy nested-aggregate case.
- [x] On 2026-10-29, a surviving uncounted owner slot now preserves weak
  observers when `RuntimeScalar` releases the last counted slot. The final
  owner release performs direct cleanup without a root query; permanent
  regression coverage is in `RuntimeScalarUncountedOwnerRepairTest`.
- [x] On 2026-10-29, localized CODE restoration now releases displaced CODE
  captures from active-frame and owner-slot identities, without scalar-registry
  or root-graph scans. `LocalizedGlobCodeOwnerReleaseTest` covers final capture
  cleanup; `LocalizedGlobCodeOwnerSlotFastPathTest` covers a live active call.
  The no-sweeps full `make`, closure/weak/DESTROY/resurrection/cycle matrix, and
  HTML-Tree 5.07 `t/refloop.t` (8/8) pass on both backends.
- [x] On 2026-10-29, scalar overwrite, deferred decrement, and tail-call release
  now defer zero-count cleanup for aggregates with a surviving uncounted owner
  slot, even with no weak observer. `UncountedOwnerSlotZeroCountReleaseTest`
  verifies each path and final destruction after the sole owner slot leaves.
  Scalar and IO referents still need owner-slot release coverage before this
  rule can safely apply to them.
- [x] On 2026-10-29, tail-call zero-count release no longer treats a
  Java-live scalar-registry wrapper as a Perl owner or forces GC to inspect it.
  `TailCallScalarRegistryOwnershipTest` verifies that a released tail-call
  referent is destroyed while its scalar wrapper remains Java-live.
- [x] On 2026-10-29, tail-call release now trusts any direct non-counted Perl
  owner slot, excluding temporary runtime roots. Added
  `TailCallUncountedOwnerSlotFastPathTest` for package scalar ownership; it
  passes in the full no-sweeps `make` gate (10m58s).
- [x] On 2026-10-29, preserve live uncounted scalar owner slots through
  referent promotion, while pruning slots in temporary containers without a
  Perl owner at final release. `PromotedUntrackedOwnerSlotTest` covers both
  cases. The no-sweeps full `make` gate passed in 14m59s; tempfile, closure,
  weak/DESTROY, resurrection, weak-cycle, and HTML-Tree 8/8 checks passed on
  both backends.
- [x] On 2026-10-29, async Future resume now checks counted and direct owner
  slots instead of rebuilding root graphs or forcing GC for a scalar-registry
  lookup. Temporary evaluation roots do not count as Perl owners.
  `PerlStrongOwnerProofTest`, the full no-sweeps `make`, the closure/weak/
  DESTROY/resurrection/cycle matrix, and HTML-Tree `t/refloop.t` (8/8 on both
  backends) pass.
- [x] On 2026-10-29, void-discard cleanup now uses registered container
  membership directly as proof that a CODE scalar slot is live, avoiding a
  duplicate scalar reachability query. `MortalizeRegisteredCodeOwnerFastPathTest`
  verifies zero scalar queries and root walks; the no-sweeps full `make` passed
  in 21m18s.
- [x] On 2026-11-02, detached glob snapshots release stale package components
  after namespace rebinding while preserving live glob-value and namespace-move
  owners. `stale_stash_saved_move_owner.t` passes 7/7 under system Perl, JVM,
  and interpreter; `StaleStashPackageOwnerReleaseTest` covers both compiled
  backends. The no-sweeps full `make` passed on `b454cab08` in 6m18s.
- [x] On 2026-11-02, zero-count void-context results now queue explicit
  lifecycle work instead of temporarily setting `refCount=1`. A remaining
  counted scalar queues its exact owner release. The new Java lifecycle test
  passes in the no-sweeps full `make` on `85d16946b` (7m01s); the Perl
  regression passes 1/1 on system Perl, JVM, and interpreter. Logs:
  `/private/tmp/issue-1649-void-discard-fullmake-85d16946b.log`,
  `/private/tmp/issue-1649-void-discard-zero-count-system-perl.log`,
  `/private/tmp/issue-1649-void-discard-zero-count-jvm-85d16946b.log`, and
  `/private/tmp/issue-1649-void-discard-zero-count-interpreter-85d16946b.log`.
- [x] On 2026-11-02, cloned thread roots now use explicit temporary owner
  identities instead of a root-graph query and positive-count fallback during
  first weaken. The lease ends after entry argument/CODE cleanup or at runtime
  close/reset. `ThreadSnapshotTemporaryRootOwnerTest` passes; the full no-sweeps
  `make` passes on `95be7ae91` in 7m29s. Log:
  `/private/tmp/issue-1649-snapshot-owner-make-95be7ae91.log`.
- [x] On 2026-11-02, semantic closure-pad owners now preserve weakly tracked
  referents through owner identities, without setting a synthetic positive
  count. Final capture release retries cleanup after the pending marker is
  retired. `CapturedAggregateWeakOwnerReleaseTest`, the no-sweeps `make`, and
  HTML::Tree 5.07 pass on the current candidate; both backends pass 989/989
  tests, with `t/refloop.t` 8/8. Logs are recorded in Current Status.
- [x] On 2026-11-02, added a cleared-aggregate regression for a nested package
  owner. Its slot identity preserves the weak observer through scope cleanup
  with zero root queries, while the shared snapshot remains for legacy
  ownerless candidates. The no-sweeps `make` and both ownership matrices pass
  on `320651865`; logs are recorded in Current Status.
- [x] On 2026-11-02, legacy semantic closure-pad cleanup now stores its
  deferred-final-release obligation independently of `refCount`, preserving
  zero while a capture remains and dispatching cleanup when its final owner
  slot leaves. `LegacyClosureCaptureOwnerReleaseTest` and the existing
  `ViaLayerCountedOwnerLifecycleTest` pass; the no-sweeps full `make`, 117-test
  lifecycle matrix, and HTML::Tree 5.07 989-test suite pass on both backends.
  Evidence is recorded in Current Status for `2cc19ab4d`.
- [x] On 2026-11-02, regex callback identities now record whether they acquired
  a paired CODE count. Final callback release only decrements that count, and
  verified count restoration includes the native slot. The focused regression
  and full no-sweeps `make` pass on `4b772241d`; logs are recorded in Current
  Status.
- [x] On 2026-11-02, tied-hash iteration now retains the current key through a
  stable owner slot and releases it at NEXTKEY, exception, exhaustion, and
  reset boundaries. Tracked and weakly tracked cases pass the focused test;
  the full no-sweeps `make` passes on `830c7690d`.
- [x] On 2026-11-02, queued END callbacks now transfer ownership from the
  current queue-cell identity to an `ACTIVE_CODE` identity spanning dequeue
  and invocation. Final cleanup consults the exact pending END cell instead of
  a generic CODE-owner fallback or synthetic positive count. The parent
  regression, full no-sweeps `make`, and 35 END lifecycle assertions on system
  Perl and both backends are recorded in Current Status.
- [x] On 2026-11-02, tail-call cleanup now uses an active lexical owner identity
  before the legacy binding marker, preserving the weak-tracking sentinel
  instead of synthesizing `refCount=1`. Parent failure, full no-sweeps `make`,
  and both-backend Perl evidence are recorded in Current Status.
- [x] On 2026-11-02, deferred cleanup recognizes counted scalar-cell identities
  through current array/hash membership without changing the identity or count.
  Parent regression and full no-sweeps `make` evidence are recorded in Current
  Status; raw bare-decrement producers remain under audit.
- [x] On 2026-11-02, first-bless mortal references now carry a counted owner
  identity through their deferred release. Weak bless sources preserve the
  existing no-strong-alias behavior. The parent regression, focused shard, and
  full no-sweeps `make` pass on `3cbff31fb`; the remaining raw producers are
  `RuntimeScalar.undefine()` and `RuntimeBase.releaseClosureCapture()`.

### Historical handoff: issue #1132 / PR #1204

This section records the earlier issue-linked PR
[#1204](https://github.com/PerlOnJava/pull/1204) handoff. Its branch was
`fix/issue-1132-closure-lifetime`; retained commits were `5b693177f`,
`1702edb70`, `4832540d6`, `9fcab6e81`, and `87b4fdee3`. These historical
branch instructions do not define the current continuation.

The Net::Async::HTTP 0.50 distribution is installed through `jcpan` in the
local CPAN cache. JVM and interpreter `t/30timeout.t` and `t/32remove.t` now
pass. Preserve both focused ownership regressions when evolving the ledger.

### Next Steps

1. Trace each remaining deferred-release, first-weaken, cleared-aggregate,
   closure, and destruction fallback to its strong-edge acquisition, transfer,
   and release sites. Distinguish already-ledgered edges from true root walks.
2. Continue the direct-count producer audit, including `unweaken()`, package
   roots, cycles, scalar-reference contents, shared storage, and resource
   owners. Trace count disagreements to the producer, transfer, or cleanup
   operation that creates them; `DESTROY` resurrection now depends on owner
   identities rather than a positive count.
3. Replace lifecycle decisions based on root snapshots, call depth, and
   fabricated counts with current owner identities and pending-release state.
4. Remove compatibility pins, corrective-sweep hooks, and unused recovery
   caches only after their normal-execution producers and release paths are
   covered.
5. Run the no-sweeps closure, weak/DESTROY/resurrection/cycle, HTML-Tree 8/8,
   async-lifetime, and full integration gates on the final immutable candidate;
   then reconcile and validate PR #1938 before publication.

### Open Questions

- Can additional compiler-created aggregate temporaries be made explicit in
  the owner ledger, rather than relying on opcode-specific transfers?
