package org.perlonjava;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/**
 * The RuntimeScalar class simulates Perl namespaces.
 */
public class Namespace {

    private static final Map<String, RuntimeScalar> globalVariables = new HashMap<>();
    private static final Map<String, RuntimeArray> globalArrays = new HashMap<>();
    private static final Map<String, RuntimeHash> globalHashes = new HashMap<>();

    // Cache to store previously normalized variables for faster lookup
    private static final Map<String, String> cache = new HashMap<>();

    private static final Set<String> SPECIAL_VARIABLES = new HashSet<>();

    static {
        // Populate with Perl's special variables
        SPECIAL_VARIABLES.add("ARGV");
        SPECIAL_VARIABLES.add("ENV");
        SPECIAL_VARIABLES.add("INC");
        SPECIAL_VARIABLES.add("SIG");
        SPECIAL_VARIABLES.add("STDOUT");
        SPECIAL_VARIABLES.add("STDERR");
    }

    public static void initializeGlobals() {
        getGlobalVariable("$main::@");    // initialize $@ to "undef"
        getGlobalVariable("$main::_");    // initialize $_ to "undef"
        getGlobalVariable("$main::\"").set(" ");    // initialize $" to " "
        getGlobalArray("@main::INC");
        getGlobalHash("%main::INC");
    }

    public static String normalizeVariableName(String variable, String defaultPackage) {

        // Create a cache key based on both the variable and the default package
        String cacheKey = defaultPackage + "::" + variable;

        // Check the cache first
        if (cache.containsKey(cacheKey)) {
            return cache.get(cacheKey);
        }

        char sigil = variable.charAt(0);
        String name = variable.substring(1);
        if (!Character.isLetter(name.charAt(0)) || SPECIAL_VARIABLES.contains(name)) {
            defaultPackage = "main";    // special variables are always in main
            if (name.length() == 2 && name.charAt(0) == '^' && name.charAt(1) >= 'A' && name.charAt(1) <= 'Z' ) {
                // For $^A to $^Z, convert the second character to the corresponding ASCII control character.
                // For example, $^A should become ${chr(1)}
                char controlChar = (char) (name.charAt(1) - 64);
                name = String.valueOf(controlChar);
            }
        }

        StringBuilder normalized = new StringBuilder(variable.length() + defaultPackage.length() + 2);
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

