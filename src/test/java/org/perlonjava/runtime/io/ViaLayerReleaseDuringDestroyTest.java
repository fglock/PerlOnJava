package org.perlonjava.runtime.io;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.runtimetypes.PerlRuntime;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
class ViaLayerReleaseDuringDestroyTest {
    @Test
    void countedLayerHoldBalancesWhileReferentIsAlreadyDestroying() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeArray referent = new RuntimeArray();
            referent.refCount = 0;
            RuntimeScalar pushed = new RuntimeScalar();
            pushed.type = RuntimeScalarType.ARRAYREFERENCE;
            pushed.value = referent;
            ViaLayer.SelfReferenceOwner owner = ViaLayer.SelfReferenceOwner.retain(pushed);

            referent.currentlyDestroying = true;
            try {
                owner.release();
                assertEquals(0, referent.refCount,
                        "the retiring layer hold must not look like resurrection");
            } finally {
                referent.currentlyDestroying = false;
                owner.release();
            }
        }
    }
}
