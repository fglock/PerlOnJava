package org.perlonjava.runtime.perlmodule;

import org.perlonjava.frontend.parser.StringParser;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;

import java.nio.charset.StandardCharsets;

/** Java replacement for the small XS core of Digest::JHash. */
public class DigestJHash extends PerlModuleBase {
    private static final int GOLDEN_RATIO = 0x9e3779b9;

    public DigestJHash() {
        super("Digest::JHash", false);
    }

    public static void initialize() {
        DigestJHash module = new DigestJHash();
        GlobalVariable.getGlobalVariable("Digest::JHash::VERSION").set(new RuntimeScalar("0.10"));
        try {
            module.registerMethod("jhash", "$");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Digest::JHash method: " + e.getMessage());
        }
    }

    public static RuntimeList jhash(RuntimeArray args, int ctx) {
        if (args.isEmpty() || args.get(0).type == RuntimeScalarType.UNDEF) {
            return new RuntimeScalar(0L).getList();
        }

        String value = args.get(0).toString();
        StringParser.assertNoWideCharacters(value, "jhash");
        byte[] data = value.getBytes(StandardCharsets.ISO_8859_1);
        if (data.length == 0) {
            return new RuntimeScalar(0L).getList();
        }

        int a = GOLDEN_RATIO;
        int b = GOLDEN_RATIO;
        int c = 0;
        int offset = 0;
        int remaining = data.length;

        while (remaining >= 12) {
            a += word(data, offset);
            b += word(data, offset + 4);
            c += word(data, offset + 8);
            int[] mixed = mix(a, b, c);
            a = mixed[0];
            b = mixed[1];
            c = mixed[2];
            offset += 12;
            remaining -= 12;
        }

        c += data.length;
        switch (remaining) {
            case 11: c += data[offset + 10] << 24;
            case 10: c += data[offset + 9] << 16;
            case 9:  c += data[offset + 8] << 8;
            case 8:  b += data[offset + 7] << 24;
            case 7:  b += data[offset + 6] << 16;
            case 6:  b += data[offset + 5] << 8;
            case 5:  b += data[offset + 4];
            case 4:  a += data[offset + 3] << 24;
            case 3:  a += data[offset + 2] << 16;
            case 2:  a += data[offset + 1] << 8;
            case 1:  a += data[offset];
            default: break;
        }
        c = mix(a, b, c)[2];
        return new RuntimeScalar(Integer.toUnsignedLong(c)).getList();
    }

    private static int word(byte[] data, int offset) {
        return data[offset]
                + (data[offset + 1] << 8)
                + (data[offset + 2] << 16)
                + (data[offset + 3] << 24);
    }

    private static int[] mix(int a, int b, int c) {
        a -= b; a -= c; a ^= c >>> 13;
        b -= c; b -= a; b ^= a << 8;
        c -= a; c -= b; c ^= b >>> 13;
        a -= b; a -= c; a ^= c >>> 12;
        b -= c; b -= a; b ^= a << 16;
        c -= a; c -= b; c ^= b >>> 5;
        a -= b; a -= c; a ^= c >>> 3;
        b -= c; b -= a; b ^= a << 10;
        c -= a; c -= b; c ^= b >>> 15;
        return new int[] {a, b, c};
    }
}
