package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.operators.Pack;
import org.perlonjava.runtime.operators.ReferenceOperators;
import org.perlonjava.runtime.operators.Unpack;
import org.perlonjava.runtime.runtimetypes.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Java replacement for the XS storage engine used by Tie::Array::Packed. */
public final class TieArrayPacked extends PerlModuleBase {
    private static final String MODULE = "Tie::Array::Packed";

    /** The XS implementation attaches the pack format as scalar magic. */
    private static final class PackedScalar extends RuntimeScalar {
        private final String packer;
        private final int elementSize;

        private PackedScalar(String packer, String initial) {
            super(initial.getBytes(StandardCharsets.ISO_8859_1));
            this.packer = packer;
            this.elementSize = packOne(packer, new RuntimeScalar(0)).length();
            if (elementSize == 0) {
                throw new PerlCompilerException("invalid/unsupported packing type " + packer);
            }
        }
    }

    public TieArrayPacked() {
        super(MODULE, false);
    }

    public static void initialize() {
        TieArrayPacked module = new TieArrayPacked();
        try {
            for (String method : new String[] {
                    "TIEARRAY", "STORE", "FETCH", "FETCHSIZE", "STORESIZE", "EXTEND",
                    "EXISTS", "DELETE", "CLEAR", "PUSH", "POP", "SHIFT", "UNSHIFT",
                    "SPLICE", "packer", "element_size", "reverse", "rotate", "bsearch"
            }) {
                module.registerMethod(method, null);
            }
            module.registerMethod("bsearch_le", "bsearchLe", null);
            module.registerMethod("bsearch_ge", "bsearchGe", null);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Unable to initialize " + MODULE, e);
        }
    }

    public static RuntimeList TIEARRAY(RuntimeArray args, int ctx) {
        if (args.size() < 3) {
            throw new PerlCompilerException("Usage: Tie::Array::Packed::TIEARRAY(class, type, initial)");
        }
        String packer = normalizePacker(args.get(1).toString());
        String initial = args.get(2).toString();
        PackedScalar data = new PackedScalar(packer, initial);
        RuntimeScalar self = data.createReference();
        ReferenceOperators.bless(self, args.get(0));
        return self.getList();
    }

    public static RuntimeList STORE(RuntimeArray args, int ctx) {
        PackedScalar data = data(args);
        int index = checkedIndex(args.get(1));
        List<RuntimeScalar> values = values(data);
        while (values.size() <= index) values.add(new RuntimeScalar(0));
        values.set(index, coerce(data, args.get(2)));
        storeValues(data, values);
        return new RuntimeList();
    }

    public static RuntimeList FETCH(RuntimeArray args, int ctx) {
        PackedScalar data = data(args);
        int index = checkedIndex(args.get(1));
        List<RuntimeScalar> values = values(data);
        return (index < values.size() ? values.get(index) : RuntimeScalarCache.scalarUndef).getList();
    }

    public static RuntimeList FETCHSIZE(RuntimeArray args, int ctx) {
        PackedScalar data = data(args);
        return new RuntimeScalar(data.toString().length() / data.elementSize).getList();
    }

    public static RuntimeList STORESIZE(RuntimeArray args, int ctx) {
        PackedScalar data = data(args);
        int size = checkedIndex(args.get(1));
        List<RuntimeScalar> values = values(data);
        while (values.size() < size) values.add(new RuntimeScalar(0));
        if (values.size() > size) values = new ArrayList<>(values.subList(0, size));
        storeValues(data, values);
        return new RuntimeList();
    }

    public static RuntimeList EXTEND(RuntimeArray args, int ctx) {
        checkedIndex(args.get(1));
        return new RuntimeList();
    }

    public static RuntimeList EXISTS(RuntimeArray args, int ctx) {
        PackedScalar data = data(args);
        int index = checkedIndex(args.get(1));
        return (index < data.toString().length() / data.elementSize
                ? RuntimeScalarCache.scalarTrue : RuntimeScalarCache.scalarUndef).getList();
    }

    public static RuntimeList DELETE(RuntimeArray args, int ctx) {
        PackedScalar data = data(args);
        int index = checkedIndex(args.get(1));
        List<RuntimeScalar> values = values(data);
        if (index >= values.size()) return RuntimeScalarCache.scalarUndef.getList();
        RuntimeScalar old = values.get(index);
        values.set(index, new RuntimeScalar(0));
        storeValues(data, values);
        return old.getList();
    }

    public static RuntimeList CLEAR(RuntimeArray args, int ctx) {
        data(args).set(new RuntimeScalar(new byte[0]));
        return new RuntimeList();
    }

    public static RuntimeList PUSH(RuntimeArray args, int ctx) {
        PackedScalar data = data(args);
        List<RuntimeScalar> values = values(data);
        for (int i = 1; i < args.size(); i++) values.add(coerce(data, args.get(i)));
        storeValues(data, values);
        return new RuntimeList();
    }

    public static RuntimeList POP(RuntimeArray args, int ctx) {
        PackedScalar data = data(args);
        List<RuntimeScalar> values = values(data);
        if (values.isEmpty()) return RuntimeScalarCache.scalarUndef.getList();
        RuntimeScalar value = values.removeLast();
        storeValues(data, values);
        return value.getList();
    }

    public static RuntimeList SHIFT(RuntimeArray args, int ctx) {
        PackedScalar data = data(args);
        List<RuntimeScalar> values = values(data);
        if (values.isEmpty()) return RuntimeScalarCache.scalarUndef.getList();
        RuntimeScalar value = values.removeFirst();
        storeValues(data, values);
        return value.getList();
    }

    public static RuntimeList UNSHIFT(RuntimeArray args, int ctx) {
        PackedScalar data = data(args);
        List<RuntimeScalar> values = values(data);
        for (int i = args.size() - 1; i >= 1; i--) values.addFirst(coerce(data, args.get(i)));
        storeValues(data, values);
        return new RuntimeList();
    }

    public static RuntimeList SPLICE(RuntimeArray args, int ctx) {
        PackedScalar data = data(args);
        List<RuntimeScalar> values = values(data);
        int offset = Math.min(checkedIndex(args.get(1)), values.size());
        int length = Math.min(checkedIndex(args.get(2)), values.size() - offset);
        List<RuntimeScalar> removed = new ArrayList<>(values.subList(offset, offset + length));
        values.subList(offset, offset + length).clear();
        for (int i = 3; i < args.size(); i++) {
            values.add(offset++, coerce(data, args.get(i)));
        }
        storeValues(data, values);
        if (ctx == RuntimeContextType.LIST) return new RuntimeList(removed.toArray(new RuntimeBase[0]));
        if (ctx == RuntimeContextType.SCALAR) {
            return (removed.isEmpty() ? RuntimeScalarCache.scalarUndef : removed.getLast()).getList();
        }
        return new RuntimeList();
    }

    public static RuntimeList packer(RuntimeArray args, int ctx) {
        return new RuntimeScalar(data(args).packer).getList();
    }

    public static RuntimeList element_size(RuntimeArray args, int ctx) {
        return new RuntimeScalar(data(args).elementSize).getList();
    }

    public static RuntimeList reverse(RuntimeArray args, int ctx) {
        PackedScalar data = data(args);
        List<RuntimeScalar> values = values(data);
        Collections.reverse(values);
        storeValues(data, values);
        return new RuntimeList();
    }

    public static RuntimeList rotate(RuntimeArray args, int ctx) {
        PackedScalar data = data(args);
        List<RuntimeScalar> values = values(data);
        if (values.isEmpty()) return new RuntimeList();
        int amount = args.size() > 1 ? args.get(1).getInt() : 1;
        amount %= values.size();
        if (amount < 0) amount += values.size();
        Collections.rotate(values, -amount);
        storeValues(data, values);
        return new RuntimeList();
    }

    public static RuntimeList bsearch(RuntimeArray args, int ctx) {
        return search(args, 0);
    }

    public static RuntimeList bsearchLe(RuntimeArray args, int ctx) {
        return search(args, -1);
    }

    public static RuntimeList bsearchGe(RuntimeArray args, int ctx) {
        return search(args, 1);
    }

    private static RuntimeList search(RuntimeArray args, int fallback) {
        PackedScalar data = data(args);
        List<RuntimeScalar> values = values(data);
        double wanted = coerce(data, args.get(1)).getDouble();
        int low = 0;
        int high = values.size();
        while (low < high) {
            int pivot = (low + high) >>> 1;
            double value = values.get(pivot).getDouble();
            if (value < wanted) low = pivot + 1;
            else if (value > wanted) high = pivot;
            else return new RuntimeScalar(pivot).getList();
        }
        int index = fallback < 0 ? low - 1 : low;
        if (fallback == 0 || index < 0 || index >= values.size()) {
            return RuntimeScalarCache.scalarUndef.getList();
        }
        return new RuntimeScalar(index).getList();
    }

    private static PackedScalar data(RuntimeArray args) {
        if (args.isEmpty()) throw new PerlCompilerException("Tie::Array::Packed method called without an object");
        RuntimeScalar scalar = args.get(0).scalarDeref();
        if (!(scalar instanceof PackedScalar packed)) {
            throw new PerlCompilerException("invalid Tie::Array::Packed object");
        }
        return packed;
    }

    private static int checkedIndex(RuntimeScalar scalar) {
        long value = scalar.getLong();
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw new PerlCompilerException("Modification of non-creatable array value attempted");
        }
        return (int) value;
    }

    private static String normalizePacker(String packer) {
        if (packer.length() == 1 && "cChFfdiIjJnNvVqQeE".contains(packer)) return packer;
        if (packer.length() == 2 && packer.charAt(1) == '!' && "sSlL".indexOf(packer.charAt(0)) >= 0) {
            return packer;
        }
        throw new PerlCompilerException("invalid/unsupported packing type " + packer);
    }

    private static RuntimeScalar coerce(PackedScalar data, RuntimeScalar value) {
        return unpackOne(data.packer, packOne(data.packer, value));
    }

    private static String packOne(String packer, RuntimeScalar value) {
        if (packer.equals("h")) {
            int nibble = value.getInt() & 15;
            return Character.toString(Character.forDigit(nibble, 16));
        }
        RuntimeList packArgs = new RuntimeList(new RuntimeScalar(packer), value);
        return Pack.pack(packArgs).toString();
    }

    private static RuntimeScalar unpackOne(String packer, String bytes) {
        if (packer.equals("h")) {
            int value = Character.digit(bytes.isEmpty() ? '0' : bytes.charAt(0), 16);
            return new RuntimeScalar(Math.max(value, 0));
        }
        RuntimeList unpacked = Unpack.unpack(RuntimeContextType.LIST,
                new RuntimeScalar(packer), new RuntimeScalar(bytes.getBytes(StandardCharsets.ISO_8859_1)));
        return unpacked.isEmpty() ? RuntimeScalarCache.scalarUndef : unpacked.getFirst();
    }

    private static List<RuntimeScalar> values(PackedScalar data) {
        String raw = data.toString();
        int count = raw.length() / data.elementSize;
        List<RuntimeScalar> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int offset = i * data.elementSize;
            values.add(unpackOne(data.packer, raw.substring(offset, offset + data.elementSize)));
        }
        return values;
    }

    private static void storeValues(PackedScalar data, List<RuntimeScalar> values) {
        StringBuilder raw = new StringBuilder(values.size() * data.elementSize);
        for (RuntimeScalar value : values) raw.append(packOne(data.packer, value));
        data.set(new RuntimeScalar(raw.toString().getBytes(StandardCharsets.ISO_8859_1)));
    }
}
