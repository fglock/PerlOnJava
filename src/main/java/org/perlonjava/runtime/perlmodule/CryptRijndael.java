package org.perlonjava.runtime.perlmodule;

import org.perlonjava.frontend.parser.StringParser;
import org.perlonjava.runtime.operators.ReferenceOperators;
import org.perlonjava.runtime.runtimetypes.PerlCompilerException;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeHash;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/** Java XS replacement for Crypt::Rijndael 1.16, using the JCA AES provider. */
public final class CryptRijndael extends PerlModuleBase {
    private static final String CLASS_NAME = "Crypt::Rijndael";
    private static final String STATE_KEY = "_rijndael_state";
    private static final int BLOCK_SIZE = 16;
    private static final int MODE_ECB = 1;
    private static final int MODE_CBC = 2;
    private static final int MODE_CFB = 3;
    private static final int MODE_PCBC = 4;
    private static final int MODE_OFB = 5;
    private static final int MODE_CTR = 6;

    public CryptRijndael() {
        super(CLASS_NAME, false);
    }

    public static void initialize() {
        CryptRijndael module = new CryptRijndael();
        try {
            module.registerMethod("new", "new_", null);
            module.registerMethod("keysize", null);
            module.registerMethod("blocksize", null);
            module.registerMethod("set_iv", null);
            module.registerMethod("encrypt", null);
            module.registerMethod("decrypt", null);
            module.registerMethod("DESTROY", null);
            module.registerMethod("MODE_ECB", null);
            module.registerMethod("MODE_CBC", null);
            module.registerMethod("MODE_CFB", null);
            module.registerMethod("MODE_PCBC", null);
            module.registerMethod("MODE_OFB", null);
            module.registerMethod("MODE_CTR", null);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Unable to initialize " + CLASS_NAME, e);
        }
    }

    public static RuntimeList keysize(RuntimeArray args, int ctx) { return scalar(32); }
    public static RuntimeList blocksize(RuntimeArray args, int ctx) { return scalar(BLOCK_SIZE); }
    public static RuntimeList MODE_ECB(RuntimeArray args, int ctx) { return scalar(MODE_ECB); }
    public static RuntimeList MODE_CBC(RuntimeArray args, int ctx) { return scalar(MODE_CBC); }
    public static RuntimeList MODE_CFB(RuntimeArray args, int ctx) { return scalar(MODE_CFB); }
    public static RuntimeList MODE_PCBC(RuntimeArray args, int ctx) { return scalar(MODE_PCBC); }
    public static RuntimeList MODE_OFB(RuntimeArray args, int ctx) { return scalar(MODE_OFB); }
    public static RuntimeList MODE_CTR(RuntimeArray args, int ctx) { return scalar(MODE_CTR); }

    public static RuntimeList new_(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            throw new PerlCompilerException("Usage: Crypt::Rijndael::new(class, key, mode=MODE_ECB)");
        }
        RuntimeScalar keyArg = args.get(1);
        if (keyArg.type != RuntimeScalarType.STRING && keyArg.type != RuntimeScalarType.BYTE_STRING) {
            throw new PerlCompilerException("Key must be an string scalar");
        }
        byte[] key = bytes(keyArg, "new");
        if (key.length != 16 && key.length != 24 && key.length != 32) {
            throw new PerlCompilerException("Wrong key length: key must be 128, 192 or 256 bits long");
        }
        int mode = args.size() > 2 ? args.get(2).getInt() : MODE_ECB;
        if (mode != MODE_ECB && mode != MODE_CBC && mode != MODE_CFB && mode != MODE_OFB && mode != MODE_CTR) {
            throw new PerlCompilerException("Illegal mode, see documentation for valid modes");
        }
        RuntimeHash self = new RuntimeHash();
        self.put(STATE_KEY, new RuntimeScalar(new State(key, mode)));
        RuntimeScalar ref = self.createReference();
        String className = args.get(0).toString();
        ReferenceOperators.bless(ref, new RuntimeScalar(className.isEmpty() ? CLASS_NAME : className));
        return ref.getList();
    }

    public static RuntimeList set_iv(RuntimeArray args, int ctx) {
        if (args.size() < 2) throw new PerlCompilerException("Usage: Crypt::Rijndael::set_iv(self, data)");
        byte[] iv = bytes(args.get(1), "set_iv");
        if (iv.length != BLOCK_SIZE) throw new PerlCompilerException("set_iv: IV must be 16 bytes long");
        State state = state(args.get(0));
        System.arraycopy(iv, 0, state.iv, 0, BLOCK_SIZE);
        return args.get(0).getList();
    }

    public static RuntimeList encrypt(RuntimeArray args, int ctx) { return crypt(args, true); }
    public static RuntimeList decrypt(RuntimeArray args, int ctx) { return crypt(args, false); }
    public static RuntimeList DESTROY(RuntimeArray args, int ctx) { return new RuntimeList(); }

    private static RuntimeList crypt(RuntimeArray args, boolean encrypt) {
        if (args.size() < 2) {
            throw new PerlCompilerException("Usage: Crypt::Rijndael::" + (encrypt ? "encrypt" : "decrypt") + "(self, data, iv=self->iv)");
        }
        State state = state(args.get(0));
        byte[] input = bytes(args.get(1), encrypt ? "encrypt" : "decrypt");
        if ((state.mode == MODE_ECB || state.mode == MODE_CBC) && input.length % BLOCK_SIZE != 0) {
            throw new PerlCompilerException("encrypt: datasize not multiple of blocksize (16 bytes)");
        }
        byte[] iv = args.size() > 2 ? bytes(args.get(2), encrypt ? "encrypt" : "decrypt") : state.iv;
        if (state.mode != MODE_ECB && iv.length != BLOCK_SIZE) {
            throw new PerlCompilerException("encrypt: IV must be 16 bytes long");
        }
        try {
            Cipher cipher = Cipher.getInstance(transformation(state.mode));
            SecretKeySpec key = new SecretKeySpec(state.key, "AES");
            if (state.mode == MODE_ECB) cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, key);
            else cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, key, new IvParameterSpec(iv));
            return new RuntimeScalar(cipher.doFinal(input)).getList();
        } catch (GeneralSecurityException e) {
            throw new PerlCompilerException("Crypt::Rijndael " + (encrypt ? "encrypt" : "decrypt") + " failed: " + e.getMessage());
        }
    }

    private static String transformation(int mode) {
        return switch (mode) {
            case MODE_ECB -> "AES/ECB/NoPadding";
            case MODE_CBC -> "AES/CBC/NoPadding";
            case MODE_CFB -> "AES/CFB/NoPadding";
            case MODE_OFB -> "AES/OFB/NoPadding";
            case MODE_CTR -> "AES/CTR/NoPadding";
            default -> throw new PerlCompilerException("Illegal mode, see documentation for valid modes");
        };
    }

    private static byte[] bytes(RuntimeScalar value, String operation) {
        String string = value.toString();
        StringParser.assertNoWideCharacters(string, operation);
        return string.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static State state(RuntimeScalar selfRef) {
        RuntimeScalar stored = selfRef.hashDeref().get(STATE_KEY);
        if (stored != null && stored.type == RuntimeScalarType.JAVAOBJECT && stored.value instanceof State state) return state;
        throw new PerlCompilerException("Crypt::Rijndael object has invalid state");
    }

    private static RuntimeList scalar(int value) { return new RuntimeScalar(value).getList(); }

    private static final class State {
        private final byte[] key;
        private final int mode;
        private final byte[] iv = new byte[BLOCK_SIZE];

        private State(byte[] key, int mode) {
            this.key = Arrays.copyOf(key, key.length);
            this.mode = mode;
        }
    }
}
