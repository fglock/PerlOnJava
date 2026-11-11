package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class SuspendedFrameEndBoundaryRootTest {
    @Test
    void pendingSuspendedOwnerRootsARescuedReferentUntilItsSlotIsReleased() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash rescued = new RuntimeHash();
            PerlOwnerSlot suspendedOwner = new PerlOwnerSlot(PerlOwnerSlot.Kind.SUSPENDED_FRAME);
            suspendedOwner.acquireSuspendedFrameIdentity(rescued);
            runtime.lifecycleState.rescuedObjects.add(rescued);
            ExecutionRuntimeState.SuspendedOwnerRootProvider provider = () ->
                    suspendedOwner.isActive() ? List.of(suspendedOwner.referent()) : List.of();
            runtime.executionState().registerSuspendedOwnerRootProvider(provider);

            try {
                assertTrue(ReachabilityWalker.walkEndBoundaryRootsExcludingRescued()
                                .contains(rescued),
                        "a registered suspended frame is an independent owner through END");

                suspendedOwner.release();

                assertFalse(ReachabilityWalker.walkEndBoundaryRootsExcludingRescued()
                                .contains(rescued),
                        "a completed or abandoned frame stops rooting its former referent");
            } finally {
                runtime.executionState().unregisterSuspendedOwnerRootProvider(provider);
                if (suspendedOwner.isActive()) suspendedOwner.release();
                runtime.lifecycleState.rescuedObjects.remove(rescued);
            }
        }
    }
}
