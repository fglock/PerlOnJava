package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class MortalListOwnerSlotLedgerAuthorityTest {
    @Test
    void rootedAggregateSlotsUseScalarOwnerIdentityAfterLegacyFlagRetires() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray referent = new RuntimeArray();
            RuntimeArray arrayOwner = new RuntimeArray();
            arrayOwner.elementsOwned = true;
            RuntimeScalar arrayCell = referenceTo(referent);
            arrayCell.setRefCountOwned(true);
            arrayCell.refCountOwned = false;
            arrayOwner.elements.add(arrayCell);
            arrayOwner.reconcileArrayElementOwnerSlots();

            RuntimeHash hashOwner = new RuntimeHash();
            RuntimeScalar hashCell = referenceTo(referent);
            hashCell.setRefCountOwned(true);
            hashCell.refCountOwned = false;
            hashOwner.elements.put("target", hashCell);

            MyVarCleanupStack.register(arrayOwner);
            MyVarCleanupStack.register(hashOwner);
            try {
                assertTrue(arrayCell.hasScalarValueOwnerSlotFor(referent));
                assertTrue(hashCell.hasScalarValueOwnerSlotFor(referent));

                assertTrue(MortalList.restoreReachableAggregateOwnerCount(referent),
                        "rooted array and hash slots restore owners from ledger identity");
                assertEquals(2, referent.refCount);
                assertEquals(2, referent.reachableOwnerCount());

                arrayOwner.elements.clear();
                hashOwner.elements.clear();
                arrayCell.scalarValueOwnerSlot().release();
                hashCell.scalarValueOwnerSlot().release();
                referent.refCount = 0;
                assertFalse(MortalList.restoreReachableAggregateOwnerCount(referent),
                        "removing the semantic slots removes the recoverable owners");
            } finally {
                MyVarCleanupStack.unregister(hashOwner);
                MyVarCleanupStack.unregister(arrayOwner);
                hashOwner.elements.clear();
                arrayOwner.elements.clear();
                arrayCell.scalarValueOwnerSlot().release();
                hashCell.scalarValueOwnerSlot().release();
            }
        }
    }

    private static RuntimeScalar referenceTo(RuntimeArray referent) {
        RuntimeScalar scalar = new RuntimeScalar();
        scalar.type = RuntimeScalarType.ARRAYREFERENCE;
        scalar.value = referent;
        return scalar;
    }
}
