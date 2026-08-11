package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class PerlRuntimeTest {

    @Test
    void absentBindingFailsClearly() {
        IllegalStateException error = assertThrows(IllegalStateException.class, PerlRuntime::current);
        assertTrue(error.getMessage().contains("No PerlRuntime"));
    }

    @Test
    void nestedBindingsRestoreThePriorRuntime() {
        PerlRuntime outer = new PerlRuntime();
        PerlRuntime inner = new PerlRuntime();

        try (PerlRuntime.Binding ignored = outer.bind()) {
            assertSame(outer, PerlRuntime.current());
            try (PerlRuntime.Binding nested = inner.bind()) {
                assertSame(inner, PerlRuntime.current());
            }
            assertSame(outer, PerlRuntime.current());
        }
        assertNull(PerlRuntime.currentOrNull());
    }

    @Test
    void exceptionalScopeExitRestoresThePriorRuntime() {
        PerlRuntime outer = new PerlRuntime();
        PerlRuntime inner = new PerlRuntime();

        try (PerlRuntime.Binding ignored = outer.bind()) {
            assertThrows(IllegalArgumentException.class, () -> {
                try (PerlRuntime.Binding nested = inner.bind()) {
                    throw new IllegalArgumentException("boom");
                }
            });
            assertSame(outer, PerlRuntime.current());
        }
    }

    @Test
    void publicRootBindingCreatesTemporarilyAndPreservesAnExistingRuntime() {
        assertNull(PerlRuntime.currentOrNull());
        try (PerlRuntime.Binding ignored = PerlRuntime.bindCurrentOrNew()) {
            assertNotNull(PerlRuntime.current());
        }
        assertNull(PerlRuntime.currentOrNull());

        PerlRuntime outer = new PerlRuntime();
        try (PerlRuntime.Binding ignored = outer.bind()) {
            try (PerlRuntime.Binding nested = PerlRuntime.bindCurrentOrNew()) {
                assertSame(outer, PerlRuntime.current());
            }
            assertSame(outer, PerlRuntime.current());
        }
    }

    @Test
    void bindingIsNotInheritedByChildThreads() throws Exception {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            FutureTask<PerlRuntime> childCurrent = new FutureTask<>(PerlRuntime::currentOrNull);
            Thread child = Thread.ofPlatform().name("unbound-perl-runtime-child").start(childCurrent);
            child.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(child.isAlive());
            assertNull(childCurrent.get(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void twoThreadsBindDifferentRuntimesWithoutLeakage() throws Exception {
        PerlRuntime first = new PerlRuntime();
        PerlRuntime second = new PerlRuntime();
        CountDownLatch bothBound = new CountDownLatch(2);
        CountDownLatch inspect = new CountDownLatch(1);

        FutureTask<PerlRuntime> firstTask = boundTask(first, bothBound, inspect);
        FutureTask<PerlRuntime> secondTask = boundTask(second, bothBound, inspect);
        Thread firstThread = Thread.ofPlatform().name("perl-runtime-first").start(firstTask);
        Thread secondThread = Thread.ofPlatform().name("perl-runtime-second").start(secondTask);

        try {
            assertTrue(bothBound.await(10, TimeUnit.SECONDS));
        } finally {
            inspect.countDown();
            firstThread.join(TimeUnit.SECONDS.toMillis(10));
            secondThread.join(TimeUnit.SECONDS.toMillis(10));
        }
        assertFalse(firstThread.isAlive());
        assertFalse(secondThread.isAlive());
        assertSame(first, firstTask.get(1, TimeUnit.SECONDS));
        assertSame(second, secondTask.get(1, TimeUnit.SECONDS));
        assertNull(PerlRuntime.currentOrNull());
    }

    @Test
    void outOfOrderAndCrossThreadCloseAreRejected() throws Exception {
        PerlRuntime runtime = new PerlRuntime();
        PerlRuntime.Binding outer = runtime.bind();
        PerlRuntime.Binding inner = runtime.bind();
        try {
            assertThrows(IllegalStateException.class, outer::close);

            FutureTask<Throwable> crossThreadClose = new FutureTask<>(() -> {
                try {
                    inner.close();
                    return null;
                } catch (Throwable error) {
                    return error;
                }
            });
            Thread thread = Thread.ofPlatform().start(crossThreadClose);
            thread.join(TimeUnit.SECONDS.toMillis(10));
            assertInstanceOf(IllegalStateException.class,
                    crossThreadClose.get(1, TimeUnit.SECONDS));
        } finally {
            inner.close();
            outer.close();
        }
    }

    @Test
    void reusedExecutorThreadDoesNotRetainAClosedBinding() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().name("perl-runtime-reused-worker").factory());
        try {
            PerlRuntime runtime = new PerlRuntime();
            assertSame(runtime, executor.submit(() -> {
                try (PerlRuntime.Binding ignored = runtime.bind()) {
                    return PerlRuntime.current();
                }
            }).get(10, TimeUnit.SECONDS));
            assertNull(executor.submit(PerlRuntime::currentOrNull).get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static FutureTask<PerlRuntime> boundTask(
            PerlRuntime runtime,
            CountDownLatch bothBound,
            CountDownLatch inspect) {
        return new FutureTask<>(() -> {
            try (PerlRuntime.Binding ignored = runtime.bind()) {
                bothBound.countDown();
                assertTrue(inspect.await(10, TimeUnit.SECONDS));
                return PerlRuntime.current();
            }
        });
    }
}
