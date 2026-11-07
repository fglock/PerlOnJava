package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class PerlReturnOwnerConsumptionTest {
    @Test
    void nestedNoDestroyCleanupLeavesCallerReturnOwnerPending() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            String returnedClass = "Issue1649::PendingReturnOwner";
            String cleanupClass = "Issue1649::NoDestroyCleanup";
            AtomicInteger destroyCalls = new AtomicInteger();
            RuntimeCode destroy = new RuntimeCode((args, context) -> {
                destroyCalls.incrementAndGet();
                return new RuntimeScalar().getList();
            }, null);
            destroy.packageName = "Issue1649";
            destroy.subName = "DESTROY";
            GlobalVariable.getGlobalCodeRef(returnedClass + "::DESTROY")
                    .set(new RuntimeScalar(destroy));

            try {
                RuntimeHash returnedReferent = new RuntimeHash();
                returnedReferent.blessId = NameNormalizer.getBlessId(returnedClass);
                RuntimeScalar source = new RuntimeScalar();
                source.type = RuntimeScalarType.HASHREFERENCE;
                source.value = returnedReferent;
                RuntimeScalar returned = RuntimeScalar.copyToMortalReturnSlot(source);

                RuntimeHash unrelated = new RuntimeHash();
                unrelated.blessId = NameNormalizer.getBlessId(cleanupClass);
                unrelated.refCount = Integer.MIN_VALUE;
                DestroyDispatch.callDestroy(unrelated);

                assertEquals(1, returnedReferent.refCount,
                        "unrelated no-DESTROY cleanup must leave the return decrement pending");
                assertTrue(returnedReferent.hasPendingReturnOwner());
                assertEquals(0, destroyCalls.get());

                RuntimeScalar receiver = new RuntimeScalar();
                receiver.set(returned);
                assertFalse(returnedReferent.hasPendingReturnOwner());
                assertEquals(1, returnedReferent.refCount,
                        "the receiver replaces the temporary return owner");
                assertEquals(0, destroyCalls.get());

                receiver.set(new RuntimeScalar());
                MortalList.flushAboveMark();
                assertEquals(1, destroyCalls.get(),
                        "the receiver's final release dispatches DESTROY once");
            } finally {
                GlobalVariable.removeGlobalCodeRefForStashDelete(returnedClass + "::DESTROY");
            }
        }
    }

    @Test
    void receivingReturnConsumesOnlyItsReturnAndRetiredSourceEdges() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            RuntimeScalar source = new RuntimeScalar();
            RuntimeScalar unrelated = new RuntimeScalar();
            source.set(referent.createAnonymousReference());
            unrelated.set(referent.createAnonymousReference());
            PerlOwnerSlot unrelatedOwner = unrelated.countedOwnerSlot();

            RuntimeList returned = RuntimeCode.coerceScalarCallResult(
                    source.getList(), RuntimeContextType.SCALAR);
            RuntimeScalar returnValue = (RuntimeScalar) returned.elements.getFirst();
            MortalList.deferDecrementIfTracked(source);
            MortalList.deferDecrementIfTracked(unrelated);

            RuntimeScalar receiver = new RuntimeScalar();
            receiver.set(returnValue);

            assertEquals(2, referent.refCount,
                    "the receiving cell and unrelated live edge remain counted");
            assertFalse(referent.hasPendingReturnOwner());
            assertTrue(receiver.refCountOwned);
            assertFalse(unrelated.refCountOwned);
            assertEquals(1, runtime.lifecycleState.pendingOwnerReleases.size());
            assertEquals(unrelatedOwner.identity(), runtime.lifecycleState
                    .pendingOwnerReleases.getFirst().ownerSlotRelease.ownerSlotIdentity());
        }
    }
}
