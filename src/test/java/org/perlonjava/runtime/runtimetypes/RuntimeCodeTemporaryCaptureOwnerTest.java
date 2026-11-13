package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RuntimeCodeTemporaryCaptureOwnerTest {
    @Test
    void scopeExitUsesOwnerIdentitiesWhenTheCompatibilityCountIsStale() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeCode closure = new RuntimeCode((args, context) -> new RuntimeList(), null);
            RuntimeScalar capturedPad = new RuntimeScalar(42);
            PerlOwnerSlot captureOwner = capturedPad.retainClosureCaptureSlot(closure);
            closure.capturedScalars = new RuntimeScalar[] {capturedPad};
            closure.capturedScalarOwnerSlots = new PerlOwnerSlot[] {captureOwner};
            closure.refCount = 7; // No owner slot backs this compatibility count.

            RuntimeScalar temporary = new RuntimeScalar();
            temporary.type = RuntimeScalarType.CODE;
            temporary.value = closure;

            RuntimeScalar.scopeExitCleanup(temporary);

            assertNull(closure.capturedScalars,
                    "an ownerless temporary CODE releases captures despite a stale count");
            assertFalse(captureOwner.isActive());
        }
    }

    @Test
    void activeCodeOwnerDefersTemporaryCaptureReleaseUntilReturn() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeCode closure = new RuntimeCode((args, context) -> new RuntimeList(), null);
            RuntimeScalar capturedPad = new RuntimeScalar(42);
            PerlOwnerSlot captureOwner = capturedPad.retainClosureCaptureSlot(closure);
            closure.capturedScalars = new RuntimeScalar[] {capturedPad};
            closure.capturedScalarOwnerSlots = new PerlOwnerSlot[] {captureOwner};
            closure.refCount = 7;

            RuntimeScalar temporary = new RuntimeScalar();
            temporary.type = RuntimeScalarType.CODE;
            temporary.value = closure;

            RuntimeCode.pushActiveCode(closure);
            try {
                RuntimeScalar.scopeExitCleanup(temporary);
                assertNotNull(closure.capturedScalars,
                        "the active frame keeps its captured pad alive");
                assertTrue(captureOwner.isActive());
            } finally {
                RuntimeCode.popActiveCode(closure);
            }

            assertNull(closure.capturedScalars,
                    "the deferred request retires captures after the last active owner leaves");
            assertFalse(captureOwner.isActive());
        }
    }
}
