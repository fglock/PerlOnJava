package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

@Tag("unit")
class ExpiredContainerRootTest {
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
