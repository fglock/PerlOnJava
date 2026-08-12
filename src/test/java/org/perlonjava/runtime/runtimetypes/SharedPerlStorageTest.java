package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class SharedPerlStorageTest {
    @Test
    void liveRootReusesOneRecursiveLockAndSerializesContenders() throws Exception {
        RuntimeScalar root = new RuntimeScalar(1);
        SharedPerlStorage.shareValue(root);
        ReentrantLock lock = SharedPerlStorage.lockForTesting(root);
        assertSame(lock, SharedPerlStorage.lockForTesting(root));

        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        FutureTask<Void> firstTask = contender(lock, inside, maximum, start);
        FutureTask<Void> secondTask = contender(lock, inside, maximum, start);
        Thread first = Thread.ofPlatform().unstarted(firstTask);
        Thread second = Thread.ofPlatform().unstarted(secondTask);
        first.start();
        second.start();
        start.countDown();
        firstTask.get(5, TimeUnit.SECONDS);
        secondTask.get(5, TimeUnit.SECONDS);

        assertFalse(first.isAlive());
        assertFalse(second.isAlive());
        assertEquals(1, maximum.get(), "shared root must have one exclusion domain");
    }

    @Test
    void ephemeralSharedRootsDoNotRemainStronglyHeldByLockRegistry() throws Exception {
        WeakReference<RuntimeScalar> root = registerEphemeralLock();

        for (int attempt = 0; attempt < 100 && root.get() != null; attempt++) {
            System.gc();
            byte[] pressure = new byte[256 * 1024];
            assertEquals(256 * 1024, pressure.length);
            Thread.sleep(10);
        }

        assertNull(root.get(), "ephemeral shared root should become collectible");
        int removed = 0;
        for (int attempt = 0; attempt < 100 && removed == 0; attempt++) {
            removed += SharedPerlStorage.expungeStaleLocksForTesting();
            if (removed == 0) Thread.sleep(10);
        }
        assertTrue(removed > 0, "collected lock entry should be drained from its reference queue");
    }

    private static WeakReference<RuntimeScalar> registerEphemeralLock() {
        RuntimeScalar root = new RuntimeScalar(1);
        SharedPerlStorage.shareValue(root);
        SharedPerlStorage.lockForTesting(root);
        return new WeakReference<>(root);
    }

    private static FutureTask<Void> contender(ReentrantLock lock, AtomicInteger inside,
                                              AtomicInteger maximum, CountDownLatch start) {
        return new FutureTask<>(() -> {
            assertTrue(start.await(5, TimeUnit.SECONDS));
            lock.lock();
            try {
                maximum.accumulateAndGet(inside.incrementAndGet(), Math::max);
                Thread.sleep(20);
                inside.decrementAndGet();
            } finally {
                lock.unlock();
            }
            return null;
        });
    }
}
