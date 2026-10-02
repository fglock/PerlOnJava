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

### Current status: four fixes are ready for review; #1470 remains open

### Completed

- [x] Confirm the working tree was clean and create the feature branch.
- [x] Open draft [PR #1623](https://github.com/fglock/PerlOnJava/pull/1623).
- [x] Close #1466 and #1476 after verifying their reproductions on system Perl,
  JVM, and interpreter backends; add permanent regression coverage.
- [x] Capture unfixed-parent failures and add system-Perl-validated tests for
  ascending versions, prototype diagnostics, qualified constructor arguments,
  and incremental HTML entity/whitespace boundaries.
- [x] Implement source changes for #1615, #1619, #1622, and #1166.
- [x] Preserve the `HTML::Entities::decode` alias after `HTML::Parser` XS
  initialization, covering the interpreter-only TreeBuilder lookup failure.
- [x] Confirm #1470's code changes already landed in merged PR #1598; a current
  AnyEvent::Tools run still fails 5 of 103 timing and mutex assertions, so leave
  the issue open and exclude it from this PR's fixes.
- [x] Run `nice -n 19 make` successfully after the complete change set and
  validate the new tests with system Perl and both PerlOnJava backends.
- [x] Pass documentation link checks and retain the diagnostic evidence for
  the remaining AnyEvent::Tools failure.

### Next steps

1. Push the reviewed commits and update PR #1623 with verified results.
2. Investigate #1470 separately; do not close it until AnyEvent::Tools passes
   consistently.

### Open questions

- AnyEvent::Tools' `t/01_mutex.t`, `t/02_rw_mutex.t`, and `t/03_repeat.t` still
  fail timing-sensitive assertions on the current busy host.
