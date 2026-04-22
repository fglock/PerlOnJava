package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.*;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarTrue;

/**
 * Crypt::OpenSSL::Random implementation for PerlOnJava.
 * <p>
 * Backed by Java's {@link SecureRandom}. Unlike the XS module, we have no
 * explicit seed buffer to query — SecureRandom is always considered seeded,
 * so {@code random_status()} always returns 1. {@code random_seed()} feeds
 * additional entropy via {@code setSeed}; {@code random_egd()} is unsupported.
 */
public class CryptOpenSSLRandom extends PerlModuleBase {

    private static final SecureRandom SECURE = new SecureRandom();

    public CryptOpenSSLRandom() {
        super("Crypt::OpenSSL::Random", false);
    }

    public static void initialize() {
        CryptOpenSSLRandom mod = new CryptOpenSSLRandom();
        GlobalVariable.getGlobalVariable("Crypt::OpenSSL::Random::VERSION").set(new RuntimeScalar("0.17"));
        try {
            mod.registerMethod("random_bytes", null);
            mod.registerMethod("random_pseudo_bytes", null);
            mod.registerMethod("random_seed", null);
            mod.registerMethod("random_status", null);
            mod.registerMethod("random_egd", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Crypt::OpenSSL::Random method: " + e.getMessage());
        }
    }

    /** Binary bytes -> Perl byte-string (latin1-encoded Java String). */
    private static RuntimeScalar bytesToScalar(byte[] bytes) {
        return new RuntimeScalar(new String(bytes, StandardCharsets.ISO_8859_1));
    }

    /** random_bytes(IV num_bytes) - cryptographically strong pseudo-random bytes. */
    public static RuntimeList random_bytes(RuntimeArray args, int ctx) {
        int n = args.isEmpty() ? 0 : args.get(0).getInt();
        if (n < 0) n = 0;
        byte[] out = new byte[n];
        if (n > 0) SECURE.nextBytes(out);
        return bytesToScalar(out).getList();
    }

    /** random_pseudo_bytes(IV num_bytes) - non-cryptographic random bytes. */
    public static RuntimeList random_pseudo_bytes(RuntimeArray args, int ctx) {
        return random_bytes(args, ctx);
    }

    /** random_seed(PV seed_bytes) - feed entropy into the PRNG. Returns true. */
    public static RuntimeList random_seed(RuntimeArray args, int ctx) {
        if (!args.isEmpty()) {
            byte[] seed = args.get(0).toString().getBytes(StandardCharsets.ISO_8859_1);
            SECURE.setSeed(seed);
        }
        return scalarTrue.getList();
    }

    /** random_status() - PRNG always considered seeded. */
    public static RuntimeList random_status(RuntimeArray args, int ctx) {
        return new RuntimeScalar(1).getList();
    }

    /** random_egd(PV path) - entropy gathering daemon not supported. */
    public static RuntimeList random_egd(RuntimeArray args, int ctx) {
        return new RuntimeScalar(-1).getList();
    }
}
