package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class WeakArgumentAliasUndefOwnerTest {
    @Test
    void undefersWeaklyTrackedCleanupToTheCurrentArgumentAlias() {
        Assumptions.assumeFalse(ReachabilityWalker.correctiveSweepsEnabled(),
                "the owner identity must drive cleanup without corrective sweeps");

        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            MortalList.setActive(true);
            RuntimeArray target = new RuntimeArray();
            target.refCount = WeakRefRegistry.WEAKLY_TRACKED;
            RuntimeScalar source = referenceTo(target);
            RuntimeScalar weakObserver = referenceTo(target);
            MyVarCleanupStack.register(source);
            WeakRefRegistry.weaken(weakObserver);
            PerlOwnerSlot argumentAlias = new PerlOwnerSlot(
                    PerlOwnerSlot.Kind.ARGUMENT_ALIAS);
            argumentAlias.acquireArgumentAliasIdentity(target);

            try {
                source.undefine();

                assertEquals(WeakRefRegistry.WEAKLY_TRACKED, target.refCount,
                        "the alias identity defers cleanup without a synthetic count");
                assertTrue(target.deferredFinalReleaseForUncountedOwners);
                assertTrue(argumentAlias.isActive());
                assertTrue(WeakRefRegistry.isweak(weakObserver));

                argumentAlias.release();

                assertFalse(argumentAlias.isActive());
                assertFalse(WeakRefRegistry.isweak(weakObserver),
                        "the final alias release clears the weak observer");
                assertEquals(Integer.MIN_VALUE, target.refCount);
            } finally {
                argumentAlias.release();
                if (WeakRefRegistry.isweak(weakObserver)) {
                    WeakRefRegistry.unweaken(weakObserver);
                }
                RuntimeScalar.scopeExitCleanup(source);
                MyVarCleanupStack.unregister(source);
                MortalList.flush();
            }
        }
    }

    private static RuntimeScalar referenceTo(RuntimeBase referent) {
        RuntimeScalar scalar = new RuntimeScalar();
        scalar.type = RuntimeScalarType.REFERENCE;
        scalar.value = referent;
        return scalar;
    }
}
