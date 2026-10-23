package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class MyVarCleanupStackOwnerSlotTest {
    @Test
    void retainingBlessedGlobAliasDoesNotDuplicateRetiredLegacyOwner() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeGlob glob = new RuntimeGlob("OwnerSlotGlob::HANDLE");
            RuntimeScalar lexicalAlias = new RuntimeScalar();
            lexicalAlias.type = RuntimeScalarType.GLOB;
            lexicalAlias.value = glob;
            lexicalAlias.setRefCountOwned(true);
            lexicalAlias.refCountOwned = false;
            MyVarCleanupStack.register(lexicalAlias);
            try {
                glob.refCount = 4;

                MyVarCleanupStack.retainBlessedGlobAliases(glob, glob);

                assertEquals(4, glob.refCount);
                assertTrue(lexicalAlias.scalarValueOwnerSlot().isActive());
            } finally {
                MyVarCleanupStack.unregister(lexicalAlias);
            }
        }
    }
}
