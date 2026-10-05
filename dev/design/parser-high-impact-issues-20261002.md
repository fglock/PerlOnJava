# Parser compatibility issue batch (October 2026)

## Goal

Implement the confirmed fixes among the selected high impact reports labeled
`area:parser`, and keep the unresolved AnyEvent timing report open:

- [#1470](https://github.com/fglock/PerlOnJava/issues/1470) — AnyEvent::Tools compilation and timer behavior.
- [#1619](https://github.com/fglock/PerlOnJava/issues/1619) — HTML::Tree incremental split parsing.
- [#1622](https://github.com/fglock/PerlOnJava/issues/1622) — ascending `use VERSION` declarations.
- [#1615](https://github.com/fglock/PerlOnJava/issues/1615) — valid `\@;@` prototype warning.
- [#1166](https://github.com/fglock/PerlOnJava/issues/1166) — fully qualified indirect constructors.

The current `master` behavior for [#1466](https://github.com/fglock/PerlOnJava/issues/1466)
and [#1476](https://github.com/fglock/PerlOnJava/issues/1476) was confirmed on
system Perl and both PerlOnJava backends. Regression tests for those fixes are
retained in PR #1623, and the issues were closed with that evidence.

## Progress tracking

### Current status: all five issue gates pass on source candidate `d753a616e`; PR #1623 head `c7349c917` has pending CI/review and user acceptance

### Completed

- [x] Confirm the working tree was clean and create the feature branch.
- [x] Open draft [PR #1623](https://github.com/fglock/PerlOnJava/pull/1623).
- [x] Close #1466 and #1476 after verifying their reproductions on system Perl,
  JVM, and interpreter backends; add permanent regression coverage.
- [x] Capture unfixed-parent failures and add system-Perl-validated tests for
  ascending versions, prototype diagnostics, qualified constructor arguments,
  and incremental HTML entity/whitespace boundaries.
- [x] Add a system-Perl-validated compile-only regression for the fully
  qualified indirect constructor followed by a method call (issue #1166 Form A).
- [x] Implement source changes for #1615, #1619, #1622, and #1166.
- [x] Preserve the `HTML::Entities::decode` alias after `HTML::Parser` XS
  initialization, covering the interpreter-only TreeBuilder lookup failure.
- [x] Confirm the compile compatibility change for #1470 already landed in
  merged PR #1598; continue tracking its unresolved clock and dispatch behavior
  in this parser issue batch.
- [x] Run `nice -n 19 make` successfully after the complete change set and
  validate the new tests with system Perl and both PerlOnJava backends.
- [x] Pass documentation link checks and retain the diagnostic evidence for
  the remaining AnyEvent::Tools failure.
- [x] Trace the remaining latency to a synchronous reachability sweep requested
  during `MyVarCleanupStack.noteVarLeftScope()` while AnyEvent is returning a
  callback frame. Confirm that the existing targeted sweep still performs the
  global root walk and does not meet the timing assertion; discard the
  behavior-changing scope-exit experiment after it failed callback-lifetime
  regression tests.
- [x] Capture a 1 ms JFR profile over the failing mutex assertion. It shows two
  full root walks between the first and second reader callback timestamps.
  The same patched AnyEvent test passes on system Perl (about 0.02 ms between
  callbacks); PerlOnJava measures roughly 2.5–2.9 ms, while disabling automatic
  sweeps reduces that interval to about 0.5 ms. Reject deferral across the whole
  reader queue because it could leave stale weak references visible to later
  callbacks; cleanup must remain complete before the next callback.
- [x] Reject per-target root queries on the callback path: a live test-worker
  thread dump showed repeated reflective traversal of global code captures
  from `isReachableFromRoots`, making the full gate run pathologically slowly.
  Also reject the active-owner shortcut: the full gate failed
  `weak_active_coderef_dispatch.t` and `weak_localized_cache_lifetime.t`, so
  that bookkeeping cannot safely stand in for complete Perl reachability.
- [x] Revert the follow-up targeted-queue and owner-prefilter experiment after
  it caused five lifecycle and async unit failures. The restored candidate
  passes `timeout 3600 nice -n 19 make` (6m47s) and `make check-links`.
- [x] Re-run the patched AnyEvent::Tools `t/02_rw_mutex.t` against that built
  candidate: seven of eight assertions pass; assertion 4 still misses its 1 ms
  callback threshold. The dispatch issue remains unresolved.
- [x] Profile the callback path again after removing the duplicate lexical
  scalar-registry scan, iterating runtime root maps directly, and skipping a
  redundant per-owner reachability query when the complete immediate sweep is
  guaranteed at the same boundary. The full `nice -n 19 make` gate passes on
  this candidate, but the AnyEvent timing assertion still fails; an unprofiled
  diagnostic measured an 8.2 ms callback gap on this busy host. JFR still shows
  the complete root walk at the statement boundary, so these changes are safe
  cost reductions, not a complete dispatch fix.
- [x] Test a versioned array snapshot of global CODE slots and capacity sizing
  from that root count. Focused weak-reference tests pass, but AnyEvent still
  misses its 1 ms threshold (4.0–5.6 ms unprofiled in these runs). JFR did not
  show the cached slot iteration as the dominant callback cost; the sampled
  identity-map resizes came from parser source indexing. Revert both
  micro-optimizations and keep the complete weak sweep behavior unchanged.
- [x] Cache installed global CODE identities and the subset with traversable
  capture/state edges, and skip BFS expansion for terminal CODE roots. The full
  `nice -n 19 make` gate passes on this candidate (8m31s). An unprofiled
  AnyEvent mutex diagnostic measured 1.742 ms between synchronous reader
  callbacks, so the timing acceptance still fails. Retain this as a measured
  cost reduction only; the root walk remains on the callback path.
- [x] Re-read the live #1470 acceptance criteria and isolate its remaining
  failure: `AnyEvent::Tools/t/02_rw_mutex.t` compares two synchronous reader
  callbacks with a 1 ms wall-clock threshold. The first callback creates a
  timer and returns; its lexical cleanup can request a global weak-reference
  sweep before the second reader callback. This is callback-return cleanup
  cost, not event-loop timer dispatch. Keep the separate `AnyEvent::Loop` clock
  patch for timer correctness, but do not treat it as the dispatch-latency fix.
- [x] Rebase the parser and dispatch work onto `origin/master` at `c66fc3b`.
  Resolve the `RuntimeGraphCloner` conflict by retaining both cloned fields.
  The restored candidate passes the full `nice -n 19 make` gate in 6m05s.
- [x] Re-run the mutex timing diagnostic on the rebased build: seven of eight
  assertions pass, but assertion 4 still misses the 1 ms bound (1.364 ms).
- [x] Verify the unchanged upstream `AnyEvent::Tools/t/02_rw_mutex.t` with
  standard Perl: all 8 assertions pass. Default PerlOnJava still fails only
  assertion 4, which compares two reader start times against a 1 ms threshold.
  With `JPERL_NO_AUTO_GC=1`, PerlOnJava passes all 8 assertions; this diagnostic
  suppresses automatic weak cleanup and is not an acceptable fix. Keep the test
  unchanged and preserve automatic lifecycle cleanup in the dispatch fix.
- [x] Measure the unchanged test with structural work counters on the restored
  source: a fresh diagnostic of the unchanged file performs 10 reachability
  walks over 58,746 nodes and 44,016 container slots between assertion 3 and
  evaluation of assertion 4 (including the timer wait), then fails only
  assertion 4. These are deterministic traversal-work counts, not retired
  hardware instructions or a timing estimate. A targeted scope-exit cleanup
  experiment was reverted after the full unit gate exposed five weak-lifetime
  and async regressions (`weak_active_coderef_dispatch.t`,
  `weak_localized_cache_lifetime.t`,
  `returned_owned_scalar_arg_chain_weak.t`, `sub_quote_qsub_metadata.t`, and
  `custom_warning_async_suspend_state.t`). Preserve the current full cleanup
  semantics while pursuing the owner-ledger approach.
- [x] Trace the repeated AnyEvent scope-exit requests with existing work
  counters. The unresolved callback interval includes `RuntimeArray` targets
  whose selective `refCount` is 2 while `activeOwners` is empty. A diagnostic
  registry scan sees 4–13 nonweak scalar cells pointing to these arrays across
  repeated requests, all with `refCountOwned=false`; optional registration
  stacks include AnyEvent timer assignments and captured `$t` cells. These
  cells are not authoritative owner tokens, and the two counted references
  have not been mapped to their exact slots and paired releases. Keep the full
  sweep until that audit is complete.
- [x] Trace the repeated arrays' initial counted stores and test ledger
  activation after weak tracking, at first Perl reference creation, and at the
  two acquire paths. A full `nice -n 19 make` passed for each candidate, but
  unchanged `t/02_rw_mutex.t` remained 7/8 and the diagnostic AnyEvent run
  remained at 80 full walks. All three candidates were reverted. The
  transition trace attributes the initial increments to a
  scalar store in generated AnyEvent loop code and `RuntimeArray.push` from
  that loop; `WeakRefRegistry.weaken` then removes a scalar count. The reported
  generated source line numbers exceed the installed module's physical source
  lines. At repeated scope exits, scalar registry entries include `$timer`,
  `$self`, and captured `$t`, but each has `refCountOwned=false`; the active
  owner set is empty. The container-slot increment has no paired release
  identity in the trace yet. These findings explain why early ledger
  activation did not skip the walk; they do not justify changing cleanup.
- [x] Test per-CV updates to the cached CODE-capture root index. The full gate
  failed `refcount/nested_weak_sweep_temporaries.t` on both attempts because
  closure metadata was lost across a nested weak sweep. Revert this cache
  mutation approach; preserve the passing root walk until ownership is
  authoritative.
- [x] Test a positive-only live-scalar owner check before the broad root walk.
  The full gate passed, but three unprofiled timing runs still missed the 1 ms
  limit (2.70–4.13 ms). JFR did not sample the shortcut in the callback window;
  it showed a full `sweepWeakRefs()` followed by another CODE-capture query in
  deferred cleanup. Revert the extra scan and target that repeated query.
- [x] Check hardware-counter support for the callback gate. The default
  Instruments CPU Bottlenecks trace on this Apple Silicon host exposes cycles
  and bottleneck categories, but no retired-instruction total. The profile
  evidence indicates two full reachability walks between the callbacks; use
  deterministic work counts (walks, nodes, and slots visited) to compare future
  candidates, with wall time retained as context only. Remove temporary JFR and
  Instruments traces after extracting findings.
- [x] Keep a system-Perl-passing regression for state-held weak metadata alive
  while its named sub is installed and cleared after `undef *name`; do not alter
  the test. Fix glob removal to request a complete weak sweep when the displaced
  CODE has state or capture edges. The test passes on system Perl and both
  PerlOnJava backends.
- [x] On deferred weak-target cleanup, carry unblessed capture candidates to the
  next safe complete sweep instead of rescanning live and global CODE captures
  per target. The full `nice -n 19 make` gate passes. In the mutex diagnostic's
  callback window, the prior candidate scanned 1,513 CODE roots across four
  target queries (213 + 338 + 816 + 71 + 75); this candidate queues those four
  targets and performs no target-specific capture query. Two full reachability
  walks remain, each visiting about 5,850 graph nodes and 4,400 container slots.
  The seven-file AnyEvent::Tools suite passes 102/103 assertions on both
  backends; the only failure is its 1 ms callback-gap assertion. Use work counts
  as the acceptance measure per the user instruction, and keep #1470 open while
  the duplicate full walks remain.
- [x] Reorder deferred cleanup to query live/global CODE captures before
  queueing another deferred candidate. The full low-priority `make` gate passes
  in 5m51s. On the unchanged upstream mutex test, the exact interval between
  reader-start time calls still contains one complete auto-sweep: 5,874 graph
  nodes and 4,403 container slots, clearing zero referents. The live CODE-capture
  query immediately before it returns true, so this reorder avoids a redundant
  per-target query but does not discharge the already pending sweep. PerlOnJava
  still fails only assertion 4; system Perl passes all 8. Keep the upstream test
  unchanged and #1470 open.
- [x] Check whether active call arguments can cheaply preserve a scope-exiting
  weak target. The argument-root query returned false for the candidate arrays;
  the callback path still performed full walks of about 5,858 nodes and 4,391
  container slots. The full `nice -n 19 make` gate passed in 6m07s, but this
  added work without avoiding the sweep, so remove the query. The unchanged
  upstream test still passes 8/8 on system Perl and fails only assertion 4 on
  PerlOnJava. Keep the test unchanged.
- [x] Reject target-only early-exit sweeps for scope-exit cleanup. They reduced
  graph traversal when all selected targets were found live, but skipped weak
  cleanup required by `weak_active_coderef_dispatch.t`,
  `weak_localized_cache_lifetime.t`, and `sub_quote_qsub_metadata.t`. Restore the
  complete sweep behavior; any further reduction must preserve the cleanup
  obligations of the full weak-reference registry.
- [x] Reject targeted-only scope-exit and deferred-capture sweep substitutions.
  The full `nice -n 19 make` gate exposed failures in
  `weak_active_coderef_dispatch.t`, `weak_localized_cache_lifetime.t`,
  `returned_owned_scalar_arg_chain_weak.t`, `sub_quote_qsub_metadata.t`, and
  `custom_warning_async_suspend_state.t`; restore the immediate complete sweep.
  The restored candidate passes `nice -n 19 make` (5m23s). The unchanged mutex
  test remains 7/8 on PerlOnJava and passes 8/8 on system Perl. Deterministic
  work between assertions 3 and 4 is 3 complete walks / 17,607 nodes / 13,195
  container slots, each from an immediate `MyVarCleanupStack` scope-exit sweep.
  The project-owned `weak_callback_scope_exit_dispatch.t` remains 5/5 on system
  Perl and both backends. Keep #1470 open until the unchanged seven-file suite
  passes on both backends.
- [x] Revalidate the diagnostic-instrumented candidate on 2026-10-04. The
  exact source passes `nice -n 19 make` in 6m07s. Unchanged
  `AnyEvent-Tools/t/02_rw_mutex.t` passes 8/8 on standard Perl and remains 7/8
  on both PerlOnJava backends; assertion 4 is the only failure. Do not change
  the upstream test or run the full AnyEvent suite until this focused case
  passes on both backends; keep #1470 open.
- [x] Cross-check successive full-sweep liveness snapshots in debug mode. At
  the final four-candidate pass, three weak candidates keep their liveness and
  one `RuntimeArray` changes from live to dead. Sweep decisions still use the
  fresh walk, so this is diagnostic evidence only. The full gate passes on the
  exact source in 9m15s. With diagnostics disabled, system Perl passes the
  unchanged test 8/8; JVM and interpreter remain 7/8.
- [x] Trace the changed candidate's prior path through a captured array and
  direct owner scalar. The array remains live but changes from four slots to
  zero; its scalar becomes unrooted and changes `refCountOwned` from true to
  false while still pointing at the target. Queue that owner scalar with the
  deferred `RuntimeArray.shift()`/`pop()` decrement. The new focused regression
  passes 3/3 on system Perl; the later exact-source full gate and backend
  results are recorded below.
- [x] Attempt the full gate on the path-node diagnostic source. Compilation and
  packaging passed, but the 20-minute `make` timeout stopped the unit shards
  during concurrent unrelated CPAN workloads on the shared host. Treat this as
  inconclusive; no workers from this make remained, and the source changed
  afterward to carry array-slot owner identity.
- [x] Run the issue-specific upstream gates on the rebased checkpoint:
  `HTML::Tree` 5.07 `t/split.t` passes 444/444 under standard Perl and both
  PerlOnJava backends; `License::SPDX` 0.07 passes 35 tests, `Argv` 1.28 passes
  11, and `Weasel::Driver::Selenium2` 0.15 passes its 1-test load suite under
  both PerlOnJava backends. `LWPx::TimedHTTP` 1.8 passes its 7-test jcpan suite;
  for the issue's 1.4 release, local-server tests skip because PerlOnJava does
  not implement `fork`, and the HTTPS test is marked TODO.
- [x] Fix the oversized closure deparse-source constant by keeping long source
  text in per-runtime metadata keyed by generated class. The new Java
  integration regression passes in the full `nice -n 19 make` gate, and its
  equivalent large-source closure passes standard Perl. The fallback-disabled
  AnyEvent test no longer reports `UTF8 string too large`.
- [x] Compare deterministic reachability work before and after the compiler
  fix. Both focused runs recorded 80 walks; the parent had 468,333 nodes and
  349,462 container slots, while the candidate had 468,581 nodes and 349,784
  slots. This aggregate includes module loading and confirms the source fix did
  not reduce callback work. The seven-file AnyEvent::Tools suite remains
  102/103, failing only unchanged `t/02_rw_mutex.t` assertion 4. Other method
  size and ASM frame-computation fallbacks remain.
- [x] Fix interpreter lexical undef assignment to release weakly observed tree
  descendants through the existing scalar cell. The project-owned
  `weak_blessed_tree_descendant_cleanup.t` passes on system Perl and both
  PerlOnJava backends; its unfixed-parent interpreter run failed the final
  assertion. The unchanged HTML::Tree 5.07 `t/refloop.t` passes 8/8 on all
  three runtimes, and the full distribution passes 989 tests across 23 files
  on both PerlOnJava backends.
- [x] Trace the shifted array slot as the owner path that becomes unreachable
  while its referent remains in the returned scalar. Queue the source scalar
  with the deferred `shift()`/`pop()` decrement and register initialized
  lexical arrays as interpreter roots. The new focused regression passes 3/3
  on system Perl, JVM, and interpreter; exact `nice -n 19 make` passes in
  5m26s. The full `make test-interpreter` inventory is not green (2,080/2,129
  files pass; 35 fail, 10 error, 4 incomplete), so classify those failures
  against standard Perl and JVM before attributing any to this change.
- [x] Re-run unchanged `AnyEvent-Tools/t/02_rw_mutex.t` from its distribution
  root with local `AnyEvent` dependencies. Standard Perl passes 8/8; JVM and
  forced interpreter remain 7/8, failing only assertion 4. Keep the upstream
  test unchanged, keep #1470 open, and defer the seven-file gate until this
  focused acceptance case passes on both PerlOnJava backends.
- [x] Count work on the exact owner-queue candidate with debug counters. Between
  assertions 3 and 4 it performs 3 full walks / 17,577 graph nodes / 13,180
  container slots. The last pass sees 4 candidates, with 3 stable and one
  `RuntimeArray` changing live-to-dead; its prior root path includes a captured
  parent array whose slot count drops to zero and an owner scalar whose
  `refCountOwned` marker clears. Diagnostics are not timing evidence. The queue
  change has not reduced these required full walks, so retain fresh-walk
  cleanup and continue the owner-edge audit.
- [x] Update the open PR #1623 description with the current issue scope, the
  work-count status for #1470, and the upstream validation results.
- [x] On the final batched source, `timeout 1800 nice -n 19 make` passes in
  4m59s; all three label/pad regressions pass 66/66 on standard Perl, JVM, and
  interpreter. The Java label-registration regression passes in that build.
- [x] Revalidate unchanged `AnyEvent-Tools` 0.12 acceptance on the final batch:
  the mutex file passes 8/8, and all seven files / 103 assertions pass on
  standard Perl, JVM, and interpreter. This clears the prior JVM assertion-4
  acceptance gap for the current source candidate.
- [x] Rebase the candidate onto current `origin/master` at `d753a616e`; only
  CPAN report data had changed upstream. The exact rebased commit passes the
  full unit gate in 6m26s and the five issue acceptance sets.
- [x] HTML::Tree 5.07 passes on system Perl (969 tests; LeakTrace-only file
  skipped), JVM, and interpreter (989 assertions each, one existing TODO
  failure). `t/split.t` passes 444/444 and `t/refloop.t` 8/8.
- [x] License::SPDX 0.07 passes 35/35 across 10 files on JVM via `jcpan -t`
  and on interpreter with the same declared dependencies.
- [x] Prototype, ascending version, both qualified constructor forms, and
  retained #1466/#1476 regressions pass 9/9 on standard Perl and both backends.
- [ ] Update PR #1623 from head `52a7fc6349d08dd9a6574bd8d19ff5700c7d7896`
  to the rebased candidate; verify remote contents, required CI, review, then
  complete the five-issue closure audit.

### Exact reader-callback work interval (2026-10-04)

Ran the unchanged `t/02_rw_mutex.t` twice with the existing
`JPERL_DISPATCH_WORK_MARKERS` and `JPERL_REACHABILITY_WORK_DEBUG` hooks. The
`Time::HiRes::time` markers identify the first reader start callback, then the
second reader start callback. In both runs the interval contains one complete
root walk: 5,865 graph nodes and 4,395 container slots, plus three bounded
strong-cycle checks. A single scope-exit request on a `RuntimeArray` with
`refCount=1` and no active trace owners triggers the sweep. It examines two
weak candidates and clears none. Candidate path diagnostics show one walker-live
referent through `RuntimeCode -> captured RuntimeArray -> owning RuntimeScalar
-> weak RuntimeArray`; the scope-exit target passes a strong-cycle check. Both
candidates were stable at the preceding full sweep. The unchanged test fails
only assertion 4. The debug-only path trace passed the full `make` gate in
5m12s after its diagnostic-off guard was corrected.

This is the exact callback timestamp interval, unlike the earlier 3-walk
aggregate between TAP assertions 3 and 4 (17,577 nodes / 13,180 slots), which
includes the wait and other test activity. These are deterministic work units,
not retired hardware instruction totals or timing estimates. The unchanged
positive witness survives an unrelated slot append to its captured array.

A debug-only root-seed trace identifies that `RuntimeCode` as rooted through
`globalCodeRefs`. In the exact interval from the first reader callback marker
(`anon1948.apply`, `t/02_rw_mutex.t:691`) to the second (`anon1950.apply`,
`t/02_rw_mutex.t:796`), the unchanged test has one scope-exit-triggered full
walk: 5,868 nodes / 4,398 slots, three cycle checks, two stable weak candidates,
and zero clears. The scope-exit target is itself cycle-protected. The owner
scalar and captured-array identities are unchanged across adjacent sweeps,
although that array grows from three to four slots. The same run's later sweep,
outside the callback interval, observes a prior owner scalar become unrooted and
its referent change live-to-dead. A positive cache therefore must verify the
actual global CODE root, the code-to-capture edge, and the exact strong array
slot on each relevant mutation; the root identity by itself is insufficient.
This instrumented JVM run still fails only assertion 4. Its exact source passed
`timeout 1800 nice -n 19 make` in 5m48s before the focused run.

### Matching-parent interpreter comparison (2026-10-04)

The candidate is `977864670`; its comparison parent is `75681d00d`. The
candidate full inventory covered 2,129 files: 2,080 pass, 35 fail, 10 error,
4 incomplete, and no timeout. The interpreter target exits zero even with
nonpassing files, so these counts came from its JSON result and TAP summary.

Ran the exact 49 nonpassing candidate files on the parent JAR in an isolated
worktree after `timeout 1800 nice -n 19 make` passed there. All 49 outcomes
match per file: status, failed assertion count, planned and actual TAP counts,
and errors. The parent subset totals are 35 fail, 10 error, 4 incomplete, and
no timeout. The per-file comparison is preserved in
[the interpreter baseline report](parser-interpreter-parent-comparison-20261004.md).
No new interpreter failure was found in the candidate.

Copied `shifted_array_weak_owner_release.t` into `/private/tmp` and ran it with
the parent interpreter: assertion 1 fails because the shifted lexical no longer
retains the weakly observed referent; assertions 2 and 3 pass. Candidate source
passes all three assertions on system Perl, JVM, and interpreter. The focused
regression therefore has direct parent-failure evidence.

These 49 interpreter gaps are present on the matching parent. Require the
repository's default full unit gate and all affected focused interpreter tests;
repeat this failure comparison if later runtime changes add any nonpassing
interpreter files. Do not label this inventory green.

### Next steps

Follow the [five-issue completion plan](parser-high-impact-completion-plan.md)
for the authoritative sequence, acceptance gates, and build discipline. The
rebased source candidate `d753a616e` passes the full unit gate and all five
issue acceptance sets. PR #1623 is at `c7349c917`, a documentation-only update
after that source candidate; its Linux and Windows `make ci` jobs are active.

1. Start user acceptance against PR #1623 using the realistic host, without
   changing the tested source. Exercise the five issue workflows and record
   semantic results; use deterministic work counts for dispatch comparisons
   because wall time varies with host load.
2. Wait for CI and PR review. If UAT or CI reveals a failure, compare it to the
   matching unfixed parent, add project-owned regression coverage, and batch
   necessary source changes before rebuilding.
3. Complete the interpreter regression audit and changelog decision. After
   merge, close only tickets confirmed fixed by the complete acceptance audit.

### Open questions

- Candidate `d753a616e` passes the unchanged mutex and seven-file AnyEvent::Tools
  suite on all three runtimes. Repeat on the final PR commit.
