package org.perlonjava.runtime.runtimetypes;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class ReachabilitySnapshotCompletenessTest {
    @Test
    void cappedRootSnapshotsCannotProveAnUnseenTargetUnreachable() throws Exception {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray root = new RuntimeArray();
            ArrayChain chain = appendChain(root);
            RuntimeArray tail = chain.tail();
            String globalName =
                    "ReachabilitySnapshotCompletenessTest::global";
            GlobalVariable.globalArrays.put(globalName, root);
            MyVarCleanupStack.register(root);
            MyVarCleanupStack.snapshotStackToLiveCounts();

            LifecycleRuntimeState state = runtime.lifecycleState;
            try {
                ReachabilityWalker.ExternalRootSnapshot external;
                ReachabilityWalker.ScalarRootReachabilitySnapshot scalars;
                ReachabilityWalker.LiveRootSnapshot live =
                        new ReachabilityWalker.LiveRootSnapshot();
                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    external = new ReachabilityWalker.ExternalRootSnapshot(false);
                    scalars = ReachabilityWalker.scalarReachabilitySnapshot();
                    assertEquals(50_000L,
                            measurement.stats().externalRootSnapshotNodesVisited,
                            "the external snapshot reaches its visit cap");
                    assertEquals(50_000L, measurement.stats().nodesVisited,
                            "the scalar snapshot reaches its visit cap");
                }

                assertFalse(external.complete(), "the external snapshot records truncation");
                assertFalse(live.complete(), "the live snapshot records truncation");
                assertFalse(scalars.complete(), "the scalar snapshot records truncation");
                assertTrue(external.isReachable(tail),
                        "an incomplete external snapshot cannot prove a miss");
                assertTrue(live.isReachable(tail),
                        "an incomplete lexical snapshot cannot prove a miss");
                assertTrue(scalars.isReachable(chain.tailOwner()),
                        "an incomplete scalar snapshot cannot prove an owner miss");
                ReachabilityWalker.ScalarRootReachabilitySnapshot completeScalars =
                        ReachabilityWalker.completeScalarReachabilitySnapshot();
                assertTrue(completeScalars.complete(),
                        "capture cleanup can request a complete scalar-root walk");
                assertTrue(completeScalars.isReachable(chain.tailOwner()),
                        "the complete capture cleanup walk reaches a lexical beyond the bounded prefix");
                state.scalarRootSnapshot = scalars;
                state.reachabilityDrainDepth++;
                try {
                    assertTrue(MortalList.isScalarReachableFromDrainSnapshot(
                                    chain.tailOwner()),
                            "the cleanup drain cannot treat an incomplete scalar snapshot as a miss");
                } finally {
                    state.reachabilityDrainDepth--;
                }
                assertTrue(ReachabilityWalker.isScalarReachable(chain.tailOwner()),
                        "a capped scalar walk cannot prove an owner unreachable");
                assertTrue(ReachabilityWalker.isReachableFromLiveScalarRegistry(
                                tail, List.of(root.createReference())),
                        "a capped live-scalar registry walk cannot prove a target unreachable");
                assertTrue(ReachabilityWalker.isReachableFromRoots(tail, true),
                        "a capped general root walk cannot prove a target unreachable");
                assertTrue(ReachabilityWalker
                                .isReachableFromExternalRootExcludingDirectLexical(tail),
                        "a capped external-root walk cannot prove a target unreachable");
                ReachabilityWalker.TiedHashReachabilitySnapshot tiedSnapshot =
                        ReachabilityWalker.reachableThroughTiedHashes();
                assertFalse(tiedSnapshot.complete(),
                        "the tied-hash snapshot records the visit cap");
                assertTrue(tiedSnapshot.contains(tail),
                        "an incomplete tied-hash snapshot cannot prove a miss");

                state.externalRootSnapshot = external;
                state.liveRootSnapshot = live;
                state.fullRootSnapshot = new ReachabilityWalker.RootReachabilitySnapshot(
                        Collections.emptySet(), true,
                        ReachabilityWalker.MAX_ROOT_SNAPSHOT_EDGES);
                Method cachedQuery = MortalList.class.getDeclaredMethod(
                        "isReachableFromExternalRootCached", RuntimeBase.class);
                cachedQuery.setAccessible(true);
                assertTrue((boolean) cachedQuery.invoke(null, tail),
                        "the cached cleanup query conservatively retains misses without "
                                + "a second full-root snapshot");
            } finally {
                MyVarCleanupStack.unregister(root);
                GlobalVariable.globalArrays.remove(globalName);
                MortalList.invalidateAllRootSnapshots();
            }
        }
    }

    private static ArrayChain appendChain(RuntimeArray root) {
        RuntimeArray parent = root;
        RuntimeScalar lastOwner = null;
        for (int i = 0; i < 50_001; i++) {
            RuntimeArray child = new RuntimeArray();
            lastOwner = child.createAnonymousReference();
            parent.elements.add(lastOwner);
            parent = child;
        }
        return new ArrayChain(parent, lastOwner);
    }

    private record ArrayChain(RuntimeArray tail, RuntimeScalar tailOwner) {}
}
