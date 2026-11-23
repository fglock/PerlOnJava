package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class OwnerSlotKindIndexTest {
    @Test
    void packageAggregateAndActiveCodeIndexesTrackSlotLifecycle() {
        RuntimeArray array = new RuntimeArray();
        PerlOwnerSlot packageOwner = new PerlOwnerSlot(
                PerlOwnerSlot.Kind.PACKAGE_AGGREGATE_SLOT);
        packageOwner.acquirePackageAggregateIdentity(array);
        assertEquals(1,
                array.ownerSlotCount(PerlOwnerSlot.Kind.PACKAGE_AGGREGATE_SLOT));
        assertCountsMatchLedger(array);
        packageOwner.release();
        assertEquals(0,
                array.ownerSlotCount(PerlOwnerSlot.Kind.PACKAGE_AGGREGATE_SLOT));

        RuntimeCode code = new RuntimeCode((args, context) -> new RuntimeList(), null);
        PerlOwnerSlot activeCodeOwner = new PerlOwnerSlot(PerlOwnerSlot.Kind.ACTIVE_CODE);
        activeCodeOwner.acquireActiveCodeIdentity(code);
        assertEquals(1, code.ownerSlotCount(PerlOwnerSlot.Kind.ACTIVE_CODE));
        assertCountsMatchLedger(code);
        activeCodeOwner.release();
        assertEquals(0, code.ownerSlotCount(PerlOwnerSlot.Kind.ACTIVE_CODE));
        assertCountsMatchLedger(code);
    }

    @Test
    void countedSlotReclassificationAndReleaseKeepPerKindIndexCurrent() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeHash referent = new RuntimeHash();
            referent.setBlessId(NameNormalizer.getBlessId(
                    "OwnerSlotKindIndexTest::Referent"));
            RuntimeScalar owner = new RuntimeScalar();
            RuntimeScalar reference = new RuntimeScalar();
            reference.type = RuntimeScalarType.HASHREFERENCE;
            reference.value = referent;
            owner.set(reference);

            PerlOwnerSlot slot = owner.countedOwnerSlot();
            assertEquals(1, referent.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_CELL));
            assertEquals(0, referent.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_VALUE));
            assertEquals(1, referent.countedOwnerSlotCount());
            assertCountsMatchLedger(referent);

            slot.reclassifyCountedIdentity(PerlOwnerSlot.Kind.ARGUMENT_VALUE);
            assertEquals(0, referent.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_CELL));
            assertEquals(1, referent.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_VALUE));
            assertEquals(1, referent.countedOwnerSlotCount());
            assertCountsMatchLedger(referent);

            slot.reclassifyCountedIdentity(PerlOwnerSlot.Kind.RETURN_VALUE);
            assertEquals(0, referent.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_VALUE));
            assertEquals(1, referent.ownerSlotCount(PerlOwnerSlot.Kind.RETURN_VALUE));
            assertEquals(1, referent.countedOwnerSlotCount());
            assertCountsMatchLedger(referent);

            owner.set(new RuntimeScalar());
            assertEquals(0, referent.ownerSlotCount(PerlOwnerSlot.Kind.RETURN_VALUE));
            assertEquals(0, referent.countedOwnerSlotCount());
            assertCountsMatchLedger(referent);
        }
    }

    @Test
    void transientUntrackedOwnerCountTracksStagingAndMortalReturnTransitions() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeScalar referent = new RuntimeScalar("transient referent");
            RuntimeScalar reference = referenceTo(referent);
            RuntimeScalar staged = RuntimeScalar.retainStagedArgumentValue(reference);
            PerlOwnerSlot stagedSlot = staged.untrackedReferenceOwnerSlot();

            assertNotNull(stagedSlot);
            assertEquals(1, referent.transientUntrackedReferenceOwnerCount());
            assertEquals(1, referent.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));
            RuntimeScalar.releaseStagedArgumentValue(staged);
            assertEquals(0, referent.transientUntrackedReferenceOwnerCount());
            assertEquals(0, referent.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));

            MortalList.pushMark();
            RuntimeScalar returned = RuntimeScalar.copyToMortalReturnSlot(reference);
            PerlOwnerSlot returnSlot = returned.untrackedReferenceOwnerSlot();
            assertNotNull(returnSlot);
            assertEquals(1, referent.transientUntrackedReferenceOwnerCount());
            returned.finishMortalReturnHandoff();
            assertEquals(1, referent.transientUntrackedReferenceOwnerCount(),
                    "the return owner remains transient until its handoff owner is released");
            returned.releaseMortalReturnValueOwner();
            assertEquals(0, referent.transientUntrackedReferenceOwnerCount());
            MortalList.popAndFlush();
            assertEquals(0, referent.transientUntrackedReferenceOwnerCount());
        }
    }

    @Test
    void stashCodeSlotCheckUsesTheIndexedCodeSlotLedger() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeCode code = new RuntimeCode((args, context) -> new RuntimeList(), null);
            RuntimeScalar stashSlot = new RuntimeScalar();
            stashSlot.type = RuntimeScalarType.CODE;
            stashSlot.value = code;

            stashSlot.retainStashCodeSlotBinding();
            assertEquals(1, code.ownerSlotCount(PerlOwnerSlot.Kind.CODE_SLOT));
            assertCountsMatchLedger(code);
            assertTrue(code.hasStashCodeSlotOwner());

            stashSlot.releaseStashCodeSlotBinding();
            MortalList.flush();
            assertEquals(0, code.ownerSlotCount(PerlOwnerSlot.Kind.CODE_SLOT));
            assertFalse(code.hasStashCodeSlotOwner());
            assertCountsMatchLedger(code);
        }
    }

    private static RuntimeScalar referenceTo(RuntimeBase value) {
        RuntimeScalar reference = new RuntimeScalar();
        reference.type = RuntimeScalarType.REFERENCE;
        reference.value = value;
        return reference;
    }

    private static void assertCountsMatchLedger(RuntimeBase referent) {
        java.util.Set<PerlOwnerSlot> slots = referent.ownerSlotSnapshot();
        for (PerlOwnerSlot.Kind kind : PerlOwnerSlot.Kind.values()) {
            int ledgerCount = 0;
            for (PerlOwnerSlot slot : slots) {
                if (slot.kind() == kind) ledgerCount++;
            }
            assertEquals(ledgerCount, referent.ownerSlotCount(kind), kind.toString());
        }
    }
}
