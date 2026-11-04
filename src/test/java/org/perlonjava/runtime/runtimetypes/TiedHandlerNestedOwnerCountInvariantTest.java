package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class TiedHandlerNestedOwnerCountInvariantTest {
    @Test
    void nestedValueStoredBeforeFirstWeakenKeepsCountAlignedWithLiveSlots() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash target = new RuntimeHash();
            RuntimeHash handler = new RuntimeHash();
            handler.put("target", target.createAnonymousReference());

            RuntimeHash tied = new RuntimeHash();
            tied.type = RuntimeHash.TIED_HASH;
            tied.elements = new TieHash("TiedHandlerOwnerCount::Tie", new RuntimeHash(),
                    handler.createAnonymousReference());

            assertEquals(1, target.refCount);
            assertEquals(1, target.countedOwnerSlotCount());
            assertEquals(1, target.ownerSlotCount(PerlOwnerSlot.Kind.HASH_ELEMENT));
            assertEquals(1, handler.ownerSlotCount(PerlOwnerSlot.Kind.TIE_HANDLER));

            RuntimeScalar strong = new RuntimeScalar();
            strong.set(target.createAnonymousReference());
            RuntimeScalar weak = new RuntimeScalar();
            weak.set(strong);
            try {
                WeakRefRegistry.weaken(weak);

                assertEquals(2, target.refCount,
                        "the nested hash edge and strong scalar each contribute one count");
                assertEquals(target.refCount, target.countedOwnerSlotCount());
                assertEquals(1, target.ownerSlotCount(PerlOwnerSlot.Kind.HASH_ELEMENT));
                assertEquals(1, target.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_CELL));

                strong.undefine();
                assertEquals(1, target.refCount,
                        "releasing the scalar owner leaves the nested handler edge counted");
                assertTrue(WeakRefRegistry.isweak(weak));

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    ((TieHash) tied.elements).releaseTiedObject();

                    assertEquals(Integer.MIN_VALUE, target.refCount,
                            "untie destroys the handler and releases its final nested value edge");
                    assertEquals(0, target.countedOwnerSlotCount());
                    assertEquals(RuntimeScalarType.UNDEF, weak.type,
                            "the final owner release clears the weak observer");
                    assertEquals(0, measurement.stats().rootQueries);
                    assertEquals(0, measurement.stats().snapshotsBuilt);
                }
            } finally {
                ((TieHash) tied.elements).releaseTiedObject();
                if (WeakRefRegistry.isweak(weak)) WeakRefRegistry.unweaken(weak);
                if (strong.refCountOwned) strong.undefine();
            }
        }
    }
}
