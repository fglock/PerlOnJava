package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.operators.ReferenceOperators;
import org.perlonjava.runtime.runtimetypes.*;

/**
 * Java replacement for the XS backend of Tie::Hash::Indexed 0.08.
 *
 * <p>The original module is copyright Marcus Holland-Moritz and is available
 * under the same terms as Perl itself. This implementation preserves its
 * public tied-hash and object-oriented APIs while storing the state in ordinary
 * Perl data structures so Clone and Storable can traverse it.</p>
 */
public class TieHashIndexed extends PerlModuleBase {
    private static final String ORDER = "__perlonjava_order";
    private static final String VALUES = "__perlonjava_values";
    private static final String SERIAL = "__perlonjava_serial";
    private static final String TIE_ITER = "__perlonjava_tie_iter";
    private static final String ITER_PARENT = "__perlonjava_parent";
    private static final String ITER_INDEX = "__perlonjava_index";
    private static final String ITER_REVERSE = "__perlonjava_reverse";
    private static final String ITER_SERIAL = "__perlonjava_iterator_serial";

    public TieHashIndexed() {
        super("Tie::Hash::Indexed", false);
    }

    public static void initialize() {
        TieHashIndexed module = new TieHashIndexed();
        try {
            module.registerMethod("TIEHASH", "construct", null);
            module.registerMethod("new", "construct", null);
            module.registerMethod("FETCH", "fetch", null);
            module.registerMethod("get", "fetch", null);
            module.registerMethod("STORE", "store", null);
            module.registerMethod("set", "set", null);
            module.registerMethod("FIRSTKEY", "firstKey", null);
            module.registerMethod("NEXTKEY", "nextKey", null);
            module.registerMethod("EXISTS", "exists", null);
            module.registerMethod("exists", "exists", null);
            module.registerMethod("has", "exists", null);
            module.registerMethod("DELETE", "deleteMethod", null);
            module.registerMethod("delete", "deleteMethod", null);
            module.registerMethod("CLEAR", "clearTied", null);
            module.registerMethod("clear", "clearObject", null);
            module.registerMethod("SCALAR", "scalar", null);
            module.registerMethod("items", "items", null);
            module.registerMethod("as_list", "items", null);
            module.registerMethod("keys", "keys", null);
            module.registerMethod("values", "values", null);
            module.registerMethod("merge", "merge", null);
            module.registerMethod("assign", "assign", null);
            module.registerMethod("push", "pushPairs", null);
            module.registerMethod("unshift", "unshiftPairs", null);
            module.registerMethod("pop", "pop", null);
            module.registerMethod("shift", "shift", null);
            module.registerMethod("iterator", "iterator", null);
            module.registerMethod("reverse_iterator", "reverseIterator", null);
            module.registerMethod("preinc", "preinc", null);
            module.registerMethod("predec", "predec", null);
            module.registerMethod("postinc", "postinc", null);
            module.registerMethod("postdec", "postdec", null);
            module.registerMethod("add", "add", null);
            module.registerMethod("subtract", "subtract", null);
            module.registerMethod("multiply", "multiply", null);
            module.registerMethod("divide", "divide", null);
            module.registerMethod("modulo", "modulo", null);
            module.registerMethod("concat", "concat", null);
            module.registerMethod("dor_assign", "dorAssign", null);
            module.registerMethod("dor_equals", "dorAssign", null);
            module.registerMethod("or_assign", "orAssign", null);
            module.registerMethod("or_equals", "orAssign", null);
            module.registerMethod("STORABLE_freeze", "storableFreeze", null);
            module.registerMethod("STORABLE_thaw", "storableThaw", null);

            module.registerMethodInPackage("Tie::Hash::Indexed::Iterator", "next", "iteratorNext");
            module.registerMethodInPackage("Tie::Hash::Indexed::Iterator", "prev", "iteratorPrev");
            module.registerMethodInPackage("Tie::Hash::Indexed::Iterator", "valid", "iteratorValid");
            module.registerMethodInPackage("Tie::Hash::Indexed::Iterator", "key", "iteratorKey");
            module.registerMethodInPackage("Tie::Hash::Indexed::Iterator", "value", "iteratorValue");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Cannot register Tie::Hash::Indexed backend", e);
        }
    }

    private static RuntimeHash self(RuntimeArray args) {
        RuntimeScalar object = args.get(0);
        if (object.type == RuntimeScalarType.REFERENCE) {
            return object.scalarDeref().hashDeref();
        }
        return object.hashDeref();
    }

    private static RuntimeArray order(RuntimeHash self) {
        return self.elements.get(ORDER).arrayDeref();
    }

    private static RuntimeHash values(RuntimeHash self) {
        return self.elements.get(VALUES).hashDeref();
    }

    private static long serial(RuntimeHash self) {
        return self.elements.get(SERIAL).getLong();
    }

    private static void invalidate(RuntimeHash self) {
        self.put(SERIAL, new RuntimeScalar(serial(self) + 1));
    }

    private static RuntimeScalar copy(RuntimeScalar value) {
        return value == null ? new RuntimeScalar() : new RuntimeScalar(value);
    }

    private static int findKey(RuntimeArray order, String key) {
        for (int i = 0; i < order.size(); i++) {
            if (order.get(i).toString().equals(key)) return i;
        }
        return -1;
    }

    private static void putValue(RuntimeHash self, RuntimeScalar key, RuntimeScalar value,
                                 boolean move, boolean front) {
        RuntimeArray order = order(self);
        RuntimeHash values = values(self);
        String stringKey = key.toString();
        int oldIndex = findKey(order, stringKey);
        if (oldIndex >= 0 && move) order.elements.remove(oldIndex);
        if (oldIndex < 0 || move) {
            RuntimeScalar storedKey = copy(key);
            if (front) order.elements.add(0, storedKey);
            else order.push(storedKey);
        }
        values.put(stringKey, copy(value));
        values.markKeyByte(stringKey, key.type == RuntimeScalarType.BYTE_STRING);
    }

    private static void requirePairs(RuntimeArray args, int first) {
        if ((args.size() - first) % 2 != 0) {
            throw new PerlCompilerException("odd number of arguments");
        }
    }

    public static RuntimeList construct(RuntimeArray args, int ctx) {
        requirePairs(args, 1);
        RuntimeHash self = new RuntimeHash();
        self.put(ORDER, new RuntimeArray().createReference());
        self.put(VALUES, new RuntimeHash().createReference());
        self.put(SERIAL, new RuntimeScalar(0));
        self.put(TIE_ITER, new RuntimeScalar(0));
        RuntimeScalar holder = new RuntimeScalar(self.createReference());
        RuntimeScalar ref = holder.createReference();
        ReferenceOperators.bless(ref, new RuntimeScalar("Tie::Hash::Indexed"));
        for (int i = 1; i < args.size(); i += 2) {
            putValue(self, args.get(i), args.get(i + 1), false, false);
        }
        return ref.getList();
    }

    public static RuntimeList fetch(RuntimeArray args, int ctx) {
        RuntimeScalar value = values(self(args)).elements.get(args.get(1).toString());
        return copy(value).getList();
    }

    public static RuntimeList store(RuntimeArray args, int ctx) {
        RuntimeHash self = self(args);
        invalidate(self);
        putValue(self, args.get(1), args.get(2), false, false);
        return new RuntimeList();
    }

    public static RuntimeList set(RuntimeArray args, int ctx) {
        RuntimeHash self = self(args);
        invalidate(self);
        putValue(self, args.get(1), args.get(2), false, false);
        return copy(args.get(2)).getList();
    }

    public static RuntimeList firstKey(RuntimeArray args, int ctx) {
        RuntimeHash self = self(args);
        self.put(TIE_ITER, new RuntimeScalar(0));
        RuntimeArray order = order(self);
        return order.isEmpty() ? new RuntimeScalar().getList() : copy(order.get(0)).getList();
    }

    public static RuntimeList nextKey(RuntimeArray args, int ctx) {
        RuntimeHash self = self(args);
        int index = self.elements.get(TIE_ITER).getInt() + 1;
        self.put(TIE_ITER, new RuntimeScalar(index));
        RuntimeArray order = order(self);
        return index >= order.size() ? new RuntimeScalar().getList() : copy(order.get(index)).getList();
    }

    public static RuntimeList exists(RuntimeArray args, int ctx) {
        return new RuntimeScalar(values(self(args)).elements.containsKey(args.get(1).toString())).getList();
    }

    public static RuntimeList deleteMethod(RuntimeArray args, int ctx) {
        RuntimeHash self = self(args);
        RuntimeArray order = order(self);
        RuntimeHash values = values(self);
        String key = args.get(1).toString();
        RuntimeScalar removed = values.elements.remove(key);
        if (removed == null) return new RuntimeScalar().getList();
        int index = findKey(order, key);
        if (index >= 0) {
            order.elements.remove(index);
            int tieIndex = self.elements.get(TIE_ITER).getInt();
            if (index <= tieIndex) self.put(TIE_ITER, new RuntimeScalar(tieIndex - 1));
        }
        invalidate(self);
        return copy(removed).getList();
    }

    private static void clear(RuntimeHash self) {
        order(self).elements.clear();
        values(self).elements.clear();
        invalidate(self);
    }

    public static RuntimeList clearTied(RuntimeArray args, int ctx) {
        clear(self(args));
        return new RuntimeList();
    }

    public static RuntimeList clearObject(RuntimeArray args, int ctx) {
        RuntimeScalar result = args.get(0);
        clear(self(args));
        return result.getList();
    }

    public static RuntimeList scalar(RuntimeArray args, int ctx) {
        int size = order(self(args)).size();
        return new RuntimeScalar(size == 0 ? "0" : size + "/8").getList();
    }

    private static RuntimeList select(RuntimeArray args, int ctx, int mode) {
        RuntimeHash self = self(args);
        RuntimeArray selected = new RuntimeArray();
        if (args.size() == 1) {
            for (int i = 0; i < order(self).size(); i++) selected.push(copy(order(self).get(i)));
        } else {
            for (int i = 1; i < args.size(); i++) selected.push(copy(args.get(i)));
        }
        int count = selected.size() * (mode == 0 ? 2 : 1);
        if (ctx == RuntimeContextType.SCALAR) return new RuntimeScalar(count).getList();
        RuntimeList result = new RuntimeList();
        RuntimeHash values = values(self);
        for (int i = 0; i < selected.size(); i++) {
            RuntimeScalar key = selected.get(i);
            RuntimeScalar value = values.elements.get(key.toString());
            if (mode != 2) result.add(copy(key));
            if (mode != 1) result.add(copy(value));
        }
        return result;
    }

    public static RuntimeList items(RuntimeArray args, int ctx) { return select(args, ctx, 0); }
    public static RuntimeList keys(RuntimeArray args, int ctx) { return select(args, ctx, 1); }
    public static RuntimeList values(RuntimeArray args, int ctx) { return select(args, ctx, 2); }

    private static RuntimeList mergePairs(RuntimeArray args, boolean clear, boolean move, boolean front) {
        requirePairs(args, 1);
        RuntimeHash self = self(args);
        invalidate(self);
        if (clear) {
            order(self).elements.clear();
            values(self).elements.clear();
        }
        if (front) {
            for (int i = args.size() - 2; i >= 1; i -= 2) {
                putValue(self, args.get(i), args.get(i + 1), move, true);
            }
        } else {
            for (int i = 1; i < args.size(); i += 2) {
                putValue(self, args.get(i), args.get(i + 1), move, false);
            }
        }
        return new RuntimeScalar(order(self).size()).getList();
    }

    public static RuntimeList merge(RuntimeArray args, int ctx) { return mergePairs(args, false, false, false); }
    public static RuntimeList assign(RuntimeArray args, int ctx) { return mergePairs(args, true, false, false); }
    public static RuntimeList pushPairs(RuntimeArray args, int ctx) { return mergePairs(args, false, true, false); }
    public static RuntimeList unshiftPairs(RuntimeArray args, int ctx) { return mergePairs(args, false, true, true); }

    private static RuntimeList removeEnd(RuntimeArray args, int ctx, boolean first) {
        RuntimeHash self = self(args);
        RuntimeArray order = order(self);
        if (order.isEmpty()) return new RuntimeList();
        int index = first ? 0 : order.size() - 1;
        RuntimeScalar key = copy(order.get(index));
        order.elements.remove(index);
        RuntimeScalar value = values(self).elements.remove(key.toString());
        invalidate(self);
        if (ctx == RuntimeContextType.LIST) return new RuntimeList(key, copy(value));
        return copy(value).getList();
    }

    public static RuntimeList pop(RuntimeArray args, int ctx) { return removeEnd(args, ctx, false); }
    public static RuntimeList shift(RuntimeArray args, int ctx) { return removeEnd(args, ctx, true); }

    private static RuntimeList makeIterator(RuntimeArray args, boolean reverse) {
        RuntimeHash parent = self(args);
        RuntimeHash iterator = new RuntimeHash();
        iterator.put(ITER_PARENT, args.get(0));
        iterator.put(ITER_REVERSE, new RuntimeScalar(reverse));
        iterator.put(ITER_SERIAL, new RuntimeScalar(serial(parent)));
        iterator.put(ITER_INDEX, new RuntimeScalar(reverse ? order(parent).size() - 1 : 0));
        RuntimeScalar ref = iterator.createReference();
        ReferenceOperators.bless(ref, new RuntimeScalar("Tie::Hash::Indexed::Iterator"));
        return ref.getList();
    }

    public static RuntimeList iterator(RuntimeArray args, int ctx) { return makeIterator(args, false); }
    public static RuntimeList reverseIterator(RuntimeArray args, int ctx) { return makeIterator(args, true); }

    private static RuntimeHash iteratorParent(RuntimeHash iterator) {
        RuntimeScalar parent = iterator.elements.get(ITER_PARENT);
        if (parent.type == RuntimeScalarType.REFERENCE) {
            return parent.scalarDeref().hashDeref();
        }
        return parent.hashDeref();
    }

    private static boolean iteratorCurrent(RuntimeHash iterator) {
        RuntimeHash parent = iteratorParent(iterator);
        int index = iterator.elements.get(ITER_INDEX).getInt();
        return serial(parent) == iterator.elements.get(ITER_SERIAL).getLong()
                && index >= 0 && index < order(parent).size();
    }

    private static void checkIterator(RuntimeHash iterator) {
        if (serial(iteratorParent(iterator)) != iterator.elements.get(ITER_SERIAL).getLong()) {
            throw new PerlCompilerException("invalid iterator access");
        }
    }

    private static RuntimeList iteratorMove(RuntimeArray args, int ctx, boolean next) {
        RuntimeHash iterator = self(args);
        checkIterator(iterator);
        RuntimeHash parent = iteratorParent(iterator);
        int index = iterator.elements.get(ITER_INDEX).getInt();
        RuntimeList result = new RuntimeList();
        if (ctx == RuntimeContextType.LIST && index >= 0 && index < order(parent).size()) {
            RuntimeScalar key = order(parent).get(index);
            result.add(copy(key));
            result.add(copy(values(parent).elements.get(key.toString())));
        }
        boolean reverse = iterator.elements.get(ITER_REVERSE).getBoolean();
        int delta = (next == reverse) ? -1 : 1;
        iterator.put(ITER_INDEX, new RuntimeScalar(index + delta));
        return result;
    }

    public static RuntimeList iteratorNext(RuntimeArray args, int ctx) { return iteratorMove(args, ctx, true); }
    public static RuntimeList iteratorPrev(RuntimeArray args, int ctx) { return iteratorMove(args, ctx, false); }

    public static RuntimeList iteratorValid(RuntimeArray args, int ctx) {
        return new RuntimeScalar(iteratorCurrent(self(args))).getList();
    }

    private static RuntimeList iteratorItem(RuntimeArray args, boolean value) {
        RuntimeHash iterator = self(args);
        checkIterator(iterator);
        RuntimeHash parent = iteratorParent(iterator);
        int index = iterator.elements.get(ITER_INDEX).getInt();
        if (index < 0 || index >= order(parent).size()) return new RuntimeScalar().getList();
        RuntimeScalar key = order(parent).get(index);
        return copy(value ? values(parent).elements.get(key.toString()) : key).getList();
    }

    public static RuntimeList iteratorKey(RuntimeArray args, int ctx) { return iteratorItem(args, false); }
    public static RuntimeList iteratorValue(RuntimeArray args, int ctx) { return iteratorItem(args, true); }

    private static RuntimeList increment(RuntimeArray args, int delta, boolean post) {
        RuntimeHash self = self(args);
        RuntimeHash values = values(self);
        RuntimeScalar key = args.get(1);
        RuntimeScalar old = values.elements.get(key.toString());
        if (old == null) {
            old = new RuntimeScalar(0);
            putValue(self, key, old, false, false);
        }
        RuntimeScalar original = copy(old);
        RuntimeScalar replacement = new RuntimeScalar(old.getDouble() + delta);
        values.put(key.toString(), replacement);
        return (post ? original : copy(replacement)).getList();
    }

    public static RuntimeList preinc(RuntimeArray args, int ctx) { return increment(args, 1, false); }
    public static RuntimeList predec(RuntimeArray args, int ctx) { return increment(args, -1, false); }
    public static RuntimeList postinc(RuntimeArray args, int ctx) { return increment(args, 1, true); }
    public static RuntimeList postdec(RuntimeArray args, int ctx) { return increment(args, -1, true); }

    private static RuntimeList binary(RuntimeArray args, int operation) {
        RuntimeHash self = self(args);
        RuntimeScalar key = args.get(1);
        RuntimeHash values = values(self);
        RuntimeScalar current = values.elements.get(key.toString());
        if (current == null) {
            current = new RuntimeScalar();
            putValue(self, key, current, false, false);
        }
        RuntimeScalar operand = args.get(2);
        RuntimeScalar result;
        switch (operation) {
            case 0 -> result = new RuntimeScalar(current.getDouble() + operand.getDouble());
            case 1 -> result = new RuntimeScalar(current.getDouble() - operand.getDouble());
            case 2 -> result = new RuntimeScalar(current.getDouble() * operand.getDouble());
            case 3 -> result = new RuntimeScalar(current.getDouble() / operand.getDouble());
            case 4 -> result = new RuntimeScalar(current.getLong() % operand.getLong());
            case 5 -> result = new RuntimeScalar(current.toString() + operand.toString());
            case 6 -> result = current.type == RuntimeScalarType.UNDEF ? copy(operand) : copy(current);
            case 7 -> result = current.getBoolean() ? copy(current) : copy(operand);
            default -> throw new IllegalArgumentException("invalid operation");
        }
        values.put(key.toString(), result);
        return copy(result).getList();
    }

    public static RuntimeList add(RuntimeArray args, int ctx) { return binary(args, 0); }
    public static RuntimeList subtract(RuntimeArray args, int ctx) { return binary(args, 1); }
    public static RuntimeList multiply(RuntimeArray args, int ctx) { return binary(args, 2); }
    public static RuntimeList divide(RuntimeArray args, int ctx) { return binary(args, 3); }
    public static RuntimeList modulo(RuntimeArray args, int ctx) { return binary(args, 4); }
    public static RuntimeList concat(RuntimeArray args, int ctx) { return binary(args, 5); }
    public static RuntimeList dorAssign(RuntimeArray args, int ctx) { return binary(args, 6); }
    public static RuntimeList orAssign(RuntimeArray args, int ctx) { return binary(args, 7); }

    public static RuntimeList storableFreeze(RuntimeArray args, int ctx) {
        RuntimeHash self = self(args);
        RuntimeList result = new RuntimeList(new RuntimeScalar("THI!\0\0"));
        RuntimeArray payload = new RuntimeArray();
        RuntimeArray order = order(self);
        RuntimeHash values = values(self);
        for (int i = 0; i < order.size(); i++) {
            RuntimeScalar key = copy(order.get(i));
            payload.push(key);
            payload.push(copy(values.elements.get(key.toString())));
        }
        result.add(payload.createReference());
        return result;
    }

    public static RuntimeList storableThaw(RuntimeArray args, int ctx) {
        RuntimeArray payload = args.size() > 3 ? args.get(3).arrayDeref() : new RuntimeArray();
        if (payload.size() % 2 != 0) {
            throw new PerlCompilerException("odd number of items in STORABLE_thaw");
        }
        RuntimeHash self;
        if (args.get(0).type == RuntimeScalarType.REFERENCE) {
            self = new RuntimeHash();
            args.get(0).scalarDeref().set(self.createReference());
        } else {
            self = self(args);
        }
        self.put(ORDER, new RuntimeArray().createReference());
        self.put(VALUES, new RuntimeHash().createReference());
        self.put(SERIAL, new RuntimeScalar(0));
        self.put(TIE_ITER, new RuntimeScalar(0));
        for (int i = 0; i < payload.size(); i += 2) {
            putValue(self, payload.get(i), payload.get(i + 1), false, false);
        }
        return new RuntimeList();
    }
}
