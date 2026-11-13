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
            referent.refCount = 0;
            RuntimeScalar global = GlobalVariable.getGlobalVariable(
                    "DestroyOwnerSlotResurrectionTest::countedOwner");
            Set<PerlOwnerSlot> ownersAtStart = referent.ownerSlotSnapshot();

            try {
                global.set(referenceTo(referent));
                assertEquals(1, referent.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_CELL));

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    assertTrue(DestroyDispatch.hasNewPerlOwnerSlot(referent, ownersAtStart));
                    assertEquals(0, measurement.stats().rootQueries,
                            "a counted slot identity should replace the root walk");
                    assertEquals(0, measurement.stats().snapshotsBuilt,
                            "a counted slot identity should not build a root snapshot");
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
            referent.refCount = -1;
            RuntimeScalar globalOwner = referent.createAnonymousReference();
            Set<PerlOwnerSlot> ownersAtStart = referent.ownerSlotSnapshot();

            try {
                packageRoot.elements.add(globalOwner);
                assertTrue(referent.hasUntrackedReferenceOwnerSlotIdentity());

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    assertTrue(DestroyDispatch.hasNewPerlOwnerSlot(referent, ownersAtStart));
                    assertTrue(referent.hasUntrackedReferenceOwnerSlotIdentity());
                    assertEquals(0, measurement.stats().rootQueries,
                            "owner-slot resurrection checks must not walk Perl roots");
                    assertEquals(0, measurement.stats().snapshotsBuilt,
                            "owner-slot resurrection checks must not build root snapshots");
                }
            } finally {
                packageRoot.elements.clear();
                GlobalVariable.globalArrays.remove(globalName);
                MortalList.invalidateAllRootSnapshots();
            }
        }
    }

    @Test
    void currentCountedOwnerIsDetectedOutsideGlobalTeardownWithoutRootWalk() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeHash referent = new RuntimeHash();
            referent.refCount = 0;
            RuntimeScalar global = GlobalVariable.getGlobalVariable(
                    "DestroyOwnerSlotResurrectionTest::nonGlobalCountedOwner");

            try {
                global.set(referenceTo(referent));
                assertEquals(1, referent.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_CELL));

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    assertTrue(DestroyDispatch.hasEscapedPerlOwner(referent, false, null),
                            "a durable current owner slot keeps a local DESTROY from finalizing");
                    assertEquals(0, measurement.stats().rootQueries,
                            "local DESTROY owner checks must not walk Perl roots");
                    assertEquals(0, measurement.stats().snapshotsBuilt,
                            "local DESTROY owner checks must not build root snapshots");
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
            referent.refCount = 0;
            RuntimeScalar detachedCell = new RuntimeScalar();
            detachedCell.localBindingExists = true;
            detachedCell.set(referenceTo(referent));
            Set<PerlOwnerSlot> ownersAtStart = Set.of();
            detachedCell.localBindingExists = false;

            try (MortalList.ReachabilityQueryMeasurement measurement =
                         MortalList.measureReachabilityQueries()) {
                assertFalse(DestroyDispatch.hasNewPerlOwnerSlot(referent, ownersAtStart),
                        "a scalar cell outside any live slot is stale bookkeeping");
                assertEquals(0, measurement.stats().rootQueries,
                        "stale scalar slots should be rejected without a root walk");
                assertFalse(referent.hasSemanticCaptureOwner(),
                        "the detached scalar has no live closure capture");
                String ownerSlotState = referent.ownerSlotSnapshot().stream()
                        .map(slot -> slot.kind() + "="
                                + DestroyDispatch.isCurrentPerlOwnerSlot(referent, slot))
                        .toList().toString();
                assertFalse(DestroyDispatch.hasCurrentPerlOwnerSlot(referent), ownerSlotState);
                assertFalse(DestroyDispatch.hasEscapedPerlOwner(referent, false, null),
                        "a detached counted cell does not escape a local DESTROY");
                assertEquals(0, measurement.stats().rootQueries,
                        "local DESTROY must reject stale cells without a root walk");
            } finally {
                detachedCell.set(new RuntimeScalar());
            }
        }
    }

    @Test
    void temporaryArgumentOwnerIsNotResurrection() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeHash referent = new RuntimeHash();
            referent.refCount = 0;
            referent.activateOwnerTracking();
            Set<PerlOwnerSlot> ownersAtStart = referent.ownerSlotSnapshot();
            RuntimeScalar staged = RuntimeScalar.retainStagedArgumentValue(referenceTo(referent));

            try {
                assertEquals(1, referent.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_VALUE));
                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    assertFalse(DestroyDispatch.hasNewPerlOwnerSlot(referent, ownersAtStart),
                            "a staged call argument does not outlive DESTROY as a Perl owner");
                    assertEquals(0, measurement.stats().rootQueries,
                            "temporary-owner checks must not walk Perl roots");
                    assertEquals(0, measurement.stats().snapshotsBuilt,
                            "temporary-owner checks must not build root snapshots");
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
