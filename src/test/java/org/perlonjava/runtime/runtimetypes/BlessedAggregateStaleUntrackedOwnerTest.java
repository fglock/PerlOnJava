package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Tag("unit")
class BlessedAggregateStaleUntrackedOwnerTest {
    @Test
    void detachedArgumentCellDoesNotKeepBlessedAggregateAlive() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeHash target = new RuntimeHash();
            target.blessId = NameNormalizer.getBlessId("Local::BlessedAggregate");

            RuntimeArray detachedArguments = new RuntimeArray();
            RuntimeScalar staleCell = target.createAnonymousReference();
            detachedArguments.elements.add(staleCell);
            PerlOwnerSlot staleOwner = staleCell.untrackedReferenceOwnerSlot();

            assertNotNull(staleOwner);
            assertFalse(staleCell.hasLiveDurableStrongValueSlot());
            assertFalse(target.hasNonPackageAggregateUncountedOwnerSlotIdentity(),
                    "a stale cell in an unowned argument array is not a Perl owner");
            assertFalse(staleOwner.isActive(),
                    "final-owner inspection retires the detached reference slot");
        }
    }
}
