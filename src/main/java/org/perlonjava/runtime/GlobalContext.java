package org.perlonjava.runtime;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The RuntimeScalar class simulates Perl namespaces.
 */
public class GlobalContext {

    private static final Map<String, RuntimeScalar> globalVariables = new HashMap<>();
    private static final Map<String, RuntimeArray> globalArrays = new HashMap<>();
    private static final Map<String, RuntimeHash> globalHashes = new HashMap<>();
    private static final Map<String, RuntimeScalar> globalCodeRefs = new HashMap<>();

    // Cache to store previously normalized variables for faster lookup
    private static final Map<String, String> cache = new HashMap<>();

    private static final Set<String> SPECIAL_VARIABLES = Set.of(
            "ARGV", "ENV", "INC", "SIG", "STDOUT", "STDERR", "STDIN"
    );

    public static void initializeGlobals() {
        getGlobalVariable("main::@");    // initialize $@ to "undef"
        getGlobalVariable("main::_");    // initialize $_ to "undef"
        getGlobalVariable("main::\"").set(" ");    // initialize $" to " "
        getGlobalArray("main::INC");
        getGlobalHash("main::INC");
    }

    /**
     * Normalizes a Perl variable name by ensuring it includes the default package if not already specified.
     *
     * @param variable       The variable name to normalize, without sigil. This should be a Perl variable name, potentially without a package.
     * @param defaultPackage The default package to prepend to the variable name if it does not already include a package.
     * @return The normalized variable name, without sigil, including the default package if it was not already specified.
     */
    public static String normalizeVariableName(String variable, String defaultPackage) {

        // Create a cache key based on both the variable and the default package
        String cacheKey = defaultPackage + "::" + variable;

        // Check the cache first
        if (cache.containsKey(cacheKey)) {
            return cache.get(cacheKey);
        }

        if (!Character.isLetter(variable.charAt(0)) || SPECIAL_VARIABLES.contains(variable)) {
            defaultPackage = "main";    // special variables are always in main
            if (variable.length() == 2 && variable.charAt(0) == '^' && variable.charAt(1) >= 'A' && variable.charAt(1) <= 'Z') {
                // For $^A to $^Z, convert the second character to the corresponding ASCII control character.
                // For example, $^A should become ${chr(1)}
                char controlChar = (char) (variable.charAt(1) - 64);
                variable = String.valueOf(controlChar);
            }
        }

        StringBuilder normalized = new StringBuilder(variable.length() + defaultPackage.length() + 2);
        if (variable.startsWith("::")) {
            // $::x
            normalized.append(defaultPackage).append(variable);
        } else if (variable.contains("::")) {
            // If already in a package, return as-is
            normalized.append(variable);
        } else {
            // Prepend default package
            normalized.append(defaultPackage).append("::").append(variable);
        }

        // Convert to string and store in cache
        String normalizedStr = normalized.toString();
        cache.put(cacheKey, normalizedStr);

        return normalizedStr;
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

    public static RuntimeScalar getGlobalCodeRef(String key) {
        RuntimeScalar var = globalCodeRefs.get(key);
        if (var == null) {
            var = new RuntimeScalar();
            var.type = RuntimeScalarType.GLOB;  // value is null
            globalCodeRefs.put(key, var);
        }
        return var;
    }

    public static boolean existsGlobalCodeRef(String key) {
        return globalCodeRefs.containsKey(key);
    }
}

