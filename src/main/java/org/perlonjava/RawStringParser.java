package org.perlonjava;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RawStringParser {

    private static final int START = 0;
    private static final int STRING = 1;
    private static final int ESCAPE = 2;
    private static final int END_TOKEN = 3;

    private static final Map<Character, Character> QUOTE_PAIR = new HashMap<>();

    static {
        QUOTE_PAIR.put('<', '>');
        QUOTE_PAIR.put('{', '}');
        QUOTE_PAIR.put('(', ')');
        QUOTE_PAIR.put('[', ']');
    }

    public static ParsedString parseRawStringWithDelimiter(List<LexerToken> tokens, int index, boolean redo) {
        int tokPos = index;
        char startDelim = 0;
        char endDelim = 0;
        int state = START;
        int parenLevel = 0;
        boolean isPair = false;
        StringBuilder buffer = new StringBuilder();
        StringBuilder remain = new StringBuilder();

        while (state != END_TOKEN) {
            if (tokens.get(tokPos).type == LexerTokenType.EOF) {
                throw new IllegalStateException("Can't find string terminator " + endDelim + " anywhere before EOF");
            }

            for (char ch : tokens.get(tokPos).text.toCharArray()) {
                switch (state) {
                    case START:
                        startDelim = ch;
                        endDelim = startDelim;
                        if (QUOTE_PAIR.containsKey(startDelim)) {  // q< ... >
                            isPair = true;
                            endDelim = QUOTE_PAIR.get(startDelim);
                        }
                        state = STRING;
                        break;

                    case STRING:
                        if (isPair && ch == startDelim) {
                            parenLevel++;    // <
                        } else if (ch == endDelim) {
                            if (parenLevel == 0) {
                                if (redo && !isPair) {
                                    redo = false;
                                    state = START;    // start again; one more string to fetch
                                    break;  // go back to FSM start
                                } else {
                                    state = END_TOKEN;  // no more strings to fetch
                                }
                                continue;  // skip the rest of the loop
                            }
                            parenLevel--;  // >
                        } else if (ch == '\\') {
                            state = ESCAPE;
                        }
                        buffer.append(ch);
                        break;

                    case ESCAPE:
                        buffer.append(ch);  // handle \start_delim \end_delim
                        state = STRING;
                        break;

                    case END_TOKEN:
                        remain.append(ch);
                        break;

                    default:
                        throw new IllegalStateException("Unexpected state: " + state);
                }
            }
            tokPos++;
        }

        if (remain.length() > 0) {
            tokPos--;
            tokens.get(tokPos).text = remain.toString();  // put the remaining string back in the tokens list
        }

        return new ParsedString(index, tokPos, buffer.toString());
    }

    public static class ParsedString {
        public final int index;
        public final int next;
        public final String buffer;

        public ParsedString(int index, int next, String buffer) {
            this.index = index;
            this.next = next;
            this.buffer = buffer;
        }
    }
}

