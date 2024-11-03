package org.perlonjava.parser;

import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.PerlCompilerException;

import java.util.List;

public class TokenUtils {

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

    public static LexerToken peek(Parser parser) {
        parser.tokenIndex = Whitespace.skipWhitespace(parser.tokenIndex, parser.tokens);
        if (parser.tokenIndex >= parser.tokens.size()) {
            return new LexerToken(LexerTokenType.EOF, "");
        }
        return parser.tokens.get(parser.tokenIndex);
    }

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
            }
        }
        return str;
    }

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

    public static LexerToken consume(Parser parser) {
        parser.tokenIndex = Whitespace.skipWhitespace(parser.tokenIndex, parser.tokens);
        if (parser.tokenIndex >= parser.tokens.size()) {
            return new LexerToken(LexerTokenType.EOF, "");
        }
        return parser.tokens.get(parser.tokenIndex++);
    }

    public static LexerToken consume(Parser parser, LexerTokenType type) {
        LexerToken token = consume(parser);
        if (token.type != type) {
            throw new PerlCompilerException(
                    parser.tokenIndex, "Expected token " + type + " but got " + token, parser.ctx.errorUtil);
        }
        return token;
    }

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
