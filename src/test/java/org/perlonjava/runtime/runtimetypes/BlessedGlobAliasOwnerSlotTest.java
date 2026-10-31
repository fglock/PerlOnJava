package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class BlessedGlobAliasOwnerSlotTest {
    @Test
    void lexicalAndPackageAliasesAcquireDistinctCountedOwnerSlots() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeGlob glob = new RuntimeGlob("BlessedGlobAliasOwnerSlotTest::handle");
            glob.blessId = NameNormalizer.getBlessId("BlessedGlobAliasOwnerSlotTest::Handle");
            glob.refCount = 0;

            RuntimeScalar first = globAlias(glob);
            RuntimeScalar second = globAlias(glob);
            MyVarCleanupStack.register(first);
            MyVarCleanupStack.register(second);

            String packageName = "BlessedGlobAliasOwnerSlotTest::package_handle";
            RuntimeScalar packageAlias = globAlias(glob);
            GlobalVariable.globalVariables.put(packageName, packageAlias);

            try {
                MyVarCleanupStack.retainBlessedGlobAliases(glob, glob);

                assertEquals(3, glob.refCount);
                assertEquals(3, glob.countedOwnerSlotCount());
                assertEquals(3, glob.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_CELL));
                assertNotEquals(first.countedOwnerSlot().identity(),
                        second.countedOwnerSlot().identity());
                assertNotEquals(first.countedOwnerSlot().identity(),
                        packageAlias.countedOwnerSlot().identity());
                assertTrue(first.isCurrentCountedScalarOwner(glob, first.countedOwnerSlot()));
                assertTrue(second.isCurrentCountedScalarOwner(glob, second.countedOwnerSlot()));
                assertTrue(packageAlias.isCurrentCountedScalarOwner(
                        glob, packageAlias.countedOwnerSlot()));

                RuntimeScalar.scopeExitCleanup(first);
                MyVarCleanupStack.unregister(first);
                MortalList.flush();
                assertEquals(2, glob.refCount);
                assertEquals(2, glob.countedOwnerSlotCount());

                RuntimeScalar.scopeExitCleanup(second);
                MyVarCleanupStack.unregister(second);
                MortalList.flush();
                assertEquals(1, glob.refCount);
                assertEquals(1, glob.countedOwnerSlotCount());

                RuntimeScalar.scopeExitCleanup(packageAlias);
                GlobalVariable.globalVariables.remove(packageName);
                MortalList.flush();
                assertEquals(0, glob.countedOwnerSlotCount());
            } finally {
                if (MyVarCleanupStack.isRegistered(first)) {
                    RuntimeScalar.scopeExitCleanup(first);
                    MyVarCleanupStack.unregister(first);
                }
                if (MyVarCleanupStack.isRegistered(second)) {
                    RuntimeScalar.scopeExitCleanup(second);
                    MyVarCleanupStack.unregister(second);
                }
                if (GlobalVariable.globalVariables.containsKey(packageName)) {
                    RuntimeScalar.scopeExitCleanup(packageAlias);
                    GlobalVariable.globalVariables.remove(packageName);
                }
                MortalList.flush();
            }
        }
    }

    private static RuntimeScalar globAlias(RuntimeGlob glob) {
        RuntimeScalar alias = new RuntimeScalar();
        alias.type = RuntimeScalarType.GLOB;
        alias.value = glob;
        return alias;
    }
}
