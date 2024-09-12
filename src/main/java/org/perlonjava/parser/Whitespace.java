package org.perlonjava.parser;

import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;

import java.util.List;

public class Whitespace {
    
    public static int skipWhitespace(int tokenIndex, List<LexerToken> tokens) {
        while (tokenIndex < tokens.size()) {
            LexerToken token = tokens.get(tokenIndex);
            switch (token.type) {
                case WHITESPACE:
                    tokenIndex++;
                    break;

                case NEWLINE:
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
                        // Skip comment until end of line
                        while (tokenIndex < tokens.size() && tokens.get(tokenIndex).type != LexerTokenType.NEWLINE) {
                            tokenIndex++;
                        }
                    } else {
                        return tokenIndex; // Stop processing and return current index for non-comment operator
                    }
                    break;

                default:
                    return tokenIndex; // Stop processing when a non-whitespace/non-comment token is found
            }
        }
        return tokenIndex;
    }
}
