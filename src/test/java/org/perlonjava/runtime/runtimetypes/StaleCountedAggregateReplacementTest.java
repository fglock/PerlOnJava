package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class StaleCountedAggregateReplacementTest {
    @Test
    void replacingDetachedHashElementRetiresAlreadyZeroCountedOwner() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeHash previousReferent = new RuntimeHash();
            RuntimeHash stash = new RuntimeHash();
            stash.put("value", previousReferent.createAnonymousReference());
            RuntimeScalar stashValue = stash.elements.get("value");
            PerlOwnerSlot previousSlot = stashValue.countedOwnerSlot();
            RuntimeHash replacementReferent = new RuntimeHash();
            RuntimeScalar replacementValue = replacementReferent.createAnonymousReference();

            assertEquals(PerlOwnerSlot.Kind.HASH_ELEMENT, previousSlot.kind());
            assertEquals(1, previousReferent.refCount);
            assertTrue(stashValue.refCountOwned);
            assertTrue(previousSlot.isActive());

            // Model a detached scalar cell whose compatibility count reached
            // zero before its old hash-element identity was retired.
            stash.elements = new HashMap<>();
            stashValue.reconcileContainerOwnerAfterMutation(stash);
            previousReferent.setTrackedRefCount(0);
            assertTrue(stashValue.refCountOwned);
            assertTrue(previousSlot.isActive());

            stashValue.set(replacementValue);

            assertEquals(0, previousReferent.ownerSlotCount(
                    PerlOwnerSlot.Kind.HASH_ELEMENT));
            assertFalse(previousSlot.isActive());
            assertEquals(1, replacementReferent.refCount);
            assertEquals(1, replacementReferent.countedOwnerSlotCount());
            assertSame(replacementReferent, stashValue.value);
            assertNotSame(previousSlot, stashValue.countedOwnerSlot());
            assertSame(replacementReferent, stashValue.countedOwnerSlot().referent());
            assertTrue(stashValue.refCountOwned);

            stashValue.set(new RuntimeScalar());
            MortalList.flush();
        }
    }
}
