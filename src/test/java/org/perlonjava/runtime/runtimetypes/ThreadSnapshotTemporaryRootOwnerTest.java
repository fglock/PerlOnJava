package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class ThreadSnapshotTemporaryRootOwnerTest {

    @Test
    void directSnapshotRootUsesAnOwnerIdentityUntilTheLeaseCloses() {
        PerlRuntime parent = new PerlRuntime().initialize();
        PerlRuntime.RootSnapshot snapshot = null;
        try {
            RuntimeArray sourceReferent = new RuntimeArray();
            RuntimeArray sourceObserverRoot = new RuntimeArray();
            RuntimeScalar weakObserver = new RuntimeScalar();
            weakObserver.type = RuntimeScalarType.ARRAYREFERENCE;
            weakObserver.value = sourceReferent;
            RuntimeScalar directRoot = new RuntimeScalar();
            directRoot.type = RuntimeScalarType.ARRAYREFERENCE;
            directRoot.value = sourceReferent;

            try (PerlRuntime.Binding ignored = parent.bind()) {
                // The direct Java root is intentionally outside the source
                // runtime's counted scalar slots. Keep the source referent
                // live while the snapshot reconstructs its external edge.
                sourceReferent.refCount = 1;
                sourceObserverRoot.addClonedElement(weakObserver);
                WeakRefRegistry.weaken(weakObserver);
            }

            snapshot = parent.snapshotCloneWithRoots(List.of(sourceObserverRoot, directRoot));
            PerlRuntime child = snapshot.runtime();
            RuntimeArray childReferent = (RuntimeArray)
                    ((RuntimeScalar) snapshot.roots().get(1)).value;
            RuntimeScalar childWeakObserver = ((RuntimeArray) snapshot.roots().getFirst())
                    .elements.getFirst();

            try (PerlRuntime.Binding ignored = child.bind()) {
                assertNotNull(childReferent);
                assertSame(childReferent, childWeakObserver.value);
                assertTrue(WeakRefRegistry.isweak(childWeakObserver));
                assertEquals(WeakRefRegistry.WEAKLY_TRACKED, childReferent.refCount,
                        "a snapshot root must not fabricate a positive Perl reference count");
                assertEquals(1, childReferent.ownerSlotCount(PerlOwnerSlot.Kind.TEMPORARY_ROOT),
                        "the direct snapshot edge must have a stable temporary owner identity");
            }

            snapshot.close();
            snapshot = null;

            try (PerlRuntime.Binding ignored = child.bind()) {
                assertEquals(RuntimeScalarType.UNDEF, childWeakObserver.type,
                        "retiring the final snapshot root must clear its weak observers");
            }
        } finally {
            if (snapshot != null) snapshot.close();
            parent.close();
        }
    }
}
