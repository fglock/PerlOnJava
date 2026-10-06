# Perl-compatible ownership rewrite

**Status:** Phase 1 in progress; closure-pad release, capture transfer, weak transitions, and runtime cycle retention are covered

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
must cover those boundaries before HTML::Tree can pass. `undef $sub` is a
scalar slot overwrite, not the separate `undef &sub` CV-body operation.

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

## Progress tracking

### Current status: Phase 1 in progress; closure-pad release regressions fixed

### Completed

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

### Next steps

1. Reproduce the Catalyst scenario from #1642 and compare its cleanup traffic
   with the measured deferred-release path; identify which releases can share
   a root snapshot across statement boundaries.
2. Qualify the closure owner's remaining lifecycle boundaries while preserving
   Perl 5.45.4 weak and exact count checkpoints, including closure replacement
   and scope-exit cases for tracked blessed aggregates.
3. Extend the complete ownership path through scalar proxies and array/hash
   slots in Phase 2, then resolve the HTML::Tree teardown failures and verify
   unchanged `t/refloop.t`.
4. Run each repository gate from an immutable commit and record before/after
   evidence for the enabled ownership path.

### Open questions and blockers

- The aggregate scope-unregister walk and repeated deferred-release snapshot
  builds now have separate deterministic counters. The synthetic deferred
  test observes one external-root snapshot per flush. Reproduction of the
  Catalyst workload from #1642 remains open so its object and flush pattern can
  be matched to these paths.
- END block CVs have explicit queued and active lifetime protection. Other
  phaser queues and nested exit paths still need the same ownership audit.
- The current standard Perl oracle is
  `/Users/fglock/projects/perl5/perl`, version 5.45.4 with `useithreads=undef`.
  It needs the checkout's `lib`, `cpan/Test-Simple/lib`, and
  `cpan/Scalar-List-Utils/lib` added to `@INC`; the checkout remains untouched.
  The prior Perl 5.44.0 build remains the oracle for historical HTML-Tree
  integration results.
- The focused `new_from_lol` regression now passes, but HTML-Tree 5.07's
  content and incremental parser cases still retain two and three elements at
  the immediate release assertion on both PerlOnJava backends; Perl 5.44 passes
  all cases. The new parser lifecycle reproducer records this open failure.
- The Dancer2-Plugin-DBIx-Class teardown workload from #1642 now passes. The
  full Catalyst-Runtime workload remains unqualified because its local setup
  cannot load Moose and `B::Hooks::EndOfScope`; the owner-slot delta check does
  not yet have a measured Catalyst cleanup profile.
- `RuntimeBase.refCount` still combines selective counts with legacy lifecycle
  sentinels and triggers cleanup for mixed-owner referents. The new explicit
  lifecycle prevents repeat dispatch and records destruction/resurrection, but
  it does not yet replace sentinel checks at every legacy boundary. Scalar/
  proxy and array/hash migration belongs to Phase 2 under issue #1649's current
  plan.
