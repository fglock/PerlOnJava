# Walker blind spot in `MortalList.maybeAutoSweep()`

Investigation sandbox for the bug documented in
`dev/modules/dbix_class.md` "Investigation Plan: Schema-Detached Bug
in t/52leaks.t (line 430)".

## Summary

Under `./jcpan -t DBIx::Class`, `t/52leaks.t` occasionally throws
`Unable to perform storage-dependent operations with a detached
result source` mid-test. We confirmed it is caused by
`MortalList.maybeAutoSweep()` (5-s throttled) clearing the weak ref
from `ResultSource → Schema` even though the test scope still holds
a strong ref to the Schema via `my $schema = DBICTest->init_schema()`.

`JPERL_NO_AUTO_GC=1` removes the crash but exposes 14/23 leak-detection
failures (the existing tests 12-18 issues), so the fix is NOT to
disable the sweep.

## Reproducer attempts

`simple_lexical_repro.t` — minimal Schema/ResultSource pair with one
weakened back-ref. Holds `my $schema` and burns >5s of wall clock so
the auto-sweep timer fires multiple times.

**Status: passes both with and without `JPERL_NO_AUTO_GC=1`.** The
walker correctly traces the simple `my $schema` lexical. The DBIC
failure must depend on a more complex pattern — possibly the schema
being captured into a closure/temporary during one of the accessor
chain steps, or held only via a HashSpecialVariable / refCountOwned
flag the walker happens to skip in that moment.

## Next steps (for whoever picks this up)

1. Add diagnostic logging into `ReachabilityWalker.sweepWeakRefs()`
   so that **every weak-ref clear** prints the cleared object's
   classname + `findPathTo(target)` output. Run the full DBIC suite
   with that on; find the first clear that hits a Schema object;
   use the path to identify which seeding gate dropped it.

2. Look at `ReachabilityWalker.walk()` Phase 2 lines 111-153: the
   ScalarRefRegistry seed loop has guards on `captureCount > 0`,
   `WeakRefRegistry.isweak`, `MortalList.isDeferredCapture`,
   `MyVarCleanupStack.isLive`, `scopeExited`, `refCountOwned`. Some
   subset of those is incorrectly excluding the test-scope `$schema`
   in DBIC's specific call pattern.

3. Build the *failing* reproducer by mirroring DBIC's pattern more
   precisely — passing the schema through method dispatch (which
   creates `@_` temporaries and JVM-stack temporaries), via a chain
   of accessor closures.

## Pointers

- `src/main/java/org/perlonjava/runtime/runtimetypes/ReachabilityWalker.java`
- `src/main/java/org/perlonjava/runtime/runtimetypes/MortalList.java` — `maybeAutoSweep()`
- `src/main/java/org/perlonjava/runtime/runtimetypes/MyVarCleanupStack.java`
- `src/main/java/org/perlonjava/runtime/runtimetypes/ScalarRefRegistry.java`
- Disable while debugging: `JPERL_NO_AUTO_GC=1`
- Trace mode: `JPERL_GC_DEBUG=1`
