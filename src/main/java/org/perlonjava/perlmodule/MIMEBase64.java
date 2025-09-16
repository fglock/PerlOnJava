package org.perlonjava.perlmodule;

import org.perlonjava.parser.StringParser;
import org.perlonjava.runtime.RuntimeArray;
import org.perlonjava.runtime.RuntimeList;
import org.perlonjava.runtime.RuntimeScalar;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class MIMEBase64 extends PerlModuleBase {

    public MIMEBase64() {
        super("MIME::Base64", false);
    }

    public static void initialize() {
        MIMEBase64 base64 = new MIMEBase64();
        base64.initializeExporter();
        base64.defineExport("EXPORT", "encode_base64", "decode_base64");
        try {
            base64.registerMethod("encode_base64", null);
            base64.registerMethod("decode_base64", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing MIMEBase64 method: " + e.getMessage());
        }
    }

    public static RuntimeList encode_base64(RuntimeArray args, int ctx) {
        if (args.size() > 2) {
            throw new IllegalStateException("Bad number of arguments for encode_base64");
        }
        RuntimeScalar str = args.get(0);
        RuntimeScalar eol = args.size() > 1 ? args.get(1) : new RuntimeScalar("\n");

        String input = str.toString();

        if (input.isEmpty()) {
            return new RuntimeScalar("").getList();
        }

        // Check for wide characters
        StringParser.assertNoWideCharacters(input, "subroutine entry");

        byte[] inputBytes = input.getBytes(StandardCharsets.ISO_8859_1);
        String eolStr = eol.toString();

        String encoded;
        if (eolStr.isEmpty()) {
            // No line breaking when eol is empty
            encoded = Base64.getEncoder().encodeToString(inputBytes);
        } else {
            // For standard line separators, use MIME encoder
            if (eolStr.equals("\n") || eolStr.equals("\r\n") || eolStr.equals("\r")) {
                encoded = Base64.getMimeEncoder(76, eolStr.getBytes()).encodeToString(inputBytes);
                // MIME encoder doesn't add trailing line separator if content fits in one line
                if (!encoded.endsWith(eolStr)) {
                    encoded += eolStr;
                }
            } else {
                // For custom separators, manually break lines
                String base64 = Base64.getEncoder().encodeToString(inputBytes);
                StringBuilder result = new StringBuilder();

                // Break into 76-character lines
                for (int i = 0; i < base64.length(); i += 76) {
                    if (i > 0) {
                        result.append(eolStr);
                    }
                    result.append(base64, i, Math.min(i + 76, base64.length()));
                }

                // Always append the line ending at the end
                result.append(eolStr);
                encoded = result.toString();
            }
        }

        return new RuntimeScalar(encoded).getList();
    }

    public static RuntimeList decode_base64(RuntimeArray args, int ctx) {
        if (args.size() > 1) {
            throw new IllegalStateException("Bad number of arguments for decode_base64");
        }
        RuntimeScalar input = args.get(0);
        String encoded = input.toString();

        // First, keep only valid base64 characters (including =)
        encoded = encoded.replaceAll("[^A-Za-z0-9+/=]", "");

        // Handle padding - find first = and truncate everything after it
        int paddingIndex = encoded.indexOf('=');
        if (paddingIndex >= 0) {
            // Truncate at the first padding character
            encoded = encoded.substring(0, paddingIndex);

            // Add back proper padding
            while (encoded.length() % 4 != 0) {
                encoded += "=";
            }
        }

        // Handle empty string case
        if (encoded.isEmpty()) {
            return new RuntimeScalar("").getList();
        }

        byte[] decodedBytes = Base64.getMimeDecoder().decode(encoded);
        String decoded = new String(decodedBytes, StandardCharsets.ISO_8859_1);
        return new RuntimeScalar(decoded).getList();
    }
}
