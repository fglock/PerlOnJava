package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RuntimeListHashOwnerTransferTest {
    @Test
    void listAssignmentTransfersHashSlotsAndReleasesDisplacedValues() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            boolean previousMortalState = MortalList.isActive();
            RuntimeHash displacedReferent = new RuntimeHash();
            RuntimeScalar displacedCell = new RuntimeScalar();
            displacedCell.set(displacedReferent.createAnonymousReference());
            RuntimeHash destination = new RuntimeHash();
            destination.elements.put("old", displacedCell);

            RuntimeHash incomingReferent = new RuntimeHash();
            RuntimeArray sourceValues = new RuntimeArray();
            sourceValues.elements.add(new RuntimeScalar("new"));
            sourceValues.elements.add(incomingReferent.createAnonymousReference());

            RuntimeList rightHandSide = new RuntimeList();
            rightHandSide.elements.add(new RuntimeScalar(7));
            rightHandSide.elements.add(sourceValues);
            RuntimeList leftHandSide = new RuntimeList();
            leftHandSide.elements.add(new RuntimeScalar());
            leftHandSide.elements.add(destination);

            try {
                MortalList.setActive(true);
                leftHandSide.setFromList(rightHandSide);

                assertNull(destination.elements.get("old"));
                assertNotNull(destination.elements.get("new"));
                assertEquals(0, displacedReferent.hashSlotOwnerCount(),
                        "replacing the hash releases its old entry slot");
                assertEquals(1, incomingReferent.hashSlotOwnerCount(),
                        "the destination's owner-aware map registers the transferred entry");
                assertEquals(1, incomingReferent.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_VALUE));
                assertEquals(1, incomingReferent.refCount,
                        "the destination hash value owns exactly one referent count");

                MortalList.flush();
                assertEquals(0, displacedReferent.countedScalarOwnerCount(),
                        "replacement releases the displaced scalar owner");
                assertTrue(displacedReferent.refCount <= 0);
            } finally {
                MortalList.setActive(previousMortalState);
                MortalList.flush();
            }
        }
    }
}
