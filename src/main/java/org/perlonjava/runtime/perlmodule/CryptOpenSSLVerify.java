package org.perlonjava.runtime.perlmodule;

import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.openssl.PEMParser;
import org.perlonjava.runtime.operators.ReferenceOperators;
import org.perlonjava.runtime.runtimetypes.*;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/** Crypt::OpenSSL::Verify implemented with the existing Bouncy Castle/JCA stack. */
public class CryptOpenSSLVerify extends PerlModuleBase {
    private static final String CLASS_NAME = "Crypt::OpenSSL::Verify";
    private static final String STATE_KEY = "_verify_certificates";

    public CryptOpenSSLVerify() { super(CLASS_NAME, false); }

    public static void initialize() {
        CryptOpenSSLVerify module = new CryptOpenSSLVerify();
        try {
            module.registerMethod("new", "new_", null);
            module.registerMethod("verify", null);
            module.registerMethod("register_verify_cb", "noop", null);
            module.registerMethod("ctx_error_code", "zero", null);
            module.registerMethod("__X509_cleanup", "noop", null);
            module.registerMethod("DESTROY", "noop", null);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    public static RuntimeList new_(RuntimeArray args, int ctx) {
        try {
            List<X509Certificate> certificates = new ArrayList<>();
            if (args.size() > 1 && args.get(1).defined().getBoolean()) {
                String pem = Files.readString(Path.of(args.get(1).toString()));
                try (PEMParser parser = new PEMParser(new StringReader(pem))) {
                    Object value;
                    while ((value = parser.readObject()) != null) {
                        if (value instanceof X509CertificateHolder holder) {
                            certificates.add(new JcaX509CertificateConverter().setProvider("BC")
                                    .getCertificate(holder));
                        }
                    }
                }
            }
            RuntimeHash hash = new RuntimeHash();
            hash.put(STATE_KEY, new RuntimeScalar(certificates));
            RuntimeScalar ref = hash.createAnonymousReference();
            ReferenceOperators.bless(ref, new RuntimeScalar(CLASS_NAME));
            return ref.getList();
        } catch (Exception e) {
            return CryptOpenSSLX509.fail("Unable to load CA certificates: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static List<X509Certificate> certificates(RuntimeScalar self) {
        RuntimeScalar state = self.hashDeref().get(STATE_KEY);
        if (state != null && state.value instanceof List<?>) return (List<X509Certificate>) state.value;
        throw new IllegalArgumentException("Invalid Crypt::OpenSSL::Verify object");
    }

    public static RuntimeList verify(RuntimeArray args, int ctx) {
        X509Certificate target = CryptOpenSSLX509.certificate(args.get(1));
        for (X509Certificate issuer : certificates(args.get(0))) {
            if (!target.getIssuerX500Principal().equals(issuer.getSubjectX500Principal())) continue;
            try {
                target.verify(issuer.getPublicKey());
                return new RuntimeScalar(1).getList();
            } catch (Exception ignored) {
                // Try the remaining candidate issuers.
            }
        }
        return CryptOpenSSLX509.fail("certificate verify failed");
    }

    public static RuntimeList noop(RuntimeArray args, int ctx) { return new RuntimeList(); }
    public static RuntimeList zero(RuntimeArray args, int ctx) { return new RuntimeScalar(0).getList(); }
}
