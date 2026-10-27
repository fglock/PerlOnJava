package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class CodeSlotOwnerTest {
    private static RuntimeCode code() {
        return new RuntimeCode((args, context) -> new RuntimeList(), null);
    }

    @Test
    void compiledLookupPinOwnsTheGpAfterItsStashNameIsDeleted() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeScalar slot = GlobalVariable.getGlobalCodeRef("CodeOwner::pinned");
            RuntimeCode code = code();
            slot.set(new RuntimeScalar(code));
            GlobalVariable.globalCodeRefs.remove("CodeOwner::pinned");
            assertEquals(1, code.refCount);
            assertEquals(RuntimeBase.PerlLifecycleState.LIVE, code.perlLifecycleState());
            assertEquals(1, code.ownerSlotCount(PerlOwnerSlot.Kind.CODE_SLOT));
            GlobalVariable.clearPinnedCodeRefsForNamespace("CodeOwner::");
            assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED, code.perlLifecycleState());
        }
    }

    @Test
    void firstAssignmentToAnEmptySlotAcquiresExactlyOneEdge() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeScalar slot = new RuntimeScalar();
            GlobalVariable.globalCodeRefs.put("CodeOwner::empty", slot);
            RuntimeCode first = code();
            slot.set(new RuntimeScalar(first));
            assertEquals(1, first.refCount);
            long identity = slot.countedOwnerSlot().identity();
            RuntimeCode second = code();
            slot.set(new RuntimeScalar(second));
            assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED, first.perlLifecycleState());
            assertEquals(1, second.refCount);
            assertEquals(identity, slot.countedOwnerSlot().identity());
            slot.set(RuntimeScalarCache.scalarUndef);
            assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED, second.perlLifecycleState());
            GlobalVariable.globalCodeRefs.remove("CodeOwner::empty");
        }
    }

    @Test
    void wholeGlobNamesShareOnePhysicalCodeOwner() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeCode code = code();
            RuntimeScalar slot = new RuntimeScalar(code);
            GlobalVariable.globalCodeRefs.put("CodeOwner::first", slot);
            GlobalVariable.globalCodeRefs.put("CodeOwner::second", slot);
            assertEquals(1, code.refCount);
            assertEquals(1, code.ownerSlotCount(PerlOwnerSlot.Kind.CODE_SLOT));
            long identity = slot.countedOwnerSlot().identity();
            GlobalVariable.globalCodeRefs.remove("CodeOwner::first");
            assertEquals(1, code.refCount);
            assertEquals(identity, slot.countedOwnerSlot().identity());
            RuntimeScalar.scopeExitCleanup(slot); // A borrow cannot retire a GP slot.
            assertEquals(1, code.refCount);
            try (MortalList.ReachabilityQueryMeasurement measurement = MortalList.measureReachabilityQueries()) {
                GlobalVariable.globalCodeRefs.remove("CodeOwner::second");
                assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED, code.perlLifecycleState());
                assertEquals(0, code.ownerSlotCount(PerlOwnerSlot.Kind.CODE_SLOT));
                assertEquals(0, measurement.stats().rootQueries);
                assertEquals(0, measurement.stats().snapshotsBuilt);
            }
        }
    }

    @Test
    void separateCodeSlotsOwnDistinctEdgesToTheSameCv() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeCode code = code();
            RuntimeScalar first = new RuntimeScalar(code);
            RuntimeScalar second = new RuntimeScalar(code);
            GlobalVariable.globalCodeRefs.put("CodeOwner::first", first);
            GlobalVariable.globalCodeRefs.put("CodeOwner::second", second);
            assertEquals(2, code.refCount);
            assertEquals(2, code.ownerSlotCount(PerlOwnerSlot.Kind.CODE_SLOT));
            assertNotEquals(first.countedOwnerSlot().identity(), second.countedOwnerSlot().identity());
            GlobalVariable.globalCodeRefs.remove("CodeOwner::first");
            assertEquals(1, code.refCount);
            assertEquals(RuntimeBase.PerlLifecycleState.LIVE, code.perlLifecycleState());
            GlobalVariable.globalCodeRefs.entrySet().removeIf(e -> e.getKey().equals("CodeOwner::second"));
            assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED, code.perlLifecycleState());
        }
    }

    @Test
    void localizedSavedSlotKeepsItsOwnerUntilItIsRestored() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeCode original = code();
            RuntimeCode temporary = code();
            RuntimeScalar saved = new RuntimeScalar(original);
            GlobalVariable.globalCodeRefs.put("CodeOwner::localized", saved);
            long identity = saved.countedOwnerSlot().identity();
            GlobalVariable.enterLocalizedCodeRef("CodeOwner::localized", saved);
            GlobalVariable.globalCodeRefs.put("CodeOwner::localized", new RuntimeScalar(temporary));
            assertEquals(1, original.refCount);
            assertEquals(identity, saved.countedOwnerSlot().identity());
            GlobalVariable.globalCodeRefs.put("CodeOwner::localized", saved);
            GlobalVariable.exitLocalizedCodeRef("CodeOwner::localized", saved);
            assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED, temporary.perlLifecycleState());
            assertEquals(1, original.refCount);
            assertEquals(identity, saved.countedOwnerSlot().identity());
            GlobalVariable.globalCodeRefs.clear();
            assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED, original.perlLifecycleState());
        }
    }

    @Test
    void finalNamedOwnerWaitsForTheLastActiveCall() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeCode code = code();
            GlobalVariable.globalCodeRefs.put("CodeOwner::active", new RuntimeScalar(code));
            RuntimeCode.pushActiveCode(code);
            GlobalVariable.globalCodeRefs.remove("CodeOwner::active");
            assertEquals(0, code.refCount);
            assertEquals(RuntimeBase.PerlLifecycleState.LIVE, code.perlLifecycleState());
            try (MortalList.ReachabilityQueryMeasurement measurement = MortalList.measureReachabilityQueries()) {
                RuntimeCode.popActiveCode(code);
                assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED, code.perlLifecycleState());
                assertEquals(0, code.ownerSlotCount(PerlOwnerSlot.Kind.ACTIVE_CODE));
                assertEquals(0, measurement.stats().rootQueries);
                assertEquals(0, measurement.stats().snapshotsBuilt);
            }
        }
    }
}
