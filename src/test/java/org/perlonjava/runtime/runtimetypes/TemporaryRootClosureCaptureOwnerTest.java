package org.perlonjava.runtime.runtimetypes;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class TemporaryRootClosureCaptureOwnerTest {
    @Test
    void aggregateCaptureOwnerHasCurrentOwnerSlot() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray capturedPad = new RuntimeArray();
            RuntimeCode closure = new RuntimeCode(null, List.of());
            PerlOwnerSlot captureOwner = capturedPad.retainClosureCaptureOwner(closure);
            closure.capturedAggregates = new RuntimeBase[] {capturedPad};
            closure.capturedAggregateOwnerSlots = new PerlOwnerSlot[] {captureOwner};
            closure.captureFieldsRecorded = true;

            assertRootCaptureProof(capturedPad, closure, captureOwner);
        }
    }

    @Test
    void scalarCaptureOwnerHasCurrentOwnerSlot() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar capturedPad = new RuntimeScalar();
            RuntimeCode closure = new RuntimeCode(null, List.of());
            PerlOwnerSlot captureOwner = capturedPad.retainClosureCaptureSlot(closure);
            closure.capturedScalars = new RuntimeScalar[] {capturedPad};
            closure.capturedScalarOwnerSlots = new PerlOwnerSlot[] {captureOwner};
            closure.captureFieldsRecorded = true;

            assertRootCaptureProof(capturedPad, closure, captureOwner);
        }
    }

    @Test
    void compiledPadConstantOwnerHasCurrentOwnerSlot() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalarReadOnly padConstant = new RuntimeScalarReadOnly("constant");
            RuntimeCode code = new RuntimeCode(null, List.of());
            code.installPadConstants(new RuntimeBase[] {padConstant});
            RuntimeArray root = new RuntimeArray();
            root.localBindingExists = true;
            root.elements.add(code.createReference());

            MortalList.pushTemporaryRoot(root);
            try {
                PerlOwnerSlot padOwner = padConstant.ownerSlotSnapshot().stream()
                        .filter(slot -> slot.kind() == PerlOwnerSlot.Kind.CODE_PAD_CONSTANT)
                        .findFirst().orElseThrow();
                assertTrue(DestroyDispatch.isCurrentPerlOwnerSlot(padConstant, padOwner),
                        "the compiled pad-constant slot must be active");

            } finally {
                MortalList.popTemporaryRoot(root);
                root.elements.clear();
                root.localBindingExists = false;
                MortalList.scopeExitCleanupArray(root);
                code.clearPadConstantWeakRefs();
            }
        }
    }

    private static void assertRootCaptureProof(
            RuntimeBase capturedPad, RuntimeCode closure, PerlOwnerSlot captureOwner) {
        RuntimeArray root = new RuntimeArray();
        root.localBindingExists = true;
        root.elements.add(closure.createReference());

        MortalList.pushTemporaryRoot(root);
        try {
            assertTrue(DestroyDispatch.isCurrentPerlOwnerSlot(capturedPad, captureOwner),
                    "the capture slot must identify a current closure owner");

        } finally {
            MortalList.popTemporaryRoot(root);
            root.elements.clear();
            root.localBindingExists = false;
            MortalList.scopeExitCleanupArray(root);
            closure.releaseCaptures();
        }
    }
}
