package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

@Tag("unit")
class RuntimeCodeGraphClonerTest {

    @Test
    void jvmAndInterpreterClosuresCloneCapturedScalarArrayAndHash() throws Exception {
        for (boolean interpreter : new boolean[]{false, true}) {
            PerlRuntime sourceRuntime = new PerlRuntime();
            PerlRuntime targetRuntime = new PerlRuntime();
            RuntimeScalar sourceCodeRef = compileClosure(sourceRuntime, interpreter);
            RuntimeScalar clonedCodeRef = (RuntimeScalar) new RuntimeGraphCloner(
                    sourceRuntime, targetRuntime).cloneGraph(sourceCodeRef);

            assertNotSame(sourceCodeRef, clonedCodeRef);
            assertNotSame(sourceCodeRef.value, clonedCodeRef.value);
            assertEquals("41:2:2", invoke(sourceRuntime, sourceCodeRef));
            assertEquals("41:2:2", invoke(targetRuntime, clonedCodeRef));
            assertEquals("42:3:3", invoke(sourceRuntime, sourceCodeRef));
            assertEquals("42:3:3", invoke(targetRuntime, clonedCodeRef));
        }
    }

    private static RuntimeScalar compileClosure(PerlRuntime runtime, boolean interpreter)
            throws Exception {
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            CompilerOptions options = new CompilerOptions();
            options.fileName = "<runtime-code-graph-cloner>";
            options.useInterpreter = interpreter;
            options.code = "my $x = 40; my @a = (1); my %h = (one => 1); "
                    + "sub { ++$x; push @a, $x; $h{$x} = 1; join q(:), $x, scalar @a, scalar keys %h }";
            return PerlLanguageProvider.executePerlCode(options, false).scalar();
        }
    }

    private static String invoke(PerlRuntime runtime, RuntimeScalar codeRef) throws Exception {
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            return RuntimeCode.apply(codeRef, new RuntimeArray(), RuntimeContextType.SCALAR)
                    .scalar().toString();
        }
    }
}
