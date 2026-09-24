# Issue 1508: Clone deferred strings

## Problem

`Dist::Zilla::Plugin::TrialVersionComment` clones a parsed PPI document before
editing its `$VERSION` declaration. PerlOnJava's Java implementation of
`Clone::clone` copied only a scalar's raw `type` and `value` fields. When the
scalar held deferred string concatenation, the raw value was stale and the
clone lost its content. The plugin consequently emitted a malformed version
declaration and the CPAN test run timed out.

## Resolution

Use `RuntimeScalar`'s copy constructor for non-reference scalar values in the
Java Clone module. The constructor materializes deferred string concatenation
before copying the scalar payload.

## Regression coverage

`src/test/resources/unit/clone.t` now verifies that `Clone::clone` preserves a
string built by concatenating through a hash slot. The test is compatible with
standard Perl and passed on system Perl, the JVM backend, and the interpreter.

## Progress Tracking

### Current Status: Complete (2026-09-24)

### Completed Phases

- [x] Root-cause analysis
  - Identified Java `Clone::clone` bypassing deferred-string materialization.
- [x] Implementation and regression coverage
  - Updated `src/main/java/org/perlonjava/runtime/perlmodule/Clone.java`.
  - Added coverage in `src/test/resources/unit/clone.t`.
- [x] Validation
  - System Perl regression test, both PerlOnJava backends, and `make` pass.
  - `Dist-Zilla-Plugin-TrialVersionComment-0.007` completed: 15 files and 30
    tests passed; six optional-plugin tests skipped as expected.

### Next Steps

1. Review and merge the issue #1508 fix.

### Open Questions

None.
