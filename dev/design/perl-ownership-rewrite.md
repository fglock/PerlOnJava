# Perl-compatible ownership rewrite

**Status:** Phase 1 is complete; Phase 2 is in progress. HTML-Tree 5.07 `t/refloop.t` passes 8/8 on both backends with corrective sweeps disabled. The full HTML-Tree suite passes 23 files and 989 checks on both backends. `@DB::args` now stores the selected source scalar cells directly, avoiding detached untracked-reference owners. Visible named package IO globs carry a `PACKAGE_GLOB_SLOT` identity through replacement and removal; detached typeglob snapshots and whole-namespace moves now carry explicit component owners before stash slots are removed, including through scalarization of a namespace move. `Symbol::gensym` globs are excluded from package stash ownership and retire their hidden lookup entry after their last IO owner closes. Unblessed array and hash cycles now remain live through their actual element-owner slots; deferred cleanup no longer restores a positive count from a cycle walk. CODE self-capture cleanup restores from verified counted slots rather than a graph walk. Aggregate closure capture release no longer decrements another owner's counted edge. Shared proxy release, ithread weak-slot resurrection, PadWalker rebinding, active CODE ownership, compiled/pinned/localized CODE slot reset, visible stash CODE ownership, and async cleanup regressions pass on both backends. Aggregate clear and replacement now queue IO holder release with scalar referent cleanup. Weak aliases of blessed anonymous globs are excluded from scalar owner backfill, release their IO holder on weaken, and restore it on unweaken. Rescued-object processing now consults live Perl owner identities, and a promoted weak slot gets a second `DESTROY` when its final owner leaves. Production sweep callers have been removed; count alignment still uses verified current owner slots while remaining producer gaps are audited.

**Current checkpoint (2026-11-08):** #1649 remains incomplete on candidate
`8776112b94` (`feature/issue1649-rescued-owner-finalization`, PR #1938).
`nice -n 19 make` passed in 7m23 with the new array-element refalias producer
fix and regression; log: `/tmp/issue1649-array-refalias-full-make.log`. The new
regression passes 8/8 on system Perl, JVM, and interpreter. `make check-links`
also passed. The latest GitHub API attempt failed from this host, so CI/PR state
still needs refreshing.

Earlier work on this candidate added argument-frame owner accounting to
`Internals::SvREFCNT` B-object probes and expanded
`b_refcount_destroy_aggregate_owner.t` to four cases. It passes on all three
runtimes; the unfixed runtime reported 4 instead of Perl's expected 2 in the
nested Schema/Source destructor case. Class::MethodMaker `t/array.t` passes
438/438 on system Perl 5.45.5 and both backends. The project-owned tied-array
handler lifetime regression fails on the unfixed parent and passes 4/4 on all
three runtimes. Prior focused evidence is retained in the `/tmp/issue1649-*`
logs referenced by the earlier commits.

DBIx::Class `t/18insert_default.t` passes all four assertions and its END leak
check on exact system Perl 5.34. The JVM reaches all assertions, then exits 22
because the package's END block calls the imported `assert_empty_weakregistry`
after `namespace::clean` removes its stash entry. The interpreter has a
separate Hash::Merge failure. A reduced case passes on Perl and fails on the
current JVM when another module is loaded before the importing package; a
similarly small helper without that preceding load passes. The trigger is not
isolated enough to change namespace cleanup. Keep this separate from the
storage leak assertion and its intermediate `REFCNT` discrepancy. Evidence:
`/tmp/issue1649-dbix-18insert-host-perl.log`,
`/tmp/issue1649-dbix-18insert-current-jvm.log`,
`/tmp/issue1649-dbix-end-binding-{perl,jvm}.log`, and
`/tmp/issue1649-helper-runmode-{perl,jvm}.log`.

The owner audit still has five retained mechanisms: legacy root recovery
behind an unpaired decrement hook; verified current-slot count alignment;
`sweepReleasedWeakReferents()` for direct Java compatibility/test calls;
localized CODE restore count adoption; and missing-token adoption in
`WeakRefRegistry.weaken`. Nine inventory entries are audit areas, not nine
confirmed defects. Four mechanisms have no ordinary production caller or no
demonstrated missing-token path; current-slot alignment is the only ordinary
production count-recovery path and checks exact owner identities without a
root walk. A removal experiment for the weaken adapter failed three existing
Java tests that intentionally construct an already-counted scalar without a
slot token. Keep this direct-caller compatibility contract while auditing
production producers; it is not an ordinary reachability recovery path.
Continue closing proven producer gaps before reassessing the remaining
compatibility bridges. On PR head `714b07474f`, the latest check snapshot has
Ubuntu and Windows builds running; Actions and dependency submissions passed.
The C/C++, Java/Kotlin, and Python analysis jobs failed during CodeQL
initialization because GitHub returned HTTP 504 for the CodeQL CLI bundle, so
those jobs did not analyze source. Aggregate CodeQL is neutral. Details:
[analysis run 42467369054](https://github.com/PerlOnJava/PerlOnJava/actions/runs/42467369054)
and [build run 42467371162](https://github.com/PerlOnJava/PerlOnJava/actions/runs/42467371162).
Recheck after the six-minute polling interval for build completion.

The fresh source call-site audit confirms that production code has no caller of
`MortalList.deferDecrement(RuntimeBase)` and no caller of
`ReachabilityWalker.sweepReleasedWeakReferents()`; the latter appears only in
four Java tests. All current `refCountOwned = true` assignments acquire a
`PerlOwnerSlot` or transfer an existing active slot, apart from the explicit
legacy-adoption helper. These findings support retaining the test/compatibility
contracts while continuing to look for a real producer-ordering gap; they do
not justify removing the active exact-slot alignment bridge.

The next audited producer gap was direct array-element refaliasing:
`RuntimeArrayProxyEntry.aliasToReference` installed the referenced scalar cell
without an `ARRAY_ALIAS_CELL` owner identity. The new regression passed 8/8 on
system Perl, failed on the unfixed JVM and interpreter candidates, and now
passes 8/8 on both backends. The fix acquires the identity before publishing
the slot and carries existing cell identities into array alias views and
tail-call carriers. `nice -n 19 make` passed on this source in 7m23; log:
`/tmp/issue1649-array-refalias-full-make.log`. The ordinary recovery/adoption
audit remains at five retained mechanisms; a temporary removal of missing-token
adoption failed three direct Java tests that construct a counted scalar without
a slot token. The original implementation is restored. Evidence:
`/tmp/issue1649-no-weaken-adoption-full-make.log` and XML reports in
`build/test-results/testUnitShard0/`. Next: classify the current CI failures,
then continue the producer audit before reassessing any remaining compatibility
mechanism.

### Next steps

1. Run the full `JPERL_DISABLE_CORRECTIVE_SWEEPS=1 nice -n 19 make` gate on one
   immutable candidate after the producer batch.
2. Refresh PR #1938 checks after its current Ubuntu and Windows builds finish;
   CodeQL CLI initialization failed with HTTP 504 and needs a rerun or clear
   infrastructure classification.
3. Continue the producer/release audit, especially localized CODE restore and
   exact current-slot alignment. Keep the DBIx END-block and interpreter
   Hash::Merge failures separate from storage-lifetime assertions; classify
   strict CPAN outcomes before any completion claim.

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
  gate is complete on merged source `7683d18534`; results and the remaining
  three baseline failures are recorded in the current checkpoint above.
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
- The previously failing HTML-Tree content and incremental parser release
  assertions now pass 4/4 on the current candidate and host system Perl. The
  project-owned regression is in `src/test/resources/unit/refcount/`; this
  lifecycle workload no longer demonstrates a producer gap.
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
  release, and local restoration. Try/finally return paths now unwind lexical
  registrations at each frame they leave, on both execution backends.
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

### Cleared aggregate cleanup ignores legacy count as sole owner proof (2026-10-31)

The cleared-aggregate scope-exit finalizer now checks current Perl owner
identities independently of `refCount`. A positive legacy count alone no longer
blocks final weak cleanup; temporary roots and the cached external-root proof
still preserve owners at boundaries that have not migrated. The regression
releases a nested untracked scalar owner, leaves a positive compatibility count,
and verifies the weak observer is cleared. The full `nice -n 19 make` gate passes
in 7m17s with the regression included. After this change, the full upstream
HTML-Tree 5.07 suite passes all 23 files and 989 TAP checks on both backends
with corrective sweeps disabled; `t/refloop.t` passes 8/8. The bundled
`Test::LeakTrace` stub makes HTML-Tree's 20 leak-test checks non-measurements.
The #1794 controller and WebSocket regressions pass 3/3 and 6/6 respectively
on both backends, and the #1860 growing-output regression passes 4/4 on both.
A focused post-change weak/DESTROY/resurrection/cycle matrix also passes all
8 fixtures and 68 assertions on each backend.

The count of `5` in `finalCellCleanupReleasesNestedUntrackedOwnerDespiteLegacyCount`
is assigned directly by the Java test; it is an invariant check, not evidence
that normal runtime operations create that state. A Perl-level async reproducer
did expose a real ordering window: scope-exit finalization can run after a
counted owner slot is retired but before its queued decrement drains. RuntimeBase
now tracks queued owner releases through completion, and cleared-aggregate
finalization leaves those releases to decide the lifecycle transition. A focused
Java regression exercises this real queue path and passes in the full
`nice -n 19 make` gate. The async scope-exit oracle is now tracked as
`issue_1649_cleared_aggregate_async_scope_exit.t`; it suspends on a pending
Future, checks that the frame retains its weakly observed root, then verifies
weak clearing and one `DESTROY` after resume. It passes 3/3 on system Perl and
both no-sweeps backends. This is acceptance coverage for the real Perl cleanup
path; the queued-release ordering regression remains specifically covered by
`PendingOwnerReleaseFinalizationTest`.

### Explicit returns cross try/finally wrappers (2026-10-31)

The parser lowers `try/finally` through a synthetic `:lvalue` wrapper so the
expression can preserve aliases when its caller requests an lvalue. Two frame
exit paths missed that wrapper: ordinary rvalue returns skipped acquiring a
return owner because the wrapper is marked `:lvalue`, and non-local returns
could bypass lexical teardown before the enclosing subroutine resumed. The
compiler now snapshots rvalue returns before cleanup while preserving true
lvalue aliases. The JVM wrapper registers its lexical slots even when the
static cleanup heuristic would omit them. The interpreter's lexical register
metadata includes return-specific cleanup opcodes, and both `RETURN_NONLOCAL`
propagation and marker consumption retire the active frame's slots before
leaving it. JVM and interpreter call boundaries unwind their cleanup marks as
well. The focused JUnit regression checks weak-reference and `DESTROY` timing
on both backends, plus lvalue assignment through `finally`. A
`Try::Tiny` oracle validates the standard Perl lifetime contract; system Perl
does not parse PerlOnJava's `finally` syntax directly.
The focused unit gate and full `nice -n 19 make` pass; the full gate took
7m42s. With corrective sweeps disabled, HTML::Tree `t/refloop.t` passes 8/8
on both backends. `make check-links` and standard Perl lifetime and lvalue
oracles pass.

### Scalar overwrite uses owner identities (2026-10-31)

`RuntimeScalar.setLargeRefCounted` now defers cleanup when any remaining
non-counted Perl owner slot proves the value is live, after refreshing the
untracked scalar-cell ledger and repairing current counted owners. This removes
the package-global reachability query from scalar overwrite. Production callers
no longer request corrective sweeps during scalar overwrite/undef, lexical
scope exit, or final weak-owner release; the explicit diagnostic helpers remain
for their focused tests. The first post-rebase compile caught duplicate
`RuntimeHashElementMap` mutator overrides from combining the cache and owner
reconciliation changes. The duplicate block is removed; the owner-aware Map
methods and collection views remain, and collection removal now reconciles the
same owner slot as direct removal. The first full gate then caught three missed
merge behaviors: Map mutators and entry/key views must disable stable-method
caching while retaining owner hooks, and an executing CODE with active frames
must defer final release at refcount zero until its last `ACTIVE_CODE` owner
leaves. Those source paths are corrected.

Post-rebase validation on 2026-10-31 passes: the full `nice -n 19 make` gate
completed in 22m41s with Gradle limited to one worker for the busy host. Eight
focused lifecycle fixtures covering weaken, DESTROY, exception resurrection,
self-cycles, nested-form weak lifetime, explicit closure return, Sub::Quote
cleanup, and async scope exit pass 80/80 on each backend with corrective
sweeps disabled. The full upstream HTML-Tree 5.07 suite passes all 23 files
and 989 TAP checks on both backends; `t/refloop.t` is 8/8 on system Perl and
both backends. The async scope-exit regression passes 3/3 on system Perl with
standard `Future::AsyncAwait` 0.71 installed in a temporary local Perl library.
Logs: `/private/tmp/ownership-rebased-make-20261031-serial.txt`, files with
the `/private/tmp/ownership-rebased-{jvm,interpreter}-` prefix,
`/private/tmp/ownership-rebased-html-tree-full-jvm.log`,
`/private/tmp/ownership-rebased-html-tree-full-interpreter.log`,
`/private/tmp/ownership-rebased-html-refloop-perl.log`, and
`/private/tmp/ownership-rebased-async-scope-system-perl.log`.

### DESTROY checks use current owner-slot identities (2026-10-31)

Global-destruction resurrection checks now compare owner slots before and
after `DESTROY` and validate counted scalar and aggregate slots against their
current storage. Durable untracked scalar owners and optree constant pins are
recognized directly. Staged arguments, return transfers, argument aliases,
active CODE frames, temporary roots, and the weak-tracking compatibility pin
do not count as resurrection. Non-global DESTROY now validates current owner
slots as well; the legacy positive-count rule remains only when no owner-slot
identity exists. Detached scalar cells no longer survive on a stale count,
and neither path walks scalar roots to decide whether destruction should be
deferred.

`DestroyOwnerSlotResurrectionTest` covers counted and untracked durable owners,
a detached counted cell, a temporary call argument, and non-global checks for
both live and detached counted cells. The focused Make gate and full serial
`nice -n 19 make` pass; the full gate took 22m17s. With corrective sweeps
disabled, #1794 controller release passes 3/3, the long-poll controller test
passes 5/5, and the WebSocket lifetime test passes 6/6 on both backends. The
#1860 growing-output test passes 4/4, tied-hash autovivification passes 6/6,
and both HTML lifecycle reproducers pass 4/4 on each backend. The latter
socket-based tests required extended access to enable loopback sockets.
HTML::Tree 5.07 passes all 23 files and 989 TAP checks on each backend,
including `t/refloop.t` 8/8. Its single `TODO` assertion remains marked TODO;
the bundled `Test::LeakTrace` stub does not measure leaks. Logs:
`/private/tmp/ownership-nonglobal-destroy-owner-full-make-20261031.txt`,
`/private/tmp/ownership-nonglobal-destroy-owner-20261031.txt`,
`/private/tmp/ownership-nonglobal-ext-{jvm,interpreter}-mojo-{controller,websocket}.log`,
`/private/tmp/ownership-nonglobal-{jvm,interpreter}-*-20261031.log`, and
`/private/tmp/ownership-nonglobal-htmltree-{jvm,interpreter}-*-20261031.log`.

### Scalar-reference contents have retargetable owner slots (2026-10-31)

Scalar-reference contents now use `SCALAR_REFERENCE_CONTENTS` owner slots
instead of a wrapper boolean and anonymous `refCount` adjustments. Each strong
wrapper has a stable slot identity; aliases to one mutable scalar cell share
one compatibility count. Replacing the cell's value retargets those slots, and
final direct or deferred release carries the slot provenance into counted
destruction. Global and lexical localization transfer the slot identity with
the saved scalar state. `DestroyDispatch` validates the owner wrapper, scalar
cell, and current referent before treating the slot as live.

`PerlOwnerSlotScalarReferenceContentsTest` covers retargeting, alias release,
and deferred final release. Three Perl regressions cover returned scalar
references, mutable contents, and localized global teardown. The full
single-worker `nice -n 19 make` passes with corrective sweeps disabled in 23m;
all 30 focused ownership, #1794, #1860, weak/DESTROY/resurrection/cycle, and
async cleanup runs pass on both backends. HTML-Tree 5.07 `t/refloop.t` passes
8/8 on system Perl, JVM, and interpreter. Logs:
`/private/tmp/ownership-scalar-reference-full-make-20261031.log`,
`/private/tmp/ownership-scalar-reference-focused-status-20261031.txt`, and
`/private/tmp/ownership-scalar-reference-html-tree-refloop-{system-perl,jvm,interpreter}-20261031.log`.

### Rescued-object cleanup checks owner identity (2026-10-31)

After explicit undef, nested weak cleanup now checks live Perl owner identities
directly. The rescued-object queue and temporary evaluation roots do not count
as Perl owners, and this decision no longer walks external roots. The new
`RescuedObjectOwnerIdentityTest` covers durable package ownership and confirms
that rescued and temporary roots do not trigger root queries.

The full single-worker `nice -n 19 make` passes in 22m56s. With corrective
sweeps disabled, 32 focused ownership, lifecycle, weak-reference, resurrection,
and cycle runs pass across JVM and interpreter. HTML-Tree 5.07 `t/refloop.t`
passes 8/8 on both backends. Logs:
`/private/tmp/ownership-scalar-rescue-owner-full-make-20261031.log`,
`/private/tmp/ownership-rescue-owner-final-status-20261031.txt`, and
`/private/tmp/ownership-rescue-owner-html-tree-refloop-{jvm,interpreter}-20261031.log`.

### Suspended async frames retain lexical owner identities (2026-10-31)

Before the active cleanup mark unwinds at `await`, the suspended interpreter
frame now records owner slots for its live lexical registers. Scalar-backed
slots follow reassignment and weakening; each slot leaves at lexical scope exit
or frame completion, failure, or cancellation. The final frame-owner release
rechecks cleared aggregate cleanup after any queued counted release is visible.
That lets async cleanup use exact owner identities without treating a stale
positive compatibility count as a reason to rebuild external roots.

`ClearedAggregateOwnerIdentityNoRootWalkTest` requires immediate weak-observer
cleanup with zero root queries. `PendingOwnerReleaseFinalizationTest` confirms
queued decrements remain authoritative. The async scope-exit Perl fixture passes
on system Perl, JVM, and interpreter. The full single-worker no-sweeps
`nice -n 19 make` passes in 21m52s. Logs:
`/private/tmp/ownership-async-system-perl-current.txt`,
`/private/tmp/ownership-async-owner-jvm-final-20261031.log`,
`/private/tmp/ownership-async-owner-interpreter-final-20261031.log`, and
`/private/tmp/ownership-async-owner-identities-final-make-20261031.log`.

### Tied-handler cleanup uses nested owner slots (2026-10-31)

Deferred release no longer builds a tied-handler root snapshot. A handler's
`TIE_HANDLER` slot keeps the handler alive, while each value in its hash keeps
its own current `HASH_ELEMENT` slot. `TiedHandlerNestedOwnerSlotRepairTest`
models a legacy count undercount, drains the deferred release, and requires the
nested value to survive with zero root queries before its final hash slot
clears the weak observer. The final `nice -n 19 make` gate passed on
`59a4e03eb` in 7m50s with corrective sweeps disabled; `make check-links`
passed at that checkpoint. Full gate log:
`/private/tmp/ownership-tied-handler-no-sweeps-make-resumed-20261031.log`.

The Perl-level companion `refcount/tied_handler_nested_value_owner.t` covers
the normal producer path: a tie handler's nested hash element owns the target
until `untie` releases the handler. It passes on system Perl, the JVM, and the
interpreter. `TiedHandlerNestedOwnerCountInvariantTest` asserts the normal
nested `HASH_ELEMENT` and scalar owner slots balance `refCount` before and
after first weaken, then checks zero root queries at untie. The filtered
`nice -n 19 make` gate passes in 1m15s; logs:
`/private/tmp/tied-handler-nested-owner-system-perl.log`,
`/private/tmp/tied-handler-nested-owner-jvm.log`,
`/private/tmp/tied-handler-nested-owner-interpreter.log`, and
`/private/tmp/tied-handler-nested-owner-focused-make.log`.
The final full no-sweeps `nice -n 19 make` including this fixture passed on
the immutable commit `cbebfc724` in 6m57s; log:
`/private/tmp/ownership-tied-handler-immutable-make-20261031.log`.

The remaining `processDeferredBase` recovery paths still mix several owner
families. The next audit distinguishes the semantic edge from the fallback
that currently protects it:

| Recovery path | Semantic strong edge | Remaining gap before removal |
|---|---|---|
| `restoreReachableOwnerCount` | Counted scalar-cell owner slots, formerly mirrored by `activeOwners`, including nested package-array cells | The audited array and hash mutation wrappers mark current storage; their main count-acquiring APIs do so after insertion. The remaining root-snapshot tests exercise earlier count acquisition: `ReachabilityQueryCostTest.deferredOwnerChecksShareOneRootSnapshotPerDrain` calls `incrementRefCountForContainerStore` before adding the scalar to the nested package array, leaving a `SCALAR_CELL` identity; `UnblessedAggregateReachabilitySnapshotTest.repeatedOwnerScalarChecksShareOneRootSnapshotPerDrain` also clears `containerOwner` directly. No production caller with this exact ordering has been identified in the call-site audit so far. These tests preserve the bridge for synthetic/out-of-order states and do not establish a normal store-path defect. |
| Scalar overwrite (`RuntimeScalar.setLargeRefCounted`) | The scalar cell's old referent edge; new edge is acquired before old release | Scalar-referent cells with a live uncounted lexical/package identity now defer zero-count cleanup to that exact edge. Counted scalar/aggregate slots still repair identified undercounts. External-root/live-CODE checks and the blessed weak-referent raw-count bridge remain for aggregate referents; trace their producers and final release before removal. `ActiveLexicalScalarOwnerLifecycleTest` covers overwrite and observer lifetime. |
| First weaken (`WeakRefRegistry.weaken`) | The scalar cell being weakened and any other current scalar, aggregate, or capture owners | Ordinary tracked owners use current counted-slot identities; general live-CODE and strong-root walks were removed from this path. Package-nested untracked aggregates now use exact `UNTRACKED_REFERENCE` identities without a compatibility pin or fabricated count. The cloned-thread snapshot root and direct CODE pad-constant check remain. `FirstWeakenUsesCurrentPackageOwnerTest`, `WeakeningCountedOwnerSlotTest`, and `PackageNestedUntrackedAggregateReleaseTest` cover current owner identities and weak-observer lifetime. |
| Unblessed aggregate root snapshot | Array/hash elements reachable through lexical or package storage | Normal slots have identities; parser assembly and compatibility mutation paths still need producer-level invariant coverage. `UnblessedAggregateReachabilitySnapshotTest` deliberately exercises the remaining fallback. |
| Strong-cycle graph walk | Internal scalar-reference and aggregate-element edges | The CODE weak-referent cycle walk has been removed; non-final CODE releases restore from verified current counted slots. Self-capture lifetime is covered on both backends. Audit uncounted cycle edge kinds and producer coverage before removing remaining cycle or cleanup recovery. |
| Deferred live/global CODE capture walk | Captured scalar cells, aggregate pads, and persistent CODE state | Persistent state cells and aggregates now carry `CODE_STATE` identities; closure captures use `SCALAR_CAPTURE` / `CLOSURE_PAD` slots. The deferred traversal and its unused live/global query helpers are removed. |
| Nested-call depth pin | Active argument aliases and compiler temporaries | Borrowed aliases and owned argument/return values must remain distinct through unwind and async suspension. |
| Blessed root snapshots and package cache | Package scalar, aggregate, glob, or CODE slots | Package scalar, array/hash, named IO glob, and CODE stash entries have owner identities. Whole-glob aliases route these components through wrapper maps; thread-clone setup rebuilds owner ledgers after raw-map population and before activation. Remaining root snapshots serve compatibility or diagnostic paths rather than ordinary ownership decisions. |
| Closure cleanup (`RuntimeCode.releaseCaptures`) | `SCALAR_CAPTURE`, `CLOSURE_PAD`, and aggregate capture slots held by live CODE | Scalar and aggregate referents use native `CLOSURE_PAD` owner slots. Captured pad cells retain `SCALAR_CAPTURE` identities. Closure release no longer queues a raw count decrement: counted cells and elements retire their own edges. The producer audit found all normal compiler, materialization, clone, and PadWalker paths pair captures with explicit owner slots; legacy count calls remain only in compatibility APIs and PadWalker migration fallbacks. `captureCount` still mirrors explicit and legacy owner identities. End-to-end exception, nested-CODE, transfer, cloning, and async cancellation coverage remains part of final qualification. |
| Final destruction and resurrection (`DestroyDispatch`) | Current durable Perl owner slots, active CODE, and actual internal cycle edges | `processRescuedObjects` now retains objects with current owner identities instead of treating a count of one as a rescue pin. A real weak-slot rescue redispatches `DESTROY` after its final owner leaves; marker-only rescue still completes once. CODE cycle retention uses verified counted owner slots instead of a graph walk. The rescue registry and remaining producer paths still need audit. `DestroyOwnerSlotResurrectionTest` plus the new weak aggregate regression cover current durable slots without root walks. |

This is an inventory, not evidence that these compatibility paths are safe to
delete. For each row, the next migration must fix acquisition, replacement,
and cleanup at the producer before removing its fallback. There are ten named
areas in the table; the deferred live/global CODE capture walk has since been
removed, leaving nine areas with producer or lifecycle questions. Several share
the same owner transitions and can be handled in a small number of batches.
This is an audit inventory, not a claim that nine independent live fallbacks
have been reproduced; recheck each row against current call sites as it is
migrated.

### Package aggregate root lookup uses owner identities (2026-10-31)

`GlobalVariable.isPackageRootedAggregate` now consults active
`PACKAGE_AGGREGATE_SLOT` identities for package array and hash roots, avoiding
map-value scans during lifecycle checks. Explicit root markers still cover
installation-time roots. `PackageRootOwnerIdentityLookupTest` verifies that the
identity alone proves liveness and that releasing the final slot removes that
proof. The immutable source `dd255e847` passes the full repository gate and the
HTML-Tree suite on both backends; the gate logs are recorded in the current
checkpoint above. `nice -n 19 make check-links` also passes (821 links checked).
This removes the package-table membership scan only; deferred
release, first-weaken, closure, and destruction graph fallbacks remain under
audit.

### Single-value array replacement transfers the counted cell (2026-10-31)

The deferred-root snapshot tests construct their legacy case by acquiring a
counted scalar owner before inserting it directly into an aggregate. The
ordinary `push`, list assignment, and hash-store producers establish container
storage before `incrementRefCountForContainerStore`, so they already classify
the counted owner correctly. `RuntimeArray.set(RuntimeScalar)` was an omitted
producer: it stored the same cell without acquiring/reclassifying its owner,
and left `elementsOwned` false. It now acquires the element owner after the
store, preserves any existing owner identity, and marks the array as owning the
cell. `RuntimeArraySingleValueOwnerTransferTest` forces an undercount and
requires current-slot repair with zero root queries; final cell overwrite must
clear the weak observer. The full `nice -n 19 make` gate passed in 7m35s,
including the regression (one test, zero failures); log:
`/private/tmp/ownership-array-single-value-owner-make-20261031.log`.

### Cloned hash replacement releases the displaced slot (2026-10-31)

`RuntimeHash.putClonedElement` previously wrote and acquired the replacement
directly, leaving any old value at the same key counted after it left the
hash. It now uses `storeOwnedElement`, which applies the same independent-slot
copy rules, installs the new owner before queuing release of the displaced
slot, and balances host-side graph construction. The regression
`RuntimeHashClonedElementReplacementOwnerTest` verifies that replacing the
final hash owner clears its weak observer and that the new slot remains alive
until removal. The full gate passed on source `c9572eb57` in 7m6s; log:
`/private/tmp/ownership-hash-clone-replacement-make-committed-20261031.log`.

### Array removal preserves the slot identity (2026-11-01)

When a localized array slot held a blessed reference, unwind removed the scalar
before checking whether the array owned it. The stale `elementsOwned` summary
was false, so the shared helper skipped the still active counted slot. Ordinary
`pop`, `shift`, and middle `delete` had the same ordering. Each path now
validates the current `ARRAY_ELEMENT` identity before mutation and transfers
that proof into deferred release. Borrowed `@_` aliases remain excluded because
they have no counted element identity. The regression verifies returned values
stay alive until dropped, weak observers clear after the final owner leaves,
and `DESTROY` runs once for each value. The unfixed parent fails 4/12; system
Perl and both PerlOnJava backends pass 12/12. Full validation passes on
`a0f50e758`. During this change, the existing
`issue_1646_blessed_io_handle_keeps_descriptor.t` exposed an argument-alias
misclassification: an active `@_` cell had an `ARRAY_ELEMENT` label but was
borrowed by the argument frame. The owner proof now excludes the current
argument alias, and both the focused and full gates pass.

### Persistent CODE state bindings use owner identities (2026-11-01)

Source commit `1c893a2bc` adds `CODE_STATE` slots for persistent state scalar
cells, arrays, hashes, and refalias cells. `StateVariable` acquires the slot
when it installs a per-CV binding; replacement acquires the new edge before
retiring the old one. Final state cleanup detaches maps before releasing their
owners, and definition adoption plus `RuntimeGraphCloner` rebind or reconstruct
the identities for the resulting CV. `MortalList.processDeferredBase` no longer
walks live/global CODE capture graphs for unblessed weak referents.

`CodeStateAggregateOwnerSlotTest` proves deferred cleanup uses the direct state
owner without root queries and covers scalar, array, hash, and alias slots.
`RuntimeCodeStateGraphClonerOwnerTest` verifies cloned state storage receives
independent slots. `closure_state_owner_release.t` passes 10/10 on system Perl
and both backends with sweeps disabled; nine additional closure/state fixtures
also pass on all three engines. Parent and focused candidate evidence is listed
in the current checkpoint above. The full no-sweeps make passed on candidate
`b8ac7b1b9` in 6m20; log:
`/private/tmp/ownership-code-state-immutable-no-sweeps-make-b8ac7b1b9.log`.

This retires one deferred reachability path. Blessed root snapshots, the raw
count bridge, and corrective-sweep scheduling still have unmigrated producers.

### Closure aggregate release preserves current owner counts (2026-11-02)

Every production aggregate-capture builder found in `SubroutineParser`,
`OpcodeHandlerExtended`, `RuntimeCode`, `RuntimeGraphCloner`, and `Internals`
records a `CLOSURE_PAD` owner slot. `RuntimeBase.retainClosureCapture()` has no
main-source caller. Its release path nevertheless queued a raw decrement when
the pad scope ended and a positive count remained, without identifying which
scalar or element had acquired that count. `LegacyClosureAggregateReleaseOwnerTest`
keeps a counted scalar owner live while a legacy capture exits and requires
closure cleanup to leave that count untouched. The test failed on the
unmodified runtime: the release processed one deferred base.

The closure release now cleans the pad but leaves positive counts to their
acquiring slots. The focused shard passes on `ab4569170` in 6m15s, and the
full no-sweeps `nice -n 19 timeout 3600 make` passes in 7m28s. Parent and
candidate logs are listed in the current checkpoint above. This removes one
raw deferred-release producer; it does not complete the broader closure,
first-weaken, or lifecycle-fallback audit.

### Package-rooted blessed parents retain nested owners (2026-11-03)

`BlessedPackageOwnerRecursiveReleaseTest` models a blessed hash with an
untracked package-root owner and one counted outer-hash owner. The parent
contained a child observed through a weak scalar. On `dafb62d40`, clearing the
outer hash both prewalked the child's field and destroyed the parent: recursive
cleanup used `refCount == 1` as its last-owner proof, while deferred aggregate
cleanup checked for an untracked `REFERENCE` scalar and missed the parent's
`HASHREFERENCE` owner. The regression failed on that parent in the focused run
and was the sole failure in the 3,398-test serial unit gate (six skipped).

The fix makes recursive field cleanup depend on the absence of current Perl
owner identities. Deferred finalization recognizes any untracked aggregate
reference type for blessed parents without direct weak observers; when direct
weak observers exist, their package-root proof still uses the root snapshot.
The regression verifies the child remains live while the package edge owns the
parent, then that removing the last package edge destroys the parent and clears
the child weak observer. The focused group passes all 19 tests, including the
existing snapshot-cost cases. The first full gate exposed four failures from
the broader owner shortcut. After narrowing it, the full no-sweeps gate passes
on immutable checkpoint `6c285e880`: 3,921 tests, 7 skipped, zero failures or
errors in 7m22. Log: `/private/tmp/issue-1649-fullmake-no-sweeps-6c285e880.log`.

### Counted scalar-owner queries use the slot ledger (2026-11-03)

`e93b73a16` removes `RuntimeBase.activeOwners`, the duplicate strong set of
scalar wrappers. Counted owner snapshots and counts now derive from each
referent's active counted `PerlOwnerSlot` identities, checking that the slot's
scalar still owns the same referent. `reachableOwnerCount` applies its existing
root proof to those same slot identities, including owners installed before
first weaken. `WeakRefRegistry` and `DestroyDispatch` no longer activate a
separate registry; `activateOwnerTracking()` remains a Java compatibility
no-op because existing tests call it.

`0ff00785a` also updates reachability diagnostics to report counted slots
instead of the removed set. `CountedOwnerSlotLedgerTest` covers two scalar
owners installed before any weaken call. The complete no-sweeps `make` gate
passes on immutable `0ff00785a`: 3,941 tests, 7 skipped, zero failures/errors
in 7m27. Log:
`/private/tmp/issue-1649-owner-ledger-no-sweeps-0ff00785a.log`.
The ordinary `restoreReachableOwnerCount` root fallback remains; its producers
and final use cases still require audit before removal.

### Retained mechanism rationale

1. Keep the `restoreReachableOwnerCount` path isolated to its legacy unpaired
   decrement hook. The call-site audit finds no production caller of
   `MortalList.deferDecrement(RuntimeBase)`. Every production
   `deferDecrementIfTracked` caller passes a `RuntimeScalar`, whose active owner
   slot is captured in a `PendingOwnerRelease`; `RuntimeCode.releaseCaptures`
   reaches that same scalar-aware path after retiring its capture slot. Thus
   ordinary production cleanup does not enter `legacyUnpairedDecrement`. The
   only production call to `ReachabilityWalker.isScalarReachable` is through
   `restoreReachableOwnerCount`; the remaining root-based recovery branches in
   `processDeferredBase` are likewise guarded by that legacy unpaired path.
   `finalizeClearedAggregateOwnerAfterScopeExit` may issue a root query only
   when query statistics are enabled, and ignores its result. `sweepWeakRefs`
   overloads are compatibility no-ops. `sweepReleasedWeakReferents` still has
   targeted root/cycle traversal and fixed-point weak-cleanup logic for direct
   Java compatibility/test calls; a source call-site audit finds no production
   callers. Keep that distinction explicit and retire the implementation only
   after its direct callers and tests have a replacement contract. Ordinary
   runtime cleanup no longer depends on graph sweeps.
2. Retain verified owner-slot count alignment while its producer gaps are
   audited. This checks current scalar, aggregate, invocant, and native owner
   identities directly; removing it previously failed lifecycle and Perl-level
   owner tests that explicitly construct undercount/sentinel states. No normal
   producer mismatch was found in this pass. It is not reachability-based
   recovery.
3. Batch any demonstrated producer/release fixes with focused coverage before
   rebuilding. The current Phase 2 acceptance and PR status are in the
   checkpoint at the top of this document.
