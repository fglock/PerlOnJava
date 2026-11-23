package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class OwnerSlotCandidateFastPathTest {
    @Test
    void temporaryRootDoesNotTriggerPerlOwnerLedgerScan() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeArray referent = new RuntimeArray();
            PerlOwnerSlot temporaryRoot = new PerlOwnerSlot(
                    PerlOwnerSlot.Kind.TEMPORARY_ROOT);
            temporaryRoot.acquireTemporaryRootIdentity(referent);

            assertFalse(referent.hasCurrentPerlOwnerSlotIdentity());
            assertTrue(referent.hasTemporaryRootOwnerSlotIdentity());

            temporaryRoot.release();
            assertFalse(referent.hasCurrentPerlOwnerSlotIdentity());
        }
    }

    @Test
    void indexedPerlOwnerCandidateStillUsesCurrentSlotValidation() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeArray referent = new RuntimeArray();
            PerlOwnerSlot packageOwner = new PerlOwnerSlot(
                    PerlOwnerSlot.Kind.PACKAGE_AGGREGATE_SLOT);
            packageOwner.acquirePackageAggregateIdentity(referent);

            assertTrue(referent.hasCurrentPerlOwnerSlotIdentity());

            packageOwner.release();
            assertFalse(referent.hasCurrentPerlOwnerSlotIdentity());
        }
    }
}
