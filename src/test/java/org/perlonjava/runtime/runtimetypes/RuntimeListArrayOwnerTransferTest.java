package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RuntimeListArrayOwnerTransferTest {
    @Test
    void listAssignmentTransfersOwnedCellsAndReleasesDisplacedArrayValues() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            boolean previousMortalState = MortalList.isActive();
            RuntimeHash displacedReferent = new RuntimeHash();
            RuntimeScalar displacedCell = new RuntimeScalar();
            displacedCell.set(displacedReferent.createAnonymousReference());

            RuntimeArray destination = new RuntimeArray();
            destination.elements.add(displacedCell);
            destination.elementsOwned = true;
            destination.reconcileArrayElementOwnerSlots();

            RuntimeHash incomingReferent = new RuntimeHash();
            RuntimeArray sourceValues = new RuntimeArray();
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

                assertTrue(destination.elementsOwned,
                        "the assigned tail becomes an owning array");
                assertEquals(1, incomingReferent.refCount,
                        "the transferred cell acquires one durable scalar owner");
                assertEquals(1, incomingReferent.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_VALUE));
                assertEquals(1, incomingReferent.arraySlotOwnerCount(),
                        "the destination registers its array element slot");
                assertEquals(0, displacedReferent.arraySlotOwnerCount(),
                        "replacing the old tail retires its array element slot");

                MortalList.flush();
                assertEquals(0, displacedReferent.countedScalarOwnerCount(),
                        "clearing the old tail releases its scalar owner");

                RuntimeArray.pop(destination);
                MortalList.flush();
                assertEquals(0, incomingReferent.arraySlotOwnerCount());
                assertEquals(0, incomingReferent.countedScalarOwnerCount(),
                        "removing the transferred cell releases its owner");
                assertTrue(incomingReferent.refCount <= 0,
                        "the final array removal drains the referent count");
            } finally {
                MortalList.setActive(previousMortalState);
                MortalList.flush();
            }
        }
    }
}
