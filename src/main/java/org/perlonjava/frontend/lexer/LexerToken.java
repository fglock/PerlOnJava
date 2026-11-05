package org.perlonjava.frontend.lexer;

/**
 * The LexerToken class represents a lexical token in a programming language or text.
 * A token is a basic unit of meaningful data, such as a keyword, identifier, operator,
 * or literal, that is produced by a lexical analyzer (lexer) during the process of
 * lexical analysis.
 *
 * <p>This class encapsulates the type and text of a token. The type is represented
 * by an instance of the LexerTokenType enum, which categorizes the token (e.g., keyword,
 * identifier, operator). The text is the actual string of characters that make up
 * the token.</p>
 */
public class LexerToken {
    /**
     * The type of the token, represented by an instance of the LexerTokenType enum.
     * This field categorizes the token (e.g., keyword, identifier, operator).
     */
    public final LexerTokenType type;

    /**
     * The text of the token, represented as a string.
     * This field contains the actual string of characters that make up the token.
     */
    public final String text;

    // Flyweight cache: single-char OPERATOR tokens and common constant tokens.
    // Fields are final so sharing instances across parses is safe.
    private static final LexerToken[] OPERATOR_CACHE = new LexerToken[128];
    public static final LexerToken TOKEN_EOF      = new LexerToken(LexerTokenType.EOF, "<EOF>");
    public static final LexerToken TOKEN_NEWLINE  = new LexerToken(LexerTokenType.NEWLINE, "\n");
    public static final LexerToken TOKEN_SPACE    = new LexerToken(LexerTokenType.WHITESPACE, " ");

    static {
        for (char c : "!\"#$%&'()*+,-./:;<=>?@[\\]^`{|}~".toCharArray()) {
            OPERATOR_CACHE[c] = new LexerToken(LexerTokenType.OPERATOR, String.valueOf(c));
        }
    }

    /**
     * Factory that returns a cached instance for common tokens, or allocates a new one.
     * Use this instead of {@code new LexerToken(...)} everywhere except the static
     * initialiser above.
     */
    public static LexerToken of(LexerTokenType type, String text) {
        if (type == LexerTokenType.OPERATOR && text.length() == 1) {
            char c = text.charAt(0);
            if (c < 128) {
                LexerToken cached = OPERATOR_CACHE[c];
                if (cached != null) return cached;
            }
        } else if (type == LexerTokenType.EOF) {
            return TOKEN_EOF;
        } else if (type == LexerTokenType.NEWLINE && "\n".equals(text)) {
            return TOKEN_NEWLINE;
        } else if (type == LexerTokenType.WHITESPACE && " ".equals(text)) {
            return TOKEN_SPACE;
        }
        return new LexerToken(type, text);
    }

    /**
     * Constructs a new LexerToken with the specified type and text.
     * Prefer the {@link #of} factory for common token types.
     *
     * @param type the type of the token, represented by an instance of the LexerTokenType enum
     * @param text the text of the token, represented as a string
     */
    public LexerToken(LexerTokenType type, String text) {
        this.type = type;
        this.text = text;
    }

    /**
     * Returns a string representation of the token.
     * The string representation includes the type and text of the token.
     *
     * @return a string representation of the token
     */
    @Override
    public String toString() {
        return "LexerToken{" + "type=" + type + ", text='" + text + '\'' + '}';
    }
}

