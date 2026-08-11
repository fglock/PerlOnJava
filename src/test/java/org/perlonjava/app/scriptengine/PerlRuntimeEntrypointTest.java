package org.perlonjava.app.scriptengine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.runtime.runtimetypes.PerlRuntime;
import org.perlonjava.runtime.runtimetypes.RuntimeList;

import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptEngine;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class PerlRuntimeEntrypointTest {

    @BeforeEach
    void resetGlobals() {
        PerlLanguageProvider.resetAll();
        assertNull(PerlRuntime.currentOrNull());
    }

    @Test
    void providerTemporarilyBindsUnboundJvmAndInterpreterCallers() throws Exception {
        for (boolean interpreter : new boolean[]{false, true}) {
            FutureTask<EntryResult> invocation = new FutureTask<>(() -> {
                assertNull(PerlRuntime.currentOrNull());
                RuntimeList result = PerlLanguageProvider.executePerlCode(
                        options("40 + 2", interpreter), false);
                return new EntryResult(result.scalar().getInt(), PerlRuntime.currentOrNull());
            });
            Thread worker = Thread.ofPlatform().name("unbound-provider-entry").start(invocation);
            worker.join(TimeUnit.SECONDS.toMillis(30));
            assertFalse(worker.isAlive());
            EntryResult result = invocation.get(1, TimeUnit.SECONDS);
            assertEquals(42, result.value);
            assertNull(result.runtimeAfter);
        }
    }

    @Test
    void nestedBeginRequireAndEvalPreserveTheCallersRuntime() throws Exception {
        PerlRuntime runtime = new PerlRuntime();
        String source = "BEGIN { require strict; eval q{ BEGIN { $main::phase3 = 40 } }; die $@ if $@ } $main::phase3 + 2";

        try (PerlRuntime.Binding ignored = runtime.bind()) {
            for (boolean interpreter : new boolean[]{false, true}) {
                PerlLanguageProvider.resetAll();
                RuntimeList result = PerlLanguageProvider.executePerlCode(
                        options(source, interpreter), false);
                assertEquals(42, result.scalar().getInt());
                assertSame(runtime, PerlRuntime.current());
            }
        }
        assertNull(PerlRuntime.currentOrNull());
    }

    @Test
    void jsr223EvalCompileAndCompiledEvalDoNotLeakBindings() throws Exception {
        FutureTask<Void> invocation = new FutureTask<>(() -> {
            assertNull(PerlRuntime.currentOrNull());
            ScriptEngine engine = new PerlScriptEngineFactory().getScriptEngine();
            assertEquals("42", engine.eval("40 + 2"));
            assertNull(PerlRuntime.currentOrNull());

            CompiledScript compiled = ((Compilable) engine).compile("40 + 2");
            assertNull(PerlRuntime.currentOrNull());
            assertEquals("42", compiled.eval());
            assertNull(PerlRuntime.currentOrNull());

            assertThrows(Exception.class, () -> engine.eval("my $x = ;"));
            assertNull(PerlRuntime.currentOrNull());
            return null;
        });
        Thread worker = Thread.ofPlatform().name("unbound-jsr223-entry").start(invocation);
        worker.join(TimeUnit.SECONDS.toMillis(60));
        assertFalse(worker.isAlive());
        invocation.get(1, TimeUnit.SECONDS);
    }

    @Test
    void eachScriptEngineOwnsADistinctRuntime() {
        PerlScriptEngine first = (PerlScriptEngine) new PerlScriptEngineFactory().getScriptEngine();
        PerlScriptEngine second = (PerlScriptEngine) new PerlScriptEngineFactory().getScriptEngine();
        PerlRuntime firstRuntime;
        PerlRuntime secondRuntime;

        try (PerlRuntime.Binding ignored = first.bindRuntime()) {
            firstRuntime = PerlRuntime.current();
        }
        try (PerlRuntime.Binding ignored = second.bindRuntime()) {
            secondRuntime = PerlRuntime.current();
        }
        assertNotSame(firstRuntime, secondRuntime);
        assertNull(PerlRuntime.currentOrNull());
    }

    private static CompilerOptions options(String source, boolean interpreter) {
        CompilerOptions options = new CompilerOptions();
        options.fileName = "<perl-runtime-entrypoint-test>";
        options.code = source;
        options.useInterpreter = interpreter;
        return options;
    }

    private record EntryResult(int value, PerlRuntime runtimeAfter) {
    }
}
