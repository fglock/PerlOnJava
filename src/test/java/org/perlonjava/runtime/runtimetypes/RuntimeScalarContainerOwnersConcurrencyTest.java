package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RuntimeScalarContainerOwnersConcurrencyTest {
    @Test
    void concurrentArrayOwnerRegistrationAndSnapshotsRemainSafe() throws Exception {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar cell = new RuntimeScalar();
            List<RuntimeArray> owners = new ArrayList<>();
            for (int i = 0; i < 8; i++) owners.add(new RuntimeArray());

            ExecutorService workers = Executors.newFixedThreadPool(owners.size());
            CountDownLatch ready = new CountDownLatch(owners.size());
            CountDownLatch start = new CountDownLatch(1);
            try {
                List<Future<Void>> tasks = new ArrayList<>();
                for (RuntimeArray owner : owners) {
                    tasks.add(workers.submit((Callable<Void>) () -> {
                        ready.countDown();
                        if (!start.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("concurrent owner workers did not start");
                        }
                        for (int i = 0; i < 10_000; i++) {
                            cell.markArrayContainerOwner(owner);
                            cell.arrayContainerOwnersSnapshot();
                            cell.unmarkArrayContainerOwner(owner);
                        }
                        return null;
                    }));
                }
                assertTrue(ready.await(5, TimeUnit.SECONDS), "all owner workers should be ready");
                start.countDown();
                for (Future<Void> task : tasks) task.get(10, TimeUnit.SECONDS);
                assertTrue(cell.arrayContainerOwnersSnapshot().isEmpty());
            } finally {
                workers.shutdownNow();
            }
        }
    }
}
