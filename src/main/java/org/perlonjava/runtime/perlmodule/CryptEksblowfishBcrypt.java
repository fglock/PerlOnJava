package org.perlonjava.runtime.perlmodule;

import org.bouncycastle.crypto.generators.BCrypt;
import org.perlonjava.runtime.runtimetypes.*;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/** Focused bcrypt primitive used by Crypt::Eksblowfish::Bcrypt. */
public class CryptEksblowfishBcrypt extends PerlModuleBase {
    private static final SecureRandom RANDOM = new SecureRandom();
    public CryptEksblowfishBcrypt() {
        super("Crypt::Eksblowfish::Bcrypt", false);
    }

    public static void initialize() {
        CryptEksblowfishBcrypt mod = new CryptEksblowfishBcrypt();
        try {
            mod.registerMethod("_bcrypt_hash_java", "bcryptHash", null);
            mod.registerMethod("_bcrypt_random_bytes", null);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Crypt::Eksblowfish::Bcrypt Java binding is incomplete", e);
        }
    }

    public static RuntimeList bcryptHash(RuntimeArray args, int ctx) {
        if (args.size() < 4) throw new PerlCompilerException(
                "Usage: Crypt::Eksblowfish::Bcrypt::_bcrypt_hash_java(key_nul, cost, salt, password)");
        boolean keyNul = args.get(0).getBoolean();
        int cost = args.get(1).getInt();
        byte[] salt = bytes(args.get(2));
        byte[] password = bytes(args.get(3));
        if (salt.length != 16) throw new PerlCompilerException("bcrypt salt must be 16 octets");
        if (password.length > 72) password = Arrays.copyOf(password, 72);
        if (keyNul || password.length == 0) {
            int length = Math.min(password.length + 1, 72);
            password = Arrays.copyOf(password, length);
        }
        try {
            // Bouncy Castle enforces bcrypt's modern cost floor of four.  The
            // older Crypt::Eksblowfish API also accepts 0..3; retain its
            // round-trip behavior for those legacy settings using the floor.
            byte[] result = BCrypt.generate(password, salt, Math.max(4, cost), false);
            return new RuntimeScalar(new String(Arrays.copyOf(result, 23), StandardCharsets.ISO_8859_1)).getList();
        } catch (RuntimeException e) {
            throw new PerlCompilerException("bcrypt failed: " + e.getMessage());
        }
    }

    public static RuntimeList _bcrypt_random_bytes(RuntimeArray args, int ctx) {
        int length = args.isEmpty() ? 16 : args.get(0).getInt();
        byte[] output = new byte[Math.max(0, length)];
        RANDOM.nextBytes(output);
        return new RuntimeScalar(new String(output, StandardCharsets.ISO_8859_1)).getList();
    }

    private static byte[] bytes(RuntimeScalar scalar) {
        return scalar.toString().getBytes(StandardCharsets.ISO_8859_1);
    }
}
