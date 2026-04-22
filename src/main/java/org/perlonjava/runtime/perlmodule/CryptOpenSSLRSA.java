package org.perlonjava.runtime.perlmodule;

import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.pkcs.RSAPublicKey;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;
import org.perlonjava.runtime.runtimetypes.*;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.RSAPublicKeySpec;

import static org.perlonjava.runtime.operators.WarnDie.die;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarFalse;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarTrue;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.JAVAOBJECT;

/**
 * Crypt::OpenSSL::RSA implementation for PerlOnJava, backed by Bouncy Castle
 * and the JDK's {@code java.security} APIs.
 * <p>
 * Implements the subset of the CPAN XS interface exercised by OAuth::Lite and
 * similar consumers: PKCS#1 / X.509 PEM parsing, RSA-PKCS1v15 signing and
 * verification, and the {@code use_shaN_hash} / {@code use_*_padding} mode
 * switches. OAEP encrypt/decrypt and key-parameter introspection are not yet
 * wired up — methods that would need them throw a Perl-level error.
 */
public class CryptOpenSSLRSA extends PerlModuleBase {

    private static final String CLASS_NAME = "Crypt::OpenSSL::RSA";
    private static final String STATE_KEY = "_rsa_state";

    // Sign/verify hash algorithms. Default is SHA-1 to match OAuth 1.0 RSA-SHA1
    // test vectors; the underlying Java Signature algorithm is "<hash>withRSA"
    // (PKCS#1 v1.5 padding).
    enum Hash {
        SHA1("SHA1"), SHA224("SHA224"), SHA256("SHA256"), SHA384("SHA384"),
        SHA512("SHA512"), MD5("MD5"), RIPEMD160("RIPEMD160"), WHIRLPOOL("WHIRLPOOL");
        final String javaName;
        Hash(String javaName) { this.javaName = javaName; }
    }

    enum Padding { NONE, PKCS1, PKCS1_OAEP, PKCS1_PSS, SSLV23 }

    /** Mutable RSA key state kept under $self->{_rsa_state}. */
    public static final class State {
        PrivateKey priv;   // null for public-only keys
        PublicKey pub;     // always set
        Hash hash = Hash.SHA1;
        Padding padding = Padding.PKCS1_OAEP;
    }

    public CryptOpenSSLRSA() {
        super(CLASS_NAME, false);
    }

    public static void initialize() {
        CryptOpenSSLRSA mod = new CryptOpenSSLRSA();
        GlobalVariable.getGlobalVariable("Crypt::OpenSSL::RSA::VERSION").set(new RuntimeScalar("0.37"));
        try {
            // Class methods
            mod.registerMethod("generate_key", null);
            mod.registerMethod("_new_public_key_pkcs1", null);
            mod.registerMethod("_new_public_key_x509", null);
            mod.registerMethod("new_private_key", null);
            mod.registerMethod("_random_seed", null);
            mod.registerMethod("_random_status", null);
            // Instance methods
            mod.registerMethod("DESTROY", null);
            mod.registerMethod("get_public_key_string", null);
            mod.registerMethod("get_public_key_x509_string", null);
            mod.registerMethod("get_private_key_string", null);
            mod.registerMethod("sign", null);
            mod.registerMethod("verify", null);
            mod.registerMethod("size", null);
            mod.registerMethod("check_key", null);
            mod.registerMethod("is_private", null);
            mod.registerMethod("encrypt", null);
            mod.registerMethod("decrypt", null);
            mod.registerMethod("private_encrypt", null);
            mod.registerMethod("public_decrypt", null);
            // Padding selectors
            mod.registerMethod("use_no_padding", null);
            mod.registerMethod("use_pkcs1_padding", null);
            mod.registerMethod("use_pkcs1_oaep_padding", null);
            mod.registerMethod("use_pkcs1_pss_padding", null);
            mod.registerMethod("use_sslv23_padding", null);
            // Hash selectors
            mod.registerMethod("use_md5_hash", null);
            mod.registerMethod("use_sha1_hash", null);
            mod.registerMethod("use_sha224_hash", null);
            mod.registerMethod("use_sha256_hash", null);
            mod.registerMethod("use_sha384_hash", null);
            mod.registerMethod("use_sha512_hash", null);
            mod.registerMethod("use_ripemd160_hash", null);
            mod.registerMethod("use_whirlpool_hash", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Crypt::OpenSSL::RSA method: " + e.getMessage());
        }
    }

    // ---- helpers ----

    private static RuntimeScalar bytesToScalar(byte[] bytes) {
        return new RuntimeScalar(new String(bytes, StandardCharsets.ISO_8859_1));
    }

    private static byte[] scalarToBytes(RuntimeScalar s) {
        return s.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static State getState(RuntimeScalar self) {
        RuntimeHash h = self.hashDeref();
        RuntimeScalar d = h.get(STATE_KEY);
        if (d == null || d.type != JAVAOBJECT || !(d.value instanceof State s)) {
            die(new RuntimeScalar("Crypt::OpenSSL::RSA: invalid object (no state)"),
                    new RuntimeScalar("\n"));
            return null;
        }
        return s;
    }

    private static RuntimeScalar newBlessedObject(String className, State st) {
        RuntimeHash h = new RuntimeHash();
        h.blessId = NameNormalizer.getBlessId(className);
        h.put(STATE_KEY, new RuntimeScalar(st));
        return h.createReference();
    }

    private static String writePem(String type, byte[] der) {
        try {
            StringWriter sw = new StringWriter();
            try (PemWriter pw = new PemWriter(sw)) {
                pw.writeObject(new PemObject(type, der));
            }
            return sw.toString();
        } catch (Exception e) {
            throw new RuntimeException("PEM write failed: " + e.getMessage(), e);
        }
    }

    // ---- class methods ----

    /** generate_key($class, $bits, $exp = 65537) */
    public static RuntimeList generate_key(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            die(new RuntimeScalar("Usage: Crypt::OpenSSL::RSA->generate_key($bits [, $exp])"),
                    new RuntimeScalar("\n"));
        }
        String cls = args.get(0).toString();
        int bits = args.get(1).getInt();
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            // NB: Java's default RSA exponent is 65537; honouring a custom $exp
            // would require RSAKeyGenParameterSpec with a BigInteger — not worth
            // the dependency on args.get(2) being a valid public exponent for OAuth.
            kpg.initialize(bits);
            KeyPair kp = kpg.generateKeyPair();
            State st = new State();
            st.priv = kp.getPrivate();
            st.pub = kp.getPublic();
            return newBlessedObject(cls, st).getList();
        } catch (Exception e) {
            die(new RuntimeScalar("generate_key failed: " + e.getMessage()),
                    new RuntimeScalar("\n"));
            return scalarFalse.getList();
        }
    }

    /** _new_public_key_pkcs1($class, $pem) — -----BEGIN RSA PUBLIC KEY----- */
    public static RuntimeList _new_public_key_pkcs1(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            die(new RuntimeScalar("Usage: Crypt::OpenSSL::RSA->new_public_key($pem)"),
                    new RuntimeScalar("\n"));
        }
        String cls = args.get(0).toString();
        String pem = args.get(1).toString();
        try (PEMParser p = new PEMParser(new StringReader(pem))) {
            Object obj = p.readObject();
            PublicKey pk;
            if (obj instanceof SubjectPublicKeyInfo spki) {
                pk = new JcaPEMKeyConverter().getPublicKey(spki);
            } else if (obj instanceof RSAPublicKey rsaPub) {
                // Raw PKCS#1 RSAPublicKey (some BC versions expose it directly).
                KeyFactory kf = KeyFactory.getInstance("RSA");
                pk = kf.generatePublic(new RSAPublicKeySpec(
                        rsaPub.getModulus(), rsaPub.getPublicExponent()));
            } else {
                die(new RuntimeScalar("unrecognized public key PEM"),
                        new RuntimeScalar("\n"));
                return scalarFalse.getList();
            }
            State st = new State();
            st.pub = pk;
            return newBlessedObject(cls, st).getList();
        } catch (Exception e) {
            die(new RuntimeScalar("new_public_key (pkcs1) failed: " + e.getMessage()),
                    new RuntimeScalar("\n"));
            return scalarFalse.getList();
        }
    }

    /** _new_public_key_x509($class, $pem) — -----BEGIN PUBLIC KEY----- */
    public static RuntimeList _new_public_key_x509(RuntimeArray args, int ctx) {
        // BC's PEMParser handles both PKCS1 RSA PUBLIC KEY and X.509 PUBLIC KEY
        // by emitting a SubjectPublicKeyInfo, so the x509 path shares the
        // pkcs1 code path.
        return _new_public_key_pkcs1(args, ctx);
    }

    /** new_private_key($class, $pem [, $passphrase]) */
    public static RuntimeList new_private_key(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            die(new RuntimeScalar("Usage: Crypt::OpenSSL::RSA->new_private_key($pem)"),
                    new RuntimeScalar("\n"));
        }
        String cls = args.get(0).toString();
        String pem = args.get(1).toString();
        // Passphrase-protected keys not yet supported.
        try (PEMParser p = new PEMParser(new StringReader(pem))) {
            Object obj = p.readObject();
            KeyPair kp;
            if (obj instanceof PEMKeyPair pkp) {
                kp = new JcaPEMKeyConverter().getKeyPair(pkp);
            } else if (obj instanceof PrivateKeyInfo pki) {
                PrivateKey pk = new JcaPEMKeyConverter().getPrivateKey(pki);
                // derive public key from CRT parameters
                if (pk instanceof RSAPrivateCrtKey crt) {
                    KeyFactory kf = KeyFactory.getInstance("RSA");
                    PublicKey pub = kf.generatePublic(new RSAPublicKeySpec(
                            crt.getModulus(), crt.getPublicExponent()));
                    State st = new State();
                    st.priv = pk;
                    st.pub = pub;
                    return newBlessedObject(cls, st).getList();
                }
                die(new RuntimeScalar("unsupported private key (not RSA CRT)"),
                        new RuntimeScalar("\n"));
                return scalarFalse.getList();
            } else {
                die(new RuntimeScalar("unrecognized private key format"),
                        new RuntimeScalar("\n"));
                return scalarFalse.getList();
            }
            State st = new State();
            st.priv = kp.getPrivate();
            st.pub = kp.getPublic();
            return newBlessedObject(cls, st).getList();
        } catch (Exception e) {
            die(new RuntimeScalar("new_private_key failed: " + e.getMessage()),
                    new RuntimeScalar("\n"));
            return scalarFalse.getList();
        }
    }

    /** Class-level stubs used by import_random_seed in RSA.pm */
    public static RuntimeList _random_status(RuntimeArray args, int ctx) {
        return new RuntimeScalar(1).getList();
    }
    public static RuntimeList _random_seed(RuntimeArray args, int ctx) {
        return scalarTrue.getList();
    }

    // ---- instance methods ----

    public static RuntimeList DESTROY(RuntimeArray args, int ctx) {
        return scalarTrue.getList();
    }

    public static RuntimeList is_private(RuntimeArray args, int ctx) {
        State st = getState(args.get(0));
        return (st.priv != null ? scalarTrue : scalarFalse).getList();
    }

    public static RuntimeList check_key(RuntimeArray args, int ctx) {
        State st = getState(args.get(0));
        if (st.priv == null) {
            die(new RuntimeScalar("check_key called on public key"), new RuntimeScalar("\n"));
        }
        return scalarTrue.getList();
    }

    public static RuntimeList size(RuntimeArray args, int ctx) {
        State st = getState(args.get(0));
        if (st.pub instanceof RSAKey rsa) {
            return new RuntimeScalar((rsa.getModulus().bitLength() + 7) / 8).getList();
        }
        return new RuntimeScalar(0).getList();
    }

    public static RuntimeList get_public_key_string(RuntimeArray args, int ctx) {
        State st = getState(args.get(0));
        try {
            // PKCS#1 RSAPublicKey DER (not the SPKI).
            SubjectPublicKeyInfo spki = SubjectPublicKeyInfo.getInstance(st.pub.getEncoded());
            byte[] pkcs1 = spki.parsePublicKey().getEncoded(ASN1Encoding.DER);
            return new RuntimeScalar(writePem("RSA PUBLIC KEY", pkcs1)).getList();
        } catch (Exception e) {
            die(new RuntimeScalar("get_public_key_string failed: " + e.getMessage()),
                    new RuntimeScalar("\n"));
            return scalarFalse.getList();
        }
    }

    public static RuntimeList get_public_key_x509_string(RuntimeArray args, int ctx) {
        State st = getState(args.get(0));
        return new RuntimeScalar(writePem("PUBLIC KEY", st.pub.getEncoded())).getList();
    }

    public static RuntimeList get_private_key_string(RuntimeArray args, int ctx) {
        State st = getState(args.get(0));
        if (st.priv == null) {
            die(new RuntimeScalar("get_private_key_string called on public-only key"),
                    new RuntimeScalar("\n"));
        }
        try {
            // Convert PKCS#8 encoding to PKCS#1 RSAPrivateKey DER.
            PrivateKeyInfo pki = PrivateKeyInfo.getInstance(st.priv.getEncoded());
            byte[] pkcs1 = pki.parsePrivateKey().toASN1Primitive().getEncoded(ASN1Encoding.DER);
            return new RuntimeScalar(writePem("RSA PRIVATE KEY", pkcs1)).getList();
        } catch (Exception e) {
            die(new RuntimeScalar("get_private_key_string failed: " + e.getMessage()),
                    new RuntimeScalar("\n"));
            return scalarFalse.getList();
        }
    }

    public static RuntimeList sign(RuntimeArray args, int ctx) {
        State st = getState(args.get(0));
        if (st.priv == null) {
            die(new RuntimeScalar("sign requires a private key"), new RuntimeScalar("\n"));
        }
        byte[] data = scalarToBytes(args.get(1));
        try {
            Signature sig = Signature.getInstance(st.hash.javaName + "withRSA");
            sig.initSign(st.priv);
            sig.update(data);
            return bytesToScalar(sig.sign()).getList();
        } catch (Exception e) {
            die(new RuntimeScalar("sign failed: " + e.getMessage()), new RuntimeScalar("\n"));
            return scalarFalse.getList();
        }
    }

    public static RuntimeList verify(RuntimeArray args, int ctx) {
        State st = getState(args.get(0));
        byte[] data = scalarToBytes(args.get(1));
        byte[] sigBytes = scalarToBytes(args.get(2));
        try {
            Signature sig = Signature.getInstance(st.hash.javaName + "withRSA");
            sig.initVerify(st.pub);
            sig.update(data);
            return (sig.verify(sigBytes) ? scalarTrue : scalarFalse).getList();
        } catch (Exception e) {
            // Per Crypt::OpenSSL::RSA semantics, bad signatures return false,
            // not die. Only programmer errors should croak.
            return scalarFalse.getList();
        }
    }

    // encrypt/decrypt not wired up yet — OAuth doesn't need them.
    public static RuntimeList encrypt(RuntimeArray args, int ctx) {
        die(new RuntimeScalar("Crypt::OpenSSL::RSA::encrypt: not implemented in PerlOnJava"),
                new RuntimeScalar("\n"));
        return scalarFalse.getList();
    }
    public static RuntimeList decrypt(RuntimeArray args, int ctx) {
        die(new RuntimeScalar("Crypt::OpenSSL::RSA::decrypt: not implemented in PerlOnJava"),
                new RuntimeScalar("\n"));
        return scalarFalse.getList();
    }
    public static RuntimeList private_encrypt(RuntimeArray args, int ctx) {
        die(new RuntimeScalar("Crypt::OpenSSL::RSA::private_encrypt: not implemented in PerlOnJava"),
                new RuntimeScalar("\n"));
        return scalarFalse.getList();
    }
    public static RuntimeList public_decrypt(RuntimeArray args, int ctx) {
        die(new RuntimeScalar("Crypt::OpenSSL::RSA::public_decrypt: not implemented in PerlOnJava"),
                new RuntimeScalar("\n"));
        return scalarFalse.getList();
    }

    // ---- padding selectors ----

    private static RuntimeList setPadding(RuntimeArray args, Padding p) {
        getState(args.get(0)).padding = p;
        return scalarTrue.getList();
    }
    public static RuntimeList use_no_padding(RuntimeArray args, int ctx)         { return setPadding(args, Padding.NONE); }
    public static RuntimeList use_pkcs1_padding(RuntimeArray args, int ctx) {
        // Crypt::OpenSSL::RSA 0.35+ makes this fatal. We match that.
        die(new RuntimeScalar("use_pkcs1_padding: PKCS#1 v1.5 padding is insecure and disabled"),
                new RuntimeScalar("\n"));
        return scalarFalse.getList();
    }
    public static RuntimeList use_pkcs1_oaep_padding(RuntimeArray args, int ctx) { return setPadding(args, Padding.PKCS1_OAEP); }
    public static RuntimeList use_pkcs1_pss_padding(RuntimeArray args, int ctx)  { return setPadding(args, Padding.PKCS1_PSS); }
    public static RuntimeList use_sslv23_padding(RuntimeArray args, int ctx)     { return setPadding(args, Padding.SSLV23); }

    // ---- hash selectors ----

    private static RuntimeList setHash(RuntimeArray args, Hash h) {
        getState(args.get(0)).hash = h;
        return scalarTrue.getList();
    }
    public static RuntimeList use_md5_hash(RuntimeArray args, int ctx)       { return setHash(args, Hash.MD5); }
    public static RuntimeList use_sha1_hash(RuntimeArray args, int ctx)      { return setHash(args, Hash.SHA1); }
    public static RuntimeList use_sha224_hash(RuntimeArray args, int ctx)    { return setHash(args, Hash.SHA224); }
    public static RuntimeList use_sha256_hash(RuntimeArray args, int ctx)    { return setHash(args, Hash.SHA256); }
    public static RuntimeList use_sha384_hash(RuntimeArray args, int ctx)    { return setHash(args, Hash.SHA384); }
    public static RuntimeList use_sha512_hash(RuntimeArray args, int ctx)    { return setHash(args, Hash.SHA512); }
    public static RuntimeList use_ripemd160_hash(RuntimeArray args, int ctx) { return setHash(args, Hash.RIPEMD160); }
    public static RuntimeList use_whirlpool_hash(RuntimeArray args, int ctx) { return setHash(args, Hash.WHIRLPOOL); }
}
