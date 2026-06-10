package org.perlonjava.runtime.perlmodule;

import org.perlonjava.frontend.parser.StringParser;
import org.perlonjava.runtime.io.ClosedIOHandle;
import org.perlonjava.runtime.runtimetypes.*;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarFalse;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarUndef;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.JAVAOBJECT;

/**
 * Digest::MD2 XS-compatible implementation for PerlOnJava.
 */
public class DigestMD2 extends PerlModuleBase {

    private static final String CLASS_NAME = "Digest::MD2";
    private static final String STATE_KEY = "_md2_state";

    private static final int[] S = {
            41, 46, 67, 201, 162, 216, 124, 1, 61, 54, 84, 161, 236, 240, 6,
            19, 98, 167, 5, 243, 192, 199, 115, 140, 152, 147, 43, 217, 188,
            76, 130, 202, 30, 155, 87, 60, 253, 212, 224, 22, 103, 66, 111, 24,
            138, 23, 229, 18, 190, 78, 196, 214, 218, 158, 222, 73, 160, 251,
            245, 142, 187, 47, 238, 122, 169, 104, 121, 145, 21, 178, 7, 63,
            148, 194, 16, 137, 11, 34, 95, 33, 128, 127, 93, 154, 90, 144, 50,
            39, 53, 62, 204, 231, 191, 247, 151, 3, 255, 25, 48, 179, 72, 165,
            181, 209, 215, 94, 146, 42, 172, 86, 170, 198, 79, 184, 56, 210,
            150, 164, 125, 182, 118, 252, 107, 226, 156, 116, 4, 241, 69, 157,
            112, 89, 100, 113, 135, 32, 134, 91, 207, 101, 230, 45, 168, 2, 27,
            96, 37, 173, 174, 176, 185, 246, 28, 70, 97, 105, 52, 64, 126, 15,
            85, 71, 163, 35, 221, 81, 175, 58, 195, 92, 249, 206, 186, 197,
            234, 38, 44, 83, 13, 110, 133, 40, 132, 9, 211, 223, 205, 244, 65,
            129, 77, 82, 106, 220, 55, 200, 108, 193, 171, 250, 36, 225, 123,
            8, 12, 189, 177, 74, 120, 136, 149, 139, 227, 99, 232, 109, 233,
            203, 213, 254, 59, 0, 29, 57, 242, 239, 183, 14, 102, 88, 208, 228,
            166, 119, 114, 248, 235, 117, 75, 10, 49, 68, 80, 180, 143, 237,
            31, 26, 219, 153, 141, 51, 159, 17, 131, 20
    };

    public DigestMD2() {
        super(CLASS_NAME, false);
    }

    public static void initialize() {
        DigestMD2 md2 = new DigestMD2();
        GlobalVariable.getGlobalVariable("Digest::MD2::VERSION").set(new RuntimeScalar("2.04"));
        try {
            md2.registerMethod("new", "newInstance", null);
            md2.registerMethod("clone", null);
            md2.registerMethod("add", null);
            md2.registerMethod("addfile", null);
            md2.registerMethod("digest", null);
            md2.registerMethod("hexdigest", null);
            md2.registerMethod("b64digest", null);
            md2.registerMethod("reset", null);
            md2.registerMethod("md2", null);
            md2.registerMethod("md2_hex", null);
            md2.registerMethod("md2_base64", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Digest::MD2 method: " + e.getMessage());
        }
    }

    public static RuntimeList newInstance(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return scalarUndef.getList();
        }
        RuntimeScalar invocant = args.get(0);
        if (invocant.value instanceof RuntimeHash) {
            RuntimeHash self = invocant.hashDeref();
            self.put(STATE_KEY, new RuntimeScalar(new MD2State()));
            return invocant.getList();
        }

        String className = invocant.toString();
        if (className.isEmpty()) {
            className = CLASS_NAME;
        }
        RuntimeHash self = new RuntimeHash();
        self.blessId = NameNormalizer.getBlessId(className);
        self.put(STATE_KEY, new RuntimeScalar(new MD2State()));
        return self.createReference().getList();
    }

    public static RuntimeList clone(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return scalarUndef.getList();
        }
        RuntimeHash self = args.get(0).hashDeref();
        RuntimeHash copy = new RuntimeHash();
        copy.blessId = self.blessId;
        copy.put(STATE_KEY, new RuntimeScalar(new MD2State(getState(args.get(0)))));
        return copy.createReference().getList();
    }

    public static RuntimeList reset(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return scalarFalse.getList();
        }
        RuntimeHash self = args.get(0).hashDeref();
        self.put(STATE_KEY, new RuntimeScalar(new MD2State()));
        return args.get(0).getList();
    }

    public static RuntimeList add(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return scalarFalse.getList();
        }
        MD2State state = getState(args.get(0));
        for (int i = 1; i < args.size(); i++) {
            RuntimeScalar data = args.get(i);
            if (data.type == RuntimeScalarType.UNDEF) {
                continue;
            }
            String str = data.toString();
            StringParser.assertNoWideCharacters(str, "add");
            state.update(str.getBytes(StandardCharsets.ISO_8859_1));
        }
        return args.get(0).getList();
    }

    public static RuntimeList addfile(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            throw new PerlCompilerException("No filehandle passed");
        }
        MD2State state = getState(args.get(0));
        RuntimeScalar fileArg = args.get(1);
        RuntimeIO fh = RuntimeIO.getRuntimeIO(fileArg);
        if (fh == null) {
            String name = globLeafName(fileArg);
            if (name != null) {
                throw new PerlCompilerException("Bad filehandle: " + name);
            }
            throw new PerlCompilerException("No filehandle passed");
        }
        if (fh.ioHandle == null || fh.ioHandle instanceof ClosedIOHandle) {
            throw new PerlCompilerException("No filehandle passed");
        }

        fh.binmode(":raw");
        while (true) {
            RuntimeScalar result = fh.ioHandle.read(4096);
            if (result.type == RuntimeScalarType.UNDEF || result.toString().isEmpty()) {
                break;
            }
            state.update(result.toString().getBytes(StandardCharsets.ISO_8859_1));
        }
        return args.get(0).getList();
    }

    public static RuntimeList digest(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return scalarUndef.getList();
        }
        return new RuntimeScalar(bytesToLatin1(finishAndReset(args.get(0)))).getList();
    }

    public static RuntimeList hexdigest(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return scalarUndef.getList();
        }
        return new RuntimeScalar(toHex(finishAndReset(args.get(0)))).getList();
    }

    public static RuntimeList b64digest(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return scalarUndef.getList();
        }
        return new RuntimeScalar(toB64NoPad(finishAndReset(args.get(0)))).getList();
    }

    public static RuntimeList md2(RuntimeArray args, int ctx) {
        return new RuntimeScalar(bytesToLatin1(digestArgs(args))).getList();
    }

    public static RuntimeList md2_hex(RuntimeArray args, int ctx) {
        return new RuntimeScalar(toHex(digestArgs(args))).getList();
    }

    public static RuntimeList md2_base64(RuntimeArray args, int ctx) {
        return new RuntimeScalar(toB64NoPad(digestArgs(args))).getList();
    }

    private static MD2State getState(RuntimeScalar selfRef) {
        RuntimeHash self = selfRef.hashDeref();
        RuntimeScalar stored = self.get(STATE_KEY);
        if (stored != null && stored.type == JAVAOBJECT && stored.value instanceof MD2State state) {
            return state;
        }
        MD2State state = new MD2State();
        self.put(STATE_KEY, new RuntimeScalar(state));
        return state;
    }

    private static byte[] finishAndReset(RuntimeScalar selfRef) {
        MD2State state = getState(selfRef);
        byte[] digest = state.finish();
        state.reset();
        return digest;
    }

    private static byte[] digestArgs(RuntimeArray args) {
        MD2State state = new MD2State();
        for (int i = 0; i < args.size(); i++) {
            RuntimeScalar data = args.get(i);
            if (data.type == RuntimeScalarType.UNDEF) {
                continue;
            }
            String str = data.toString();
            StringParser.assertNoWideCharacters(str, "md2");
            state.update(str.getBytes(StandardCharsets.ISO_8859_1));
        }
        return state.finish();
    }

    private static String globLeafName(RuntimeScalar scalar) {
        if (scalar.value instanceof RuntimeGlob glob && glob.globName != null) {
            int idx = glob.globName.lastIndexOf("::");
            return idx >= 0 ? glob.globName.substring(idx + 2) : glob.globName;
        }
        return null;
    }

    private static String bytesToLatin1(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private static String toB64NoPad(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes).replaceAll("=+$", "");
    }

    private static final class MD2State {
        private final int[] state = new int[16];
        private final int[] checksum = new int[16];
        private final int[] buffer = new int[16];
        private int count;

        MD2State() {
        }

        MD2State(MD2State other) {
            System.arraycopy(other.state, 0, state, 0, state.length);
            System.arraycopy(other.checksum, 0, checksum, 0, checksum.length);
            System.arraycopy(other.buffer, 0, buffer, 0, buffer.length);
            count = other.count;
        }

        void reset() {
            Arrays.fill(state, 0);
            Arrays.fill(checksum, 0);
            Arrays.fill(buffer, 0);
            count = 0;
        }

        void update(byte[] input) {
            int index = count;
            count = (index + input.length) & 0x0f;
            int partLen = 16 - index;
            int i;

            if (input.length >= partLen) {
                for (int j = 0; j < partLen; j++) {
                    buffer[index + j] = input[j] & 0xff;
                }
                transform(buffer);

                for (i = partLen; i + 15 < input.length; i += 16) {
                    int[] block = new int[16];
                    for (int j = 0; j < 16; j++) {
                        block[j] = input[i + j] & 0xff;
                    }
                    transform(block);
                }
                index = 0;
            } else {
                i = 0;
            }

            for (; i < input.length; i++) {
                buffer[index++] = input[i] & 0xff;
            }
        }

        byte[] finish() {
            int padLen = 16 - count;
            byte[] padding = new byte[padLen];
            Arrays.fill(padding, (byte) padLen);
            update(padding);

            byte[] checksumBytes = new byte[16];
            for (int i = 0; i < 16; i++) {
                checksumBytes[i] = (byte) checksum[i];
            }
            update(checksumBytes);

            byte[] digest = new byte[16];
            for (int i = 0; i < 16; i++) {
                digest[i] = (byte) state[i];
            }
            return digest;
        }

        private void transform(int[] block) {
            int[] x = new int[48];
            for (int i = 0; i < 16; i++) {
                x[i] = state[i];
                x[i + 16] = block[i];
                x[i + 32] = state[i] ^ block[i];
            }

            int t = 0;
            for (int i = 0; i < 18; i++) {
                for (int j = 0; j < 48; j++) {
                    x[j] ^= S[t];
                    t = x[j] & 0xff;
                }
                t = (t + i) & 0xff;
            }

            System.arraycopy(x, 0, state, 0, 16);

            t = checksum[15];
            for (int i = 0; i < 16; i++) {
                checksum[i] ^= S[block[i] ^ t];
                checksum[i] &= 0xff;
                t = checksum[i];
            }
        }
    }
}
