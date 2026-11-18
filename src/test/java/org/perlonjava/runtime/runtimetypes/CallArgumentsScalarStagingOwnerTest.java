package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class CallArgumentsScalarStagingOwnerTest {
    @Test
    void mortalScalarArgumentOwnsItsReferentAcrossLaterArgumentCleanup() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            RuntimeScalar source = new RuntimeScalar();
            source.set(referent.createAnonymousReference());
            RuntimeScalar temporary = RuntimeScalar.copyToMortalReturnSlot(source);
            RuntimeCode.CallArguments arguments = RuntimeCode.newCallArguments(2);

            arguments.add(temporary);
            arguments.add(new RuntimeScalar(1));

            assertTrue(temporary.isMortalReturnSnapshot());
            assertEquals(1, referent.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_VALUE));
            assertEquals(RuntimeBase.PerlLifecycleState.LIVE, referent.perlLifecycleState());

            // A later argument expression can drain the return owner before
            // the call has materialized its @_ aliases.
            MortalList.flushAboveMark();
            assertEquals(0, referent.ownerSlotCount(PerlOwnerSlot.Kind.RETURN_VALUE));
            assertEquals(1, referent.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_VALUE));
            assertEquals(RuntimeBase.PerlLifecycleState.LIVE, referent.perlLifecycleState());

            arguments.finish();
            MortalList.flushAboveMark();

            assertEquals(0, referent.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_VALUE));
            assertEquals(0, runtime.positiveOwnerSlotReferentCount(referent));
            assertTrue(source.countedOwnerSlot().isActive());
        }
    }

    @Test
    void persistentScalarArgumentContinuesToBorrowItsCallerCell() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            RuntimeScalar source = new RuntimeScalar();
            source.set(referent.createAnonymousReference());
            RuntimeCode.CallArguments arguments = RuntimeCode.newCallArguments(2);

            arguments.add(source);

            assertEquals(1, referent.refCount);
            assertEquals(0, referent.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_VALUE));

            arguments.finish();
            assertEquals(1, referent.refCount);
            assertTrue(source.countedOwnerSlot().isActive());
        }
    }

    @Test
    void finalMortalScalarArgumentSurvivesCalleeCleanup() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            RuntimeScalar borrowedSource = new RuntimeScalar(referent.createAnonymousReference());
            assertFalse(borrowedSource.refCountOwned);
            RuntimeScalar temporary = RuntimeScalar.copyToMortalReturnSlot(borrowedSource);
            RuntimeCode.CallArguments arguments = RuntimeCode.newCallArguments(1);

            arguments.add(temporary);

            assertEquals(0, referent.ownerSlotCount(PerlOwnerSlot.Kind.RETURN_VALUE));
            assertEquals(1, referent.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_VALUE));

            // Safe cleanup inside the callee must preserve the staged owner.
            MortalList.flushAboveMark();
            assertEquals(0, referent.ownerSlotCount(PerlOwnerSlot.Kind.RETURN_VALUE));
            assertEquals(1, referent.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_VALUE));
            assertEquals(RuntimeBase.PerlLifecycleState.LIVE, referent.perlLifecycleState());

            arguments.finish();
            MortalList.flushAboveMark();
            assertEquals(0, referent.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_VALUE));
            assertEquals(0, runtime.positiveOwnerSlotReferentCount(referent));
        }
    }
}
