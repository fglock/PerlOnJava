# Parser compatibility issue batch (October 2026)

## Goal

Resolve the five remaining high impact reports labeled `area:parser`:

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

### Current status: implementation and validation in progress

### Completed

- [x] Confirm the working tree was clean and create the feature branch.
- [x] Open draft [PR #1623](https://github.com/fglock/PerlOnJava/pull/1623).
- [x] Close #1466 and #1476 after verifying their reproductions on system Perl,
  JVM, and interpreter backends; add permanent regression coverage.
- [x] Capture unfixed-parent failures and add system-Perl-validated tests for
  ascending versions, prototype diagnostics, qualified constructor arguments,
  and incremental HTML entity/whitespace boundaries.
- [x] Implement source changes for #1615, #1619, #1622, and #1166.
- [x] Confirm #1470's code changes already landed in merged PR #1598; a current
  AnyEvent::Tools run still shows a sub-millisecond timer assertion failure, so
  leave the issue open pending a stable integration pass.

### Next steps

1. Build once after the full implementation batch and run all focused tests on
  both backends.
2. Run HTML::Tree integration and recheck AnyEvent::Tools timing assertions.
3. Run `nice -n 19 make`, push the final batch, and update PR #1623 with
   verified results.

### Open questions

- AnyEvent::Tools timing assertions have small margins and varied between
  runs. Determine whether #1470 is stable before closing it.
