# Perl-compatible ownership rewrite

**Status:** Phase 1 is complete; Phase 2 is in progress. HTML-Tree 5.07 `t/refloop.t` passes 8/8 on both backends with corrective sweeps disabled. `@DB::args` now stores the selected source scalar cells directly, avoiding detached untracked-reference owners. Shared proxy release, ithread weak-slot resurrection, PadWalker rebinding, and async cleanup regressions pass on both backends. Remaining owner families still prevent removal of the legacy bridge and reachability fallbacks.

**Sweep policy (2026-10-27):** Corrective sweeps are disabled by default, as
requested. `JPERL_ENABLE_CORRECTIVE_SWEEPS=1` temporarily enables legacy
comparison; a nonempty `JPERL_DISABLE_CORRECTIVE_SWEEPS` overrides it. Disabled
runs skip automatic scheduling, discarded-container sweep preparation,
scope-exit capture candidate processing, and the pre-END reachability sweep.
Post-END global teardown remains separate from ordinary reference release.
The build tracks both switches as test inputs so Gradle's configuration cache
cannot silently reuse another run's policy. Default-off is a migration step,
not evidence that every owner family is complete.

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

### Current status: Phase 1 complete; Phase 2 in progress

Phase 1 merged in PR #1653 as `84f32be179bdc2b351c3eaae1cb26df39964ab6b`
(2026-10-07).

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

### Targeted weak release with owner slots

`sweepReleasedWeakReferents()` now treats an active owner slot as direct proof
that a pending target is still strongly owned. When every target in the batch
has that proof, it returns without building root snapshots or running the full
walker. Remaining uncounted targets still use the existing fallback. The new
`TargetedWeakReleaseOwnerSlotFastPathTest` passes with sweeps enabled, along
with the existing targeted-query and weak-CODE fallback tests. HTML-Tree
5.07 `t/refloop.t` still passes 8/8 on both backends with sweeps disabled.

### CODE optree constants receive owner identities (2026-10-31)

System Perl confirms that a saved coderef keeps a backslash-addressable
constant alive after its named glob is replaced, and that a nested anonymous
optree shares its constant identity across closure instances while its factory
CV remains alive. The expanded permanent Perl regression passes 8/8 on system
Perl. Each compiled optree now has a distinct constant scalar per source
occurrence and one CODE_PAD_CONSTANT slot per strong edge. A parent CV owns
embedded anonymous optrees as templates; each produced closure also owns its
own edges. The interpreter tracks constants from its constant pool and nested
closures in the same owner ledger. Glob replacement retires only the stash
owner, and final CV cleanup releases constant slots and clears weak observers
after the last owner. Runtime lookup uses the owning CV's registered optree
constants; the reachability walker no longer follows pad constants as a
substitute for explicit edges. Full build and backend validation for this
batch is pending.

### Next steps

1. Extend the ownership path through scalar proxies and ordinary array/hash
   slots in Phase 2, then resolve the HTML::Tree teardown failures and verify
   unchanged `t/refloop.t`.
2. Resolve the strict target failures in the fresh-home CPAN release
   acceptance, then rerun that gate on the final candidate. The current run
   distinguishes prerequisite/setup timeouts and missing dependencies from
   runtime assertions; those outcomes do not yet satisfy #1642's acceptance.
3. Rerun Moo and DBIx::Class after their separate tracked fixes land; classify
   the two DBIx::Class assertions independently.
4. Run each repository gate from an immutable commit and record before/after
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
- The focused `new_from_lol` regression now passes, but HTML-Tree 5.07's
  content and incremental parser cases still retain two and three elements at
  the immediate release assertion on both PerlOnJava backends; Perl 5.44 passes
  all cases. The new parser lifecycle reproducer records this open failure.
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

### Phase 2 progress (2026-10-27)

- JVM lexical cleanup now includes captured scalar cells at ordinary scope and
  subroutine fall-through boundaries. The new `closure_capture_scope_exit.t`
  regression passes on system Perl and both backends. The existing recursive
  builder regression also passes on both backends with corrective sweeps
  disabled.
- Explicit return cleanup now leaves the interpreter's `RETURN` opcode
  decrements queued until the `RuntimeCode` boundary has removed the callee's
  lexical registrations. The explicit-return regression passes 6/6 on JVM and
  interpreter with corrective sweeps disabled. Declaration-scope provenance
  still distinguishes locals from outer cells borrowed by a closure.
- JVM and interpreter closure creation now assign a distinct `SCALAR_CAPTURE`
  identity to each captured scalar pad cell. `releaseCaptures`, pad rebinding,
  and thread cloning balance those identities; a focused Java test passes.
  The slots remain owned by their closure metadata while the selective count
  bridge is active. Registering them as a second global root retained a
  Catalyst request tempfile, so native root-ledger integration still needs a
  closure-liveness boundary.
- [x] Counted scalar, array-element, and hash-element stores now have distinct
  `PerlOwnerSlot` identities (2026-10-27). Overwrite preserves the cell's
  identity; deferred releases carry an immutable slot identity and sequence,
  so draining an old value cannot retire a newer assignment. These slots
  identify the selective-count bridge without becoming global roots.
  `RuntimeHash.put` acquires its element owner and releases displaced values;
  array removal and scope cleanup retain release provenance. A final
  identified scalar/element release can destroy its referent directly rather
  than restoring a synthetic count from a stale root snapshot.
- [x] Local-array rvalue returns now transfer through distinct `RETURN_VALUE`
  slots before lexical cleanup on both backends (2026-10-27). Void returns
  retain no element owners; scalar returns yield the count; list returns keep
  mortal ownership until the receiving caller consumes the values. The new
  [`local_array_return_owner_release.t`](../../src/test/resources/unit/refcount/local_array_return_owner_release.t)
  passes 8/8 on system Perl and both backends with corrective sweeps disabled;
  the unfixed implementation failed five assertions on each backend. The
  equivalent implicit-return probe also passes 8/8 on all three runtimes.
- [x] Weak links and captured/container-owned scalar results are treated as
  borrows during recursive release and void discard (2026-10-27). This keeps
  self-retaining closure cycles alive after invocation. The existing closure
  cycle regression passes 3/3, the weak localized-cache regression 7/7, and
  the new repeated-element/cycle regression 6/6 on both backends with sweeps
  disabled. Seven Java owner-slot tests cover replacement, shared referents,
  queue provenance, weak edges, borrowed captures, and a final element release
  with zero root queries or snapshot builds.
- [x] Unchanged HTML-Tree 5.07 `t/refloop.t` passes 8/8 on JVM and interpreter
  with `JPERL_DISABLE_CORRECTIVE_SWEEPS=1` (2026-10-27). The full unfiltered
  `nice -n 19 make` passes in 6m45s. Focused no-sweep qualification also passes
  `destroy_edge_cases.t` (27/27), `weaken_edge_cases.t` (43/43), and END
  destruction ordering (5/5) on both backends.
- [x] Successful and throwing `DESTROY` callbacks now share ownership
  finalization (2026-10-27). Synthetic callback arguments are balanced before
  reblessing and resurrection checks; dead referents clear weak observers and
  release owned children even when the callback dies or warning delivery
  throws. Real escaped owners preserve the referent and allow its next
  destruction. `issue_1649_destroy_exception_cleanup.t` now passes 10/10
  without sweeps on both backends, resolving its two weak-clearing failures.
  The new
  [`destroy_exception_resurrection.t`](../../src/test/resources/unit/refcount/destroy_exception_resurrection.t)
  passes 11/11 on system Perl and both backends without sweeps, covering
  resurrection followed by final release, reblessing, cleanup warnings, and
  preservation of `$@`. A Java regression verifies observer clearing and
  child-owner release with automatic sweeps suppressed. HTML::Tree remains
  8/8 on both backends without sweeps; the latest unfiltered
  `nice -n 19 make` passes in 7m05s.

- [x] Shared scalar/list return boundaries now snapshot reference values,
  including CODE values, into mortal `RETURN_VALUE` slots (2026-10-27).
  Forwarding an existing temporary avoids duplicate acquisition; lvalue returns
  retain their cell aliases. Staged reference-valued list arguments acquire
  distinct `ARGUMENT_VALUE` slots and queue their release once, including
  repeated referents. Pending return owners protect closure captures until
  the caller consumes the result. Five Java transfer tests pass. The new
  [`code_return_argument_snapshot.t`](../../src/test/resources/unit/refcount/code_return_argument_snapshot.t)
  passes 4/4 on system Perl and both backends without sweeps; the unfixed
  implementation failed two assertions on each backend. Focused no-sweep
  checks also pass local-array returns (8/8), throwing destructor resurrection
  (11/11), self-retaining closure cycles (3/3), explicit closure returns
  (6/6), and unchanged HTML::Tree (8/8) on both backends. The bundled
  candidate passes the unfiltered `nice -n 19 make` gate in 8m18s.

- [x] Partially evaluated method arguments now retire their explicit owners
  on exceptions and frame exit (2026-10-27). JVM argument emission installs
  an exception handler; interpreter inline-eval unwinding retires staging
  registers created within that eval. Strong staging registrations and call
  depth distinguish abandoned calls from still-active outer evaluations.
  Staged argument owners and pending return tokens retain their referents
  through the runtime positive-owner registry, releasing that retention once
  their exact token drains. Existing corrective sweeps respect these explicit
  transfer owners rather than reclaiming them from an incomplete root walk.
  Non-local map returns preserve their result mortals during interpreter and
  shared-call cleanup. The new
  [`argument_evaluation_unwind.t`](../../src/test/resources/unit/refcount/argument_evaluation_unwind.t)
  passes 20/20 and
  [`argument_evaluation_return.t`](../../src/test/resources/unit/refcount/argument_evaluation_return.t)
  passes 18/18 on standard Perl and both backends without sweeps. Unfixed
  backends failed six unwind assertions each and six/seven return assertions
  on JVM/interpreter respectively. Four Java checks cover repeated argument
  identities, caller-frame preservation, non-local return transfer, and child
  retention through an explicit argument owner. All Java tests and the
  focused normal-mode refcount corpus pass (`nice -n 19 make`, 2m16s).

### Final owner releases and default-off qualification (2026-10-27)

- [x] Final weakening and scalar/element overwrite use their identified owner
  edge instead of restoring a count from a root walk. Unblessed scalar cells
  finalize when their lexical binding or last capture ends; escaped strong
  references keep the cell and its contents alive. Scalar capture identities
  are visible in the referent's local ledger without becoming global roots.
  Legacy sweeps respect those binding/capture owners. Java coverage verifies
  final release with zero root queries and no duplicate lifecycle transition.
- [x] Whole-array replacement, undef, and local restoration release only owned
  element cells. Clearing aliased `@_` preserves the caller's scalar owners,
  including independently weakened probe copies; inserted elements still
  release once. Files: `RuntimeArray.java`, `PerlArgumentUnwindOwnerTest.java`.
- [x] JVM and interpreter acquire rvalue return owners before lexical cleanup.
  Both interpreter return emitters reserve a separate result register, so
  cleanup of an earlier local cannot replace a later local's owner cell.
  Referenced scalar cells keep their contents until the receiving caller
  releases or overwrites them. Lvalue aliases and socket ownership remain
  covered by the existing regressions. Files: `RuntimeCode.java`,
  `EmitStatement.java`, `EmitControlFlow.java`, `BytecodeCompiler.java`,
  `CompileOperator.java`, `BytecodeInterpreter.java`, `MortalList.java`.
- [x] Temporary-expression protection no longer follows scalar references into
  arbitrary hash/array graphs. The referent and its element slots supply
  ownership; only direct expression references are inspected. A Java
  regression rejects traversal into unrelated metadata. This removes a
  recursive Moose metadata walk observed during Catalyst bootstrap: the JVM
  probe now reaches its tempfile assertion instead of timing out at 240s.

New permanent Perl regressions pass on standard Perl and both backends with
sweeps disabled by default:

| Regression | Assertions |
| --- | --- |
| `refcount/scalar_cell_scope_owner_release.t` | 11/11 |
| `refcount/scalar_cell_return_contents_owner.t` | 8/8 |
| `refcount/argument_array_clear_borrowed_cells.t` | 9/9 |
| `refcount/lexical_return_slot_owner_release.t` | 6/6 |

The corresponding unfixed failures were retained before each correction.
Existing default-off checks pass on both backends: probe copies (5/5), scalar
assignment guards (6/6), weaken/DESTROY (24/24), scalar weak references
(10/10), sliding-window release (12/12), argument exception/return cleanup
(20/20 and 18/18), local-array returns (8/8), explicit closure returns (6/6),
self-retaining closure cycles (3/3), and unchanged HTML::Tree (8/8). Existing
socket lifetime and blessed-descriptor checks also pass on both backends.

### Active call owners and final default-off gate (2026-10-27)

- [x] Every active call frame owns an `ACTIVE_CODE` identity. Recursive calls
  retain distinct tokens; removing one frame retires one token. A counted final
  release waits for the last active frame, then clears observers and captures
  without a root query. Borrowed CODE with no counted release keeps its captures;
  template, stash, and pinned CODE ownership still require migration.
- [x] Capture sweeps cannot override explicit live call/capture tokens, including
  in legacy comparison mode. Default-off also skips capture candidate queuing
  and processing and the pre-END reachability cleanup. The default-policy Java
  regression verifies no candidate retention or pre-END root queries.
- [x] Preserve genuine self-capture cycles rather than breaking them at lexical
  exit. Assigning CODE into an already captured cell acquires the capture's
  referent edge separately from the lexical store. Explicitly breaking the
  cycle allows the final external owner to release its captured data.

New permanent regressions pass on standard Perl and both backends without
sweeps: `active_callback_owner_release.t` (13/13),
`self_capture_cycle_owner.t` (9/9), and `code_template_owner_lifetime.t` (4/4).
The callback and self-cycle failures were retained on the unfixed candidate.
`ActiveCodeOwnerTest` passes all four cases, including zero root queries on
final release. Existing `weak_active_coderef_dispatch.t` now passes 3/3 on both
backends, and unchanged HTML::Tree still passes 8/8 on both.

### Eval, tied handlers, and native parser arguments (2026-10-27)

- [x] Tied hash, array, scalar, and handle wrappers acquire distinct
  `TIE_HANDLER` identities on independent handler snapshots. Repeated release
  retires the owner once. A released snapshot remains borrowable while dynamic
  localization restores magic; clearing its payload caused five observed
  regressions, all now passing on both backends without sweeps.
- [x] Final container destruction releases magic and child slots independently
  of temporary expression borrows. Ordinary lexical cleanup still protects
  live aggregates. The final destruction path runs after resurrection checks.
  Java coverage verifies hash/array child retirement and final tie release
  without root queries.
- [x] JVM eval callback returns clean eval lexicals before returning to their
  caller. Interpreter evals consume untargeted callback returns as successful
  eval results rather than errors. Explicit return owners protect referenced
  results throughout lexical cleanup, abandoned method arguments, invocant
  release, and local restoration. Targeted try/finally paths remain a separate
  ownership boundary to complete.
- [x] Scalar overwrite's external-root fallback cannot clear weak observers
  while identified strong slots remain. This exposed an actual native-call
  argument leak in `HTMLParser.fireEvent`: freshly owned event-data and call
  arrays were never retired. Both arrays now release on success, exceptions,
  and absent-method dispatch. Native parser containers use anonymous references
  instead of inventing lexical bindings. Accumulated event arrays retain their
  own contents until the program clears them.

Permanent focused Perl coverage passes on standard Perl and both backends
with corrective sweeps off:

| Regression | Assertions |
| --- | --- |
| `refcount/tied_handler_owner_release.t` | 13/13 |
| `refcount/eval_callback_return_owner_release.t` | 12/12 |
| `refcount/native_parser_callback_owner_release.t` | 9/9 |

Unfixed failures were retained before the corrections. `TiedHandlerOwnerTest`
adds three Java cases for distinct identities, exactly-once retirement, magic
restoration borrows, and final child release. The latest focused comparison
gate passes in 1m36s:
`JPERL_ENABLE_CORRECTIVE_SWEEPS=1 JPERL_TEST_FILTER=refcount/ nice -n 19 make`.
Log: `/tmp/make-issue1649-native-parser-owner-batch.log`.

Final direct default-off checks restore unchanged HTML::Tree to **8/8 on both
backends**. All eight existing `html_parser_*.t` regressions pass on both
backends. The preceding default-off reader batch also passes eval/map cleanup
(3/3), simple tied-hash destruction (2/2), explicit closure returns (6/6),
destructor exception/resurrection (11/11), and self-capture cycles (9/9).
The JVM tied-hash autovivification loop passes 6/6. Five compatibility
regressions exposed by clearing retired handler snapshots now pass on both
backends: `eval_tied_local_cleanup.t`,
`issue_1630_repeated_local_alias_io.t`, `signature_eval_tie_caller.t`,
`symbol_table_glob_identity.t`, and `tied_lvalue_sub_return.t`.

### Conditional scopes and method holds completed (2026-10-27)

Method invocant holds now have independent `ARGUMENT_VALUE` identities and
retire exactly once, including abandoned calls. Three Java cases cover distinct
holds, deferred final release, and immediate abandoned-call release without
reachability queries.

The interpreter now closes condition-local scopes. Opcode 639 snapshots their
result before cleanup; the JVM likewise acquires conditional results before
retiring locals. Tied scalar, hash-element, and array-element lvalue results
retain their storage through the caller's STORE. Legacy capture correction
skips pads with identified capture owners.

Standard Perl and both default-off backends pass the new conditional regressions
(12/12, 6/6, and 7/7). The original tied-hash autovivification regression now
passes 6/6 on both backends. Unchanged HTML::Tree remains 8/8 on both.

The subsequent unfiltered default-off gate completed in 9m49s with eight
failures: three Perl acceptance cases listed below and five Java cases that
expect legacy sweeps. Log:
`/tmp/make-issue1649-method-conditional-final-full.log`.

### Final CV state-storage cleanup completed (2026-10-27)

Final CV destruction now detaches and retires persistent scalar, array, and hash
bindings after CODE destructor callbacks and the resurrection decision. A blessed
CV that resurrects keeps its original state until its final destruction. Escaped scalar cells and aggregates retain their values until their
own last owner ends. Persistent scalar cells are born with a binding rather
than being mistaken for ephemeral returned scalars.

State aggregate refaliases own separate counted reference slots. Releasing a
CV retires those slots without ending the source aggregate's lexical binding.
Lazy definition adoption shares this ownership metadata with the state maps;
ithread cloning copies it with the cloned graph.

Permanent regressions `refcount/closure_state_owner_release.t` (10/10),
`refcount/state_aggregate_alias_owner_release.t` (6/6), and
`refcount/blessed_code_state_resurrection.t` (10/10) pass standard Perl and
both backends with sweeps explicitly disabled. Unfixed-parent failures are
retained in `/tmp`. The final focused comparison gate passes in 2m35s:
`/tmp/make-issue1649-state-resurrection-final-batch.log`. Destructor exceptions,
true cycles, and unchanged HTML::Tree 8/8 also pass on both backends:
`/tmp/issue1649-state-resurrection-final-backend-results.txt`.

The full default-off gate before the final CODE-destructor ordering correction
finished in 7m07s with the same eight known failures and no new failures from
state-storage cleanup. The final ordering correction received the focused and
backend checks above; another full gate remains required before publication.
Evidence: `/tmp/make-issue1649-state-storage-final-full.log` and
`/tmp/issue1649-state-storage-full-failures.txt`.

Adjacent default-off checks preserve state refaliases, declared-reference list
identity, true self-capture cycles, CODE template lifetimes, and HTML::Tree 8/8
on both backends. `state.t` passes the JVM; its interpreter failures at 6, 7,
and 13 reproduce on the saved pre-change JAR. Evidence:
`/tmp/issue1649-state-final-backend-results.txt` and
`/tmp/issue1649-state-parent-interpreter.log`.

This completes state-storage retirement for CVs that actually reach final
release. Named CODE/glob lifetime accounting still prevents that transition in
the remaining named-state acceptance case. No remaining sweep or compatibility
bridge has been removed on the strength of these focused passes.

### Named CODE-slot ownership completed (2026-10-27)

One physical CODE slot now owns one counted `CODE_SLOT` identity. Whole-glob
names sharing its scalar share that edge; separate CODE slots have separate
identities. Saved localization, compiled-GV pins, and retained compiled-call
references keep the slot alive across namespace replacement or deletion.
Restoration releases the temporary slot after the saved slot is installed.

Native CODE final release uses these counted identities instead of historical
stash counters or the discarded-closure root-walk correction. Capture attachment
preserves counts already acquired by named slots. Cache notifications no longer
acquire an extra owner during first assignment to an empty slot. Compatibility
root-index shape metadata survives capture retirement without retaining captured
values or influencing lifetime decisions.

Interpreter frames borrow a stable self-reference for `__SUB__` and persistent
state operations. Undefining the executing glob therefore leaves its active CV
and state usable until the last frame returns. Thread snapshots reconstruct
tracked CV counts, scalar owner identities, and namespace/compiled-GV bindings
in the child runtime.

Six `CodeSlotOwnerTest` cases cover independent and shared slot identities,
first assignment and replacement, localization, compiled pins, active calls,
and final release without reachability queries. Three new permanent Perl tests
pass standard Perl and both backends with corrective sweeps explicitly disabled:

| Regression | Assertions |
| --- | --- |
| `refcount/named_code_state_owner_release.t` | 9/9 |
| `refcount/localized_code_state_owner_release.t` | 9/9 |
| `refcount/compiled_glob_state_owner_release.t` | 4/4 |

The original `weak_deferred_capture_full_sweep.t` now passes 3/3 on both
backends. Existing glob redefinition, local alias restoration, current-sub map
and sort, glob identity, inheritance cloning, CODE resurrection, and unchanged
HTML::Tree 8/8 also pass on both backends. Evidence:
`/tmp/issue1649-code-pin-final-backend-results.txt`.
The focused comparison build passes in 1m33s:
`/tmp/make-issue1649-code-slot-pin-batch.log`.

The final unfiltered default-off gate completed in 7m19s with **seven failures**:
the two existing shared-reference cases below and the five Java cases requiring
legacy sweeps. Named state teardown now passes in the full gate, reducing its
failure count from eight to seven. No new full-suite failure was introduced.
Logs: `/tmp/make-issue1649-code-slot-final-full.log` and
`/tmp/issue1649-code-slot-full-failures.txt`.

Unfixed-parent evidence is retained for named-state release, the first-store
localization leak, and premature state destruction after stash deletion.
The earlier ownership work is preserved in WIP checkpoint `98e611e96`.

This completes the named-slot retain/release paths tested here. Compiler
templates, per-optree retirement of compiled-GV pins, raw descriptor/embedding
borrows, and complete CODE producer ownership still need audit before the
compatibility bridge can be removed. The complete rewrite remains in progress.

### Shared proxy and cloned weak-slot cleanup (2026-10-27)

`SharedElementProxy` is now a local expression borrow; the destination Perl
slot owns the view. Final release clears weak observers on that local view
without cascading into canonical shared storage.

Ithread snapshots install all cloned weak edges before draining the queued
capture-owner releases. This lets `DESTROY` rescue an existing weak hash slot
after the complete child graph is registered. Scope cleanup also retires
legacy scalar capture counts that have no explicit `SCALAR_CAPTURE` owner.
Async warning-handler retention uses its counted scalar owner, and PadWalker
rebinding records replacement scalar and aggregate captures with explicit owner
slots.

The following regressions pass on both PerlOnJava backends with corrective
sweeps disabled: `threads_shared_nested_proxies.t` (10/10),
`threads_destroy_weak_slot_rescue.t` (3/3), `padwalker_set_closed_over.t`
(6/6), `direct_closure_padwalker_rebind.t` (3/3),
`custom_warning_async_suspend_state.t` (6/6), and
`refcount/io_async_loop_refcount_lifecycle.t` (2/2). The two deferred-capture
boundary Java cases also pass without sweeps. The two thread regressions also
pass their standard Perl oracle runs.

Current-JAR requalification also passes `closure_capture_explicit_return.t`
(6/6) and unchanged HTML-Tree 5.07 `t/refloop.t` (8/8) on both backends with
sweeps disabled. Logs: `/tmp/issue1649-current-explicit-return-jvm.log`,
`/tmp/issue1649-current-explicit-return-interpreter.log`,
`/tmp/issue1649-current-html-tree-jvm.log`, and
`/tmp/issue1649-current-html-tree-interpreter.log`.

Requalification after the scalar-reference return and lazy thread-snapshot fixes
passes the full sweep-enabled `make` gate. The full no-sweep gate completed
1,242 tests with only these three existing sweep-helper cases failing:
`ReachabilityQueryCostTest`'s two targeted weak-release checks and
`WeakBackrefCodeCaptureSweepTest`'s quiet weak-code capture walk. No Perl unit
test failed. The fallback scalar-reference guard, lazy thread-snapshot race,
and unchanged HTML-Tree 5.07 `t/refloop.t` each pass on both backends with
sweeps disabled. Evidence: `/tmp/make-issue1649-final-normal.log`,
`/tmp/make-issue1649-final-no-sweep.log`, and the corresponding
`/tmp/issue1649-*-no-sweep-verified.log` files.

### Interpreter temporary closure owners retired

Counted scalar owner slots now hold a weak link to their implementing scalar
cell. At interpreter frame exit, generated closures that are neither active nor
returned release unregistered CODE cells left in borrowed aliased `@_` arrays.
Registered lexicals, package slots, and container-owned CODE refs stay live.
The Catalyst request tempfile regression passes 2/2 on the interpreter with
corrective sweeps disabled; the filtered JVM unit test also passes. Logs:
`/tmp/issue1649-catalyst-interpreter-borrowed-code-cell.log` and
`/tmp/make-issue1649-catalyst-borrowed-code-cell.log`.

### Temporary CODE argument slots counted

Temporary closures placed in aliased argument arrays now acquire a counted
`ARGUMENT_VALUE` owner. Returning from the call releases that owner through the
normal scalar cleanup path. `cleanupOwnedArgumentAliases()` no longer uses a
root walk to decide whether to release the CV's captures. `CallArguments`
retains its materialized argument array until finish, so a call rejected before
callee entry also retires the owner; normal callee cleanup and this fallback
are idempotent on the same slot.

`PerlClosureArgumentOwnerTest` verifies normal and abandoned-call release, the
slot identity, capture retirement, and zero reachability queries. The Perl regression
`issue_1649_temporary_closure_argument_owner.t` passes 5/5 on system Perl and
both PerlOnJava backends with sweeps disabled. The focused Java gate also passes
`PerlArgumentUnwindOwnerTest`, `PerlCallTransferOwnerTest`, and both deferred
capture boundary cases. Current-JAR requalification passes explicit-return
cleanup 6/6 and HTML-Tree 5.07 `t/refloop.t` 8/8 on both backends with sweeps
disabled. Evidence: `/tmp/issue1649-argument-owner-oracle.log`,
`/tmp/make-issue1649-argument-owner-materialized-final.log`, and logs with the
`/tmp/issue1649-argument-owner-materialized-` prefix.

This closes the temporary CODE-cell argument path only. Aliased `@_` cells,
native buffers, escaping aliases, and the remaining return paths still need
explicit owner-slot accounting.

### Direct scalar arguments staged through later expressions (2026-10-28)

`CallArguments` gives each strong scalar return snapshot its own
`ARGUMENT_VALUE` slot until `finish()`. This keeps an earlier argument alive if
a later expression drains its return owner, and keeps a final scalar argument
alive through callee cleanup. Persistent caller cells remain borrowed so
assignment through `@_` can release their previous values normally.

`CallArgumentsScalarStagingOwnerTest` covers a non-final mortal result, a final
mortal result, and a persistent caller cell. The default-off full
`nice -n 19 make` gate passes. Explicit-return cleanup passes 6/6 and
HTML::Tree 5.07 `t/refloop.t` passes 8/8 on both backends with corrective
sweeps unset. Evidence: `/tmp/make-issue1649-final-argument-owner-verified.log`
and files with the `/tmp/issue1649-final-argument-` prefix.

This covers scalar return snapshots in direct call arguments. It does not
complete aliased `@_` cell ownership or shared and tail-call cleanup.

Unresolved acceptance cases:

- Escaping proxy/lvalue magic and other native call buffers still need explicit
  ownership completion; compatibility borrows are not a substitute for strong
  alias owners.

The two targeted-walker assertions in `ReachabilityQueryCostTest` and the quiet
weak-code capture walk in `WeakBackrefCodeCaptureSweepTest` intentionally call
the diagnostic sweep helpers. Runtime cleanup requests now use a separate
corrective-sweep scheduler, which remains gated by the environment policy; the
default-policy test verifies that disabled runtimes do not queue or run those
requests. Keep the existing helper tests intact.

Phase 2 remains in progress: the integer count is still the compatibility
bridge for these new identities, and argument ownership and other return paths
are not fully migrated. Passing the default-off suite does not establish that
every producer, alias, or cleanup boundary uses semantic ownership alone.

### Explicit scalar-capture owner release

Scope-exited scalar captures now use their `SCALAR_CAPTURE` owner slots to
identify each owning `RuntimeCode`. When every capture of a pad has an explicit
owner, cleanup checks the CODE owner ledger and releases ownerless closures
without building END or scalar root graphs. Pads with compatibility-only
captures still use the existing graph fallback. The Java regression
`CaptureOwnerReleaseWithoutWalkerTest` passes; the full sweep-enabled
`nice -n 19 make` gate passes, and HTML-Tree 5.07 `t/refloop.t` passes 8/8 on
both backends with corrective sweeps disabled.

This removes root walks for migrated capture-owner decisions, but weak
referent candidate discovery and the weak-CODE capture graph are still
sweep-based.

### Weak referents with explicit owner slots

Quiet weak sweeps now check a referent's strong owner-slot ledger before
consulting the legacy captured-element proof. A candidate with an active slot
is preserved directly, so the weak-CODE capture graph is built lazily only for
aggregate candidates that still lack owner identities. The new
`WeakBackrefOwnerSlotFastPathTest` covers an array-element owner, while the
existing weak-CODE capture test continues to cover the legacy fallback. The
sweep-enabled full `nice -n 19 make` gate passes, and HTML-Tree 5.07
`t/refloop.t` passes 8/8 on both backends with sweeps disabled.

### Closure capture release with an explicit scalar owner (2026-10-28)

When undefining one CODE scalar, cleanup now checks whether another active
owner-slot identity retains the CV before building a root snapshot. It excludes
the slot being overwritten, so final-owner release still retires captures
through the existing compatibility path. `CodeOwnerSlotReleaseFastPathTest`
checks that the first of two scalar owners releases with zero root queries and
that the final owner still releases the captures. The full sweep-enabled
`nice -n 19 make` gate passes. Ownerless CODEs without explicit identities
still use the root-walk fallback.

### Package-root release from counted owner slots (2026-10-28)

When a package-rooted blessed value reaches a transient zero during overwrite,
remaining counted owner-slot identities now repair the compatibility count
before the package-root walker runs. `PackageRootOwnerSlotFastPathTest` keeps a
weak observer alive through an undercounted overwrite, checks that the count
matches the remaining hash slot, and verifies zero root queries. The full
sweep-enabled `nice -n 19 make` gate passes. Values without counted owner slots
retain the existing root-walk fallback.

### Localized CODE restore with an active owner slot (2026-10-28)

Whole-glob restore now skips the scalar registry, live capture, and Perl-root
walks when a non-stash owner slot still retains the localized CV. The regression
`LocalizedGlobCodeOwnerSlotFastPathTest` models the legacy direct stash write,
keeps the CV active across restore, and verifies its captures survive with zero
root queries. The sweep-enabled full `nice -n 19 make` gate passes. CVs without
owner slots still take the compatibility checks.

### Default-off full-suite acceptance (2026-10-28)

Production cleanup callers now schedule corrective passes through a gated
runtime method. The direct helper APIs remain available to the existing
reachability-cost and weak-CODE capture tests, including the test that requests
one explicit quiet pass at a safe boundary. This keeps diagnostic coverage
while leaving corrective scheduling disabled by default.

The full `nice -n 19 make` gate passes with both sweep-control variables unset:
212 test suites report 3,095 tests, zero failures, zero errors, and five
skips. `CorrectiveSweepDefaultPolicyTest`, `ReachabilityQueryCostTest`, and
`WeakBackrefCodeCaptureSweepTest` all pass. Against the same build, HTML::Tree
5.07 `t/refloop.t` passes 8/8 and `closure_capture_explicit_return.t` passes
6/6 on both backends with corrective sweeps explicitly disabled. Logs:
`/tmp/make-issue1649-no-sweep-helper-separation-retry.log` and files with the
`/tmp/issue1649-post-gate-` prefix.

This qualifies the current default-off behavior; it does not remove direct
diagnostic walkers, the integer-count compatibility bridge, or the remaining
reachability fallbacks.

### Tail-call release from counted owner slots (2026-10-28)

`releaseTailCallArgElement` now restores a transiently undercounted referent
from its remaining counted owner slots before consulting the weak-referent
root walker. `TailCallArgumentOwnerSlotFastPathTest` models a tail-call
handoff with one surviving array-element owner and verifies that the weak
observer stays live with zero root walks. The default-off full
`nice -n 19 make` gate passes; `goto_tailcall_args_cleanup.t` passes 5/5 and
`goto_weakself_tailcall_refcount.t` passes 2/2 on both backends with sweeps
unset. System Perl passes the weak-self test; its tail-call cleanup test skips
because `Internals` is unavailable. Evidence: `/tmp/make-issue1649-tailcall-owner-fastpath.log`,
`/tmp/issue1649-tailcall-args-*.log`, and
`/tmp/issue1649-tailcall-weakself-*.log`.

This avoids reachability walks when counted slots prove a surviving owner.
Tail-call arguments without counted slots still use the compatibility walker.

### Deferred weak release from counted owner slots (2026-10-28)

Ordinary deferred cleanup now restores the compatibility count from remaining
counted owner slots before asking the root walker for blessed referents.
`DeferredReleaseOwnerSlotFastPathTest` checks that one surviving scalar-cell
slot preserves its weak observer with zero root walks, and that releasing the
final slot still clears it. The unblessed aggregate snapshot path remains in
place to distinguish live Perl roots from stale scalar wrappers. The default-off
full `nice -n 19 make` gate passes. Evidence:
`/tmp/make-issue1649-deferred-owner-fastpath-corrected.log`.

### Argument-owner ledger bypass for active-frame scans (2026-10-28)

Deferred weak releases now consult counted owner slots before scanning active
`@_` arrays and staged argument frames for a matching referent. If a blessed
referent has another counted owner, ordinary deferred cleanup repairs the
compatibility count directly. `ActiveArgumentOwnerLedgerFastPathTest` verifies
that a persistent caller cell remains a borrowed alias, avoids the frame scan,
and still clears its weak observer when the final slot is overwritten. The
default-off full `nice -n 19 make` gate passes. Evidence:
`/tmp/make-issue1649-argument-scan-fastpath.log`.

Uncounted compatibility aliases still use the referent scan; explicit alias
slot ownership remains unfinished.

### Scalar overwrite recovery from counted owner slots (2026-10-28)

When a blessed weakly observed referent reaches a transient zero during scalar
overwrite, `RuntimeScalar` now restores the compatibility count from surviving
counted owner slots before invoking nested-call or package-root reachability
fallbacks. `RuntimeScalarCountedOwnerRepairTest` verifies that a remaining
array-element slot preserves the weak observer with zero root queries, and
that releasing the final slot clears it. The default-off full
`nice -n 19 make` gate passes. Evidence:
`/tmp/make-issue1649-scalar-owner-repair-final2.log`.

### Non-counted identities for staged and active argument cells (2026-10-28)

`CallArguments` and active `@_` frames now attach `ARGUMENT_ALIAS` identities
only to durable Perl storage cells and already active argument aliases. These
identities follow reference assignments and weak/strong transitions without
changing Perl-visible `refCount`; overwrite and explicit `undef` defer a final
release while an alias is the only owner. Transient expression wrappers retain
their staged return ownership without being treated as caller cells. The alias
identity is recorded in the referent ledger but does not add a separate runtime
root; the active frame already owns its scalar cell. RuntimeBase keeps
constant-time counts for alias and counted slots so ordinary deferred cleanup
does not scan a referent's full owner ledger.

`ArgumentAliasOwnerSlotTest` covers refcount neutrality, identity transfer from
primitive to reference values, weak-target lifetime through overwrite and
explicit `undef`, exclusion of transient wrappers, no extra runtime roots, and
retirement when a stale cell still points at a destroyed referent. The default-off
full `nice -n 19 make` gate passes. Evidence:
`/tmp/make-issue1649-argument-alias-frame-roots-final.log`.

This covers durable scalar cells staged for calls and active argument frames.
The tracked `new_from_lol` lifecycle reproducer passes 4/4 on JVM; its
interpreter result (1/4) matches the committed parent `7ba27d774`, so those
three failures predate this change. The TreeBuilder lifecycle reproducer passes
4/4 on both backends.

### Retire active argument identities when `@_` slots are removed (2026-10-28)

Active argument-frame identities now retire when their matching array slot is
removed, replaced, cleared, or deleted through a subrange operation. The
element-list hooks cover indexed removal and Java's direct `removeFirst` /
`removeLast` paths. `ArgumentAliasOwnerSlotTest` checks duplicate aliases,
replacement, clearing, and exact one-time retirement when the frame later exits.
`nice -n 19 make` passes. With corrective sweeps unset, explicit-return
cleanup passes 6/6 on both backends and HTML-Tree 5.07 `t/refloop.t` passes 8/8
on both backends.

### Argument cleanup decisions use owner identities (2026-10-28)

`hasActiveArgumentOwner` now checks only the `ARGUMENT_ALIAS` owner-slot count.
The fallback walks through active `@_` arrays and staged argument values were
removed, along with their recursive `CallArguments` referent scan. The
default-off full `nice -n 19 make` gate passes. Against that build,
`weak_nested_form_argument_lifetime.t` passes 29/29 and
`closure_capture_explicit_return.t` passes 6/6 on both backends; HTML-Tree
5.07 `t/refloop.t` passes 8/8 on both backends with sweeps unset.

### Staged owners for nested list aggregates (2026-10-28)

`CallArguments` now walks nested `RuntimeList`, `RuntimeArray`, and
`RuntimeHash` values and creates one `ARGUMENT_VALUE` owner for each strong
reference element. `CallArgumentsNestedAggregateOwnerSlotTest` verifies that
array and hash elements nested in lists each retain their own slot through
later argument cleanup and release at call completion. The default-off full
`nice -n 19 make` gate passes. Explicit-return cleanup is 6/6, nested-form
lifetime is 29/29, and HTML-Tree 5.07 `t/refloop.t` is 8/8 on both backends
with sweeps unset.

### Shared and tail-call argument frame identities (2026-10-28)

`ArgumentAliasHandoffOwnerSlotTest` verifies that a tail-call target frame gets
its own alias identity and releases it in LIFO order, and that overwriting a
shared scalar cell retires its identity exactly once. The default-off full
`nice -n 19 make` gate passes. With sweeps unset, `goto_tailcall_args_cleanup.t`
passes 5/5, `goto_weakself_tailcall_refcount.t` passes 2/2, and
`threads_shared_nested_proxies.t` passes 10/10 on both backends.

### Tail-call frame identities and thread cloning (2026-10-28)

Queued scalar decrements now capture their source argument-frame identity at
enqueue time. Tail-call cleanup matches that identity directly, while retaining
the existing direct-argument and bless-temporary cases. The new
`ArgumentAliasHandoffOwnerSlotTest` verifies copied arguments retain the source
identity in the pending queue and that thread cloning drops this execution-local
identity. `nice -n 19 make` passes; `goto_tailcall_args_cleanup.t`,
`goto_weakself_tailcall_refcount.t`, and `threads_shared_nested_proxies.t` pass
on both backends with corrective sweeps unset.

### Weakening with a surviving explicit owner (2026-10-28)

`WeakRefRegistry.weaken()` now skips `reachableOwnerCount()` when another
explicit owner slot remains. `PerlFinalWeakOwnerTest` verifies that weakening a
live lexical owner preserves the weak observer with zero scalar reachability
queries. The default-off full `nice -n 19 make` gate passes; with corrective
sweeps unset, explicit-return cleanup passes 6/6, nested-form lifetime passes
29/29, and HTML-Tree 5.07 `t/refloop.t` passes 8/8 on both backends.

### Identities for untracked container referents (2026-10-28)

Array and hash elements that strongly reference an otherwise untracked or
weakly-tracked referent now receive an `UNTRACKED_REFERENCE` slot identity
without promoting the compatibility `refCount`. The identity transfers when
the element is overwritten and retires on container clearing or scalar cleanup;
weaken/unweaken updates the same ownership record. `UntrackedReferenceOwnerSlotTest`
covers `-1` and `WEAKLY_TRACKED` referents and verifies their legacy counts stay
unchanged. The full default-off `nice -n 19 make` gate passes. With corrective
sweeps disabled, explicit-return cleanup passes 6/6, nested-form lifetime
passes 29/29, HTML-Tree 5.07 `t/refloop.t` passes 8/8, and representative
weaken, DESTROY, cycle, and resurrection tests pass on both backends.

### Durable scalar-cell identities for untracked values (2026-10-28)

Durable scalar cells now record one `UNTRACKED_REFERENCE` identity for a strong
value edge when the referent is still untracked or already weakly tracked.
This includes lexical cells, package roots, closure captures, CODE slots, and
array/hash element cells whose referents remain untracked or weakly tracked.
These identities leave legacy counts unchanged; anonymous nested aggregate
slots continue through their existing counted path. Assignment transfers the
same identity; lexical registration, replacement, scope exit, capture release,
container clearing, weaken, and unweaken refresh or retire it at their
ownership boundary. `UntrackedReferenceOwnerSlotTest` verifies the
untracked-to-weakly-tracked transfer and that a captured lexical keeps the
identity until its final owner exits.

`nice -n 19 make` passes. With corrective sweeps disabled, both backends pass
explicit-return cleanup 6/6, nested-form lifetime 29/29, HTML-Tree 5.07
`t/refloop.t` 8/8, `weaken.t` 4/4, `destroy.t` 15/15, blessed self-cycle 4/4,
and exception resurrection 11/11.

### Weakening repairs counts from surviving owner slots (2026-10-28)

When weakening exposes a transient undercount, `WeakRefRegistry` restores the
compatibility count from surviving counted owner slots before walking roots.
`WeakeningCountedOwnerSlotTest` covers the repair with zero root and
scalar-reachability queries. CODE weak-reference cleanup also needs to
distinguish live owners from compatibility pins and expired frame cells; the
next section records that migration. The full
`nice -n 19 make` gate passes. With corrective sweeps disabled, both backends
pass explicit-return cleanup 6/6, nested-form lifetime 29/29, weaken 4/4,
DESTROY 15/15, blessed self-cycle 4/4, exception resurrection 11/11,
Sub::Quote cleanup 8/8, and HTML-Tree 5.07 `t/refloop.t` 8/8.

### Untracked referent weakening uses slot identities (2026-10-28)

When an untracked referent has a durable `UNTRACKED_REFERENCE` owner slot,
`weaken()` now avoids the registry-wide corrective sweep and, for aggregates,
the initial root scan. Releasing its final owner queues targeted weak cleanup.
`UntrackedReferenceOwnerSlotTest` checks the no-sweep decision and final-slot cleanup
with corrective sweeps enabled. The focused owner-slot, unblessed-root, and
weak-CODE capture tests pass. `nice -n 19 make` passes; with corrective sweeps
disabled, both backends pass explicit-return cleanup 6/6, nested-form lifetime
29/29, weaken 4/4, DESTROY 15/15, blessed self-cycle 4/4, exception
resurrection 11/11, Sub::Quote cleanup 8/8, and HTML-Tree 5.07 `t/refloop.t`
8/8.

### CODE weak-reference owners exclude compatibility pins (2026-10-28)

Before relying on CODE owner slots, `WeakRefRegistry` retires eligible
frame-temporary scalar cells. It accepts remaining non-`CODE_SLOT` identities
as direct Perl owners. `CODE_SLOT` can represent a compatibility pin retained
after a glob is deleted; installed stash refs remain covered by the stash-root
checks. `WeakCodeOwnerSlotTest` verifies that a live lexical CODE slot skips the
scalar-root walk. The existing `subquote_defer_weak_cleanup.t` passes 8/8 on
both backends with sweeps disabled. `nice -n 19 make` passes in 8m14s. With
corrective sweeps disabled, both backends pass explicit-return cleanup 6/6,
nested-form lifetime 29/29, `weaken.t` 4/4, `destroy.t` 15/15, blessed
self-cycle 4/4, exception resurrection 11/11, Sub::Quote cleanup 8/8, and
HTML-Tree 5.07 `t/refloop.t` 8/8.

### Package scalar roots get per-key owner identities (2026-10-28)

Each package scalar table key now owns a distinct `PACKAGE_SCALAR_SLOT`
identity for its scalar cell. Replacing, removing, or clearing a key releases
that identity; aliases therefore keep separate identities. Ithread snapshots
rebuild the identities for their cloned scalar table. Package-rooted scalar
cells can now own untracked reference values without promoting the legacy
count. `UntrackedReferenceOwnerSlotTest` covers aliases, key removal, clone rebuild,
and cells already at the legacy destroyed sentinel. `nice -n 19 make` passes in
6m02s. With corrective sweeps disabled, both backends pass explicit-return
cleanup 6/6, nested-form lifetime 29/29, `weaken.t` 4/4, `destroy.t` 15/15,
blessed self-cycle 4/4, exception resurrection 11/11, Sub::Quote cleanup 8/8,
and HTML-Tree 5.07 `t/refloop.t` 8/8.

### Tied handlers and staged values retain untracked referents (2026-10-28)

Tied-handler values and staged call/return values now mark their owning scalar
cell as a durable strong slot. That lets the existing
`UNTRACKED_REFERENCE` identity represent their referents without promoting a
legacy `refCount`; release retires the identity once and defers any counted
decrement. Added owner-slot tests cover untracked values, weaken without a root
scan, and idempotent release. Runtime clone setup also tolerates rebuilding
these identities before the destination runtime is bound. The focused tied
storage and owner-slot Java tests pass, followed by the full `nice -n 19 make`
gate (6m30s). With corrective sweeps disabled, all eight lifecycle cases pass
on both backends, including HTML-Tree 5.07 `t/refloop.t` (8/8).

### Untracked mortal return values release at caller boundaries (2026-10-28)

Mortal return snapshots whose referents remain untracked or weakly tracked now
receive `UNTRACKED_REFERENCE` identities. Their release follows the existing
mortal mark stack, so nested frames cannot retire a caller's return value;
final owner release clears weak observers directly when corrective sweeps are
disabled. `MortalReturnUntrackedOwnerTest` passes both cases with sweeps
disabled and records zero root queries, snapshots, and scalar reachability
queries for final weak cleanup. The full `nice -n 19 make` gate passes in
6m17s. The sweep-disabled closure, weak/DESTROY, resurrection, cycle,
Sub::Quote, and HTML-Tree matrix passes on both backends; HTML-Tree
`t/refloop.t` is 8/8.

### Package array and hash roots get per-key owner identities (2026-10-28)

Each package array or hash key now owns a distinct `PACKAGE_AGGREGATE_SLOT`.
The global-map facade transfers or releases those identities for replacement,
key/value/entry removal, entry replacement, clear, and cloned runtime tables.
Final root release clears weak observers directly when corrective sweeps are
disabled. `SvREFCNT` keeps Perl's special `%^H` count by excluding its hidden
package-root identity. `PackageAggregateOwnerSlotTest` covers aliases,
replacement, clearing, clone reconstruction, and weak cleanup; related tests
now live in new test classes instead of modifying existing test files.
The full `nice -n 19 make` gate passes in 7m05s. With sweeps disabled, 16
closure, weak/DESTROY, resurrection, cycle, Sub::Quote, and HTML-Tree cases
pass on both backends; HTML-Tree `t/refloop.t` is 8/8.

### Weak CODE release uses direct owner identities (2026-10-28)

Package-rooted array and hash cells now register their value ownership when
the aggregate is marked as a package root. `shouldKeepCodeWeakRefs` relies on
active owner slots after checking installed CODE slots, active frames, and
retiring stale frame temporaries. It no longer consults sweep-local live sets,
full-root snapshots, or scalar-root scans. `WeakCodeOwnerSlotTest` verifies
lexical and package-array CODE owners with zero reachability queries. The full
`nice -n 19 make` gate passes in 6m33s, and the 16-case sweeps-disabled
JVM/interpreter matrix passes, including HTML-Tree `t/refloop.t` 8/8.

### Untracked container values refresh and retire owners (2026-10-28)

Array and hash writes now refresh untracked scalar referent identities after
the element is installed. Copy construction refreshes only after the new
storage is assigned, and scope cleanup releases each identity before the
no-weak-reference fast return. Repeated cleanup leaves no stale identity.
`UntrackedReferenceOwnerSlotTest` covers raw and normal array/hash writes,
copy construction, removal, and scope exit without root or scalar-reachability
queries. Untracked array/hash referents still use the existing root proof on
their first weak transition; their native release semantics remain to be
migrated. `nice -n 19 make` passes in 9m55s. With corrective sweeps disabled,
the 16-case lifecycle matrix passes on both backends, including HTML-Tree
`t/refloop.t` 8/8.

### Active lexical pads own aggregate cells (2026-10-28)

Active lexical frames and top-level pads now keep owner identities for registered
array and hash cells. Lexical scope cleanup releases each pad identity after
aggregate cleanup, allowing weak observers to clear directly when corrective
sweeps are disabled. `ActiveLexicalAggregateOwnerSlotTest` covers both pad
types, and the UNITCHECK capture regression guards against releasing captured
children too early. The full `nice -n 19 make` gate passes in 8m02s. With sweeps
disabled, the UNITCHECK and lifecycle matrix passes on both backends, and
HTML-Tree 5.07 `t/refloop.t` passes 8/8 on both backends. The recursive
`new_from_lol` reduction still fails three interpreter assertions; this is the
known interpreter lifecycle gap tracked below.

### Untracked aggregate values use explicit owner identities (2026-10-28)

Durable scalar cells now attach `UNTRACKED_REFERENCE` identities to array and
hash referents as well as scalar referents, without promoting their legacy
counts. First weaken trusts direct uncounted slot identities, including active
lexical and package aggregate roots. Final release of a weakly-tracked aggregate
owner now performs direct destruction and cascading cleanup with sweeps disabled;
blessed aggregates run `DESTROY` before weak observers clear. The new
`UntrackedAggregateOwnerSlotTest` covers two independent scalar owners, lexical
and package roots, zero root queries, and blessed `DESTROY` ordering. The full
`nice -n 19 make` gate passes in 8m48s. With sweeps disabled, the UNITCHECK,
closure, weak/DESTROY/resurrection/cycle, Sub::Quote, and HTML-Tree `t/refloop.t`
8/8 tests pass on both backends. The recursive `new_from_lol` gap was caused by
interpreter scope cleanup emitting releases for captured outer pad registers.
`BytecodeCompiler` now excludes those registers from normal, explicit-return,
condition, and tail-call cleanup, matching the JVM backend. The
`issue_1649_recursive_builder_pad_release.t` regression passes 9/9 on both
backends with corrective sweeps disabled and on system Perl. An earlier
unfiltered `make` reported four JVM lifetime failures:
`catalyst_request_body_tempfile_lifetime.t`,
`file_temp_path_wrapper_lifetime.t`,
`inc_hook_generator_state.t`, and
`refcount/blessed_scalar_reference_contents_destroy.t`. The full no-sweeps
2026-10-29 `make` gate now passes; the AnyEvent listener test remains
environment-dependent because this host cannot allocate its loopback port.

### Package-rooted untracked aggregate release (2026-10-29)

When first weaken uses a package-root proof to preserve an untracked aggregate,
the temporary compatibility count now retires after its final untracked owner
slot leaves. `RuntimeScalar.undefine()` refreshes the scalar's owner identity,
and counted-slot promotion records the new owner before releasing the old one.
The new Java two-owner regression and Perl package-array/hash regression pass;
the six Perl assertions pass on system Perl and both PerlOnJava backends with
corrective sweeps disabled. The first-weaken root query now returns from the
owner-slot ledger before inspecting package edges; its regression records one
query and zero edges or nodes visited. The full no-sweeps `nice -n 19 make`
passes in 11m. HTML-Tree 5.07 `t/refloop.t` passes 8/8 on both backends, and
closure, weak, DESTROY, resurrection, and cycle regressions pass on both
backends with sweeps disabled.

### JVM-live scalar registry is not Perl ownership (2026-10-29)

The zero-count branch in `WeakRefRegistry.weaken` no longer uses
`isReachableFromLiveScalarRegistry` to rescue a referent. Tail-call cleanup
also stopped using that forced-GC registry scan. A Java-live wrapper in the
weak-key registry is not a Perl owner; durable scalar owners must be
represented by owner slots or another explicit Perl root.
`ScalarRegistryIsNotPerlOwnerTest` covers first-weaken cleanup, and
`TailCallScalarRegistryOwnershipTest` covers the released tail-call owner.
Async Future resume now checks the referent's counted or direct owner-slot
state, excluding temporary evaluation roots. Other sweep and reachability
callers still need to be migrated. `PerlStrongOwnerProofTest` covers counted,
untracked, and temporary owner slots; `FutureAsyncAwaitRuntimeTest` passes in
the full no-sweeps `make` gate.

### Uncounted owner slots release without a root walk (2026-10-29)

When weakening the final counted probe leaves an uncounted strong slot,
`WeakRefRegistry.weaken` now trusts that slot directly and transitions the
referent to `WEAKLY_TRACKED`. Releasing the final slot then clears weak
observers immediately, without a graph query or compatibility count. The new
`WeakUncountedOwnerSlotFastPathTest` checks the transition, zero root queries,
and final cleanup. The no-sweeps full `nice -n 19 make` gate passes in 10m30s.
The weak, DESTROY, resurrection, closure, and cycle matrix passes on both
backends; HTML-Tree 5.07 `t/refloop.t` passes 8/8 on both.

### Untracked first-weaken avoids generic root fallback (2026-10-29)

First weaken of an otherwise untracked aggregate now keeps the transitional
`WEAKLY_TRACKED` sentinel until a durable owner slot is installed, without
walking the general root graph. The narrow legacy package-root rescue remains
for aggregates already nested in a package global. Added
`UntrackedAggregateFirstWeakenFastPathTest` for the zero-query transition and
direct final-slot cleanup. The no-sweeps full `nice -n 19 make` gate passes in
10m34s; HTML-Tree 5.07 `t/refloop.t` passes 8/8 on both backends.

### Counted scalar release trusts surviving uncounted slots (2026-10-29)

When overwriting the last counted scalar reference leaves an uncounted owner
slot, `RuntimeScalar` now transitions a non-CODE referent to `WEAKLY_TRACKED`
directly. The final slot release then clears weak observers without a
package-root query. `RuntimeScalarUncountedOwnerRepairTest` covers the
undercount repair and final release with corrective sweeps disabled. CODE keeps
its native active-frame zero-count protocol; remaining root checks protect
owners that do not yet have slot identities.

### Localized CODE release uses owner slots (2026-10-29)

Restoring a localized glob now releases the displaced CODE and its captured
pads when no active frame or owner slot remains. The release path no longer
scans scalar registries or root graphs; the existing active-frame owner test
still protects executing CODE. `LocalizedGlobCodeOwnerReleaseTest` verifies
capture cleanup and zero root queries. The no-sweeps full `nice -n 19 make`
passes in 10m45s. The closure, weak-reference, DESTROY, resurrection, cycle,
localized-glob, and HTML-Tree `t/refloop.t` (8/8) matrix passes on both backends.

### Uncounted owners defer zero-count cleanup without weak observers (2026-10-29)

Scalar overwrite, deferred decrement, and tail-call release now recognize a
surviving uncounted aggregate owner slot before reaching root fallbacks. Final
slot release dispatches cleanup even when no weak observer exists. A separate
pending-release marker keeps this path distinct from legacy `WEAKLY_TRACKED`
values. `UncountedOwnerSlotZeroCountReleaseTest` covers all three paths and
verifies exactly one final cleanup with no root query. The scalar-overwrite
path still needs direct owner-slot coverage for scalar and IO referents.

### Counted slots repair unblessed scalar overwrites (2026-10-29)

`RuntimeScalar.setLargeRefCounted()` and `MortalList.processDeferredBase()` now
restore transient zeros from verified, current array/hash element slots for
unblessed referents; blessed referents keep the counted-slot path. The check
requires that the owner scalar still points at the referent and remains in its
recorded container. `CountedOwnerSlotRepairsUnblessedReferentTest` covers scalar
overwrite, and `DeferredUnblessedAggregateOwnerSlotRepairTest` covers deferred
release. Both verify no root query or snapshot and weak clearing after the final
slot leaves. Scalar-cell-only owners without container provenance still use the
root snapshot, so stale wrappers are not mistaken for live Perl roots.

The full no-sweeps `nice -n 19 make` gate passes in 11m03s. Six lifecycle tests
pass 83/83 on both backends, and HTML-Tree 5.07 `t/refloop.t` passes 8/8 on both
backends with sweeps disabled. The existing scalar-wrapper snapshot tests also
pass in the full gate.

### Tail-call cleanup trusts direct uncounted owner slots (2026-10-29)

Tail-call release now defers final cleanup when a non-counted Perl owner slot
remains, including package scalar slots. Temporary runtime roots do not count as
Perl owners. `TailCallUncountedOwnerSlotFastPathTest` checks zero root queries
and cleanup after the package slot leaves. The no-sweeps `nice -n 19 make` gate
passed in 10m58s; the regression passed with no skips or failures.

### Durable uncounted slots survive referent promotion (2026-10-29)

An existing durable uncounted scalar slot now remains in the ledger when its
referent becomes tracked. Scalar overwrite and deferred release defer cleanup to
that slot instead of trusting a transient zero. Final release checks the exact
owning scalar and its container's Perl ownership, so a temporary argument array
cannot pin the referent after its frame ends. `PromotedUntrackedOwnerSlotTest`
covers package ownership and temporary-container cleanup. The no-sweeps full
`nice -n 19 make` gate passed in 14m59s. Blessed scalar contents, Catalyst
tempfile lifetime, closure cleanup, weak/DESTROY, exception resurrection,
weak-closure, and HTML-Tree `t/refloop.t` (8/8) passed on both backends.

### Registered CODE owners skip a duplicate scalar query (2026-10-29)

Void-discard cleanup now accepts registered container membership as direct
proof that a CODE scalar slot is live, without repeating the same check through
`ReachabilityWalker.isScalarReachable()`. `MortalizeRegisteredCodeOwnerFastPathTest`
asserts that cleanup performs zero scalar reachability queries and root walks.
The full `nice -n 19 make` gate passed in 21m18s with corrective sweeps disabled.

### Captured scalar-cell owners repair transient undercounts (2026-10-29)

Immediate scalar overwrite and deferred cleanup now restore an unblessed
referent count from its exact current scalar-cell owner when that cell has an
active capture slot whose CODE owner is still live. This avoids root walks for
captured scalar-cell ownership without treating conservative captures as Perl
owners. `CapturedScalarCellOwnerFastPathTest` covers both cleanup paths, checks
that root-query counters stay at zero, and verifies weak cleanup after the last
capture is released. The full no-sweeps `nice -n 19 make` gate passes in 14m02s.

### Final scalar CODE owner releases captures from the owner ledger (2026-10-29)

Overwriting the final scalar CODE owner now releases closure captures after
checking other scalar, stash, active-frame, transfer, and installed-global
owners directly. It does not trust a possibly inflated compatibility count or
walk the root graph. `FinalCodeOwnerCaptureReleaseFastPathTest` verifies the
final pad capture is released with zero root queries and snapshots. The full
no-sweeps `nice -n 19 make` gate passes in 13m16s.

### Aggregate captures release from CODE owner identities (2026-10-29)

Scope-exit cleanup now decides whether a captured aggregate's CODE owner is
live from owner-slot identities, ignoring a stale positive compatibility
count. Compiled regex callbacks now keep their CODE object through a typed
owner slot, so their captured pads survive until the regex releases that
callback. `ClosureAggregateOwnerIdentityReleaseTest` covers stale-count
release and retention by a scalar owner; the existing callback DESTROY test
covers callback lifetime. The legacy weak-backref graph walk remains for
captures that have no recorded owner identity. The no-sweeps full `make` gate
passed in 15m56s. The callback DESTROY test passed 3/3 on standard Perl and
both PerlOnJava backends; HTML-Tree `t/refloop.t` passed 8/8 on both backends.
The owner-slot regressions cover stale positive CODE counts and preservation by
live scalar and callback owners.

### Non-captured scalar-cell owners repair transient undercounts (2026-10-29)

Immediate overwrite and deferred cleanup now restore an unblessed referent
from verified current scalar-cell identities, including return, argument, and
CODE slots. The repair counts current scalar and aggregate owners together;
unrooted wrappers and capture counts without a live CODE owner do not prove
liveness. Focused regressions cover both cleanup paths, mixed owner families,
weak clearing, and zero root snapshots. The no-sweeps `nice -n 19 make` gate
passed in 10m41s. HTML-Tree 5.07 `t/refloop.t` passed 8/8 on both backends;
`make check-links` passed.

### First-weaken package aggregate proof (2026-10-29)

First weakening of an untracked package aggregate now validates its active
`UNTRACKED_REFERENCE` scalar owners directly, including whether each owner
still occupies a durable strong slot. This removes the root-reachability call
from that transition. The existing package-nested owner-slot tests pass as
part of the no-sweeps `nice -n 19 make` gate (11m53s), including the proof-only
query with zero graph edges and the final-owner weak-observer release.
HTML-Tree 5.07 `t/refloop.t` passes 8/8 on both backends with sweeps disabled.

### Tail-call argument owner release without a root walk (2026-10-29)

Tail-call argument release now finalizes from counted and uncounted owner
identities. The root-graph fallback is removed; a new regression confirms that
a Java-live scalar-registry entry cannot preserve the released Perl referent or
cause a root query. The test passes in the full no-sweeps `nice -n 19 make`
gate (13m06s); HTML-Tree 5.07 `t/refloop.t` passes 8/8 on both backends with
sweeps disabled.

### Package scalar value slots for tracked referents (2026-10-29)

A package scalar cell now records an `UNTRACKED_REFERENCE` identity for a
strong referent value when the cell has no counted owner slot. The identity
does not inflate the legacy count and is released with the package scalar slot.
A regression checks that an independent counted owner survives until its own
release. The full no-sweeps `nice -n 19 make` gate passes in 10m35s, and
HTML-Tree 5.07 `t/refloop.t` passes 8/8 on both backends with sweeps disabled.

Live lexical array and hash cells with no counted owner slot also record an
`UNTRACKED_REFERENCE` identity for their current referent. The slot keeps a
tracked referent alive across another owner's release and clears weak observers
when the aggregate cell releases its final value owner. The new regression and
full no-sweeps `nice -n 19 make` gate pass (12m51s); HTML-Tree 5.07
`t/refloop.t` passes 8/8 on both backends with sweeps disabled.

### Package aggregate scalar-cell values (2026-10-29)

Prepopulated package arrays and hashes now record an `UNTRACKED_REFERENCE`
identity when a value slot points to a scalar cell with no counted owner. The
new regression checks final weak clearing without root queries or snapshots on
both aggregate types. The full no-sweeps `nice -n 19 make` gate passes in 6m53s.
Package aggregate edges to array/hash referents and cross-container aliases
remain on the root-snapshot path.

### Prepopulated package aggregate values that reference aggregates (2026-10-29)

When a package array or hash becomes rooted with existing values, its scalar
cells now record an `UNTRACKED_REFERENCE` identity for array/hash referents.
Final value replacement clears weak observers without root queries or
snapshots. The identity is limited to cells present when the package aggregate
becomes a root; values inserted later and cross-container aliases retain the
root-snapshot fallback until their storage provenance is explicit. The new
array/hash regressions and full no-sweeps `nice -n 19 make` gate pass (9m30s).

### Caller arguments preserve source scalar identity (2026-10-30)

`caller()` now populates `@DB::args` with the selected frame's existing scalar
cells instead of copied wrappers. Writes through the debugger array therefore
reach the caller's variable, and source-cell cleanup releases the same owner
slot rather than leaving a detached untracked reference behind. Aliased package
arrays no longer promote their borrowed cells to package roots.

The new `caller_db_args_alias_slot.t` regression passes under system Perl and
both PerlOnJava backends; it fails on the preceding JAR by leaving the caller's
value unchanged. The existing tied-args, freed-argument, DESTROY-capture,
zombie-reference, and failed-constructor weak-cache tests pass on system Perl
and both backends. The full no-sweeps `nice -n 19 make` gate passes, and
HTML-Tree 5.07 `t/refloop.t` passes 8/8 on both backends.

### Undef releases one weakly tracked owner at a time (2026-10-30)

`RuntimeScalar.undefine()` now checks the remaining Perl owner identities after
retiring the current cell's untracked owner. A weakly tracked referent stays live
while another owner slot remains; the final slot release clears weak observers
and performs cleanup. This fixes the unconditional cleanup in `undefine()`
instead of relying on a positive legacy count to hide that path.
`WeaklyTrackedUndefOwnerSlotTest` covers two independent lexical cells, and
`weakly_tracked_undef_preserves_other_owner.t` records the standard Perl
behavior. Both backend runs pass with corrective sweeps disabled. The no-sweeps
full `nice -n 19 make` gate passes in 11m55s, and HTML-Tree 5.07 `t/refloop.t`
passes 8/8 on both backends.

### Package-nested aggregate cleanup uses owner identities (2026-10-30)

The package-root compatibility count remains required by the existing
root-proof contract, but it now has a dedicated owner-slot identity. The
package element scalar slots remain the Perl ownership evidence; when their final slot
leaves, cleanup retires the compatibility identity and count before clearing
the weak observer. This makes the transitional pin explicit and exactly-once
instead of leaving it as an untracked positive count. A focused Java regression
checks pin identity, final-slot release, idempotence, and unweakened count
preservation; a Perl-level test records the package array lifetime oracle. The
root proof validates direct scalar-owner identities without traversing package
edges.

### Interpreter return ownership precedes aggregate cleanup (2026-10-30)

The interpreter previously prepared an explicit return value from scalar
cleanup opcodes. A subroutine with only an array or hash lexical skipped those
opcodes, so aggregate cleanup could release a returned closure's captures
before the closure acquired its return owner. The bytecode now prepares the
return owner once before any scalar, array, or hash cleanup, matching the JVM
backend's ordering. The existing `closure_capture_explicit_return.t` test
reproduces the interpreter failure and passes 6/6 on standard Perl, the JVM,
and the interpreter after the change, with corrective sweeps disabled. The
full no-sweeps `nice -n 19 make` gate passes in 12m47s. The related closure,
weak-reference, DESTROY, resurrection, and cycle matrix passes on both
backends; HTML-Tree 5.07 `t/refloop.t` passes 8/8 on both.

### Growing recursive output avoids repeated live-root scans (2026-10-30)

Added `issue_1860_growing_lexical_output.t`, a project-owned reproduction of
Data::Tumbler's recursive path/value/payload results accumulating in a lexical
output array. It passes 4/4 on system Perl and both PerlOnJava backends with
corrective sweeps disabled. `GrowingLexicalOutputOwnerSlotCostTest` grows a
256-row nested output graph while running the corresponding temporary-array
cleanup; its measured work is zero root queries, live/external snapshots, and
edges inspected, while weak observers stay live until the final output owner
is removed. The full no-sweeps `nice -n 19 make` gate passes in 12m39s.
Unchanged Data-Tumbler 0.010 `t/90-stress.t` generates all 65,536 results in
7.38s on the JVM backend and 11.20s on the interpreter; system Perl completes
it in 0.15s.

### Mojolicious controller teardown follows callback owners (2026-10-30)

Added focused #1794 coverage for controller destruction after a long-poll
response finishes, controller retention while a WebSocket is open, and release
of a controller captured by transaction callbacks. The three project tests pass
14/14 on system Perl and both PerlOnJava backends with corrective sweeps
disabled. `NestedUntrackedOwnerFinalCellCleanupTest` also passes 1/1: retiring
the nested cell slot preserves an independently counted owner and its weak
observer, which clears when that counted owner leaves. The no-sweeps
`nice -n 19 make test-unit` gate passes in 12m24s. Mojolicious 9.53
`t/mojolicious/app.t` passes 459/459 and
`t/mojolicious/websocket_lite_app.t` passes 35/35 on all three runtimes. The
pre-fix baseline reproduces the controller teardown failure in both backends;
the WebSocket server-push suite also passes on that baseline, so the added
WebSocket case remains acceptance coverage rather than a reproduced failure.
The full `nice -n 19 make` gate still needs to run after this ownership batch is
complete.

### Weak CODE capture walk removed from quiet sweep decisions (2026-10-30)

Quiet weak-reference cleanup no longer builds `WeakBackrefCaptureSnapshot` to
preserve aggregate candidates by traversing captures from weak CODE referents.
Real closure captures already carry `CLOSURE_PAD`, `ARRAY_ELEMENT`, and
`HASH_ELEMENT` owner identities; the regular issue-1649 aggregate lifecycle
tests pass with corrective sweeps disabled on both backends. The weakly held
closure oracle also clears both CODE and captured target on system Perl and
both backends. Full `nice -n 19 make` passes in 7m59s.

The snapshot helper remains reachable only through the post-DESTROY diagnostic
path in `MortalList.flush`; `WeakBackrefCodeCaptureSweepTest` currently asserts
those diagnostic walk counters. The production corrective sweep constant is
already disabled, so this gate compiled the removal but did not execute that
legacy sweep branch. Remove the remaining diagnostic walk when the test no
longer requires its internal counters.

### Weakening retires stale untracked owner slots (2026-10-30)

When the last counted owner is weakened, `WeakRefRegistry.weaken()` now
refreshes `UNTRACKED_REFERENCE` slots against their scalar cell's current
durable storage before preserving the referent. A cell whose container has
lost its Perl owner can no longer leave a stale slot behind and strand the weak
observer until a later sweep. `WeakeningPrunesStaleUntrackedOwnerSlotTest`
checks final weak clearing with zero root queries or snapshots. The full
`nice -n 19 make` gate passes in 8m08s; issue-1649 aggregate and closure-owner
tests pass on both backends, as does HTML-Tree 5.07 `t/refloop.t` (8/8 each).

### Interpreter pad constants follow the published CV owner (2026-10-31)

Lazy interpreter materialization left optree constant owner slots on the
temporary `InterpretedCode` wrapper, while the stable placeholder is the
Perl-owned CV. Transfer the direct and embedded pad-constant tree to that
placeholder on both the normal interpreter path and the VerifyError fallback.
The stale-positive-count cleanup check seeds a positive `refCount` in the Java
test to exercise this boundary; it is an invariant test, not evidence that this
scenario naturally leaves a positive count after the last owner exits. In
production, cleanup uses the remaining owner identities instead of treating the
compatibility count as another owner. The
`pad_constant_cv_owner_lifetime.t` oracle passes 10/10 on system Perl and both
backends with corrective sweeps disabled; the new Java owner-slot regression
also checks final pad-tree release with zero root queries or snapshots. The
full `nice -n 19 make` gate passes in 7m27s. HTML-Tree 5.07 `t/refloop.t`
passes 8/8 on all three runtimes. The #1860 growing-output regression and
Data-Tumbler 0.010 `t/90-stress.t` pass on system Perl and both no-sweeps
backends; the stress case generates 65,536 results in 6.56s on JVM and 10.76s
on the interpreter. The #1794 controller and WebSocket regressions pass on
system Perl and both no-sweeps backends.

### Full upstream HTML::Tree 5.07 suite (2026-10-31)

Ran all 23 test files from the [upstream HTML-Tree 5.07 distribution](https://metacpan.org/dist/HTML-Tree),
not just the previously extracted `t/refloop.t` fixture. System Perl passes
969 tests; `t/leaktest.t` skips because `Test::LeakTrace` is unavailable.
JVM and interpreter each pass all 23 files with corrective sweeps disabled
(988 TAP checks per backend). The bundled PerlOnJava `Test::LeakTrace` module
is a no-op stub, so its 20 `t/leaktest.t` checks are not leak measurements.
The target `t/refloop.t` remains 8/8 on system Perl and both backends. Logs:
`/private/tmp/html-tree-full-system-perl.log`,
`/private/tmp/html-tree-full-jvm-gate.log`, and
`/private/tmp/html-tree-full-interpreter-gate.log`.

### Package-root nested weak-owner oracle (2026-10-31)

Added `src/test/resources/unit/refcount/issue_1649_package_nested_untracked_weak_owner.t`
to exercise an untracked aggregate retained only by a package array, then release
that owner while checking weak-observer cleanup. The second scenario verifies
that `unweaken` creates a strong owner that survives package-array removal and
releases the weak probe only when that scalar is dropped. The test passes 7/7 on
system Perl and both PerlOnJava backends with corrective sweeps disabled; the
full `nice -n 19 make` gate passes in 6m55s with the test included. This records
the observable contract while the package-root compatibility pin remains a
migration bridge; it does not claim the bridge has been removed.

### Next steps

1. Remove production corrective-sweep scheduling as each strong-owner family
   becomes identity-complete. Keep explicit diagnostic sweep helpers isolated
   from production lifecycle paths while existing helper tests still exercise
   them.
2. In one ownership-family batch, finish remaining CODE template and optree
   pins, raw descriptor/embedding borrows, native call buffers, escaping
   lvalues and refaliases, and targeted try/finally returns. Add regressions
   before running the focused gates and one full `nice -n 19 make`.
3. Requalify the full upstream HTML-Tree suite, weak/DESTROY, resurrection,
   cycle, and repository suite after each ownership-family migration.
4. Make semantic owner counts authoritative, then remove the compatibility
   bridge, production corrective scheduling, and reachability fallbacks after
   all acceptance gates pass.
