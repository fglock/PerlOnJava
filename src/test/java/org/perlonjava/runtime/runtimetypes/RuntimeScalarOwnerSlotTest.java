package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class RuntimeScalarOwnerSlotTest {
    @Test
    void independentScalarCellsKeepTheirIdentitiesAcrossOverwriteAndSameReferentAssignment() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash first = new RuntimeHash();
            RuntimeHash replacement = new RuntimeHash();
            RuntimeScalar left = new RuntimeScalar();
            RuntimeScalar right = new RuntimeScalar();
            RuntimeScalar reference = first.createReference();

            left.set(reference);
            right.set(reference);
            PerlOwnerSlot leftSlot = left.countedOwnerSlot();
            PerlOwnerSlot rightSlot = right.countedOwnerSlot();

            assertNotEquals(leftSlot.identity(), rightSlot.identity());
            assertEquals(2, first.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_CELL));
            assertEquals(2, first.refCount);
            assertEquals(0, runtime.positiveOwnerSlotReferentCount(first));

            left.set(reference);
            assertSame(leftSlot, left.countedOwnerSlot());
            assertSame(first, leftSlot.referent());
            assertEquals(2, first.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_CELL));
            assertEquals(2, first.refCount);

            left.set(replacement.createReference());
            assertSame(leftSlot, left.countedOwnerSlot());
            assertSame(replacement, leftSlot.referent());
            assertSame(first, rightSlot.referent());
            assertEquals(1, first.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_CELL));
            assertEquals(1, replacement.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_CELL));
            assertEquals(1, first.refCount);

            left.set(new RuntimeScalar());
            assertFalse(leftSlot.isActive());
            assertEquals(0, replacement.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_CELL));
            assertTrue(rightSlot.isActive());
        }
    }

    @Test
    void arrayRemovalCarriesTheRemovedElementsIdentityUntilItsDecrementDrains() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            MortalList.setActive(true);
            MortalList.pushMark();
            RuntimeHash target = new RuntimeHash();
            RuntimeArray array = new RuntimeArray();
            array.push(target.createReference());
            array.push(target.createReference());
            PerlOwnerSlot retainedSlot = array.elements.get(0).countedOwnerSlot();
            PerlOwnerSlot removedSlot = array.elements.get(1).countedOwnerSlot();

            assertNotEquals(retainedSlot.identity(), removedSlot.identity());
            assertEquals(2, target.ownerSlotCount(PerlOwnerSlot.Kind.ARRAY_ELEMENT));
            assertEquals(0, runtime.positiveOwnerSlotReferentCount(target));

            RuntimeScalar removed = RuntimeArray.pop(array);
            RuntimeBase.PendingOwnerRelease pending = runtime.lifecycleState.pendingOwnerReleases.getLast();
            PerlOwnerSlot.PendingRelease release = pending.ownerSlotRelease;

            assertNotNull(release);
            assertEquals(removedSlot.identity(), release.ownerSlotIdentity());
            assertEquals(PerlOwnerSlot.Kind.ARRAY_ELEMENT, release.kind());
            assertFalse(release.isCompleted());
            assertFalse(removed.refCountOwned);
            assertFalse(removedSlot.isActive());
            assertTrue(retainedSlot.isActive());
            assertEquals(1, target.ownerSlotCount(PerlOwnerSlot.Kind.ARRAY_ELEMENT));
            assertEquals(2, target.refCount);

            MortalList.flushAboveMark();
            assertTrue(release.isCompleted());
            assertEquals(1, target.refCount);
            assertTrue(retainedSlot.isActive());
            MortalList.popMark();
        }
    }

    @Test
    void queuedReleaseCannotReleaseANewerValueStoredInTheSameCell() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            MortalList.setActive(true);
            RuntimeHash first = new RuntimeHash();
            RuntimeHash next = new RuntimeHash();
            first.localBindingExists = true;
            RuntimeScalar cell = new RuntimeScalar();
            cell.set(first.createReference());
            PerlOwnerSlot slot = cell.countedOwnerSlot();

            MortalList.pushMark();
            MortalList.deferDecrementIfTracked(cell);
            PerlOwnerSlot.PendingRelease release =
                    runtime.lifecycleState.pendingOwnerReleases.getLast().ownerSlotRelease;
            assertNotNull(release);
            assertEquals(slot.identity(), release.ownerSlotIdentity());

            boolean previousSuppression = MortalList.suppressFlush(true);
            try {
                cell.set(next.createReference());
            } finally {
                MortalList.suppressFlush(previousSuppression);
            }
            assertSame(slot, cell.countedOwnerSlot());
            assertSame(next, slot.referent());

            MortalList.flushAboveMark();
            assertTrue(release.isCompleted());
            assertSame(next, slot.referent());
            assertEquals(1, next.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_CELL));
            assertEquals(0, first.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_CELL));
            MortalList.popMark();
        }
    }

    @Test
    void hashKeysOwnDistinctElementSlotsEvenWhenTheyStoreTheSameInputScalar() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash target = new RuntimeHash();
            RuntimeHash hash = new RuntimeHash();
            RuntimeScalar reference = target.createReference();
            hash.put("left", reference);
            hash.put("right", reference);

            PerlOwnerSlot left = hash.elements.get("left").countedOwnerSlot();
            PerlOwnerSlot right = hash.elements.get("right").countedOwnerSlot();
            assertNotEquals(left.identity(), right.identity());
            assertEquals(PerlOwnerSlot.Kind.HASH_ELEMENT, left.kind());
            assertEquals(PerlOwnerSlot.Kind.HASH_ELEMENT, right.kind());
            assertEquals(2, target.ownerSlotCount(PerlOwnerSlot.Kind.HASH_ELEMENT));

            WeakRefRegistry.weaken(hash.elements.get("left"));
            assertFalse(left.isActive());
            assertTrue(right.isActive());
            assertEquals(1, target.ownerSlotCount(PerlOwnerSlot.Kind.HASH_ELEMENT));
            assertEquals(1, target.refCount);
        }
    }

    @Test
    void overwritingAWeakHashSlotDoesNotReleaseItsTargetsChildOwners() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            MortalList.setActive(true);
            RuntimeHash child = new RuntimeHash();
            RuntimeHash parent = new RuntimeHash();
            parent.put("child", child.createReference());
            RuntimeScalar strongParent = new RuntimeScalar();
            strongParent.set(parent.createReference());
            RuntimeScalar weakChild = child.createReference();
            WeakRefRegistry.weaken(weakChild);

            RuntimeHash observer = new RuntimeHash();
            observer.put("parent", parent.createReference());
            WeakRefRegistry.weaken(observer.elements.get("parent"));
            PerlOwnerSlot childSlot = parent.elements.get("child").countedOwnerSlot();

            observer.put("parent", new RuntimeScalar());

            assertTrue(childSlot.isActive());
            assertTrue(parent.elements.get("child").refCountOwned);
            assertEquals(1, child.refCount);
            assertTrue(strongParent.countedOwnerSlot().isActive());
        }
    }

    @Test
    void finalElementOwnerClearsWeakReferencesWithoutConsultingAStaleRootSnapshot() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            MortalList.setActive(true);
            runtime.lifecycleState.weakSweepSuppressionDepth++;
            int lexicalMark = MyVarCleanupStack.pushMark();
            try {
                RuntimeArray child = new RuntimeArray();
                RuntimeScalar strong = new RuntimeScalar();
                strong.set(child.createAnonymousReference());
                RuntimeScalar weak = new RuntimeScalar();
                weak.set(strong);
                WeakRefRegistry.weaken(weak);
                RuntimeArray metadata = new RuntimeArray();
                metadata.push(strong);
                RuntimeScalar metadataCell = new RuntimeScalar();
                metadataCell.set(metadata.createAnonymousReference());
                MyVarCleanupStack.register(metadataCell);
                strong.set(new RuntimeScalar(1));
                assertTrue(MortalList.isUnblessedAggregateReachableDuringDrain(child));

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    metadataCell.set(new RuntimeScalar());

                    assertEquals(RuntimeScalarType.UNDEF, weak.type);
                    assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED, child.perlLifecycleState());
                    ReachabilityQueryStats stats = measurement.stats();
                    assertEquals(0, stats.rootQueries);
                    assertEquals(0, stats.snapshotsBuilt);
                    assertEquals(0, stats.externalRootSnapshotsBuilt);
                    assertEquals(0, stats.liveRootSnapshotsBuilt);
                    assertEquals(0, stats.scalarReachabilityQueries);
                }
            } finally {
                MyVarCleanupStack.popMark(lexicalMark);
                runtime.lifecycleState.weakSweepSuppressionDepth--;
            }
        }
    }

    @Test
    void voidDiscardBorrowsAnActiveCapturedScalarInsteadOfConsumingItsOwner() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray target = new RuntimeArray();
            RuntimeScalar pad = new RuntimeScalar();
            pad.set(target.createAnonymousReference());
            PerlOwnerSlot capture = pad.retainClosureCaptureSlot();
            PerlOwnerSlot valueOwner = pad.countedOwnerSlot();
            RuntimeList result = new RuntimeList();
            result.elements.add(pad);

            MortalList.mortalizeForVoidDiscard(result);

            assertTrue(pad.refCountOwned);
            assertTrue(valueOwner.isActive());
            assertEquals(1, target.refCount);
            assertTrue(runtime.lifecycleState.pendingOwnerReleases.isEmpty());
            pad.releaseClosureCaptureSlot(capture);
        }
    }
}
