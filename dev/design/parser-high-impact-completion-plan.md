# Completion plan for the five high impact parser issues

## Objective and completion criteria

Fix and verify all five selected `area:parser` issues, preserve existing tests,
and deliver reviewable changes through PR #1623. Follow this sequence until the
complete acceptance matrix is green. An implemented fix, reduced work count, or green default build
alone does not complete the goal.

All unit tests must pass. A standard-Perl-passing test must pass in PerlOnJava;
fix the implementation and keep the test unchanged. Every externally observed
failure needs permanent project-owned regression coverage, standard Perl
validation where applicable, unfixed-parent evidence, and success on both
PerlOnJava backends.

## Current evidence — reviewed 2026-10-05

### Current blead UAT follow-up — 2026-10-05

PerlOnJava follows current blead semantics, including when the project is
advertised as the latest stable Perl. The latest source pull is blead
`c4d03d9396` (native Perl v5.45.4); its `t/lib/croak.t`, `t/op/caller.t`, and
`t/re/anyof.t` all pass on the standard Perl oracle. Keep imported core tests
unchanged.

The current full PerlOnJava UAT candidate before this follow-up completed 572
of 575 files with three real failures: two `croak.t` assertions for repeated
`use VERSION`, one `caller.t` line-number assertion, and one `re/anyof.t`
compiled-node assertion. The October 3 comparator also reports assertion-count
changes across regex test files; its three actual failed files are the release
gate failures to fix, not expected semantic differences.

The follow-up batch now:

- Rejects differing in-scope `use VERSION` declarations with the blead
  diagnostics, while retaining blead's exception for a preceding version
  below 5.10 and repeated declarations of the same version.
- Reports the call statement's source line when an anonymous subroutine is a
  multiline argument, in both compiler paths. Project-owned caller-line tests
  whose old expectations fail on standard blead were updated; native 5.45.4
  passes those tests.
- Renders caseless negated singleton character classes with Perl's
  `NEXACTb` description, while preserving the expanded fold set for folded
  characters such as `a`.
- Removes the child stash entry when `RuntimeStash` deletes a namespace.
  The refreshed blead `Symbol.pm` provides `delete_package`; standard Perl
  passes the project regression for it.

The full no-options `dev/import-perl5/sync.pl` completed with 218 sources and
zero errors; its tracked imports are committed in `179e91ac1`. The candidate
`2f840f179` passes full `make`. A follow-up fixed JVM method-call line metadata
and purges both bare and `main::`-qualified stash entries;
`symbol_delete_package.t` passes on the JVM and interpreter.

The full refreshed blead UAT's TAP summary reported **575/575 files** and
680,708 passing assertions, with no `not ok` assertions. This result is not
accepted: strict comparison found nonzero subprocess exits in
`class/method.t`, `op/coreamp.t`, and `op/try.t`, despite their TAP output not
containing failed assertions. The comparator report is
`/private/tmp/parser-uat-20261005-followup-comparison.json`; the captured run is
`/Users/fglock/projects/PerlOnJava/logs/test_20261005_followup_2f840f179.log`.
The user's acceptance rule is zero UAT failures, including nonzero test-process
exits, test errors, timeouts, incomplete files, or failed TAP assertions.

The current fix batch adds project-owned coverage for lexical class method
calls, callable `CORE::bless`/`CORE::break`, and local `goto` within `finally`.
It lowers `->&` to call a package CV directly with the invocant when there is
no lexical method, preserving Perl's no-inheritance-dispatch behavior. All new
unit cases pass the standard Perl oracle. The previous exact-head `make`
passed before these latest emitter/runtime edits. Focused UAT results show
`class/method.t` exits 0; `coreamp.t` passes the `bless`
cases and reaches the next unsupported wrapper, `CORE::break`; and
`op/try.t` still fails in its JVM path while the interpreter passes. The
`CORE::break` wrapper and JVM emitter changes for local-finally gotos are in
progress and have not yet been rebuilt. No UAT result is accepted until the
full suite and strict subprocess-exit comparison report zero failures.

The Oct 3 comparison also reports ten fewer planned assertions in each of ten
regex fixtures after refreshing the blead corpus. Preserve the comparison
report, but do not treat assertion-count differences as substitutes for a
zero-failure current UAT run.

This section supersedes the earlier Perl 5.44 target and expected-failure
notes below. Those notes remain as historical evidence only.

### Resume handoff — 2026-10-05

#### UAT regression follow-up — 2026-10-05

The 13:00 candidate UAT exposed label, stash, closure weak-reference, and
symbolic glob regressions. The latest batched source is on
`wip/parser-uat-regressions-20261005-141631`, rebased onto master
`3893a1903`; implementation commit `5f88a41ac` has the runtime fixes. It adds
permanent project-owned coverage without changing any existing tests:

- `goto_conditional_label_shadowing.t` covers a legal later Unicode label;
  the fix clears stale construct-entry metadata when an unconditional target
  supersedes an earlier same-named conditional label. The unchanged construct-
  entry and nested-target unit tests pass.
- `anonymous_stash_unicode_undef.t` covers anonymous stash names after undef;
  `B.pm` no longer replaces an anonymous package label with a forward-reference
  package name.
- `symbolic_unicode_glob_lookup.t` covers non-vivifying symbolic `defined`
  and a basic pseudo-constant export. `pseudo_constant_export_after_glob_promotion.t`
  preserves the full promotion history from `uni/gv.t`; distinct stash
  assignments now copy cached read-only literal identities before recording
  pseudo-constant aliases.
- `weak_closure_capture_cycle.t` covers RT #22547's weak subscriber; scope
  exit again requests the reachability check even when selective owner records
  still show a semantic closure capture.

The Oct 5 full UAT against the Oct 3 baseline initially completed with 574/575
files passing and one assertion failure in `perl5_t/t/lib/croak.t`; no files
timed out or were incomplete. Its comparator shows one changed assertion in
`lib/croak.t` (344/344 to 343/344). The source suite is from the `perl5`
checkout at Perl 5.45.4 development, while PerlOnJava targets 5.44.1.

The user selected Perl 5.44 semantics. To establish the oracle, I built
standard Perl 5.44.0 from the local `perl5` tag in `/private/tmp` without
changing that source checkout or system Perl. On standard 5.44.0:
`use v5.12; use v5.20` emits the `deprecated::subsequent_use_version` warning
and succeeds; `use warnings FATAL => 'deprecated::subsequent_use_version'`
makes it fatal; and the existing legacy decimal sequence `use 5.006; use
v5.10.0` succeeds without a warning. The newer 5.45 test fixture expects the
warning to be fatal, which is scheduled for Perl 5.46. Running that fixture
under standard 5.44.0 reproduces the version-mismatch failures.

The parser now tracks legacy decimal syntax separately, preserves its 5.44
warning-free upgrade behavior, emits the 5.44 deprecation warning for other
eligible version changes, respects disabled and fatal warning categories, and
retains the fatal rules for versions >=5.39 and downgrades below v5.11. The
new `use_version_subsequent_warning.t` passes all eight assertions on standard
Perl 5.44.0 and fails three assertions on the unfixed PerlOnJava JVM and
interpreter. After the fix, `nice -n 19 timeout 1800 make` passes; the focused
`use_version_*` tests pass on both PerlOnJava backends. The full UAT and fresh
PR CI still need to rerun for this source.

The last CI run for old PR head `3a5804a54` passed on Ubuntu and Windows. Push
the validated 5.44 behavior, rerun the requested full UAT and compare with the
Oct 3 log, and refresh CI. Classify any remaining `croak.t` difference against
the verified 5.44 oracle; do not adopt the 5.46 fatal behavior in this
PerlOnJava 5.44 release. Close only tickets confirmed fixed after merge.

This handoff and Immediate next steps supersede older candidate status below.
The final-batch record below also supersedes the earlier three-failure and
AnyEvent assertion-4 status: the batched source passes the default unit gate,
the focused regressions, and all seven AnyEvent::Tools tests on both PerlOnJava
backends.
Earlier green builds did not validate the label registration change; the final
batch now does.

- Delivery: **PR #1623 for all five selected issues**, as confirmed by the
  user. Integrate the passing AnyEvent work into that PR; do not open a separate
  AnyEvent PR. Recheck its remote branch and contents before integration.
- Checkout: `wip/parser-label-resume-20261005-103230`, WIP snapshot
  `d894f915d`, preserving runtime changes, diagnostics, and new tests.
  Preflight backups are `/tmp/wip-{unstaged,staged}-20261005-103230.patch`
  and `/tmp/wip-status-20261005-103230.txt`. This is an investigative snapshot,
  not a passing publication candidate.
- Latest full gate: `timeout 1800 nice -n 19 make`, exit 2 after 5m10s;
  `/private/tmp/parser-duplicate-label-fix2-make.log`. Session `21817` has
  exited. XML reports show **three existing test failures**, correcting the
  earlier conversational count of two:
  `array_sparse_slot_alias.t` rejects entry into a foreach body;
  `goto_nested_label_precedence.t` assertion 3 reports a construct-entry error
  instead of `Can't find label UNREACHABLE`; and
  `lvref_conditional_target_alias.t` assertion 55 fails state refalias binding
  through a pad slot reached before its declaration. Inspect
  `build/test-results/testUnitShard1/` and `testUnitShard3/` before rebuilding.
- The new `EmitBlockLabelRegistrationTest` passes (one test, zero failures in
  `build/test-results/testUnitShard0/`). The reduced array-dereference early
  returns plus `LOCK_RMUTEX: return` case passes standard Perl syntax and
  execution. Before the label change, JVM compilation fell back after an ASM
  `Frame.merge` NPE; the diagnostic dump shows duplicate name checks and a
  branch to an undefined label. ASM validation reports `Undefined label used`.
- `ParseBlock` records statement labels in both `LabelNode` statements and
  `BlockNode.labels`. `EmitBlock` can allocate duplicate ASM targets;
  `EmitLabel` visits the current target, while the dispatcher can branch to
  both. The current deduplication attempt retains recursive statement-label
  collection and removes the reduced fallback, but fails the three existing
  tests. Registration scope, shadowing, unreachable targets, and pad entry
  still need a coherent repair.
- Keep production pristine-argument fallback metadata propagation in
  `EmitterMethodCreator.java` and its
  `PristineArgsInterpreterFallbackMetadataTest.java` coverage. Temporary ASM
  retry/stack printing and operation counters still need removal before the
  final gate. Source files and tests are preserved in the WIP commit above.
- [#1482](https://github.com/fglock/PerlOnJava/issues/1482) reports Tags 0.16
  `Test::NoWarnings` failures around non-local `last SKIP` under
  `no warnings 'exiting'`, sometimes with `Label not found`. Related comments
  add Code::Perl, Test2::Tools::LoadModule, and DBIx::TempDB cases. This is
  adjacent control-flow coverage; a shared root cause is **not proven**.
  LAST uses loop/block targets, while the reduced defect involves GOTO targets;
  lexical warning suppression is an additional obligation. #1482 is not one
  of the five selected issues and must not be claimed fixed by this JVM test.
- `/private/tmp/issue-1482-skip.t` was created but not run. Its extracted
  snippet declares four tests without the complete upstream body. Use the
  exact distribution test or a correctly planned focused probe before recording
  results.
- Several implementation attempts removed the reduced ASM fallback but caused
  existing label/pad failures. They tried skipping duplicate stack entries,
  deduplicating dispatcher matches by name, and sharing ASM targets. None is
  accepted. Current uncommitted `EmitBlock` work shares ASM targets for direct
  labels; it has **not** passed the unit gate and must be replaced or justified
  before publication. The label test passing is insufficient acceptance.
- Latest full gate on that candidate: `timeout 1800 nice -n 19 make`, exit 2
  after 4m48s; `/private/tmp/parser-shared-label-target-make.log`. Reports:
  `array_sparse_slot_alias.t` (goto from a map callback is rejected as entering
  a foreach), `goto_nested_label_precedence.t` (false-branch target reports a
  construct-entry error instead of missing label), and
  `lvref_conditional_target_alias.t` (assertion 55, state refalias after a
  forward jump, fails). All three existing tests pass on standard Perl and
  PerlOnJava interpreter (4/4, 4/4, 58/58); only the JVM backend fails them.
- Current-JAR probes with `JPERL_SHOW_FALLBACK=1` confirm the reduced label
  compilation test is narrower than these semantics: the array case executes
  without fallback and emits the foreach error; the goto case falls back in
  `Test::Builder` and still reports the wrong false-branch diagnostic; the
  lvalue case falls back in `Test::Builder` and still fails the state-pad
  assertion. Full output was captured in
  `/private/tmp/{array_sparse_slot_alias,goto_nested_label_precedence,lvref_conditional_target_alias}.jperl.log`.
- Source inspection found `EmitSubroutine` copied enclosing loop-protected
  labels into every new method even though its comment describes eval-block
  propagation. The current uncommitted candidate now limits that loop-label
  copy to eval blocks; this is not yet validated. The defer-label copy remains
  unchanged. `collectConstructEntryLabels` also marks constant-folded-away
  `if` branches although `EmitStatement.emitIf` removes those branches. The
  state-pad failure still needs a trace through goto target emission and pad
  initialization. Verify these in one focused source batch before rebuilding.
- Parser inspection confirms `map`/ `grep` blocks are
  `SubroutineNode(useTryCatch=false)`; eval blocks set `useTryCatch=true`.
  The loop/defer-set copy in `EmitSubroutine` is unconditional, while nearby
  comments describe eval-only propagation. This supports, but does not yet prove,
  the map callback false-positive diagnosis.

| Issue | Implementation and recorded evidence | Remaining work |
|---|---|---|
| [#1619](https://github.com/fglock/PerlOnJava/issues/1619): HTML::Tree split parsing | On rebased commit `d753a616e`, system Perl passes 969 tests across 23 files (the LeakTrace-only file is skipped); JVM/interpreter each run 989 assertions across 23 files, with one existing TODO failure. `t/split.t` passes 444/444 and `t/refloop.t` 8/8. | Recheck after PR update; preserve the upstream TODO and skip status |
| [#1622](https://github.com/fglock/PerlOnJava/issues/1622): ascending version declarations | `use_version_ascending_scope.t` passes standard Perl and both backends. `jcpan -t License::SPDX` passes 35/35 across 10 files on JVM; the same suite passes 35/35 on interpreter. | Recheck on the PR commit; full distro system-Perl run requires CPAN prerequisites unavailable in the host system-Perl environment |
| [#1615](https://github.com/fglock/PerlOnJava/issues/1615): valid prototype warnings | `prototype_optional_after_array.t` passes standard Perl, JVM, and interpreter; included in the full unit gate. | Recheck on the PR commit |
| [#1166](https://github.com/fglock/PerlOnJava/issues/1166): qualified indirect constructors | Both reported forms pass unchanged tests (five assertions total) on standard Perl, JVM, and interpreter. | Recheck both forms on the PR commit |
| [#1470](https://github.com/fglock/PerlOnJava/issues/1470): AnyEvent compilation and dispatch | Earlier compiler compatibility landed in PR #1598; oversized closure source constant and dispatch changes are implemented locally. On the final batched source, unchanged mutex passes 8/8 and the full AnyEvent::Tools suite passes 103/103 on standard Perl, JVM, and interpreter. | Recheck on the final PR commit; then include in the five-issue closure audit |

The [issue batch](parser-high-impact-issues-20261002.md) retains experiment
history and distribution evidence, including Argv, Weasel::Driver::Selenium2,
and LWPx::TimedHTTP results. Match each result to the actual issue acceptance
criteria when preparing the final matrix; skips and TODOs are not proof that
unsupported behavior is fixed.

[PR #1623](https://github.com/fglock/PerlOnJava/pull/1623) is the delivery PR
for all five fixes. Its recorded remote contents currently cover four parser
fixes; integrate the AnyEvent changes after their acceptance gates pass.
Recheck remote state, branch history, and contents before updating it.

Remote review on 2026-10-05 confirms PR #1623 is **OPEN**, based on `master`,
at head `52a7fc6349d08dd9a6574bd8d19ff5700c7d7896`, with the four selected
parser fixes and their regression files. The current AnyEvent worktree is not
part of that PR.

### Runtime optimization history and current working candidate

The first items below record earlier candidates and their acceptance results;
the latest working-candidate status is recorded in Progress Tracking and
Immediate next steps below. Each failed mutex result remains an acceptance gap.

- WIP snapshot `91a622943` preserves the initial witness-cache scaffolding and
  `weak_global_code_array_witness.t`; the regression passes 9/9 on system Perl,
  JVM, and interpreter on the current artifact. It is superseded by the
  production implementation below, but remains the parent-side regression
  evidence.
- Candidate `977864670` preserves the runtime, diagnostics, and test source on
  top of parent `75681d00d`. A subsequent debug-only candidate-path trace was
  validated by `timeout 1800 nice -n 19 make` in 5m12s. This diagnostic source
  is still in progress; rerun the final gate after later runtime changes.
- The oversized constant fix stores long closure deparse source in per-runtime
  metadata keyed by generated class. A Java integration regression exercises
  70,000-character source and asserts JVM compilation without interpreter
  fallback, with interpreter validation separately. Equivalent source passes
  standard Perl; the unfixed AnyEvent load exposed `UTF8 string too large`.
  Other method-size and ASM frame fallbacks are separate questions.
- `RuntimeArray.shift()` / `pop()` now carry the removed owner scalar with the
  deferred decrement. Initialized lexical arrays now receive interpreter root
  registration in `CompileAssignment`. The new
  `shifted_array_weak_owner_release.t` passes 3/3 on standard Perl, JVM, and
  interpreter. The earlier interpreter candidate failed this regression.
- The earlier exact source passed `timeout 1800 nice -n 19 make` in 5m26s. Its full
  interpreter inventory reports 2,080/2,129 pass, 35 fail, 10 error, and 4
  incomplete (no timeouts). A second captured run reproduced those totals.
  The matching-parent `75681d00d` comparison ran all 49 nonpassing candidate
  files on its freshly built JAR: every file has the same status, failed
  assertion count, planned/actual TAP count, and error list. No new interpreter
  failure is attributable to this candidate. The target exits zero despite
  these results; inspect its summary and per-file data, not exit status alone.
  The 49 failures are parent-present interpreter gaps, not evidence that the
  focused changed behavior regressed. The default all-unit `make` gate remains
  required and passed; rerun affected interpreter tests after runtime changes.
- Commits `00478c93d` through `a2fba0368` implement a quiet-sweep fast path with
  weak owner witnesses, direct root/edge validation, bounded strong-cycle
  proofs, fail-closed fallback, and an optional fresh-walk cross-check. The
  latest exact source passed `timeout 1800 nice -n 19 make` (4m44s; all unit
  tests passed). The focused lifecycle batch, including
  `weak_global_code_array_witness.t`, passed with cross-checking enabled on both
  backends.
- On the unchanged mutex test, the exact first-to-second reader callback
  interval now takes the fast path: 2 weak candidates, 1 validated witness,
  1 bounded cycle check over 5 edges, and no full root walk in the interval.
  The uninstrumented unchanged test passes 8/8 on interpreter and still fails
  only assertion 4 on JVM. The full AnyEvent distribution remains gated on
  fixing that JVM case.
- Diagnostic-only instruction counters were temporarily added to the worktree,
  used to measure the interval, and removed after profiling. That instrumented
  revision passed `timeout 1800 nice -n 19 make` in 4m42s. With
  counters enabled, the exact callback window executes 3,305 generated JVM
  bytecodes and 114 interpreter opcodes in the JVM run. The generated-code
  histogram is led by AnyEvent::Loop `timer` (479), RWMutex `_delete_client`
  (448), `_add_client` (430), an RWMutex closure (375), and `rlock_limit`
  (243). The runtime dispatch counter records 10 calls: `_check_mutex` twice,
  and once each for `_delete_client`, `_add_client`, `is_rlocked`, `is_wlocked`,
  `rlock_limit`, `Time::HiRes::time`, `CLOCK_MONOTONIC`, and the guard's `null`
  callback. These count generated JVM bytecodes and interpreter opcodes; they
  are not retired hardware instructions or elapsed-time estimates.
- An instrumented 100 us JFR sampling run produced 302 samples over four
  seconds, but none fell inside the roughly 2 ms marker window; the nearest
  samples were just before and after. It does not attribute residual cost.
  Instrumented runs perturb timing and are not acceptance evidence. Keep the
  upstream test unchanged.
- The current source change makes each `JvmClosureFrame`'s created-closure list
  and returned-closure identity map lazy. The exact source passed
  `timeout 1800 nice -n 19 make` in 5m01s, including all default unit tests.
  This validates the existing unit suite against the change, but does not show
  that it reduces enough work in the mutex interval. The unchanged JVM mutex
  run on the resulting artifact is 6/8 (assertions 4 and 7); interpreter is
  8/8. Treat the allocation reduction as semantics-validated, not as an
  accepted latency fix.
- The one-slot `ThreadLocal` `JvmClosureFrame` cache passed the full build in
  4m40s. On that rebuilt artifact, the unchanged mutex test passes 7/8 on JVM
  (assertion 4 fails) and 8/8 on interpreter; standard Perl passes 8/8. The
  cache removes a frame allocation on hits, but does not meet JVM acceptance.
  `closure.t` passes 18/18 on system Perl and JVM; its interpreter run fails
  assertion 17, a failure already recorded on parent `75681d00d` in the
  interpreter inventory comparison. The frame-cache source changes only
  `RuntimeCode`, which `InterpretedCode.apply` overrides.
- The current candidate adds compile-time closure-frame metadata.
  Generated methods are marked as requiring a frame when they emit a nested
  subroutine or `eval`; generated code objects inherit the mark for their own
  bodies. `RuntimeCode` created outside the compiler remains conservative by
  default. Methods marked unable to create CODE objects skip the frame push,
  closure tracking, and pop entirely. The per-thread cache remains for methods
  that need a frame. The exact AnyEvent mutex helpers `_delete_client`,
  `_add_client`, `rlock_limit`, `is_rlocked`, and `is_wlocked` contain no nested
  subroutine or eval expressions in the unchanged module source, making these
  calls the targeted frame-elision paths. Closures created by the reader
  callback and timer code retain normal frames.
- The completed metadata audit covers eagerly generated CVs, lazy named-sub
  placeholders, nested subroutines, eval, closure clones, and runtime graph
  snapshots. External code remains conservative. The full unit build passed on
  this exact worktree in 5m11s. The unchanged mutex test is 7/8 on JVM
  (assertion 4 fails) and 8/8 on interpreter; standard Perl remains 8/8.
  `weak_global_code_array_witness.t` passes 9/9 and
  `returned_closure_captures.t` passes 4/4 on both PerlOnJava backends. Frame
  elision is semantics-validated for these focused cases, but it is not an
  accepted dispatch fix.
- `pushArgs` now reuses a shared immutable empty pristine-argument snapshot.
  This removes the empty `ArrayList` allocation for nullary calls while
  preserving independent snapshots for nonempty `@_`. The exact callback
  inventory has ten runtime dispatches; `Time::HiRes::time` and
  `CLOCK_MONOTONIC` are nullary, so this removes two list allocations in that
  interval. The full unit build passed in 5m06s. Three `caller`/`@DB::args`
  regressions (8 assertions) pass on system Perl, JVM, and interpreter. The
  unchanged mutex result remains JVM 7/8 (assertion 4), interpreter 8/8, and
  standard Perl 8/8; the allocation saving is not an accepted latency fix.
- Production candidate `5f81a8a3a` adds fail-closed compile-time analysis for
  pristine `@_` snapshots. It shares the live argument list only when the CV
  body is proven not to mutate or alias `@_`; structural writes, `$_[n]` writes,
  implicit shifts/pops, aliases, eval, goto, and unknown forms retain the copy.
  The analysis skips nested CV bodies, which have separate argument frames,
  and its metadata is propagated through generated code, lazy named-sub
  materialization, closure cloning, and runtime graph snapshots. Five Java
  unit tests cover the effect rules and generated-code metadata. The exact
  source passed `timeout 1800 nice -n 19 make` in 5m08s. On that artifact,
  the unchanged mutex is 8/8 on standard Perl and interpreter, but 7/8 on JVM
  (assertion 4). This optimization is semantics-validated but does not complete
  #1470. A later exact-interval counter shows that `_check_mutex` still copies
  one argument, so the static expectation that its self-tailcall would share
  caller arguments is not established on the actual parser path.
- Follow-up source and test work now covers reads of `@_` arity, ordinary `$_`
  use, explicit calls with fresh arguments, nested CV isolation, and calls
  marked as sharing the caller's argument list. Java tests also materialize
  lazy named CVs before checking metadata and inspect the parsed prototyped
  timer body directly. Java-backed `Time::HiRes::time`,
  `Time::HiRes::clock_gettime`, and `Scalar::Util::weaken` registrations now
  carry both no-closure-frame and read-only-argument metadata. The exact
  worktree, including these changes, passed `timeout 1800 nice -n 19 make` in
  4m36s. Its unchanged mutex result remains standard Perl 8/8, interpreter
  8/8, JVM 7/8 (assertion 4 only). This is a passing unit candidate, not an
  accepted #1470 fix. The production changes are committed in `5f81a8a3a`.
- A static-label `goto` analysis refinement and regression are included in
  `5f81a8a3a`. The test proves the analyzer remains conservative when a later
  `shift` mutates `@_`. Follow-up tracing corrected the earlier parser-name
  hypothesis: the real AnyEvent `_check_mutex` reaches
  `PristineArgsSnapshotAnalysis` with its canonical name, and its direct
  self-tailcall is accepted. JVM generation then falls back after ASM frame
  computation fails. The failure is an NPE in `Frame.merge` (`dstFrame` is
  null) while `MethodWriter.computeAllFrames` processes generated method
  `anon1976.apply`. Interpreter fallback paths did not copy the analyzer result
  out of `JavaClassInfo`, leaving `InterpretedCode` at its conservative default.
- The current uncommitted production fix propagates pristine-argument metadata
  to interpreter code on force-interpreter and all fallback paths, and runs the
  analysis for force-interpreter compilation. A Java regression checks that a
  safe self-tailcall fallback shares arguments while a mutating function still
  snapshots them. Its Perl source passes `perl -c`. The full
  `timeout 1800 nice -n 19 make` gate passed in 5m06s before the latest
  opcode-counter diagnostics were added. Exact-marker counters then showed
  `_check_mutex` using a shared snapshot (one element) instead of a copied
  snapshot; the callback still copies. This removes one argument-list and
  element copy in the measured interval, but the unchanged JVM test still fails
  assertion 4 (7/8), so it is not the accepted latency fix.
- A Perl-valid reduced compiler reproducer now narrows the ASM failure: simple
  statement labels compile, and the same early-return branches without a label
  compile; combining array-dereference early returns with a later label triggers
  `Frame.merge`'s null-destination-frame failure. A similar branch using a
  scalar field instead of array dereferences compiles. This points to the
  emitted join/control-flow around `return if/unless @{...}` and the label,
  but does not yet identify the invalid edge. The current ASM diagnostic retry
  handles `ArrayIndexOutOfBoundsException`; this failure is wrapped as a
  `PerlCompilerException` with a `NullPointerException` cause, so it does not
  emit a no-frame bytecode dump. The reduced sources pass `timeout 60 perl -c`;
  they are diagnostic probes, not permanent regression coverage.
- A runtime counter scoped to the exact existing markers
  `t/02_rw_mutex.t:691`–`:796` reports 16 dispatches; 4 empty argument
  snapshots; 2 copied snapshots over 2 elements; 10 shared snapshots over 16
  elements; 13 active lexical frames, of which 9 allocate lexical maps; 3
  closure-frame pushes (2 allocations, 1 reuse); 1 created-closure list; and
  0 returned-closure maps. `_check_mutex` and the reader callback each account
  for one copied snapshot and one element. These are operation counts from an
  instrumented run, not hardware instructions or timing estimates. A separate
  exact-marker run counts 3,313 generated JVM bytecodes; it excludes Java
  runtime bytecodes.
- Active lexical frame reuse in `5f81a8a3a` retains all 13 semantic frames
  while reusing their objects, avoiding 13 frame-object allocations in this
  interval. The exact full unit gate passed in 4m44s, but the unchanged mutex
  result remains standard Perl 8/8, interpreter 8/8, JVM 7/8 (assertion 4).
  A prior experiment that omitted an active frame when the symbol table's
  variable-name list was empty failed `cpan_tooling_runtime.t`,
  `devel_lexalias_padwalker.t`, `format_active_lexical.t`,
  `padwalker_var_name.t`, and `regex/dynamic_overload_lexical_tracking.t`; it
  was reverted. Keep the frames and optimize their lifecycle only when
  semantics remain intact.

### What the dispatch evidence establishes

The failing assertion measures the gap between two synchronous reader
callbacks with a 1 ms wall-clock limit. Callback-return cleanup is the diagnosed
cost; timer clock correctness is a separate concern.

Before the optimization, the TAP-window diagnostic counted **3 complete walks,
17,577 graph nodes, and 13,180 container slots between assertions 3 and 4**;
that includes other test activity. Four exact callback-interval runs each
showed one complete walk and three cycle checks, with 5,865–5,868 nodes and
4,395–4,398 slots. Each sweep checked two weak candidates and cleared neither.
Those are deterministic work counts, not CPU instruction counts or wall-clock
estimates.

Successive baseline walks showed three stable weak candidates and one array
changing from live to dead. The retained path was:

```text
live CODE -> captured array -> owning scalar slot -> weakly observed array
```

A later walk found the captured parent array emptied; its old scalar slot was
unrooted and `refCountOwned` was false. The surviving candidate's exact
CODE/array/scalar witness remained while the captured parent array grew from
three to four slots; a second weak candidate was protected by a strong cycle.
This motivated validating the specific owner edge instead of trusting a root
identity or broad graph snapshot. The current fast path validates positive
witnesses and fails closed to the established full walk when it cannot prove
every current obligation.

After the optimization, the exact interval records two weak candidates, one
validated witness, one bounded cycle check over five edges, and no full root
walk. Diagnostic counters now report 3,305 generated JVM bytecodes, 114
interpreter opcodes in the JVM run, and 10 runtime subroutine calls. Most
generated bytecodes come from timer setup and RWMutex queue/helper methods;
this points the next investigation at their generic call-frame/runtime
dispatch cost, not another global graph walk. An instrumented 100 us JFR run
did not sample inside the marker window, so it does not refine that attribution.
Do not rank hypotheses by elapsed time on this loaded host; keep the unchanged
1 ms test solely as the required pass/fail acceptance check.

## Ordered execution plan

### Phase 1 — Freeze candidates and classify unit failures (complete 2026-10-04)

1. Preserve the coherent source as candidate `977864670`; compare it to exact
   parent `75681d00d` in an isolated worktree. Both revisions pass the default
   `nice -n 19 make` gate.
2. Capture the candidate's full interpreter inventory (2,129 files), then run
   its 49 nonpassing files on the parent JAR. The per-file statuses, failed
   assertion counts, planned/actual TAP counts, and error lists match exactly:
   35 fail, 10 error, 4 incomplete. No candidate-only interpreter failure was
   found. The target's zero exit status is not a pass signal.
3. Run `shifted_array_weak_owner_release.t` on the exact parent interpreter:
   assertion 1 fails (the shifted owner does not retain the referent); current
   source passes 3/3 on standard Perl and both PerlOnJava backends. This is
   direct unfixed-parent evidence for the lifecycle repair.
4. Keep these 49 parent-present interpreter gaps visible as baseline evidence;
   they do not block this issue batch absent evidence tying them to these fixes.
   Continue to require the default all-unit `make` gate and focused interpreter
   acceptance for each changed behavior. Any candidate-only failure blocks.
5. Preserve the four-fix PR baseline while investigating AnyEvent; integrate
   the accepted changes into PR #1623 after the required gates pass.

**Exit:** satisfied for current source; repeat the comparison if a later change
causes a new full-interpreter failure.

### Phase 2 — Measure the actual reader callback interval (baseline complete 2026-10-04)

1. Locate the exact two callback timestamps in the unchanged mutex test. Use
   existing runtime counters where possible; add one coherent diagnostic batch
   only if needed. Snapshot counters at corresponding runtime boundaries and
   emit results after the interval. Do not edit the upstream test or log inside
   the measured interval.
2. Separate module loading, timer waits, callback bodies, callback-return
   cleanup, and second callback entry. Count complete walks, nodes, slots,
   capture-root queries, and fallback executions for the actual interval.
3. Use the realistic workload on this host for representative graph sizes.
   Compare work counts on equivalent inputs and code paths. If retired CPU
   instructions cannot be measured reliably with available tooling, report
   that limitation and label operation counts accurately.
4. Attribute every expensive operation in the interval to its requester and
   cleanup obligation. Investigate compiler fallback only if it executes there.
   Write one hypothesis, expected work reduction, and correctness conditions
   before changing the runtime.

The measured callback interval contains one scope-exit-triggered full root
walk. It sees two weak candidates and clears zero. A debug-only path trace shows
one walker-live candidate through `RuntimeCode -> captured RuntimeArray ->
one walker-live candidate through `RuntimeCode -> captured RuntimeArray ->
owning RuntimeScalar -> weak RuntimeArray`; root-source tracing identifies the
CODE root as `globalCodeRefs`. Across adjacent sweeps this exact code/array/
scalar identity path remains while the captured parent array grows from three
to four slots. The exact callback interval has one scope-exit-triggered walk:
5,868 nodes / 4,398 slots, three strong-cycle checks, two weak candidates, and
zero cleared. The scope-exit target itself passes a one-node strong-cycle
check. A later sweep outside the interval shows that an old path can become
stale: its owner scalar is no longer live and the referent changes live-to-dead.
The root identity alone therefore cannot validate a cached positive; the
specific owner slot and all root/capture mutations must be checked. Other runs
show different scope-exit targets, so cycle-only skipping is not a complete
fix. The next hypothesis remains narrow positive-witness validation with a
fresh-walk fallback, subject to an authoritative invalidation design.

Source inspection narrows direct validation for this one path: the candidate
CODE is in `GlobalRuntimeState.installedCodeRefs()`, an identity set cached by
`codeRefVersion`; the closure's `capturedAggregates` array records its captured
`RuntimeArray`; and that array's `elements` list contains the exact strong
owner scalar. These are checkable edges, but there is not yet a production
witness cache, and this path alone does not account for other weak candidates
or `DestroyDispatch`'s destroyable-object sweep.

**Exit:** satisfied for baseline and hypothesis selection. TAP-window totals
alone do not satisfy this.

### Phase 3 — Remove redundant work while preserving cleanup

The production optimization is implemented in `00478c93d` through
`a2fba0368`: it gathers current sweep witnesses, validates root/capture/owner
edges directly, checks bounded strong cycles, verifies all weak and destroyable
obligations, and fails closed to the established full walk. Fresh-walk
cross-checking and deterministic counters are diagnostic-only. The exact
callback interval takes this fast path and its focused lifecycle tests pass on
both backends. Before lazy closure-frame allocation, the uninstrumented mutex
test passed 8/8 on interpreter and failed assertion 4 on JVM. The lazy
allocation candidate passed the full unit build, but its latest unchanged mutex
run passes 8/8 on interpreter and fails assertions 4 and 7 on JVM. Thus the
JVM acceptance criterion remains unmet.

The closure-frame-elision candidate skips all closure-frame work for generated
methods whose compiler metadata proves they cannot create a CODE object. This
removes frame allocation, runtime-stack push/pop, cache access, closure
protection, and closure scanning on those invocations. Unknown code stays
conservative. The metadata audit and focused lifecycle tests are complete, and
the full build passed in 5m11s. The unchanged mutex test remains JVM 7/8
(assertion 4), while interpreter and standard Perl each pass 8/8. This
candidate is not the accepted dispatch fix.

1. Preserve and review the audited metadata flow through generated code, lazy
   named-sub materialization, closure cloning, runtime graph snapshots, and
   conservative external-code defaults. Keep `weaken` call-depth tracking
   because its DESTROY path can re-enter Perl. Keep `clock_gettime`
   call-depth tracking because `RuntimeScalar.getLong()` can fetch tied data or
   invoke numeric overload code. Only no-argument `Time::HiRes::time`
   qualifies for the current no-callback leaf metadata.
2. The latest exact-marker callback-work counter batch reports 16 dispatches,
   4 empty snapshots, 2 copied snapshots over 2 elements, 10 shared snapshots
   over 16 elements, 13 active lexical frames (9 lexical maps), and 3
   closure-frame pushes (2 allocations, 1 reuse). It also reports one
   created-closure list and no returned-closure maps. These are operation
   counts scoped to `t/02_rw_mutex.t:691`–`:796`, not hardware instructions.
   The separate 3,313 generated JVM bytecode count excludes Java bytecodes in
   `RuntimeCode.apply` and its callees.
3. Current source also lazily allocates active lexical maps, reuses active
   lexical frame objects while retaining the frames, skips lexical and closure
   frames for audited Java leaf methods, preserves call-depth tracking for
   reentrant native methods, and shares `@_` only for proven-pristine direct
   self-tailcalls. Before fallback metadata propagation, the counter showed
   one `_check_mutex` copy and one callback copy; the callback copy remains
   required because it mutates `$_[0]`. After propagation, `_check_mutex`
   shares one argument element and the callback still copies.
4. The corrected label source now applies construct-entry checks only to
   constant-folded live branches, limits inherited protected loop labels to
   eval blocks, and returns the RHS value for state-scalar refalias assignment.
   The final batched source passed the full build and focused JVM/interpreter
   tests on 2026-10-05; the standard-Perl oracle passed all 66 assertions.
5. The final uninstrumented exact-window operation count before removing
   diagnostics was 16 dispatches, 4 empty snapshots, 1 copied snapshot over 1
   element, 11 shared snapshots over 17 elements, 12 active lexical frames, 9
   lexical maps, 3 closure-frame pushes (2 allocations, 1 reuse), 2 closure
   lists, and 0 returned-closure maps. These are deterministic operation
   counts, not hardware instruction counts. One instrumented assertion failure
   is not acceptance evidence.
6. Diagnostic-only probes and markers have now been removed from the current
   source batch while production metadata and frame-reuse changes remain. Run
   `git diff --check`, one exact-source `timeout 1800 nice -n 19 make`, focused
   regressions on both backends, then the unchanged mutex and full AnyEvent
   distribution gates. Any later production edit invalidates this final gate.

**Rejected shortcuts:** delaying cleanup across reader callbacks, skipping
sweeps based on refcount/synthetic keepalive or incomplete `activeOwners`,
replacing complete cleanup with target-only queries, and skipping a scope-exit
sweep solely because its target has a strong cycle. The last shortcut was
tested against the unchanged mutex case and did not remove the full walk across
runs; the cycle-checked target can differ from the candidate that needs a root
proof. Earlier candidates caused weak-reference, closure, async, and warning
regressions. Registry holders are not authoritative owner tokens. Preserve
fresh-walk behavior until replacement coverage is proven.

**Exit:** production optimization, diagnostic equivalence evidence, and all
focused lifecycle/backend tests passing. No accepted stale weak-reference or
changed destructor behavior.

### Phase 4 — Complete focused and distribution acceptance

1. Validate new Perl-level regressions with standard Perl first; retain
   unfixed-parent failure evidence. Run affected JVM/interpreter tests,
   including shifted-array ownership, callback scope exit, active CODE capture,
   localized cache lifetime, returned-owned argument chains, quoted-sub
   metadata, async warning state, and blessed descendant cleanup.
2. Run the unchanged mutex test without diagnostics on both backends. Require
   8/8. Reduced operation counts guide optimization but do not replace the
   unchanged 1 ms assertion. Record host conditions; do not select only a lucky
   passing run or change the threshold.
3. Once the focused case passes, require the complete unchanged AnyEvent::Tools
   0.12 suite: seven files / 103 assertions on both backends, including all
   mutex/timer cases and the required CPAN compatibility patch. Record the patch
   separately from upstream test sources.
4. Re-run each of the other four issues' unchanged reproducers and distribution
   gates on its final candidate. Cover both #1166 forms, prototype diagnostics,
   ascending versions, and full HTML::Tree acceptance.

**Exit:** all five issue acceptance sets pass with permanent regression evidence.

### Phase 5 — Final gates, PR #1623, and closure

1. Freeze clean, committed candidates. Record tested commit IDs and matching
   JAR provenance. Run unfiltered `nice -n 19 make` and require every default
   unit test to pass. Run the full interpreter inventory as a regression audit;
   compare nonpassing files with the recorded matching parent and require all
   focused changed-behavior tests to pass. Resolve any candidate-only failure
   without modifying existing tests.
2. Run issue gates after the build writers finish. Any source change invalidates
   the previous final gate; rerun on the exact candidate. Inspect results,
   including skips/TODOs, rather than relying solely on process status.
3. Add significant changes under `Work in progress` in the changelog, update
   tracked evidence and progress, and run `nice -n 19 make check-links`.
4. Recheck remote PR state and contents; push feature branches only after their
   required gates pass. Update PR #1623 with all five fixes, exact scope,
   tested commits, commands, results, and limitations.
5. Wait for review before merging. Close only confirmed-fixed issues with their
   complete acceptance evidence. Audit all five before marking the goal done.

**Exit:** verified PR #1623 and completed five-issue audit; no outstanding required
implementation or acceptance work.

## Build and shared-host discipline

- Reuse the matching JAR and existing diagnostic switches. Batch coherent edits
  before rebuilding; avoid repeated full builds for diagnostic-only variants.
  Final publication always requires the full gates.
- Run every repository make command with `nice -n 19`; wrap investigative
  `jperl`, `jcpan`, and `prove` runs in suitable timeouts. Capture full output.
- Run shared-JAR writers serially, including `make test-bundled-modules`.
  Start reader gates after the writer and all workers exit. Keep each tested
  checkout immutable through its entire gate.
- Poll infrequently. A tool observation timeout is not grounds to restart a
  still-running gate. Record real gate timeouts as inconclusive.
- Record concise results and failure inventories in tracked documentation,
  then remove task-owned large logs/profiles and unused build worktrees.
  Preserve WIP backups and unrelated host workloads.
- Documentation-only plan changes require link checks, without runtime rebuilds.

## Progress tracking

### Current status: final rebased candidate `5fcb368e3`; final build, UAT, PR CI, and issue audit pending (2026-10-06)

- [x] Rebased all 28 PR commits onto `origin/master` at `1a056c033` before
  final validation. The rebase had one `RuntimeCode.java` conflict; resolution
  preserves upstream caller-package tracking and the PR's reusable active-frame
  optimization.
- [x] Added strict test-runner handling for nonzero child exits after complete
  TAP, with a runner integration regression.
- [x] Added permanent CORE coderef coverage for open, array I/O, substr lvalue,
  raw DATA markers, and tie/tied/sysread/umask argument and dispatch behavior.
  The new `core_tie_sysread_umask_coderef.t` passes on standard Perl; earlier
  focused regressions pass on standard Perl and both PerlOnJava backends where
  applicable. Recheck all of them on this rebased candidate.
- [x] Focused strict `coreamp.t` exposed callable `CORE::sysread`, `tie`,
  `tied`, and `umask` gaps. Its next run exposed the adjacent `CORE::undef`
  reference prototype and dispatch gaps after passing 520/524 assertions.
  Source fixes and project-owned regressions for these cases are now staged in
  the next validation batch; `core_undef_coderef.t` passes standard Perl. The
  candidate still needs a build and focused rerun.
- [ ] Run full `make`, focused `coreamp.t`, then the full strict 575-file UAT
  on the immutable rebased candidate. Require zero failed assertions, child
  exit errors, timeouts, or incomplete files.
- [ ] Compare the final UAT to the October 3 baseline and classify the known
  imported-corpus count changes and existing zero-TAP rows.
- [ ] Update PR #1623, wait for green required CI, audit the linked issues,
  close only those confirmed fixed, and merge.

- [x] Four selected parser fixes and permanent regressions implemented.
- [x] Oversized closure constant diagnosed, fixed, and regression validated.
- [x] HTML::Tree interpreter lifecycle parity and full distribution validated.
- [x] Shifted-owner regression validated on standard Perl and both backends;
  latest default full build passed (2026-10-04).
- [x] Reduced label-registration regression identified and covered by a Java
  no-fallback test; three existing JVM regressions were repaired in one source
  batch and pass on JVM/interpreter. Standard Perl passes all 66 assertions.
- [x] Removed temporary dispatch and reachability instrumentation from the
  current source batch after capturing operation-count evidence.
- [x] Final batched source passes `timeout 1800 nice -n 19 make` in 4m59s;
  output is `/private/tmp/parser-label-final-batch-make.log`.
- [x] Three unchanged label/pad regressions pass all 66 assertions on standard
  Perl, JVM, and interpreter. Java label-registration regression passes in the
  full build; focused run output is `/private/tmp/parser-label-final-batch-focused.log`.
- [x] Unchanged AnyEvent mutex passes 8/8 on standard Perl, JVM, and
  interpreter. Full AnyEvent::Tools 0.12 suite passes 7 files / 103 assertions
  on standard Perl, JVM, and interpreter on this source batch.
- [x] Rebasing the squashed candidate onto current `origin/master` was source
  neutral: the only intervening upstream changes were the CPAN report refresh.
  Exact rebased commit `d753a616e` passes `timeout 1800 nice -n 19 make` in
  6m26s; output is `/private/tmp/parser-high-impact-integration-make.log`.
- [x] On `d753a616e`, HTML::Tree 5.07 passes 23 files on all three runtimes:
  system Perl reports 969 tests and skips the LeakTrace-only file; JVM and
  interpreter run 989 assertions with one pre-existing TODO failure. The
  issue reproducer `t/split.t` passes 444/444 and `t/refloop.t` 8/8.
- [x] License::SPDX 0.07 passes 35/35 across 10 files on JVM via `jcpan -t`
  and on interpreter with the same declared dependency set.
- [x] The prototype, ascending version, both qualified-constructor forms, and
  retained #1466/#1476 regression tests pass 9/9 on system Perl, JVM, and
  interpreter.
- [x] Repeat final-candidate AnyEvent::Tools acceptance on `d753a616e`: the
  unchanged mutex and full seven-file suite pass 8/8 and 103/103 respectively
  on standard Perl, JVM, and interpreter.
- [x] Phase 1: matching-parent interpreter comparison; all 49 candidate
  failures are present with identical outcomes on parent `75681d00d`.
- [x] Phase 2: exact callback-interval baseline; four runs each count one
  walk and three cycle checks, with 5,865–5,866 nodes and 4,395–4,396 slots.
- [x] Added debug-only candidate root-path output and passed the full gate in
  5m12s; captured the stable candidate's unchanged owner witness.
- [x] Added object identities to cycle diagnostics; full gate passed in 5m07s.
  The exact-test interval ties the cycle-protected referent to its scope-exit
  identity; a cycle-only skip did not remove the required sweep in all runs.
- [x] Added debug-only root-seed labels, passed `nice -n 19 make` in 5m48s,
  and reran the unchanged mutex test on the rebuilt JVM artifact. The exact
  first-to-second reader callback interval has a `globalCodeRefs` path, one
  5,868-node / 4,398-slot walk, three cycle checks, two stable weak candidates,
  and zero clears; assertion 4 still fails.
- [x] Implemented bounded quiet-sweep witness fast path across commits
  `00478c93d`–`a2fba0368`; latest full `make` passed and focused lifecycle
  tests passed on JVM/interpreter with fresh-walk cross-checking.
- [x] Exact mutex callback interval now avoids the global root walk (2 weak
  candidates, 1 witness, 1 cycle check, 5 cycle edges), with all sweep
  obligations retained.
- [x] Added opt-in generated JVM bytecode, interpreter opcode, and Perl runtime
  subroutine counters bounded by the existing callback markers. The full
  `nice -n 19 make` gate passed in 4m42s with instrumentation call sites guarded
  when disabled.
- [x] Exact JVM callback window: 3,305 generated JVM bytecodes, 114 interpreter
  opcodes, and 10 runtime dispatch calls. Leading generated sources are
  `AnyEvent::Loop::timer` (479), RWMutex `_delete_client` (448), `_add_client`
  (430), an RWMutex closure (375), and `rlock_limit` (243).
- [x] Revalidated unchanged mutex without instrumentation on the lazy-frame
  candidate: JVM 6/8 (assertions 4 and 7); interpreter 8/8; standard Perl is
  8/8 from prior validation. The earlier JVM run failed assertion 4 only.
  Instrumented runs perturb timing and do not count as acceptance evidence.
- [x] Configured JFR at 100 us; its 302 samples over four seconds did not land
  inside the callback interval, so JFR does not refine the exact instruction
  attribution.
- [x] Lazy-allocate the `JvmClosureFrame` created-closure list and
  returned-closure map. The default `nice -n 19 make` gate passed on this
  candidate in 5m01s. The unchanged mutex test remains failing on JVM (6/8,
  assertions 4 and 7); interpreter passes 8/8. This is not an accepted latency
  fix.
- [x] One-slot per-thread `JvmClosureFrame` reuse candidate. Full build passed
  in 4m40s; standard Perl and interpreter mutex tests passed 8/8, while JVM
  passed 7/8 and still failed assertion 4. This candidate is insufficient.
- [x] Compile-time closure-frame elision for generated methods proven not to
  create CODE objects. Full build and focused closure ownership tests pass;
  unchanged JVM mutex remains 7/8, so this is not the accepted dispatch fix.
- [x] Propagate frame metadata through lazy named-sub materialization, closure
  clones, and runtime graph snapshots; unknown code remains conservative.
- [x] Reuse an immutable empty pristine-argument snapshot. Full unit build
  passed in 5m06s; `caller_db_args_freed.t`, `caller_tied_db_args.t`, and
  `destroy_zombie_captured_by_db_args.t` pass 8/8 on standard Perl and both
  backends. Mutex remains JVM 7/8 (assertion 4), interpreter/standard Perl 8/8.
- [x] Add fail-closed compile-time pristine-`@_` snapshot effect analysis with
  five Java regressions. The exact full unit build passed in 5m08s; unchanged
  mutex remains JVM 7/8 (assertion 4), interpreter and standard Perl 8/8.
  Therefore the candidate is validated but #1470 remains unresolved.
- [x] Extend the effect audit for direct calls with fresh arguments, calls
  annotated as sharing caller arguments, nested prototyped timer bodies, and
  lazily materialized CV metadata. Mark the three identified read-only,
  Java-backed hot-path methods as not requiring a closure frame or argument
  snapshot. Exact-source `timeout 1800 nice -n 19 make` passed in 4m36s;
  unchanged mutex remains JVM 7/8 (assertion 4), interpreter and standard Perl
  8/8. This historical candidate was not yet committed at the time and did not
  complete #1470; its production changes are now in `5f81a8a3a`.
- [x] Add a fail-closed direct self-tailcall exception to pristine-`@_`
  sharing. A Java integration regression covers a safe self-tailcall and a
  different-target goto; the source passes standard-Perl syntax checking.
  The preceding full build passed in 4m35s, but predates the current
  call-depth-safety correction. The unchanged mutex remains JVM 7/8 (assertion
  4), interpreter and standard Perl 8/8.
- [x] Earlier pre-refinement operation counters recorded
  16 dispatches, 4 empty snapshots, 2 copied snapshots over 2 elements, 10
  shared snapshots over 16 elements, and 17 active lexical frames/maps. Those
  counts predate lazy-map allocation and self-tailcall sharing and had
  asymmetric marker boundaries; the later exact-marker batch is recorded
  below.
- [x] Restore call-depth tracking for `Time::HiRes::clock_gettime` after code
  review showed its `getLong()` argument conversion can fetch a tied scalar or
  invoke numeric overload code. `Time::HiRes::time` remains the only registered
  no-callback leaf; `Scalar::Util::weaken` also retains tracking because it can
  trigger `DESTROY`. The corrected exact source passed `timeout 1800 nice -n 19
  make` in 5m23s. On that artifact the unchanged `t/02_rw_mutex.t` passes 8/8
  on system Perl and the interpreter, while JVM remains 7/8 (assertion 4).
- [x] Add static-label `goto` support to the pristine-argument analysis and
  add a Java regression proving that a later `shift` keeps the analysis
  conservative. This still does not prove the AnyEvent named-CV self-tailcall
  because that parser path reaches analysis without the canonical CV name.
- [x] Capture exact-marker dispatch counts after the lexical-map and
  self-tailcall refinements: 16 dispatches; 4 empty snapshots; 2 copies over
  2 elements; 10 shared snapshots over 16 elements; 13 active frames and 9
  maps; 3 closure-frame pushes (2 allocations, 1 reuse); 1 created-closure
  list; 0 returned-closure maps. `_check_mutex` and the callback each account
  for one copy. The generated JVM bytecode count is 3,313. These diagnostics
  identify the remaining `_check_mutex` snapshot but do not establish its
  cause or satisfy JVM assertion 4.
- [x] Reuse active lexical frame objects while retaining all active frames.
  The exact full unit gate passed in 4m44s and the counter shows 13 reuses with
  no active-frame allocations in the measured interval. The unchanged mutex
  remains JVM 7/8 (assertion 4); interpreter and system Perl pass 8/8. A
  separate omission experiment failed five lexical/pad-related tests and was
  reverted; do not omit frames based on an empty symbol-table name list.
- [x] Trace the real AnyEvent `_check_mutex` parser/materialization path: its
  canonical name reaches analysis and the self-tailcall is accepted. JVM
  emission then fails in ASM frame computation and falls back to the
  interpreter; before the current fix, fallback discarded the proven
  pristine-argument metadata.
- [x] Propagate pristine-argument metadata through interpreter fallback and add
  a Java regression for safe self-tailcall versus mutation. The preceding full
  unit build passed; exact-marker counters show one fewer copied argument
  element, but JVM mutex remains 7/8 (assertion 4). Rerun the full gate on the
  final diagnostic-free source.
- [x] Reduce the ASM failure and identify an undefined dispatcher target from
  duplicate label registration (2026-10-05); the new JVM regression passes.
- [x] Repair registration without breaking the three existing tests recorded
  in Resume handoff; all three now pass on standard Perl, JVM, and interpreter
  after the diagnostic cleanup.
- [x] Revalidate the actual AnyEvent mutex and full distribution on the final
  artifact: 8/8 and 103/103 on standard Perl, JVM, and interpreter. The earlier
  114 interpreter opcodes do not prove dispatch-cost dominance.
- [x] Remove diagnostic-only source probes and markers from the current batch.
- [x] Run the exact final-source unit and focused gates after this cleanup.
- [x] Phase 3: combined cleanup and dispatch candidate passes current #1470
  acceptance. Earlier frame-elision-only candidates failed assertion 4.
- [x] Phase 4: the unchanged issue acceptance gates pass on candidate
  `d753a616e`; the interpreter inventory still has 49 matching-parent gaps.
- [ ] Phase 5: final rebased candidate `5fcb368e3` is based on `origin/master`
  `1a056c033`. The final `make`, strict focused `coreamp.t`, full 575-file UAT,
  baseline comparison, updated PR CI, and issue closure audit remain pending.
  Earlier croak failures were checked against the local standard Perl 5.44.0
  oracle and must remain unchanged in imported tests.

### Immediate next steps

The final source commit `5fcb368e3` is rebased on `origin/master` `1a056c033`.
The latest batch fixes strict subprocess-exit accounting and callable CORE
operator gaps found after the previous broad run. No full build or UAT result
applies to this exact source yet; the last strict `coreamp.t` run was on the
previous JAR and correctly reported nonzero exit plus failures. Keep imported
Perl tests unchanged.

1. Run `nice -n 19 timeout 3600 make`, then rerun focused regressions and
   strict `coreamp.t` on the resulting JAR.
2. Run the full refreshed core corpus with five jobs and a 300-second per-test
   timeout. Require zero failed UAT files, failed assertions, nonzero child
   exits, timeouts, and incomplete files.
3. Recompare the final corpus against
   `/Users/fglock/projects/PerlOnJava/logs/test_20261003_080000_mixed.log` and
   retain the normalized report with the assertion-count and subprocess
   metadata classification.
4. Push the validated candidate to PR #1623, check every required CI job, and
   fix and retest any failure. Close only tickets confirmed fixed by the
   merged PR and full acceptance evidence.
5. Keep the UAT logs and JSON as evidence, remove only task-created temporary
   artifacts that are no longer needed, and poll long-running gates
   infrequently.

### Open questions and blockers

- The 49 interpreter nonpassing files match the parent exactly; a later runtime
  candidate must repeat the regression comparison if its failure inventory changes.
- Does the self-tailcall proof cover every AST path for caller-argument sharing,
  and is it fail-closed for all other goto targets and argument aliases?
- Label registration now shares the statement/declaration target and passes the
  reducer plus all three unchanged JVM regressions on the current candidate.
  Earlier name deduplication and stack changes remain rejected historical
  attempts.
- The map callback, constant-folded false branch, and state-pad failures are
  fixed by eval-only protected-label propagation, live-branch construct-entry
  collection, and returning the state refalias RHS value. The unchanged tests
  pass on standard Perl and both PerlOnJava backends.
- Does #1482 share a concrete cause, or require independent non-local LAST
  target and lexical warning-state fixes? Its report and a passing GOTO
  compilation regression do not establish that relationship.
- The current local code batch passes full `make` and refreshed blead UAT.
  Final acceptance still requires repeating both on the latest rebased commit,
  classifying the Oct 3 comparator's corpus/count and subprocess metadata
  differences, and green PR CI after pushing.

## Related documents and skills

- [Issue batch evidence](parser-high-impact-issues-20261002.md)
- [Reference owner ledger](refcount-owner-ledger.md)
- [Runtime profiling skill](../../.agents/skills/profile-perlonjava/SKILL.md)
- [Debugging skill](../../.agents/skills/debug-perlonjava/SKILL.md)
