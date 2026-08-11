package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;
import org.perlonjava.runtime.io.StandardIO;
import org.perlonjava.runtime.operators.Readline;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class PerlRuntimeIOIsolationTest {

    @Test
    void newRuntimesOwnIndependentStandardAndBookkeepingHandles() {
        PerlRuntime first = new PerlRuntime();
        PerlRuntime second = new PerlRuntime();

        IOState firstState = snapshot(first);
        IOState secondState = snapshot(second);

        assertNotSame(firstState.stdout, secondState.stdout);
        assertNotSame(firstState.stderr, secondState.stderr);
        assertNotSame(firstState.stdin, secondState.stdin);
        assertSame(firstState.stdout, firstState.selected);
        assertSame(secondState.stdout, secondState.selected);
        assertSame(firstState.stdout, firstState.lastWritten);
        assertSame(secondState.stdout, secondState.lastWritten);
        assertNull(firstState.lastAccessed);
        assertNull(secondState.lastAccessed);
        assertNull(firstState.lastReadlineName);
        assertNull(secondState.lastReadlineName);
        assertTrue(firstState.stderr.isAutoFlush());
        assertTrue(secondState.stderr.isAutoFlush());
        assertNull(PerlRuntime.currentOrNull());
    }

    @Test
    void nestedBindingsRestoreAllCurrentHandleState() {
        PerlRuntime first = new PerlRuntime();
        PerlRuntime second = new PerlRuntime();
        IOState configuredFirst = configure(first, "first");
        IOState configuredSecond = configure(second, "second");

        try (PerlRuntime.Binding ignored = first.bind()) {
            assertState(configuredFirst);
            try (PerlRuntime.Binding nested = second.bind()) {
                assertState(configuredSecond);
            }
            assertState(configuredFirst);
        }

        assertNull(PerlRuntime.currentOrNull());
    }

    @Test
    void stdinReadsAndStderrWritesRemainInTheirRuntime() {
        PerlRuntime first = new PerlRuntime();
        PerlRuntime second = new PerlRuntime();
        ByteArrayOutputStream firstError = new ByteArrayOutputStream();
        ByteArrayOutputStream secondError = new ByteArrayOutputStream();

        installInputAndError(first, "first-input", firstError);
        installInputAndError(second, "second-input", secondError);

        assertEquals("first-input", readInputAndWriteError(first, "first-error"));
        assertEquals("second-input", readInputAndWriteError(second, "second-error"));
        assertEquals("first-error", firstError.toString(StandardCharsets.ISO_8859_1));
        assertEquals("second-error", secondError.toString(StandardCharsets.ISO_8859_1));
    }

    @Test
    void replacingUppercaseStdoutDoesNotReplaceTheLowercaseGlob() {
        PerlRuntime runtime = new PerlRuntime();
        RuntimeIO replacement = output(new ByteArrayOutputStream());

        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeIO lowercaseBefore = GlobalVariable.getGlobalIO("main::stdout").getRuntimeIO();
            assertSame(RuntimeIO.getStdout(), lowercaseBefore);
            RuntimeIO.setStdout(replacement);
            assertSame(replacement, RuntimeIO.getStdout());
            assertSame(replacement,
                    GlobalVariable.getGlobalIO("main::STDOUT").getRuntimeIO());
            assertSame(lowercaseBefore,
                    GlobalVariable.getGlobalIO("main::stdout").getRuntimeIO());
        }
    }

    @Test
    void deletingStdoutHidesItsStashEntryButPreservesItsHandle() {
        PerlRuntime runtime = new PerlRuntime();
        PerlRuntime other = new PerlRuntime();
        RuntimeIO otherStdout;
        try (PerlRuntime.Binding ignored = other.bind()) {
            otherStdout = RuntimeIO.getStdout();
        }

        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeIO stdout = RuntimeIO.getStdout();
            HashSpecialVariable stash = new HashSpecialVariable(
                    HashSpecialVariable.Id.STASH, "main::");
            RuntimeGlob stdoutGlob = GlobalVariable.getGlobalIO("main::STDOUT");

            RuntimeScalar deleted = stash.remove("STDOUT");

            assertSame(stdout, deleted.getRuntimeIO());
            assertFalse(GlobalVariable.existsGlobalIO("main::STDOUT"));
            assertFalse(stash.containsKey("STDOUT"));
            assertSame(stdout, RuntimeIO.getStdout());
            assertSame(stdout, GlobalVariable.getGlobalIO("main::STDOUT").getRuntimeIO());

            stdoutGlob.dynamicSaveState();
            assertTrue(GlobalVariable.existsGlobalIO("main::STDOUT"));
            stdoutGlob.dynamicRestoreState();
            assertFalse(GlobalVariable.existsGlobalIO("main::STDOUT"));
        }

        try (PerlRuntime.Binding ignored = other.bind()) {
            assertTrue(GlobalVariable.existsGlobalIO("main::STDOUT"));
            assertSame(otherStdout, RuntimeIO.getStdout());
            assertSame(otherStdout, GlobalVariable.getGlobalIO("main::STDOUT").getRuntimeIO());
        }
    }

    @Test
    void concurrentWritesAndBookkeepingRemainInTheirRuntime() throws Exception {
        PerlRuntime first = new PerlRuntime();
        PerlRuntime second = new PerlRuntime();
        ByteArrayOutputStream firstBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream secondBytes = new ByteArrayOutputStream();
        RuntimeIO firstOutput = output(firstBytes);
        RuntimeIO secondOutput = output(secondBytes);

        installOutput(first, firstOutput);
        installOutput(second, secondOutput);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        FutureTask<IOState> firstTask = writeTask(first, firstOutput, "first", ready, start);
        FutureTask<IOState> secondTask = writeTask(second, secondOutput, "second", ready, start);
        Thread firstThread = Thread.ofPlatform().name("perl-io-first").start(firstTask);
        Thread secondThread = Thread.ofPlatform().name("perl-io-second").start(secondTask);

        try {
            assertTrue(ready.await(10, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            firstThread.join(TimeUnit.SECONDS.toMillis(10));
            secondThread.join(TimeUnit.SECONDS.toMillis(10));
        }
        assertFalse(firstThread.isAlive());
        assertFalse(secondThread.isAlive());

        IOState firstState = firstTask.get(1, TimeUnit.SECONDS);
        IOState secondState = secondTask.get(1, TimeUnit.SECONDS);
        assertEquals("first", firstBytes.toString(StandardCharsets.ISO_8859_1));
        assertEquals("second", secondBytes.toString(StandardCharsets.ISO_8859_1));
        assertSame(firstOutput, firstState.stdout);
        assertSame(firstOutput, firstState.selected);
        assertSame(firstOutput, firstState.lastWritten);
        assertSame(firstOutput, firstState.lastAccessed);
        assertEquals("first-fh", firstState.lastReadlineName);
        assertSame(secondOutput, secondState.stdout);
        assertSame(secondOutput, secondState.selected);
        assertSame(secondOutput, secondState.lastWritten);
        assertSame(secondOutput, secondState.lastAccessed);
        assertEquals("second-fh", secondState.lastReadlineName);
        assertNull(PerlRuntime.currentOrNull());
    }

    @Test
    void resetReplacesOnlyTheBoundRuntimesStandardInput() {
        PerlRuntime first = new PerlRuntime();
        PerlRuntime second = new PerlRuntime();
        IOState firstBefore = configure(first, "first");
        IOState secondBefore = configure(second, "second");

        try (PerlRuntime.Binding ignored = first.bind()) {
            PerlLanguageProvider.resetAll();
            IOState firstAfter = snapshotCurrent();
            assertSame(firstBefore.stdout, firstAfter.stdout);
            assertSame(firstBefore.stderr, firstAfter.stderr);
            assertNotSame(firstBefore.stdin, firstAfter.stdin);
            assertSame(firstBefore.selected, firstAfter.selected);
            assertSame(firstBefore.lastWritten, firstAfter.lastWritten);
            assertSame(firstBefore.lastAccessed, firstAfter.lastAccessed);
            assertEquals(firstBefore.lastReadlineName, firstAfter.lastReadlineName);
        }

        assertState(second, secondBefore);
        assertNull(PerlRuntime.currentOrNull());
    }

    private static FutureTask<IOState> writeTask(
            PerlRuntime runtime,
            RuntimeIO output,
            String marker,
            CountDownLatch ready,
            CountDownLatch start) {
        return new FutureTask<>(() -> {
            try (PerlRuntime.Binding ignored = runtime.bind()) {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                output.write(marker);
                output.flush();
                RuntimeIO.setLastAccessedHandle(output);
                RuntimeIO.setLastReadlineHandleName(marker + "-fh");
                return snapshotCurrent();
            }
        });
    }

    private static void installOutput(PerlRuntime runtime, RuntimeIO output) {
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeIO.setStdout(output);
            RuntimeIO.setSelectedHandle(output);
            RuntimeIO.setLastWrittenHandle(output);
        }
    }

    private static void installInputAndError(
            PerlRuntime runtime, String input, ByteArrayOutputStream error) {
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeIO.setStdin(new RuntimeIO(new StandardIO(
                    new ByteArrayInputStream(input.getBytes(StandardCharsets.ISO_8859_1)))));
            RuntimeIO.setStderr(output(error));
        }
    }

    private static String readInputAndWriteError(PerlRuntime runtime, String error) {
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeIO input = RuntimeIO.getStdin();
            String value = Readline.readline(input).toString();
            assertSame(input, RuntimeIO.getLastAccessedHandle());
            RuntimeIO.getStderr().write(error);
            RuntimeIO.getStderr().flush();
            assertSame(input, RuntimeIO.getLastAccessedHandle());
            return value;
        }
    }

    private static IOState configure(PerlRuntime runtime, String name) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        RuntimeIO stdout = output(bytes);
        RuntimeIO stderr = output(new ByteArrayOutputStream());
        RuntimeIO stdin = new RuntimeIO(new StandardIO(
                new ByteArrayInputStream(name.getBytes(StandardCharsets.ISO_8859_1))));
        RuntimeIO selected = output(new ByteArrayOutputStream());
        RuntimeIO lastWritten = output(new ByteArrayOutputStream());
        RuntimeIO lastAccessed = output(new ByteArrayOutputStream());

        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeIO.setStdout(stdout);
            RuntimeIO.setStderr(stderr);
            RuntimeIO.setStdin(stdin);
            RuntimeIO.setSelectedHandle(selected);
            RuntimeIO.setLastWrittenHandle(lastWritten);
            RuntimeIO.setLastAccessedHandle(lastAccessed);
            RuntimeIO.setLastReadlineHandleName(name + "-fh");
            return snapshotCurrent();
        }
    }

    private static RuntimeIO output(ByteArrayOutputStream bytes) {
        return new RuntimeIO(new StandardIO(bytes, true));
    }

    private static IOState snapshot(PerlRuntime runtime) {
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            return snapshotCurrent();
        }
    }

    private static IOState snapshotCurrent() {
        return new IOState(
                RuntimeIO.getStdout(),
                RuntimeIO.getStderr(),
                RuntimeIO.getStdin(),
                RuntimeIO.getSelectedHandle(),
                RuntimeIO.getLastWrittenHandle(),
                RuntimeIO.getLastAccessedHandle(),
                RuntimeIO.getLastReadlineHandleName());
    }

    private static void assertState(PerlRuntime runtime, IOState expected) {
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            assertState(expected);
        }
    }

    private static void assertState(IOState expected) {
        IOState actual = snapshotCurrent();
        assertSame(expected.stdout, actual.stdout);
        assertSame(expected.stderr, actual.stderr);
        assertSame(expected.stdin, actual.stdin);
        assertSame(expected.selected, actual.selected);
        assertSame(expected.lastWritten, actual.lastWritten);
        assertSame(expected.lastAccessed, actual.lastAccessed);
        assertEquals(expected.lastReadlineName, actual.lastReadlineName);
    }

    private record IOState(
            RuntimeIO stdout,
            RuntimeIO stderr,
            RuntimeIO stdin,
            RuntimeIO selected,
            RuntimeIO lastWritten,
            RuntimeIO lastAccessed,
            String lastReadlineName) {
    }
}
