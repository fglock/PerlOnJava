package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class TailCallArgumentOwnerSlotFastPathTest {
    @Test
    void survivingElementOwnerRepairsUndercountedTailCallWithoutRootWalk() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash target = new RuntimeHash();
            target.blessId = NameNormalizer.getBlessId("Issue1649::TailCallOwnerSlot");
            RuntimeArray backing = new RuntimeArray();
            backing.addClonedElement(target.createAnonymousReference());

            RuntimeScalar tailArgument = new RuntimeScalar();
            tailArgument.set(target.createAnonymousReference());
            RuntimeArray tailArguments = new RuntimeArray(1);
            tailArguments.elements.add(tailArgument);
            tailArguments.elementsOwned = true;

            RuntimeScalar weak = target.createAnonymousReference();
            WeakRefRegistry.weaken(weak);
            try {
                assertEquals(2, target.countedOwnerSlotCount());
                target.refCount = 1; // Model a transient legacy-count underflow.

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    MortalList.releaseTailCallArgs(tailArguments);

                    assertEquals(1, target.refCount);
                    assertEquals(1, target.countedOwnerSlotCount());
                    assertTrue(WeakRefRegistry.isweak(weak));
                    assertEquals(0, measurement.stats().rootQueries);
                    assertEquals(0, measurement.stats().snapshotsBuilt);
                }
            } finally {
                WeakRefRegistry.unweaken(weak);
                backing.undefine();
            }
        }
    }
}
