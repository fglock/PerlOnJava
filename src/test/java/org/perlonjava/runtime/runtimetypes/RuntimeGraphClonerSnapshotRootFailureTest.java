package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("unit")
class RuntimeGraphClonerSnapshotRootFailureTest {
    @Test
    void failedLaterRootReleasesEarlierTemporaryRootSlots() {
        PerlRuntime source = new PerlRuntime();
        PerlRuntime target = new PerlRuntime();
        RuntimeHash firstRoot = new RuntimeHash();
        RuntimeScalar destroyedSharedRoot = new RuntimeScalar();
        destroyedSharedRoot.threadShared = true;
        destroyedSharedRoot.refCount = Integer.MIN_VALUE;
        RuntimeGraphCloner cloner = new RuntimeGraphCloner(source, target);

        try (PerlRuntime.Binding ignored = target.bind()) {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> cloner.cloneSnapshotRoots(List.of(firstRoot, destroyedSharedRoot)));
            assertEquals("cannot retain a destroyed temporary root", failure.getMessage());

            RuntimeHash clonedFirstRoot = (RuntimeHash) cloner.cloneValue(firstRoot);
            assertEquals(0, clonedFirstRoot.ownerSlotCount(PerlOwnerSlot.Kind.TEMPORARY_ROOT));
        } finally {
            RuntimeBase clonedFirstRoot = cloner.cloneValue(firstRoot);
            for (PerlOwnerSlot owner : clonedFirstRoot.ownerSlotSnapshot()) {
                if (owner.kind() == PerlOwnerSlot.Kind.TEMPORARY_ROOT) owner.release();
            }
            source.close();
            target.close();
        }
    }
}
