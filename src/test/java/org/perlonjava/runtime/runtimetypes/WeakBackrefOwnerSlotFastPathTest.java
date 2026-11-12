package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class WeakBackrefOwnerSlotFastPathTest {
    @Test
    void elementOwnerPreservesWeakBackrefWithoutRootWalk() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray capturedParent = new RuntimeArray();
            RuntimeArray target = new RuntimeArray();
            capturedParent.addClonedElement(target.createAnonymousReference());
            assertEquals(1, target.ownerSlotCount(PerlOwnerSlot.Kind.ARRAY_ELEMENT));

            RuntimeCode weakBackrefCode = new RuntimeCode(null, List.of());
            weakBackrefCode.capturedAggregates = new RuntimeBase[] {capturedParent};
            weakBackrefCode.captureFieldsRecorded = true;
            RuntimeScalar weakCode = new RuntimeScalar(weakBackrefCode);
            WeakRefRegistry.weaken(weakCode);

            RuntimeScalar weakTarget = target.createAnonymousReference();
            WeakRefRegistry.weaken(weakTarget);
            try {
                MortalList.flush();
                assertTrue(WeakRefRegistry.isweak(weakTarget),
                        "the active element owner keeps the weakly observed target alive");
            } finally {
                WeakRefRegistry.unweaken(weakTarget);
                WeakRefRegistry.unweaken(weakCode);
            }
        }
    }
}
