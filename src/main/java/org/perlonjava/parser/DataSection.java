package org.perlonjava.parser;

import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.GlobalVariable;
import org.perlonjava.runtime.RuntimeIO;
import org.perlonjava.runtime.RuntimeScalar;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DataSection {

    /**
     * Set of parser instances that have already processed their DATA section
     */
    private static final Set<Parser> processedParsers = new HashSet<>();

    /**
     * Creates or updates a DATA filehandle for a package.
     *
     * @param parser  the parser instance
     * @param content the content after __DATA__ or __END__
     */
    public static void createDataHandle(Parser parser, String content) {
        String handleName = parser.ctx.symbolTable.getCurrentPackage() + "::DATA";

        // Create a scalar to hold the content
        RuntimeScalar contentScalar = new RuntimeScalar(content);

        parser.ctx.logDebug("Creating DATA handle for package: " + handleName + " with content: " + content);

        // Create a read-only file handle backed by the scalar
        var fileHandle = RuntimeIO.open(contentScalar.createReference(), "<");
        GlobalVariable.getGlobalIO(handleName).setIO(fileHandle);
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

        if (token.text.equals("__DATA__") || (token.text.equals("__END__") && parser.isTopLevelScript)) {
            processedParsers.add(parser);
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
        }
        // Return tokens.size() to indicate we've consumed everything
        return tokens.size();
    }
}
