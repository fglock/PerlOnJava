package org.perlonjava.parser;

import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;

import java.util.List;

/**
 * The Whitespace class provides utility methods for handling and skipping whitespace
 * and comments in a list of lexer tokens. It is designed to navigate through tokens
 * while ignoring irrelevant whitespace and comments, and handling special cases like
 * POD sections and end-of-file markers.
 */
public class Whitespace {

    /**
     * Skips over whitespace, comments, and POD sections in the provided list of tokens,
     * starting from the specified index. It returns the index of the next non-whitespace
     * and non-comment token.
     *
     * @param parser     The parser object
     * @param tokenIndex The starting index in the list of tokens.
     * @param tokens     The list of LexerToken objects to process.
     * @return The index of the next non-whitespace and non-comment token.
     */
    public static int skipWhitespace(Parser parser, int tokenIndex, List<LexerToken> tokens) {
        while (tokenIndex < tokens.size()) {
            LexerToken token = tokens.get(tokenIndex);
            switch (token.type) {
                case WHITESPACE:
                    tokenIndex++;
                    break;

                case NEWLINE:
                    if (parser != null) {
                        // Process heredocs before advancing past the NEWLINE
                        // processHeredocs(heredocNodes, tokens, tokenIndex);
                    }

                    tokenIndex++;
                    if (tokenIndex < tokens.size() && tokens.get(tokenIndex).text.equals("=")) {
                        // Check for pod section after '='
                        if (tokenIndex + 1 < tokens.size() && tokens.get(tokenIndex + 1).type == LexerTokenType.IDENTIFIER) {
                            boolean inPod = true;

                            // Skip through pod section until 'cut' or 'end' is found
                            while (tokenIndex < tokens.size() && inPod) {
                                String podEqual = tokens.get(tokenIndex).text;
                                String podToken = tokens.get(tokenIndex + 1).text;
                                if (podEqual.equals("=")
                                        && (podToken.equals("cut") || podToken.equals("end"))) {
                                    inPod = false; // End of pod
                                }

                                // Skip to the next newline
                                while (tokenIndex < tokens.size() && tokens.get(tokenIndex).type != LexerTokenType.NEWLINE) {
                                    tokenIndex++;
                                }
                                tokenIndex++; // Consume newline
                            }
                        }
                    }
                    break;

                case OPERATOR:
                    if (token.text.equals("#")) {
                        // Skip optional whitespace after '#'
                        tokenIndex++;
                        while (tokenIndex < tokens.size() && tokens.get(tokenIndex).type == LexerTokenType.WHITESPACE) {
                            tokenIndex++;
                        }
                        // Check if it's a "# line" directive
                        if (tokenIndex < tokens.size() && tokens.get(tokenIndex).text.equals("line")) {
                            tokenIndex = parseLineDirective(tokenIndex, tokens);
                        }
                        // Skip comment until end of line
                        while (tokenIndex < tokens.size() && tokens.get(tokenIndex).type != LexerTokenType.NEWLINE) {
                            tokenIndex++;
                        }
                    } else {
                        return tokenIndex; // Stop processing and return current index
                    }
                    break;

                case STRING:
                    if (token.text.equals(String.valueOf((char) 4)) || token.text.equals(String.valueOf((char) 26))) {
                        // Handle ^D (EOT, ASCII 4) or ^Z (SUB, ASCII 26)
                        tokenIndex = tokens.size();
                    }
                    return tokenIndex; // Stop processing and return current index

                case IDENTIFIER:
                    if (token.text.equals("__END__") || token.text.equals("__DATA__")) {
                        tokenIndex = tokens.size();
                    }
                    return tokenIndex; // Stop processing and return current index

                default:
                    return tokenIndex; // Stop processing when a non-whitespace/non-comment token is found
            }
        }
        return tokenIndex;
    }

    private static int parseLineDirective(int tokenIndex, List<LexerToken> tokens) {
        tokenIndex++; // Skip 'line'
        // Skip optional whitespace after 'line'
        while (tokenIndex < tokens.size() && tokens.get(tokenIndex).type == LexerTokenType.WHITESPACE) {
            tokenIndex++;
        }
        // Parse the line number
        if (tokenIndex < tokens.size() && tokens.get(tokenIndex).type == LexerTokenType.NUMBER) {
            String lineNumberStr = tokens.get(tokenIndex).text;
            try {
                int lineNumber = Integer.parseInt(lineNumberStr);
                tokenIndex++;
                // Skip optional whitespace before filename
                while (tokenIndex < tokens.size() && tokens.get(tokenIndex).type == LexerTokenType.WHITESPACE) {
                    tokenIndex++;
                }
                // Parse the filename enclosed in quotes
                if (tokenIndex < tokens.size() && tokens.get(tokenIndex).type == LexerTokenType.OPERATOR && tokens.get(tokenIndex).text.equals("\"")) {
                    tokenIndex++; // Skip opening quote
                    StringBuilder filenameBuilder = new StringBuilder();
                    while (tokenIndex < tokens.size() && !(tokens.get(tokenIndex).type == LexerTokenType.OPERATOR && tokens.get(tokenIndex).text.equals("\""))) {
                        filenameBuilder.append(tokens.get(tokenIndex).text);
                        tokenIndex++;
                    }
                    if (tokenIndex < tokens.size() && tokens.get(tokenIndex).type == LexerTokenType.OPERATOR && tokens.get(tokenIndex).text.equals("\"")) {
                        tokenIndex++; // Skip closing quote
                        String filename = filenameBuilder.toString();
                        // Handle the line number and filename as needed
                        // For example, update a state or context object
                    }
                }
            } catch (NumberFormatException e) {
                // Handle the error if the line number is not valid
            }
        }
        return tokenIndex;
    }
}
