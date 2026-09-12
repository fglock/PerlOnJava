package org.perlonjava.backend.bytecode;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.frontend.lexer.LexerToken;
import org.perlonjava.frontend.lexer.LexerTokenType;
import org.perlonjava.runtime.runtimetypes.ErrorMessageUtil;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class InterpretedCodeClosureMetadataTest {

    @Test
    void closureCopyRetainsIndependentCleanupRegisterMetadata() {
        int[] bytecode = {Opcodes.SCOPE_EXIT_CLEANUP, 4};
        InterpretedCode template = new InterpretedCode(
                bytecode, new Object[0], new String[0], 8, null,
                "test", 1, null, null, null, 0, 0, null);

        InterpretedCode closure = template.withCapturedVars(null);

        assertTrue(closure.myVarRegisters.get(4));
        closure.myVarRegisters.clear(4);
        assertTrue(template.myVarRegisters.get(4));
        assertFalse(closure.myVarRegisters.get(4));
    }

    @Test
    void closureCopyReusesTemplateDeparseSourceText() {
        ErrorMessageUtil errorUtil = new ErrorMessageUtil("-e", List.of(
                new LexerToken(LexerTokenType.IDENTIFIER, "first"),
                new LexerToken(LexerTokenType.NEWLINE, "\n"),
                new LexerToken(LexerTokenType.IDENTIFIER, "second"),
                new LexerToken(LexerTokenType.EOF, "")
        ));
        InterpretedCode template = new InterpretedCode(
                new int[0], new Object[0], new String[0], 4, null,
                "-e", 1, null, null, errorUtil, 0, 0, null);

        InterpretedCode closure = template.withCapturedVars(null);

        assertSame(template.deparseSourceText, closure.deparseSourceText);
    }

    @Test
    void closureCopyReusesAbsentTemplateDeparseSourceText() {
        ErrorMessageUtil errorUtil = new ErrorMessageUtil("-e", List.of(
                new LexerToken(LexerTokenType.IDENTIFIER, "x".repeat(64 * 1024 + 1)),
                new LexerToken(LexerTokenType.EOF, "")
        ));
        InterpretedCode template = new InterpretedCode(
                new int[0], new Object[0], new String[0], 4, null,
                "-e", 1, null, null, errorUtil, 0, 0, null);

        InterpretedCode closure = template.withCapturedVars(null);

        assertNull(template.deparseSourceText);
        assertNull(closure.deparseSourceText);
    }
}
