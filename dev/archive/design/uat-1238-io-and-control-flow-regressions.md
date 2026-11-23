# UAT #1238: I/O and Control-Flow Follow-up

## Scope

This follow-up covers the two UAT failures in Perl 5 core tests:

- `io/defout.t`: selected-output-handle format variables must preserve Perl 5
  defaults and handle-local state.
- `op/rt119311.t`: `goto LABEL` from a subroutine must resume at a valid
  enclosing lexical label.

Imported Perl 5 tests remain unchanged. Each root cause requires focused,
project-owned regression coverage.

## Current Handoff

- Selected-handle format state, cross-subroutine control dispatch, and
  write-time multi-line format blocks are implemented.
- `io/defout.t` emits 22/22 passing assertions and `op/rt119311.t` passes
  22/22 on both execution backends.
- `control_flow.t`, `socket_options.t`, and `tie_handle.t` are covered by the
  clean full unit gate.

## Implementation Plan

### Selected output-handle variables

`RuntimeIO` should own page-length, lines-left, and page-number state. Magic
scalar proxies should resolve `$=`, `$-`, and `$%` through the selected output
handle, including dynamic `local` save and restore. Successful format writes
should initialize a page as needed and reduce lines-left by emitted physical
lines.

Next steps:

1. Re-run the integration gate from PR #1238 and monitor CI.

### Cross-subroutine goto

The JVM backend must consume a `GOTO` control-flow marker at the lexical
file/block boundary where its target label is valid. The change must preserve
loop markers, tail calls, non-local returns, dynamic scope, and destructor
cleanup, and it must not make a label visible outside its lexical scope.

Next steps:

1. Re-run the integration gate from PR #1238 and monitor CI.

## Acceptance Criteria

1. New or strengthened project-owned tests pass on system Perl, JVM, and
   interpreter backends.
2. `io/defout.t` and `op/rt119311.t` each pass 22/22 through the Perl test
   runner.
3. Both UAT files pass together.
4. A clean, isolated `make` passes after the implementation commits are in
   place.
5. `make check-links` passes for this design document.
6. PR #1238 is updated only after the source gates pass; then UAT can start.

## Constraints

- Do not modify imported Perl 5 tests.
- Wrap every `jperl`, `jcpan`, and `prove` invocation in `timeout` and capture
  full output to a file.
- Do not mutate the checkout while a build or test gate is running.

## Progress Tracking

### Current Status: Original UAT regressions recovered; authoritative full comparison pending

### Completed Work

- [x] Selected-output format state and selected-glob regression coverage.
- [x] Cross-subroutine `goto`, `last`, `next`, and `redo` dispatch.
- [x] Write-time multi-line format argument blocks for recursive `DESTROY`.
- [x] Bytecode `send`/`recv` and tied `print` result propagation.
- [x] JVM lexical `goto` dispatcher and control-flow dispatcher scoping
  (2026-09-03).
- [x] Perl test runner accounting for TAP assertions appended to deliberate
  format output (2026-09-03).
- [x] Local acceptance gates (2026-09-03): `make`, `make check-links`, both
  UAT files at 22/22 together, and explicit interpreter UAT runs.
- [x] PR #1238 CI (2026-09-03): Ubuntu and Windows builds, including their
  focused thread compatibility gates, passed.
- [x] Interpreter list/localization, array-last-index lvalue, tied coderef,
  postderef, hash-capacity, multidimensional-hash, and non-finite-repeat
  regressions isolated from the UAT report (2026-09-04).
- [x] Interpreter regex state across `redo` in `while` loops: retain one
  loop-level snapshot and restore its durable regex-only checkpoint after
  discarding skipped nested block snapshots (2026-09-04). Regression coverage:
  `interpreter_our_undef_list_placeholder.t`; system Perl, JVM, interpreter,
  and a clean `make` pass. This restores `op/while.t` assertion 15.
- [x] Interpreter `while` control-flow parity: run `continue` after the body
  scope has unwound, preserve regex checkpoints across nested skipped scopes,
  and return the terminal condition in scalar context while evaluating loop
  bodies in void context (2026-09-04). The focused project-owned regression
  suite has 24 passing assertions on system Perl, JVM, and interpreter; the
  forced-interpreter upstream `op/while.t` result is now 23/26, with only its
  pre-existing capture assertions remaining.
- [x] File-test cache parity for stacked `-e -t HANDLE` (2026-09-04): `-t`
  invalidates the cached stat result rather than restatting a handle's path.
  The project-owned suite has 26 passing assertions on system Perl, JVM, and
  interpreter; imported `op/filetest_t.t` passes 7/7 on both backends.
- [x] Final full UAT comparison (2026-09-04): 673,808 total tests and 669,517
  passing, a net gain of 1,657 against the #1238 baseline; zero regressions.
  The excluded six-test `win32/seekdir.t` variation is the established
  platform flake.
- [x] Recovered the UAT control-flow and I/O rows (2026-09-05): `op/pack.t`
  is back to 14,699/14,726, `op/tie_fetch_count.t` to 219/347, `op/chr.t` to
  45/45, `op/goto.t` to 15/87 (above its 14/87 reference), `op/tie.t` to
  72/95 (above its 71/95 reference), and `io/argv.t` to 22/53. Added focused
  system-Perl, JVM, and interpreter coverage for string increment pointer
  warnings, tied range fetches, `chr` overload evaluation, missing top-level
  labels, tied magic `goto`, and runtime-readonly `tie`.
- [x] Final local source and tool gate (2026-09-05): a clean `make` passed
  after the control-flow, readonly-tie, and TAP-accounting changes.
- [x] Original UAT row verification (2026-09-05): the seven affected imported
  files run sequentially from the final JAR at or above their references:
  `pack` 14,699/14,726, `fork` 14/28, `tie` 75/95,
  `tie_fetch_count` 219/347, `argv` 22/53, `goto` 15/87, and `chr` 45/45.
  The fork run still reports some capacity-related child failures, but its
  passing count matches the reference exactly.
- [x] Callback-to-enclosing-label `goto` recovery (2026-09-05): unresolved
  callback labels now propagate their control-flow marker to the enclosing
  lexical dispatcher rather than becoming an immediate missing-label error.
  Added `goto_callback_outer_label.t`; it passes on system Perl, JVM, and
  interpreter. This restores `op/rt119311.t` from 4/22 to 22/22 on both
  backends. A clean `make` passed after the change.
- [x] Orphaned lexical array-last-index references (2026-09-05): a reference
  to `$#array` no longer keeps a lexical array live after scope exit. Added
  `array_last_index_orphan.t`, passing on system Perl, JVM, and interpreter.
  `op/array.t` is now 185/199, above the 182/199 UAT reference.

### Next Milestone

1. Run the authoritative full UAT comparison from the immutable final build.
2. Commit the validated remediation and update PR #1238.
