package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RuntimeCodeCloneCaptureOwnershipTest {

    @Test
    void clonedClosureOwnsCapturedReferentOnBothBackends() throws Exception {
        for (boolean interpreter : new boolean[]{false, true}) {
            PerlRuntime parent = new PerlRuntime();
            PerlRuntime child = new PerlRuntime();
            RuntimeScalar parentCode = compileCapturedObjectClosure(parent, interpreter);
            RuntimeScalar childCode = (RuntimeScalar) new RuntimeGraphCloner(parent, child)
                    .cloneGraph(parentCode);

            RuntimeCode cloned = (RuntimeCode) childCode.value;
            RuntimeScalar captured = cloned.capturedScalars[0];
            RuntimeHash capturedObject = (RuntimeHash) captured.value;
            int captureCount = captured.captureCount;

            assertNotSame(parentCode, childCode);
            assertSame(captured, cloned.closedOverVariables.values().iterator().next());
            assertTrue(captureCount > 0);
            assertEquals("alive", invoke(child, childCode));
            assertEquals(captureCount, captured.captureCount);
            assertSame(capturedObject, captured.value);
        }
    }

    private static RuntimeScalar compileCapturedObjectClosure(
            PerlRuntime runtime, boolean interpreter) throws Exception {
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            CompilerOptions options = new CompilerOptions();
            options.fileName = "<runtime-code-clone-capture-ownership>";
            options.useInterpreter = interpreter;
            options.code = "package CloneCaptureGuard; sub DESTROY {} "
                    + "package main; my $guard = bless {}, 'CloneCaptureGuard'; "
                    + "sub { $guard; 'alive' }";
            return PerlLanguageProvider.executePerlCode(options, false).scalar();
        }
    }

    private static String invoke(PerlRuntime runtime, RuntimeScalar code) {
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            return RuntimeCode.apply(code, new RuntimeArray(), RuntimeContextType.SCALAR)
                    .scalar().toString();
        }
    }
}
