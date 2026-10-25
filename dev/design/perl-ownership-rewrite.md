# Perl-compatible ownership rewrite

**Status:** Phase 1 closure-pad path is complete. Phase 2 remains in progress for [draft PR #1816](https://github.com/PerlOnJava/pull/1816), last reconciled with `origin/master` through `aeda28344`. The latest ownership candidate, tie-wrapper owner-slot migration, passes full `nice -n 19 make` locally; pushing and hosted validation are pending. Hosted run `39991272777` passes on Linux and Windows, including both thread gates, for preceding head `c1167ffbd`. Owner slots still bridge to `RuntimeBase.refCount` for dispatch, and default owner-slot accounting remains incomplete.

**Tracking:** [#1649](https://github.com/fglock/PerlOnJava/issues/1649)

**Baseline:** `c38ef8adff2b8067c4ff51fa929ec491f89baa8e` (2026-10-05)

This document records the implementation contract, migration boundaries, and
evidence for the ownership rewrite proposed in #1649. The older
`refcount_alignment_plan.md` describes historical fixes to the selective
counter; it does not define this rewrite's phase boundaries.

## Contract

Represent each Perl-visible strong owner as an explicit owner slot with a
unique identity and an owner kind. Acquiring, transferring, and releasing a
slot go through one API. Referent lifecycle state is separate from its
nonnegative strong-owner count. Counted cycles remain retained for the owning
runtime while their Perl owner count is positive. At an unmigrated boundary,
an explicit legacy owner bridge accounts for the edge; no edge may be counted
by both systems or by neither.

Java references and `RuntimeScalar` wrappers do not by themselves imply Perl
owners. In particular, lexical aliases, borrowed call arguments, aggregate
slots, closure pads, and mortal temporaries have different ownership rules.
Every path must state whether it creates an owner, borrows a cell, transfers an
owner, or creates a deferred mortal owner.

During migration, `activeOwnerCount()` and `activeOwnerSnapshot()` describe
only the compatibility `activeOwners` registry. They must not be widened to
include explicit slots: several recovery and diagnostics paths need to tell
whether the legacy registry itself recorded an owner. Use
`countedScalarOwnerCount()` / `countedScalarOwnerSnapshot()` when the caller
needs the identity-deduplicated union of legacy entries and `SCALAR_VALUE`
slots. Keep that distinction until the compatibility registry is removed.

## Baseline ownership map

| Concern | Current implementation | Phase 1 implication |
|---|---|---|
| Referent identity and lifecycle | `RuntimeBase.refCount` combines selective counts with `-1`, `-2`, and `Integer.MIN_VALUE` sentinels; `blessId`, destruction flags, and owner metadata are separate fields | Count and lifecycle must be split for migrated referents; the legacy fields remain behind the boundary for unmigrated referents |
| Scalar owner identity | `RuntimeScalar` is both a Perl scalar cell and a Java wrapper; its copy constructor does not acquire a referent count; `refCountOwned` is a Boolean on the wrapper | A Boolean cannot describe multiple owner kinds or transfers; ownership token identity must follow the slot, not Java object reachability |
| Reference creation | `RuntimeScalar.createReference`, aggregate `createReference` methods, blessing, and scalar-reference promotion create reference values with different initial tracking states | Each producer must initialize the same identity/lifecycle contract or cross the bridge explicitly |
| Scalar stores | `RuntimeScalar.setLargeRefCounted` adjusts referent counts, ownership metadata, scalar-reference contents, weak state, IO state, and resurrection recovery | Central retain/replace/release ordering must preserve self-assignment and destructor reentrancy |
| Aggregate stores | `RuntimeArray` / `RuntimeHash` mutation paths copy scalar wrappers and use `incrementRefCountForContainerStore`; deletion and scope cleanup are handled separately | The first path cannot stop at lexical scalar stores; container slot acquisition and removal must use the same ownership contract |
| Captures and calls | `RuntimeScalar` capture counters, `RuntimeCode`, call frames, argument arrays, and cleanup code each have special accounting | These remain legacy-bridged unless the first path directly crosses them; borrowed aliases must not be translated into owned slots |
| Deferred cleanup | `MortalList` queues decrements and uses owner metadata, weak registries, root proofs, and `ReachabilityWalker` fallback | Migrated releases must preserve Perl mortality ordering while removing whole-root reachability as the authority for migrated slots |
| Destruction and weak references | `DestroyDispatch`, `WeakRefRegistry`, global teardown, and scalar cleanup interpret the legacy sentinel state | The migrated owner count must be the sole dispatch authority for migrated referents; bridge tests must prevent duplicate dispatch |
| JVM cleanup | `EmitStatement`, `EmitControlFlow`, and `EmitBlock` emit lexical/aggregate cleanup; Java runtime methods are shared by both backends | Cleanup must release the same owner slot on fall-through, return, exceptions, loop exits, goto, and eval for JVM and interpreter |
| Interpreter cleanup | `BytecodeCompiler` emits `SCOPE_EXIT_CLEANUP*`; `BytecodeInterpreter` implements ordinary and exceptional cleanup | Opcode cleanup must use the same slot contract and preserve returned values |
| Cycles and runtime teardown | `ReachabilityWalker` makes corrective decisions over Java object graphs; `PerlRuntime` owns execution state | Migrated positive-count cycles need explicit per-runtime semantic retention and a defined teardown path |

### Boundary finding

A scalar-lexical-only migration is not safe. The same referent may move between
a scalar cell and an array/hash slot, and scalar wrappers may represent copies
or borrowed aliases. Their counter transitions also interact with closure
captures, return values, `MortalList`, weak clearing, and `DESTROY`. Turning on a
new scalar-only counter would omit owners at aggregate stores and could dispatch
destruction while a legacy owner still exists.

This finding rules out treating a scalar-only migration as a fix for #1618.
The reduced reproducer crosses closure pads and aggregate slots, so Phase 2
must cover those boundaries in the ownership model. The current branch now
passes HTML::Tree's integration tests with the existing selective-count and
reachability machinery; that result is useful compatibility evidence, but it
does not prove the planned owner-slot migration. `undef $sub` is a scalar slot
overwrite, not the separate `undef &sub` CV-body operation.

The first verified fix is at the reachability boundary. The interpreter's
`capturedVars` array is execution metadata and can still contain a pad after
that pad has been removed from the closure's semantic capture-owner list. The
reachability walker treated this retired metadata as a live strong edge, found
a self-cycle, and stopped `releaseCaptures()` from releasing the remaining
pads. The closure owner lists are now authoritative for this path. The new
dependency-free regression reproduces the lifecycle, passes on Perl 5.44 and
both PerlOnJava backends, and fails on the parent interpreter backend.

This fixes the demonstrated path and wires typed `PerlOwnerSlot` identity into
closure-pad semantic ownership, including transfer when a captured pad changes
referents and idempotent release. Runtime-scoped retention follows active
slots. Captured array/hash pads now use their owner-slot count directly and do
not also increment the selective count; `B::SV::REFCNT` includes those slots,
and final scope-exit cleanup is dispatched when the last slot releases. Other
unmigrated references to the same referent remain represented by the selective
legacy count, so mixed lifetimes compose without counting the captured edge in
both systems. Scalar captures still use the explicit legacy bridge. The
selected aggregate-capture path also covers weak/unweaken transitions,
callback reentrancy, resurrection, replacement, and exception cleanup on both
backends. The independent lifecycle state records destruction separately from
sentinel interpretation at remaining legacy boundaries. Scalar stores and
array/hash-element ownership remain outside this path. Phase 1 is complete only
after the slot identity/count/lifecycle contract, positive-count cycle
retention, and bridge cover the selected path end to end on both backends.
Phase 2 extends that path across scalar proxies and array/hash ownership, with
HTML::Tree as the integration exit criterion.

## Phases

| Phase | Scope | Exit evidence |
|---|---|---|
| 0. Oracle and boundary audit | Pin Perl 5.44 oracle; reproduce #1618 and #1642; inventory ownership edges; capture baseline behavior and cleanup costs | Oracle configuration, permanent reproducers, source/sink inventory, and a selected path with a demonstrated boundary |
| 1. First complete ownership path | Minimum owner identity/count/lifecycle API, retain/release/transfer primitives, runtime-scoped positive-count cycle retention, and explicit legacy bridge for one selected path | Enable the complete path by default on both backends; a permanent Perl-level regression changes from failing to passing; primitive and boundary tests pass |
| 2. Aggregate ownership and HTML::Tree | Extend complete paths through scalar/proxy and array/hash operations, replacement, deletion, aliases, calls, cleanup, and destruction needed by HTML::Tree | Migrated paths are default-enabled; unchanged HTML-Tree 5.07 `t/refloop.t` passes 8/8 with correct counts and destruction |
| 3. Calls and captured values | Migrate remaining argument/list ownership, return values, mortal temporaries, closures and pads together with cleanup on all exits, nested eval and fallback | Lifecycle regressions pass by default; migrated ordinary cleanup and count queries need no corrective whole-root scans |
| 4. Remaining ownership families | Complete globs, code/constants, tied/magic and provider boundaries, refcount APIs, thread cloning/shared lifetimes, resources, runtime reset and global destruction | Every supported family is migrated and passes differential/integration tests |
| 5. Qualification and legacy removal | Ecosystem/performance acceptance, stabilization, then removal of obsolete accounting and recovery | Final acceptance passes before and after removal with CPU, allocation, heap and deterministic cleanup costs recorded |

Phase numbers are delivery boundaries, not estimates of the total project.
The 20–36 engineer-week estimate remains provisional until the ownership audit
and first path establish implementation cost.

Tracked package-global CODE refs (`refCount >= 0`) receive `SCALAR_VALUE` slots
through scalar assignment; untracked CODE refs (`refCount == -1`) correctly
remain outside counted ownership. `RuntimeScalar.undefine` now releases the
tracked slot when present and retains the legacy bridge for older or untracked
cells. The global CODE path performs this release before its early return
through `RuntimeCode.undefineCodeReference`; saved aliases still observe the
undefined CV. A focused Java regression verifies the final tracked CODE owner
retires its slot and still dispatches lifecycle cleanup. `nice -n 19 make`
passes in 13m26s on this batch.

## Progress tracking

### Current status: Phase 2 in progress; Phase 1 merged in PR #1653 as `84f32be179bdc2b351c3eaae1cb26df39964ab6b` (2026-10-07)

The #1794/#1795 fixes and #1649 work remain in [draft PR #1816](https://github.com/PerlOnJava/pull/1816). The branch includes the current-master merge at `aeda28344`; the latest confirmed pushed head before the local tie-wrapper batch is `b9382dbaf`. Focused Mojolicious controller/WebSocket and Catalyst tempfile regressions pass on both backends with loopback access. On the tail-call candidate, the interpreter tempfile test exceeded a 180-second run but completed 24/24 within a 240-second JFR run; a later 240-second profile after owner-proof caching emitted no TAP, while an unprofiled run completed 24/24 within the 600-second limit. Sampled `isRootedAggregateOwner` frames declined from 517 to 190, with 112 cache-hit frames; the profiled runs had different total sample counts, so wall-clock attribution remains inconclusive. HTML::Tree 5.07 passes all 23 files and 989 tests on both backends on the preceding ownership candidate. Phase 2 remains incomplete: `refCount` still controls lifecycle dispatch, and the slot ledger is not yet the default accounting source across general scalar/proxy stores, aliases, and cleanup. Distinct scalar-reference aliases keep separate reachability identities while contributing one referent count per scalar cell. The HTML-Tree result is compatibility evidence, not the Phase 2 owner-slot exit proof.

### Current batch

- Postfix increment/decrement now transfers the stable `SCALAR_VALUE` slot from
  the mutated lvalue to its returned scalar. Assignment of that result releases
  the temporary through the slot API after acquiring the destination owner.
  New Java coverage checks slot identity and verifies one owner is represented
  by exactly one live slot for both `++` and `--`. This closes a ledger mismatch
  where the old implementation manually decremented `refCount` but left the
  temporary's owner slot active. Full `nice -n 19 make` passes in 14m45s; both
  focused Java cases pass.
- `Storable.releaseApplyArgs()` now releases materialized argument owners through
  `SCALAR_VALUE` slots when available, retaining the legacy fallback for older
  cells. Its existing Java regression retires the legacy ownership flag before
  release and verifies the slot and count both clear; this now exercises the
  direct slot-release API rather than detaching the slot after a manual count
  decrement. Full `nice -n 19 make` passes in 15m05s; the focused test passes
  with zero failures.
- `RuntimeGlob.dynamicRestoreState()` now retires a tracked temporary CODE
  scalar through its `SCALAR_VALUE` slot before dispatching final cleanup, with
  the legacy bridge retained for older cells. `RuntimeGlobCodeOwnerSlotTest`
  verifies the slot and count both retire. Full `nice -n 19 make` passes in
  13m21s on this batch.
- On 2026-10-24, tied scalar, array, hash, and handle wrappers now record
  handler ownership in distinct `TIE_WRAPPER` slots. A wrapper installed while
  its handler has the untracked sentinel remains uncounted until rooted owner
  recovery; the existing Java regression requires this so the wrapper cannot
  consume a later scalar owner's count. Root recovery includes live wrapper
  slots from rooted owner containers. The wrapper-slot batch passed full
  `nice -n 19 make` in 27m21s.
- The temporary `perlonjava.test.disableAggregateOwnerRecovery` diagnostic was
  removed after the no-recovery audit. The two production call sites in
  `MortalList.processDeferredBase` and `RuntimeScalar.setLargeRefCounted` still
  use recovery: six Java tests fail with the bypass because they directly test
  recovery from deliberately undercounted synthetic graphs. The root-chain
  and lifecycle tests directly construct scalar cells, mark them owned, then
  force the referent count below the live owners; the reachability-cost tests
  queue more decrements than they acquired owners. These fixtures do not
  identify a missing Perl store operation. Concretely, the six are
  `MortalListAggregateOwnerSlotRootChainTest` (two cases),
  `MortalListAggregateOwnerSlotTest.rootedHashEntrySlotRestoresAnUnderflowedLegacyCountUntilEntryRemoval`,
  `ReachabilityQueryCostTest.deferredOwnerChecksShareOneRootSnapshotPerDrain`,
  and two `UnblessedAggregateReachabilitySnapshotTest` cases. The first three
  assign an artificially low `refCount` after constructing active owner slots;
  the last three add three counted owners and then queue three generic
  referent decrements without releasing those owners. The JVM
  global-destruction wrapper timed out its nested child at 300 seconds, while
  the underlying Catalyst lifetime test passed directly and the wrapper passed
  on the interpreter; that wrapper result is inconclusive.

  The recovery scan has two production callers. In
  `MortalList.processDeferredBase`, a queued slot release is completed, any
  transient trace owner is retired, and the legacy `refCount` is decremented;
  if it reaches zero, recovery rebuilds the count from rooted aggregate slots,
  scalar-value slots, tie wrappers, and reachable counted scalar owners before
  lifecycle dispatch. In `RuntimeScalar.setLargeRefCounted`, overwriting a
  scalar releases the displaced scalar-value owner and decrements the old
  referent; at zero, the same recovery protects any still-rooted aggregate
  owners. The two production scan callers remain in
  `MortalList.processDeferredBase` and `RuntimeScalar.setLargeRefCounted`.
  Removing the scan is blocked on real operations that can produce these
  mismatches and on direct accounting for aggregate slots and
  scalar-reference contents; the current synthetic tests are not sufficient
  evidence to replace the scan. After the diagnostic review, default behavior
  passes full `nice -n 19 make` in 39m43s; offline documentation link checking
  also passes. A source audit found generic deferred decrements that do not
  carry a semantic owner slot. The fallback in
  `RuntimeScalar.deferOwnedScalarReferenceContents` was unreachable: it
  required an active contents slot after the method had already returned
  through that slot's deferred-release path. Removed the dead generic decrement
  and added Java coverage that verifies the contents slot remains active until
  drain, then releases exactly once. `RuntimeCode.releaseMethodInvocantHold`
  queues a generic deferred decrement. Its ordinary path is balanced:
  acquisition adds one count, and release either subtracts it or queues exactly
  one decrement for the boundary drain. The interpreter tracks holds by frame
  and abandons them on exceptional/non-local exits through
  `releaseAbandonedMethodInvocantHold`, which decrements directly and dispatches
  `DESTROY` at zero without consulting owner recovery. The project-owned
  `issue_1649_abandoned_method_invocant_hold.t` now verifies the critical
  ordering: an argument exception abandons the call while a hash still owns the
  invocant, and deleting the hash entry later fires `DESTROY`. It passes 3/3 on
  system Perl, JVM, and interpreter. No accounting mismatch is reproduced, so
  retain the current lifecycle decision while migrating transient holds to
  explicit slots as part of the broader ownership phase.
- The recovery-disabled audit had exposed an interpreter regression in
  `issue_1649_recursive_builder_pad_release.t`: the interpreter retained all
  five weakly observed nodes after dropping the root (expected two), even with
  recovery enabled. Commit `9adc112aa` preserves captured lexical owners across
  return cleanup. On 2026-10-25, the permanent regression passed all 9
  assertions on system Perl, JVM, and interpreter, confirming the root release
  and subsequent escaped-subtree release. This closes that interpreter
  blocker; it does not establish that aggregate-owner recovery is removable.
- On 2026-10-25, a two-node reducer narrowed a related interpreter failure to
  replacing the final lexical root with `undef` when the child has a weak
  parent edge. System Perl passes, and lexical scope exit releases the same
  tree under the interpreter; explicit assignment leaves both weak observers
  alive. The failure also reproduces with aggregate-owner recovery disabled.
  Existing `weak_blessed_tree_descendant_cleanup.t` and `weaken_destroy.t`
  exercise scope exit, so the new project-owned regression covers explicit
  replacement. A refstate diagnostic call changes the timing and makes the
  test pass, so its count output is not a reliable oracle for the missing edge.
  Invalidating cached reachability for the displaced referent as well as its
  scalar cell fixes the focused reducer and the existing recursive-builder
  regression. Both pass on JVM and interpreter with aggregate-owner recovery
  disabled; system Perl passes the new four-assertion reducer. Full
  `nice -n 19 make` passes in 13m09s. The #1794 controller-owner regression
  passes 3/3 and the #1795 Catalyst tempfile regression passes 24/24 on both
  backends. The WebSocket integration test skipped because loopback sockets
  were unavailable in the sandbox, then passed with extended access: the
  WebSocket controller test passes 6/6 and callback/long-poll lifetime passes
  29/29 on both backends. With aggregate-owner recovery disabled, the #1794
  controller, both WebSocket tests, #1795 tempfile test, void-context hash
  owner test, captured scalar/array argument test, and aggregate-capture owner
  test all pass on both backends. Recovery remains enabled by default; its two
  production callers still need independent direct-accounting proof, and the
  synthetic Java tests still directly require the recovery behavior.
- The full no-recovery audit reached 1,300 tests in shard 0 and failed six Java
  assertions that directly require `restoreReachableAggregateOwnerCount` to
  rebuild an artificially undercounted referent; the other 1,294 passed, with
  three skipped. Since Gradle stopped at shard 0, shards 1–4 were run
  separately with recovery disabled and all passed. Focused #1794/#1795
  integration tests and selected #1649 lifetime tests also pass on both
  backends with recovery disabled. The diagnostic switch is now removed; keep
  the recovery method and its two production callers until direct accounting
  proves those paths no longer need it.
- A second focused reducer,
  `issue_1649_captured_scalar_array_argument.t`, isolates the same class of
  lifetime error: a closure writes a blessed object to a captured scalar while
  receiving an array-reference argument, then the closure and returned owner
  are released. System Perl and the JVM pass both assertions; the interpreter
  failed the weak-observer release assertion with aggregate recovery both on
  and off, while `DESTROY` fired once. A temporary `Internals::SvREFCNT` probe
  showed Perl reporting 2 owners after the closure call and 1 after closure
  release; the interpreter reported 0 at both points. The trace narrowed the
  transition to return cleanup emitted for a captured lexical register. A
  permanent test now reproduces this on the unfixed interpreter parent and
  passes on system Perl and the JVM. A runtime frame-mark guard around return
  cleanup also makes the focused interpreter test and the 24-assertion
  Catalyst tempfile interpreter test pass, and its consolidated
  `nice -n 19 make` passed in 35m17s, including the Mojolicious lifetime tests.
  The first guard marked compiler register-index metadata as captured, but that
  metadata also contains non-capture entries. It failed the interpreter's
  recursive-builder release check (five weakly observed nodes remained instead
  of two). Limiting metadata to the actual capture count compiled and passed
  the full `make`, but still missed both interpreter reproductions because
  captured registers are filled later by `withCapturedVars`. Marking those
  registers when the closure copy receives its capture array fixes the scalar
  regression. This all-type frame-aware patch passed full `make` in 14m27s, the
  scalar interpreter test, and Catalyst 24/24. The recursive-builder
  interpreter test still fails (five nodes remain rather than two) with
  recovery enabled and disabled, so it is a separate existing failure. A
  scalar-only variant retained the scalar and Catalyst passes but made
  `catalyst_request_body_tempfile_global_destroy.t` exceed its 300-second child
  timeout; that variant was discarded. The all-type fix was committed as
  `9adc112aa`; its final `nice -n 19 make` passed in 31m15s. The permanent
  regression passes with system Perl and the interpreter, and Catalyst passes
  24/24 on the interpreter. The recursive-builder failure remains open. The
  earlier static filter also caused unrelated cleanup failures. Aggregate
  recovery remains enabled while its two production callers await direct
  ownership accounting.
- On 2026-10-24, the temporary scalar owner installed for `DESTROY`'s self
  argument now balances through the `SCALAR_VALUE` slot API without invoking
  destruction recursively inside the active DESTROY frame. A focused Java test
  checks slot and compatibility-registry retirement when the legacy flag has
  already cleared. Full `nice -n 19 make` passes in 14m on `108c06094`.
- On 2026-10-24, tracked scalar deletion from a stash now releases the scalar
  owner through its `SCALAR_VALUE` slot, keeping the legacy bridge for cells
  without explicit slots. The existing retired-flag regression now also checks
  that the compatibility active-owner registry and slot ledger both clear.
  Full `nice -n 19 make` passes in 14m53s on `54ca5153f`.
- On 2026-10-24, materialized scalar-copy cleanup in list assignment now uses
  the `SCALAR_VALUE` release API, keeping the legacy bridge for cells without
  explicit slots. The shared helper covers discarded values assigned to
  scalar, array, and hash targets, including the undefined-target and simple
  scalar-assignment paths. A Java regression verifies slot retirement after
  the legacy flag is cleared. Full `nice -n 19 make` passes in 16m53s on
  `e9c32a68e`.
- On 2026-10-24, removal of newly localized hash and array proxy entries now
  releases the contained scalar's direct referent through its `SCALAR_VALUE`
  slot, retaining the legacy fallback for cells without explicit slots. Two
  Java regressions retire the legacy flag while keeping each slot active, then
  verify restoration removes both scalar and aggregate owner slots and destroys
  the displaced referent. Full `nice -n 19 make` passes in 13m28s on
  `a0ff791ea`.
- On 2026-10-24, global `local` restoration now releases a displaced direct
  scalar owner through its `SCALAR_VALUE` slot, while retaining the legacy
  fallback and the existing last-owner `DESTROY` decision after the global
  binding is restored. The regression keeps the slot active after retiring
  `refCountOwned` and verifies the localized referent is destroyed while the
  original global remains owned. Full `nice -n 19 make` passes in 14m47s on
  `a547b89b8`, including the new regression.
- On 2026-10-24, `dynamicRestoreState()` now retires a displaced localized
  referent through its `SCALAR_VALUE` owner slot before restoring the saved
  scalar state, while preserving post-restore `DESTROY` dispatch. A focused
  Java regression covers a retired legacy `refCountOwned` flag with an active
  slot and verifies that the restored referent remains owned. Full
  `nice -n 19 make` passes in 17m on this batch. The Catalyst tempfile file
  passes 24/24, the #1794 controller-owner file 3/3, and the Mojolicious
  WebSocket release file 6/6 on both JVM and interpreter. The prior ownership
  cases and lifecycle bridge remain open; Phase 2 is incomplete.
- Array and hash tails in nested list assignment transfer materialized cells
  through their owning-store paths. This releases displaced values, acquires
  scalar ownership idempotently, and registers structural slots on the
  destination's owner-aware container. Tied and autovivifying hashes use their
  normal assignment path instead of adopting a temporary container. Java and
  Perl-level regressions cover both transfer paths. The hash-tail regression
  passes 4/4 on system Perl, JVM, and interpreter, and fails all four assertions
  on the unfixed JVM and interpreter parent. Immutable code commit
  `85daddac0` passes full `nice -n 19 make` in 17m20s.
- The hash-tail validation record was pushed in `993d5875e`. Both JVM and
  interpreter regressions pass 4/4 after the fix; the unfixed parent fails
  4/4 on each backend. The latest PR head has no hosted checks attached yet.
- Explicit `undef` now releases a counted direct scalar owner through its
  `SCALAR_VALUE` slot, decrementing the bridged count in the same operation;
  `undefine()` still owns the local-binding and lifecycle decision. The Java
  regression verifies that both the slot and count retire. Full
  `nice -n 19 make` passes in 23m10s on the source committed as `29372cac0`.
  The #1794 controller/WebSocket regressions pass 5/5, 6/6, 6/6, and 29/29 on
  both backends with loopback access. Catalyst tempfile regressions pass 24/24
  and 3/3 on both backends; the controller-owner release regression passes
  3/3 on both.
- Deferred scalar cleanup now carries `SCALAR_VALUE` slot identity into the
  mortal queue for ordinary scope release, recursive blessed/unblessed
  container cleanup, and `pop`/`shift`. Slot retirement and count decrement
  remain ordered around the existing mortal drain; cells predating explicit
  slots keep the legacy queue path. A Java regression checks token identity
  and delayed decrement. Full `nice -n 19 make` passes in 16m10s on
  `2c05509e6`. The Mojolicious, Catalyst tempfile, and controller-owner
  lifetime files pass on JVM and interpreter after this change.
- Immediate tail-call cleanup now uses the scalar slot release API for both
  argument cells and CODE references. Existing stash, local-binding,
  reachability, and DESTROY decisions remain in `MortalList`; a Java regression
  covers final CODE release. Full `nice -n 19 make` passes in 18m28s on
  `fad2199eb`. The focused lifetime files pass on both backends, with the
  interpreter Catalyst tempfile file requiring a 240-second limit after a
  180-second run stopped at assertion 13. Its JFR samples show external-root
  snapshot construction and aggregate-owner recovery as the dominant work.
- Aggregate owner recovery now memoizes root proofs by owner identity for the
  duration of one restore call. The same excluded referent and graph remain
  fixed throughout that scan. A deterministic test with 64 hash-entry slots
  verifies that all 64 owners are restored with one proof and 63 cache hits.
  Full `nice -n 19 make` passes in 28m41s on `ca58da7c4`. The interpreter
  Catalyst tempfile regression passes 24/24 without profiling. JFR sampled
  `isRootedAggregateOwner` 517 times before and 190 times after the change,
  plus 112 cache-hit frames; the profiled after-run hit its 240-second limit
  before TAP output, so these samples do not establish a wall-clock speedup.
- On 2026-10-24, aggregate-owner recovery now takes one owner-slot snapshot and
  classifies each slot in a single pass, combining hash, array, and scalar-value
  recovery. A deterministic counter verifies all 128 slots in the mixed test
  graph are inspected once, replacing three full scans. Full `nice -n 19 make`
  passes in 15m56s on `6730377d1`. The Catalyst tempfile test passes 24/24 and
  controller-owner release passes 3/3 on both backends; the WebSocket
  controller-release regression passes 6/6 on both backends with loopback
  access. This reduces snapshot allocation and slot traversal; it does not
  replace the legacy `refCount` dispatch bridge.
- On 2026-10-24, `WeakRefRegistry.weaken()` now consumes an active
  `SCALAR_VALUE` slot through `releaseScalarValueOwnerCount()`; cells without an
  explicit slot retain the legacy decrement path. The existing regression
  explicitly retires `refCountOwned` before weakening and verifies that the
  slot is detached and the count is decremented exactly once. Full
  `nice -n 19 make` passes in 31m48s on `c21116699`. Catalyst tempfile (24/24),
  controller-owner release (3/3), and WebSocket controller release (6/6) pass
  on both backends; the WebSocket run used loopback access.
- On 2026-10-24, `WeakRefRegistry.unweaken()` now restores the strong count and
  owner ledger through the scalar-value slot acquisition API. The new Java
  regression covers the full weaken/unweaken cycle, including count restoration
  and slot reactivation. Full `nice -n 19 make` passes in 26m14s on
  `6a4d65840`. The bridge still updates `refCount` because lifecycle dispatch
  has not yet moved to the explicit owner ledger.
- On 2026-10-24, `incrementRefCountForContainerStore()` now acquires the direct
  scalar count and owner registries through the `SCALAR_VALUE` slot API. Existing
  aggregate ownership tests verify separate copied cells still acquire distinct
  owners and active slots are not double-counted. Full `nice -n 19 make` passes
  in 23m54s on `09988c53e`.
- On 2026-10-24, the tracked scalar-store retain now uses the same slot API,
  installing the new scalar owner before releasing the displaced referent. A
  self-assignment regression checks that the count and legacy owner registry
  remain at one. Full `nice -n 19 make` passes in 16m12s on `2a2b9fe3b`. The
  Catalyst tempfile, controller-owner, and WebSocket controller-release files
  pass on both backends; WebSocket ran with loopback access.
- On 2026-10-24, scalar overwrite now releases the displaced direct owner
  through its `SCALAR_VALUE` slot, then reuses the stable slot for the new
  referent. Lifecycle dispatch remains after the assignment's existing rescue
  and reachability checks. A Java regression retires the legacy Boolean before
  overwrite and verifies old/new counts, registries, and slot identity. Full
  `nice -n 19 make` passes in 20m05s; Catalyst tempfile, controller-owner, and
  WebSocket controller-release regressions pass on both backends.
- The preceding array-tail fix routes `removeFirst()` and `removeLast()` through
  structural mutation hooks so `shift` and `pop` retire array slots. Its
  immutable code commit `ecc5efd37` passes full `nice -n 19 make` in 13m34s,
  and the Perl-level regression passes on system Perl and both backends.
- Followed live `SCALAR_VALUE` and `SCALAR_REFERENCE_CONTENTS` slots from an
  aggregate toward their containing aggregate or lexical root. Deferred
  aggregate-owner recovery now avoids whole-root walks through these scalar
  owner paths. Java regressions prove exact count restoration with zero root
  queries and graph snapshots. Full `nice -n 19 make` passes in 21m54s; the
  root-chain test class passes 3/3. Hosted CI for head `27d6df476` passes on
  Linux and Windows, including the platform thread gates.
- Rebased PR #1816 onto master `5a6cf5ed6` before this batch's one full build.
  This keeps the validation aligned with current master and avoids rebuilding
  an obsolete candidate.
- On 2026-10-23, allowed scalar owner-slot identity to supply rooted array/hash
  recovery when the legacy ownership flag is absent, while retaining the flag
  for cells with no explicit slot. The Java regression covers array and hash
  owners, restoration, and release after removing their semantic slots. Full
  `nice -n 19 make` passes in 21m46s. The first hosted run had one failure in
  `dbic_deep_cycle_weak_shift_regression.t` (assertion 10) in shard 3. The
  regression passes in the filtered DBI/DBIC make and in shard 3's full normal
  order (14m34s); hosted rerun `39861290618` passes on Linux and Windows,
  including the platform thread gates.
- On 2026-10-23, direct scalar-owner snapshots began trusting the active
  `SCALAR_VALUE` slot when the legacy Boolean has retired. The Java regression
  checks the legacy registry and slot union, then verifies that releasing the
  slot removes the counted owner. Full `nice -n 19 make` passes in 22m49s;
  hosted run `39880315002` passes on Linux and Windows, including both thread
  compatibility gates.
- The next cleanup tranche lets deferred scalar release, captured release, and
  array `pop`/`shift` recognize the explicit scalar slot after the legacy flag
  retires. Java regressions verify slot retirement and the deferred legacy
  bridge decrement for each cleanup path. Full `nice -n 19 make` passes in
  22m25s on `759302bb7`; hosted run `39895645097` passes on Linux and Windows,
  including both thread compatibility gates.
- The following tranche extends the same scalar-slot predicate through
  recursive container clear, tail-call argument release, and void-result
  cleanup paths. Java regressions cover recursive and tail-call release;
  full `nice -n 19 make` passes in 33m11s on `5578d44bd`; hosted validation is
  pending.
- Lexical scope exit now recognizes an active scalar-value owner slot after
  `refCountOwned` retires. The focused Java regression passes as part of full
  `nice -n 19 make` (23m57s); documentation links pass. A follow-up inspection
  found the same retired-flag gap in explicit `undef`, including named CODE
  slots. That path now checks the scalar slot before clearing the cell, with a
  Java regression; full `nice -n 19 make` passes in 15m40s. The next bundled
  tranche extends slot authority through scalar overwrite, container-copy
  accounting, scalar-reference contents, closure-capture acquisition, and
  deferred captured cleanup. Java regressions cover these boundaries. Its
  full validation is pending. This branch began at `origin/master` commit
  `5a6cf5ed6`; `origin/master` advanced to `aeda28344`, including the Catalyst
  upload cleanup for #1795. The master changes were merged without rewriting
  the published ownership commits. The overlapping reachability changes were
  combined so statement-boundary candidates and active owner-slot roots both
  participate in capture pruning; the broader multipart and request-context
  regression remains in place. Full `nice -n 19 make` passes on the merged
  candidate in 20m47s, including the Catalyst lifetime test (1 test, 0 failures)
  and all owner-slot regressions. `make check-links` passes; hosted validation
  is pending.
- Follow-up batches now use explicit scalar slots through weak release,
  reachability, stash deletion, graph cloning, DESTROY argument balancing,
  blessed glob retention, list-copy cleanup, localized globals, Storable
  arguments, bytecode temporaries, dynamic state save/restore, and GLOB scope
  exit. Focused Java regressions cover retired-flag cases. The full local Make
  gate passes on candidate `9c93d5922` in 19m53s; hosted validation for that
  head is pending. The slot ledger remains a bridge to selective `refCount`,
  so Phase 2 is not complete.

The earlier candidate was based on `origin/master` (`ede542b29`) and passed the full local make in 22m55s, Ubuntu `make ci`, the Ubuntu Perl thread compatibility gate, SBOM generation, Windows `make ci` in about 52 minutes, and the focused Windows thread gate. Windows CI exposed two existing unit tests with POSIX `/tmp` paths; `make ci` now creates the runner's root-relative `tmp` directory before building. On blead Perl 5.45.4, the controller, WebSocket, Catalyst tempfile, owner-release, and writable `Internals::SvREFCNT` regressions pass. The controller regression passes 5/5 on JVM and interpreter; the WebSocket regression passes 6/6 on both; the Catalyst tempfile regression passes 24/24 on both; `refcount/issue_1794_controller_owner_release.t` passes 3/3 on both; and `internals_svrefcnt_write.t` passes 6/6 on both after failing all six assertions on its unfixed parent. The alias-count follow-up rebases onto `origin/master` (`18323a1ac`). Its focused make passes in 50s and its full local make passes in 31m30s on code commit `15e362ddb`.

### Completed

- [x] On 2026-10-22, synchronized the per-scalar reverse index of destroyable
  closure owners. Ithread cloning and parent-side temporary closure release
  could concurrently mutate its `ArrayList`, producing an intermittent
  `ConcurrentModificationException` in `threads-shared/t/object.t`. A focused
  Java regression races reverse-index releases. The external test passed 28/28
  when rerun alone. The bundled Java regression passes in full `nice -n 19
  make` (15m59s); `make test-threads` passes JVM virtual 1925/1925, interpreter
  virtual 1925/1925, and platform-focused 485/485. This batch is pending
  hosted CI.

- [x] On 2026-10-22, synchronized mutation and snapshot operations on the
  legacy direct-scalar-owner registry and changed `reachableOwnerCount()` to
  snapshot before graph walks. The new Java concurrency regression passes in
  the full `nice -n 19 make` (22m39s). The same candidate passes `make
  test-threads` in all three configurations: JVM virtual 1925/1925,
  interpreter virtual 1925/1925, and platform-focused 485/485. The first local
  thread-gate attempt lacked write access to the external Perl checkout; the
  passing rerun used extended access. The published PR head's CI rerun also
  passes on both platforms; this local batch still needs hosted validation.

- [x] On 2026-10-22, used the scalar-owner identity union in global
  destruction owner-delta checks and `MortalList` deferred-count restoration.
  The new Java regression proves that a package-rooted scalar slot still
  counts when its legacy registry entry is missing. Full `nice -n 19 make`
  passes in 56m43s; the global-destruction, tempfile, and controller-owner
  regressions pass 36/36 on both JVM and interpreter backends.

- [x] On 2026-10-22, unified direct scalar-owner queries across explicit
  `SCALAR_VALUE` slots and the legacy active-owner registry, deduplicating by
  scalar-cell identity. Cleanup guards, weak CODE checks, and localized CODE
  cleanup now see owners that predate legacy tracking. The two focused Java
  tests pass in the full `nice -n 19 make`; `make test-threads` passes JVM
  virtual 1925/1925, interpreter virtual 1925/1925, and platform-focused
  485/485. Eight focused lifetime regressions pass 79/79 on JVM and 73/73 on
  the interpreter.

- [x] On 2026-10-22, restore undercounted referents from a reachable standalone
  `SCALAR_VALUE` slot even when the scalar predates activation of the legacy
  `activeOwners` registry. The Java regression proves count restoration from
  the slot with no active-owner entry. Full `nice -n 19 make` passes in 19m26s.
  The lifecycle bridge still dispatches through `refCount`.

- [x] On 2026-10-22, made the targeted weak-release direct-owner proof consult
  explicit `SCALAR_VALUE` slots before the lazily populated scalar registry.
  A new Java regression creates the strong scalar owner before the first
  `weaken()` and verifies the slot preserves its referent without a root
  snapshot. Full `nice -n 19 make` passes in 18m12s. Local
  `nice -n 19 timeout 1800 make test-threads` passes all three configurations:
  JVM virtual 1925/1925, interpreter virtual 1925/1925, and platform-focused
  485/485. Hosted CI for the preceding commit had one `free2.t` failure; the
  follow-up hosted run is pending.

- [x] On 2026-10-22, added stable `SCALAR_VALUE` owner slots for direct scalar
  referents and synchronized them through assignment, postfix ownership
  transfer, dynamic localization, and release. Reachable-owner recovery and
  targeted weak sweeps recognize the new slot while preserving legacy
  refcount dispatch and aggregate root-query fast paths. Two focused Java tests
  cover stable slot transfer and localized-value restoration. Full
  `nice -n 19 make` passes in 20m56s; the currently published PR head's Linux,
  Windows, and thread compatibility checks pass. The default slot migration
  remains incomplete.

- [x] On 2026-10-21, corrected the array pop/shift guard after the rebased
  aggregate-alias change exposed false retention in HTML::Tree cleanup. Only
  Perl array lvalue aliases now suppress the removed slot's deferred
  decrement; internal work-array copies still release it. The existing
  project-owned HTML::Tree reproducers pass on system Perl, JVM, and
  interpreter; the full local `make` passes in 21m34s, and HTML::Tree 5.07
  passes all 23 files and 989 tests on both backends.

- [x] On 2026-10-20, rebased the #1794/#1795 candidate onto current master,
  fixed the remaining interpreter closure-capture and cleanup behavior, and
  validated the controller (5/5), WebSocket (6/6), Catalyst tempfile (24/24),
  and owner-release (3/3) regressions on both backends. The full `make` gate
  passes with the new two-worker and 3 GiB worker settings. Standard Perl
  5.45.4 passes the Perl-level regressions. Published the rebased candidate to
  PR #1816 as a draft; its Linux and Windows CI jobs are pending.
- [x] On 2026-10-20, added writable `Internals::SvREFCNT` behavior with a
  separate B::SV query path, plus a six-assertion Perl regression that passes
  on blead Perl 5.45.4 and both backends and fails on the unfixed parent. The
  complete local `make` gate passes on `dd368a80a`. Windows CI found two
  existing unit scripts that require `/tmp`; the PR prepares the ephemeral
  Windows runner path without changing those tests. This initial hosted run
  exposed the workflow's step-order contract and a Windows time-budget issue;
  both are resolved in the 2026-10-21 entry below.
- [x] On 2026-10-21, kept the CI workflow's fixed step contract, moved the
  Windows root-relative temp-directory setup into `make ci`, and configured
  four Gradle workers only on Windows after the two-worker gate exceeded its
  75-minute budget. The full local `make` passed in 22m55s. Ubuntu and Windows
  make gates, the Ubuntu thread compatibility gate, the Windows focused thread
  gate, and Ubuntu SBOM generation all passed on PR head `8a6c5a365`.
- [x] On 2026-10-21, followed typed hash/array owner slots through nested
  aggregate roots during deferred-release recovery and `DESTROY` resurrection
  checks. Two Java regressions assert the ownership result with zero root
  queries and snapshots. The full local `nice -n 19 make` passes on
  `ea7b77164` in 21m11s; hosted CI for this head is queued.
- [x] On 2026-10-21, added an explicit scalar-reference contents owner slot
  that follows a referenced scalar cell when it is overwritten. The Perl
  regression verifies replacement and final `DESTROY`/weak clearing; the Java
  test covers slot accounting. Testing exposed and fixed a duplicate legacy
  direct-store count on the same cell. The focused refcount gate and the full
  local `nice -n 19 make` pass on `f0f10ed0e` (27m56s); hosted Ubuntu and
  Windows CI are running.
- [x] Re-ran the unchanged HTML::Tree 5.07 suite on code head `f0f10ed0e`:
  all 23 files and 989 tests pass, including `t/refloop.t`. This confirms the
  ownership fix preserves the existing integration behavior; the suite still
  exercises legacy refcount/reachability paths and does not complete the
  planned default owner-slot migration.
- [x] On 2026-10-21, ran HTML::Tree 5.07 under the interpreter backend on code
  head `f0f10ed0e`; all 23 files and 989 tests pass, including `t/refloop.t`.
  PR #1816 remains open as a draft at `4eebdf5c4`, with hosted Ubuntu and
  Windows checks still in progress.
- [x] On 2026-10-21, added a 12-assertion scalar-reference alias test. B::SV
  reports one contents owner for two references to the same scalar cell on
  system Perl 5.34.1 and blead Perl 5.45.4. The pre-fix parent reports two on
  both backends; the rebased fix passes all 12 assertions on JVM and
  interpreter. The owner-slot Java tests cover alias acquisition, idempotent
  acquisition, retargeting, reachable-owner deduplication, and deferred and
  final release. Full `nice -n 19 make` passes in 31m30s on code commit
  `15e362ddb`; unchanged HTML::Tree 5.07 passes all 23 files and 989 tests on
  both backends, including `t/refloop.t`.
- [x] Revalidated `ordinary_aggregate_element_owner_refcount.t`: all 11
  standard-Perl count/destruction assertions pass on blead Perl 5.45.4, JVM,
  and interpreter. This covers scalar ownership through array/hash insertion,
  push, shift, delete, replacement, aggregate clear, and final release; Phase 2
  still requires replacing the legacy counters with default owner-slot paths.
- [x] On 2026-10-21, kept a referent alive when `pop`, `shift`, or hash deletion
  removes one owning aggregate slot while another array/hash slot still aliases
  the same scalar cell. Its 22-assertion Perl regression covers those removals
  and array/hash clear with a second owning aggregate. It passes on system Perl
  5.34.1, blead Perl 5.45.4, JVM, and interpreter; the failing pop/delete cases
  were reproduced on the unfixed parent on both backends. After rebasing onto
  `162d8eb84`, full `nice -n 19 make` passes in 31m15s on code commit
  `69d30792f`. Legacy
  `refCount` dispatch remains; the next work is default owner-slot accounting
  across ordinary aggregate stores and cleanup.
- [x] On 2026-10-22, fixed interpreter WebSocket callback lifetime after the
  tracked 29-assertion regression showed that an active callback's controller
  was destroyed early. External-root snapshots now follow persistent scalar,
  array, and hash state on installed CVs and continue through nested callback
  code to its captured values. The new Java regression covers that full owner
  chain; blead Perl 5.45.4 and both PerlOnJava backends pass the WebSocket test.
  Full `nice -n 19 make` passes in 19m15s, and HTML::Tree 5.07 passes all 989
  tests on both backends on code commit `05ca274a6`.
- [x] Re-read the parent issue's implementation contract and reviewed the
  independent lifecycle candidate handoff. Historical candidate test results
  are not treated as current Phase 1 evidence.
- [x] Mapped the principal referent, scalar, aggregate, call/capture,
  deferred-cleanup, lifecycle, cycle, JVM cleanup, and interpreter cleanup
  components.
- [x] Built an isolated Perl 5.44.0 oracle from the local source tag and
  reproduced unchanged HTML-Tree 5.07 `t/refloop.t`: Perl passes 8/8 while the
  parent JVM fails 4/8 with retained weak references.
- [x] Switched current unit-test oracle validation to the user's target Perl
  build at `/Users/fglock/projects/perl5/perl` (Perl 5.45.4, non-threaded).
  The recursive-pad and captured-pad-transfer regressions pass on that build
  and on both PerlOnJava backends; 5.44 remains historical baseline evidence.
- [x] Reduced the failure to recursive `new_from_lol` closure release and
  recorded an oracle-validated baseline reproducer at
  [`dev/repros/issue-1649-html-tree-lifecycle.t`](../repros/issue-1649-html-tree-lifecycle.t).
- [x] Added the permanent dependency-free regression at
  [`src/test/resources/unit/refcount/issue_1649_recursive_builder_pad_release.t`](../../src/test/resources/unit/refcount/issue_1649_recursive_builder_pad_release.t).
  Perl 5.44 and both PerlOnJava backends pass; the parent interpreter fails
  the release assertions.
- [x] Removed retired `InterpretedCode.capturedVars` entries from reachability
  ownership walks. The explicit capture-owner lists now determine whether
  closure pads are live Perl edges.
- [x] Added an oracle-validated parser lifecycle reproducer at
  [`dev/repros/issue-1649-html-treebuilder-lifecycle.t`](../repros/issue-1649-html-treebuilder-lifecycle.t)
  for the remaining content and incremental parsing failures.
- [x] Added typed owner-slot identity for closure pads, with acquire/transfer/
  release operations and a primitive identity regression. Existing selective
  refcount and deferred-release transitions remain the compatibility bridge.
- [x] Added a standalone oracle regression for captured-pad referent transfer,
  shared closure visibility, and exact final release. Perl 5.45.4 and both
  PerlOnJava backends pass 6/6.
- [x] Moved closure capture bridge counts into `PerlOwnerSlot` and attached
  stable owner-slot identity and sequence tokens to deferred releases. Added a
  Java regression for bridge acquisition and drain. `nice -n 19 make` passes;
  both Perl regressions pass on Perl 5.45.4 and on the JVM and interpreter
  backends.
- [x] Added runtime-scoped strong retention for referents with positive
  closure-pad owner-slot counts. Slot transfer/release and runtime lifecycle
  clear update the registry; a Java regression covers multiple slots and
  referent transfer. `nice -n 19 make` and `make check-links` pass.
- [x] Added permanent closure-pad tests for weak/unweaken transitions and
  controlled `B::svref_2object(...)->REFCNT` checkpoints. Perl 5.45.4, JVM,
  and interpreter agree on all five weak-lifecycle assertions and all four
  owner-count assertions.
- [x] Added a referent lifecycle state independent of the selective count
  sentinel, wired final dispatch and resurrection transitions through it, and
  made completed destruction idempotent. `PerlReferentLifecycleStateTest`, the
  full `nice -n 19 make` gate, and all four Perl ownership regressions pass on
  Perl 5.45.4 and both PerlOnJava backends.
- [x] Added real blessed `DESTROY` callback coverage for recursive dispatch
  and rescue followed by final destruction. The three focused lifecycle Java
  tests pass in the full gate for commit `4137ad8cd`; the rescued referent
  fixture preserves the destructor argument's owner while adding a distinct
  resurrection owner.
- [x] Added standard-Perl-validated exception cleanup coverage: failing
  destructors still allow subsequent destructors to run, clear weak references,
  emit the cleanup warnings, and preserve `$@`. The Perl 5.45.4 oracle and both
  PerlOnJava backends pass all ten assertions, including `DESTROY` triggered
  when a captured pad leaves scope. Java coverage checks that a throwing
  callback exits `DESTROYING`, resets its reentry guard, and reaches
  `DESTROYED` with the legacy terminal sentinel restored. The new Java
  assertion first exposed a leaked synthetic `$_[0]` owner (refcount 1) when
  the callback threw; `DestroyDispatch` now drains callback-created pending
  cleanup and balances that owner in `finally`. The full `nice -n 19 make`
  gate passes on `4137ad8cd`.
- [x] Guard the closure bridge against integer overflow before changing the
  referent or owner slot, and verify an unowned release is an underflow no-op.
  All three `PerlOwnerSlotLegacyBridgeTest` cases and the full
  `nice -n 19 make` gate pass on `f164ca168`.
- [x] Add optional runtime-scoped counters for root queries, seeded roots,
  inspected edges, visited nodes, and full-root snapshots. A deterministic
  test drives three real aggregate scope cleanups through
  `MyVarCleanupStack.unregister()` and `MortalList` against the same unrelated
  root graph: it observes three fresh root queries, at least 768 inspected
  edges and 771 visited nodes, and zero shared snapshots. `nice -n 19 make`
  passes on `3be05b505`.
- [x] Count deferred bases and external-root snapshot work in the same
  measurement. A deterministic test drains three separately queued weak
  referents retained by one package array; each reaches
  `MortalList.processDeferredBase()` and rebuilds the external-root snapshot,
  inspecting at least 768 edges and visiting at least 771 nodes. This measures
  the repeated deferred-cleanup fallback separately from aggregate
  scope-unregister queries. The full `nice -n 19 make` gate passes on the
  rebased tree.
- [x] Measure cleanup cost for the migrated captured-aggregate path. A
  deterministic test places 256 unrelated referents under a package root,
  exits a captured array pad, and releases its native owner slot; the release
  performs zero root queries, snapshot builds, or edge inspections. The full
  `nice -n 19 make` gate passes on the candidate tree committed as
  `75406d339` (8m05s, 2026-10-06).
- [x] Preserve END-block captures across assertion and callback cleanup.
  END CVs now retain their capture edges while queued and during invocation;
  a rescued selective count is released after the active phaser returns. Two
  permanent regressions cover DESTROY ordering inside END and shared lexical
  access across LIFO END blocks; additional regressions cover explicit `exit`
  dispatch, `die`-initiated shutdown, nested `exit` from END, and continuing
  the END queue after a callback dies. Nested exit previously became a false
  END failure; it now propagates as control flow. A callback `die` now records
  its error, continues the queue, and matches Perl 5.45.4's exit status 22.
  All six END lifecycle regressions pass on Perl 5.45.4 and both PerlOnJava backends.
  The runtime-reset contract still propagates a failing END after cleanup.
  The full `nice -n 19 make` gate passes on `78369acc9` (8m33s).
- [x] Keep array/hash referents alive through queued END closures, then release
  them when the phaser clears the captured lexical. The new permanent
  `issue_1649_end_aggregate_capture_lifecycle.t` passes 6/6 on Perl 5.45.4,
  JVM, and interpreter, including exactly-once `DESTROY` and weak clearing.
- [x] Add a permanent captured-aggregate pad regression at
  [`issue_1649_captured_aggregate_pad_lifecycle.t`](../../src/test/resources/unit/refcount/issue_1649_captured_aggregate_pad_lifecycle.t).
  The test covers array and hash captures, temporary aggregate-only closures,
  and exact `DESTROY` counts; all fifteen assertions pass on Perl 5.45.4 and
  both PerlOnJava backends. Diagnosis found that assignment-form
  `my @array = ...` and `my %hash = ...` omitted `REGISTER_MY_VAR` in
  interpreter bytecode, so the statement-boundary weak sweep could miss live
  lexical aggregates. The assignment compiler now registers both aggregate
  types, and the interpreter also tracks aggregate-only closures for frame-exit
  capture release. The full `nice -n 19 make` gate passes (6m43s,
  2026-10-06).
- [x] Register BEGIN-backed scalar and aggregate lexical cells when the
  interpreter retrieves them into the main pad, so runtime sweeps see these
  live owners. The unfixed interpreter cleared blessed values still owned by
  the main lexical scope after UNITCHECK; the JVM path did not fail.
  The permanent UNITCHECK/CHECK/INIT regression passes 11/11 on Perl 5.45.4
  and both PerlOnJava backends. The pre-fix interpreter failed all three
  main-pad retention assertions; the Perl oracle and JVM passed. The full
  `nice -n 19 make` gate passes on `3ee9f9772` (12m01s, 2026-10-06).
- [x] Count captured array and hash pads as referent owners through their
  closure owner slots, and discount the `B::SV` probe's private hash slot for
  named aggregates. The permanent regression records the Perl 5.45.4 sequence
  2, 3, 4, 3, 2 for two closures capturing one array or hash, then checks the
  same owner releases after each declaring scope exits. The pre-fix JVM and
  interpreter each failed three of the original ten assertions; both backends
  now pass all thirty, as does the Perl oracle. Closure replacement releases
  array and hash capture owners with the same exact counts. Blessed array/hash
  probes also verify the 3, 2, 1 scope-exit sequence, weak clearing, and
  exactly-once `DESTROY`. Captured-aggregate lifecycle (15/15) and scalar
  pad-transfer (6/6) regressions also pass on both backends. The full
  `nice -n 19 make` gate passes on `bf977fc71` (8m34s, 2026-10-06).
- [x] Move captured array/hash pad edges from the selective `refCount` bridge
  into native owner-slot counts. `Internals::SvREFCNT` combines native capture
  slots with remaining legacy owners, and a final native slot release now
  drives scope-exit cleanup without manufacturing a legacy decrement. The new
  `PerlOwnerSlotNativeCaptureTest` verifies slot retention independently from
  `refCount`; the 30-assertion Perl 5.45.4 regression and both backends pass.
  `nice -n 19 make` passes (9m23s, 2026-10-06).
- [x] During global destruction, snapshot active Perl owner-slot identities
  before `DESTROY` and treat only newly retained slots as resurrection. This
  ignores temporary arguments and preexisting global aliases while preserving
  warnings for a real saved reference. The Perl 5.45.4 regression passes 3/3;
  the unfixed JVM and interpreter warned for weak and non-weak temporary
  arguments, while both fixed backends pass. Full `nice -n 19 make` passes on
  `7f4c85c4d` (7m18s, 2026-10-06); link check passes (555 OK, 0 errors).
- [x] Re-run Dancer2-Plugin-DBIx-Class 1.1001's `t/01_both_using_rsnames.t`,
  the #1642 global teardown workload. After installing its missing pure-Perl
  dependencies in the local CPAN cache, PerlOnJava passes all six assertions
  and exits cleanly without the prior repeated `Type::Tiny` resurrection
  warnings.
- [x] Import the current Perl 5.45.4 test suite with
  `dev/import-perl5/sync.pl --only perl5/t`; both selected imports completed
  successfully and the synced tree required no follow-up edits.
- [x] Run the complete imported suite on the current PR code: 575/575 files,
  681,471/681,471 passing assertions, zero failed, timed out, or incomplete
  files. Comparison with the 2026-10-05 baseline reports 575 unchanged files
  and no regressions.
- [x] Recheck selected CPAN ownership workloads: Dancer2-Plugin-DBIx-Class
  `t/01_both_using_rsnames.t` passes 6/6; Catalyst-Runtime's patched
  `t/live_fork.t` passes all ten supported assertions and skips only the six
  fork assertions; `t/aggregate/live_component_controller_action_chained2.t`
  passes 9/9. The additional requested Moo and DBIx::Class suites still fail
  on the rebased build for pre-existing defects tracked in #1656 and #1434;
  DBIx::Class's two assertion failures remain unclassified in #1646.
- [x] Rebase the draft PR onto the latest fetched `master` commit
  `7bdd1cfbe16cd123dd5ee8a6b103e0f8375ab0a8`; all 82 Phase 1 commits replayed
  cleanly, with the upstream ZIP changes and Phase 1 changelog entry retained.
- [x] Validate the rebased candidate with `nice -n 19 make` and
  `nice -n 19 make check-links`; both pass. The post-rebase UAT passes 575/575
  files and 681,471 assertions, and comparison reports 575 unchanged files and
  no regressions.
- [x] Confirm CI on final PR #1653 head `c52918c92269d7295bb3095b4f156c389af58f23`
  is green on Ubuntu and Windows, then merge as `84f32be179bdc2b351c3eaae1cb26df39964ab6b`.
- [x] Compare the additional user-requested Moo and DBIx::Class CPAN failures
  against current master and target Perl 5.45.4. Moo's seven call-site
  assertions fail identically on master while target Perl passes 29/29; the
  DBIx::Class heredoc conflict-marker diagnostic reproduces on master while
  target Perl loads the helper. These are pre-existing issues tracked in
  [#1656](https://github.com/fglock/PerlOnJava/issues/1656) and
  [#1434](https://github.com/fglock/PerlOnJava/issues/1434); the DBIx run's two
  assertion failures remain separately unclassified under
  [#1646](https://github.com/fglock/PerlOnJava/issues/1646).

### Next steps

1. Keep PR #1816 open/draft while continuing the Phase 2 owner-accounting work.
2. Continue Phase 2 from reachability recovery to default owner-slot
   accounting for ordinary array/hash element stores and scalar proxies. Cover
   acquisition, transfer, replacement, deletion, aliases, and final release
   with oracle-checked refcount, weak-reference, and DESTROY assertions. Keep
   legacy dispatch and reachability bridges only at explicit unmigrated
   boundaries; use unchanged HTML-Tree as the integration guard.
3. Resolve the strict target failures in the fresh-home CPAN release
   acceptance, then rerun that gate on the final candidate. The current run
   distinguishes prerequisite/setup timeouts and missing dependencies from
   runtime assertions; those outcomes do not yet satisfy #1642's acceptance.
4. Rerun Moo and DBIx::Class after their separate tracked fixes land; classify
   the two DBIx::Class assertions independently.
5. Run each repository gate from an immutable commit and record before/after
   evidence for each enabled path.

### Open questions and blockers

- The aggregate scope-unregister walk and repeated deferred-release snapshot
  builds have separate deterministic counters. The synthetic deferred test
  observes one external-root snapshot per flush. On rebased PR #1746 head
  `8fd9f830f`, Catalyst-Runtime `t/aggregate/live_component_controller_action_chained2.t`
  passes 9/9, `t/arg_constraints.t` passes 69/69, and `t/query_constraints.t`
  passes 18/18. The latter two files had five UTF-8 failures in an earlier
  candidate. Mojolicious 9.49 `t/mojo/dom.t` passes 132 top-level subtests /
  1,418 assertions; DBIx::Class `t/55namespaces_cleaned.t` passes 14,931
  assertions; DateTime 1.67 `t/00load.t` and `t/01sanity.t` pass 1/1 and 16/16;
  Image::ExifTool 13.55 `t/Unknown.t` passes 3/3. The Catalyst chained action
  and Dancer2-Plugin-DBIx-Class shutdown tests pass on both backends. PR CI is
  green on Ubuntu and Windows. The fresh-home `make test-cpan-release-acceptance`
  gate has not been completed on this final candidate. The earlier run hit
  DateTime and Catalyst setup timeouts and Mojolicious had 99/109 programs fail
  to load because its isolated home lacked `IO::Compress::Gzip`; these are
  historical run outcomes, not final-candidate suite results. The full gate remains a required final qualification; targeted-file results do
  not substitute for it.
- END CVs have queued and active lifetime tests, including aggregate captures,
  nested `exit`, `die`, and callback failure. UNITCHECK/CHECK/INIT lexical-pad
  lifetime is covered separately; the corresponding phaser ownership path is
  no longer an open Phase 1 boundary.
- The current standard Perl oracle is
  `/Users/fglock/projects/perl5/perl`, version 5.45.4 with `useithreads=undef`.
  It needs the checkout's `lib`, `cpan/Test-Simple/lib`, and
  `cpan/Scalar-List-Utils/lib` added to `@INC`; the checkout remains untouched.
  The prior Perl 5.44.0 build remains the oracle for historical HTML-Tree
  integration results.
- On commit `4cad02e3b`, the focused `new_from_lol` and content/incremental
  parser lifecycle reproducers pass on system Perl, JVM, and interpreter. The
  upstream `timeout 1800 ./jcpan -t HTML::Tree` run passes all 23 files and 989
  tests, including `t/refloop.t`; standalone system Perl 5.42.2 passes the
  unchanged `t/refloop.t` 8/8. This closes the observed HTML-Tree behavior
  failure on this candidate, but does not complete Phase 2: owner slots still
  cover closure pads only, and cleanup still relies on selective counts and
  reachability walks. Revalidate the upstream suite after the scalar/proxy and
  array/hash ownership path is migrated.
- The Dancer2-Plugin-DBIx-Class `t/01_both_using_rsnames.t` workload now passes
  all six assertions and exits on both backends. The interpreter first exposed
  a `caller->isa` scalar-context bug, fixed in this follow-up, then exceeded a
  360-second teardown bound. Its 120-second JFR profile attributed 9.25% of
  samples to full-root snapshot building and 28.53% to `WeakHashMap.put`.
  Retaining positive aggregate-root proofs across nested invalidations now
  lets the focused test exit within the same bound. The deterministic regression
  verifies reuse after a root removal and reset at the drain boundary. Artifacts
  are `/tmp/issue1642-dancer-interpreter-teardown-after-caller-fix.jfr`,
  `/tmp/issue1642-dancer-interpreter-teardown-after-shortcut.jfr`,
  `/tmp/issue1642-dancer-interpreter-after-drain-positive-snapshot.log`, and
  `/tmp/issue1642-dancer-jvm-after-drain-positive-snapshot.log`. The local CPAN
  cache now also has `B::Hooks::EndOfScope` 0.28; Moose is supplied by the
  PerlOnJava provider. `jperl -MCatalyst` loads Catalyst-Runtime 5.90132.
  Its checked-in distropref patches only `t/live_fork.t` to skip the six
  process-fork assertions on PerlOnJava; the `system` and backtick assertions
  and all other distribution tests remain enabled. A direct run from the
  newest patched CPAN build passes all ten non-fork assertions, skips exactly
  the six fork assertions, and exits successfully. The broader Catalyst
  `jcpan -t` attempt exceeded its 900-second deadline while running
  `t/aggregate/live_component_controller_action_chained2.t`, after 21 earlier
  tests reported `ok`; that test passes 9/9 when run alone in about 25 seconds.
  The full attempt therefore remains incomplete and did not produce a cleanup
  profile. A post-change Dancer2 `jcpan -t` run reported
  `t/01_both_using_rsnames.t` ok, then exceeded the 240-second command deadline
  later in the distribution suite. The Catalyst owner-slot delta workload and
  full cleanup acceptance are still open. On the 2026-10-12 rebased candidate,
  the repository `make` gate passed in 7m05s. The first local AnyEvent install
  hit its 30-minute timeout while the full-root snapshot walker scanned a large
  aggregate. The walker now caps inspected edges at 50,000 and marks a capped
  snapshot incomplete, preserving conservative reachability. The final
  implementation `make` passed in 7m03s, the focused edge-cap regression
  passed, and retrying the AnyEvent install completed in 15 minutes with all 32
  files installed; `anyevent_server_guard_rebind.t` passes 2/2.
- `RuntimeBase.refCount` still combines selective counts with legacy lifecycle
  sentinels and triggers cleanup for mixed-owner referents. The new explicit
  lifecycle prevents repeat dispatch and records destruction/resurrection, but
  it does not yet replace sentinel checks at every legacy boundary. Scalar/
  proxy and array/hash migration belongs to Phase 2 under issue #1649's current
  plan.
- On the 2026-10-12 rebase of #1642 onto `32472699a`, `nice -n 19 make` passes
  in 7m42s. Catalyst-Runtime
  `t/aggregate/live_component_controller_action_chained2.t` passes 9/9 on JVM
  and interpreter; Mojolicious `t/mojo/dom.t` passes 132 subtests / 1,418
  assertions on both backends. The labeled-SKIP parser regression passes 2/2
  on system Perl and both backends, and `make check-links` passes. The obsolete
  `TestMoreHelper` macro hook remains removed after rebasing onto master. The
  fresh-home release acceptance and current-candidate Catalyst cleanup profile
  remain open; targeted tests do not establish the full gate or the total cost
  of root walking.
- The full root snapshot now seeds all installed CODE slots but follows capture
  fields only through `GlobalVariable.globalCodeRefEdgeRootsView()`, which
  already caches the slots with walkable edges. A deterministic counter test
  verifies one capture walk among 65 installed CODE slots while retaining the
  captured aggregate. `nice -n 19 make` passes in 18m10s on this change, and the
  Catalyst chained action passes on both backends. The current JFR still samples
  `reachableFromRootsSnapshotWithStatus()` in 3,253 of 8,615 execution samples;
  further cleanup-cost work and the fresh-home acceptance remain open.
- The same JFR shows `MortalList.recordUnblessedAggregateSnapshotResult()` in
  stacks copying the entire reachable set into a second drain cache for each
  unresolved aggregate. The drain cache now retains only targets with positive
  proofs, while the full snapshot answers other targets directly. Added a
  deterministic counter regression for repeated unreachable targets over a
  populated root graph. `nice -n 19 make` passes in 8m48s; the new regression
  passes in the unit gate, and Catalyst-Runtime
  `t/aggregate/live_component_controller_action_chained2.t` passes 9/9 on JVM
  and interpreter. `make check-links` passes. A follow-up profile is recorded
  below.
- The cleanup-cost regression now drives three zero-count aggregates through
  `MortalList` deferred release instead of calling its reachability helper
  directly. After rebasing onto `2995d5a0a`, `nice -n 19 make` passes in 10m24s,
  including the new test, and Catalyst-Runtime
  `t/aggregate/live_component_controller_action_chained2.t` passes 9/9 on both
  backends. The expensive release acceptance remains unrun per the user's
  instruction to use focused CPAN files. A post-change 84-second JFR profile
  of the focused Catalyst test contains 4,639 execution samples; the full root
  walker appears in 1,790 sample stacks. The previous graph-sized copy is gone
  from the aggregate result path: its only sampled result-helper stack performs
  the target membership check, and the sole `AbstractCollection.addAll` sample
  belongs to `RuntimeBase.ownerSlotSnapshot()`. Full-root traversal remains a
  major cost across cleanup drains, so #1642 is not complete; the next
  investigation is safe snapshot reuse across unchanged root sets or an
  ownership path that removes the ordinary cleanup fallback. Full release
  acceptance also remains required before closing the issue.
