package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class PerlRuntimeLifecycleTest {

    @Test
    void initializeExecuteAndCloseHaveExplicitLifecycle() throws Exception {
        PerlRuntime runtime = new PerlRuntime();
        assertFalse(runtime.isInitialized());
        assertFalse(runtime.isClosed());
        assertSame(runtime, runtime.initialize());
        assertTrue(runtime.isInitialized());
        assertNull(PerlRuntime.currentOrNull());

        PerlRuntime sentinel = new PerlRuntime();
        try (PerlRuntime.Binding ignored = sentinel.bind()) {
            assertSame(runtime, runtime.execute(PerlRuntime::current));
            assertSame(sentinel, PerlRuntime.current());
        }

        runtime.close();
        runtime.close();
        assertTrue(runtime.isClosed());
        assertThrows(IllegalStateException.class, runtime::bind);
        assertThrows(IllegalStateException.class, runtime::initialize);
        assertThrows(IllegalStateException.class, () -> runtime.execute(() -> 1));
    }

    @Test
    void executeSerializesOwnershipOfOneRuntime() throws Exception {
        PerlRuntime runtime = new PerlRuntime().initialize();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAttempted = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        FutureTask<Void> firstTask = new FutureTask<>(() -> runtime.execute(() -> {
            firstEntered.countDown();
            assertTrue(releaseFirst.await(10, TimeUnit.SECONDS));
            return null;
        }));
        FutureTask<Void> secondTask = new FutureTask<>(() -> {
            secondAttempted.countDown();
            return runtime.execute(() -> {
                secondEntered.countDown();
                return null;
            });
        });
        Thread first = Thread.ofPlatform().name("managed-runtime-first").start(firstTask);
        Thread second = null;
        try {
            assertTrue(firstEntered.await(10, TimeUnit.SECONDS));
            second = Thread.ofPlatform().name("managed-runtime-second").start(secondTask);
            assertTrue(secondAttempted.await(10, TimeUnit.SECONDS));
            assertFalse(secondEntered.await(250, TimeUnit.MILLISECONDS));
        } finally {
            releaseFirst.countDown();
            first.join(TimeUnit.SECONDS.toMillis(10));
            if (second != null) second.join(TimeUnit.SECONDS.toMillis(10));
            runtime.close();
        }
        assertFalse(first.isAlive());
        assertNotNull(second);
        assertFalse(second.isAlive());
        firstTask.get(1, TimeUnit.SECONDS);
        secondTask.get(1, TimeUnit.SECONDS);
    }

    @Test
    void independentManagedRuntimesExecuteConflictingProgramsConcurrently() throws Exception {
        PerlRuntime first = new PerlRuntime().initialize();
        PerlRuntime second = new PerlRuntime().initialize();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        FutureTask<String> firstTask = programTask(first, "first", false, ready, start);
        FutureTask<String> secondTask = programTask(second, "second", true, ready, start);
        Thread firstThread = Thread.ofPlatform().name("managed-program-first").start(firstTask);
        Thread secondThread = Thread.ofPlatform().name("managed-program-second").start(secondTask);

        try {
            assertTrue(ready.await(10, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            firstThread.join(TimeUnit.SECONDS.toMillis(30));
            secondThread.join(TimeUnit.SECONDS.toMillis(30));
            first.close();
            second.close();
        }
        assertFalse(firstThread.isAlive());
        assertFalse(secondThread.isAlive());
        assertEquals("first:first", firstTask.get(1, TimeUnit.SECONDS));
        assertEquals("second:second", secondTask.get(1, TimeUnit.SECONDS));
    }

    private static FutureTask<String> programTask(
            PerlRuntime runtime, String marker, boolean interpreter,
            CountDownLatch ready, CountDownLatch start) {
        return new FutureTask<>(() -> {
            ready.countDown();
            assertTrue(start.await(10, TimeUnit.SECONDS));
            return runtime.execute(() -> {
                CompilerOptions options = new CompilerOptions();
                options.fileName = "<managed-runtime-" + marker + ">";
                options.useInterpreter = interpreter;
                options.code = "$Phase12::value='" + marker + "'; "
                        + "sub Phase12::value { $Phase12::value } "
                        + "$Phase12::value . ':' . Phase12::value()";
                return PerlLanguageProvider.executePerlCode(options, false)
                        .scalar().toString();
            });
        });
    }
}
