package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class PerlArgumentUnwindOwnerTest {
    @Test
    void argumentArrayReplacementReleasesInsertedOwnersButBorrowsCallerCells() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash callerReferent = new RuntimeHash();
            RuntimeScalar caller = new RuntimeScalar();
            caller.set(callerReferent.createAnonymousReference());
            PerlOwnerSlot callerSlot = caller.countedOwnerSlot();
            RuntimeArray arguments = new RuntimeArray();
            caller.setArrayOfAlias(arguments);
            RuntimeHash inserted = new RuntimeHash();
            arguments.push(inserted.createAnonymousReference());
            assertEquals(1, inserted.refCount);
            arguments.setFromList(new RuntimeList());
            assertTrue(callerSlot.isActive());
            assertTrue(caller.refCountOwned);
            assertEquals(1, callerReferent.refCount);
            assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED, inserted.perlLifecycleState());
            caller.setArrayOfAlias(arguments);
            RuntimeHash nextInserted = new RuntimeHash();
            arguments.push(nextInserted.createAnonymousReference());
            arguments.undefine();
            assertTrue(callerSlot.isActive());
            assertEquals(1, callerReferent.refCount);
            assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED, nextInserted.perlLifecycleState());
            caller.set(new RuntimeScalar());
            assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED, callerReferent.perlLifecycleState());
        }
    }

    @Test
    void abandonedInnerFrameReleasesOnlyItsOwnRepeatedArgumentOwners() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            RuntimeScalar source = new RuntimeScalar();
            source.set(referent.createAnonymousReference());
            RuntimeList values = new RuntimeList();
            values.elements.add(source);
            values.elements.add(source);
            RuntimeCode.CallArguments outer = RuntimeCode.newCallArguments(2);
            outer.add(values);
            RuntimeCode.CallArguments inner = RuntimeCode.newCallArguments(2);
            inner.add(values);
            assertEquals(5, referent.refCount);
            assertEquals(4, runtime.positiveOwnerSlotReferentCount(referent));
            RuntimeException failure = new RuntimeException("argument failed");
            assertSame(failure, inner.abandon(failure));
            assertEquals(3, referent.refCount);
            assertEquals(2, runtime.positiveOwnerSlotReferentCount(referent));
            assertEquals(2, referent.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_VALUE));
            assertEquals(java.util.List.of(outer), runtime.executionState().callArgumentFrames);
            assertTrue(runtime.lifecycleState.pendingOwnerReleases.isEmpty());
            inner.finish();
            assertEquals(3, referent.refCount);
            outer.finish();
            MortalList.flushAboveMark();
            assertEquals(1, referent.refCount);
            assertFalse(runtime.retainsPositiveOwnerSlotReferent(referent));
            assertTrue(runtime.executionState().callArgumentFrames.isEmpty());
        }
    }

    @Test
    void frameExitRetiresAbandonedStagingButPreservesItsCallersFrame() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeCode.CallArguments caller = RuntimeCode.newCallArguments(2);
            RuntimeCode.pushArgs(new RuntimeArray());
            RuntimeCode.CallArguments abandoned = RuntimeCode.newCallArguments(2);
            RuntimeHash referent = new RuntimeHash();
            RuntimeScalar source = new RuntimeScalar();
            source.set(referent.createAnonymousReference());
            abandoned.add(source.getList());
            assertEquals(2, referent.refCount);
            RuntimeCode.popArgs();
            assertEquals(java.util.List.of(caller), runtime.executionState().callArgumentFrames);
            assertEquals(0, referent.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_VALUE));
            assertEquals(1, runtime.lifecycleState.pendingOwnerReleases.size());
            abandoned.finish();
            assertEquals(1, runtime.lifecycleState.pendingOwnerReleases.size());
            MortalList.flushAboveMark();
            assertEquals(1, referent.refCount);
            caller.finish();
        }
    }

    @Test
    void nonLocalReturnKeepsItsMortalResultUntilTheReceiverConsumesIt() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeCode.CallArguments abandoned = RuntimeCode.newCallArguments(2);
            RuntimeHash referent = new RuntimeHash();
            RuntimeScalar source = new RuntimeScalar();
            source.set(referent.createAnonymousReference());
            RuntimeScalar returned = RuntimeScalar.copyToMortalReturnSlot(source);
            PerlNonLocalReturnException transfer = new PerlNonLocalReturnException(returned);
            assertSame(transfer, abandoned.abandon(transfer));
            assertTrue(referent.hasPendingReturnOwner());
            assertTrue(runtime.retainsPositiveOwnerSlotReferent(referent));
            assertEquals(2, referent.refCount);
            RuntimeScalar receiver = new RuntimeScalar();
            receiver.set(returned);
            assertFalse(referent.hasPendingReturnOwner());
            assertFalse(runtime.retainsPositiveOwnerSlotReferent(referent));
            assertEquals(2, referent.refCount);
            assertSame(referent, receiver.value);
        }
    }

    @Test
    void stagedArgumentRetainsItsChildrenUntilItsExplicitRelease() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash child = new RuntimeHash();
            RuntimeScalar childOwner = new RuntimeScalar();
            childOwner.set(child.createAnonymousReference());
            RuntimeScalar weakChild = new RuntimeScalar();
            weakChild.set(childOwner);
            WeakRefRegistry.weaken(weakChild);
            RuntimeHash parent = new RuntimeHash();
            // put consumes an element cell; the caller keeps its own SV.
            parent.put("child", new RuntimeScalar(childOwner));
            RuntimeScalar parentOwner = new RuntimeScalar();
            parentOwner.set(parent.createAnonymousReference());
            RuntimeCode.CallArguments arguments = RuntimeCode.newCallArguments(2);
            arguments.add(parentOwner.getList());
            MortalList.deferDecrementIfTracked(childOwner);
            MortalList.deferDecrementIfTracked(parentOwner);
            MortalList.flushAboveMark();
            ReachabilityWalker.sweepWeakRefs(true);
            assertEquals(RuntimeScalarType.HASHREFERENCE, weakChild.type);
            assertEquals(1, parent.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_VALUE));
            arguments.abandon(new RuntimeException("later argument failed"));
            assertEquals(RuntimeScalarType.UNDEF, weakChild.type);
            assertFalse(runtime.retainsPositiveOwnerSlotReferent(parent));
        }
    }
}
