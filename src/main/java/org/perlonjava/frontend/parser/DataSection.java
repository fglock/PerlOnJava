package org.perlonjava.frontend.parser;

import org.perlonjava.app.cli.CompilerOptions;

import org.perlonjava.frontend.lexer.LexerToken;
import org.perlonjava.frontend.lexer.LexerTokenType;
import org.perlonjava.runtime.io.ScalarBackedIO;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.RuntimeIO;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.perlonjava.runtime.perlmodule.Strict.HINT_UTF8;

public class DataSection {

    /**
     * Set of package names that have already processed their DATA section
     */
    private static final Set<String> processedPackages = new HashSet<>();

    /**
     * Set of package names that have already created placeholder DATA handles
     */
    private static final Set<String> placeholderCreated = new HashSet<>();

    /**
     * Resets all static state for DataSection.
     * Called between test runs to prevent stale state from interfering.
     */
    public static void reset() {
        processedPackages.clear();
        placeholderCreated.clear();
    }

    /**
     * Creates a placeholder DATA filehandle for a package early in parsing.
     * This ensures the DATA filehandle exists during BEGIN block execution.
     *
     * @param parser the parser instance
     */
    public static void createPlaceholderDataHandle(Parser parser) {
        String handleName = parser.ctx.symbolTable.getCurrentPackage() + "::DATA";

        if (placeholderCreated.contains(handleName)) {
            return; // Already created placeholder for this package
        }

        placeholderCreated.add(handleName);

        if (CompilerOptions.DEBUG_ENABLED) parser.ctx.logDebug("Creating placeholder DATA handle for package: " + handleName);

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

        if (CompilerOptions.DEBUG_ENABLED) parser.ctx.logDebug("Populating DATA handle for package: " + handleName + " with content: " + content);

        // Get the existing RuntimeIO (which should be the placeholder we created earlier)
        RuntimeIO existingIO = GlobalVariable.getGlobalIO(handleName).getRuntimeIO();

        if (existingIO != null) {
            // Update the existing IO handle with new content instead of replacing it
            // This ensures that any aliased handles (like *ARGV = *DATA) continue to work
            RuntimeScalar contentScalar = new RuntimeScalar(content);
            ScalarBackedIO newScalarIO = new ScalarBackedIO(contentScalar);
            existingIO.ioHandle = newScalarIO;
            if (CompilerOptions.DEBUG_ENABLED) parser.ctx.logDebug("Updated existing DATA handle with new content");
        } else {
            // Fallback: create new handle if no placeholder exists
            RuntimeScalar contentScalar = new RuntimeScalar(content);
            var fileHandle = RuntimeIO.open(contentScalar.createReference(), "<");
            GlobalVariable.getGlobalIO(handleName).setIO(fileHandle);
            if (CompilerOptions.DEBUG_ENABLED) parser.ctx.logDebug("Created new DATA handle");
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

    /**
     * Extracts DATA section content from raw file bytes.
     * In Perl 5, &lt;DATA&gt; reads raw bytes from the file by default. When
     * {@code use utf8} is active, a {@code :utf8} IO layer is applied to the
     * DATA handle (handled by the caller), matching Perl 5 behavior.
     *
     * @param rawBytes    the raw file bytes (after BOM removal)
     * @param markerText  the marker to search for ("__DATA__" or "__END__")
     * @return the DATA content as a string (Latin-1 encoded), or null if marker not found
     */
    private static String extractDataFromRawBytes(byte[] rawBytes, String markerText) {
        byte[] marker = markerText.getBytes(StandardCharsets.US_ASCII);
        int markerLen = marker.length;

        // Search for the marker at the start of a line in raw bytes
        for (int i = 0; i <= rawBytes.length - markerLen; i++) {
            // Check that we're at the start of a line (position 0 or after \n)
            if (i > 0 && rawBytes[i - 1] != '\n') {
                continue;
            }

            // Check if the marker matches at this position
            boolean match = true;
            for (int j = 0; j < markerLen; j++) {
                if (rawBytes[i + j] != marker[j]) {
                    match = false;
                    break;
                }
            }
            if (!match) continue;

            // Verify the marker is followed by whitespace/newline/EOF (not part of a longer identifier)
            int afterMarker = i + markerLen;
            if (afterMarker < rawBytes.length) {
                byte next = rawBytes[afterMarker];
                if (next != '\n' && next != '\r' && next != ' ' && next != '\t') {
                    continue; // Part of a longer identifier
                }
            }

            // Skip past the marker and any trailing whitespace + newline
            int dataStart = afterMarker;
            // Skip spaces/tabs
            while (dataStart < rawBytes.length && (rawBytes[dataStart] == ' ' || rawBytes[dataStart] == '\t')) {
                dataStart++;
            }
            // Skip the newline (\n or \r\n)
            if (dataStart < rawBytes.length && rawBytes[dataStart] == '\r') {
                dataStart++;
            }
            if (dataStart < rawBytes.length && rawBytes[dataStart] == '\n') {
                dataStart++;
            }

            // Always store as Latin-1 (each byte = one character) to preserve raw bytes.
            // The DATA handle's encoding layer (applied by parseDataSection) handles
            // UTF-8 decoding at read time when `use utf8` is active.
            return new String(rawBytes, dataStart, rawBytes.length - dataStart, StandardCharsets.ISO_8859_1);
        }

        return null; // Marker not found
    }

    static int parseDataSection(Parser parser, int tokenIndex, List<LexerToken> tokens, LexerToken token) {
        String handleName = parser.ctx.symbolTable.getCurrentPackage() + "::DATA";

        // Check if this package has already processed its DATA section.
        // However, allow re-processing if the DATA handle was closed (e.g., module
        // was re-required after delete $INC{...}). This is needed because modules
        // like ConfigData.pm close DATA after reading and expect a fresh handle on reload.
        if (processedPackages.contains(handleName)) {
            RuntimeIO existingIO = GlobalVariable.getGlobalIO(handleName).getRuntimeIO();
            if (existingIO != null && !(existingIO.ioHandle instanceof org.perlonjava.runtime.io.ClosedIOHandle)) {
                return tokens.size();
            }
            // Handle was closed — allow re-processing
            processedPackages.remove(handleName);
            placeholderCreated.remove(handleName);
        }

        if (token.text.equals("__DATA__") || token.text.equals("__END__")) {
            processedPackages.add(handleName);

            // __END__ should always stop parsing, but only top-level scripts (and __DATA__) should
            // populate the DATA handle content.
            boolean populateData = token.text.equals("__DATA__") || parser.isTopLevelScript;

            tokenIndex++;

            // Skip any whitespace immediately after __DATA__
            while (tokenIndex < tokens.size() && tokens.get(tokenIndex).type == LexerTokenType.WHITESPACE) {
                tokenIndex++;
            }

            // Skip the newline after __DATA__
            if (tokenIndex < tokens.size() && tokens.get(tokenIndex).type == LexerTokenType.NEWLINE) {
                tokenIndex++;
            }

            if (populateData) {
                // Try to extract DATA content from raw file bytes first.
                // This preserves non-UTF-8 bytes (e.g., Latin-1) that would be corrupted
                // by the UTF-8 decoding that happens when reading source files.
                // In Perl 5, <DATA> reads raw bytes from the file.
                byte[] rawBytes = parser.ctx.compilerOptions.rawCodeBytes;
                boolean useUtf8 = parser.ctx.symbolTable.isStrictOptionEnabled(HINT_UTF8);
                String rawContent = null;
                if (rawBytes != null) {
                    rawContent = extractDataFromRawBytes(rawBytes, token.text);
                }

                if (rawContent != null) {
                    createDataHandle(parser, rawContent);
                } else {
                    // Fallback: concatenate remaining tokens (for eval/string-based code
                    // where raw bytes are not available)
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

                // When `use utf8` is active, apply :utf8 layer to the DATA handle.
                // This matches Perl 5 behavior where the DATA handle inherits the
                // source encoding pragma, decoding UTF-8 bytes at read time.
                if (useUtf8) {
                    RuntimeIO dataIO = GlobalVariable.getGlobalIO(handleName).getRuntimeIO();
                    if (dataIO != null) {
                        dataIO.binmode(":utf8");
                    }
                }
            }
        }
        // Return tokens.size() to indicate we've consumed everything
        return tokens.size();
    }
}
