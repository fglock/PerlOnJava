# Issue #1136: IO::Async `B::SV` Refcount Alignment

**Status:** Phase 1 implemented; CPAN acceptance verification pending

**Issue:** [#1136](https://github.com/fglock/PerlOnJava/issues/1136)

## Problem

IO::Async's loop tests use `B::svref_2object(...)->REFCNT` to assert exact
ownership.  PerlOnJava's selective refcount is deliberately narrower than
Perl's SV refcount, and the `B::SV` wrapper itself stores the inspected
reference in a hash slot.  A compensation intended for DBIx::Class destruction
could run during ordinary probes, causing a singly-owned loop to appear to
have two owners.

The original report also found stale capture ownership after
`$IO::Async::Loop::ONE_TRUE_LOOP->{notifiers}` was removed.  That was a real
owner-lifecycle defect, separate from the `B::SV` presentation adjustment.

## Design

1. Keep the DBIx::Class aggregate-owner compensation only while Perl
   `DESTROY` is executing.  Outside destruction, `B::SV::REFCNT` must report
   normal lexical/global ownership and must not infer an aggregate owner from
   an unregistered lexical.
2. Release every scope-exited captured-pad owner through the ordinary deferred
   decrement path.  Do not transfer that owner merely because the referent is
   temporarily reachable through a package global.
3. Test the observable contract with a loop-shaped lexical plus package-global
   singleton.  The expected count is two before singleton removal and one
   after it, on system Perl and both PerlOnJava backends.

## Progress Tracking

### Current Status: Phase 1 implemented; Phase 2 pending

### Completed Phases

- [x] Phase 1: Correct ownership accounting (2026-09-01)
  - Scoped the B-specific aggregate adjustment to `DESTROY` in
    `Internals.svRefcount()` (commit `3c3ee39d2`).
  - Made discarded captured-pad owners schedule their ordinary decrement in
    `MortalList.releaseCapturedDecrement()` (commit `5889a105e`).
  - Existing focused coverage: `b_refcount_fresh_lexical.t` and
    `captured_global_owner_release.t`.
- [x] Phase 1b: Add the IO::Async singleton lifecycle regression (2026-09-03)
  - Added `unit/refcount/io_async_loop_refcount_lifecycle.t`.

### Next Steps

1. Validate the new test on system Perl, JVM PerlOnJava, and interpreter
   PerlOnJava.
2. Run the focused IO::Async Poll/Select notifier, timer, signal, idle,
   control, and metrics tests with the maintained distropref.
3. Update the historical `t/05notifier-loop.t` compatibility expectation from
   three to the native-Perl count of two, if that patch is still present.
4. Run a bounded full `jcpan -t IO::Async`, then close #1136 only if the
   exact-count failures are gone and DBIx::Class/thread lifecycle regressions
   remain green.

### Open Questions

- Does the current IO::Async 0.805 distropref still contain the stale
  three-owner assertion described in #1136?
- Are the remaining IO::Async CPAN failures independent of refcounting, or
  does an untested notifier teardown path still retain an owner?

## Related Documents

- [Refcount owner ledger](refcount-owner-ledger.md)
- [Reference-count alignment plan](refcount_alignment_plan.md)
- [Weaken and DESTROY architecture](../architecture/weaken-destroy.md)
