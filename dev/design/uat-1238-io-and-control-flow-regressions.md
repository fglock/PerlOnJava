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

- The runtime working tree contains an uncommitted implementation of
  selected-handle `$=`, `$-`, and `$%` variables, plus focused coverage. It
  needs review and successful validation before being committed.
- A focused cross-subroutine `goto` regression is present in `control_flow.t`.
  The runtime control-flow implementation has not yet been changed.
- UAT remains blocked until both core tests and the final integration gate
  pass.

## Implementation Plan

### Selected output-handle variables

`RuntimeIO` should own page-length, lines-left, and page-number state. Magic
scalar proxies should resolve `$=`, `$-`, and `$%` through the selected output
handle, including dynamic `local` save and restore. Successful format writes
should initialize a page as needed and reduce lines-left by emitted physical
lines.

Next steps:

1. Review proxy lookup, selected-handle switching, and `local` restoration.
2. Validate the focused test on system Perl, then both JVM and interpreter
   backends.
3. Run `io/defout.t` through the Perl test runner and require 22/22.

### Cross-subroutine goto

The JVM backend must consume a `GOTO` control-flow marker at the lexical
file/block boundary where its target label is valid. The change must preserve
loop markers, tail calls, non-local returns, dynamic scope, and destructor
cleanup, and it must not make a label visible outside its lexical scope.

Next steps:

1. Trace the control-flow marker through generated subroutine and enclosing
   block dispatchers.
2. Implement lexical target resolution without changing unrelated control-flow
   dispatch.
3. Validate `control_flow.t` on system Perl and both PerlOnJava backends.
4. Run `op/rt119311.t`; add focused coverage before addressing each newly
   exposed root cause, and require 22/22.

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
