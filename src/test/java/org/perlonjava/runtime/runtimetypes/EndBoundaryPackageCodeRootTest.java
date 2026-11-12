package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class EndBoundaryPackageCodeRootTest {
    @Test
    void installedAnonymousClosureKeepsItsCapturedReferentRooted() {
        assertInstalledCodeCaptureRoot(
                "Issue1649AnonymousCodeSlot::installed",
                "__ANON__",
                "issue1649-anonymous-code-slot.pl");
    }

    @Test
    void evalDefinedNamedClosureKeepsItsCapturedReferentRooted() {
        assertInstalledCodeCaptureRoot(
                "Issue1649EvalCodeSlot::installed",
                "installed",
                "(eval 1649)");
    }

    @Test
    void codeCaptureOwnerUsesItsDirectSlotIdentity() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash capturedReferent = new RuntimeHash();
            RuntimeCode code = new RuntimeCode((args, context) -> new RuntimeList(), null);
            PerlOwnerSlot capture = capturedReferent.retainClosureCaptureOwner(code);
            code.capturedAggregates = new RuntimeBase[] {capturedReferent};
            code.capturedAggregateOwnerSlots = new PerlOwnerSlot[] {capture};

            assertTrue(capture.isActive());
            assertTrue(capturedReferent.hasSemanticCaptureOwner());

            code.releaseCaptures();

            assertFalse(capture.isActive(),
                    "the CODE owner releases the captured aggregate's exact slot");
            assertFalse(capturedReferent.hasSemanticCaptureOwner());
        }
    }

    private static void assertInstalledCodeCaptureRoot(
            String slotName, String subName, String sourceFile) {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash capturedReferent = new RuntimeHash();
            RuntimeScalar capturedScalar = capturedReferent.createAnonymousReference();
            RuntimeCode code = new RuntimeCode((args, context) -> new RuntimeList(), null);
            code.packageName = slotName.substring(0, slotName.lastIndexOf("::"));
            code.subName = subName;
            code.cvStartFile = sourceFile;
            code.putClosedOverVariable("$captured", capturedScalar);
            PerlOwnerSlot capture = capturedReferent.retainClosureCaptureOwner(code);
            code.capturedAggregates = new RuntimeBase[] {capturedReferent};
            code.capturedAggregateOwnerSlots = new PerlOwnerSlot[] {capture};
            RuntimeScalar codeRef = new RuntimeScalar(code);

            GlobalVariable.globalCodeRefs.put(slotName, codeRef);
            try {
                RuntimeScalar installedCodeRef = GlobalVariable.globalCodeRefs.get(slotName);
                assertTrue(installedCodeRef.refCountOwned,
                        "the installed package CODE slot owns its CV");
                assertTrue(capture.isActive(),
                        "the installed CV carries a direct capture owner slot");
                assertTrue(capturedReferent.hasSemanticCaptureOwner());
                assertTrue(DestroyDispatch.hasLivePerlOwnerIdentity(capturedReferent),
                        "the capture slot itself proves the current Perl owner");
            } finally {
                GlobalVariable.globalCodeRefs.remove(slotName);
                code.releaseCaptures();
            }
        }
    }
}
