package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.backend.bytecode.InterpretedCode;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Transfers compiled format expressions into generated JVM class constants. */
public final class RuntimeFormatExpressionRegistry {
    private static final ConcurrentHashMap<String, List<InterpretedCode>> pending =
            new ConcurrentHashMap<>();

    private RuntimeFormatExpressionRegistry() {}

    public static void register(String key, List<InterpretedCode> expressions) {
        pending.put(key, List.copyOf(expressions));
    }

    public static List<InterpretedCode> take(String key) {
        List<InterpretedCode> expressions = pending.remove(key);
        return expressions == null ? List.of() : expressions;
    }

    public static void discardClass(String className) {
        String prefix = className + "#format#";
        pending.keySet().removeIf(key -> key.startsWith(prefix));
    }
}
