package org.perlonjava;

import java.util.*;

/**
 * The RuntimeScalar class simulates Perl namespaces.
 */
public class Namespace {

    private static final Map<String, RuntimeScalar> globalVariables = new HashMap<>();
    private static final Map<String, RuntimeArray> globalArrays = new HashMap<>();
    private static final Map<String, RuntimeHash> globalHashes = new HashMap<>();

    // Static methods

    public static void initializeGlobals() {
        getGlobalVariable("$@");    // initialize $@ to "undef"
        getGlobalVariable("$_");    // initialize $_ to "undef"
        getGlobalVariable("$\"").set(" ");    // initialize $_ to " "
        getGlobalArray("@INC");
        getGlobalHash("%INC");
    }

    // Cache to store previously normalized variables for faster lookup
    private static final Map<String, String> cache = new HashMap<>();

    public static String normalizeVariableName(String variable, String defaultPackage) {

        // Create a cache key based on both the variable and the default package
        String cacheKey = defaultPackage + "::" + variable;

        // Check the cache first
        if (cache.containsKey(cacheKey)) {
            return cache.get(cacheKey);
        }

        char sigil = variable.charAt(0);
        String name = variable.substring(1);
        if (!Character.isLetter(name.charAt(0))) {
            defaultPackage = "main";    // special variables are always in main
        }

        // Use StringBuilder for efficient string operations
        StringBuilder normalized = new StringBuilder(variable.length() + defaultPackage.length() + 2);

        // Special case handling
        if (name.startsWith("::")) {
            // $::x
            normalized.append(sigil).append(defaultPackage).append(name);
        } else if (name.contains("::")) {
            // If already in a package, return as-is
            normalized.append(variable);
        } else {
            // Prepend default package
            normalized.append(sigil).append(defaultPackage).append("::").append(name);
        }

        // Convert to string and store in cache
        String normalizedStr = normalized.toString();
        cache.put(cacheKey, normalizedStr);

        return normalizedStr;
    }

    public static RuntimeScalar setGlobalVariable(String key, RuntimeScalar value) {
        RuntimeScalar var = globalVariables.get(key);
        if (var == null) {
            var = new RuntimeScalar();
            globalVariables.put(key, var);
        }
        return var.set(value);
    }

    public static RuntimeScalar setGlobalVariable(String key, String value) {
        RuntimeScalar var = globalVariables.get(key);
        if (var == null) {
            var = new RuntimeScalar();
            globalVariables.put(key, var);
        }
        return var.set(value);
    }

    public static RuntimeScalar getGlobalVariable(String key) {
        RuntimeScalar var = globalVariables.get(key);
        if (var == null) {
            var = new RuntimeScalar();
            globalVariables.put(key, var);
        }
        return var;
    }

    public static boolean existsGlobalVariable(String key) {
        return globalVariables.containsKey(key);
    }

    public static RuntimeArray getGlobalArray(String key) {
        RuntimeArray var = globalArrays.get(key);
        if (var == null) {
            var = new RuntimeArray();
            globalArrays.put(key, var);
        }
        return var;
    }

    public static boolean existsGlobalArray(String key) {
        return globalArrays.containsKey(key);
    }

    public static RuntimeHash getGlobalHash(String key) {
        RuntimeHash var = globalHashes.get(key);
        if (var == null) {
            var = new RuntimeHash();
            globalHashes.put(key, var);
        }
        return var;
    }

    public static boolean existsGlobalHash(String key) {
        return globalHashes.containsKey(key);
    }
}

