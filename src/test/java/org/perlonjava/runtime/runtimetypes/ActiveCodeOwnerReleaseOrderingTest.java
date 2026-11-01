package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

@Tag("unit")
class ActiveCodeOwnerReleaseOrderingTest {
    @Test
    void activeOwnerIdentityOutlivesTheStackEntryDuringFramePop() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar pad = new RuntimeScalar(42);
            ObservedRuntimeCode code = new ObservedRuntimeCode();
            code.refCount = 0;
            code.capturedScalars = new RuntimeScalar[] {pad};
            code.capturedScalarOwnerSlots = new PerlOwnerSlot[] {
                    pad.retainClosureCaptureSlot(code)
            };

            RuntimeCode.pushActiveCode(code);
            code.releaseCaptures();
            assertEquals(1, pad.captureCount,
                    "the active CODE frame defers capture retirement");

            try (MortalList.ReachabilityQueryMeasurement measurement =
                         MortalList.measureReachabilityQueries()) {
                RuntimeCode.popActiveCode(code);

                assertFalse(code.lastActiveOwnerReleasedWhileOnStack,
                        "the active owner slot must remain until the stack entry is removed");
                assertEquals(0, code.ownerSlotCount(PerlOwnerSlot.Kind.ACTIVE_CODE));
                assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED, code.perlLifecycleState());
                assertNull(code.capturedScalars);
                assertEquals(0, pad.captureCount);
                assertEquals(0, measurement.stats().rootQueries);
                assertEquals(0, measurement.stats().snapshotsBuilt);
            }
        }
    }

    private static final class ObservedRuntimeCode extends RuntimeCode {
        private boolean lastActiveOwnerReleasedWhileOnStack;

        private ObservedRuntimeCode() {
            super((args, context) -> new RuntimeList(), null);
        }

        @Override
        void finishActiveCodeOwnerRelease() {
            if (!hasActiveCodeOwner() && RuntimeCode.isActiveCode(this)) {
                lastActiveOwnerReleasedWhileOnStack = true;
            }
            super.finishActiveCodeOwnerRelease();
        }
    }
}
