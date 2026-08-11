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
class RuntimeCodeRuntimeIsolationTest {

    @Test
    void evalRegistriesOptionsAndInlineCachesBelongToTheBoundRuntime() {
        PerlRuntime first = new PerlRuntime();
        PerlRuntime second = new PerlRuntime();
        RuntimeCode firstMethod = codeReturning("first");
        RuntimeCode secondMethod = codeReturning("second");

        try (PerlRuntime.Binding ignored = first.bind()) {
            assertEquals("(eval 1)", RuntimeCode.getNextEvalFilename());
            assertEquals(0, RuntimeCode.allocateMethodCallsiteId());
            RuntimeCode.putInterpretedSub("same", firstMethod);
            RuntimeCode.registerAnonymousSub("same", String.class);
            RuntimeCode.registerPadConstants("same", new RuntimeBase[]{new RuntimeScalar("first")});
            first.runtimeCodeState.evalCache.put("same", String.class);
            first.runtimeCodeState.methodHandleCache.put(String.class, null);
            first.runtimeCodeState.evalContexts.put("same", null);
            first.runtimeCodeState.cacheInlineMethod(0, 17, 23, firstMethod);
            RuntimeCode.setDisassemble(true);
            RuntimeCode.setUseInterpreter(true);
            RuntimeCode.enableLexicalAliasSupport();
        }

        try (PerlRuntime.Binding ignored = second.bind()) {
            RuntimeCode.setDisassemble(false);
            RuntimeCode.setUseInterpreter(false);
            assertEquals("(eval 1)", RuntimeCode.getNextEvalFilename());
            assertEquals(0, RuntimeCode.allocateMethodCallsiteId());
            assertNull(RuntimeCode.getInterpretedSub("same"));
            assertFalse(second.runtimeCodeState.anonymousSubs.containsKey("same"));
            assertFalse(second.runtimeCodeState.padConstantsByClassName.containsKey("same"));
            assertFalse(second.runtimeCodeState.evalCache.containsKey("same"));
            assertFalse(second.runtimeCodeState.methodHandleCache.containsKey(String.class));
            assertFalse(second.runtimeCodeState.evalContexts.containsKey("same"));
            assertNull(second.runtimeCodeState.cachedInlineMethod(0, 17, 23));
            assertFalse(RuntimeCode.isDisassemble());
            assertFalse(RuntimeCode.isUseInterpreter());
            assertFalse(second.runtimeCodeState.lexicalAliasSupportEnabled);

            RuntimeCode.putInterpretedSub("same", secondMethod);
            second.runtimeCodeState.cacheInlineMethod(0, 17, 23, secondMethod);
        }

        try (PerlRuntime.Binding ignored = first.bind()) {
            assertSame(firstMethod, RuntimeCode.getInterpretedSub("same"));
            assertSame(String.class, first.runtimeCodeState.anonymousSubs.get("same"));
            assertEquals("first", first.runtimeCodeState.padConstantsByClassName
                    .get("same")[0].toString());
            assertSame(String.class, first.runtimeCodeState.evalCache.get("same"));
            assertTrue(first.runtimeCodeState.methodHandleCache.containsKey(String.class));
            assertTrue(first.runtimeCodeState.evalContexts.containsKey("same"));
            assertSame(firstMethod, first.runtimeCodeState.cachedInlineMethod(0, 17, 23));
            assertTrue(RuntimeCode.isDisassemble());
            assertTrue(RuntimeCode.isUseInterpreter());
            assertTrue(first.runtimeCodeState.lexicalAliasSupportEnabled);
        }

        try (PerlRuntime.Binding ignored = second.bind()) {
            assertSame(secondMethod, RuntimeCode.getInterpretedSub("same"));
            assertSame(secondMethod, second.runtimeCodeState.cachedInlineMethod(0, 17, 23));
        }
    }

    @Test
    void resetClearsOnlyTheBoundRuntimeCodeCaches() {
        PerlRuntime first = new PerlRuntime();
        PerlRuntime second = new PerlRuntime();
        Object firstCode = new Object();
        Object secondCode = new Object();

        try (PerlRuntime.Binding ignored = first.bind()) {
            RuntimeCode.putInterpretedSub("entry", firstCode);
        }
        try (PerlRuntime.Binding ignored = second.bind()) {
            RuntimeCode.putInterpretedSub("entry", secondCode);
        }
        try (PerlRuntime.Binding ignored = first.bind()) {
            RuntimeCode.clearCaches();
            assertNull(RuntimeCode.getInterpretedSub("entry"));
        }
        try (PerlRuntime.Binding ignored = second.bind()) {
            assertSame(secondCode, RuntimeCode.getInterpretedSub("entry"));
        }
    }

    @Test
    void concurrentEvalIdentifiersDoNotShareASequence() throws Exception {
        PerlRuntime first = new PerlRuntime();
        PerlRuntime second = new PerlRuntime();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        FutureTask<String> firstTask = evalSequenceTask(first, ready, start);
        FutureTask<String> secondTask = evalSequenceTask(second, ready, start);
        Thread firstThread = Thread.ofPlatform().start(firstTask);
        Thread secondThread = Thread.ofPlatform().start(secondTask);

        try {
            assertTrue(ready.await(10, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            firstThread.join(TimeUnit.SECONDS.toMillis(10));
            secondThread.join(TimeUnit.SECONDS.toMillis(10));
        }
        assertFalse(firstThread.isAlive());
        assertFalse(secondThread.isAlive());
        assertEquals("(eval 1):(eval 2)", firstTask.get(1, TimeUnit.SECONDS));
        assertEquals("(eval 1):(eval 2)", secondTask.get(1, TimeUnit.SECONDS));
    }

    @Test
    void concurrentMixedBackendEvalClosuresUseTheirOwningRegistry() throws Exception {
        PerlRuntime first = new PerlRuntime();
        PerlRuntime second = new PerlRuntime();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        FutureTask<String> firstTask = executionTask(
                first, source("Phase9Concurrent", "first"), false, ready, start);
        FutureTask<String> secondTask = executionTask(
                second, source("Phase9Concurrent", "second"), true, ready, start);
        Thread firstThread = Thread.ofPlatform().start(firstTask);
        Thread secondThread = Thread.ofPlatform().start(secondTask);

        try {
            assertTrue(ready.await(10, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            firstThread.join(TimeUnit.SECONDS.toMillis(30));
            secondThread.join(TimeUnit.SECONDS.toMillis(30));
        }
        assertFalse(firstThread.isAlive());
        assertFalse(secondThread.isAlive());
        assertEquals("first:first:first", firstTask.get(1, TimeUnit.SECONDS));
        assertEquals("second:second:second", secondTask.get(1, TimeUnit.SECONDS));
    }

    @Test
    void evalClosuresAndMethodDispatchStayIsolatedOnBothBackends() throws Exception {
        for (boolean interpreter : new boolean[]{false, true}) {
            PerlRuntime first = new PerlRuntime();
            PerlRuntime second = new PerlRuntime();
            String pkg = interpreter ? "Phase9Interpreter" : "Phase9Jvm";

            String firstSource = source(pkg, "first");
            String secondSource = source(pkg, "second");
            assertEquals("first:first:first", run(first, firstSource, interpreter));
            assertEquals("second:second:second", run(second, secondSource, interpreter));
            assertEquals("first:first", run(first,
                    "$" + pkg + "::closure->() . ':' . " + pkg + "->value", interpreter));
            assertEquals("second:second", run(second,
                    "$" + pkg + "::closure->() . ':' . " + pkg + "->value", interpreter));
        }
    }

    private static RuntimeCode codeReturning(String value) {
        return new RuntimeCode((args, context) -> new RuntimeScalar(value).getList(), null);
    }

    private static FutureTask<String> evalSequenceTask(
            PerlRuntime runtime, CountDownLatch ready, CountDownLatch start) {
        return new FutureTask<>(() -> {
            try (PerlRuntime.Binding ignored = runtime.bind()) {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return RuntimeCode.getNextEvalFilename() + ":"
                        + RuntimeCode.getNextEvalFilename();
            }
        });
    }

    private static FutureTask<String> executionTask(
            PerlRuntime runtime, String source, boolean interpreter,
            CountDownLatch ready, CountDownLatch start) {
        return new FutureTask<>(() -> {
            ready.countDown();
            assertTrue(start.await(10, TimeUnit.SECONDS));
            return run(runtime, source, interpreter);
        });
    }

    private static String source(String pkg, String value) {
        return "package " + pkg + ";"
                + " my $captured = '" + value + "';"
                + " our $closure = sub { eval '$captured' };"
                + " sub value { '" + value + "' }"
                + " my $object = bless {}, '" + pkg + "';"
                + " $closure->() . ':' . $object->value . ':' . $object->value";
    }

    private static String run(PerlRuntime runtime, String source, boolean interpreter)
            throws Exception {
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            CompilerOptions options = new CompilerOptions();
            options.fileName = "<runtime-code-isolation>";
            options.code = source;
            options.useInterpreter = interpreter;
            return PerlLanguageProvider.executePerlCode(options, false).scalar().toString();
        }
    }
}
