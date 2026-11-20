package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.*;

/** Java replacement for URI::Encode::XS's UTF-8 percent codec entry points. */
public final class URIEncodeXS extends PerlModuleBase {
    public static final String XS_VERSION = "0.11";

    public URIEncodeXS() { super("URI::Encode::XS", false); }

    public static void initialize() {
        URIEncodeXS module = new URIEncodeXS();
        module.initializeExporter();
        module.defineExport("EXPORT_OK", "uri_encode", "uri_encode_utf8", "uri_decode", "uri_decode_utf8");
        try {
            module.registerMethod("uri_encode", "encode", null);
            module.registerMethod("uri_encode_utf8", "encode", null);
            module.registerMethod("uri_decode", "decode", null);
            module.registerMethod("uri_decode_utf8", "decode", null);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Missing URI::Encode::XS method", e);
        }
    }

    public static RuntimeList encode(RuntimeArray args, int ctx) {
        RuntimeArray forwarded = new RuntimeArray();
        forwarded.push(args.isEmpty() ? new RuntimeScalar("") : args.get(0));
        forwarded.push(new RuntimeScalar(""));
        return URIFast.encode(forwarded, ctx);
    }

    public static RuntimeList decode(RuntimeArray args, int ctx) {
        RuntimeArray forwarded = new RuntimeArray();
        forwarded.push(args.isEmpty() ? new RuntimeScalar("") : args.get(0));
        return URIFast.decode(forwarded, ctx);
    }
}
