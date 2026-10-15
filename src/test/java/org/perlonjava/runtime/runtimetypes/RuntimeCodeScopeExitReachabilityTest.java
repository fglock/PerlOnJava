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
}
