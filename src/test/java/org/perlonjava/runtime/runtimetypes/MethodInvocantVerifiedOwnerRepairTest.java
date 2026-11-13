package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class MethodInvocantVerifiedOwnerRepairTest {
    @Test
    void directMethodHoldParticipatesInVerifiedCountRestoration() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeHash target = new RuntimeHash();
            target.blessId = NameNormalizer.getBlessId("Issue1649::MethodOwnerRepair");
            RuntimeScalar source = new RuntimeScalar();
            source.set(target.createAnonymousReference());
            RuntimeBase hold = RuntimeCode.acquireMethodInvocantHold(source);

            try {
                assertEquals(1, target.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_VALUE));
                assertTrue(target.ownerSlotSnapshot().stream()
                        .filter(slot -> slot.kind() == PerlOwnerSlot.Kind.ARGUMENT_VALUE)
                        .anyMatch(slot -> slot.isMethodInvocantOwner(target)));

                // Exercise the same repair boundary as deferred and tail-call
                // release without fabricating a scalar storage cell for the hold.
                target.refCount = 0;
                assertTrue(target.restoreVerifiedCurrentCountedOwnerSlotRefCount());
                assertEquals(1, target.refCount);
            } finally {
                RuntimeCode.releaseAbandonedMethodInvocantHold(hold);
                source.set(RuntimeScalarCache.scalarUndef);
            }
        }
    }
}
