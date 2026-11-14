package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class MethodInvocantOwnerSlotTest {
    @Test
    void directMethodHoldAddsAndReleasesOneCountedOwner() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeHash target = new RuntimeHash();
            target.blessId = NameNormalizer.getBlessId("Issue1649::MethodOwnerRepair");
            RuntimeScalar source = new RuntimeScalar();
            source.set(target.createAnonymousReference());
            RuntimeBase hold = RuntimeCode.acquireMethodInvocantHold(source);

            assertEquals(2, target.refCount,
                    "the scalar cell and direct method hold each add one owner count");
            assertEquals(1, target.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_VALUE));
            assertTrue(target.ownerSlotSnapshot().stream()
                    .filter(slot -> slot.kind() == PerlOwnerSlot.Kind.ARGUMENT_VALUE)
                    .anyMatch(slot -> slot.isMethodInvocantOwner(target)));

            RuntimeCode.releaseAbandonedMethodInvocantHold(hold);
            assertEquals(1, target.refCount,
                    "releasing the direct method hold retires only its own count");
            source.set(RuntimeScalarCache.scalarUndef);
        }
    }
}
