package org.perlonjava.runtime.perlmodule;

import com.booking.sereal.Latin1String;
import com.booking.sereal.PerlObject;
import com.booking.sereal.PerlReference;
import com.booking.sereal.PerlUndef;
import org.perlonjava.runtime.runtimetypes.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.*;

/** Converts PerlOnJava runtime values to and from the official Sereal Java model. */
final class SerealRuntimeConverter {
    private SerealRuntimeConverter() {}

    static Object toJava(RuntimeScalar scalar) {
        return toJavaScalar(scalar, new IdentityHashMap<>());
    }

    private static Object toJavaScalar(RuntimeScalar scalar, IdentityHashMap<RuntimeBase, Object> seen) {
        while (scalar.type == READONLY_SCALAR && scalar.value instanceof RuntimeScalar inner) {
            scalar = inner;
        }
        return switch (scalar.type) {
            case UNDEF -> PerlUndef.CANONICAL;
            case INTEGER -> scalar.getLong();
            case DOUBLE -> scalar.getDouble();
            case BOOLEAN -> scalar.getBoolean();
            case BYTE_STRING -> new Latin1String(scalar.toString());
            case STRING, VSTRING, DUALVAR -> scalar.toString();
            case ARRAYREFERENCE -> referenceValue(scalar, scalar.arrayDeref(), seen);
            case HASHREFERENCE -> referenceValue(scalar, scalar.hashDeref(), seen);
            case REFERENCE -> referenceValue(scalar, scalar.scalarDeref(), seen);
            default -> scalar.toString();
        };
    }

    private static Object referenceValue(RuntimeScalar scalar, RuntimeBase referent,
                                         IdentityHashMap<RuntimeBase, Object> seen) {
        Object converted = seen.get(referent);
        if (converted == null) {
            if (referent instanceof RuntimeArray array) {
                List<Object> list = new ArrayList<>(array.size());
                PerlReference reference = new PerlReference(list);
                seen.put(referent, reference);
                for (RuntimeScalar value : array) list.add(toJavaScalar(value, seen));
                converted = reference;
            } else if (referent instanceof RuntimeHash hash) {
                Map<Object, Object> map = new LinkedHashMap<>();
                PerlReference reference = new PerlReference(map);
                seen.put(referent, reference);
                for (Map.Entry<String, RuntimeScalar> entry : hash.elements.entrySet()) {
                    map.put(entry.getKey(), toJavaScalar(entry.getValue(), seen));
                }
                converted = reference;
            } else if (referent instanceof RuntimeScalar target) {
                PerlReference placeholder = new PerlReference(null);
                seen.put(referent, placeholder);
                placeholder.setValue(toJavaScalar(target, seen));
                converted = placeholder;
            } else {
                converted = new PerlReference(referent.toString());
                seen.put(referent, converted);
            }
        }
        int blessId = RuntimeScalarType.blessedId(scalar);
        if (blessId != 0) {
            String className = NameNormalizer.getBlessStr(blessId);
            converted = new PerlObject(className == null ? "__ANON__" : className, converted);
        }
        return converted;
    }

    static RuntimeScalar fromJava(Object value) {
        return fromJava(value, new IdentityHashMap<>());
    }

    private static RuntimeScalar fromJava(Object value, IdentityHashMap<Object, RuntimeScalar> seen) {
        if (value == null || value instanceof PerlUndef) return new RuntimeScalar();
        RuntimeScalar prior = seen.get(value);
        if (prior != null) return prior;
        if (value instanceof PerlObject object) {
            RuntimeScalar result = fromJava(object.getData(), seen);
            if (RuntimeScalarType.isReference(result) && result.value instanceof RuntimeBase base) {
                base.blessId = NameNormalizer.getBlessId(object.getName());
            }
            return result;
        }
        if (value instanceof PerlReference reference) {
            Object referent = reference.getValue();
            RuntimeScalar target = fromJava(referent, seen);
            // Java's PerlReference wraps both aggregate references and scalar
            // references. Aggregate conversion already returns a Perl reference;
            // only scalar referents need one more level of indirection.
            RuntimeScalar result = !(referent instanceof PerlReference)
                    && (target.type == ARRAYREFERENCE || target.type == HASHREFERENCE)
                    ? target : target.createReference();
            seen.put(value, result);
            return result;
        }
        if (value instanceof Map<?, ?> map) {
            RuntimeHash hash = new RuntimeHash();
            RuntimeScalar result = hash.createAnonymousReference();
            seen.put(value, result);
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                hash.put(entry.getKey().toString(), fromJava(entry.getValue(), seen));
            }
            return result;
        }
        if (value instanceof List<?> list) {
            RuntimeArray array = new RuntimeArray();
            RuntimeScalar result = array.createAnonymousReference();
            seen.put(value, result);
            for (Object item : list) RuntimeArray.push(array, fromJava(item, seen));
            return result;
        }
        if (value instanceof Object[] arrayValue) {
            RuntimeArray array = new RuntimeArray();
            RuntimeScalar result = array.createAnonymousReference();
            seen.put(value, result);
            for (Object item : arrayValue) RuntimeArray.push(array, fromJava(item, seen));
            return result;
        }
        if (value instanceof byte[] bytes) return byteScalar(bytes);
        if (value instanceof Latin1String latin1) return byteScalar(latin1.getBytes());
        if (value instanceof Boolean bool) return new RuntimeScalar(bool);
        if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
            return new RuntimeScalar(((Number) value).intValue());
        }
        if (value instanceof Long number) return new RuntimeScalar(number);
        if (value instanceof Number number) return new RuntimeScalar(number.doubleValue());
        return new RuntimeScalar(value.toString());
    }

    static RuntimeScalar byteScalar(byte[] bytes) {
        RuntimeScalar scalar = new RuntimeScalar(new String(bytes, StandardCharsets.ISO_8859_1));
        scalar.type = BYTE_STRING;
        return scalar;
    }
}
