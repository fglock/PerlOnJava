package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class ContainerSlotAcquisitionOrderTest {
    @Test
    void hashReplacementAcquiresNewEdgeBeforePublishingAndReleasesOldEdgeAfterward() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray previous = new RuntimeArray();
            RuntimeArray replacement = new RuntimeArray();
            ObservingHash hash = new ObservingHash(replacement, previous);
            RuntimeScalar previousReference = previous.createAnonymousReference();
            RuntimeScalar replacementReference = replacement.createAnonymousReference();

            hash.put("child", previousReference);
            assertEquals(1, previous.refCount);

            hash.observePublication = true;
            hash.put("child", replacementReference);

            assertTrue(hash.replacementCountAtPublication > 0,
                    "the new referent must be retained before the map publishes its slot");
            assertTrue(hash.previousOwnerAtPublication,
                    "the old edge must remain owned until the replacement is published");
            assertEquals(1, replacement.refCount);
            assertEquals(1, previous.refCount,
                    "the displaced release is deferred until after publication");

            MortalList.flush();

            assertEquals(Integer.MIN_VALUE, previous.refCount,
                    "the previous referent reaches its final lifecycle state");
            assertEquals(0, previous.ownerSlotCount(PerlOwnerSlot.Kind.HASH_ELEMENT));
            assertEquals(1, replacement.refCount);
            assertEquals(1, replacement.ownerSlotCount(PerlOwnerSlot.Kind.HASH_ELEMENT));
        }
    }

    @Test
    void arrayLiteralAcquiresElementBeforePublishing() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray referent = new RuntimeArray();
            RuntimeScalar reference = referent.createAnonymousReference();
            ObservingArray array = new ObservingArray(referent);

            array.add(reference);

            assertEquals(1, array.referentCountAtPublication,
                    "an array literal must acquire its element edge before publishing the cell");
            assertEquals(1, referent.ownerSlotCount(PerlOwnerSlot.Kind.ARRAY_ELEMENT));
        }
    }

    @Test
    void pushAcquiresElementsBeforePublishing() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray referent = new RuntimeArray();
            RuntimeScalar reference = referent.createAnonymousReference();
            ObservingArray array = new ObservingArray(referent);

            RuntimeArray.push(array, reference);

            assertEquals(1, array.referentCountAtPublication,
                    "push must acquire each strong element edge before publishing it");
            assertEquals(1, referent.ownerSlotCount(PerlOwnerSlot.Kind.ARRAY_ELEMENT));
        }
    }

    @Test
    void wholeArrayReplacementAcquiresElementsBeforePublishing() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray referent = new RuntimeArray();
            RuntimeScalar reference = referent.createAnonymousReference();
            RuntimeArray destination = new RuntimeArray();
            RuntimeArray.push(destination, new RuntimeScalar(1));
            ObservingArray array = new ObservingArray(referent);
            array.elements.addAll(destination.elements);
            RuntimeList replacement = new RuntimeList();
            replacement.add(reference);

            array.setFromList(replacement);

            assertEquals(1, array.referentCountAtPublication,
                    "whole-array replacement must acquire new element edges before publication");
            assertEquals(1, referent.ownerSlotCount(PerlOwnerSlot.Kind.ARRAY_ELEMENT));
        }
    }

    @Test
    void singleValueArrayReplacementPublishesBeforeReleasingPreviousElement() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray previous = new RuntimeArray();
            RuntimeScalar previousReference = previous.createAnonymousReference();
            RuntimeArray replacement = new RuntimeArray();
            RuntimeScalar replacementReference = replacement.createAnonymousReference();
            ObservingArray array = new ObservingArray(replacement, previous);
            array.add(previousReference);

            array.set(replacementReference);

            assertEquals(1, array.referentCountAtPublication,
                    "the replacement referent is retained before the array slot is published");
            assertEquals(1, array.previousOwnersAtPublication,
                    "the previous element remains owned until the replacement is published");
            assertEquals(Integer.MIN_VALUE, previous.refCount,
                    "the old element is released after the replacement is visible");
        }
    }

    private static final class ObservingHash extends RuntimeHash {
        private final RuntimeArray replacement;
        private final RuntimeArray previous;
        private boolean observePublication;
        private int replacementCountAtPublication = -1;
        private boolean previousOwnerAtPublication;

        private ObservingHash(RuntimeArray replacement, RuntimeArray previous) {
            this.replacement = replacement;
            this.previous = previous;
        }

        @Override
        void markPackageRootedValue(RuntimeScalar value) {
            super.markPackageRootedValue(value);
            if (!observePublication || value == null || value.value != replacement) return;
            replacementCountAtPublication = replacement.refCount;
            previousOwnerAtPublication =
                    previous.ownerSlotCount(PerlOwnerSlot.Kind.HASH_ELEMENT) == 1;
        }
    }

    private static final class ObservingArray extends RuntimeArray {
        private final RuntimeArray referent;
        private final RuntimeArray previous;
        private int referentCountAtPublication = -1;
        private int previousOwnersAtPublication = -1;

        private ObservingArray(RuntimeArray referent) {
            this(referent, null);
        }

        private ObservingArray(RuntimeArray referent, RuntimeArray previous) {
            this.referent = referent;
            this.previous = previous;
        }

        @Override
        void markPackageRootedValue(RuntimeScalar value) {
            super.markPackageRootedValue(value);
            if (value != null && value.value == referent) {
                referentCountAtPublication = referent.refCount;
                if (previous != null) {
                    previousOwnersAtPublication =
                            previous.ownerSlotCount(PerlOwnerSlot.Kind.ARRAY_ELEMENT);
                }
            }
        }
    }
}
