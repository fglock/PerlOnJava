package org.perlonjava.runtime.runtimetypes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Runtime dispatch support for the parser's lexical autobox call-site map. */
public final class AutoboxRuntime {
    private static final Map<List<String>, String> SHIM_CLASSES = new ConcurrentHashMap<>();
    private static final AtomicLong NEXT_SHIM_ID = new AtomicLong();

    private AutoboxRuntime() { }

    /** Return the first applicable class mapping for this native invocant. */
    public static String mappedClass(RuntimeScalar scalar, String encodedBindings) {
        if (scalar == null || encodedBindings == null || encodedBindings.isEmpty()) return null;
        if (RuntimeScalarType.blessedId(scalar) != 0) return null;

        String type = autoboxType(scalar);
        if (type == null) return null;
        Map<String, List<String>> bindings = decode(encodedBindings);
        List<String> hierarchy = switch (type) {
            case "INTEGER", "FLOAT" -> List.of(type, "NUMBER", "SCALAR", "UNIVERSAL");
            case "STRING" -> List.of("STRING", "SCALAR", "UNIVERSAL");
            case "UNDEF" -> List.of("UNDEF");
            case "ARRAY", "HASH", "CODE" -> List.of(type, "UNIVERSAL");
            default -> List.of(type, "UNIVERSAL");
        };

        List<String> classes = new ArrayList<>();
        for (String candidate : hierarchy) {
            List<String> mapped = bindings.get(candidate);
            if (mapped != null) {
                for (String className : mapped) {
                    if (!classes.contains(className)) classes.add(className);
                }
            }
        }
        if (classes.isEmpty()) return null;
        if (classes.size() == 1) return classes.getFirst();
        return syntheticClass(classes);
    }

    public static String autoboxType(RuntimeScalar scalar) {
        if (scalar == null) return "UNDEF";
        int type = RuntimeScalarType.effectiveType(scalar);
        return switch (type) {
            case RuntimeScalarType.INTEGER, RuntimeScalarType.BOOLEAN -> "INTEGER";
            case RuntimeScalarType.DOUBLE -> "FLOAT";
            case RuntimeScalarType.STRING, RuntimeScalarType.BYTE_STRING,
                    RuntimeScalarType.VSTRING -> "STRING";
            case RuntimeScalarType.UNDEF -> "UNDEF";
            case RuntimeScalarType.REFERENCE -> "SCALAR";
            case RuntimeScalarType.ARRAYREFERENCE -> "ARRAY";
            case RuntimeScalarType.HASHREFERENCE -> "HASH";
            case RuntimeScalarType.CODE -> "CODE";
            default -> null;
        };
    }

    private static Map<String, List<String>> decode(String encoded) {
        Map<String, List<String>> bindings = new LinkedHashMap<>();
        for (String entry : encoded.split(";")) {
            int separator = entry.indexOf('=');
            if (separator < 1) continue;
            String type = entry.substring(0, separator);
            String encodedClasses = entry.substring(separator + 1);
            if (encodedClasses.isEmpty()) {
                bindings.put(type, List.of());
            } else {
                bindings.put(type, List.of(encodedClasses.split("\\|", -1)));
            }
        }
        return bindings;
    }

    private static String syntheticClass(List<String> classes) {
        List<String> key = List.copyOf(classes);
        String shimName = SHIM_CLASSES.computeIfAbsent(key,
                ignored -> "autobox::__PerlOnJavaShim" + NEXT_SHIM_ID.incrementAndGet());
        RuntimeArray isa = GlobalVariable.getGlobalArray(shimName + "::ISA");
        synchronized (isa) {
            if (isa.elements.isEmpty()) {
                for (String className : key) isa.elements.add(new RuntimeScalar(className));
            }
        }
        return shimName;
    }
}
