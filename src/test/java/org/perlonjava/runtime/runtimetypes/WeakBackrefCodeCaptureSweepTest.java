package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class WeakBackrefCodeCaptureSweepTest {
    @Test
    void arrayElementOwnerReleaseClearsTheWeakObserver() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray capturedParent = new RuntimeArray();
            RuntimeArray target = new RuntimeArray();
            target.localBindingExists = false;
            capturedParent.addClonedElement(target.createAnonymousReference());

            RuntimeScalar weakTarget = target.createAnonymousReference();
            WeakRefRegistry.weaken(weakTarget);
            try {
                assertEquals(1, target.ownerSlotCount(PerlOwnerSlot.Kind.ARRAY_ELEMENT));
                assertTrue(WeakRefRegistry.isweak(weakTarget),
                        "the exact array element owner preserves its weak observer");
                MortalList.scopeExitCleanupArray(capturedParent);
                MortalList.flush();
                assertEquals(0, target.ownerSlotCount(PerlOwnerSlot.Kind.ARRAY_ELEMENT));
                assertFalse(WeakRefRegistry.isweak(weakTarget),
                        "clearing the final strong Perl slot releases the weak observer: refCount="
                                + target.refCount + ", local=" + target.localBindingExists
                                + ", owners=" + target.ownerSlotSnapshot().stream()
                                        .map(slot -> slot.kind() + ":" + slot.isActive())
                                        .toList());
            } finally {
                if (WeakRefRegistry.isweak(weakTarget)) WeakRefRegistry.unweaken(weakTarget);
            }
        }
    }
}
