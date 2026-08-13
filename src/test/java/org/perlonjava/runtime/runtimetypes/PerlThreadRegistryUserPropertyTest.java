package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class PerlThreadRegistryUserPropertyTest {
    @Test
    void samePropertyWaitsForOneDefinitionWhileDifferentNamesProceed() throws Exception {
        PerlThreadRegistry registry = new PerlThreadRegistry();
        CountDownLatch ownerStarted = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        AtomicInteger sameNameCalls = new AtomicInteger();

        FutureTask<String> owner = new FutureTask<>(() -> registry.resolveUserUnicodeProperty(
                "main::IsSlow", () -> {
                    sameNameCalls.incrementAndGet();
                    ownerStarted.countDown();
                    try {
                        assertTrue(releaseOwner.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                    return "slow";
                }));
        Thread ownerThread = Thread.ofPlatform().start(owner);
        assertTrue(ownerStarted.await(5, TimeUnit.SECONDS));

        FutureTask<String> waiter = new FutureTask<>(() -> registry.resolveUserUnicodeProperty(
                "main::IsSlow", () -> {
                    sameNameCalls.incrementAndGet();
                    return "wrong";
                }));
        Thread waiterThread = Thread.ofPlatform().start(waiter);

        assertEquals("quick", registry.resolveUserUnicodeProperty(
                "main::IsQuick", () -> "quick"));
        waitUntilBlockedOnOwner(waiterThread, waiter);
        assertFalse(waiter.isDone());
        releaseOwner.countDown();

        assertEquals("slow", owner.get(5, TimeUnit.SECONDS));
        assertEquals("slow", waiter.get(5, TimeUnit.SECONDS));
        assertEquals(1, sameNameCalls.get());
        ownerThread.join(5_000);
        waiterThread.join(5_000);
        assertFalse(ownerThread.isAlive());
        assertFalse(waiterThread.isAlive());
    }

    private static void waitUntilBlockedOnOwner(Thread waiterThread, FutureTask<?> waiter)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!waiter.isDone()
                && waiterThread.getState() != Thread.State.TIMED_WAITING
                && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertEquals(Thread.State.TIMED_WAITING, waiterThread.getState(),
                "waiter entered the existing property's timed future wait");
    }
}
