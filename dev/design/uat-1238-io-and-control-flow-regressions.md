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

### Current Status: UAT regression remediation complete; awaiting PR review

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

### Next Milestone

1. Commit the validated remediation, update PR #1238, and monitor CI review.
