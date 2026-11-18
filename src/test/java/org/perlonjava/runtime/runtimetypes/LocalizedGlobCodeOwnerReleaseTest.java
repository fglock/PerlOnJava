package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@Tag("unit")
class LocalizedGlobCodeOwnerReleaseTest {
    @Test
    void unownedLocalizedCodeReleasesCapture() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            String name = "LocalizedGlobCodeOwnerReleaseTest::sub";
            RuntimeCode original = code();
            original.stashRefCount = 1;
            GlobalVariable.globalCodeRefs.put(name, new RuntimeScalar(original));

            RuntimeGlob glob = new RuntimeGlob(name);
            RuntimeCode localized = code();
            localized.setTrackedRefCount(2);
            localized.stashRefCount = 1;
            RuntimeScalar capturedPad = new RuntimeScalar(42);
            localized.capturedScalars = new RuntimeScalar[] {capturedPad};
            localized.capturedScalarOwnerSlots = new PerlOwnerSlot[] {
                    capturedPad.retainClosureCaptureSlot(localized)
            };
            localized.captureFieldsRecorded = true;

            glob.dynamicSaveState();
            RuntimeScalar localizedSlot = GlobalVariable.globalCodeRefs.get(name);
            // Reproduce a legacy direct stash write that has no CODE-slot edge.
            localizedSlot.type = RuntimeScalarType.CODE;
            localizedSlot.value = localized;
            localizedSlot.globalCodeRefFqn = name;

            try {
                {
                    glob.dynamicRestoreState();

                    assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED,
                            localized.perlLifecycleState());
                    assertNull(localized.capturedScalars);
                    assertEquals(0, capturedPad.captureCount);
                }
            } finally {
                GlobalVariable.globalCodeRefs.remove(name);
                if (localized.perlLifecycleState() != RuntimeBase.PerlLifecycleState.DESTROYED) {
                    localized.setTrackedRefCount(0);
                    DestroyDispatch.callDestroy(localized);
                }
            }
        }
    }

    private static RuntimeCode code() {
        return new RuntimeCode((args, context) -> new RuntimeList(), null);
    }
}
