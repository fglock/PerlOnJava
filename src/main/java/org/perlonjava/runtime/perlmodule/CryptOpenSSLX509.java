package org.perlonjava.runtime.perlmodule;

import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.openssl.PEMParser;
import org.perlonjava.runtime.operators.ReferenceOperators;
import org.perlonjava.runtime.operators.WarnDie;
import org.perlonjava.runtime.runtimetypes.*;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Locale;

/** Crypt::OpenSSL::X509 compatibility backed by Bouncy Castle and JCA. */
public class CryptOpenSSLX509 extends PerlModuleBase {
    static final String CLASS_NAME = "Crypt::OpenSSL::X509";
    static final String STATE_KEY = "_x509_certificate";
    private static final DateTimeFormatter OPENSSL_TIME =
            DateTimeFormatter.ofPattern("MMM dd HH:mm:ss yyyy 'GMT'", Locale.US).withZone(ZoneOffset.UTC);

    static {
        if (java.security.Security.getProvider("BC") == null) {
            java.security.Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        }
    }

    public CryptOpenSSLX509() { super(CLASS_NAME, false); }

    public static void initialize() {
        CryptOpenSSLX509 module = new CryptOpenSSLX509();
        try {
            module.registerMethod("new_from_string", null);
            module.registerMethod("new_from_file", null);
            module.registerMethod("subject", null);
            module.registerMethod("issuer", null);
            module.registerMethod("notBefore", null);
            module.registerMethod("notAfter", null);
            module.registerMethod("checkend", null);
            module.registerMethod("extensions_by_oid", null);
            module.registerMethod("as_string", null);
            module.registerMethod("DESTROY", "destroy", null);
            module.registerMethod("__X509_cleanup", "destroy", null);
            CryptOpenSSLX509Extension.initialize();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    public static RuntimeList new_from_string(RuntimeArray args, int ctx) {
        if (args.size() < 2) return fail("Usage: Crypt::OpenSSL::X509->new_from_string(string)");
        try {
            return object(parse(args.get(1).toString())).getList();
        } catch (Exception e) {
            return fail("Unable to parse X509 certificate: " + e.getMessage());
        }
    }

    public static RuntimeList new_from_file(RuntimeArray args, int ctx) {
        if (args.size() < 2) return fail("Usage: Crypt::OpenSSL::X509->new_from_file(file)");
        try {
            return object(parse(java.nio.file.Files.readString(java.nio.file.Path.of(args.get(1).toString())))).getList();
        } catch (Exception e) {
            return fail("Unable to parse X509 certificate file: " + e.getMessage());
        }
    }

    static X509Certificate parse(String text) throws Exception {
        try (PEMParser parser = new PEMParser(new StringReader(text))) {
            Object value = parser.readObject();
            if (value instanceof X509CertificateHolder holder) {
                return new JcaX509CertificateConverter().setProvider("BC").getCertificate(holder);
            }
        }
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        return (X509Certificate) factory.generateCertificate(
                new ByteArrayInputStream(text.getBytes(StandardCharsets.ISO_8859_1)));
    }

    static RuntimeScalar object(X509Certificate certificate) {
        RuntimeHash hash = new RuntimeHash();
        hash.put(STATE_KEY, new RuntimeScalar(certificate));
        RuntimeScalar ref = hash.createAnonymousReference();
        ReferenceOperators.bless(ref, new RuntimeScalar(CLASS_NAME));
        return ref;
    }

    static X509Certificate certificate(RuntimeScalar self) {
        RuntimeScalar state = self.hashDeref().get(STATE_KEY);
        if (state != null && state.type == RuntimeScalarType.JAVAOBJECT
                && state.value instanceof X509Certificate certificate) return certificate;
        throw new IllegalArgumentException("Invalid Crypt::OpenSSL::X509 object");
    }

    public static RuntimeList subject(RuntimeArray args, int ctx) {
        return new RuntimeScalar(certificate(args.get(0)).getSubjectX500Principal().getName()).getList();
    }

    public static RuntimeList issuer(RuntimeArray args, int ctx) {
        return new RuntimeScalar(certificate(args.get(0)).getIssuerX500Principal().getName()).getList();
    }

    public static RuntimeList notBefore(RuntimeArray args, int ctx) {
        return new RuntimeScalar(OPENSSL_TIME.format(certificate(args.get(0)).getNotBefore().toInstant())).getList();
    }

    public static RuntimeList notAfter(RuntimeArray args, int ctx) {
        return new RuntimeScalar(OPENSSL_TIME.format(certificate(args.get(0)).getNotAfter().toInstant())).getList();
    }

    public static RuntimeList checkend(RuntimeArray args, int ctx) {
        long seconds = args.size() > 1 ? args.get(1).getLong() : 0;
        Date when = new Date(System.currentTimeMillis() + seconds * 1000L);
        X509Certificate cert = certificate(args.get(0));
        boolean invalid = when.before(cert.getNotBefore()) || when.after(cert.getNotAfter());
        return new RuntimeScalar(invalid).getList();
    }

    public static RuntimeList extensions_by_oid(RuntimeArray args, int ctx) {
        try {
            X509CertificateHolder holder = new X509CertificateHolder(certificate(args.get(0)).getEncoded());
            RuntimeHash hash = new RuntimeHash();
            Extensions extensions = holder.getExtensions();
            if (extensions != null) {
                for (ASN1ObjectIdentifier oid : extensions.getExtensionOIDs()) {
                    hash.put(oid.getId(), CryptOpenSSLX509Extension.object(extensions.getExtension(oid)));
                }
            }
            return hash.createAnonymousReference().getList();
        } catch (Exception e) {
            return fail("Unable to read X509 extensions: " + e.getMessage());
        }
    }

    public static RuntimeList as_string(RuntimeArray args, int ctx) {
        try {
            String base64 = java.util.Base64.getMimeEncoder(64, new byte[]{'\n'})
                    .encodeToString(certificate(args.get(0)).getEncoded());
            return new RuntimeScalar("-----BEGIN CERTIFICATE-----\n" + base64
                    + "\n-----END CERTIFICATE-----\n").getList();
        } catch (Exception e) {
            return fail("Unable to encode X509 certificate: " + e.getMessage());
        }
    }

    public static RuntimeList destroy(RuntimeArray args, int ctx) { return new RuntimeList(); }

    static RuntimeList fail(String message) {
        return WarnDie.die(new RuntimeScalar(message), new RuntimeScalar("\n")).getList();
    }
}
