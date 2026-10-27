package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class PerlCallTransferOwnerTest {
    @Test
    void scalarReturnOwnsItsReferentUntilTheReceivingCellConsumesIt() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            RuntimeScalar source = new RuntimeScalar();
            source.set(referent.createAnonymousReference());
            RuntimeList returned = RuntimeCode.coerceScalarCallResult(
                    source.getList(), RuntimeContextType.SCALAR);
            RuntimeScalar value = (RuntimeScalar) returned.elements.getFirst();
            assertNotSame(source, value);
            assertTrue(referent.hasPendingReturnOwner());
            assertEquals(PerlOwnerSlot.Kind.RETURN_VALUE,
                    runtime.lifecycleState.pendingOwnerReleases.getLast().ownerSlotRelease.kind());

            MortalList.deferDecrementIfTracked(source);
            RuntimeScalar receiver = new RuntimeScalar();
            receiver.set(value);
            assertEquals(1, referent.refCount);
            assertFalse(referent.hasPendingReturnOwner());
            assertTrue(receiver.countedOwnerSlot().isActive());
            assertEquals(RuntimeBase.PerlLifecycleState.LIVE, referent.perlLifecycleState());
        }
    }

    @Test
    void listReturnHasDistinctOwnersAndForwardingDoesNotDuplicateThem() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            RuntimeScalar source = new RuntimeScalar();
            source.set(referent.createAnonymousReference());
            RuntimeList input = new RuntimeList();
            input.elements.add(source);
            input.elements.add(source);
            RuntimeList returned = RuntimeCode.coerceScalarCallResult(input, RuntimeContextType.LIST);
            assertNotSame(returned.elements.get(0), returned.elements.get(1));
            assertEquals(2, runtime.lifecycleState.pendingOwnerReleases.size());
            PerlOwnerSlot.PendingRelease first = runtime.lifecycleState.pendingOwnerReleases.get(0).ownerSlotRelease;
            PerlOwnerSlot.PendingRelease second = runtime.lifecycleState.pendingOwnerReleases.get(1).ownerSlotRelease;
            assertNotEquals(first.ownerSlotIdentity(), second.ownerSlotIdentity());
            assertEquals(PerlOwnerSlot.Kind.RETURN_VALUE, first.kind());
            assertEquals(PerlOwnerSlot.Kind.RETURN_VALUE, second.kind());
            assertSame(returned, RuntimeCode.coerceScalarCallResult(returned, RuntimeContextType.LIST));
            assertEquals(2, runtime.lifecycleState.pendingOwnerReleases.size());
            assertEquals(3, referent.refCount);
        }
    }

    @Test
    void trackedCodeReturnGetsAnIndependentMortalOwner() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeCode code = new RuntimeCode((args, context) -> new RuntimeList(), null);
            code.refCount = 0;
            RuntimeScalar source = new RuntimeScalar();
            source.set(new RuntimeScalar(code));
            RuntimeList returned = RuntimeCode.coerceScalarCallResult(source.getList(), RuntimeContextType.SCALAR);
            RuntimeScalar value = (RuntimeScalar) returned.elements.getFirst();
            assertNotSame(source, value);
            assertSame(code, value.value);
            assertTrue(code.hasPendingReturnOwner());
            MortalList.deferDecrementIfTracked(source);
            RuntimeScalar receiver = new RuntimeScalar();
            receiver.set(value);
            assertEquals(1, code.refCount);
            assertFalse(code.hasPendingReturnOwner());
            assertSame(code, receiver.value);
        }
    }

    @Test
    void stagedRepeatedValuesHaveIndependentArgumentOwnersAndFinishOnce() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            RuntimeScalar source = new RuntimeScalar();
            source.set(referent.createAnonymousReference());
            RuntimeList values = new RuntimeList();
            values.elements.add(source);
            values.elements.add(source);
            RuntimeCode.CallArguments frame = RuntimeCode.newCallArguments(2);
            frame.add(values);
            frame.add(new RuntimeScalar(1));
            assertEquals(2, referent.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_VALUE));
            assertEquals(3, referent.refCount);
            frame.finish();
            assertEquals(0, referent.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_VALUE));
            assertEquals(2, runtime.lifecycleState.pendingOwnerReleases.size());
            for (RuntimeBase.PendingOwnerRelease pending : runtime.lifecycleState.pendingOwnerReleases) {
                assertEquals(PerlOwnerSlot.Kind.ARGUMENT_VALUE, pending.ownerSlotRelease.kind());
            }
            frame.finish();
            assertEquals(2, runtime.lifecycleState.pendingOwnerReleases.size());
            MortalList.flushAboveMark();
            assertEquals(1, referent.refCount);
            assertTrue(source.countedOwnerSlot().isActive());
        }
    }

    @Test
    void lvalueReturnContinuesToBorrowTheOriginalCell() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar source = new RuntimeScalar();
            source.set(new RuntimeHash().createAnonymousReference());
            RuntimeList returned = RuntimeCode.coerceScalarCallResult(
                    source.getList(), RuntimeContextType.LVALUE, RuntimeContextType.LVALUE, false);
            assertSame(source, returned.elements.getFirst());
            assertTrue(runtime.lifecycleState.pendingOwnerReleases.isEmpty());
        }
    }
}
