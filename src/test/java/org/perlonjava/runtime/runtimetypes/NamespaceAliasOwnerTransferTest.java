package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@Tag("unit")
class NamespaceAliasOwnerTransferTest {
    @Test
    void packageRootIdentitiesFollowMovedNamespaceSlots() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            String sourcePrefix = "NamespaceAliasOwnerTransferTest::Source::";
            String aliasPrefix = "NamespaceAliasOwnerTransferTest::Copy::";
            String replacementPrefix = "NamespaceAliasOwnerTransferTest::Other::";
            String scalarSource = sourcePrefix + "scalar";
            String arraySource = sourcePrefix + "array";
            String hashSource = sourcePrefix + "hash";
            String scalarAlias = aliasPrefix + "scalar";
            String arrayAlias = aliasPrefix + "array";
            String hashAlias = aliasPrefix + "hash";

            RuntimeScalar scalar = new RuntimeScalar("value");
            RuntimeArray array = new RuntimeArray();
            RuntimeHash hash = new RuntimeHash();
            PerlOwnerSlot scalarOwner;
            PerlOwnerSlot arrayOwner;
            PerlOwnerSlot hashOwner;

            try {
                GlobalVariable.globalVariables.put(scalarSource, scalar);
                GlobalVariable.globalArrays.put(arraySource, array);
                GlobalVariable.globalHashes.put(hashSource, hash);
                scalarOwner = runtime.globalState.packageScalarOwnerSlots().get(scalarSource);
                arrayOwner = runtime.globalState.packageArrayOwnerSlots().get(arraySource);
                hashOwner = runtime.globalState.packageHashOwnerSlots().get(hashSource);
                assertNotNull(scalarOwner);
                assertNotNull(arrayOwner);
                assertNotNull(hashOwner);

                GlobalVariable.setStashAlias(aliasPrefix, sourcePrefix);
                GlobalVariable.setStashAlias(sourcePrefix, replacementPrefix);

                assertSame(scalar, GlobalVariable.globalVariables.get(scalarAlias));
                assertSame(array, GlobalVariable.globalArrays.get(arrayAlias));
                assertSame(hash, GlobalVariable.globalHashes.get(hashAlias));
                assertSame(scalarOwner,
                        runtime.globalState.packageScalarOwnerSlots().get(scalarAlias));
                assertSame(arrayOwner,
                        runtime.globalState.packageArrayOwnerSlots().get(arrayAlias));
                assertSame(hashOwner,
                        runtime.globalState.packageHashOwnerSlots().get(hashAlias));
                assertEquals(0, scalar.ownerSlotCount(PerlOwnerSlot.Kind.GLOB_COMPONENT_SLOT));
                assertEquals(0, array.ownerSlotCount(PerlOwnerSlot.Kind.GLOB_COMPONENT_SLOT));
                assertEquals(0, hash.ownerSlotCount(PerlOwnerSlot.Kind.GLOB_COMPONENT_SLOT));

                GlobalVariable.globalVariables.remove(scalarAlias);
                GlobalVariable.globalArrays.remove(arrayAlias);
                GlobalVariable.globalHashes.remove(hashAlias);
                assertFalse(scalarOwner.isActive());
                assertFalse(arrayOwner.isActive());
                assertFalse(hashOwner.isActive());
            } finally {
                GlobalVariable.globalVariables.remove(scalarSource);
                GlobalVariable.globalVariables.remove(scalarAlias);
                GlobalVariable.globalArrays.remove(arraySource);
                GlobalVariable.globalArrays.remove(arrayAlias);
                GlobalVariable.globalHashes.remove(hashSource);
                GlobalVariable.globalHashes.remove(hashAlias);
                GlobalVariable.clearStashAlias(sourcePrefix);
                GlobalVariable.clearStashAlias(aliasPrefix);
            }
        }
    }
}
