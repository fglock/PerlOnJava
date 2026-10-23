package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RuntimeBaseActiveOwnersConcurrencyTest {
    @Test
    void concurrentOwnerRegistrationAndSnapshotsRemainSafe() throws Exception {
        RuntimeArray referent = new RuntimeArray();
        referent.activateOwnerTracking();

        int workerCount = 8;
        ExecutorService workers = Executors.newFixedThreadPool(workerCount + 2);
        CountDownLatch ready = new CountDownLatch(workerCount + 2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Void>> tasks = new ArrayList<>();
            for (int worker = 0; worker < workerCount; worker++) {
                RuntimeScalar owner = new RuntimeScalar();
                owner.value = referent;
                owner.refCountOwned = true;
                tasks.add(workers.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("owner workers did not start");
                    }
                    for (int i = 0; i < 10_000; i++) {
                        referent.recordActiveOwner(owner);
                        if ((i & 1) == 0) {
                            referent.activeOwnerCount();
                            referent.activeOwnerSnapshot();
                        }
                        referent.releaseActiveOwner(owner);
                    }
                    return null;
                }));
            }
            for (int reader = 0; reader < 2; reader++) {
                tasks.add(workers.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("owner readers did not start");
                    }
                    for (int i = 0; i < 10_000; i++) {
                        referent.activeOwnerCount();
                        referent.activeOwnerSnapshot();
                        referent.countedScalarOwnerSnapshot();
                    }
                    return null;
                }));
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS), "all owner workers should be ready");
            start.countDown();
            for (Future<Void> task : tasks) task.get(20, TimeUnit.SECONDS);
            assertTrue(referent.activeOwnerSnapshot().isEmpty());
        } finally {
            workers.shutdownNow();
        }
    }
}
