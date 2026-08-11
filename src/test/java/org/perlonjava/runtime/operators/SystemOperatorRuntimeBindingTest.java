package org.perlonjava.runtime.operators;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.io.StandardIO;
import org.perlonjava.runtime.runtimetypes.PerlRuntime;
import org.perlonjava.runtime.runtimetypes.RuntimeIO;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

@Tag("unit")
class SystemOperatorRuntimeBindingTest {

    @Test
    void streamRoutersUseTheRuntimeCapturedAtCreation() throws Exception {
        assertRoute(false, "stdout-worker");
        assertRoute(true, "stderr-worker");
        assertNull(PerlRuntime.currentOrNull());
    }

    private static void assertRoute(boolean stderr, String marker) throws Exception {
        PerlRuntime runtime = new PerlRuntime();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Thread router;
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeIO output = new RuntimeIO(new StandardIO(bytes, true));
            if (stderr) {
                RuntimeIO.setStderr(output);
            } else {
                RuntimeIO.setStdout(output);
            }
            router = SystemOperator.createStreamRouterThread(
                    new ByteArrayInputStream(marker.getBytes(StandardCharsets.ISO_8859_1)), stderr);
        }

        router.start();
        router.join(10_000);
        assertFalse(router.isAlive(), "stream router did not finish");
        assertEquals(marker, bytes.toString(StandardCharsets.ISO_8859_1));
    }
}
