package org.perlonjava.parser;

import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.PerlCompilerException;

import java.util.List;

/**
 * The TokenUtils class provides utility methods for handling and manipulating
 * lexer tokens during parsing. It includes methods for converting tokens to text,
 * peeking at the next token, and consuming tokens with specific types or text.
 */
public class TokenUtils {

    /**
     * Converts a range of tokens into a single string of text, excluding EOF tokens.
     *
     * @param tokens    The list of LexerToken objects to process.
     * @param codeStart The starting index in the list of tokens.
     * @param codeEnd   The ending index in the list of tokens.
     * @return A string representing the concatenated text of the specified token range.
     */
    public static String toText(List<LexerToken> tokens, int codeStart, int codeEnd) {
        StringBuilder sb = new StringBuilder();
        codeStart = Math.max(codeStart, 0);
        codeEnd = Math.min(codeEnd, tokens.size() - 1);
        for (int i = codeStart; i <= codeEnd; i++) {
            LexerToken tok = tokens.get(i);
            if (tok.type != LexerTokenType.EOF) {
                sb.append(tok.text);
            }
        }
        return sb.toString();
    }

    /**
     * Peeks at the next non-whitespace token in the parser's token list without consuming it.
     * Whitespace is consumed.
     *
     * @param parser The parser containing the token list and current token index.
     * @return The next non-whitespace LexerToken, or an EOF token if the end of the list is reached.
     */
    public static LexerToken peek(Parser parser) {
        parser.tokenIndex = Whitespace.skipWhitespace(parser, parser.tokenIndex, parser.tokens);
        if (parser.tokenIndex >= parser.tokens.size()) {
            return new LexerToken(LexerTokenType.EOF, "");
        }
        return parser.tokens.get(parser.tokenIndex);
    }

    /**
     * Consumes a single character from the current token in the parser's token list.
     * If the token contains only one character, it advances to the next token.
     *
     * @param parser The parser containing the token list and current token index.
     * @return The consumed character as a string.
     */
    public static String consumeChar(Parser parser) {
        String str;
        if (parser.tokenIndex >= parser.tokens.size()) {
            str = "";
        } else {
            LexerToken token = parser.tokens.get(parser.tokenIndex);
            if (token.type == LexerTokenType.EOF) {
                str = "";
            } else if (token.text.length() == 1) {
                str = token.text;
                parser.tokenIndex++;
            } else {
                str = token.text.substring(0, 1);
                token.text = token.text.substring(1);
                parser.ctx.logDebug("consumeChar left: " + token);
                if (token.text.equals("=")) {
                    LexerToken next = parser.tokens.get(parser.tokenIndex + 1);
                    if (next.text.equals("=")) {
                        next.text = "==";
                        parser.tokenIndex++;
                        parser.ctx.logDebug("consumeChar resync: " + TokenUtils.peek(parser));
                    }
                }
            }
        }
        return str;
    }

    /**
     * Peeks at the next character in the current token in the parser's token list without consuming it.
     *
     * @param parser The parser containing the token list and current token index.
     * @return The next character as a string, or an empty string if the end of the list is reached.
     */
    public static String peekChar(Parser parser) {
        String str;
        if (parser.tokenIndex >= parser.tokens.size()) {
            str = "";
        } else {
            LexerToken token = parser.tokens.get(parser.tokenIndex);
            if (token.type == LexerTokenType.EOF) {
                str = "";
            } else if (token.text.length() == 1) {
                str = token.text;
            } else {
                str = token.text.substring(0, 1);
            }
        }
        return str;
    }

    /**
     * Consumes the next non-whitespace token in the parser's token list.
     *
     * @param parser The parser containing the token list and current token index.
     * @return The consumed LexerToken, or an EOF token if the end of the list is reached.
     */
    public static LexerToken consume(Parser parser) {
        parser.tokenIndex = Whitespace.skipWhitespace(parser, parser.tokenIndex, parser.tokens);
        if (parser.tokenIndex >= parser.tokens.size()) {
            return new LexerToken(LexerTokenType.EOF, "");
        }
        return parser.tokens.get(parser.tokenIndex++);
    }

    /**
     * Consumes the next non-whitespace token in the parser's token list and checks its type.
     * Throws an exception if the token type does not match the expected type.
     *
     * @param parser The parser containing the token list and current token index.
     * @param type   The expected LexerTokenType of the token to consume.
     * @return The consumed LexerToken.
     * @throws PerlCompilerException if the token type does not match the expected type.
     */
    public static LexerToken consume(Parser parser, LexerTokenType type) {
        LexerToken token = consume(parser);
        if (token.type != type) {
            throw new PerlCompilerException(
                    parser.tokenIndex, "Expected token " + type + " but got " + token, parser.ctx.errorUtil);
        }
        return token;
    }

    /**
     * Consumes the next non-whitespace token in the parser's token list and checks its type and text.
     * Throws an exception if the token type or text does not match the expected values.
     *
     * @param parser The parser containing the token list and current token index.
     * @param type   The expected LexerTokenType of the token to consume.
     * @param text   The expected text of the token to consume.
     * @throws PerlCompilerException if the token type or text does not match the expected values.
     */
    public static void consume(Parser parser, LexerTokenType type, String text) {
        LexerToken token = consume(parser);
        if (token.type != type || !token.text.equals(text)) {
            throw new PerlCompilerException(
                    parser.tokenIndex,
                    "Expected token " + type + " with text " + text + " but got " + token,
                    parser.ctx.errorUtil);
        }
    }
}
