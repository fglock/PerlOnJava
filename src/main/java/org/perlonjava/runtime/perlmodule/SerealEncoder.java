package org.perlonjava.runtime.perlmodule;

import com.booking.sereal.Encoder;
import com.booking.sereal.EncoderOptions;
import org.perlonjava.runtime.operators.ReferenceOperators;
import org.perlonjava.runtime.operators.WarnDie;
import org.perlonjava.runtime.runtimetypes.*;

/** Sereal::Encoder XS compatibility layer backed by Sereal's official Java codec. */
public class SerealEncoder extends PerlModuleBase {
    private static final String CLASS_NAME = "Sereal::Encoder";
    private static final String STATE_KEY = "_sereal_encoder";

    public SerealEncoder() { super(CLASS_NAME, false); }

    public static void initialize() {
        SerealEncoder module = new SerealEncoder();
        try {
            module.registerMethod("new", "new_", null);
            module.registerMethod("encode", "encode_sereal", null);
            module.registerMethod("encode_sereal", null);
            module.registerMethod("sereal_encode_with_object", null);
            module.registerMethod("encode_sereal_with_header_data", null);
            module.registerMethod("flags", null);
            module.registerMethod("DESTROY", "destroy", null);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    public static RuntimeList new_(RuntimeArray args, int ctx) {
        RuntimeHash hash = new RuntimeHash();
        hash.put(STATE_KEY, new RuntimeScalar(new Encoder(options())));
        RuntimeScalar ref = hash.createAnonymousReference();
        ReferenceOperators.bless(ref, new RuntimeScalar(CLASS_NAME));
        return ref.getList();
    }

    public static RuntimeList encode_sereal(RuntimeArray args, int ctx) {
        int valueIndex = isObject(args) ? 1 : 0;
        Encoder encoder = isObject(args) ? state(args.get(0)) : new Encoder(options());
        return encode(encoder, args, valueIndex, -1);
    }

    public static RuntimeList sereal_encode_with_object(RuntimeArray args, int ctx) {
        return encode(state(args.get(0)), args, 1, -1);
    }

    public static RuntimeList encode_sereal_with_header_data(RuntimeArray args, int ctx) {
        return encode(new Encoder(options()), args, 0, 1);
    }

    private static RuntimeList encode(Encoder encoder, RuntimeArray args, int valueIndex, int headerIndex) {
        if (args.size() <= valueIndex) return WarnDie.die(
                new RuntimeScalar("Usage: encode_sereal(value)"), new RuntimeScalar("\n")).getList();
        try {
            Object value = SerealRuntimeConverter.toJava(args.get(valueIndex));
            if (headerIndex >= 0 && args.size() > headerIndex) {
                encoder.write(value, SerealRuntimeConverter.toJava(args.get(headerIndex)));
            } else {
                encoder.write(value);
            }
            return SerealRuntimeConverter.byteScalar(encoder.getData()).getList();
        } catch (Exception e) {
            return WarnDie.die(new RuntimeScalar("Sereal encode failed: " + e.getMessage()),
                    new RuntimeScalar("\n")).getList();
        }
    }

    public static RuntimeList flags(RuntimeArray args, int ctx) { return new RuntimeScalar(0).getList(); }
    public static RuntimeList destroy(RuntimeArray args, int ctx) { return new RuntimeList(); }

    private static boolean isObject(RuntimeArray args) {
        return !args.isEmpty() && RuntimeScalarType.blessedId(args.get(0)) != 0;
    }

    private static EncoderOptions options() {
        return new EncoderOptions().protocolVersion(4).perlReferences(true);
    }

    private static Encoder state(RuntimeScalar self) {
        RuntimeScalar stored = self.hashDeref().get(STATE_KEY);
        if (stored != null && stored.type == RuntimeScalarType.JAVAOBJECT && stored.value instanceof Encoder encoder) {
            return encoder;
        }
        WarnDie.die(new RuntimeScalar("Invalid Sereal::Encoder object"), new RuntimeScalar("\n"));
        return null;
    }
}
