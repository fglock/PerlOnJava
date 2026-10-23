package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class ExpiredContainerRootTest {
    @Test
    void capturedScopeExitedContainerRemainsAnOwner() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray container = new RuntimeArray();
            RuntimeScalar value = new RuntimeScalar();
            container.elements.add(value);
            value.markArrayContainerOwner(container);
            container.scopeExited = true;
            MyVarCleanupStack.register(container);

            PerlOwnerSlot capture = new PerlOwnerSlot(PerlOwnerSlot.Kind.CLOSURE_PAD);
            try {
                capture.acquireCapture(container);

                assertTrue(value.isStoredInRegisteredContainerOwner(),
                        "an active captured aggregate keeps its stored scalar alive after lexical exit");
            } finally {
                capture.release();
                MyVarCleanupStack.unregister(container);
            }
        }
    }

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
