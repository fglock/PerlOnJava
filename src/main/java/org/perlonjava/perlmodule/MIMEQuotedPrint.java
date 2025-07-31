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
        if (args.size() > 2) {
            throw new IllegalStateException("Bad number of arguments for encode_qp");
        }
        RuntimeScalar str = args.get(0);
        RuntimeScalar eol = args.size() > 1 ? args.get(1) : new RuntimeScalar("\n");
        String encoded = encodeQuotedPrintable(str.toString(), eol.toString());
        return new RuntimeScalar(encoded).getList();
    }

    public static RuntimeList decode_qp(RuntimeArray args, int ctx) {
        if (args.size() > 1) {
            throw new IllegalStateException("Bad number of arguments for decode_qp");
        }
        RuntimeScalar input = args.get(0);
        String decoded = decodeQuotedPrintable(input.toString());
        return new RuntimeScalar(decoded).getList();
    }

    private static String encodeQuotedPrintable(String input, String eol) {
        StringBuilder output = new StringBuilder();
        StringBuilder currentLine = new StringBuilder();
        int currentLength = 0;
        byte[] bytes = input.getBytes(StandardCharsets.ISO_8859_1);

        for (byte b : bytes) {
            int unsigned = b & 0xFF;
            String token;
            if ((unsigned >= 33 && unsigned <= 60) || (unsigned >= 62 && unsigned <= 126) || unsigned == 32 || unsigned == 9) {
                token = String.valueOf((char) b);
            } else {
                token = "=" + String.format("%02X", unsigned);
            }

            if (currentLength + token.length() > 76) {
                output.append(currentLine).append("=").append(eol);
                currentLine = new StringBuilder(token);
                currentLength = token.length();
            } else {
                currentLine.append(token);
                currentLength += token.length();
            }
        }

        output.append(currentLine);
        return output.toString();
    }

    private static String decodeQuotedPrintable(String input) {
        String withoutSoftBreaks = input.replaceAll("=\\r\\n|=\\n", "");
        StringBuilder output = new StringBuilder();
        int i = 0;

        while (i < withoutSoftBreaks.length()) {
            char c = withoutSoftBreaks.charAt(i);
            if (c == '=' && i + 2 < withoutSoftBreaks.length()) {
                String hex = withoutSoftBreaks.substring(i + 1, i + 3);
                try {
                    int value = Integer.parseInt(hex, 16);
                    output.append((char) value);
                    i += 3;
                } catch (NumberFormatException e) {
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

