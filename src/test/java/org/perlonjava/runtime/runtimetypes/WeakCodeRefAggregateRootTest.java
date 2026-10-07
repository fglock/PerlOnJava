package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class WeakCodeRefAggregateRootTest {
    @Test
    void fullRootSnapshotKeepsCodeRefHeldByPackageArray() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeCode code = new RuntimeCode(null, java.util.List.of());
            RuntimeScalar strongCodeRef = new RuntimeScalar(code);
            RuntimeArray callbacks = new RuntimeArray();
            callbacks.elements.add(strongCodeRef);
            String globalName = "WeakCodeRefAggregateRootTest::callbacks";
            GlobalVariable.globalArrays.put(globalName, callbacks);

            RuntimeScalar weakCodeRef = new RuntimeScalar(code);
            WeakRefRegistry.weaken(weakCodeRef);
            runtime.lifecycleState.fullRootSnapshot =
                    ReachabilityWalker.reachableFromRootsSnapshotWithStatus();

            try {
                assertTrue(runtime.lifecycleState.fullRootSnapshot.isReachable(code));
                assertFalse(ReachabilityWalker.hasLiveStrongScalarReferent(code));
                WeakRefRegistry.clearWeakRefsTo(code);
                assertTrue(WeakRefRegistry.isweak(weakCodeRef));
            } finally {
                GlobalVariable.globalArrays.remove(globalName);
            }
        }
    }

    @Test
    void liveStrongLexicalCodeRefIsFoundAfterWeakTrackingStarts() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeCode code = new RuntimeCode(null, java.util.List.of());
            RuntimeScalar strongCodeRef = new RuntimeScalar(code);
            MyVarCleanupStack.register(strongCodeRef);
            RuntimeScalar weakCodeRef = new RuntimeScalar(code);
            WeakRefRegistry.weaken(weakCodeRef);

            try {
                assertTrue(ReachabilityWalker.hasLiveStrongScalarReferent(code));
                assertFalse(ReachabilityWalker.hasLiveStrongScalarReferentOtherThan(
                        code, strongCodeRef));
            } finally {
                MyVarCleanupStack.unregister(strongCodeRef);
            }

            assertFalse(ReachabilityWalker.hasLiveStrongScalarReferent(code));
        }
    }

    @Test
    void externalRootSnapshotWalksCapturedScalarsFromInstalledCode() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash capturedTarget = new RuntimeHash();
            RuntimeScalar capturedScalar = new RuntimeScalar();
            capturedScalar.type = RuntimeScalarType.REFERENCE;
            capturedScalar.value = capturedTarget;
            RuntimeCode code = new RuntimeCode(null, java.util.List.of());
            code.capturedScalars = new RuntimeScalar[] { capturedScalar };
            code.captureFieldsRecorded = true;

            String globalName = "WeakCodeRefAggregateRootTest::capture_holder";
            GlobalVariable.globalCodeRefs.put(globalName, new RuntimeScalar(code));
            try {
                ReachabilityWalker.ExternalRootSnapshot snapshot =
                        new ReachabilityWalker.ExternalRootSnapshot();
                assertTrue(snapshot.isReachableFromNonLexicalRoot(capturedTarget));
            } finally {
                GlobalVariable.globalCodeRefs.remove(globalName);
            }
        }
    }

    @Test
    void codeRefEdgeRootCacheTracksSlotReplacementAndRemoval() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash capturedTarget = new RuntimeHash();
            RuntimeScalar capturedScalar = new RuntimeScalar();
            capturedScalar.type = RuntimeScalarType.REFERENCE;
            capturedScalar.value = capturedTarget;
            RuntimeCode capturedCode = new RuntimeCode(null, java.util.List.of());
            capturedCode.capturedScalars = new RuntimeScalar[] { capturedScalar };
            capturedCode.captureFieldsRecorded = true;

            String globalName = "WeakCodeRefAggregateRootTest::mutable_capture_holder";
            RuntimeScalar capturedRoot = new RuntimeScalar(capturedCode);
            GlobalVariable.globalCodeRefs.put(globalName, capturedRoot);
            try {
                java.util.List<RuntimeScalar> cachedRoots =
                        GlobalVariable.globalCodeRefEdgeRootsView();
                assertTrue(cachedRoots.contains(capturedRoot));

                RuntimeScalar terminalRoot = new RuntimeScalar(
                        new RuntimeCode(null, java.util.List.of()));
                GlobalVariable.globalCodeRefs.put(globalName, terminalRoot);
                assertFalse(cachedRoots.contains(capturedRoot));
                assertFalse(cachedRoots.contains(terminalRoot));

                GlobalVariable.globalCodeRefs.put(globalName, capturedRoot);
                assertTrue(cachedRoots.contains(capturedRoot));
                GlobalVariable.globalCodeRefs.remove(globalName);
                assertFalse(cachedRoots.contains(capturedRoot));
            } finally {
                GlobalVariable.globalCodeRefs.remove(globalName);
            }
        }
    }

}
