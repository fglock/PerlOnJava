package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNull;

@Tag("unit")
class RuntimeScalarDestroyableClosureOwnersConcurrencyTest {
    @Test
    void concurrentCaptureReleaseDoesNotMutateTheReverseIndexDuringIteration() throws Exception {
        RuntimeScalar scalar = new RuntimeScalar();
        int workerCount = 12;
        List<RuntimeCode> owners = new ArrayList<>(workerCount);
        List<WeakReference<RuntimeCode>> references = new ArrayList<>(workerCount * 2_000);
        for (int i = 0; i < workerCount; i++) owners.add(new RuntimeCode(null, List.of()));
        for (int repeat = 0; repeat < 2_000; repeat++) {
            for (RuntimeCode owner : owners) references.add(new WeakReference<>(owner));
        }

        Field index = RuntimeScalar.class.getDeclaredField("destroyableClosureOwners");
        index.setAccessible(true);
        index.set(scalar, references);
        Method forgetOwner = RuntimeScalar.class.getDeclaredMethod(
                "forgetDestroyableClosureOwner", RuntimeCode.class);
        forgetOwner.setAccessible(true);

        ExecutorService workers = Executors.newFixedThreadPool(workerCount);
        CountDownLatch ready = new CountDownLatch(workerCount);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> tasks = new ArrayList<>();
            for (RuntimeCode owner : owners) {
                tasks.add(workers.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("capture-release workers did not start");
                    }
                    try {
                        forgetOwner.invoke(scalar, owner);
                    } catch (ReflectiveOperationException failure) {
                        throw new AssertionError(failure.getCause() == null
                                ? failure : failure.getCause());
                    }
                    return null;
                }));
            }
            if (!ready.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("capture-release workers were not ready");
            }
            start.countDown();
            for (Future<?> task : tasks) task.get(10, TimeUnit.SECONDS);
            assertNull(index.get(scalar), "the final released owner clears the reverse index");
        } finally {
            workers.shutdownNow();
        }
    }
}
