package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class BlessedGlobAliasOwnerSlotTest {
    @Test
    void weakAliasIsNotCountedAlongsideStrongLexicalAliases() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeGlob glob = new RuntimeGlob(null);
            glob.refCount = 2;
            glob.blessId = NameNormalizer.getBlessId(
                    "BlessedGlobAliasOwnerSlotTest::GlobOwner");

            RuntimeScalar first = globAlias(glob);
            RuntimeScalar second = globAlias(glob);
            RuntimeScalar weak = globReference(glob);
            MyVarCleanupStack.register(first);
            MyVarCleanupStack.register(second);
            MyVarCleanupStack.register(weak);
            WeakRefRegistry.weaken(weak);

            try {
                MyVarCleanupStack.retainBlessedGlobAliases(glob, glob);

                assertEquals(4, glob.refCount);
                assertEquals(2, glob.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_CELL));
                assertTrue(first.refCountOwned);
                assertTrue(second.refCountOwned);
                assertTrue(first.isCurrentCountedScalarOwner(
                        glob, first.countedOwnerSlot()));
                assertTrue(second.isCurrentCountedScalarOwner(
                        glob, second.countedOwnerSlot()));
                assertTrue(WeakRefRegistry.isweak(weak));
                assertFalse(weak.refCountOwned,
                        "a weak scalar must not be retained as a glob alias");
            } finally {
                WeakRefRegistry.clearWeakRefsTo(glob);
                MyVarCleanupStack.unregister(weak);
                MyVarCleanupStack.unregister(second);
                MyVarCleanupStack.unregister(first);
                MortalList.invalidateAllRootSnapshots();
            }
        }
    }

    private static RuntimeScalar globAlias(RuntimeGlob glob) {
        RuntimeScalar alias = new RuntimeScalar();
        alias.type = RuntimeScalarType.GLOB;
        alias.value = glob;
        return alias;
    }

    private static RuntimeScalar globReference(RuntimeGlob glob) {
        RuntimeScalar alias = new RuntimeScalar();
        alias.type = RuntimeScalarType.GLOBREFERENCE;
        alias.value = glob;
        return alias;
    }
}
