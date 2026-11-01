package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.runtime.operators.ReferenceOperators;
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

    @Test
    void blessingWeakRegisteredScalarDoesNotAcquireStrongGlobOwner() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeGlob glob = new RuntimeGlob(null);
            glob.refCount = WeakRefRegistry.WEAKLY_TRACKED;
            RuntimeScalar weak = globReference(glob);
            MyVarCleanupStack.register(weak);
            WeakRefRegistry.weaken(weak);

            try {
                ReferenceOperators.bless(weak,
                        new RuntimeScalar("BlessedGlobAliasOwnerSlotTest::WeakBless"));

                assertEquals(1, glob.refCount);
                assertEquals(0, glob.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_CELL));
                assertFalse(weak.refCountOwned,
                        "bless must not turn a weak scalar into a strong glob owner");
                assertTrue(WeakRefRegistry.isweak(weak));
            } finally {
                WeakRefRegistry.clearWeakRefsTo(glob);
                MyVarCleanupStack.unregister(weak);
                MortalList.flush();
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
