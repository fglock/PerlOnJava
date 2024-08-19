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

    public static ParsedString parseRawStringWithDelimiter(List<Token> tokens, int index, boolean redo) {
        int tokPos = index;
        char startDelim = 0;
        char endDelim = 0;
        int state = START;
        int parenLevel = 0;
        boolean isPair = false;
        StringBuilder buffer = new StringBuilder();
        StringBuilder remain = new StringBuilder();
        List<String> buffers = new ArrayList<>();

        while (state != END_TOKEN) {
            if (tokens.get(tokPos).getType() == END_TOKEN) {
                throw new IllegalStateException("Can't find string terminator " + endDelim + " anywhere before EOF");
            }

            for (char ch : tokens.get(tokPos).getText().toCharArray()) {
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
                                    buffers.add(buffer.toString());
                                    buffer = new StringBuilder();
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
        buffers.add(buffer.toString());

        if (remain.length() > 0) {
            tokens.get(tokPos - 1).setText(remain.toString());  // put the remaining string back in the tokens list
        }

        return new ParsedString("RAW_STRING", index, tokPos, buffers, startDelim, endDelim);
    }

    public static class ParsedString {
        private final String type;
        private final int index;
        private final int next;
        private final List<String> buffers;
        private final char startDelim;
        private final char endDelim;

        public ParsedString(String type, int index, int next, List<String> buffers, char startDelim, char endDelim) {
            this.type = type;
            this.index = index;
            this.next = next;
            this.buffers = buffers;
            this.startDelim = startDelim;
            this.endDelim = endDelim;
        }

        // Getters here...
    }

    public static class Token {
        private final int type;
        private String text;

        public Token(int type, String text) {
            this.type = type;
            this.text = text;
        }

        public int getType() {
            return type;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }
    }
}

