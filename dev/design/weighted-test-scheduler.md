# Weighted Semantic Test Scheduling

**Status:** Implemented and locally validated

**Date:** 2026-08-17

**Issue:** [#1001](https://github.com/fglock/PerlOnJava/issues/1001)

## Scope

`dev/tools/perl_test_runner.pl` validates TAP results and Perl semantics. It is
not an authoritative timing harness. Performance comparisons use a separate,
controlled procedure, so benchmark elapsed time and benchmark ratios do not
affect this scheduler's resource profiles.

The previous scheduler split the corpus into a normal parallel phase followed
by an exclusive serial phase. Long regex stress tests therefore started only
after the ordinary corpus drained, leaving a long serial tail.

## Policy

The caller's `--jobs` value is a scheduling-unit budget:

- ordinary tests consume one unit;
- known CPU-, memory-, or subprocess-heavy semantic tests consume three units;
- a heavy weight is clamped to the caller's budget, allowing all tests to run
  with `--jobs 1` or `--jobs 2`;
- no current test has an exclusive semantic profile.

The scheduler retains an explicit isolation mechanism in case a future test
demonstrably cannot share a process environment. Such a profile is a barrier:
it waits for active work to finish and cannot be bypassed. Timing sensitivity
alone is not sufficient evidence for isolation.

## Admission Order

All input is known before execution, so the scheduler uses a stable
longest-processing-time heuristic:

1. future exclusive barriers, if any;
2. known heavy tests;
3. ordinary tests.

Original order is preserved within each class, and original test indices remain
attached to results. If the next heavy test cannot fit the remaining budget, a
lighter test may use that capacity. For example, a ten-unit budget admits three
weight-three tests and one ordinary test. Starting long-running work early
reduces the straggler tail while the more uniform short files fill gaps.

## Safety Invariants

- Active scheduling weight never exceeds `--jobs`.
- A positive `--jobs` value is required.
- Capacity is released only after the parent reaps the child.
- Per-test hard timeouts, kill grace, output capture, JSON reporting, and final
  process-group cleanup are unchanged.
- Resource profiles match both Unix and Windows path separators.

Profiles and admission helpers live in
`dev/tools/lib/PerlTestRunner/Scheduler.pm`; focused tests live in
`dev/tools/tests/perl_test_runner_scheduler.t`.

## Tuning

Adjust weights only from semantic-result parity, timeout behavior, peak memory,
CPU pressure, and orphan-process checks on supported platforms. Do not use
`perl_test_runner.pl` elapsed times to make performance claims. A weight change
must retain identical per-file TAP results and must not weaken deadlines or
cleanup behavior.

## Progress Tracking

### Current Status: Implementation complete and locally validated

### Completed Phases

- [x] Scheduler design and implementation (2026-08-17)
  - Replaced normal/exclusive phases with one weighted budget.
  - Added stable long-running-first admission with lighter gap filling.
  - Removed timing-only benchmark scheduling and timeout accommodations.
  - Added policy, capacity, isolation-barrier, low-budget, and Windows-path
    tests.
- [x] Local validation (2026-08-17)
  - `make test-thread-tooling`: 4 files, 35 assertions passed.
  - Weighted runner smoke test: 3 files, 81 assertions passed at `--jobs 1`.
  - `make check-links`: 603 links checked, zero errors.
  - Full `make`: passed all five unit shards and Joni tests.

### Next Steps

1. Verify Linux and Windows CI semantic-result parity and cleanup behavior.
2. Revisit weight three only if cross-platform memory or timeout evidence
   requires adjustment.

### Open Questions

- None. Timing measurement is explicitly owned by a separate procedure.

## Related Documentation

- [Testing guide](../../docs/reference/testing.md)
- [Concurrency and runtime isolation](concurrency.md)
- [PerlOnJava agent test-safety rules](../../AGENTS.md)
