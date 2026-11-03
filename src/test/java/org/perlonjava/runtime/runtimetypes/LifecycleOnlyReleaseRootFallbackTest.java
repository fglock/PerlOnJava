package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class LifecycleOnlyReleaseRootFallbackTest {
    @Test
    void lifecycleTokenDoesNotClaimAnUntrackedPackageEdgeWasReleased() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            String rootName = "Issue1649LifecycleOnlyReleaseRootFallback::root";
            boolean previousActive = MortalList.isActive();
            RuntimeHash packageRoot = new RuntimeHash();
            RuntimeHash target = new RuntimeHash();
            target.blessId = NameNormalizer.getBlessId("Issue1649::LegacyLifecycleRoot");

            // Model a legacy package edge that predates slot registration.
            RuntimeScalar packageEdge = new RuntimeScalar();
            packageEdge.type = RuntimeScalarType.HASHREFERENCE;
            packageEdge.value = target;
            packageRoot.elements.put("target", packageEdge);
            GlobalVariable.globalHashes.put(rootName, packageRoot);

            RuntimeScalar weak = target.createReference();
            WeakRefRegistry.weaken(weak);
            target.refCount = 0; // A zero-count lifecycle boundary carries no released edge.

            RuntimeScalar discarded = new RuntimeScalar();
            discarded.type = RuntimeScalarType.HASHREFERENCE;
            discarded.value = target;
            RuntimeList result = new RuntimeList();
            result.elements.add(discarded);

            try {
                MortalList.setActive(true);
                MortalList.invalidateAllRootSnapshots();
                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    MortalList.mortalizeForVoidDiscard(result);
                    RuntimeBase.PendingOwnerRelease lifecycleToken =
                            runtime.lifecycleState.pendingOwnerReleases.get(0);
                    assertNull(lifecycleToken.ownerSlotRelease,
                            "a lifecycle boundary has no edge-release identity");

                    MortalList.flush();

                    assertTrue(WeakRefRegistry.isweak(weak),
                            "legacy root evidence keeps the live referent from finalizing");
                    assertEquals(1, target.refCount,
                            "the reachable legacy referent is restored while its package edge lives");
                    assertTrue(measurement.stats().rootQueries > 0,
                            "unidentified lifecycle work remains on the compatibility proof path");
                }

                GlobalVariable.globalHashes.remove(rootName);
                packageRoot.elements.clear();
                MortalList.invalidateAllRootSnapshots();
                target.refCount = 0;
                RuntimeScalar finalDiscard = new RuntimeScalar();
                finalDiscard.type = RuntimeScalarType.HASHREFERENCE;
                finalDiscard.value = target;
                RuntimeList finalResult = new RuntimeList();
                finalResult.elements.add(finalDiscard);
                MortalList.mortalizeForVoidDiscard(finalResult);
                MortalList.flush();

                assertFalse(WeakRefRegistry.isweak(weak),
                        "removing the final legacy root allows weak cleanup");
            } finally {
                GlobalVariable.globalHashes.remove(rootName);
                packageRoot.elements.clear();
                MortalList.invalidateAllRootSnapshots();
                if (WeakRefRegistry.isweak(weak)) WeakRefRegistry.clearWeakRefsTo(target);
                target.refCount = Integer.MIN_VALUE;
                MortalList.setActive(previousActive);
            }
        }
    }
}
