package org.perlonjava.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class NameNormalizer {
    // Cache to store previously normalized variables for faster lookup
    private static final Map<String, String> nameCache = new HashMap<>();
    private static final Map<String, Integer> blessIdCache = new HashMap<>();
    private static final ArrayList<String> blessStrCache = new ArrayList<>();
    private static final Set<String> SPECIAL_VARIABLES = Set.of(
            "ARGV", "ARGVOUT", "ENV", "INC", "SIG", "STDOUT", "STDERR", "STDIN"
    );
    // Cache to store blessed class lookups
    private static int currentBlessId = 0;

    static {
        blessStrCache.add("");  // this starts with index 1
    }

    public static int getBlessId(String str) {
        Integer id = blessIdCache.get(str);
        if (id == null) {
            currentBlessId++;
            blessIdCache.put(str, currentBlessId);
            blessStrCache.add(currentBlessId, str);
            id = currentBlessId;
        }
        return id;
    }

    public static String getBlessStr(int id) {
        return blessStrCache.get(id);
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
        if (nameCache.containsKey(cacheKey)) {
            return nameCache.get(cacheKey);
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
        nameCache.put(cacheKey, normalizedStr);

        return normalizedStr;
    }

    public static String moduleToFilename(String moduleName) {
        if (moduleName == null || moduleName.isEmpty()) {
            throw new PerlCompilerException("Module name cannot be null or empty");
        }

        // Replace '::' with '/' and append '.pm'
        return moduleName.replace("::", "/") + ".pm";
    }
}
