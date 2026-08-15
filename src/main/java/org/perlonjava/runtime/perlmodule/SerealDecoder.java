package org.perlonjava.runtime.perlmodule;

import com.booking.sereal.Decoder;
import com.booking.sereal.DecoderOptions;
import org.perlonjava.runtime.operators.ReferenceOperators;
import org.perlonjava.runtime.operators.WarnDie;
import org.perlonjava.runtime.runtimetypes.*;

import java.nio.charset.StandardCharsets;

/** Sereal::Decoder XS compatibility layer backed by Sereal's official Java codec. */
public class SerealDecoder extends PerlModuleBase {
    private static final String CLASS_NAME = "Sereal::Decoder";
    private static final String STATE_KEY = "_sereal_decoder";

    public SerealDecoder() { super(CLASS_NAME, false); }

    public static void initialize() {
        SerealDecoder module = new SerealDecoder();
        try {
            module.registerMethod("new", "new_", null);
            module.registerMethod("decode", "decode_sereal", null);
            module.registerMethod("decode_sereal", null);
            module.registerMethod("sereal_decode_with_object", null);
            module.registerMethod("looks_like_sereal", null);
            module.registerMethod("scalar_looks_like_sereal", null);
            module.registerMethod("bytes_consumed", null);
            module.registerMethod("flags", null);
            module.registerMethod("DESTROY", "destroy", null);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Decoder newDecoder() {
        return new Decoder(new DecoderOptions().perlReferences(true).preserveUndef(true));
    }

    public static RuntimeList new_(RuntimeArray args, int ctx) {
        RuntimeHash hash = new RuntimeHash();
        hash.put(STATE_KEY, new RuntimeScalar(newDecoder()));
        RuntimeScalar ref = hash.createAnonymousReference();
        ReferenceOperators.bless(ref, new RuntimeScalar(CLASS_NAME));
        return ref.getList();
    }

    public static RuntimeList decode_sereal(RuntimeArray args, int ctx) {
        int blobIndex = isObject(args) ? 1 : 0;
        Decoder decoder = isObject(args) ? state(args.get(0)) : newDecoder();
        return decode(decoder, args, blobIndex);
    }

    public static RuntimeList sereal_decode_with_object(RuntimeArray args, int ctx) {
        return decode(state(args.get(0)), args, 1);
    }

    private static RuntimeList decode(Decoder decoder, RuntimeArray args, int blobIndex) {
        if (args.size() <= blobIndex) return WarnDie.die(
                new RuntimeScalar("Usage: decode_sereal(blob)"), new RuntimeScalar("\n")).getList();
        try {
            byte[] bytes = args.get(blobIndex).toString().getBytes(StandardCharsets.ISO_8859_1);
            decoder.setData(bytes);
            return SerealRuntimeConverter.fromJava(decoder.decode()).getList();
        } catch (Exception e) {
            return WarnDie.die(new RuntimeScalar("Sereal decode failed: " + e.getMessage()),
                    new RuntimeScalar("\n")).getList();
        }
    }

    public static RuntimeList looks_like_sereal(RuntimeArray args, int ctx) {
        int index = isObject(args) ? 1 : 0;
        return likely(args, index);
    }

    public static RuntimeList scalar_looks_like_sereal(RuntimeArray args, int ctx) { return likely(args, 0); }

    private static RuntimeList likely(RuntimeArray args, int index) {
        if (args.size() <= index) return new RuntimeScalar(0).getList();
        String value = args.get(index).toString();
        boolean match = value.length() >= 4 && value.charAt(0) == '='
                && (value.substring(1, 4).equals("srl") || value.charAt(1) == (char) 0xF3);
        return new RuntimeScalar(match).getList();
    }

    public static RuntimeList bytes_consumed(RuntimeArray args, int ctx) { return new RuntimeScalar(0).getList(); }
    public static RuntimeList flags(RuntimeArray args, int ctx) { return new RuntimeScalar(0).getList(); }
    public static RuntimeList destroy(RuntimeArray args, int ctx) { return new RuntimeList(); }

    private static boolean isObject(RuntimeArray args) {
        return !args.isEmpty() && RuntimeScalarType.blessedId(args.get(0)) != 0;
    }

    private static Decoder state(RuntimeScalar self) {
        RuntimeScalar stored = self.hashDeref().get(STATE_KEY);
        if (stored != null && stored.type == RuntimeScalarType.JAVAOBJECT && stored.value instanceof Decoder decoder) {
            return decoder;
        }
        WarnDie.die(new RuntimeScalar("Invalid Sereal::Decoder object"), new RuntimeScalar("\n"));
        return null;
    }
}
