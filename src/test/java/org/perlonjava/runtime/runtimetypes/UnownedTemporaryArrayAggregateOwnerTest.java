package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class UnownedTemporaryArrayAggregateOwnerTest {
    private static final int TEMPORARY_RESULT_COUNT = 60;

    @Test
    void countedElementInUnownedTemporaryArrayIsNotACurrentPerlOwner() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeHash referent = new RuntimeHash();
            RuntimeArray temporary = new RuntimeArray();
            temporary.localBindingExists = true;

            RuntimeScalar owner = referent.createAnonymousReference();
            RuntimeScalar.incrementRefCountForContainerStore(
                    owner, PerlOwnerSlot.Kind.ARRAY_ELEMENT);
            temporary.elements.add(owner);
            assertTrue(referent.hasCurrentPerlOwnerSlotIdentity(),
                    "a stored element in a live array is a current Perl owner");

            owner.localBindingExists = false;
            temporary.localBindingExists = false;
            assertFalse(temporary.hasPerlStrongOwner());
            assertFalse(referent.hasCurrentPerlOwnerSlotIdentity(),
                    "an element in an unowned temporary array is not a Perl owner");
        }
    }

    @Test
    void temporaryRootLeaseDefersAndThenFinishesUntrackedArrayCleanup() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            boolean previousMortalListState = MortalList.isActive();
            MortalList.setActive(true);
            RuntimeArray temporary = new RuntimeArray(TEMPORARY_RESULT_COUNT);
            RuntimeHash[] referents = new RuntimeHash[TEMPORARY_RESULT_COUNT];
            RuntimeScalar[] weakObservers = new RuntimeScalar[TEMPORARY_RESULT_COUNT];
            boolean temporaryRootPushed = false;
            try {
                // This array models a lexical result list whose scope ends
                // while an expression still has a temporary-root lease on
                // the array. Java keeps the array reachable throughout.
                temporary.localBindingExists = true;

                for (int i = 0; i < TEMPORARY_RESULT_COUNT; i++) {
                    RuntimeHash referent = new RuntimeHash();
                    referents[i] = referent;
                    temporary.elements.add(untrackedReferenceTo(referent));
                    RuntimeScalar weakObserver = untrackedReferenceTo(referent);
                    weakObservers[i] = weakObserver;
                    WeakRefRegistry.weaken(weakObserver);
                }

                MortalList.pushTemporaryRoot(temporary);
                temporaryRootPushed = true;
                MortalList.scopeExitCleanupArray(temporary);

                assertFalse(temporary.localBindingExists,
                        "scope exit removes the array's lexical owner");
                assertTrue(temporary.hasTemporaryRootOwnerSlotIdentity(),
                        "the expression lease remains active during deferred cleanup");
                assertEquals(TEMPORARY_RESULT_COUNT, temporary.elements.size(),
                        "the Java-reachable array remains available during the lease");
                for (RuntimeHash referent : referents) {
                    assertEquals(1, referent.ownerSlotCount(
                                    PerlOwnerSlot.Kind.UNTRACKED_REFERENCE),
                            "the temporary-root lease keeps each untracked cell alive");
                }
                for (RuntimeScalar weakObserver : weakObservers) {
                    assertTrue(WeakRefRegistry.isweak(weakObserver),
                            "a live temporary-root lease preserves weak observers");
                    assertEquals(RuntimeScalarType.HASHREFERENCE, weakObserver.type);
                }

                MortalList.popTemporaryRoot(temporary);
                temporaryRootPushed = false;

                assertFalse(temporary.hasTemporaryRootOwnerSlotIdentity(),
                        "popping the expression root releases its lease");
                for (RuntimeHash referent : referents) {
                    assertEquals(0, referent.ownerSlotCount(
                                    PerlOwnerSlot.Kind.UNTRACKED_REFERENCE),
                            "the final array transfer retires each untracked owner slot");
                }
                for (RuntimeScalar weakObserver : weakObservers) {
                    assertFalse(WeakRefRegistry.isweak(weakObserver),
                            "retiring the final untracked owner clears its weak observer");
                    assertEquals(RuntimeScalarType.UNDEF, weakObserver.type);
                }
                assertFalse(temporary.hasPerlStrongOwner(),
                        "Java reachability does not restore the array's Perl ownership");
            } finally {
                if (temporaryRootPushed) MortalList.popTemporaryRoot(temporary);
                MortalList.scopeExitCleanupArray(temporary);
                for (int i = 0; i < referents.length; i++) {
                    if (referents[i] != null) {
                        WeakRefRegistry.clearWeakRefsTo(referents[i]);
                    }
                }
                MortalList.setActive(previousMortalListState);
            }
        }
    }

    private static RuntimeScalar untrackedReferenceTo(RuntimeBase referent) {
        RuntimeScalar scalar = new RuntimeScalar();
        scalar.type = RuntimeScalarType.HASHREFERENCE;
        scalar.value = referent;
        return scalar;
    }
}
