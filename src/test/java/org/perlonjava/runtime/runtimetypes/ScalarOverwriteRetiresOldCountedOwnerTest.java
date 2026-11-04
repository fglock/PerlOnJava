package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class ScalarOverwriteRetiresOldCountedOwnerTest {
    @Test
    void replacingCountedValueWithUntrackedScalarReferenceRetiresOldSlot() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeHash oldReferent = new RuntimeHash();
            oldReferent.blessId = NameNormalizer.getBlessId(
                    "ScalarOverwriteRetiresOldCountedOwnerTest::Old");

            RuntimeScalar owner = new RuntimeScalar();
            RuntimeScalar oldReference = new RuntimeScalar();
            oldReference.type = RuntimeScalarType.HASHREFERENCE;
            oldReference.value = oldReferent;
            owner.set(oldReference);

            RuntimeScalar weakObserver = new RuntimeScalar(oldReference);
            WeakRefRegistry.weaken(weakObserver);

            PerlOwnerSlot oldSlot = owner.countedOwnerSlot();
            assertTrue(oldSlot != null && oldSlot.isActive());
            assertEquals(1, oldReferent.refCount);

            RuntimeScalar scalarCell = new RuntimeScalar(42);
            RuntimeScalar replacement = new RuntimeScalar();
            replacement.type = RuntimeScalarType.REFERENCE;
            replacement.value = scalarCell;
            assertEquals(-1, scalarCell.refCount,
                    "the referenced scalar cell is an untracked referent");

            owner.set(replacement);

            assertFalse(oldSlot.isActive(),
                    "the old counted slot is retired when this scalar stops owning its referent");
            assertEquals(0, oldReferent.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_CELL));
            assertEquals(Integer.MIN_VALUE, oldReferent.refCount,
                    "the old referent reaches final destruction after its last counted owner leaves");
            assertEquals(RuntimeScalarType.UNDEF, weakObserver.type,
                    "final release clears weak observers immediately");
        }
    }
}
