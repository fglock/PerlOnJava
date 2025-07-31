package org.perlonjava.perlmodule;

import org.perlonjava.runtime.*;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

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
        byte[] inputBytes = str.toString().getBytes(StandardCharsets.ISO_8859_1);
        String encoded = Base64.getMimeEncoder(76, eol.toString().getBytes()).encodeToString(inputBytes);
        return new RuntimeScalar(encoded).getList();
    }

    public static RuntimeList decode_base64(RuntimeArray args, int ctx) {
        if (args.size() > 1) {
            throw new IllegalStateException("Bad number of arguments for decode_base64");
        }
        RuntimeScalar input = args.get(0);
        String encoded = input.toString().replaceAll("[^\\x00-\\x7F]", "");
        byte[] decodedBytes = Base64.getMimeDecoder().decode(encoded);
        String decoded = new String(decodedBytes, StandardCharsets.ISO_8859_1);
        return new RuntimeScalar(decoded).getList();
    }
}

