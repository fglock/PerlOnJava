package org.perlonjava.perlmodule;

import org.perlonjava.runtime.*;
import java.nio.charset.StandardCharsets;

public class MIMEQuotedPrint extends PerlModuleBase {

    public MIMEQuotedPrint() {
        super("MIME::QuotedPrint", false);
    }

    public static void initialize() {
        MIMEQuotedPrint qp = new MIMEQuotedPrint();
        qp.initializeExporter();
        qp.defineExport("EXPORT", "encode_qp", "decode_qp");
        try {
            qp.registerMethod("encode_qp", null);
            qp.registerMethod("decode_qp", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing MIMEQuotedPrint method: " + e.getMessage());
        }
    }

    public static RuntimeList encode_qp(RuntimeArray args, int ctx) {
        if (args.size() < 1 || args.size() > 3) {
            throw new IllegalStateException("Bad number of arguments for encode_qp");
        }
        RuntimeScalar str = args.get(0);
        RuntimeScalar eol = args.size() > 1 ? args.get(1) : new RuntimeScalar("\n");
        boolean binaryMode = args.size() > 2 ? args.get(2).getBoolean() : false;

        // Empty EOL implies binary mode
        if (eol.toString().isEmpty()) {
            binaryMode = true;
        }

        String encoded = encodeQuotedPrintable(str.toString(), eol.toString(), binaryMode);
        return new RuntimeScalar(encoded).getList();
    }

    public static RuntimeList decode_qp(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for decode_qp");
        }
        RuntimeScalar input = args.get(0);
        String decoded = decodeQuotedPrintable(input.toString());
        return new RuntimeScalar(decoded).getList();
    }

    private static String encodeQuotedPrintable(String input, String eol, boolean binaryMode) {
        StringBuilder output = new StringBuilder();
        StringBuilder currentLine = new StringBuilder();
        int currentLength = 0;
        byte[] bytes = input.getBytes(StandardCharsets.ISO_8859_1);

        for (int i = 0; i < bytes.length; i++) {
            byte b = bytes[i];
            int unsigned = b & 0xFF;
            String token;

            // Check if this is a newline
            boolean isNewline = (unsigned == 10);  // \n
            boolean isCarriageReturn = (unsigned == 13);  // \r

            // In binary mode, encode all control characters including newlines
            // In non-binary mode, preserve newlines as-is
            if (!binaryMode && isNewline) {
                // Encode trailing space or tab before the newline
                int lineLen = currentLine.length();
                if (lineLen > 0) {
                    char lastChar = currentLine.charAt(lineLen - 1);
                    if (lastChar == ' ' || lastChar == '\t') {
                        // Replace the last character with its encoded form
                        currentLine.setLength(lineLen - 1);
                        currentLine.append(lastChar == ' ' ? "=20" : "=09");
                    }
                }
                // Output the line with the actual newline
                output.append(currentLine).append("\n");
                currentLine = new StringBuilder();
                currentLength = 0;
                continue;
            } else if (!binaryMode && isCarriageReturn && i + 1 < bytes.length && bytes[i + 1] == 10) {
                // Handle CRLF in non-binary mode - skip the CR, process LF next
                continue;
            }

            // Determine if character needs encoding
            boolean needsEncoding = false;

            // Always encode: non-printable characters, '=', and characters > 126
            if (unsigned < 33 || unsigned > 126 || unsigned == 61) {  // 61 is '='
                // Exception: space (32) and tab (9) are only encoded at end of line
                if (unsigned == 32 || unsigned == 9) {
                    // Only encode if at end of line or end of input
                    if (i == bytes.length - 1 || (i + 1 < bytes.length && bytes[i + 1] == 10)) {
                        needsEncoding = true;
                    } else {
                        needsEncoding = false;
                    }
                } else {
                    needsEncoding = true;
                }
            }

            if (needsEncoding) {
                token = "=" + String.format("%02X", unsigned);
            } else {
                token = String.valueOf((char) unsigned);
            }

            // Check if adding this token would make the line too long
            // We need to leave room for the '=' of a soft break, so max is 75
            if (currentLength + token.length() > 75) {
                // Need to break the line
                output.append(currentLine).append("=").append(eol);
                currentLine = new StringBuilder(token);
                currentLength = token.length();
            } else {
                currentLine.append(token);
                currentLength += token.length();
            }
        }

        // Add remaining content
        if (currentLine.length() > 0) {
            // Check for trailing space or tab at end of entire input
            int lineLen = currentLine.length();
            if (lineLen > 0) {
                char lastChar = currentLine.charAt(lineLen - 1);
                if (lastChar == ' ' || lastChar == '\t') {
                    // Replace the last character with its encoded form
                    currentLine.setLength(lineLen - 1);
                    currentLine.append(lastChar == ' ' ? "=20" : "=09");
                }
            }
            output.append(currentLine);
        }

        // Add soft line break at end unless EOL is empty
        if (!eol.isEmpty() && output.length() > 0) {
            output.append("=").append(eol);
        }

        return output.toString();
    }

    private static String decodeQuotedPrintable(String input) {
        // First remove soft line breaks (= followed by line ending)
        String withoutSoftBreaks = input.replaceAll("=[ \t]*\r?\n", "");

        // Convert CRLF to LF
        withoutSoftBreaks = withoutSoftBreaks.replaceAll("\r\n", "\n");

        StringBuilder output = new StringBuilder();
        int i = 0;

        while (i < withoutSoftBreaks.length()) {
            char c = withoutSoftBreaks.charAt(i);
            if (c == '=' && i + 2 < withoutSoftBreaks.length()) {
                String hex = withoutSoftBreaks.substring(i + 1, i + 3);
                try {
                    // Accept both uppercase and lowercase hex
                    int value = Integer.parseInt(hex, 16);
                    output.append((char) value);
                    i += 3;
                } catch (NumberFormatException e) {
                    // Invalid hex sequence, keep the '='
                    output.append(c);
                    i++;
                }
            } else {
                output.append(c);
                i++;
            }
        }

        return output.toString();
    }
}
