package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class PerlThreadShutdownWarningTest {

    @Test
    void reportsOnlyAttachedUnjoinedChildrenAtProcessExit() throws Exception {
        PerlRuntime runtime = new PerlRuntime();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PerlThreadControlBlock child;
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            child = PerlThreadControlBlock.create(runtime, childRuntime -> {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("child release timed out");
                }
                return new RuntimeScalar(1);
            }).start();
        }

        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals("Perl exited with active threads:\n"
                            + "\t1 running and unjoined\n"
                            + "\t0 finished and unjoined\n"
                            + "\t0 running and detached\n",
                    runtime.threadRegistry().activeThreadExitWarning());

            child.detach();
            assertEquals("", runtime.threadRegistry().activeThreadExitWarning());
        } finally {
            release.countDown();
            Thread javaThread = child.platformThread();
            if (javaThread != null) javaThread.join(5_000);
            assertFalse(javaThread != null && javaThread.isAlive());
            runtime.close();
        }
    }
}
