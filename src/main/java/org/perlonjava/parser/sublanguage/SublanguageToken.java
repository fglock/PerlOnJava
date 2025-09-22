package org.perlonjava.parser.sublanguage;

/**
 * Represents a token in a sublanguage parser.
 * 
 * This class encapsulates all the information about a parsed token including:
 * - The token type (from SublanguageTokenType enum)
 * - The actual text content
 * - Position information for error reporting
 * - Line and column numbers for debugging
 */
public class SublanguageToken {
    
    /** The type of this token */
    public final SublanguageTokenType type;
    
    /** The actual text content of this token */
    public final String text;
    
    /** The starting position of this token in the input string (0-based) */
    public final int position;
    
    /** The line number where this token appears (1-based) */
    public final int line;
    
    /** The column number where this token starts (1-based) */
    public final int column;
    
    /**
     * Creates a new sublanguage token.
     * 
     * @param type The token type
     * @param text The token text content
     * @param position The starting position in the input (0-based)
     * @param line The line number (1-based)
     * @param column The column number (1-based)
     */
    public SublanguageToken(SublanguageTokenType type, String text, int position, int line, int column) {
        this.type = type;
        this.text = text != null ? text : "";
        this.position = position;
        this.line = line;
        this.column = column;
    }
    
    /**
     * Creates a new sublanguage token with minimal information.
     * Position, line, and column will be set to 0.
     * 
     * @param type The token type
     * @param text The token text content
     */
    public SublanguageToken(SublanguageTokenType type, String text) {
        this(type, text, 0, 0, 0);
    }
    
    /**
     * Get the length of this token's text.
     * 
     * @return The length of the token text
     */
    public int getLength() {
        return text.length();
    }
    
    /**
     * Get the ending position of this token in the input string.
     * 
     * @return The ending position (exclusive)
     */
    public int getEndPosition() {
        return position + text.length();
    }
    
    /**
     * Check if this token represents an error or invalid input.
     * 
     * @return true if this is an invalid token, false otherwise
     */
    public boolean isError() {
        return type == SublanguageTokenType.INVALID;
    }
    
    /**
     * Check if this token represents the end of input.
     * 
     * @return true if this is an EOF token, false otherwise
     */
    public boolean isEOF() {
        return type == SublanguageTokenType.EOF;
    }
    
    /**
     * Check if this token is whitespace.
     * 
     * @return true if this is a whitespace token, false otherwise
     */
    public boolean isWhitespace() {
        return type == SublanguageTokenType.WHITESPACE;
    }
    
    /**
     * Check if this token is a literal character or text.
     * 
     * @return true if this is a literal token, false otherwise
     */
    public boolean isLiteral() {
        return type == SublanguageTokenType.LITERAL;
    }
    
    /**
     * Check if this token is an escape sequence.
     * 
     * @return true if this is any kind of escape token, false otherwise
     */
    public boolean isEscape() {
        return type == SublanguageTokenType.ESCAPE ||
               type == SublanguageTokenType.REGEX_ESCAPE ||
               type == SublanguageTokenType.TR_ESCAPE ||
               type == SublanguageTokenType.UNICODE_ESCAPE ||
               type == SublanguageTokenType.OCTAL_ESCAPE ||
               type == SublanguageTokenType.HEX_ESCAPE ||
               type == SublanguageTokenType.SIMPLE_ESCAPE ||
               type == SublanguageTokenType.CONTROL_CHAR;
    }
    
    /**
     * Check if this token is a numeric literal.
     * 
     * @return true if this is any kind of number token, false otherwise
     */
    public boolean isNumber() {
        return type == SublanguageTokenType.NUMBER_DECIMAL ||
               type == SublanguageTokenType.NUMBER_HEX ||
               type == SublanguageTokenType.NUMBER_OCTAL ||
               type == SublanguageTokenType.NUMBER_BINARY;
    }
    
    /**
     * Get a string representation of this token for debugging.
     * 
     * @return A string representation including type, text, and position
     */
    @Override
    public String toString() {
        return String.format("SublanguageToken{type=%s, text='%s', pos=%d, line=%d, col=%d}", 
                           type, text, position, line, column);
    }
    
    /**
     * Check equality with another token.
     * Two tokens are equal if they have the same type, text, and position.
     * 
     * @param obj The object to compare with
     * @return true if equal, false otherwise
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        
        SublanguageToken token = (SublanguageToken) obj;
        return position == token.position &&
               line == token.line &&
               column == token.column &&
               type == token.type &&
               text.equals(token.text);
    }
    
    /**
     * Generate hash code for this token.
     * 
     * @return Hash code based on type, text, and position
     */
    @Override
    public int hashCode() {
        int result = type.hashCode();
        result = 31 * result + text.hashCode();
        result = 31 * result + position;
        result = 31 * result + line;
        result = 31 * result + column;
        return result;
    }
}
