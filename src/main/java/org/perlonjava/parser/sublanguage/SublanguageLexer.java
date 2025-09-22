package org.perlonjava.parser.sublanguage;

import java.util.ArrayList;
import java.util.List;

/**
 * Base class for all sublanguage lexers in PerlOnJava.
 * 
 * This class provides common tokenization patterns and utilities that are shared
 * across different sublanguage parsers (regex, pack/unpack, sprintf, transliteration, etc.).
 * 
 * Key responsibilities:
 * - Position tracking for error reporting
 * - Common character classification methods
 * - Base tokenization framework
 * - Integration with PerlOnJava's error handling
 */
public abstract class SublanguageLexer {
    
    protected final String input;
    protected final int sourceOffset;
    protected int position;
    protected int line;
    protected int column;
    protected final List<SublanguageToken> tokens;
    
    /**
     * Creates a new sublanguage lexer for the given input.
     * 
     * @param input The input string to tokenize
     */
    public SublanguageLexer(String input) {
        this(input, 0);
    }
    
    /**
     * Creates a new sublanguage lexer for the given input with source offset.
     * 
     * @param input The input string to tokenize
     * @param sourceOffset The offset of this input in the original source code (for error reporting)
     */
    public SublanguageLexer(String input, int sourceOffset) {
        this.input = input != null ? input : "";
        this.sourceOffset = sourceOffset;
        this.position = 0;
        this.line = 1;
        this.column = 1;
        this.tokens = new ArrayList<>();
    }
    
    /**
     * Tokenize the input string into a list of sublanguage tokens.
     * This is the main entry point for tokenization.
     * 
     * @return List of tokens representing the parsed input
     */
    public abstract List<SublanguageToken> tokenize();
    
    /**
     * Check if we've reached the end of input.
     * 
     * @return true if at end of input, false otherwise
     */
    protected boolean isAtEnd() {
        return position >= input.length();
    }
    
    /**
     * Peek at the current character without consuming it.
     * 
     * @return current character, or '\0' if at end
     */
    protected char peek() {
        if (isAtEnd()) return '\0';
        return input.charAt(position);
    }
    
    /**
     * Peek ahead at a character at the given offset from current position.
     * 
     * @param offset Number of characters to look ahead
     * @return character at offset, or '\0' if beyond end
     */
    protected char peekAhead(int offset) {
        int pos = position + offset;
        if (pos >= input.length()) return '\0';
        return input.charAt(pos);
    }
    
    /**
     * Consume and return the current character, advancing position.
     * 
     * @return current character, or '\0' if at end
     */
    protected char advance() {
        if (isAtEnd()) return '\0';
        
        char c = input.charAt(position);
        position++;
        
        if (c == '\n') {
            line++;
            column = 1;
        } else {
            column++;
        }
        
        return c;
    }
    
    /**
     * Check if the current character matches the expected character.
     * If it matches, consume it and return true.
     * 
     * @param expected The character to match
     * @return true if matched and consumed, false otherwise
     */
    protected boolean match(char expected) {
        if (isAtEnd()) return false;
        if (input.charAt(position) != expected) return false;
        
        advance();
        return true;
    }
    
    /**
     * Create a token with the given type and text.
     * The token will include current position information for error reporting.
     * The position will be adjusted by sourceOffset to point to the correct location in the original source.
     * 
     * @param type The token type
     * @param text The token text
     * @return A new SublanguageToken
     */
    protected SublanguageToken createToken(SublanguageTokenType type, String text) {
        int tokenStart = position - text.length();
        return new SublanguageToken(type, text, sourceOffset + tokenStart, line, column - text.length());
    }
    
    /**
     * Create a token with the given type, using text from start position to current position.
     * The position will be adjusted by sourceOffset to point to the correct location in the original source.
     * 
     * @param type The token type
     * @param start The starting position of the token (relative to sublanguage input)
     * @return A new SublanguageToken
     */
    protected SublanguageToken createToken(SublanguageTokenType type, int start) {
        String text = input.substring(start, position);
        return new SublanguageToken(type, text, sourceOffset + start, line, column - text.length());
    }
    
    /**
     * Add a token to the token list.
     * 
     * @param token The token to add
     */
    protected void addToken(SublanguageToken token) {
        tokens.add(token);
    }
    
    /**
     * Common utility: Check if character is a digit (0-9).
     * 
     * @param c Character to check
     * @return true if digit, false otherwise
     */
    protected boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
    
    /**
     * Common utility: Check if character is a hex digit (0-9, a-f, A-F).
     * 
     * @param c Character to check
     * @return true if hex digit, false otherwise
     */
    protected boolean isHexDigit(char c) {
        return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
    
    /**
     * Common utility: Check if character is an octal digit (0-7).
     * 
     * @param c Character to check
     * @return true if octal digit, false otherwise
     */
    protected boolean isOctalDigit(char c) {
        return c >= '0' && c <= '7';
    }
    
    /**
     * Common utility: Check if character is alphabetic (a-z, A-Z).
     * 
     * @param c Character to check
     * @return true if alphabetic, false otherwise
     */
    protected boolean isAlpha(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }
    
    /**
     * Common utility: Check if character is alphanumeric (a-z, A-Z, 0-9).
     * 
     * @param c Character to check
     * @return true if alphanumeric, false otherwise
     */
    protected boolean isAlphaNumeric(char c) {
        return isAlpha(c) || isDigit(c);
    }
    
    /**
     * Common utility: Check if character is whitespace.
     * 
     * @param c Character to check
     * @return true if whitespace, false otherwise
     */
    protected boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n';
    }
    
    /**
     * Skip whitespace characters, advancing position.
     */
    protected void skipWhitespace() {
        while (!isAtEnd() && isWhitespace(peek())) {
            advance();
        }
    }
    
    /**
     * Get the current position in the input.
     * 
     * @return current position
     */
    public int getPosition() {
        return position;
    }
    
    /**
     * Get the current line number.
     * 
     * @return current line number (1-based)
     */
    public int getLine() {
        return line;
    }
    
    /**
     * Get the current column number.
     * 
     * @return current column number (1-based)
     */
    public int getColumn() {
        return column;
    }
    
    /**
     * Get the input string being tokenized.
     * 
     * @return input string
     */
    public String getInput() {
        return input;
    }
    
    /**
     * Get the source offset for this sublanguage fragment.
     * This is the position where this sublanguage starts in the original source code.
     * 
     * @return source offset for error reporting
     */
    public int getSourceOffset() {
        return sourceOffset;
    }
}
