package org.perlonjava.app.scriptengine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.runtime.io.StandardIO;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.RuntimeIO;
import org.perlonjava.runtime.runtimetypes.RuntimeList;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class CompilationLockTest {
    private RuntimeIO savedStdout;

    @BeforeEach
    void setUp() {
        PerlLanguageProvider.resetAll();
        savedStdout = RuntimeIO.stdout;
    }

    @AfterEach
    void tearDown() {
        RuntimeIO.stdout = savedStdout;
        GlobalVariable.getGlobalIO("main::STDOUT").setIO(savedStdout);
    }

    @Test
    void compileEntryPointQueuesBehindTheGlobalLock() throws Exception {
        FutureTask<Object> compilation = new FutureTask<>(() ->
                PerlLanguageProvider.compilePerlCode(options("40 + 2", false)));
        Thread worker = Thread.ofPlatform().name("queued-perl-compiler").unstarted(compilation);

        PerlLanguageProvider.COMPILE_LOCK.lock();
        try {
            worker.start();
            awaitQueued(worker);
            assertFalse(compilation.isDone());
        } finally {
            PerlLanguageProvider.COMPILE_LOCK.unlock();
        }

        assertNotNull(compilation.get(30, TimeUnit.SECONDS));
    }

    @Test
    void eachInvocationReleasesExactlyItsOwnReentrantHold() throws Exception {
        int initialHoldCount = PerlLanguageProvider.COMPILE_LOCK.getHoldCount();
        assertEquals(0, initialHoldCount, "test thread inherited a leaked compilation-lock hold");
        PerlLanguageProvider.COMPILE_LOCK.lock();
        try {
            int outerHoldCount = initialHoldCount + 1;
            assertEquals(outerHoldCount, PerlLanguageProvider.COMPILE_LOCK.getHoldCount());
            assertNotNull(PerlLanguageProvider.compilePerlCode(options("40 + 2", false)));
            assertEquals(outerHoldCount, PerlLanguageProvider.COMPILE_LOCK.getHoldCount());

            assertThrows(Exception.class, () ->
                    PerlLanguageProvider.compilePerlCode(options("my $x = ;", true)));
            assertEquals(outerHoldCount, PerlLanguageProvider.COMPILE_LOCK.getHoldCount());
        } finally {
            PerlLanguageProvider.COMPILE_LOCK.unlock();
        }
        assertEquals(initialHoldCount, PerlLanguageProvider.COMPILE_LOCK.getHoldCount());
    }

    @Test
    void concurrentJvmAndInterpreterCompilationsAreDeterministic() throws Exception {
        int workerCount = 8;
        CountDownLatch ready = new CountDownLatch(workerCount);
        CountDownLatch start = new CountDownLatch(1);
        List<FutureTask<Object>> tasks = new ArrayList<>();

        for (int i = 0; i < workerCount; i++) {
            int id = i;
            FutureTask<Object> task = new FutureTask<>(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return PerlLanguageProvider.compilePerlCode(options(
                        "package Phase2::P" + id + "; sub f" + id + " { qr/x/o; " + id + " } f" + id + "()",
                        (id & 1) != 0));
            });
            tasks.add(task);
            Thread.ofPlatform().name("concurrent-perl-compiler-" + id).start(task);
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        for (FutureTask<Object> task : tasks) {
            assertNotNull(task.get(60, TimeUnit.SECONDS));
        }
    }

    @Test
    void nestedBeginEvalCompilationIsReentrantForBothBackends() throws Exception {
        String source = "BEGIN { eval q{ BEGIN { $main::phase2 = 40 } }; die $@ if $@ } $main::phase2 + 2";
        for (boolean interpreter : new boolean[]{false, true}) {
            RuntimeList result = PerlLanguageProvider.executePerlCode(
                    options(source, interpreter), false);
            assertEquals(42, result.scalar().getInt());
        }
    }

    @Test
    void ordinaryExecutionDoesNotRetainItsCompilationHold() throws Exception {
        BlockingOutputStream output = new BlockingOutputStream();
        RuntimeIO.stdout = new RuntimeIO(new StandardIO(output, true));
        GlobalVariable.getGlobalIO("main::STDOUT").setIO(RuntimeIO.stdout);

        FutureTask<RuntimeList> execution = new FutureTask<>(() ->
                PerlLanguageProvider.executePerlCode(options("print 'x'; 42", false), false));
        Thread worker = Thread.ofPlatform().name("blocked-perl-execution").start(execution);
        try {
            assertTrue(output.entered.await(30, TimeUnit.SECONDS), "program did not reach output flush");
            assertTrue(PerlLanguageProvider.COMPILE_LOCK.tryLock(5, TimeUnit.SECONDS),
                    "ordinary execution still owned the compilation lock");
            PerlLanguageProvider.COMPILE_LOCK.unlock();
        } finally {
            output.release.countDown();
            worker.join(TimeUnit.SECONDS.toMillis(30));
        }

        assertFalse(worker.isAlive());
        assertEquals(42, execution.get(5, TimeUnit.SECONDS).scalar().getInt());
    }

    private static CompilerOptions options(String source, boolean interpreter) {
        CompilerOptions options = new CompilerOptions();
        options.fileName = "<compilation-lock-test>";
        options.code = source;
        options.useInterpreter = interpreter;
        return options;
    }

    private static void awaitQueued(Thread worker) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!PerlLanguageProvider.COMPILE_LOCK.hasQueuedThread(worker)) {
            if (!worker.isAlive()) {
                fail("compiler worker exited before queueing");
            }
            if (System.nanoTime() >= deadline) {
                fail("compiler worker did not queue for the compilation lock");
            }
            Thread.onSpinWait();
        }
    }

    private static final class BlockingOutputStream extends OutputStream {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void write(int value) throws IOException {
            entered.countDown();
            try {
                if (!release.await(30, TimeUnit.SECONDS)) {
                    throw new IOException("timed out waiting to release blocked output");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        }
    }
}
