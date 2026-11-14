package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class DestroyOwnerSlotResurrectionTest {
    @Test
    void currentCountedGlobalOwnerIsDetectedWithoutRootWalk() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeHash referent = new RuntimeHash();
            referent.setTrackedRefCount(0);
            RuntimeScalar global = GlobalVariable.getGlobalVariable(
                    "DestroyOwnerSlotResurrectionTest::countedOwner");
            Set<PerlOwnerSlot> ownersAtStart = referent.ownerSlotSnapshot();

            try {
                global.set(referenceTo(referent));
                assertEquals(1, referent.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_CELL));

                {
                    assertTrue(DestroyDispatch.hasNewPerlOwnerSlot(referent, ownersAtStart));
                }
            } finally {
                global.set(new RuntimeScalar());
            }
        }
    }

    @Test
    void durableUntrackedGlobalOwnerIsDetectedWithoutRootWalk() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            String globalName = "DestroyOwnerSlotResurrectionTest::untrackedRoot";
            RuntimeArray packageRoot = new RuntimeArray();
            GlobalVariable.globalArrays.put(globalName, packageRoot);
            RuntimeArray referent = new RuntimeArray();
            referent.markRefCountUntracked();
            RuntimeScalar globalOwner = referent.createAnonymousReference();
            Set<PerlOwnerSlot> ownersAtStart = referent.ownerSlotSnapshot();

            try {
                packageRoot.elements.add(globalOwner);
                assertTrue(referent.hasUntrackedReferenceOwnerSlotIdentity());

                {
                    assertTrue(DestroyDispatch.hasNewPerlOwnerSlot(referent, ownersAtStart));
                    assertTrue(referent.hasUntrackedReferenceOwnerSlotIdentity());
                }
            } finally {
                packageRoot.elements.clear();
                GlobalVariable.globalArrays.remove(globalName);
            }
        }
    }

    @Test
    void currentCountedOwnerIsDetectedOutsideGlobalTeardownWithoutRootWalk() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeHash referent = new RuntimeHash();
            referent.setTrackedRefCount(0);
            RuntimeScalar global = GlobalVariable.getGlobalVariable(
                    "DestroyOwnerSlotResurrectionTest::nonGlobalCountedOwner");

            try {
                global.set(referenceTo(referent));
                assertEquals(1, referent.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_CELL));

                {
                    assertTrue(DestroyDispatch.hasEscapedPerlOwner(referent, false, null),
                            "a durable current owner slot keeps a local DESTROY from finalizing");
                }
            } finally {
                global.set(new RuntimeScalar());
            }
        }
    }

    @Test
    void detachedCountedCellDoesNotResurrectTheReferent() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeHash referent = new RuntimeHash();
            referent.setTrackedRefCount(0);
            RuntimeScalar detachedCell = new RuntimeScalar();
            detachedCell.localBindingExists = true;
            detachedCell.set(referenceTo(referent));
            Set<PerlOwnerSlot> ownersAtStart = Set.of();
            detachedCell.localBindingExists = false;

            try {
                assertFalse(DestroyDispatch.hasNewPerlOwnerSlot(referent, ownersAtStart),
                        "a scalar cell outside any live slot is stale bookkeeping");
                assertFalse(referent.hasSemanticCaptureOwner(),
                        "the detached scalar has no live closure capture");
                String ownerSlotState = referent.ownerSlotSnapshot().stream()
                        .map(slot -> slot.kind() + "="
                                + DestroyDispatch.isCurrentPerlOwnerSlot(referent, slot))
                        .toList().toString();
                assertFalse(DestroyDispatch.hasCurrentPerlOwnerSlot(referent), ownerSlotState);
                assertFalse(DestroyDispatch.hasEscapedPerlOwner(referent, false, null),
                        "a detached counted cell does not escape a local DESTROY");
            } finally {
                detachedCell.set(new RuntimeScalar());
            }
        }
    }

    @Test
    void temporaryArgumentOwnerIsNotResurrection() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeHash referent = new RuntimeHash();
            referent.setTrackedRefCount(0);
            Set<PerlOwnerSlot> ownersAtStart = referent.ownerSlotSnapshot();
            RuntimeScalar staged = RuntimeScalar.retainStagedArgumentValue(referenceTo(referent));

            try {
                assertEquals(1, referent.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_VALUE));
                {
                    assertFalse(DestroyDispatch.hasNewPerlOwnerSlot(referent, ownersAtStart),
                            "a staged call argument does not outlive DESTROY as a Perl owner");
                }
            } finally {
                RuntimeScalar.releaseStagedArgumentValue(staged);
                MortalList.flush();
            }
        }
    }

    private static RuntimeScalar referenceTo(RuntimeBase referent) {
        RuntimeScalar scalar = new RuntimeScalar();
        scalar.type = RuntimeScalarType.HASHREFERENCE;
        scalar.value = referent;
        return scalar;
    }
}
