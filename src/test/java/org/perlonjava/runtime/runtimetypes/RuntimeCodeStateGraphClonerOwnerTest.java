package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

@Tag("unit")
class RuntimeCodeStateGraphClonerOwnerTest {
    @Test
    void clonedPersistentStateRebuildsIndependentOwnerSlots() {
        PerlRuntime sourceRuntime = new PerlRuntime();
        PerlRuntime targetRuntime = new PerlRuntime();
        RuntimeCode sourceCode;
        RuntimeScalar sourceScalar;
        RuntimeArray sourceArray;
        RuntimeHash sourceHash;
        RuntimeScalar sourceAliasCell;
        RuntimeScalar sourceCodeRef;
        try (PerlRuntime.Binding ignored = sourceRuntime.bind()) {
            sourceCode = new RuntimeCode(null, List.of());
            sourceScalar = new RuntimeScalar();
            sourceScalar.localBindingExists = true;
            sourceArray = new RuntimeArray();
            sourceHash = new RuntimeHash();
            RuntimeArray sourceAliasedArray = new RuntimeArray();
            sourceCode.bindStateScalar("scalar", sourceScalar);
            sourceCode.bindStateArray("array", sourceArray);
            sourceCode.bindStateHash("hash", sourceHash);
            sourceCode.bindStateAggregateAlias("alias", sourceAliasedArray);
            sourceAliasCell = sourceCode.stateAggregateAliasOwners.get("@alias");
            sourceCodeRef = new RuntimeScalar(sourceCode);
        }

        RuntimeScalar clonedCodeRef = (RuntimeScalar) new RuntimeGraphCloner(
                sourceRuntime, targetRuntime).cloneGraph(sourceCodeRef);
        RuntimeCode clonedCode = (RuntimeCode) clonedCodeRef.value;
        RuntimeScalar clonedScalar = clonedCode.stateVariable.get("scalar");
        RuntimeArray clonedArray = clonedCode.stateArray.get("array");
        RuntimeHash clonedHash = clonedCode.stateHash.get("hash");
        RuntimeScalar clonedAliasCell = clonedCode.stateAggregateAliasOwners.get("@alias");

        assertNotSame(sourceCodeRef, clonedCodeRef);
        assertNotSame(sourceScalar, clonedScalar);
        assertNotSame(sourceArray, clonedArray);
        assertNotSame(sourceHash, clonedHash);
        assertNotSame(sourceAliasCell, clonedAliasCell);
        assertEquals(1, sourceScalar.ownerSlotCount(PerlOwnerSlot.Kind.CODE_STATE));
        assertEquals(1, sourceArray.ownerSlotCount(PerlOwnerSlot.Kind.CODE_STATE));
        assertEquals(1, sourceHash.ownerSlotCount(PerlOwnerSlot.Kind.CODE_STATE));
        assertEquals(1, sourceAliasCell.ownerSlotCount(PerlOwnerSlot.Kind.CODE_STATE));
        assertEquals(1, clonedScalar.ownerSlotCount(PerlOwnerSlot.Kind.CODE_STATE));
        assertEquals(1, clonedArray.ownerSlotCount(PerlOwnerSlot.Kind.CODE_STATE));
        assertEquals(1, clonedHash.ownerSlotCount(PerlOwnerSlot.Kind.CODE_STATE));
        assertEquals(1, clonedAliasCell.ownerSlotCount(PerlOwnerSlot.Kind.CODE_STATE));

        PerlRuntime targetBoundRuntime = targetRuntime;
        try (PerlRuntime.Binding ignored = targetBoundRuntime.bind()) {
            assertSame(clonedCode.stateArray.get("alias"), clonedAliasCell.countedOwnerSlot().referent());
            clonedCode.releaseStateStorage();
        }
        try (PerlRuntime.Binding ignored = sourceRuntime.bind()) {
            sourceCode.releaseStateStorage();
        }
    }
}
