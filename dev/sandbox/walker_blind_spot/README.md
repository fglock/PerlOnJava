# Walker blind spot in `MortalList.maybeAutoSweep()`

Investigation sandbox for the bug documented in
`dev/modules/dbix_class.md` "Investigation Plan: Schema-Detached Bug
in t/52leaks.t (line 430)".

## Summary

Under `./jcpan -t DBIx::Class`, `t/52leaks.t` occasionally throws
`Unable to perform storage-dependent operations with a detached
result source` mid-test. We confirmed it is caused by
`MortalList.maybeAutoSweep()` (5-s throttled) clearing the weak ref
from `ResultSource → Schema`.

`JPERL_NO_AUTO_GC=1` removes the crash but exposes 14/23 leak-detection
failures (the existing tests 12-18 issues), so the fix is NOT to
disable the sweep.

## Reproducer attempts (all PASS — none reproduce the actual bug)

| File | Pattern | Result |
|---|---|---|
| `simple_lexical_repro.t` | 1 schema, 1 result-source, 1 weakened back-ref, busy loop > 5 s | PASS in both default and JPERL_NO_AUTO_GC modes |
| `lexical_scalar_root_PASSES.t` | `my $obj = bless` lexical + weakened back-ref, JPERL_FORCE_SWEEP_EVERY_FLUSH=1 + 20× Internals::jperl_gc() | PASS — walker seeds the lexical correctly |
| `dbic_real_pattern_PASSES.t` | DBIC-shape: schema in global %REGISTRY, RS chain via `$phantom`, JPERL_FORCE_SWEEP_EVERY_FLUSH=1 | PASS — walker traces the global path correctly |

**Conclusion**: the walker correctly seeds both `my $scalar = $ref`
lexicals AND globally-registered schemas. The actual DBIC blind spot is
elsewhere — likely tied to Moo/Class::C3::XS/MRO interaction, DBIC's
accessor-magic for `_result_source`, Storable's seen-table inflating
refcounts during `dclone`, or some other DBIC-specific structural cycle.

## How to find the actual bug

Don't speculate further. Use the diagnostic infrastructure now in PR #635:

1. **`JPERL_FORCE_SWEEP_EVERY_FLUSH=1`** — landed in this PR.
   Bypasses the 5-s sweep throttle so timing-dependent races trigger
   on every statement boundary.

2. **Add `JPERL_WALKER_TRACE=1`** (next investigator's job).
   Instrument `ReachabilityWalker.sweepWeakRefs()` so every cleared
   weak ref logs target identity + `findPathTo()` output + which
   seeding sources were active.

3. Run `JPERL_FORCE_SWEEP_EVERY_FLUSH=1 JPERL_WALKER_TRACE=1
   ./jcpan -t DBIx::Class` and inspect the first cleared
   Schema/ResultSource. The path-not-found tells you exactly which
   seeding gate dropped it.

## Pointers

- `src/main/java/org/perlonjava/runtime/runtimetypes/ReachabilityWalker.java`
- `src/main/java/org/perlonjava/runtime/runtimetypes/MortalList.java` — `maybeAutoSweep()`
- `src/main/java/org/perlonjava/runtime/runtimetypes/MyVarCleanupStack.java`
- `src/main/java/org/perlonjava/runtime/runtimetypes/ScalarRefRegistry.java`
- Disable while debugging: `JPERL_NO_AUTO_GC=1`
- Force sweep on every flush: `JPERL_FORCE_SWEEP_EVERY_FLUSH=1`
- Trace mode (existing): `JPERL_GC_DEBUG=1`
