package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class PerlThreadVirtualDefaultTest {
    @Test
    void nonzeroStackRequestFallsBackToAPlatformCarrier() throws Exception {
        CountDownLatch ran = new CountDownLatch(1);
        Thread thread = PerlThreadExecutionPolicy.resolve("virtual", null)
                .effectiveForStackSize(1024 * 1024)
                .unstarted(71, 1024 * 1024, ran::countDown);

        assertFalse(thread.isVirtual());
        assertEquals("perl-ithread-71", thread.getName());
        thread.start();
        assertTrue(ran.await(5, TimeUnit.SECONDS));
        thread.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(thread.isAlive());
    }
}
