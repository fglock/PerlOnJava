package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class CorrectiveSweepDefaultPolicyTest {
    @Test
    void finalStrongOwnerReleaseClearsWeakObserverWithoutRootWalk() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            RuntimeArray owner = new RuntimeArray();
            owner.addClonedElement(referent.createAnonymousReference());
            RuntimeScalar weak = referent.createAnonymousReference();
            WeakRefRegistry.weaken(weak);
            try (MortalList.ReachabilityQueryMeasurement measurement =
                         MortalList.measureReachabilityQueries()) {
                MortalList.scopeExitCleanupArray(owner);
                MortalList.flush();

                assertEquals(RuntimeScalarType.UNDEF, weak.type,
                        "the final exact element-owner release clears the weak observer");
                assertEquals(0, measurement.stats().rootQueries,
                        "ordinary weak cleanup does not need a root query");
            }
        }
    }
}
