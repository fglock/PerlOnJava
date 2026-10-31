package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class TiedHandlerNestedOwnerSlotRepairTest {
    @Test
    void nestedHandlerHashSlotRepairsDeferredUnderCountWithoutRootWalk() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash handler = new RuntimeHash();
            RuntimeHash target = new RuntimeHash();
            handler.put("target", target.createAnonymousReference());

            RuntimeHash tied = new RuntimeHash();
            tied.type = RuntimeHash.TIED_HASH;
            tied.elements = new TieHash("Issue1649::TiedOwner", new RuntimeHash(),
                    handler.createAnonymousReference());

            RuntimeScalar released = new RuntimeScalar();
            released.set(target.createAnonymousReference());
            RuntimeScalar weak = target.createAnonymousReference();
            WeakRefRegistry.weaken(weak);

            try {
                assertEquals(1, handler.ownerSlotCount(PerlOwnerSlot.Kind.TIE_HANDLER));
                assertEquals(1, target.ownerSlotCount(PerlOwnerSlot.Kind.HASH_ELEMENT));
                assertTrue(target.possiblyStoredInTiedHandler);

                target.refCount = 1; // Model a legacy count missing the tied hash owner.
                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    MortalList.deferDecrementIfTracked(released);
                    MortalList.flushAboveMark();

                    assertEquals(1, target.refCount);
                    assertTrue(WeakRefRegistry.isweak(weak));
                    assertEquals(1, target.ownerSlotCount(PerlOwnerSlot.Kind.HASH_ELEMENT));
                    assertEquals(0, measurement.stats().rootQueries);
                    assertEquals(0, measurement.stats().snapshotsBuilt);
                }

                handler.elements.get("target").set(new RuntimeScalar());
                assertFalse(WeakRefRegistry.isweak(weak));
                assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED,
                        target.perlLifecycleState());
            } finally {
                if (handler.elements.containsKey("target")) {
                    handler.elements.get("target").set(new RuntimeScalar());
                }
                if (WeakRefRegistry.isweak(weak)) WeakRefRegistry.unweaken(weak);
                released.set(new RuntimeScalar());
                ((TieHash) tied.elements).releaseTiedObject();
            }
        }
    }
}
