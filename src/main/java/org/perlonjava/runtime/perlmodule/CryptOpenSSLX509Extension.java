package org.perlonjava.runtime.perlmodule;

import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.perlonjava.runtime.operators.ReferenceOperators;
import org.perlonjava.runtime.runtimetypes.*;

/** Methods on Crypt::OpenSSL::X509::Extension objects. */
public class CryptOpenSSLX509Extension extends PerlModuleBase {
    private static final String CLASS_NAME = "Crypt::OpenSSL::X509::Extension";
    private static final String STATE_KEY = "_x509_extension";

    public CryptOpenSSLX509Extension() { super(CLASS_NAME, false); }

    public static void initialize() throws NoSuchMethodException {
        CryptOpenSSLX509Extension module = new CryptOpenSSLX509Extension();
        module.registerMethod("value", null);
        module.registerMethod("to_string", null);
        module.registerMethod("critical", null);
        module.registerMethod("extendedKeyUsage", null);
        module.registerMethod("DESTROY", "destroy", null);
    }

    static RuntimeScalar object(Extension extension) {
        RuntimeHash hash = new RuntimeHash();
        hash.put(STATE_KEY, new RuntimeScalar(extension));
        RuntimeScalar ref = hash.createAnonymousReference();
        ReferenceOperators.bless(ref, new RuntimeScalar(CLASS_NAME));
        return ref;
    }

    private static Extension extension(RuntimeScalar self) {
        RuntimeScalar state = self.hashDeref().get(STATE_KEY);
        if (state != null && state.value instanceof Extension extension) return extension;
        throw new IllegalArgumentException("Invalid X509 extension object");
    }

    public static RuntimeList value(RuntimeArray args, int ctx) {
        return new RuntimeScalar("#" + java.util.HexFormat.of().withUpperCase()
                .formatHex(extension(args.get(0)).getExtnValue().getOctets())).getList();
    }

    public static RuntimeList to_string(RuntimeArray args, int ctx) {
        Extension extension = extension(args.get(0));
        if (Extension.extendedKeyUsage.equals(extension.getExtnId())) {
            KeyPurposeId[] usages = ExtendedKeyUsage.getInstance(extension.getParsedValue()).getUsages();
            StringBuilder out = new StringBuilder();
            for (KeyPurposeId usage : usages) {
                if (!out.isEmpty()) out.append(", ");
                out.append(usage.getId());
            }
            return new RuntimeScalar(out.toString()).getList();
        }
        return value(args, ctx);
    }

    public static RuntimeList extendedKeyUsage(RuntimeArray args, int ctx) { return to_string(args, ctx); }
    public static RuntimeList critical(RuntimeArray args, int ctx) {
        return new RuntimeScalar(extension(args.get(0)).isCritical()).getList();
    }
    public static RuntimeList destroy(RuntimeArray args, int ctx) { return new RuntimeList(); }
}
