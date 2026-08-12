package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class PerlThreadExecutionPolicyTest {
    @Test
    void defaultsToPlatformThreadsAndAllowsEnvironmentOptIn() {
        assertEquals(PerlThreadExecutionPolicy.Mode.PLATFORM,
                PerlThreadExecutionPolicy.resolve(null, null).mode());
        assertEquals(PerlThreadExecutionPolicy.Mode.VIRTUAL,
                PerlThreadExecutionPolicy.resolve(null, "virtual").mode());
    }

    @Test
    void systemPropertyTakesPrecedenceOverEnvironment() {
        assertEquals(PerlThreadExecutionPolicy.Mode.PLATFORM,
                PerlThreadExecutionPolicy.resolve("platform", "virtual").mode());
        assertEquals(PerlThreadExecutionPolicy.Mode.VIRTUAL,
                PerlThreadExecutionPolicy.resolve(" VIRTUAL ", "platform").mode());
    }

    @Test
    void rejectsUnknownModes() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> PerlThreadExecutionPolicy.resolve("pooled", null));
        assertTrue(failure.getMessage().contains("platform or virtual"));
    }

    @Test
    void processConfigurationIsImmutable() {
        assertSame(PerlThreadExecutionPolicy.configured(), PerlThreadExecutionPolicy.configured());
    }

    @Test
    void createsNamedPlatformAndVirtualThreadsWithEquivalentCompletion() throws Exception {
        assertThread(PerlThreadExecutionPolicy.resolve("platform", null), false, 41);
        assertThread(PerlThreadExecutionPolicy.resolve("virtual", null), true, 42);
    }

    private static void assertThread(
            PerlThreadExecutionPolicy policy, boolean virtual, long id) throws Exception {
        CountDownLatch ran = new CountDownLatch(1);
        Thread thread = policy.unstarted(id, ran::countDown);

        assertEquals("perl-ithread-" + id, thread.getName());
        assertEquals(virtual, thread.isVirtual());
        assertEquals(Thread.State.NEW, thread.getState());

        thread.start();
        assertTrue(ran.await(5, TimeUnit.SECONDS));
        thread.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(thread.isAlive());
    }
}
