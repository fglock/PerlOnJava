package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class MortalizeRegisteredCodeOwnerFastPathTest {
    @Test
    void registeredContainerOwnerKeepsCodeWithoutScalarReachabilityQuery() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            MortalList.setActive(true);
            int lexicalMark = MyVarCleanupStack.pushMark();
            RuntimeCode code = new RuntimeCode((args, context) -> new RuntimeList(), null);
            String codeSlotName = "MortalizeRegisteredCodeOwnerFastPath::code";
            RuntimeScalar owner = new RuntimeScalar(code);
            GlobalVariable.globalCodeRefs.put(codeSlotName, owner);
            RuntimeArray registeredContainer = new RuntimeArray();
            registeredContainer.elements.add(owner);
            MyVarCleanupStack.register(registeredContainer);
            RuntimeList discardedResult = new RuntimeList();
            discardedResult.elements.add(owner);

            try {
                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    MortalList.mortalizeForVoidDiscard(discardedResult);

                    assertTrue(owner.refCountOwned,
                            "the registered container still owns its CODE scalar slot");
                    assertEquals(1, code.refCount);
                    assertTrue(runtime.lifecycleState.pendingOwnerReleases.isEmpty());
                    assertEquals(0, measurement.stats().scalarReachabilityQueries);
                    assertEquals(0, measurement.stats().scalarReachabilityRootWalks);
                }
            } finally {
                registeredContainer.elements.remove(owner);
                owner.releaseContainerOwner(registeredContainer);
                MyVarCleanupStack.unregister(registeredContainer);
                GlobalVariable.globalCodeRefs.remove(codeSlotName);
                MyVarCleanupStack.popMark(lexicalMark);
            }
        }
    }
}
