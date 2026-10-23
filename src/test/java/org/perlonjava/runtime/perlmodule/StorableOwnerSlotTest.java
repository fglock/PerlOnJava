package org.perlonjava.runtime.perlmodule;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.runtimetypes.PerlRuntime;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@Tag("unit")
class StorableOwnerSlotTest {
    @Test
    void releaseApplyArgsConsumesExplicitOwnerAfterLegacyFlagRetires() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray referent = new RuntimeArray();
            RuntimeScalar owner = new RuntimeScalar();
            owner.set(referent.createAnonymousReference());
            owner.refCountOwned = false;
            RuntimeArray args = new RuntimeArray();
            args.elements.add(owner);
            args.elementsOwned = true;

            Storable.releaseApplyArgs(args);

            assertEquals(0, referent.refCount);
            assertFalse(owner.scalarValueOwnerSlot().isActive());
            assertEquals(0, args.elements.size());
            assertFalse(args.elementsOwned);
        }
    }
}
