package org.perlonjava.backend.jvm;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
class EmitterMethodCreatorConcurrencyTest {

    @Test
    void generatedClassNamesRemainUniqueAcrossThreads() throws InterruptedException {
        int workerCount = 12;
        int namesPerWorker = 1_000;
        Set<String> names = ConcurrentHashMap.newKeySet();
        CountDownLatch ready = new CountDownLatch(workerCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(workerCount);

        for (int worker = 0; worker < workerCount; worker++) {
            Thread.ofPlatform().start(() -> {
                ready.countDown();
                try {
                    start.await();
                    for (int i = 0; i < namesPerWorker; i++) {
                        names.add(EmitterMethodCreator.generateClassName());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        done.await();

        assertEquals(workerCount * namesPerWorker, names.size());
    }
}
