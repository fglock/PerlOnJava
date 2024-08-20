package org.perlonjava;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/*
 * Perl has a complex quoting mechanism for strings, which we can't fully implement in the Lexer.
 * This module reprocesses the tokens according to the current context to extract the quoted string.
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
     * @param index Starting index in the tokens list.
     * @param redo Flag to indicate if the parsing should be redone; example:  s/.../.../
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
                                    state = START;  // Restart FSM for another string
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

        if (remain.length() > 0) {
            tokPos--;
            tokens.get(tokPos).text = remain.toString();  // Put the remaining string back in the tokens list
        }

        return new ParsedString(index, tokPos, buffer.toString(), startDelim, endDelim);
    }

    /**
     * Class to represent the parsed string and its position in the tokens list.
     */
    public static class ParsedString {
        public final int index;  // Starting index of the parsed string
        public final int next;  // Next index in the tokens list
        public final String buffer;  // Parsed string
        public final char startDelim;
        public final char endDelim;

        public ParsedString(int index, int next, String buffer, char startDelim, char endDelim) {
            this.index = index;
            this.next = next;
            this.buffer = buffer;
            this.startDelim = startDelim;
            this.endDelim = endDelim;
        }
    }
}

