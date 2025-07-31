package org.perlonjava.parser;

import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.RuntimeIO;
import org.perlonjava.runtime.RuntimeScalar;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DataSection {

    /**
     * Map of package names to their DATA handles
     */
    private static final Map<String, RuntimeScalar> dataHandles = new HashMap<>();

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

    static int parseDataSection(Parser parser, int tokenIndex, List<LexerToken> tokens, LexerToken token) {
        if (token.text.equals("__DATA__")) {
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

            // Capture all remaining content
            StringBuilder dataContent = new StringBuilder();
            while (tokenIndex < tokens.size()) {
                dataContent.append(tokens.get(tokenIndex).text);
                tokenIndex++;
            }

            createDataHandle(parser, dataContent.toString());

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

                // Check if we found __DATA__
                if (currentToken.type == LexerTokenType.IDENTIFIER &&
                        currentToken.text.equals("__DATA__")) {
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

                    // Capture content after __DATA__
                    dataContent.setLength(0);
                    while (tokenIndex < tokens.size()) {
                        dataContent.append(tokens.get(tokenIndex).text);
                        tokenIndex++;
                    }

                    createDataHandle(parser, dataContent.toString());
                    return tokenIndex;
                }

                // If we're in top-level, capture content
                if (shouldCapture) {
                    dataContent.append(currentToken.text);
                }
                tokenIndex++;
            }

            // Only set content if we were in top-level and didn't find __DATA__
            if (shouldCapture) {
                createDataHandle(parser, dataContent.toString());
            }
        }
        return tokenIndex;
    }
}
