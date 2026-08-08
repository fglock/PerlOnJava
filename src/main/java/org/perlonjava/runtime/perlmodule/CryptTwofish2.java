package org.perlonjava.runtime.perlmodule;

import org.bouncycastle.crypto.engines.TwofishEngine;
import org.bouncycastle.crypto.params.KeyParameter;
import org.perlonjava.frontend.parser.StringParser;
import org.perlonjava.runtime.operators.ReferenceOperators;
import org.perlonjava.runtime.runtimetypes.*;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.JAVAOBJECT;

/** Crypt::Twofish2's XS API, implemented with Bouncy Castle's Twofish engine. */
public class CryptTwofish2 extends PerlModuleBase {
    private static final String CLASS_NAME = "Crypt::Twofish2";
    private static final String STATE_KEY = "_twofish2_state";
    private static final int BLOCK_SIZE = 16;
    private static final int MODE_ECB = 1;
    private static final int MODE_CBC = 2;
    private static final int MODE_CFB1 = 3;

    public CryptTwofish2() {
        super(CLASS_NAME, false);
    }

    public static void initialize() {
        CryptTwofish2 module = new CryptTwofish2();
        try {
            module.registerMethod("new", "new_", null);
            module.registerMethod("encrypt", null);
            module.registerMethod("decrypt", null);
            module.registerMethod("DESTROY", null);
            module.registerMethod("keysize", null);
            module.registerMethod("blocksize", null);
            module.registerMethod("MODE_ECB", null);
            module.registerMethod("MODE_CBC", null);
            module.registerMethod("MODE_CFB1", null);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Unable to initialize " + CLASS_NAME, e);
        }
    }

    public static RuntimeList keysize(RuntimeArray args, int ctx) {
        return scalar(32);
    }

    public static RuntimeList blocksize(RuntimeArray args, int ctx) {
        return scalar(BLOCK_SIZE);
    }

    public static RuntimeList MODE_ECB(RuntimeArray args, int ctx) {
        return scalar(MODE_ECB);
    }

    public static RuntimeList MODE_CBC(RuntimeArray args, int ctx) {
        return scalar(MODE_CBC);
    }

    public static RuntimeList MODE_CFB1(RuntimeArray args, int ctx) {
        return scalar(MODE_CFB1);
    }

    public static RuntimeList new_(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            throw new PerlCompilerException("Usage: Crypt::Twofish2::new(class, key, mode=MODE_ECB)");
        }
        RuntimeScalar keyArg = args.get(1);
        if (keyArg.type != RuntimeScalarType.STRING && keyArg.type != RuntimeScalarType.BYTE_STRING) {
            throw new PerlCompilerException("key must be a string scalar");
        }
        String keyString = keyArg.toString();
        StringParser.assertNoWideCharacters(keyString, "new");
        byte[] key = keyString.getBytes(StandardCharsets.ISO_8859_1);
        if (key.length != 16 && key.length != 24 && key.length != 32) {
            throw new PerlCompilerException("wrong key length: key must be 128, 192 or 256 bits long");
        }
        int mode = args.size() > 2 ? args.get(2).getInt() : MODE_ECB;
        if (mode != MODE_ECB && mode != MODE_CBC && mode != MODE_CFB1) {
            throw new PerlCompilerException("illegal mode: mode must be MODE_ECB, MODE_2 or MODE_CFB1");
        }

        RuntimeHash self = new RuntimeHash();
        self.put(STATE_KEY, new RuntimeScalar(new State(key, mode)));
        String className = args.get(0).toString();
        if (className.isEmpty()) className = CLASS_NAME;
        RuntimeScalar ref = self.createReference();
        ReferenceOperators.bless(ref, new RuntimeScalar(className));
        return ref.getList();
    }

    public static RuntimeList encrypt(RuntimeArray args, int ctx) {
        return crypt(args, true);
    }

    public static RuntimeList decrypt(RuntimeArray args, int ctx) {
        return crypt(args, false);
    }

    public static RuntimeList DESTROY(RuntimeArray args, int ctx) {
        return new RuntimeList();
    }

    private static RuntimeList crypt(RuntimeArray args, boolean encrypt) {
        if (args.size() < 2) {
            throw new PerlCompilerException("Usage: Crypt::Twofish2::" + (encrypt ? "encrypt" : "decrypt") + "(self, data)");
        }
        String inputString = args.get(1).toString();
        StringParser.assertNoWideCharacters(inputString, encrypt ? "encrypt" : "decrypt");
        byte[] input = inputString.getBytes(StandardCharsets.ISO_8859_1);
        if (input.length % BLOCK_SIZE != 0) {
            throw new PerlCompilerException("encrypt: datasize not multiple of blocksize (128 bits)");
        }
        State state = state(args.get(0));
        byte[] output = switch (state.mode) {
            case MODE_ECB -> ecb(state.key, input, encrypt);
            case MODE_CBC -> cbc(state, input, encrypt);
            case MODE_CFB1 -> cfb1(state, input, encrypt);
            default -> throw new PerlCompilerException("block(De|En)crypt: unknown error, please report");
        };
        return new RuntimeScalar(new String(output, StandardCharsets.ISO_8859_1)).getList();
    }

    private static byte[] ecb(byte[] key, byte[] input, boolean encrypt) {
        TwofishEngine engine = engine(key, encrypt);
        byte[] output = new byte[input.length];
        for (int offset = 0; offset < input.length; offset += BLOCK_SIZE) {
            engine.processBlock(input, offset, output, offset);
        }
        return output;
    }

    private static byte[] cbc(State state, byte[] input, boolean encrypt) {
        TwofishEngine engine = engine(state.key, encrypt);
        byte[] output = new byte[input.length];
        byte[] block = new byte[BLOCK_SIZE];
        for (int offset = 0; offset < input.length; offset += BLOCK_SIZE) {
            if (encrypt) {
                for (int i = 0; i < BLOCK_SIZE; i++) block[i] = (byte) (input[offset + i] ^ state.iv[i]);
                engine.processBlock(block, 0, output, offset);
                System.arraycopy(output, offset, state.iv, 0, BLOCK_SIZE);
            } else {
                engine.processBlock(input, offset, block, 0);
                for (int i = 0; i < BLOCK_SIZE; i++) output[offset + i] = (byte) (block[i] ^ state.iv[i]);
                System.arraycopy(input, offset, state.iv, 0, BLOCK_SIZE);
            }
        }
        return output;
    }

    /** Reproduce the distribution's MSB-first, one-bit CFB feedback loop. */
    private static byte[] cfb1(State state, byte[] input, boolean encrypt) {
        TwofishEngine engine = engine(state.key, true);
        byte[] output = new byte[input.length];
        byte[] stream = new byte[BLOCK_SIZE];
        for (int bitIndex = 0; bitIndex < input.length * 8; bitIndex++) {
            engine.processBlock(state.iv, 0, stream, 0);
            int byteIndex = bitIndex >>> 3;
            int bitMask = 0x80 >>> (bitIndex & 7);
            int inputBit = (input[byteIndex] & bitMask) == 0 ? 0 : 1;
            int streamBit = (stream[0] & 0x80) == 0 ? 0 : 1;
            int outputBit = inputBit ^ streamBit;
            if (outputBit != 0) output[byteIndex] |= (byte) bitMask;
            shiftFeedback(state.iv, encrypt ? outputBit : inputBit);
        }
        return output;
    }

    private static void shiftFeedback(byte[] iv, int feedbackBit) {
        int carry = feedbackBit;
        for (int i = iv.length - 1; i >= 0; i--) {
            int nextCarry = (iv[i] >>> 7) & 1;
            iv[i] = (byte) ((iv[i] << 1) ^ carry);
            carry = nextCarry;
        }
    }

    private static TwofishEngine engine(byte[] key, boolean encrypt) {
        TwofishEngine engine = new TwofishEngine();
        engine.init(encrypt, new KeyParameter(key));
        return engine;
    }

    private static State state(RuntimeScalar selfRef) {
        RuntimeHash self = selfRef.hashDeref();
        RuntimeScalar stored = self.get(STATE_KEY);
        if (stored != null && stored.type == JAVAOBJECT && stored.value instanceof State state) {
            return state;
        }
        throw new PerlCompilerException("Crypt::Twofish2 object has invalid state");
    }

    private static RuntimeList scalar(int value) {
        return new RuntimeScalar(value).getList();
    }

    private static final class State {
        final byte[] key;
        final int mode;
        final byte[] iv = new byte[BLOCK_SIZE];

        State(byte[] key, int mode) {
            this.key = Arrays.copyOf(key, key.length);
            this.mode = mode;
        }
    }
}
