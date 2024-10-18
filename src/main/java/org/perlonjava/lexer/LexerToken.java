package org.perlonjava.lexer;

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
    public LexerTokenType type;

    /**
     * The text of the token, represented as a string.
     * This field contains the actual string of characters that make up the token.
     */
    public String text;

    /**
     * Constructs a new LexerToken with the specified type and text.
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

