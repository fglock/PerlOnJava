package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class MortalListScalarValueOwnerRestoreTest {
    @Test
    void standaloneScalarSlotRestoresCountWithoutActiveOwnerRegistry() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray referent = new RuntimeArray();
            RuntimeScalar owner = new RuntimeScalar();
            owner.set(referent.createAnonymousReference());
            MyVarCleanupStack.register(owner);

            assertEquals(0, referent.activeOwnerCount(),
                    "the scalar was stored before active-owner tracking existed");
            assertEquals(1, referent.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_VALUE));

            referent.refCount = 0;
            try {
                assertTrue(MortalList.restoreReachableAggregateOwnerCount(referent),
                        "the explicit scalar slot supplies the lost direct owner");
                assertEquals(1, referent.refCount);
            } finally {
                MyVarCleanupStack.unregister(owner);
                MortalList.deferDecrementIfTracked(owner);
                MortalList.flush();
            }
        }
    }
}
