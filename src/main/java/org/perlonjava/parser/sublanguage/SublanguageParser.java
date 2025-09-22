package org.perlonjava.parser.sublanguage;

import org.perlonjava.runtime.PerlCompilerException;
import java.util.List;

/**
 * Base class for all sublanguage parsers in PerlOnJava.
 * 
 * This class provides common parsing patterns and utilities that are shared
 * across different sublanguage parsers (regex, pack/unpack, sprintf, transliteration, etc.).
 * 
 * Key responsibilities:
 * - Token stream navigation and consumption
 * - Error reporting with accurate source positions
 * - Integration with PerlOnJava's error handling (PerlCompilerException)
 * - Common parsing patterns and utilities
 * - AST generation framework
 */
public abstract class SublanguageParser {
    
    protected final List<SublanguageToken> tokens;
    protected final String originalInput;
    protected final int sourceOffset;
    protected int current;
    
    /**
     * Creates a new sublanguage parser for the given token stream.
     * 
     * @param tokens The list of tokens to parse
     * @param originalInput The original input string (for error reporting)
     */
    public SublanguageParser(List<SublanguageToken> tokens, String originalInput) {
        this(tokens, originalInput, 0);
    }
    
    /**
     * Creates a new sublanguage parser for the given token stream with source offset.
     * 
     * @param tokens The list of tokens to parse
     * @param originalInput The original input string (for error reporting)
     * @param sourceOffset The offset of this sublanguage in the original source code
     */
    public SublanguageParser(List<SublanguageToken> tokens, String originalInput, int sourceOffset) {
        this.tokens = tokens != null ? tokens : List.of();
        this.originalInput = originalInput != null ? originalInput : "";
        this.sourceOffset = sourceOffset;
        this.current = 0;
    }
    
    /**
     * Parse the token stream and return an AST.
     * This is the main entry point for parsing.
     * 
     * @return The parsed AST representing the sublanguage structure
     */
    public abstract SublanguageASTNode parse();
    
    /**
     * Check if we've reached the end of the token stream.
     * 
     * @return true if at end of tokens, false otherwise
     */
    protected boolean isAtEnd() {
        return current >= tokens.size() || peek().isEOF();
    }
    
    /**
     * Peek at the current token without consuming it.
     * 
     * @return current token, or EOF token if at end
     */
    protected SublanguageToken peek() {
        if (current >= tokens.size()) {
            return new SublanguageToken(SublanguageTokenType.EOF, "", sourceOffset, 1, 1);
        }
        return tokens.get(current);
    }
    
    /**
     * Peek at the previous token.
     * 
     * @return previous token, or EOF token if at beginning
     */
    protected SublanguageToken previous() {
        if (current <= 0) {
            return new SublanguageToken(SublanguageTokenType.EOF, "", sourceOffset, 1, 1);
        }
        return tokens.get(current - 1);
    }
    
    /**
     * Peek ahead at a token at the given offset from current position.
     * 
     * @param offset Number of tokens to look ahead
     * @return token at offset, or EOF token if beyond end
     */
    protected SublanguageToken peekAhead(int offset) {
        int pos = current + offset;
        if (pos >= tokens.size()) {
            return new SublanguageToken(SublanguageTokenType.EOF, "", sourceOffset, 1, 1);
        }
        return tokens.get(pos);
    }
    
    /**
     * Consume and return the current token, advancing position.
     * 
     * @return current token, or EOF token if at end
     */
    protected SublanguageToken advance() {
        if (!isAtEnd()) current++;
        return previous();
    }
    
    /**
     * Check if the current token matches the expected type.
     * 
     * @param type The expected token type
     * @return true if current token matches, false otherwise
     */
    protected boolean check(SublanguageTokenType type) {
        if (isAtEnd()) return false;
        return peek().type == type;
    }
    
    /**
     * Check if the current token matches any of the expected types.
     * 
     * @param types The expected token types
     * @return true if current token matches any type, false otherwise
     */
    protected boolean check(SublanguageTokenType... types) {
        for (SublanguageTokenType type : types) {
            if (check(type)) return true;
        }
        return false;
    }
    
    /**
     * If the current token matches the expected type, consume it and return true.
     * 
     * @param type The expected token type
     * @return true if matched and consumed, false otherwise
     */
    protected boolean match(SublanguageTokenType type) {
        if (check(type)) {
            advance();
            return true;
        }
        return false;
    }
    
    /**
     * If the current token matches any of the expected types, consume it and return true.
     * 
     * @param types The expected token types
     * @return true if any type matched and consumed, false otherwise
     */
    protected boolean match(SublanguageTokenType... types) {
        for (SublanguageTokenType type : types) {
            if (check(type)) {
                advance();
                return true;
            }
        }
        return false;
    }
    
    /**
     * Consume the current token if it matches the expected type, or throw an error.
     * 
     * @param type The expected token type
     * @param message The error message if token doesn't match
     * @return The consumed token
     * @throws PerlCompilerException if token doesn't match
     */
    protected SublanguageToken consume(SublanguageTokenType type, String message) {
        if (check(type)) return advance();
        
        SublanguageToken current = peek();
        throw createError(message, current);
    }
    
    /**
     * Skip whitespace tokens, advancing position.
     */
    protected void skipWhitespace() {
        while (match(SublanguageTokenType.WHITESPACE)) {
            // Continue advancing through whitespace
        }
    }
    
    /**
     * Create a PerlCompilerException with proper error positioning.
     * 
     * @param message The error message
     * @param token The token where the error occurred
     * @return A new PerlCompilerException with position information
     */
    protected PerlCompilerException createError(String message, SublanguageToken token) {
        String fullMessage = String.format("%s at position %d", message, token.position);
        if (token.line > 0 && token.column > 0) {
            fullMessage = String.format("%s (line %d, column %d)", fullMessage, token.line, token.column);
        }
        return new PerlCompilerException(fullMessage);
    }
    
    /**
     * Create a PerlCompilerException with the current token position.
     * 
     * @param message The error message
     * @return A new PerlCompilerException with current position information
     */
    protected PerlCompilerException createError(String message) {
        return createError(message, peek());
    }
    
    /**
     * Get the current position in the token stream.
     * 
     * @return current position (0-based)
     */
    public int getCurrentPosition() {
        return current;
    }
    
    /**
     * Get the total number of tokens.
     * 
     * @return total token count
     */
    public int getTokenCount() {
        return tokens.size();
    }
    
    /**
     * Get the original input string.
     * 
     * @return original input string
     */
    public String getOriginalInput() {
        return originalInput;
    }
    
    /**
     * Get the source offset for this sublanguage fragment.
     * 
     * @return source offset for error reporting
     */
    public int getSourceOffset() {
        return sourceOffset;
    }
    
    /**
     * Get all tokens in the stream.
     * 
     * @return list of all tokens
     */
    public List<SublanguageToken> getTokens() {
        return tokens;
    }
}
