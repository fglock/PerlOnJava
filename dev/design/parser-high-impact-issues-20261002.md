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

### Current status: four parser fixes are implemented; #1470 dispatch latency remains

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
- [x] Re-read the live #1470 acceptance criteria and isolate its remaining
  failure: `AnyEvent::Tools/t/02_rw_mutex.t` compares two synchronous reader
  callbacks with a 1 ms wall-clock threshold. The first callback creates a
  timer and returns; its lexical cleanup can request a global weak-reference
  sweep before the second reader callback. This is callback-return cleanup
  cost, not event-loop timer dispatch. Keep the separate `AnyEvent::Loop` clock
  patch for timer correctness, but do not treat it as the dispatch-latency fix.

### Next steps

1. Keep PR #1623 scoped to the four confirmed parser fixes. Do not include the
   AnyEvent timing work or close #1470 until its acceptance gate passes.
2. Profile the remaining callback-return cleanup interval on the simulation
   host, focusing on the full weak-reference reachability walk and global code
   capture traversal. Preserve complete cleanup before the next callback;
   earlier whole-queue deferral and owner-based skips broke lifecycle tests.
3. After a safe dispatch optimization, run the callback-scope regressions on
   both backends, then the complete seven-file, 103-assertion AnyEvent::Tools
   gate on both backends. Repeat the timing-sensitive gate under normal host
   load.
4. Run the remaining issue-specific upstream gates on both backends:
   HTML::Tree 5.07 `t/split.t`, License-SPDX 0.07, Weasel-Driver-Selenium2
   0.15, Argv 1.28, and LWPx::TimedHTTP 1.4. Refresh the CPAN compatibility
   classification for AnyEvent::Tools after its full suite passes.
5. Update PR #1623 and close only the issues whose complete acceptance sets
   pass.

### Open questions

- AnyEvent::Tools' three mutex and timer files must pass with the CPAN source
  patch installed, and the full seven-file, 103-assertion suite must pass.
  The current blocker is `t/02_rw_mutex.t` assertion 4's 1 ms callback limit.
