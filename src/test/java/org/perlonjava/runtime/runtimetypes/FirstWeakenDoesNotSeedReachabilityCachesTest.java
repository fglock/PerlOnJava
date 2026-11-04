package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class FirstWeakenDoesNotSeedReachabilityCachesTest {
    @Test
    void firstWeakenUsesRegisteredLexicalStateWithoutSeedingWalkerCaches() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar weak = new RuntimeHash().createAnonymousReference();
            MyVarCleanupStack.register(weak);
            try {
                assertTrue(MyVarCleanupStack.isRegistered(weak));
                assertTrue(MyVarCleanupStack.isLive(weak));

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    WeakRefRegistry.weaken(weak);

                    assertTrue(WeakRefRegistry.isweak(weak));
                    assertTrue(MyVarCleanupStack.isRegistered(weak));
                    assertTrue(MyVarCleanupStack.isLive(weak),
                            "the walker reads the active lexical registration directly");
                    assertEquals(0, measurement.stats().rootQueries);
                    assertEquals(0, measurement.stats().snapshotsBuilt);
                }
            } finally {
                if (WeakRefRegistry.isweak(weak)) WeakRefRegistry.unweaken(weak);
                MyVarCleanupStack.unregister(weak);
            }
        }
    }
}
