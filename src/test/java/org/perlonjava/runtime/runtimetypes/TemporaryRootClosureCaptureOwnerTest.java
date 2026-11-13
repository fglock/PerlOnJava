package org.perlonjava.runtime.runtimetypes;

import java.util.AbstractMap;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class TemporaryRootClosureCaptureOwnerTest {
    private static final class CountingHashValues extends AbstractMap<String, RuntimeScalar> {
        private int valuesCalls;

        @Override
        public Set<Entry<String, RuntimeScalar>> entrySet() {
            return Collections.emptySet();
        }

        @Override
        public Collection<RuntimeScalar> values() {
            valuesCalls++;
            return List.of();
        }
    }

    @Test
    void aggregateCaptureOwnerProvesTemporaryRootWithoutGraphWalk() {
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
    void scalarCaptureOwnerProvesTemporaryRootWithoutGraphWalk() {
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
    void compiledPadConstantOwnerProvesTemporaryRootWithoutGraphWalk() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalarReadOnly padConstant = new RuntimeScalarReadOnly("constant");
            RuntimeCode code = new RuntimeCode(null, List.of());
            code.installPadConstants(new RuntimeBase[] {padConstant});

            CountingHashValues opaqueValues = new CountingHashValues();
            RuntimeHash opaque = new RuntimeHash();
            opaque.elements = opaqueValues;
            RuntimeArray root = new RuntimeArray();
            root.localBindingExists = true;
            root.elements.add(code.createReference());
            root.elements.add(opaque.createReference());

            MortalList.pushTemporaryRoot(root);
            try {
                PerlOwnerSlot padOwner = padConstant.ownerSlotSnapshot().stream()
                        .filter(slot -> slot.kind() == PerlOwnerSlot.Kind.CODE_PAD_CONSTANT)
                        .findFirst().orElseThrow();
                assertTrue(DestroyDispatch.isCurrentPerlOwnerSlot(padConstant, padOwner),
                        "the compiled pad-constant slot must be active");
                assertFalse(MortalList.temporaryRootDirectlyReferences(padConstant),
                        "the root contains the owning CODE object, not the pad constant");
                assertTrue(MortalList.temporaryRootDirectlyReferences(code),
                        "the root directly contains the CODE object that owns the constant");

                assertTrue(ReachabilityWalker.isReachableFromTemporaryRoots(padConstant));
                assertEquals(0, opaqueValues.valuesCalls,
                        "the validated CODE-owner path must avoid the fallback graph walk");
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
        CountingHashValues opaqueValues = new CountingHashValues();
        RuntimeHash opaque = new RuntimeHash();
        opaque.elements = opaqueValues;
        RuntimeArray root = new RuntimeArray();
        root.localBindingExists = true;
        root.elements.add(closure.createReference());
        root.elements.add(opaque.createReference());

        MortalList.pushTemporaryRoot(root);
        try {
            assertTrue(DestroyDispatch.isCurrentPerlOwnerSlot(capturedPad, captureOwner),
                    "the capture slot must identify a current closure owner");
            assertFalse(MortalList.temporaryRootDirectlyReferences(capturedPad),
                    "the root contains the closure, not its captured pad");
            assertTrue(MortalList.temporaryRootDirectlyReferences(closure),
                    "the root directly contains the closure that owns the pad");

            assertTrue(ReachabilityWalker.isReachableFromTemporaryRoots(capturedPad));
            assertEquals(0, opaqueValues.valuesCalls,
                    "the validated CODE-owner path must avoid the fallback graph walk");
        } finally {
            MortalList.popTemporaryRoot(root);
            root.elements.clear();
            root.localBindingExists = false;
            MortalList.scopeExitCleanupArray(root);
            closure.releaseCaptures();
        }
    }
}
