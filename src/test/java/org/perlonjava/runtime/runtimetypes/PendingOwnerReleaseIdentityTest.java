package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class PendingOwnerReleaseIdentityTest {
    @Test
    void onlyAnEdgeReleaseTokenIdentifiesAReleasedPerlOwner() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash lifecycleTarget = new RuntimeHash();
            lifecycleTarget.refCount = 0;
            RuntimeBase.PendingOwnerRelease lifecycleOnly =
                    lifecycleTarget.queueLifecycleOnlyAtZero("identity regression");
            try {
                assertFalse(lifecycleOnly.identifiesReleasedOwnerEdge(),
                        "a lifecycle completion token names no Perl edge");
            } finally {
                lifecycleTarget.completeQueuedOwnerRelease(lifecycleOnly, "test cleanup");
            }

            boolean previousActive = MortalList.isActive();
            RuntimeHash countedTarget = new RuntimeHash();
            RuntimeScalar owner = new RuntimeScalar();
            try {
                MortalList.setActive(true);
                owner.set(countedTarget.createAnonymousReference());
                MortalList.deferDecrementIfTracked(owner);
                RuntimeBase.PendingOwnerRelease edgeRelease =
                        runtime.lifecycleState.pendingOwnerReleases.get(
                                runtime.lifecycleState.pendingOwnerReleases.size() - 1);

                assertTrue(edgeRelease.identifiesReleasedOwnerEdge(),
                        "a queued counted-slot release carries its exact edge token");

                MortalList.flush();
            } finally {
                MortalList.setActive(previousActive);
                if (countedTarget.hasPendingOwnerRelease()) MortalList.flush();
            }
        }
    }
}
