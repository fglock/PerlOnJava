package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class ReachabilityQueryCostTest {
    @Test
    void directLiveScalarProofPrecedesGraphSnapshots() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash target = new RuntimeHash();
            RuntimeScalar root = target.createReference();
            MyVarCleanupStack.register(root);
            MyVarCleanupStack.snapshotStackToLiveCounts();
            try {
                assertEquals(MortalList.LifecycleRootProof.DIRECT_SCALAR,
                        MortalList.lifecycleRootProof(target));
            } finally {
                MyVarCleanupStack.unregister(root);
            }
        }
    }

    @Test
    void targetSpecificWalkStopsAtTheFirstContainerForAnEarlyTarget() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash target = new RuntimeHash();
            RuntimeArray global = new RuntimeArray();
            global.elements.add(target.createReference());
            for (int i = 0; i < 500; i++) {
                global.elements.add(new RuntimeHash().createReference());
            }
            GlobalVariable.globalArrays.put(
                    "ReachabilityQueryCostTest::global", global);
            int[] visitedNodes = {0};
            try {
                assertTrue(ReachabilityWalker.isReachableFromRoots(
                        target, false, visitedNodes));
                assertEquals(1, visitedNodes[0],
                        "the walk should return after inspecting the root array");
            } finally {
                GlobalVariable.globalArrays.remove(
                        "ReachabilityQueryCostTest::global");
            }
        }
    }
}
