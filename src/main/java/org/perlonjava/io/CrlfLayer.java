package org.perlonjava.io;

/**
 * Implementation of Perl's :crlf IO layer for line ending conversions.
 *
 * <p>This layer provides automatic conversion between different line ending conventions,
 * similar to Perl's :crlf layer. It handles the transformation between Unix-style
 * line endings (LF) and Windows-style line endings (CRLF).</p>
 *
 * <p>In Perl, the :crlf layer is used like:</p>
 * <pre>
 * open(my $fh, '<:crlf', 'file.txt');    # Read with CRLF conversion
 * binmode($fh, ':crlf');                  # Add CRLF layer to existing handle
 * </pre>
 *
 * <p>On Windows platforms, Perl typically applies this layer by default to text-mode
 * filehandles.</p>
 *
 * <p>The layer performs the following conversions:</p>
 * <ul>
 *   <li><b>On input:</b> CRLF sequences (\r\n) are converted to LF (\n)</li>
 *   <li><b>On input:</b> Lone CR (\r) characters are also converted to LF (\n)</li>
 *   <li><b>On output:</b> LF characters (\n) are converted to CRLF (\r\n)</li>
 * </ul>
 *
 * <p>This implementation maintains state to correctly handle CRLF sequences that
 * may be split across multiple read operations, ensuring data integrity even
 * with fragmented input.</p>
 *
 * @see IOLayer
 */
public class CrlfLayer implements IOLayer {
    /**
     * Tracks whether the last character processed was a carriage return (CR).
     *
     * <p>This state is necessary to correctly handle CRLF sequences that may be
     * split across multiple read operations. When true, if the next character
     * is LF, it will be skipped as part of the CRLF sequence.</p>
     */
    private boolean lastWasCR = false;

    /**
     * Processes input by converting Windows-style line endings to Unix-style.
     *
     * <p>This method performs the following conversions:</p>
     * <ul>
     *   <li>CRLF (\r\n) sequences are converted to a single LF (\n)</li>
     *   <li>Lone CR (\r) characters are converted to LF (\n)</li>
     *   <li>LF characters that are not part of a CRLF sequence remain unchanged</li>
     * </ul>
     *
     * <p>The method correctly handles CRLF sequences that span multiple calls by
     * maintaining state between invocations. This ensures proper conversion even
     * when the CR and LF arrive in separate chunks.</p>
     *
     * @param input the input string to process
     * @return the processed string with normalized line endings
     */
    @Override
    public String processInput(String input) {
        // Convert CRLF to LF on input
        StringBuilder result = new StringBuilder(input.length());

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (lastWasCR && c == '\n') {
                // This LF is part of a CRLF sequence
                // Skip it since we already converted the CR to LF
                lastWasCR = false;
            } else if (c == '\r') {
                // Convert CR to LF
                // This handles both lone CR and the CR in CRLF
                result.append('\n');
                lastWasCR = true;
            } else {
                // Regular character - pass through unchanged
                result.append(c);
                lastWasCR = false;
            }
        }

        return result.toString();
    }

    /**
     * Processes output by converting Unix-style line endings to Windows-style.
     *
     * <p>This method converts all LF (\n) characters to CRLF (\r\n) sequences,
     * which is the standard line ending convention on Windows platforms.</p>
     *
     * <p>Note that this is a simple replacement operation that doesn't need to
     * maintain state, as we're always expanding LF to CRLF regardless of context.</p>
     *
     * @param output the output string to process
     * @return the processed string with CRLF line endings
     */
    @Override
    public String processOutput(String output) {
        // Convert LF to CRLF on output
        // This is a straightforward replacement - no state needed
        return output.replace("\n", "\r\n");
    }

    /**
     * Resets the CRLF layer to its initial state.
     *
     * <p>This method clears the internal state flag that tracks whether the last
     * character was a CR. This should be called when switching between files or
     * when explicitly resetting the IO stream to ensure clean processing of new data.</p>
     *
     * <p>In Perl, this would typically happen when closing and reopening a filehandle
     * or when changing the layers with binmode().</p>
     */
    @Override
    public void reset() {
        lastWasCR = false;
    }
}
