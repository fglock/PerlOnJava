package org.perlonjava.parser;

import org.perlonjava.ErrorMessageUtil;
import org.perlonjava.PerlCompilerException;
import org.perlonjava.astnode.*;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/*
 * StringParser is used to parse domain-specific languages within Perl, such as Regex and string interpolation.
 *
 * Perl has a complex quoting mechanism for strings, which cannot be fully implemented in the Lexer
 * due to insufficient context.
 * This module reprocesses the tokens according to the current context to extract the quoted string.
 * The string is then passed on to the respective domain-specific compilers.
 */
public class StringParser {

    // States for the finite state machine (FSM)
    private static final int START = 0;
    private static final int STRING = 1;
    private static final int ESCAPE = 2;
    private static final int END_TOKEN = 3;

    // Map to hold pairs of matching delimiters
    private static final Map<Character, Character> QUOTE_PAIR = new HashMap<>();

    static {
        QUOTE_PAIR.put('<', '>');
        QUOTE_PAIR.put('{', '}');
        QUOTE_PAIR.put('(', ')');
        QUOTE_PAIR.put('[', ']');
    }

    /**
     * Parses a raw string with delimiters from a list of tokens.
     *
     * @param tokens List of lexer tokens.
     * @param index  Starting index in the tokens list.
     * @param redo   Flag to indicate if the parsing should be redone; example:  s/.../.../
     * @return ParsedString object containing the parsed string and updated token index.
     */
    public static ParsedString parseRawStringWithDelimiter(List<LexerToken> tokens, int index, boolean redo) {
        int tokPos = index;  // Current position in the tokens list
        char startDelim = 0;  // Starting delimiter
        char endDelim = 0;  // Ending delimiter
        int state = START;  // Initial state of the FSM
        int parenLevel = 0;  // Parenthesis nesting level
        boolean isPair = false;  // Flag to indicate if the delimiters are a pair
        StringBuilder buffer = new StringBuilder();  // Buffer to hold the parsed string
        StringBuilder remain = new StringBuilder();  // Buffer to hold the remaining string
        ArrayList<String> buffers = new ArrayList<>();

        while (state != END_TOKEN) {
            if (tokens.get(tokPos).type == LexerTokenType.EOF) {
                throw new IllegalStateException("Can't find string terminator " + endDelim + " anywhere before EOF");
            }

            for (char ch : tokens.get(tokPos).text.toCharArray()) {
                switch (state) {
                    case START:
                        startDelim = ch;
                        endDelim = startDelim;
                        if (QUOTE_PAIR.containsKey(startDelim)) {  // Check if the delimiter is a pair
                            isPair = true;
                            endDelim = QUOTE_PAIR.get(startDelim);
                        }
                        state = STRING;  // Move to STRING state
                        break;

                    case STRING:
                        if (isPair && ch == startDelim) {
                            parenLevel++;  // Increase nesting level for starting delimiter
                        } else if (ch == endDelim) {
                            if (parenLevel == 0) {
                                if (redo && !isPair) {
                                    redo = false;
                                    state = STRING;  // Restart FSM for another string
                                    buffers.add(buffer.toString());
                                    buffer = new StringBuilder();
                                    break;  // Exit the loop to restart FSM
                                } else {
                                    state = END_TOKEN;  // End parsing
                                }
                                continue;  // Skip the rest of the loop
                            }
                            parenLevel--;  // Decrease nesting level for ending delimiter
                        } else if (ch == '\\') {
                            state = ESCAPE;  // Move to ESCAPE state
                        }
                        buffer.append(ch);  // Append character to buffer
                        break;

                    case ESCAPE:
                        buffer.append(ch);  // Append escaped character to buffer
                        state = STRING;  // Return to STRING state
                        break;

                    case END_TOKEN:
                        remain.append(ch);  // Append remaining characters to remain buffer
                        break;

                    default:
                        throw new IllegalStateException("Unexpected state: " + state);
                }
            }
            tokPos++;  // Move to the next token
        }
        buffers.add(buffer.toString());
        if (remain.length() > 0) {
            tokPos--;
            tokens.get(tokPos).text = remain.toString();  // Put the remaining string back in the tokens list
        }
        return new ParsedString(index, tokPos, buffers, startDelim, endDelim);
    }

    public static StringParser.ParsedString parseRawStrings(List<LexerToken> tokens, int tokenIndex, int stringCount) {
        if (stringCount <= 0) {
            throw new IllegalArgumentException("need string_count");
        }

        int pos = tokenIndex;
        boolean redo = (stringCount == 3);
        StringParser.ParsedString ast = parseRawStringWithDelimiter(tokens, pos, redo); // use redo flag to extract 2 strings
        if (stringCount == 1) {
            return ast;
        }
        pos = ast.next;

        if (stringCount == 3) { // fetch the second of 3 strings: s{aaa}{SECOND}ig
            char delim = ast.startDelim; // / or {
            if (QUOTE_PAIR.containsKey(delim)) {
                pos = Parser.skipWhitespace(pos, tokens);
                StringParser.ParsedString ast2 = parseRawStringWithDelimiter(tokens, pos, false);
                ast.buffers.add(ast2.buffers.get(0));
                ast.next = ast2.next;
                pos = ast.next;
            }
        }

        // fetch the last string: s/aaa/bbb/LAST
        String modifier = "";
        if (tokens.get(pos).type == LexerTokenType.IDENTIFIER) {
            modifier = tokens.get(pos).text;
            ast.next = pos + 1;
        }
        ArrayList<String> buffers = ast.buffers;
        if (buffers == null) {
            buffers = new ArrayList<>();
            ast.buffers = buffers;
        }
        buffers.add(modifier);
        return ast;
    }

    static Node parseDoubleQuotedString(String input, ErrorMessageUtil errorUtil, int tokenIndex) {
        StringBuilder str = new StringBuilder();  // Buffer to hold the parsed string
        List<Node> parts = new ArrayList<>();  // List to hold parts of the parsed string
        char[] chars = input.toCharArray();  // Convert the input string to a character array
        int length = chars.length;  // Length of the character array
        int index = 0;  // Current position in the character array

        // Loop through the character array until the end
        while (index < length) {
            char ch = chars[index];  // Get the current character
            if (ch == '\\') {
                index++;  // Move to the next character
                if (index < length) {
                    char nextChar = chars[index];  // Get the next character
                    index++;  // Move to the next character
                    switch (nextChar) {
                        case '\\':
                        case '"':
                            str.append(nextChar);  // Append the escaped character
                            break;
                        case 'n':
                            str.append('\n');  // Append newline
                            break;
                        case 't':
                            str.append('\t');  // Append tab
                            break;
                        case 'r':
                            str.append('\r');  // Append carriage return
                            break;
                        case 'f':
                            str.append('\f');  // Append form feed
                            break;
                        case 'b':
                            str.append('\b');  // Append backspace
                            break;
                        case 'x':
                            // Handle \x{...} for Unicode
                            StringBuilder unicodeSeq = new StringBuilder();
                            if (index < length && chars[index] == '{') {
                                index++;  // Move to the next character
                                while (index < length && chars[index] != '}') {
                                    unicodeSeq.append(chars[index]);
                                    index++;
                                }
                                if (index < length && chars[index] == '}') {
                                    str.append((char) Integer.parseInt(unicodeSeq.toString(), 16));
                                    index++;  // Move to the next character
                                } else {
                                    throw new PerlCompilerException(tokenIndex, "Expected '}' after \\x{", errorUtil);
                                }
                            } else {
                                throw new PerlCompilerException(tokenIndex, "Expected '{' after \\x", errorUtil);
                            }
                            break;
                        default:
                            str.append('\\').append(nextChar);  // Append the backslash and the next character
                            break;
                    }
                }
            } else if (ch == '$' || ch == '@') {
                boolean isArray = ch == '@';
                Node operand;
                if (str.length() > 0) {
                    parts.add(new StringNode(str.toString(), tokenIndex));  // Add the string so far to parts
                    str = new StringBuilder();  // Reset the buffer
                }
                index++;  // Move to the next character
                if (index < length && (chars[index] == '_' || chars[index] == '@' || Character.isDigit(chars[index]))) {
                    // Handle special variables like $@, $1, etc.
                    String specialVar = String.valueOf(chars[index]);
                    index++;  // Move past the special variable character
                    operand = new OperatorNode(String.valueOf(ch), new IdentifierNode(specialVar, tokenIndex), tokenIndex);
                } else if (index < length && Character.isJavaIdentifierStart(chars[index])) {
                    StringBuilder identifier = new StringBuilder();
                    while (index < length && Character.isJavaIdentifierPart(chars[index])) {
                        identifier.append(chars[index]);
                        index++;
                    }
                    operand = new OperatorNode(String.valueOf(ch), new IdentifierNode(identifier.toString(), tokenIndex), tokenIndex);
                } else if (index < length && chars[index] == '{') {
                    index++;  // Move to the next character
                    StringBuilder varName = new StringBuilder();
                    while (index < length && Character.isJavaIdentifierPart(chars[index])) {
                        varName.append(chars[index]);
                        index++;
                    }
                    if (index < length && chars[index] == '}') {
                        index++;  // Consume the closing '}'
                        operand = new OperatorNode(String.valueOf(ch), new IdentifierNode(varName.toString(), tokenIndex), tokenIndex);
                    } else {
                        throw new PerlCompilerException(tokenIndex, "Expected '}' after variable name", errorUtil);
                    }
                } else {
                    throw new PerlCompilerException(tokenIndex, "Invalid variable name after " + ch, errorUtil);
                }
                if (isArray) {
                    operand = new BinaryOperatorNode("join", new OperatorNode("$", new IdentifierNode("\"", tokenIndex), tokenIndex), operand, tokenIndex);
                }
                parts.add(operand);
            } else {
                str.append(ch);  // Append the current character
                index++;  // Move to the next character
            }
        }

        if (str.length() > 0) {
            parts.add(new StringNode(str.toString(), tokenIndex));  // Add the remaining string to parts
        }

        // Join the parts
        if (parts.isEmpty()) {
            return new StringNode("", tokenIndex);
        } else if (parts.size() == 1) {
            Node result = parts.get(0);
            if (result instanceof StringNode) {
                return parts.get(0);
            }
            // stringify using:  "" . $a
            return new BinaryOperatorNode(".", new StringNode("", tokenIndex), parts.get(0), tokenIndex);
        } else {
            Node result = parts.get(0);
            for (int i = 1; i < parts.size(); i++) {
                result = new BinaryOperatorNode(".", result, parts.get(i), tokenIndex);
            }
            return result;
        }
    }

    static Node parseSingleQuotedString(String input, char startDelim, char endDelim, int tokenIndex) {
        StringBuilder str = new StringBuilder();  // Buffer to hold the parsed string
        char[] chars = input.toCharArray();  // Convert the input string to a character array
        int length = chars.length;  // Length of the character array
        int index = 0;  // Current position in the character array

        // Loop through the character array until the end
        while (index < length) {
            char ch = chars[index];  // Get the current character
            if (ch == '\\') {
                index++;  // Move to the next character
                if (index < length) {
                    char nextChar = chars[index];  // Get the next character
                    if (nextChar == '\\' || nextChar == startDelim || nextChar == endDelim) {
                        str.append(nextChar);  // Append the escaped character
                    } else {
                        str.append('\\').append(nextChar);  // Append the backslash and the next character
                    }
                }
            } else {
                str.append(ch);  // Append the current character
            }
            index++;  // Move to the next character
        }

        // Return a new StringNode with the parsed string and the token index
        return new StringNode(str.toString(), tokenIndex);
    }

    /**
     * Class to represent the parsed string and its position in the tokens list.
     */
    public static class ParsedString {
        public int index;  // Starting index of the parsed string
        public int next;  // Next index in the tokens list
        public ArrayList<String> buffers;  // Parsed string
        public char startDelim;
        public char endDelim;

        public ParsedString(int index, int next, ArrayList<String> buffers, char startDelim, char endDelim) {
            this.index = index;
            this.next = next;
            this.buffers = buffers;
            this.startDelim = startDelim;
            this.endDelim = endDelim;
        }
    }
}

