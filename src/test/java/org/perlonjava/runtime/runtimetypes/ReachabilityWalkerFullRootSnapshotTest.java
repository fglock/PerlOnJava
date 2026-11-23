package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class ReachabilityWalkerFullRootSnapshotTest {
    @Test
    void oneSnapshotAnswersAllTargetsLikeThePerTargetRootWalk() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash global = new RuntimeHash();
            List<RuntimeHash> targets = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                RuntimeHash target = new RuntimeHash();
                targets.add(target);
                global.put("target-" + i, target.createReference());
            }
            RuntimeHash unrooted = new RuntimeHash();
            RuntimeHash directLexical = new RuntimeHash();
            MyVarCleanupStack.register(directLexical);
            MyVarCleanupStack.snapshotStackToLiveCounts();
            GlobalVariable.globalHashes.put("FullRootSnapshotTest::global", global);
            try {
                Set<RuntimeBase> snapshot = ReachabilityWalker.reachableFromRootsSnapshot();
                for (RuntimeHash target : targets) {
                    assertEquals(ReachabilityWalker.isReachableFromRoots(target),
                            snapshot.contains(target));
                }
                assertTrue(snapshot.contains(directLexical));
                assertFalse(snapshot.contains(unrooted));
            } finally {
                GlobalVariable.globalHashes.remove("FullRootSnapshotTest::global");
                MyVarCleanupStack.unregister(directLexical);
            }
        }
    }
}
