package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RuntimeArrayElementOwnershipTest {
    @Test
    void elementSlotsFollowStructuralAndScalarCellMutations() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray first = new RuntimeArray();
            RuntimeArray second = new RuntimeArray();
            RuntimeArray third = new RuntimeArray();
            RuntimeArray array = new RuntimeArray();
            RuntimeScalar cell = first.createReference();
            array.elements.add(cell);
            array.elementsOwned = true;
            array.reconcileArrayElementOwnerSlots();

            PerlOwnerSlot slot = array.arrayElementOwnerSlot(0);
            assertEquals(1, first.arraySlotOwnerCount());
            assertSame(first, slot.referent());

            cell.set(second.createReference());
            assertEquals(0, first.arraySlotOwnerCount());
            assertEquals(1, second.arraySlotOwnerCount());
            assertSame(slot, array.arrayElementOwnerSlot(0));
            assertSame(second, slot.referent());

            array.elements.add(0, third.createReference());
            assertEquals(1, third.arraySlotOwnerCount());
            assertSame(slot, array.arrayElementOwnerSlot(1),
                    "insertion moves the existing element slot without replacing its identity");

            array.elements.remove(0);
            assertEquals(0, third.arraySlotOwnerCount());
            assertSame(slot, array.arrayElementOwnerSlot(0));

            array.elements.set(0, third.createReference());
            assertEquals(0, second.arraySlotOwnerCount());
            assertEquals(1, third.arraySlotOwnerCount());
            assertSame(slot, array.arrayElementOwnerSlot(0));
            assertSame(third, slot.referent());

            array.elements.clear();
            assertEquals(0, third.arraySlotOwnerCount());
            assertNull(array.arrayElementOwnerSlot(0));
        }
    }

    @Test
    void aliasedElementsDoNotOwnReferentsUnlessExplicitlyTransferred() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray referent = new RuntimeArray();
            RuntimeArray array = new RuntimeArray();
            RuntimeScalar cell = referent.createReference();
            array.elements.add(cell);
            array.elementsAliased = true;
            array.elementsOwned = false;

            assertEquals(0, referent.arraySlotOwnerCount());

            array.markOwnedAliasElement(cell);
            assertEquals(1, referent.arraySlotOwnerCount());
            assertSame(referent, array.arrayElementOwnerSlot(0).referent());

            array.forgetOwnedAliasElement(cell);
            assertEquals(0, referent.arraySlotOwnerCount());
            assertNull(array.arrayElementOwnerSlot(0));
        }
    }

    @Test
    void replacingAnAliasedSlotTransfersOwnershipToTheArray() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray referent = new RuntimeArray();
            RuntimeArray array = new RuntimeArray();
            RuntimeScalar borrowed = new RuntimeScalar();
            array.elements.add(borrowed);
            array.elementsAliased = true;
            array.elementsOwned = false;
            RuntimeScalar stored = referent.createReference();

            array.elements.set(0, stored);

            assertTrue(array.ownsElement(stored));
            assertEquals(1, referent.arraySlotOwnerCount());
            assertSame(referent, array.arrayElementOwnerSlot(0).referent());
        }
    }

    @Test
    void proxyStoreCreatesAndTransfersItsArrayElementOwnerSlot() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray first = new RuntimeArray();
            RuntimeArray second = new RuntimeArray();
            RuntimeArray array = new RuntimeArray();
            RuntimeArrayProxyEntry proxy = new RuntimeArrayProxyEntry(array, 0);

            proxy.set(first.createReference());

            PerlOwnerSlot slot = array.arrayElementOwnerSlot(0);
            assertTrue(array.ownsElement(array.elements.get(0)));
            assertEquals(1, first.arraySlotOwnerCount(),
                    "a proxy store into a new array must register its referent owner");
            assertSame(first, slot.referent());

            proxy.set(second.createReference());

            assertSame(slot, array.arrayElementOwnerSlot(0),
                    "replacing through the proxy preserves the structural slot identity");
            assertEquals(0, first.arraySlotOwnerCount());
            assertEquals(1, second.arraySlotOwnerCount());
            assertSame(second, slot.referent());

            array.elements.clear();
            assertEquals(0, second.arraySlotOwnerCount(),
                    "removing the proxy-written element releases its owner slot");
        }
    }

    @Test
    void scalarRefaliasCreatesAnArrayElementOwnerSlot() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray referent = new RuntimeArray();
            RuntimeScalar scalarCell = referent.createReference();
            RuntimeScalar scalarReference = scalarCell.createReference();
            RuntimeArray array = new RuntimeArray();
            RuntimeArrayProxyEntry proxy = new RuntimeArrayProxyEntry(array, 0);

            proxy.aliasToReference(scalarReference);

            assertSame(scalarCell, array.elements.get(0));
            assertTrue(array.ownsElement(scalarCell));
            assertEquals(1, referent.arraySlotOwnerCount(),
                    "refaliasing promotes the new array and registers the referenced aggregate");
            assertSame(referent, array.arrayElementOwnerSlot(0).referent());

            array.elements.clear();
            assertEquals(0, referent.arraySlotOwnerCount());
        }
    }

    @Test
    void aggregateRefaliasCreatesAnArrayElementOwnerSlot() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray referent = new RuntimeArray();
            RuntimeArray array = new RuntimeArray();
            RuntimeScalar aggregateReference = referent.createReference();

            new RuntimeArrayProxyEntry(array, 0).aliasToAggregateReference(aggregateReference);

            assertTrue(array.elementsOwned,
                    "aggregate refaliasing promotes an ordinary array to an owning aggregate");
            assertEquals(1, referent.arraySlotOwnerCount());
            assertSame(referent, array.arrayElementOwnerSlot(0).referent());

            array.elements.clear();
            assertEquals(0, referent.arraySlotOwnerCount(),
                    "removing the aliased aggregate reference releases the array owner slot");
        }
    }

    @Test
    void scalarReferenceToNewArrayElementTracksLaterReferentAssignment() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray referent = new RuntimeArray();
            RuntimeArray array = new RuntimeArray();
            RuntimeScalar elementReference = new RuntimeArrayProxyEntry(array, 0).createReference();

            elementReference.scalarDeref().set(referent.createReference());

            RuntimeScalar element = array.elements.get(0);
            assertTrue(array.ownsElement(element));
            assertEquals(1, referent.arraySlotOwnerCount(),
                    "later writes through the scalar reference need an array owner slot");
            assertSame(referent, array.arrayElementOwnerSlot(0).referent());

            array.elements.clear();
            assertEquals(0, referent.arraySlotOwnerCount());
        }
    }

    @Test
    void integerAssignmentTransfersArrayElementOwnerSlotToNoReferent() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray referent = new RuntimeArray();
            RuntimeArray array = new RuntimeArray();
            RuntimeScalar cell = referent.createReference();
            array.elements.add(cell);
            array.elementsOwned = true;
            array.reconcileArrayElementOwnerSlots();
            PerlOwnerSlot slot = array.arrayElementOwnerSlot(0);
            assertEquals(1, referent.arraySlotOwnerCount());

            cell.set(new RuntimeScalar(7));

            assertNull(array.arrayElementOwnerSlot(0),
                    "a scalar element with no referent has no active owner slot");
            assertEquals(0, referent.arraySlotOwnerCount(),
                    "replacing the referent with an integer releases its array owner slot");
            assertNull(slot.referent(), "the released slot no longer names a referent");
        }
    }

    @Test
    void nonOwningAliasedElementDoesNotProveScalarReachability() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray referent = new RuntimeArray();
            RuntimeArray packageRoot = new RuntimeArray();
            RuntimeScalar cell = referent.createReference();
            packageRoot.elements.add(cell);
            packageRoot.elementsAliased = true;
            packageRoot.elementsOwned = false;
            packageRoot.isPackageGlobalRoot = true;

            assertFalse(packageRoot.ownsElement(cell));
            assertFalse(ReachabilityWalker.isScalarReachable(cell),
                    "an @_ style alias is not an independent Perl owner");
            cell.scopeExited = true;
            assertTrue(cell.isRetiredBorrowedArrayAlias());

            packageRoot.markOwnedAliasElement(cell);
            assertTrue(packageRoot.ownsElement(cell));
            assertFalse(cell.isRetiredBorrowedArrayAlias());
            assertTrue(ReachabilityWalker.isScalarReachable(cell),
                    "an explicitly transferred alias element is an owner");
        }
    }

    @Test
    void rangeRemovalReleasesOnlyRemovedElementSlots() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray first = new RuntimeArray();
            RuntimeArray second = new RuntimeArray();
            RuntimeArray array = new RuntimeArray();
            array.elements.add(first.createReference());
            array.elements.add(second.createReference());
            array.elementsOwned = true;
            array.reconcileArrayElementOwnerSlots();

            PerlOwnerSlot retained = array.arrayElementOwnerSlot(1);
            array.elements.subList(0, 1).clear();

            assertEquals(0, first.arraySlotOwnerCount());
            assertEquals(1, second.arraySlotOwnerCount());
            assertSame(retained, array.arrayElementOwnerSlot(0));
        }
    }

    @Test
    void popReleasesAnExplicitScalarOwnerAfterLegacyFlagRetires() {
        assertRemovedElementOwnerIsReleased(true);
    }

    @Test
    void shiftReleasesAnExplicitScalarOwnerAfterLegacyFlagRetires() {
        assertRemovedElementOwnerIsReleased(false);
    }

    private static void assertRemovedElementOwnerIsReleased(boolean pop) {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray referent = new RuntimeArray();
            RuntimeScalar cell = new RuntimeScalar();
            cell.set(referent.createAnonymousReference());
            RuntimeArray array = new RuntimeArray();
            array.elements.add(cell);
            array.elementsOwned = true;
            array.reconcileArrayElementOwnerSlots();
            MyVarCleanupStack.register(array);
            int originalRefCount = referent.refCount;
            cell.refCountOwned = false;

            try {
                RuntimeScalar removed = pop ? RuntimeArray.pop(array) : RuntimeArray.shift(array);
                assertSame(cell, removed);
                assertFalse(cell.hasScalarValueOwnerSlotFor(referent),
                        "removing the element retires its explicit scalar slot");
                MyVarCleanupStack.unregister(array);
                MortalList.flush();
                assertTrue(referent.refCount < originalRefCount,
                        "the removed element's deferred count is released");
            } finally {
                MyVarCleanupStack.unregister(array);
                MortalList.flush();
            }
        }
    }
}
