package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class PerlRuntimePoolTest {
    @Test
    void configurationDefaultsOffAndRejectsInvalidValues() {
        assertEquals(0, PerlRuntimePool.resolveSize(null, null));
        assertEquals(3, PerlRuntimePool.resolveSize(null, " 3 "));
        assertEquals(2, PerlRuntimePool.resolveSize("2", "4"));
        assertThrows(IllegalArgumentException.class,
                () -> PerlRuntimePool.resolveSize("-1", null));
        assertThrows(IllegalArgumentException.class,
                () -> PerlRuntimePool.resolveSize("many", null));
    }

    @Test
    void checkoutIsExclusiveAndReturnResetsTheSameRuntime() throws Exception {
        PerlRuntimePool pool = new PerlRuntimePool(1);
        PerlRuntime first;
        try (PerlRuntimePool.Lease lease = pool.checkout(Duration.ofSeconds(1))) {
            first = lease.runtime();
            first.execute(() -> GlobalVariable.getGlobalVariable("main::tenant").set(41));
            assertThrows(IllegalStateException.class,
                    () -> pool.checkout(Duration.ofMillis(10)));
        }

        try (PerlRuntimePool.Lease lease = pool.checkout(Duration.ofSeconds(1))) {
            assertSame(first, lease.runtime());
            lease.runtime().execute(() ->
                    assertEquals(RuntimeScalarType.UNDEF,
                            GlobalVariable.getGlobalVariable("main::tenant").type));
        } finally {
            pool.close();
        }
    }

    @Test
    void closeDoesNotResetAnActiveLeaseAndClosesItOnReturn() throws Exception {
        PerlRuntimePool pool = new PerlRuntimePool(1);
        PerlRuntimePool.Lease lease = pool.checkout(Duration.ofSeconds(1));
        PerlRuntime runtime = lease.runtime();
        pool.close();
        assertFalse(runtime.isClosed());
        lease.close();
        assertTrue(runtime.isClosed());
        assertThrows(IllegalStateException.class,
                () -> pool.checkout(Duration.ZERO));
    }

    @Test
    void concurrentChurnNeverHandsOneRuntimeToTwoOwners() throws Exception {
        PerlRuntimePool pool = new PerlRuntimePool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(8);
        java.util.Set<PerlRuntime> active = java.util.Collections.synchronizedSet(
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
        java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();

        for (int i = 0; i < 8; i++) {
            Thread.ofPlatform().daemon(true).start(() -> {
                try {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    for (int round = 0; round < 20; round++) {
                        int value = round;
                        try (PerlRuntimePool.Lease lease = pool.checkout(Duration.ofSeconds(5))) {
                            PerlRuntime runtime = lease.runtime();
                            if (!active.add(runtime)) throw new AssertionError("duplicate checkout");
                            try {
                                runtime.execute(() -> GlobalVariable.getGlobalVariable("main::round")
                                        .set(value));
                            } finally {
                                active.remove(runtime);
                            }
                        }
                    }
                } catch (Throwable thrown) {
                    failure.compareAndSet(null, thrown);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.close();
        assertNull(failure.get(), () -> String.valueOf(failure.get()));
    }
}
