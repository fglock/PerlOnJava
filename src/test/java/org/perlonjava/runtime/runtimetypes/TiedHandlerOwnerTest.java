package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class TiedHandlerOwnerTest {
    @Test
    void finalHashDestructionReleasesMagicDespiteAnExpressionBorrow() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash handler = new RuntimeHash();
            handler.setTrackedRefCount(0);
            RuntimeHash outer = new RuntimeHash();
            outer.type = RuntimeHash.TIED_HASH;
            outer.elements = new TieHash("Handler", new RuntimeHash(), handler.createAnonymousReference());
            RuntimeScalar weak = new RuntimeScalar();
            weak.set(handler.createAnonymousReference());
            WeakRefRegistry.weaken(weak);
            RuntimeScalar borrowed = outer.createAnonymousReference();
            MortalList.pushTemporaryRoot(borrowed);
            try {
                outer.setTrackedRefCount(0);
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
            try {
                hash.setTrackedRefCount(0);
                DestroyDispatch.callDestroyFromCountedOwner(hash);
                array.setTrackedRefCount(0);
                DestroyDispatch.callDestroyFromCountedOwner(array);
                // Container finalization queues child decrements; the boundary
                // drains those identified releases before observers are checked.
                MortalList.flush();
                assertEquals(RuntimeScalarType.UNDEF, hashWeak.type);
                assertEquals(RuntimeScalarType.UNDEF, arrayWeak.type);
                assertFalse(hashChild.hasOwnerSlots());
                assertFalse(arrayChild.hasOwnerSlots());
            } finally {
                MortalList.popTemporaryRoot(borrowedArray);
                MortalList.popTemporaryRoot(borrowedHash);
            }
        }
    }

}
