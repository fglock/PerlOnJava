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
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.security.spec.RSAPublicKeySpec;

import static org.perlonjava.runtime.operators.WarnDie.die;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarFalse;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarTrue;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarUndef;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.JAVAOBJECT;

/**
 * Crypt::OpenSSL::RSA implementation for PerlOnJava, backed by Bouncy Castle
 * and the JDK's {@code java.security} APIs.
 * <p>
 * Implements the subset of the CPAN XS interface exercised by OAuth::Lite and
 * similar consumers: PKCS#1 / X.509 PEM parsing, RSA-PKCS1v15 signing and
 * verification, OAEP / PKCS#1 v1.5 encryption and decryption (including the
 * legacy {@code private_encrypt} / {@code public_decrypt} primitives), the
 * {@code use_shaN_hash} / {@code use_*_padding} mode switches, and the
 * {@code new_key_from_parameters} / {@code get_key_parameters} round-trips
 * (backed by {@link CryptOpenSSLBignum}).
 */
public class CryptOpenSSLRSA extends PerlModuleBase {

    private static final String CLASS_NAME = "Crypt::OpenSSL::RSA";
    private static final String STATE_KEY = "_rsa_state";

    // Register Bouncy Castle as a JCE provider once, so that signature
    // algorithms the JDK doesn't ship (e.g. RIPEMD160withRSA, WhirlpoolwithRSA,
    // PSS with non-SHA-1 digests) resolve through BC transparently.
    static {
        if (java.security.Security.getProvider("BC") == null) {
            java.security.Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        }
    }

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
            mod.registerMethod("_new_key_from_parameters", null);
            mod.registerMethod("_get_key_parameters", null);
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

    // Use Bouncy Castle as the backing provider for key parsing + construction.
    // BC is permissive about key sizes (Sun's RSA provider rejects keys
    // smaller than 512 bits, which breaks Crypt::OpenSSL::RSA's t/format.t
    // canary keys) and exposes a superset of the JDK's algorithms.
    private static JcaPEMKeyConverter pemConverter() {
        return new JcaPEMKeyConverter().setProvider("BC");
    }

    private static KeyFactory rsaKeyFactory() throws java.security.NoSuchAlgorithmException {
        try {
            return KeyFactory.getInstance("RSA", "BC");
        } catch (java.security.NoSuchProviderException e) {
            return KeyFactory.getInstance("RSA");
        }
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
                pk = pemConverter().getPublicKey(spki);
            } else if (obj instanceof RSAPublicKey rsaPub) {
                // Raw PKCS#1 RSAPublicKey (some BC versions expose it directly).
                pk = rsaKeyFactory().generatePublic(new RSAPublicKeySpec(
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
                kp = pemConverter().getKeyPair(pkp);
            } else if (obj instanceof PrivateKeyInfo pki) {
                PrivateKey pk = pemConverter().getPrivateKey(pki);
                // derive public key from CRT parameters
                if (pk instanceof RSAPrivateCrtKey crt) {
                    PublicKey pub = rsaKeyFactory().generatePublic(new RSAPublicKeySpec(
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

    // ---- Bignum-backed parameter round-trips ----
    //
    // The Perl-side Crypt::OpenSSL::RSA wrapper passes BIGNUM values across XS
    // as "pointers" (opaque scalars) produced by Crypt::OpenSSL::Bignum's
    // pointer_copy(); here those scalars carry a java.math.BigInteger JAVAOBJECT.
    // We do the BigInteger -> java.security.Key translation here.

    /**
     * _new_key_from_parameters($class, $n, $e, $d, $p, $q)
     * <p>
     * The public key requires ($n, $e). If ($p, $q) are present we derive the
     * full CRT private key; otherwise if $d is present we build a plain
     * (n,d) private key via PKCS#8. With just (n, e) we return a public-only
     * RSA object, matching the upstream XS.
     */
    public static RuntimeList _new_key_from_parameters(RuntimeArray args, int ctx) {
        if (args.size() < 3) {
            die(new RuntimeScalar("Usage: Crypt::OpenSSL::RSA->new_key_from_parameters($n, $e [, $d, $p, $q])"),
                    new RuntimeScalar("\n"));
        }
        String cls = args.get(0).toString();
        BigInteger n = scalarToBigInt(args.get(1));
        BigInteger e = scalarToBigInt(args.get(2));
        BigInteger d = args.size() > 3 ? scalarToBigIntOrNull(args.get(3)) : null;
        BigInteger p = args.size() > 4 ? scalarToBigIntOrNull(args.get(4)) : null;
        BigInteger q = args.size() > 5 ? scalarToBigIntOrNull(args.get(5)) : null;

        if (n == null || e == null) {
            die(new RuntimeScalar("new_key_from_parameters: n and e are required"),
                    new RuntimeScalar("\n"));
        }

        // Do the Bignum-level sanity / derivation work BEFORE asking Java's
        // KeyFactory to build the public key: BC rejects even moduli outright
        // with "RSA modulus is even", but we want to surface the semantically
        // correct "p not prime" / "q not prime" error the caller is looking for.
        if (d != null || p != null || q != null) {
            // If we have one prime factor but not the other, derive it from
            // the modulus (q = n / p when n % p == 0, and vice versa). If the
            // division isn't exact the caller lied about the supposed prime.
            if (p != null && q == null) {
                BigInteger[] dr = n.divideAndRemainder(p);
                if (dr[1].signum() != 0) {
                    die(new RuntimeScalar("OpenSSL error: q not prime"), new RuntimeScalar("\n"));
                }
                q = dr[0];
            }
            if (q != null && p == null) {
                BigInteger[] dr = n.divideAndRemainder(q);
                if (dr[1].signum() != 0) {
                    die(new RuntimeScalar("OpenSSL error: p not prime"), new RuntimeScalar("\n"));
                }
                p = dr[0];
            }
            if (p != null && !p.isProbablePrime(20)) {
                die(new RuntimeScalar("OpenSSL error: p not prime"), new RuntimeScalar("\n"));
            }
            if (q != null && !q.isProbablePrime(20)) {
                die(new RuntimeScalar("OpenSSL error: q not prime"), new RuntimeScalar("\n"));
            }
            // If d was omitted but we have both primes, derive it from e and
            // the Euler totient phi(n) = (p-1)(q-1).
            if (d == null && p != null && q != null) {
                BigInteger phi = p.subtract(BigInteger.ONE).multiply(q.subtract(BigInteger.ONE));
                d = e.modInverse(phi);
            }
        }

        try {
            KeyFactory kf = rsaKeyFactory();
            State st = new State();
            st.pub = kf.generatePublic(new RSAPublicKeySpec(n, e));

            if (d != null) {
                if (p != null && q != null) {
                    // Full CRT parameters: fastest private key.
                    BigInteger dP   = d.mod(p.subtract(BigInteger.ONE));
                    BigInteger dQ   = d.mod(q.subtract(BigInteger.ONE));
                    BigInteger qInv = q.modInverse(p);
                    st.priv = kf.generatePrivate(new RSAPrivateCrtKeySpec(n, e, d, p, q, dP, dQ, qInv));
                } else {
                    // (n, d) only — no CRT acceleration.
                    st.priv = kf.generatePrivate(new java.security.spec.RSAPrivateKeySpec(n, d));
                }
            }

            return newBlessedObject(cls, st).getList();
        } catch (org.perlonjava.runtime.runtimetypes.PerlDieException pde) {
            throw pde;
        } catch (Exception ex) {
            die(new RuntimeScalar("new_key_from_parameters failed: " + ex.getMessage()),
                    new RuntimeScalar("\n"));
            return scalarFalse.getList();
        }
    }

    /**
     * _get_key_parameters($self)
     * <p>
     * Returns up to 8 "pointers" (scalars carrying BigInteger JAVAOBJECTs):
     * n, e, d, p, q, d mod (p-1), d mod (q-1), 1/q mod p. Missing values
     * (e.g. d/p/q on a public-only key) come back as undef, which the Perl
     * wrapper maps to undef in the Bignum list.
     */
    public static RuntimeList _get_key_parameters(RuntimeArray args, int ctx) {
        State st = getState(args.get(0));
        RuntimeList out = new RuntimeList();

        BigInteger n = null, e = null;
        if (st.pub instanceof java.security.interfaces.RSAPublicKey pk) {
            n = pk.getModulus();
            e = pk.getPublicExponent();
        } else if (st.priv instanceof RSAPrivateCrtKey crt) {
            n = crt.getModulus();
            e = crt.getPublicExponent();
        }
        out.add(asPtr(n));
        out.add(asPtr(e));

        if (st.priv instanceof RSAPrivateCrtKey crt) {
            out.add(asPtr(crt.getPrivateExponent()));
            out.add(asPtr(crt.getPrimeP()));
            out.add(asPtr(crt.getPrimeQ()));
            out.add(asPtr(crt.getPrimeExponentP()));
            out.add(asPtr(crt.getPrimeExponentQ()));
            out.add(asPtr(crt.getCrtCoefficient()));
        } else if (st.priv instanceof java.security.interfaces.RSAPrivateKey pk) {
            out.add(asPtr(pk.getPrivateExponent()));
            for (int i = 0; i < 5; i++) out.add(scalarUndef);
        } else {
            // public-only key
            for (int i = 0; i < 6; i++) out.add(scalarUndef);
        }
        return out;
    }

    // ---- Bignum-pointer marshaling helpers ----

    /** Decode a "pointer" scalar produced by Crypt::OpenSSL::Bignum::pointer_copy. */
    private static BigInteger scalarToBigInt(RuntimeScalar s) {
        if (s.type == JAVAOBJECT && s.value instanceof BigInteger bi) return bi;
        try { return new BigInteger(s.toString()); }
        catch (NumberFormatException nfe) { return null; }
    }

    private static BigInteger scalarToBigIntOrNull(RuntimeScalar s) {
        if (s == null) return null;
        // The Perl wrapper maps missing Bignums to 0, which we treat as "absent".
        if (s.type == JAVAOBJECT && s.value instanceof BigInteger bi) return bi;
        String str = s.toString();
        if (str.isEmpty() || str.equals("0")) return null;
        try { return new BigInteger(str); }
        catch (NumberFormatException nfe) { return null; }
    }

    private static RuntimeScalar asPtr(BigInteger v) {
        return v == null ? scalarUndef : new RuntimeScalar(v);
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
            return bytesToScalar(signImpl(st, data)).getList();
        } catch (Exception e) {
            die(new RuntimeScalar("sign failed: " + e.getMessage()), new RuntimeScalar("\n"));
            return scalarFalse.getList();
        }
    }

    private static byte[] signImpl(State st, byte[] data) throws Exception {
        // Fast path: whatever "<hash>withRSA" Signature algorithm the JDK +
        // Bouncy Castle collectively expose.
        try {
            Signature sig = Signature.getInstance(st.hash.javaName + "withRSA");
            sig.initSign(st.priv);
            sig.update(data);
            return sig.sign();
        } catch (java.security.NoSuchAlgorithmException nsa) {
            // Fallback for hashes with no bundled <Hash>withRSA provider
            // (e.g. Whirlpool). Build the DigestInfo ourselves and let
            // Cipher("RSA/ECB/PKCS1Padding") apply PKCS#1 v1.5 type-1 padding.
            byte[] digestInfo = buildDigestInfo(st.hash, data);
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding");
            c.init(javax.crypto.Cipher.ENCRYPT_MODE, st.priv);
            return c.doFinal(digestInfo);
        }
    }

    public static RuntimeList verify(RuntimeArray args, int ctx) {
        State st = getState(args.get(0));
        byte[] data = scalarToBytes(args.get(1));
        byte[] sigBytes = scalarToBytes(args.get(2));
        try {
            // Fast path — symmetric with sign().
            Signature sig = Signature.getInstance(st.hash.javaName + "withRSA");
            sig.initVerify(st.pub);
            sig.update(data);
            return (sig.verify(sigBytes) ? scalarTrue : scalarFalse).getList();
        } catch (java.security.NoSuchAlgorithmException nsa) {
            // Fallback: recover DigestInfo via Cipher("RSA/ECB/PKCS1Padding")
            // and compare against the locally-computed DigestInfo.
            try {
                javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding");
                c.init(javax.crypto.Cipher.DECRYPT_MODE, st.pub);
                byte[] recovered = c.doFinal(sigBytes);
                byte[] expected = buildDigestInfo(st.hash, data);
                return (java.util.Arrays.equals(recovered, expected) ? scalarTrue : scalarFalse).getList();
            } catch (Exception e) {
                return scalarFalse.getList();
            }
        } catch (Exception e) {
            // Per Crypt::OpenSSL::RSA semantics, bad signatures return false,
            // not die. Only programmer errors should croak.
            return scalarFalse.getList();
        }
    }

    /**
     * Build the PKCS#1 v1.5 DigestInfo DER for the given hash algorithm over {@code data}:
     * {@code SEQUENCE { SEQUENCE { OID algorithm, NULL }, OCTET STRING digest }}.
     */
    private static byte[] buildDigestInfo(Hash h, byte[] data) throws Exception {
        String jdkName = switch (h) {
            case SHA1       -> "SHA-1";
            case SHA224     -> "SHA-224";
            case SHA256     -> "SHA-256";
            case SHA384     -> "SHA-384";
            case SHA512     -> "SHA-512";
            case MD5        -> "MD5";
            case RIPEMD160  -> "RIPEMD160";
            case WHIRLPOOL  -> "WHIRLPOOL";
        };
        java.security.MessageDigest md;
        try {
            md = java.security.MessageDigest.getInstance(jdkName);
        } catch (java.security.NoSuchAlgorithmException nsa) {
            md = java.security.MessageDigest.getInstance(jdkName, "BC");
        }
        byte[] digest = md.digest(data);

        org.bouncycastle.asn1.ASN1ObjectIdentifier oid = switch (h) {
            case SHA1       -> new org.bouncycastle.asn1.ASN1ObjectIdentifier("1.3.14.3.2.26");
            case SHA224     -> new org.bouncycastle.asn1.ASN1ObjectIdentifier("2.16.840.1.101.3.4.2.4");
            case SHA256     -> new org.bouncycastle.asn1.ASN1ObjectIdentifier("2.16.840.1.101.3.4.2.1");
            case SHA384     -> new org.bouncycastle.asn1.ASN1ObjectIdentifier("2.16.840.1.101.3.4.2.2");
            case SHA512     -> new org.bouncycastle.asn1.ASN1ObjectIdentifier("2.16.840.1.101.3.4.2.3");
            case MD5        -> new org.bouncycastle.asn1.ASN1ObjectIdentifier("1.2.840.113549.2.5");
            case RIPEMD160  -> new org.bouncycastle.asn1.ASN1ObjectIdentifier("1.3.36.3.2.1");
            case WHIRLPOOL  -> new org.bouncycastle.asn1.ASN1ObjectIdentifier("1.0.10118.3.0.55");
        };
        org.bouncycastle.asn1.x509.AlgorithmIdentifier ai =
                new org.bouncycastle.asn1.x509.AlgorithmIdentifier(oid, org.bouncycastle.asn1.DERNull.INSTANCE);
        return new org.bouncycastle.asn1.x509.DigestInfo(ai, digest).getEncoded(ASN1Encoding.DER);
    }

    // ---- encrypt / decrypt ----
    //
    // Padding → Java Cipher transformation mapping:
    //   NONE       → RSA/ECB/NoPadding     (raw modular exponentiation)
    //   PKCS1      → RSA/ECB/PKCS1Padding  (PKCS#1 v1.5 type 2 for enc, type 1 for sign;
    //                                       Java chooses based on cipher mode + key type)
    //   PKCS1_OAEP → RSA/ECB/OAEPWithSHA-1AndMGF1Padding  (SHA-1 per Crypt::OpenSSL::RSA docs)
    //   PKCS1_PSS  → signing-only; encryption methods croak
    //   SSLV23     → not supported by the JDK; encryption methods croak
    //
    // {encrypt, decrypt} use the public/private key respectively in the usual
    // encryption direction. {private_encrypt, public_decrypt} are the legacy
    // low-level "sign raw block" primitives OpenSSL exposes; Java's SunJCE
    // RSA/PKCS1Padding Cipher selects the correct PKCS#1 block type (1 vs 2)
    // based on the (mode, key type) combination, so we just plumb through.

    private static String cipherTransform(Padding p) {
        return switch (p) {
            case NONE       -> "RSA/ECB/NoPadding";
            case PKCS1      -> "RSA/ECB/PKCS1Padding";
            case PKCS1_OAEP -> "RSA/ECB/OAEPWithSHA-1AndMGF1Padding";
            case PKCS1_PSS  -> throw new IllegalStateException("PSS padding is for signing only");
            case SSLV23     -> throw new IllegalStateException("SSLv23 padding is not supported");
        };
    }

    private static byte[] rsaCipher(State st, int mode, java.security.Key key, byte[] data) throws Exception {
        javax.crypto.Cipher c = javax.crypto.Cipher.getInstance(cipherTransform(st.padding));
        c.init(mode, key);
        return c.doFinal(data);
    }

    /** encrypt($data) — encrypt with the public key. */
    public static RuntimeList encrypt(RuntimeArray args, int ctx) {
        State st = getState(args.get(0));
        byte[] data = scalarToBytes(args.get(1));
        try {
            return bytesToScalar(rsaCipher(st, javax.crypto.Cipher.ENCRYPT_MODE, st.pub, data)).getList();
        } catch (Exception e) {
            die(new RuntimeScalar("encrypt failed: " + e.getMessage()), new RuntimeScalar("\n"));
            return scalarFalse.getList();
        }
    }

    /** decrypt($ciphertext) — decrypt with the private key. */
    public static RuntimeList decrypt(RuntimeArray args, int ctx) {
        State st = getState(args.get(0));
        if (st.priv == null) {
            die(new RuntimeScalar("decrypt requires a private key"), new RuntimeScalar("\n"));
        }
        byte[] data = scalarToBytes(args.get(1));
        try {
            return bytesToScalar(rsaCipher(st, javax.crypto.Cipher.DECRYPT_MODE, st.priv, data)).getList();
        } catch (Exception e) {
            die(new RuntimeScalar("decrypt failed: " + e.getMessage()), new RuntimeScalar("\n"));
            return scalarFalse.getList();
        }
    }

    /** private_encrypt($data) — legacy "sign raw block" using the private key. */
    public static RuntimeList private_encrypt(RuntimeArray args, int ctx) {
        State st = getState(args.get(0));
        if (st.priv == null) {
            die(new RuntimeScalar("private_encrypt requires a private key"), new RuntimeScalar("\n"));
        }
        byte[] data = scalarToBytes(args.get(1));
        try {
            return bytesToScalar(rsaCipher(st, javax.crypto.Cipher.ENCRYPT_MODE, st.priv, data)).getList();
        } catch (Exception e) {
            die(new RuntimeScalar("private_encrypt failed: " + e.getMessage()), new RuntimeScalar("\n"));
            return scalarFalse.getList();
        }
    }

    /** public_decrypt($ciphertext) — legacy "verify raw block" using the public key. */
    public static RuntimeList public_decrypt(RuntimeArray args, int ctx) {
        State st = getState(args.get(0));
        byte[] data = scalarToBytes(args.get(1));
        try {
            return bytesToScalar(rsaCipher(st, javax.crypto.Cipher.DECRYPT_MODE, st.pub, data)).getList();
        } catch (Exception e) {
            die(new RuntimeScalar("public_decrypt failed: " + e.getMessage()), new RuntimeScalar("\n"));
            return scalarFalse.getList();
        }
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
