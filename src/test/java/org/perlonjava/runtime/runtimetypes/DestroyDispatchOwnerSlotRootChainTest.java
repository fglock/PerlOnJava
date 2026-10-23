package org.perlonjava.runtime.runtimetypes;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class DestroyDispatchOwnerSlotRootChainTest {
    @Test
    void nestedHashOwnerSlotProvesResurrectionWithoutWholeRootWalk() {
        PerlRuntime runtime = new PerlRuntime();
        String rootName = "DestroyDispatchOwnerSlotRootChainTest::root";
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            RuntimeHash nested = new RuntimeHash();
            RuntimeHash root = new RuntimeHash();
            nested.put("target", referent.createReference());
            root.put("nested", nested.createReference());
            GlobalVariable.aliasGlobalHash(rootName, root);
            referent.activateOwnerTracking();

            Set<RuntimeScalar> noScalarOwnersAtStart =
                    Collections.newSetFromMap(new IdentityHashMap<>());
            Set<PerlOwnerSlot> noOwnerSlotsAtStart =
                    Collections.newSetFromMap(new IdentityHashMap<>());
            try {
                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    assertTrue(DestroyDispatch.hasEscapedPerlOwner(
                                    referent, true, noScalarOwnersAtStart, noOwnerSlotsAtStart),
                            "a newly added nested hash slot rooted in a package global is resurrection");
                    assertEquals(0, measurement.stats().rootQueries,
                            "typed owner slots prove the resurrection path");
                    assertEquals(0, measurement.stats().snapshotsBuilt,
                            "the proof does not build a whole-root graph snapshot");
                }
            } finally {
                root.elements.clear();
                nested.elements.clear();
                GlobalVariable.globalHashes.remove(rootName);
                MortalList.invalidateAllRootSnapshots();
            }
        }
    }
}
