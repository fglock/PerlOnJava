package org.perlonjava.runtime.perlmodule;

import org.bouncycastle.crypto.generators.SCrypt;
import org.perlonjava.runtime.runtimetypes.*;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/** Authen::Passphrase::Scrypt's single XS primitive, backed by Bouncy Castle. */
public class AuthenPassphraseScrypt extends PerlModuleBase {
    private static final SecureRandom RANDOM = new SecureRandom();
    public AuthenPassphraseScrypt() {
        super("Authen::Passphrase::Scrypt", false);
    }

    public static void initialize() {
        AuthenPassphraseScrypt mod = new AuthenPassphraseScrypt();
        GlobalVariable.getGlobalVariable("Authen::Passphrase::Scrypt::VERSION").set(new RuntimeScalar("0.002"));
        try {
            mod.registerMethod("crypto_scrypt", null);
            mod.registerMethod("_scrypt_random_bytes", null);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Authen::Passphrase::Scrypt Java binding is incomplete", e);
        }
    }

    public static RuntimeList crypto_scrypt(RuntimeArray args, int ctx) {
        if (args.size() < 6) throw new PerlCompilerException(
                "Usage: Authen::Passphrase::Scrypt::crypto_scrypt(password, salt, N, r, p, length)");
        try {
            byte[] result = SCrypt.generate(bytes(args.get(0)), bytes(args.get(1)),
                    args.get(2).getInt(), args.get(3).getInt(), args.get(4).getInt(), args.get(5).getInt());
            return new RuntimeScalar(new String(result, StandardCharsets.ISO_8859_1)).getList();
        } catch (RuntimeException e) {
            throw new PerlCompilerException("Error in crypto_scrypt: " + e.getMessage());
        }
    }

    public static RuntimeList _scrypt_random_bytes(RuntimeArray args, int ctx) {
        int length = args.isEmpty() ? 32 : args.get(0).getInt();
        byte[] output = new byte[Math.max(0, length)];
        RANDOM.nextBytes(output);
        return new RuntimeScalar(new String(output, StandardCharsets.ISO_8859_1)).getList();
    }

    private static byte[] bytes(RuntimeScalar scalar) {
        return scalar.toString().getBytes(StandardCharsets.ISO_8859_1);
    }
}
