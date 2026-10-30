package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class TiedHandlerOwnerTest {
    @Test
    void wrappersHaveDistinctOwnersAndDoNotBorrowTheirSourceCell() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash handler = new RuntimeHash();
            handler.refCount = 0;
            RuntimeScalar source = handler.createAnonymousReference();
            TieHash hash = new TieHash("Handler", new RuntimeHash(), source);
            TieArray array = new TieArray("Handler", new RuntimeArray(), source, new RuntimeArray());
            TieScalar scalar = new TieScalar("Handler", new RuntimeScalar(), source);
            TieHandle handle = new TieHandle("Handler", new RuntimeIO(), source);
            assertEquals(4, handler.ownerSlotCount(PerlOwnerSlot.Kind.TIE_HANDLER));
            assertEquals(4, handler.ownerSlotSnapshot().stream()
                    .map(PerlOwnerSlot::identity).distinct().count());
            assertEquals(4, handler.refCount);
            source.set(RuntimeScalarCache.scalarUndef);
            assertSame(handler, hash.getSelf().value);
            assertSame(handler, array.getSelf().value);
            assertSame(handler, scalar.getSelf().value);
            RuntimeScalar weak = new RuntimeScalar();
            weak.set(handler.createAnonymousReference());
            WeakRefRegistry.weaken(weak);
            hash.releaseTiedObject();
            assertSame(handler, hash.getSelf().value,
                    "retired magic can still borrow its snapshot during local restoration");
            assertFalse(hash.getSelf().refCountOwned);
            hash.releaseTiedObject();
            array.releaseTiedObject();
            array.releaseTiedObject();
            assertEquals(2, handler.refCount);
            assertEquals(2, handler.ownerSlotCount(PerlOwnerSlot.Kind.TIE_HANDLER));
            scalar.releaseTiedObject();
            scalar.releaseTiedObject();
            assertEquals(RuntimeScalarType.HASHREFERENCE, weak.type);
            try (MortalList.ReachabilityQueryMeasurement measurement = MortalList.measureReachabilityQueries()) {
                handle.releaseTiedObject();
                handle.releaseTiedObject();
                assertEquals(RuntimeScalarType.UNDEF, weak.type);
                assertFalse(handler.hasOwnerSlots());
                assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED, handler.perlLifecycleState());
                assertEquals(0, measurement.stats().rootQueries);
                assertEquals(0, measurement.stats().snapshotsBuilt);
            }
        }
    }

    @Test
    void finalHashDestructionReleasesMagicDespiteAnExpressionBorrow() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash handler = new RuntimeHash();
            handler.refCount = 0;
            RuntimeHash outer = new RuntimeHash();
            outer.type = RuntimeHash.TIED_HASH;
            outer.elements = new TieHash("Handler", new RuntimeHash(), handler.createAnonymousReference());
            RuntimeScalar weak = new RuntimeScalar();
            weak.set(handler.createAnonymousReference());
            WeakRefRegistry.weaken(weak);
            RuntimeScalar borrowed = outer.createAnonymousReference();
            MortalList.pushTemporaryRoot(borrowed);
            try {
                outer.refCount = Integer.MIN_VALUE;
                DestroyDispatch.callDestroyFromCountedOwner(outer);
                assertEquals(RuntimeScalarType.UNDEF, weak.type);
                assertFalse(handler.hasOwnerSlots());
                assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED, outer.perlLifecycleState());
            } finally {
                MortalList.popTemporaryRoot(borrowed);
            }
        }
    }
    @Test
    void finalContainerReleaseDropsItsChildSlotsDespiteExpressionBorrows() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash hashChild = new RuntimeHash();
            RuntimeHash hash = new RuntimeHash();
            hash.put("child", hashChild.createAnonymousReference());
            RuntimeScalar hashWeak = new RuntimeScalar();
            hashWeak.set(hashChild.createAnonymousReference());
            WeakRefRegistry.weaken(hashWeak);
            RuntimeHash arrayChild = new RuntimeHash();
            RuntimeArray array = new RuntimeArray();
            array.push(arrayChild.createAnonymousReference());
            RuntimeScalar arrayWeak = new RuntimeScalar();
            arrayWeak.set(arrayChild.createAnonymousReference());
            WeakRefRegistry.weaken(arrayWeak);
            RuntimeScalar borrowedHash = hash.createAnonymousReference();
            RuntimeScalar borrowedArray = array.createAnonymousReference();
            MortalList.pushTemporaryRoot(borrowedHash);
            MortalList.pushTemporaryRoot(borrowedArray);
            try (MortalList.ReachabilityQueryMeasurement measurement = MortalList.measureReachabilityQueries()) {
                hash.refCount = Integer.MIN_VALUE;
                DestroyDispatch.callDestroyFromCountedOwner(hash);
                array.refCount = Integer.MIN_VALUE;
                DestroyDispatch.callDestroyFromCountedOwner(array);
                // Container finalization queues child decrements; the boundary
                // drains those identified releases before observers are checked.
                MortalList.flush();
                assertEquals(RuntimeScalarType.UNDEF, hashWeak.type);
                assertEquals(RuntimeScalarType.UNDEF, arrayWeak.type);
                assertFalse(hashChild.hasOwnerSlots());
                assertFalse(arrayChild.hasOwnerSlots());
                assertEquals(0, measurement.stats().rootQueries);
            } finally {
                MortalList.popTemporaryRoot(borrowedArray);
                MortalList.popTemporaryRoot(borrowedHash);
            }
        }
    }

}
