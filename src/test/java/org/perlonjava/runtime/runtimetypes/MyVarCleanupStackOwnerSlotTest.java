package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class MyVarCleanupStackOwnerSlotTest {
    @Test
    void retainingTrackedBlessedGlobAliasAcquiresCountThroughScalarSlot() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeGlob glob = new RuntimeGlob("OwnerSlotGlob::TRACKED_HANDLE");
            glob.refCount = 0;
            RuntimeScalar lexicalAlias = new RuntimeScalar();
            lexicalAlias.type = RuntimeScalarType.GLOB;
            lexicalAlias.value = glob;
            MyVarCleanupStack.register(lexicalAlias);
            try {
                MyVarCleanupStack.retainBlessedGlobAliases(glob, glob);

                assertEquals(1, glob.refCount);
                assertEquals(1, glob.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_VALUE));
                assertTrue(lexicalAlias.releaseScalarValueOwnerCount(
                        glob, "MyVarCleanupStackOwnerSlotTest release"));
                assertEquals(0, glob.refCount);
                assertEquals(0, glob.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_VALUE));
            } finally {
                MyVarCleanupStack.unregister(lexicalAlias);
            }
        }
    }

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
