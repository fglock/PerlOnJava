package org.perlonjava.runtime.io;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.runtimetypes.PerlOwnerSlot;
import org.perlonjava.runtime.runtimetypes.PerlRuntime;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;
import org.perlonjava.runtime.runtimetypes.WeakRefRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class ViaLayerCountedOwnerLifecycleTest {
    @Test
    void releasingLayerDoesNotDestroyReferentWithLiveClosurePadOwner() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeArray referent = new RuntimeArray();
            referent.refCount = 0;
            PerlOwnerSlot captureOwner = referent.retainClosureCaptureOwner();

            RuntimeScalar pushed = new RuntimeScalar();
            pushed.type = RuntimeScalarType.ARRAYREFERENCE;
            pushed.value = referent;
            ViaLayer.SelfReferenceOwner layerOwner = ViaLayer.SelfReferenceOwner.retain(pushed);

            RuntimeScalar weakObserver = new RuntimeScalar();
            weakObserver.type = RuntimeScalarType.ARRAYREFERENCE;
            weakObserver.value = referent;
            WeakRefRegistry.weaken(weakObserver);

            try {
                layerOwner.release();

                assertTrue(captureOwner.isActive());
                assertTrue(WeakRefRegistry.isweak(weakObserver),
                        "the closure-pad owner keeps weak observers alive after POPPED");
                assertEquals(0, referent.refCount,
                        "the layer's paired count is released without destroying the live pad");
            } finally {
                if (captureOwner.isActive()) referent.releaseClosureCaptureOwner(captureOwner);
                WeakRefRegistry.clearWeakRefsTo(referent);
            }
        }
    }
}
