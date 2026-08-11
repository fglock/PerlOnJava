package org.perlonjava.app.scriptengine;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.perlonjava.runtime.io.StandardIO;
import org.perlonjava.runtime.runtimetypes.PerlRuntime;
import org.perlonjava.runtime.runtimetypes.RuntimeIO;

import javax.script.Compilable;
import javax.script.CompiledScript;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class PerlScriptEngineIOIsolationTest {

    @BeforeEach
    void resetGlobals() {
        PerlLanguageProvider.resetAll();
        assertNull(PerlRuntime.currentOrNull());
    }

    @Test
    void compiledScriptsWriteToTheirOwningEngineRuntime() throws Exception {
        PerlScriptEngine first = (PerlScriptEngine) new PerlScriptEngineFactory().getScriptEngine();
        PerlScriptEngine second = (PerlScriptEngine) new PerlScriptEngineFactory().getScriptEngine();

        // Initialize the remaining process-global runtime tables before
        // alternating between engines; Phase 4 isolates I/O, not global stacks.
        assertEquals("1", first.eval("1"));
        assertEquals("1", second.eval("1"));
        assertEquals("1", first.eval("exists $main::{STDOUT} ? 1 : 0"));
        assertEquals("1", first.eval("scalar(grep { $_ eq 'STDOUT' } keys %main::)"));

        ByteArrayOutputStream firstBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream secondBytes = new ByteArrayOutputStream();
        configureOutput(first, firstBytes);
        configureOutput(second, secondBytes);

        assertEquals("1", first.eval("print 'first-eval'; 1"));
        assertEquals("1", second.eval("print 'second-eval'; 1"));

        CompiledScript firstScript = ((Compilable) first).compile(
                "print 'first-bare'; print STDOUT '-first-explicit'; 41 + 1");
        CompiledScript secondScript = ((Compilable) second).compile(
                "print 'second-bare'; print STDOUT '-second-explicit'; 40 + 3");

        // Execution-stack state remains process-global until Phase 5, so this
        // phase verifies engine ownership without claiming concurrent Perl
        // execution support. The lower-level I/O test exercises simultaneous
        // writes without entering those still-global stacks.
        assertEquals("42", firstScript.eval());
        assertEquals("43", secondScript.eval());
        flushOutput(first);
        flushOutput(second);
        assertEquals("first-evalfirst-bare-first-explicit",
                firstBytes.toString(StandardCharsets.ISO_8859_1));
        assertEquals("second-evalsecond-bare-second-explicit",
                secondBytes.toString(StandardCharsets.ISO_8859_1));
        assertNull(PerlRuntime.currentOrNull());
    }

    @Test
    void engineEvaluationRestoresAnOuterRuntimeAndItsSelectedHandle() throws Exception {
        PerlScriptEngine engine = (PerlScriptEngine) new PerlScriptEngineFactory().getScriptEngine();
        PerlRuntime outer = new PerlRuntime();
        RuntimeIO outerSelected = new RuntimeIO(new StandardIO(new ByteArrayOutputStream(), true));

        try (PerlRuntime.Binding ignored = outer.bind()) {
            RuntimeIO.setSelectedHandle(outerSelected);
            assertEquals("42", engine.eval("40 + 2"));
            assertSame(outer, PerlRuntime.current());
            assertSame(outerSelected, RuntimeIO.getSelectedHandle());
        }
        assertNull(PerlRuntime.currentOrNull());
    }

    @Test
    void jvmReadlineEmitterUpdatesTheOwningRuntime() throws Exception {
        PerlScriptEngine engine = (PerlScriptEngine) new PerlScriptEngineFactory().getScriptEngine();
        try (PerlRuntime.Binding ignored = engine.bindRuntime()) {
            RuntimeIO.setStdin(new RuntimeIO(new StandardIO(
                    new ByteArrayInputStream("line\n".getBytes(StandardCharsets.ISO_8859_1)))));
        }

        assertEquals("line\n", engine.eval("<STDIN>"));
        try (PerlRuntime.Binding ignored = engine.bindRuntime()) {
            assertEquals("STDIN", RuntimeIO.getLastReadlineHandleName());
        }
        assertNull(PerlRuntime.currentOrNull());
    }

    private static void configureOutput(PerlScriptEngine engine, ByteArrayOutputStream bytes) {
        try (PerlRuntime.Binding ignored = engine.bindRuntime()) {
            RuntimeIO output = new RuntimeIO(new StandardIO(bytes, true));
            RuntimeIO.setStdout(output);
            RuntimeIO.setSelectedHandle(output);
            RuntimeIO.setLastWrittenHandle(output);
        }
    }

    private static void flushOutput(PerlScriptEngine engine) {
        try (PerlRuntime.Binding ignored = engine.bindRuntime()) {
            RuntimeIO.getStdout().flush();
        }
    }
}
