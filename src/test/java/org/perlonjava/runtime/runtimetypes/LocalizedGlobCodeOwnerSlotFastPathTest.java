package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Tag("unit")
class LocalizedGlobCodeOwnerSlotFastPathTest {
    @Test
    void activeCallOwnerSkipsCaptureReachabilityWalkOnGlobRestore() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            String name = "LocalizedGlobCodeOwnerSlotFastPath::sub";
            RuntimeCode original = code();
            original.stashRefCount = 1;
            GlobalVariable.globalCodeRefs.put(name, new RuntimeScalar(original));

            RuntimeGlob glob = new RuntimeGlob(name);
            RuntimeCode localized = code();
            localized.refCount = 2;
            localized.stashRefCount = 1;
            localized.capturedScalars = new RuntimeScalar[] {new RuntimeScalar(42)};

            glob.dynamicSaveState();
            // Exercise the legacy stash path: the localized slot already has
            // a CODE-slot binding while still undef. Bypass set() just as old
            // direct stash writes did, so the CV has not opted into counted
            // CODE-slot ownership yet.
            RuntimeScalar localizedSlot = GlobalVariable.globalCodeRefs.get(name);
            localizedSlot.type = RuntimeScalarType.CODE;
            localizedSlot.value = localized;
            localizedSlot.globalCodeRefFqn = name;
            RuntimeCode.pushActiveCode(localized);
            try {
                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    glob.dynamicRestoreState();

                    assertEquals(0, localized.activeOwnerCount());
                    assertEquals(1, localized.ownerSlotCount(PerlOwnerSlot.Kind.ACTIVE_CODE));
                    assertEquals(1, localized.refCount,
                            "restoring the glob retires only the localized stash owner");
                    assertNotNull(localized.capturedScalars,
                            "the active call keeps its captured pad alive");
                    assertEquals(0, measurement.stats().rootQueries,
                            "the active-code owner slot replaces all three root checks");
                    assertEquals(0, measurement.stats().snapshotsBuilt);
                }
            } finally {
                RuntimeCode.popActiveCode(localized);
                GlobalVariable.globalCodeRefs.remove(name);
                localized.refCount = Integer.MIN_VALUE;
                DestroyDispatch.callDestroy(localized);
            }
        }
    }

    private static RuntimeCode code() {
        return new RuntimeCode((args, context) -> new RuntimeList(), null);
    }
}
