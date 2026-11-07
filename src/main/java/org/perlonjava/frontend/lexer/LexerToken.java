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

    // Cache pure-space WHITESPACE tokens up to this length (covers 1–4 level indentation
    // at 2/4/8-space increments; eval STRING tokenizes indented source on every call).
    private static final int MAX_CACHED_SPACES = 20;
    private static final LexerToken[] SPACE_CACHE = new LexerToken[MAX_CACHED_SPACES + 1];

    // Cache NUMBER tokens for small non-negative integers (0–99). eval STRING tokenizes
    // its source on every call; loop counters and small literals like 1 and 40 recur
    // constantly. Consumed by Lexer.consumeNumber() which avoids substring() for these.
    static final int MAX_CACHED_INT = 99;
    static final LexerToken[] INT_CACHE = new LexerToken[MAX_CACHED_INT + 1];

    static {
        for (char c : "!\"#$%&'()*+,-./:;<=>?@[\\]^`{|}~".toCharArray()) {
            OPERATOR_CACHE[c] = new LexerToken(LexerTokenType.OPERATOR, String.valueOf(c));
        }
        SPACE_CACHE[1] = TOKEN_SPACE;
        for (int i = 2; i <= MAX_CACHED_SPACES; i++) {
            SPACE_CACHE[i] = new LexerToken(LexerTokenType.WHITESPACE, " ".repeat(i));
        }
        for (int i = 0; i <= MAX_CACHED_INT; i++) {
            INT_CACHE[i] = new LexerToken(LexerTokenType.NUMBER, Integer.toString(i));
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
        } else if (type == LexerTokenType.WHITESPACE) {
            int len = text.length();
            if (len >= 1 && len <= MAX_CACHED_SPACES && text.charAt(0) == ' ') {
                LexerToken cached = SPACE_CACHE[len];
                if (cached.text.equals(text)) return cached;
            }
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

