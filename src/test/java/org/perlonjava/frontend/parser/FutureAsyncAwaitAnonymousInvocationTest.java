package org.perlonjava.frontend.parser;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@Tag("unit")
class FutureAsyncAwaitAnonymousInvocationTest {
    @BeforeEach
    void setUp() {
        PerlLanguageProvider.resetAll();
    }

    @AfterEach
    void tearDown() {
        PerlLanguageProvider.resetAll();
    }

    @Test
    void parsesPostfixInvocationAtStartOfPrototypeBlock() {
        assertDoesNotThrow(() -> parse("""
                use Future::AsyncAwait;
                sub run_async (&) { $_[0]->() }
                run_async {
                    async sub { 42 }->();
                };
                """));
    }

    private static void parse(String code) throws Exception {
        CompilerOptions options = new CompilerOptions();
        options.code = code;
        options.fileName = "future_asyncawait_anonymous_invocation.t";
        options.parseOnly = true;
        RuntimeArray.push(options.inc, new RuntimeScalar("src/main/perl/lib"));
        PerlLanguageProvider.executePerlCode(options, true);
    }
}
