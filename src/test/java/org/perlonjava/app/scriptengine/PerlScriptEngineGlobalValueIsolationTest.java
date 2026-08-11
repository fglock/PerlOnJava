package org.perlonjava.app.scriptengine;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.runtime.runtimetypes.PerlRuntime;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
class PerlScriptEngineGlobalValueIsolationTest {

    @Test
    void jsr223EnginesKeepGlobalValuesIndependent() throws Exception {
        PerlScriptEngine first = (PerlScriptEngine) new PerlScriptEngineFactory().getScriptEngine();
        PerlScriptEngine second = (PerlScriptEngine) new PerlScriptEngineFactory().getScriptEngine();

        assertEquals("first:array:hash", first.eval(
                "$Phase8a::scalar='first'; @Phase8a::array=('array'); "
                        + "$Phase8a::hash{key}='hash'; "
                        + "join(':', $Phase8a::scalar, $Phase8a::array[0], $Phase8a::hash{key})"));
        assertEquals("0:0:0", second.eval(
                "join(':', defined($Phase8a::scalar) ? 1 : 0, "
                        + "scalar(@Phase8a::array), scalar(keys %Phase8a::hash))"));
        assertEquals("first:array:hash", first.eval(
                "join(':', $Phase8a::scalar, $Phase8a::array[0], $Phase8a::hash{key})"));
    }

    @Test
    void jvmAndInterpreterBothResolveGlobalsThroughRuntimeFacade() throws Exception {
        for (boolean interpreter : new boolean[]{false, true}) {
            PerlRuntime first = new PerlRuntime();
            PerlRuntime second = new PerlRuntime();
            assertEquals("first:one:hash", execute(first,
                    "$Phase8a::scalar='first'; @Phase8a::array=('one'); "
                            + "$Phase8a::hash{key}='hash'; "
                            + "join(':', $Phase8a::scalar, $Phase8a::array[0], $Phase8a::hash{key})",
                    interpreter));
            assertEquals("0:0:0", execute(second,
                    "join(':', defined($Phase8a::scalar) ? 1 : 0, "
                            + "scalar(@Phase8a::array), scalar(keys %Phase8a::hash))",
                    interpreter));
            assertEquals("first:one:hash", execute(first,
                    "join(':', $Phase8a::scalar, $Phase8a::array[0], $Phase8a::hash{key})",
                    interpreter));
        }
    }

    private static String execute(PerlRuntime runtime, String source, boolean interpreter)
            throws Exception {
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            CompilerOptions options = new CompilerOptions();
            options.fileName = "<phase8a-global-isolation>";
            options.code = source;
            options.useInterpreter = interpreter;
            return PerlLanguageProvider.executePerlCode(options, false).scalar().toString();
        }
    }
}
