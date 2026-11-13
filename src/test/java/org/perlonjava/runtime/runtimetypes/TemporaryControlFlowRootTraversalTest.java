package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class TemporaryControlFlowRootTraversalTest {
    @Test
    void temporaryReturnMarkerOwnsItsReturnValueForTheRootLease() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            RuntimeScalar returnedReference = referent.createAnonymousReference();
            RuntimeControlFlowList transfer = new RuntimeControlFlowList(
                    returnedReference, "temporary-control-flow-root.t", 1);

            MortalList.pushTemporaryRoot(transfer);
            try {
                assertTrue(MortalList.temporaryRootDirectlyReferences(referent));
                assertTrue(ReachabilityWalker.isReachableFromTemporaryRoots(referent));
                List<String> path = ReachabilityWalker.findPathTo(referent);
                assertNotNull(path);
                assertTrue(path.toString().contains("<return-value>"), path.toString());
            } finally {
                MortalList.popTemporaryRoot(transfer);
            }

            assertFalse(MortalList.temporaryRootDirectlyReferences(referent));
            assertFalse(ReachabilityWalker.isReachableFromTemporaryRoots(referent));
        }
    }
}
