package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@Tag("unit")
class RuntimeCodeScopeExitReachabilityTest {
    @Test
    void scopeExitRetainsCapturesWhileCodeIsStoredInReachableHash() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar captured = new RuntimeScalar("controller");
            captured.retainClosureCapture();
            RuntimeCode callback = new RuntimeCode(null, List.of());
            callback.refCount = 0;
            callback.capturedScalars = new RuntimeScalar[] { captured };

            RuntimeScalar codeRef = new RuntimeScalar(callback);
            RuntimeHash callbacks = new RuntimeHash();
            callbacks.elements.put("callback", codeRef);
            String globalName = "RuntimeCodeScopeExitReachabilityTest::callbacks";
            GlobalVariable.globalHashes.put(globalName, callbacks);

            try {
                RuntimeScalar.scopeExitCleanup(codeRef);

                assertNotNull(callback.capturedScalars,
                        "scope cleanup must preserve captures while a hash owns the CODE ref");
                assertEquals(1, captured.captureCount);

                GlobalVariable.globalHashes.remove(globalName);
                RuntimeScalar.scopeExitCleanup(codeRef);

                assertNull(callback.capturedScalars,
                        "discarding the final reachable CODE owner releases its captures");
                assertEquals(0, captured.captureCount);
            } finally {
                GlobalVariable.globalHashes.remove(globalName);
            }
        }
    }

    @Test
    void deferredReleaseRetainsCapturesForCodeStoredInReachableHashWithoutWeakRefs() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar captured = new RuntimeScalar("controller");
            captured.retainClosureCapture();
            RuntimeCode callback = new RuntimeCode(null, List.of());
            callback.refCount = 1;
            callback.capturedScalars = new RuntimeScalar[] { captured };

            RuntimeHash callbacks = new RuntimeHash();
            callbacks.elements.put("callback", new RuntimeScalar(callback));
            String globalName = "RuntimeCodeScopeExitReachabilityTest::deferredCallbacks";
            GlobalVariable.globalHashes.put(globalName, callbacks);
            boolean previousActive = MortalList.isActive();
            MortalList.setActive(true);

            try {
                MortalList.deferDecrement(callback);
                MortalList.flush();

                assertNotNull(callback.capturedScalars,
                        "a rooted callback must keep its lexical captures when a temporary owner is released");
                assertEquals(1, captured.captureCount);

                GlobalVariable.globalHashes.remove(globalName);
                MortalList.deferDecrement(callback);
                MortalList.flush();

                assertNull(callback.capturedScalars,
                        "removing the last Perl root must release callback captures");
                assertEquals(0, captured.captureCount);
            } finally {
                GlobalVariable.globalHashes.remove(globalName);
                MortalList.setActive(previousActive);
            }
        }
    }

    @Test
    void arrayScopeExitPreservesRootedCallbacksThenReleasesThemWhenRootIsRemoved() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar captured = new RuntimeScalar("captured");
            captured.retainClosureCapture();
            RuntimeCode callback = new RuntimeCode(null, List.of());
            callback.refCount = 1;
            callback.capturedScalars = new RuntimeScalar[] { captured };

            RuntimeScalar codeRef = new RuntimeScalar(callback);
            codeRef.refCountOwned = true;
            RuntimeArray callbacks = new RuntimeArray();
            callbacks.elements.add(codeRef);
            callbacks.elementsOwned = true;
            String globalName = "RuntimeCodeScopeExitReachabilityTest::callbacks";
            GlobalVariable.globalArrays.put(globalName, callbacks);

            RuntimeScalar weakProbe = new RuntimeScalar(new RuntimeHash()).createReference();
            WeakRefRegistry.weaken(weakProbe);
            boolean previousActive = MortalList.isActive();
            MortalList.setActive(true);
            try {
                MortalList.scopeExitCleanupArray(callbacks);
                MortalList.flush();

                assertEquals(1, callback.refCount,
                        "a persistent aggregate root keeps its callback owner count");
                assertNotNull(callback.capturedScalars,
                        "a persistent aggregate root keeps callback captures");

                GlobalVariable.globalArrays.remove(globalName);
                MortalList.scopeExitCleanupArray(callbacks);
                MortalList.flush();

                assertNull(callback.capturedScalars,
                        "discarding the last callback container releases its captures");
                assertEquals(0, captured.captureCount);
            } finally {
                GlobalVariable.globalArrays.remove(globalName);
                MortalList.setActive(previousActive);
            }
        }
    }

}
