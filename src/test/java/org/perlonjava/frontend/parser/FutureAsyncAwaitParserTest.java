package org.perlonjava.frontend.parser;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class FutureAsyncAwaitParserTest {
    private PrintStream originalOut;
    private ByteArrayOutputStream capturedOut;

    @BeforeEach
    void setUp() {
        PerlLanguageProvider.resetAll();
        originalOut = System.out;
        capturedOut = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capturedOut, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
        PerlLanguageProvider.resetAll();
    }

    @Test
    void compatibilityFacadeLoadsWithoutXs() {
        assertDoesNotThrow(() -> execute("use Future::AsyncAwait; 1;", false));
    }

    @Test
    void parsesAnonymousAsyncSubAndAwaitIntoAnnotatedAst() {
        assertDoesNotThrow(() -> execute("""
                use Future::AsyncAwait;
                my $code = async sub {
                    my $future;
                    await $future;
                };
                """, true));

        String ast = capturedOut.toString(StandardCharsets.UTF_8);
        assertTrue(ast.contains("SubroutineNode:"), ast);
        assertTrue(ast.contains("futureAsyncAwaitSub: 'true'"), ast);
        assertTrue(ast.contains("OperatorNode: await"), ast);
        assertTrue(ast.contains("futureAsyncAwait: 'true'"), ast);
    }

    @Test
    void parsesNamedAsyncSub() {
        assertDoesNotThrow(() -> execute("""
                use Future::AsyncAwait;
                async sub named_task { }
                """, true));

        String ast = capturedOut.toString(StandardCharsets.UTF_8);
        assertTrue(ast.contains("futureAsyncAwaitSub: 'true'"), ast);
        assertTrue(ast.contains("compileTimeOnly: 'true'"), ast);
    }

    @Test
    void parsesLexicalAsyncSubWithSignatureAndAttributes() {
        assertDoesNotThrow(() -> execute("""
                use v5.26;
                use feature 'signatures';
                use Future::AsyncAwait;
                sub enclosing {
                    my async sub lexical_task :method ($future) {
                        await $future;
                    }
                }
                """, true));
    }

    @Test
    void parsesAsyncForwardDeclarationAndDefinition() {
        assertDoesNotThrow(() -> execute("""
                use Future::AsyncAwait;
                async sub declared_task;
                async sub declared_task { await $_[0] }
                """, true));
    }

    @Test
    void parsesAsyncClassMethod() {
        assertDoesNotThrow(() -> execute("""
                use feature 'class';
                no warnings 'experimental::class';
                use Future::AsyncAwait;
                class AsyncExample {
                    async method perform($future) { await $future }
                }
                """, true));

        String ast = capturedOut.toString(StandardCharsets.UTF_8);
        assertTrue(ast.contains("futureAsyncAwaitSub: 'true'"), ast);
        assertTrue(ast.contains("OperatorNode: await"), ast);
    }

    @Test
    void rejectsAwaitInsideOrdinarySub() {
        Exception exception = assertThrows(Exception.class, () -> execute("""
                use Future::AsyncAwait;
                sub ordinary {
                    my $future;
                    await $future;
                }
                """, true));

        assertTrue(rootMessage(exception).contains("Cannot 'await' outside of an 'async sub'"),
                rootMessage(exception));
    }

    @Test
    void unimportDisablesSyntaxLexically() {
        Exception exception = assertThrows(Exception.class, () -> execute("""
                use Future::AsyncAwait;
                {
                    no Future::AsyncAwait;
                    async sub disabled { }
                }
                """, true));

        assertTrue(rootMessage(exception).contains("syntax error"), rootMessage(exception));
    }

    @Test
    void importCapabilityDoesNotLeakOutOfBlock() {
        Exception exception = assertThrows(Exception.class, () -> execute("""
                {
                    use Future::AsyncAwait;
                    async sub enabled_here { }
                }
                async sub disabled_here { }
                """, true));

        assertTrue(rootMessage(exception).contains("syntax error"), rootMessage(exception));
    }

    @Test
    void runtimeCompilationStopsAtExplicitPhaseBoundary() {
        Exception exception = assertThrows(Exception.class, () -> execute("""
                use Future::AsyncAwait;
                my $code = async sub { 1 };
                """, false));

        assertTrue(rootMessage(exception).contains(FutureAsyncAwaitParser.BACKEND_MESSAGE),
                rootMessage(exception));
    }

    private static void execute(String code, boolean parseOnly) throws Exception {
        CompilerOptions options = new CompilerOptions();
        options.code = code;
        options.fileName = "future_asyncawait_phase1.t";
        options.parseOnly = parseOnly;
        RuntimeArray.push(options.inc, new RuntimeScalar("src/main/perl/lib"));
        PerlLanguageProvider.executePerlCode(options, true);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() != null ? root.getMessage() : root.toString();
    }
}
