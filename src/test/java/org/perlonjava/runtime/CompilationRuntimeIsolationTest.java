package org.perlonjava.runtime;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;
import org.perlonjava.runtime.perlmodule.FilterUtilCall;
import org.perlonjava.runtime.runtimetypes.PerlRuntime;
import org.perlonjava.runtime.runtimetypes.WarningFlags;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class CompilationRuntimeIsolationTest {

    @Test
    void warningHintAndFilterStateFollowNestedBindings() {
        PerlRuntime first = new PerlRuntime();
        PerlRuntime second = new PerlRuntime();

        try (PerlRuntime.Binding ignored = first.bind()) {
            WarningBitsRegistry.register("same-class", "first-bits");
            WarningBitsRegistry.setCallSiteHints(17);
            first.compilationState.hintSnapshots.put(7, Map.of("marker", "first"));
            first.compilationState.callSiteHintHashId = 7;
            WarningFlags.registerCategory("Phase10::first");
            WarningFlags.registerScopeWarnings(Set.of("uninitialized"));
            FilterUtilCall.markFilterInstalled();

            try (PerlRuntime.Binding nested = second.bind()) {
                assertNull(WarningBitsRegistry.get("same-class"));
                assertEquals(0, WarningBitsRegistry.getCallSiteHints());
                assertNull(HintHashRegistry.getCurrentCallSiteHintHash());
                assertFalse(WarningFlags.isCustomCategory("Phase10::first"));
                assertFalse(FilterUtilCall.wasFilterInstalled());

                WarningBitsRegistry.register("same-class", "second-bits");
                WarningBitsRegistry.setCallSiteHints(23);
                second.compilationState.hintSnapshots.put(7, Map.of("marker", "second"));
                second.compilationState.callSiteHintHashId = 7;
            }

            assertEquals("first-bits", WarningBitsRegistry.get("same-class"));
            assertEquals(17, WarningBitsRegistry.getCallSiteHints());
            assertEquals("first", HintHashRegistry.getCurrentCallSiteHintHash().get("marker"));
            assertTrue(WarningFlags.isCustomCategory("Phase10::first"));
            assertTrue(FilterUtilCall.wasFilterInstalled());
        }

        try (PerlRuntime.Binding ignored = second.bind()) {
            assertEquals("second-bits", WarningBitsRegistry.get("same-class"));
            assertEquals(23, WarningBitsRegistry.getCallSiteHints());
            assertEquals("second", HintHashRegistry.getCurrentCallSiteHintHash().get("marker"));
        }
    }

    @Test
    void concurrentErrorLocationsUseTheOwningSourceMap() throws Exception {
        PerlRuntime first = new PerlRuntime();
        PerlRuntime second = new PerlRuntime();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        FutureTask<String> firstTask = failureTask(first, "phase10-first.pl", 71,
                "first failure", ready, start);
        FutureTask<String> secondTask = failureTask(second, "phase10-second.pl", 93,
                "second failure", ready, start);
        Thread firstThread = Thread.ofPlatform().name("phase10-source-first").start(firstTask);
        Thread secondThread = Thread.ofPlatform().name("phase10-source-second").start(secondTask);

        try {
            assertTrue(ready.await(10, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            firstThread.join(TimeUnit.SECONDS.toMillis(30));
            secondThread.join(TimeUnit.SECONDS.toMillis(30));
        }
        assertFalse(firstThread.isAlive());
        assertFalse(secondThread.isAlive());
        assertAll(
                () -> assertTrue(firstTask.get(1, TimeUnit.SECONDS).contains("first failure")),
                () -> assertTrue(firstTask.get(1, TimeUnit.SECONDS).contains("phase10-first.pl")),
                () -> assertTrue(secondTask.get(1, TimeUnit.SECONDS).contains("second failure")),
                () -> assertTrue(secondTask.get(1, TimeUnit.SECONDS).contains("phase10-second.pl")));
    }

    private static FutureTask<String> failureTask(
            PerlRuntime runtime, String file, int line, String message,
            CountDownLatch ready, CountDownLatch start) {
        return new FutureTask<>(() -> {
            ready.countDown();
            assertTrue(start.await(10, TimeUnit.SECONDS));
            CompilerOptions options = new CompilerOptions();
            options.fileName = "<phase10-source-map>";
            options.code = "#line " + line + " \"" + file + "\"\ndie '" + message + "';";
            try (PerlRuntime.Binding ignored = runtime.bind()) {
                try {
                    PerlLanguageProvider.executePerlCode(options, false);
                    fail("die should throw");
                    return "";
                } catch (Exception error) {
                    return error.getMessage();
                }
            }
        });
    }
}
