package org.perlonjava.perlmodule;

import org.perlonjava.parser.StringParser;
import org.perlonjava.runtime.RuntimeArray;
import org.perlonjava.runtime.RuntimeList;
import org.perlonjava.runtime.RuntimeScalar;

import java.nio.charset.StandardCharsets;
import org.perlonjava.util.Base64Util;

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
            encoded = Base64Util.encode(inputBytes);
        } else {
            // Use our custom MIME encoder with the specified line separator
            encoded = Base64Util.encodeMime(inputBytes, eolStr);
        }

        return new RuntimeScalar(encoded).getList();
    }

    public static RuntimeList decode_base64(RuntimeArray args, int ctx) {
        if (args.size() > 1) {
            throw new IllegalStateException("Bad number of arguments for decode_base64");
        }
        RuntimeScalar input = args.get(0);
        String encoded = input.toString();

        // Our custom decoder handles invalid characters and padding internally
        byte[] decodedBytes = Base64Util.decode(encoded);
        String decoded = new String(decodedBytes, StandardCharsets.ISO_8859_1);
        return new RuntimeScalar(decoded).getList();
    }
}
