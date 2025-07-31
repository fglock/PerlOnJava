package org.perlonjava.parser;

import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.RuntimeIO;
import org.perlonjava.runtime.RuntimeScalar;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

public class DataSection {

    /**
     * Map of package names to their DATA handles
     */
    private static final Map<String, RuntimeScalar> dataHandles = new HashMap<>();

    /**
     * Set of parser instances that have already processed their DATA section
     */
    private static final Set<Parser> processedParsers = new HashSet<>();

    /**
     * Creates or updates a DATA filehandle for a package.
     *
     * @param parser the parser instance
     * @param content the content after __DATA__ or __END__
     */
    public static void createDataHandle(Parser parser, String content) {
        String packageName = parser.ctx.symbolTable.getCurrentPackage();

        // Create a scalar to hold the content
        RuntimeScalar contentScalar = new RuntimeScalar(content);

        parser.ctx.logDebug("Creating DATA handle for package: " + packageName + " with content: " + content);

        // Create a read-only file handle backed by the scalar
        dataHandles.put(packageName, new RuntimeScalar(RuntimeIO.open(contentScalar, "<")));
    }

    /**
     * Gets the DATA filehandle for a package.
     *
     * @param packageName the package name
     * @return the RuntimeIO handle, or undef if no DATA section exists
     */
    public static RuntimeScalar getDataHandle(String packageName) {
        RuntimeScalar dataHandle = dataHandles.get(packageName);
        return dataHandle != null ? dataHandle : new RuntimeScalar();
    }

    /**
     * Checks if a token represents an end-of-file marker.
     * This includes EOF tokens and special characters like ^D (EOT) and ^Z (SUB).
     *
     * @param token the token to check
     * @return true if the token is an end marker, false otherwise
     */
    private static boolean isEndMarker(LexerToken token) {
        if (token.type == LexerTokenType.EOF) {
            return true;
        }
        if (token.type == LexerTokenType.STRING) {
            return token.text.equals(String.valueOf((char) 4)) ||  // ^D (EOT)
                    token.text.equals(String.valueOf((char) 26));    // ^Z (SUB)
        }
        return false;
    }

    static int parseDataSection(Parser parser, int tokenIndex, List<LexerToken> tokens, LexerToken token) {
        // Check if this parser instance has already processed its DATA section
        if (processedParsers.contains(parser)) {
            return tokens.size();
        }

        if (token.text.equals("__DATA__")) {
            processedParsers.add(parser);

            // __DATA__ works in all scripts - just capture content after it
            tokenIndex++;

            // Skip any whitespace immediately after __DATA__
            while (tokenIndex < tokens.size() && tokens.get(tokenIndex).type == LexerTokenType.WHITESPACE) {
                tokenIndex++;
            }

            // Skip the newline after __DATA__
            if (tokenIndex < tokens.size() && tokens.get(tokenIndex).type == LexerTokenType.NEWLINE) {
                tokenIndex++;
            }

            // Capture all remaining content until end marker
            StringBuilder dataContent = new StringBuilder();
            while (tokenIndex < tokens.size()) {
                LexerToken currentToken = tokens.get(tokenIndex);

                // Stop if we hit an end marker
                if (isEndMarker(currentToken)) {
                    break;
                }

                dataContent.append(currentToken.text);
                tokenIndex++;
            }

            createDataHandle(parser, dataContent.toString());

            // Return tokens.size() to indicate we've consumed everything
            return tokens.size();

        } else if (token.text.equals("__END__")) {
            // __END__ - always scan for __DATA__, but only capture if top-level
            tokenIndex++;

            // Skip any whitespace immediately after __END__
            while (tokenIndex < tokens.size() && tokens.get(tokenIndex).type == LexerTokenType.WHITESPACE) {
                tokenIndex++;
            }

            // Skip the newline after __END__
            if (tokenIndex < tokens.size() && tokens.get(tokenIndex).type == LexerTokenType.NEWLINE) {
                tokenIndex++;
            }

            StringBuilder dataContent = new StringBuilder();
            boolean shouldCapture = parser.isTopLevelScript;

            // Scan for __DATA__ (always)
            while (tokenIndex < tokens.size()) {
                LexerToken currentToken = tokens.get(tokenIndex);

                // Stop if we hit an end marker
                if (isEndMarker(currentToken)) {
                    // If we're in top-level and haven't found __DATA__, save what we have
                    if (shouldCapture) {
                        processedParsers.add(parser);
                        createDataHandle(parser, dataContent.toString());
                    }
                    // Return tokens.size() to indicate we've consumed everything
                    return tokens.size();
                }

                // Check if we found __DATA__
                if (currentToken.type == LexerTokenType.IDENTIFIER &&
                        currentToken.text.equals("__DATA__")) {
                    processedParsers.add(parser);

                    // Found __DATA__, process it
                    tokenIndex++;

                    // Skip whitespace after __DATA__
                    while (tokenIndex < tokens.size() && tokens.get(tokenIndex).type == LexerTokenType.WHITESPACE) {
                        tokenIndex++;
                    }

                    // Skip newline after __DATA__
                    if (tokenIndex < tokens.size() && tokens.get(tokenIndex).type == LexerTokenType.NEWLINE) {
                        tokenIndex++;
                    }

                    // Capture content after __DATA__ until end marker
                    dataContent.setLength(0);
                    while (tokenIndex < tokens.size()) {
                        LexerToken dataToken = tokens.get(tokenIndex);

                        // Stop if we hit an end marker
                        if (isEndMarker(dataToken)) {
                            break;
                        }

                        dataContent.append(dataToken.text);
                        tokenIndex++;
                    }

                    createDataHandle(parser, dataContent.toString());
                    // Return tokens.size() to indicate we've consumed everything
                    return tokens.size();
                }

                // If we're in top-level, capture content
                if (shouldCapture) {
                    dataContent.append(currentToken.text);
                }
                tokenIndex++;
            }

            // Only set content if we were in top-level and didn't find __DATA__
            if (shouldCapture) {
                processedParsers.add(parser);
                createDataHandle(parser, dataContent.toString());
            }
        }
        // Return tokens.size() to indicate we've consumed everything
        return tokens.size();
    }
}
