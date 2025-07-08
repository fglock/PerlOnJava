package org.perlonjava.io;

/**
 * Simplified layer that handles CRLF to LF conversion.
 */
public class CrlfLayer implements IOLayer {
    private boolean lastWasCR = false;

    @Override
    public String processInput(String input) {
        // Convert CRLF to LF on input
        StringBuilder result = new StringBuilder(input.length());

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (lastWasCR && c == '\n') {
                // Skip LF in CRLF sequence (CR was already converted to LF)
                lastWasCR = false;
            } else if (c == '\r') {
                result.append('\n');
                lastWasCR = true;
            } else {
                result.append(c);
                lastWasCR = false;
            }
        }

        return result.toString();
    }

    @Override
    public String processOutput(String output) {
        // Convert LF to CRLF on output
        return output.replace("\n", "\r\n");
    }

    @Override
    public void reset() {
        lastWasCR = false;
    }
}
