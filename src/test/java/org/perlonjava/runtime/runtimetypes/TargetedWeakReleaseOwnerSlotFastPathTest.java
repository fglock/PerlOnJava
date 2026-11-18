package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class TargetedWeakReleaseOwnerSlotFastPathTest {
    @Test
    void activeElementOwnerKeepsWeakObserverUntilOwnerRelease() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray owner = new RuntimeArray();
            RuntimeArray target = new RuntimeArray();
            owner.addClonedElement(target.createAnonymousReference());
            RuntimeScalar weak = target.createAnonymousReference();
            WeakRefRegistry.weaken(weak);

            try {
                assertEquals(1, target.ownerSlotCount(PerlOwnerSlot.Kind.ARRAY_ELEMENT));
                MortalList.flush();
                assertTrue(WeakRefRegistry.isweak(weak),
                        "the active element slot keeps the target's weak observer intact");
                assertTrue(target.refCount > 0,
                        "the element's strong owner remains counted");
            } finally {
                WeakRefRegistry.unweaken(weak);
            }
        }
    }
}
