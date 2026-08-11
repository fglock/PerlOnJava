package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;
import org.perlonjava.runtime.io.StandardIO;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class GlobalIORuntimeIsolationTest {

    @Test
    void namedIoAndFormatSlotsFollowNestedRuntimeBindings() {
        PerlRuntime first = new PerlRuntime();
        PerlRuntime second = new PerlRuntime();
        RuntimeIO firstIo = output();
        RuntimeIO secondIo = output();
        RuntimeGlob firstGlob;
        RuntimeFormat firstFormat;
        RuntimeGlob secondGlob;
        RuntimeFormat secondFormat;

        try (PerlRuntime.Binding ignored = first.bind()) {
            firstGlob = GlobalVariable.getGlobalIO("Phase8::FH").setIO(firstIo);
            firstFormat = GlobalVariable.getGlobalFormatRef("Phase8::REPORT").setTemplate("first");
            assertSame(firstGlob, GlobalVariable.peekGlobalIO("Phase8::FH"));
            assertSame(firstFormat, GlobalVariable.getGlobalFormatRef("Phase8::REPORT"));

            try (PerlRuntime.Binding nested = second.bind()) {
                assertNull(GlobalVariable.peekGlobalIO("Phase8::FH"));
                assertFalse(GlobalVariable.existsGlobalFormat("Phase8::REPORT"));
                secondGlob = GlobalVariable.getGlobalIO("Phase8::FH").setIO(secondIo);
                secondFormat = GlobalVariable.getGlobalFormatRef("Phase8::REPORT").setTemplate("second");
                assertNotSame(firstGlob, secondGlob);
                assertNotSame(firstFormat, secondFormat);
            }

            assertSame(firstGlob, GlobalVariable.peekGlobalIO("Phase8::FH"));
            assertSame(firstIo, firstGlob.getRuntimeIO());
            assertSame(firstFormat, GlobalVariable.getGlobalFormatRef("Phase8::REPORT"));
            assertEquals("first", firstFormat.getTemplate());
        }

        try (PerlRuntime.Binding ignored = second.bind()) {
            assertSame(secondIo, GlobalVariable.peekGlobalIO("Phase8::FH").getRuntimeIO());
            assertEquals("second", GlobalVariable.getGlobalFormatRef("Phase8::REPORT").getTemplate());
        }
        assertNull(PerlRuntime.currentOrNull());
    }

    @Test
    void simultaneousNamedIoAndFormatMutationDoesNotCrossRuntimes() throws Exception {
        PerlRuntime first = new PerlRuntime();
        PerlRuntime second = new PerlRuntime();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        FutureTask<SlotSnapshot> firstTask = slotTask(first, "first", ready, start);
        FutureTask<SlotSnapshot> secondTask = slotTask(second, "second", ready, start);
        Thread firstThread = Thread.ofPlatform().name("phase8-io-first").start(firstTask);
        Thread secondThread = Thread.ofPlatform().name("phase8-io-second").start(secondTask);

        try {
            assertTrue(ready.await(10, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            firstThread.join(TimeUnit.SECONDS.toMillis(10));
            secondThread.join(TimeUnit.SECONDS.toMillis(10));
        }
        assertFalse(firstThread.isAlive());
        assertFalse(secondThread.isAlive());

        SlotSnapshot firstResult = firstTask.get(1, TimeUnit.SECONDS);
        SlotSnapshot secondResult = secondTask.get(1, TimeUnit.SECONDS);
        assertNotSame(firstResult.glob, secondResult.glob);
        assertNotSame(firstResult.format, secondResult.format);
        assertEquals("first", firstResult.format.getTemplate());
        assertEquals("second", secondResult.format.getTemplate());
        assertNull(PerlRuntime.currentOrNull());
    }

    @Test
    void resetClearsOnlyTheBoundRuntimesNamedIoAndFormats() {
        PerlRuntime first = new PerlRuntime();
        PerlRuntime second = new PerlRuntime();

        installSlots(first, "first");
        SlotSnapshot secondBefore = installSlots(second, "second");

        try (PerlRuntime.Binding ignored = first.bind()) {
            GlobalVariable.resetAllGlobals();
            assertNull(GlobalVariable.peekGlobalIO("Phase8::FH"));
            assertFalse(GlobalVariable.existsGlobalFormat("Phase8::REPORT"));
        }

        try (PerlRuntime.Binding ignored = second.bind()) {
            assertSame(secondBefore.glob, GlobalVariable.peekGlobalIO("Phase8::FH"));
            assertSame(secondBefore.format, GlobalVariable.getGlobalFormatRef("Phase8::REPORT"));
            assertEquals("second", secondBefore.format.getTemplate());
        }
    }

    @Test
    void namedIoAndFormatsStayIsolatedOnJvmAndInterpreter() throws Exception {
        for (boolean interpreter : new boolean[]{false, true}) {
            PerlRuntime first = new PerlRuntime();
            PerlRuntime second = new PerlRuntime();

            assertEquals("first", run(first,
                    "open Phase8c::FH, '>', \\$Phase8c::sink; "
                            + "print Phase8c::FH 'first'; close Phase8c::FH; $Phase8c::sink",
                    interpreter));
            assertEquals("second", run(second,
                    "open Phase8c::FH, '>', \\$Phase8c::sink; "
                            + "print Phase8c::FH 'second'; close Phase8c::FH; $Phase8c::sink",
                    interpreter));
            RuntimeGlob firstGlob;
            RuntimeFormat firstFormat;
            try (PerlRuntime.Binding ignored = first.bind()) {
                firstGlob = GlobalVariable.peekGlobalIO("Phase8c::FH");
                assertNotNull(firstGlob);
                firstFormat = GlobalVariable.getGlobalFormatRef("Phase8c::REPORT")
                        .setTemplate("FIRST");
                assertTrue(firstFormat.isFormatDefined());
                assertEquals("FIRST", firstFormat.getTemplate());
            }
            try (PerlRuntime.Binding ignored = second.bind()) {
                assertNotSame(firstGlob, GlobalVariable.peekGlobalIO("Phase8c::FH"));
                RuntimeFormat secondFormat = GlobalVariable.getGlobalFormatRef("Phase8c::REPORT")
                        .setTemplate("SECOND");
                assertNotSame(firstFormat, secondFormat);
                assertTrue(secondFormat.isFormatDefined());
                assertEquals("SECOND", secondFormat.getTemplate());
            }
        }
    }

    private static FutureTask<SlotSnapshot> slotTask(
            PerlRuntime runtime, String marker, CountDownLatch ready, CountDownLatch start) {
        return new FutureTask<>(() -> {
            try (PerlRuntime.Binding ignored = runtime.bind()) {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                RuntimeGlob glob = GlobalVariable.getGlobalIO("Phase8::FH").setIO(output());
                RuntimeFormat format = GlobalVariable.getGlobalFormatRef("Phase8::REPORT")
                        .setTemplate(marker);
                assertSame(glob, GlobalVariable.peekGlobalIO("Phase8::FH"));
                assertSame(format, GlobalVariable.getGlobalFormatRef("Phase8::REPORT"));
                return new SlotSnapshot(glob, format);
            }
        });
    }

    private static SlotSnapshot installSlots(PerlRuntime runtime, String marker) {
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeGlob glob = GlobalVariable.getGlobalIO("Phase8::FH").setIO(output());
            RuntimeFormat format = GlobalVariable.getGlobalFormatRef("Phase8::REPORT")
                    .setTemplate(marker);
            return new SlotSnapshot(glob, format);
        }
    }

    private static RuntimeIO output() {
        return new RuntimeIO(new StandardIO(new ByteArrayOutputStream(), true));
    }

    private static String run(PerlRuntime runtime, String source, boolean interpreter)
            throws Exception {
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            CompilerOptions options = new CompilerOptions();
            options.fileName = "<global-io-format-runtime-isolation>";
            options.code = source;
            options.useInterpreter = interpreter;
            return PerlLanguageProvider.executePerlCode(options, false).scalar().toString();
        }
    }

    private record SlotSnapshot(RuntimeGlob glob, RuntimeFormat format) {
    }
}
