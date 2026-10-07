package org.perlonjava;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;
import org.perlonjava.runtime.runtimetypes.RuntimeList;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
class LexicalMethodBackendParityTest {
    @BeforeEach
    void resetRuntime() {
        PerlLanguageProvider.resetAll();
    }

    @Test
    void directLexicalMethodCallsWorkOnBothBackends() throws Exception {
        String source = """
                use strict;
                use warnings;
                use feature 'class';
                no warnings 'experimental::class';

                class LexicalMethodBackendParity {
                    my method private_value { return 'private'; }
                    my method signed_value ($suffix = '') { return 'signed' . $suffix; }

                    method call_private_value { return private_value($self); }
                    method call_signed_value { return signed_value($self, '!'); }
                }

                join ',',
                    LexicalMethodBackendParity->new->call_private_value,
                    LexicalMethodBackendParity->new->call_signed_value;
                """;

        for (boolean interpreter : new boolean[] { false, true }) {
            PerlLanguageProvider.resetAll();
            CompilerOptions options = new CompilerOptions();
            options.fileName = "<lexical-method-backend-parity>";
            options.code = source;
            options.useInterpreter = interpreter;

            RuntimeList result = PerlLanguageProvider.executePerlCode(options, false);

            assertEquals("private,signed!", result.scalar().toString(),
                    "direct lexical method calls should work when interpreter=" + interpreter);
        }
    }
}
