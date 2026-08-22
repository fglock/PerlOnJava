package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class ReachabilityWalkerTiedSnapshotTest {
    @Test
    void collectsAllTargetsBehindOneTiedHandlerInOneSnapshot() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            String globalName = "Phase36A178::tied";
            RuntimeHash handler = new RuntimeHash();
            List<RuntimeHash> targets = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                RuntimeHash target = new RuntimeHash();
                targets.add(target);
                handler.elements.put("target-" + i, target.createReference());
            }

            RuntimeHash tied = new RuntimeHash();
            tied.type = RuntimeHash.TIED_HASH;
            tied.elements = new TieHash("Phase36A178::Tie",
                    new RuntimeHash(), handler.createReference());

            assertTrue(handler.possiblyStoredInTiedHandler);
            for (RuntimeHash target : targets) {
                assertTrue(target.possiblyStoredInTiedHandler);
            }

            assertFalse(ReachabilityWalker.reachableThroughTiedHashes()
                    .contains(targets.getFirst()));
            GlobalVariable.globalHashes.put(globalName, tied);
            try {
                Set<RuntimeBase> reachable =
                        ReachabilityWalker.reachableThroughTiedHashes();
                assertTrue(reachable.contains(handler));
                for (RuntimeHash target : targets) {
                    assertTrue(reachable.contains(target));
                }
            } finally {
                GlobalVariable.globalHashes.remove(globalName);
            }
        }
    }
}
