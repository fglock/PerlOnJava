package org.perlonjava.runtime.runtimetypes;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class ScalarSnapshotCodeStateRootTest {
    @Test
    void completeScalarSnapshotFollowsPersistentCodeState() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar persistentState = new RuntimeScalar("state");
            RuntimeScalar stateSlot = new RuntimeScalar();
            stateSlot.type = RuntimeScalarType.REFERENCE;
            stateSlot.value = persistentState;

            RuntimeCode code = new RuntimeCode(null, List.of());
            code.stateVariable.put("persistent", stateSlot);
            String globalName = "ScalarSnapshotCodeStateRootTest::state_holder";
            GlobalVariable.globalCodeRefs.put(globalName, new RuntimeScalar(code));
            try {
                ReachabilityWalker.ScalarRootReachabilitySnapshot snapshot =
                        ReachabilityWalker.completeScalarReachabilitySnapshot();

                assertTrue(snapshot.complete(), "the cleanup snapshot is complete");
                assertTrue(snapshot.isReachable(stateSlot),
                        "a globally installed sub keeps its persistent state slot live");
                assertTrue(snapshot.isReachable(persistentState),
                        "persistent state values remain roots while their sub is installed");
            } finally {
                GlobalVariable.globalCodeRefs.remove(globalName);
                MortalList.invalidateAllRootSnapshots();
            }
        }
    }
}
