package org.perlonjava.parser;

import org.perlonjava.io.ScalarBackedIO;
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
     * Set of parser instances that have already created placeholder DATA handles
     */
    private static final Set<Parser> placeholderCreated = new HashSet<>();

    /**
     * Creates a placeholder DATA filehandle for a package early in parsing.
     * This ensures the DATA filehandle exists during BEGIN block execution.
     *
     * @param parser the parser instance
     */
    public static void createPlaceholderDataHandle(Parser parser) {
        if (placeholderCreated.contains(parser)) {
            return; // Already created placeholder for this parser
        }

        placeholderCreated.add(parser);
        String handleName = parser.ctx.symbolTable.getCurrentPackage() + "::DATA";

        parser.ctx.logDebug("Creating placeholder DATA handle for package: " + handleName);

        // Create an empty placeholder file handle that will be populated later
        RuntimeScalar emptyContent = new RuntimeScalar("");
        var fileHandle = RuntimeIO.open(emptyContent.createReference(), "<");
        GlobalVariable.getGlobalIO(handleName).setIO(fileHandle);
    }

    /**
     * Creates or updates a DATA filehandle for a package.
     *
     * @param parser  the parser instance
     * @param content the content after __DATA__ or __END__
     */
    public static void createDataHandle(Parser parser, String content) {
        String handleName = parser.ctx.symbolTable.getCurrentPackage() + "::DATA";

        parser.ctx.logDebug("Populating DATA handle for package: " + handleName + " with content: " + content);

        // Get the existing RuntimeIO (which should be the placeholder we created earlier)
        RuntimeIO existingIO = GlobalVariable.getGlobalIO(handleName).getRuntimeIO();

        if (existingIO != null) {
            // Update the existing IO handle with new content instead of replacing it
            // This ensures that any aliased handles (like *ARGV = *DATA) continue to work
            RuntimeScalar contentScalar = new RuntimeScalar(content);
            ScalarBackedIO newScalarIO = new ScalarBackedIO(contentScalar);
            existingIO.ioHandle = newScalarIO;
            parser.ctx.logDebug("Updated existing DATA handle with new content");
        } else {
            // Fallback: create new handle if no placeholder exists
            RuntimeScalar contentScalar = new RuntimeScalar(content);
            var fileHandle = RuntimeIO.open(contentScalar.createReference(), "<");
            GlobalVariable.getGlobalIO(handleName).setIO(fileHandle);
            parser.ctx.logDebug("Created new DATA handle");
        }
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
