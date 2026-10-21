package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class ExternalRootSnapshotCodeStateTest {
    @Test
    void installedCodeStateKeepsItsCallbackGraphAsAnExternalRoot() {
        PerlRuntime runtime = new PerlRuntime();
        String globalName = "ExternalRootSnapshotCodeStateTest::singleton";
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray controller = new RuntimeArray();
            RuntimeScalar controllerCell = controller.createReference();

            RuntimeCode userCallback = new RuntimeCode(null, List.of());
            userCallback.capturedScalars = new RuntimeScalar[] { controllerCell };
            RuntimeScalar callbackCell = new RuntimeScalar(userCallback);
            RuntimeCode timerWrapper = new RuntimeCode(null, List.of());
            timerWrapper.capturedScalars = new RuntimeScalar[] { callbackCell };
            timerWrapper.closedOverVariables = new java.util.LinkedHashMap<>();
            timerWrapper.closedOverVariables.put("$callback", callbackCell);
            RuntimeHash timerRecord = new RuntimeHash();
            timerRecord.elements.put("cb", new RuntimeScalar(timerWrapper));
            RuntimeHash reactorTimers = new RuntimeHash();
            reactorTimers.elements.put("timer", timerRecord.createReference());

            RuntimeCode arrayStateCallback = new RuntimeCode(null, List.of());
            RuntimeArray stateArray = new RuntimeArray();
            stateArray.elements.add(new RuntimeScalar(arrayStateCallback));

            RuntimeCode hashStateCallback = new RuntimeCode(null, List.of());
            RuntimeHash stateHash = new RuntimeHash();
            stateHash.elements.put("callback", new RuntimeScalar(hashStateCallback));

            RuntimeCode singletonMethod = new RuntimeCode(null, List.of());
            singletonMethod.stateVariable.put("loop", reactorTimers.createReference());
            singletonMethod.stateArray.put("callbacks", stateArray);
            singletonMethod.stateHash.put("callbacks", stateHash);
            GlobalVariable.globalCodeRefs.put(globalName, new RuntimeScalar(singletonMethod));
            GlobalVariable.invalidateGlobalCodeRefGraphRoots();

            try {
                ReachabilityWalker.ExternalRootSnapshot roots =
                        new ReachabilityWalker.ExternalRootSnapshot(false);

                assertTrue(roots.isReachableFromNonLexicalRoot(reactorTimers),
                        "the installed CV's scalar state must root the singleton reactor");
                assertTrue(roots.isReachableFromNonLexicalRoot(timerWrapper),
                        "timer callbacks must remain reachable through the singleton reactor");
                assertTrue(roots.isReachableFromNonLexicalRoot(userCallback),
                        "nested user callbacks must remain reachable through their wrappers");
                assertTrue(roots.isReachableFromNonLexicalRoot(controller),
                        "a callback's captured controller must remain reachable");
                assertTrue(roots.isReachableFromNonLexicalRoot(arrayStateCallback),
                        "persistent state arrays must remain reachable");
                assertTrue(roots.isReachableFromNonLexicalRoot(hashStateCallback),
                        "persistent state hashes must remain reachable");
            } finally {
                GlobalVariable.globalCodeRefs.remove(globalName);
                GlobalVariable.invalidateGlobalCodeRefGraphRoots();
            }
        }
    }
}
