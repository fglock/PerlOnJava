package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class ExpiredContainerRootTest {
    @Test
    void unregisteredContainerLeftInLiveVarSnapshotIsNotAnOwner() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray weakReferent = new RuntimeArray();
            RuntimeScalar weakReference = weakReferent.createReference();
            WeakRefRegistry.weaken(weakReference);

            RuntimeArray expired = new RuntimeArray();
            RuntimeScalar cell = new RuntimeScalar();
            expired.elements.add(cell);
            MyVarCleanupStack.register(expired);
            MyVarCleanupStack.snapshotStackToLiveCounts();
            MyVarCleanupStack.unregister(expired);

            assertFalse(MyVarCleanupStack.isRegistered(expired));
            assertTrue(MyVarCleanupStack.snapshotLiveVars().contains(expired),
                    "setup must retain the test container in the stale live-var snapshot");
            assertFalse(ReachabilityWalker.isScalarReachable(cell),
                    "an unregistered container in a stale snapshot must not retain its cell");
        }
    }

    @Test
    void scopeExitedContainerInLiveVarSnapshotIsNotAnOwner() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray expired = new RuntimeArray();
            RuntimeScalar cell = new RuntimeScalar();
            expired.elements.add(cell);
            expired.scopeExited = true;
            MyVarCleanupStack.register(expired);
            MyVarCleanupStack.snapshotStackToLiveCounts();
            try {
                assertFalse(ReachabilityWalker.isScalarReachable(cell),
                        "a stale container snapshot must not revive an expired element");
            } finally {
                MyVarCleanupStack.unregister(expired);
            }
        }
    }
}
