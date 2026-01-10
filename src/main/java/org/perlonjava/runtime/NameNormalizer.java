package org.perlonjava.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * The NameNormalizer class provides utility methods for normalizing Perl variable names
 * and managing "blessed" class lookups. It includes caching mechanisms for efficient
 * retrieval of previously normalized names and blessed IDs.
 */
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

    /**
     * Retrieves the unique ID associated with a "blessed" class name.
     * If the class name is not already cached, it assigns a new ID.
     *
     * @param str The name of the class to be "blessed".
     * @return The unique ID associated with the class name.
     */
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

    /**
     * Retrieves the class name associated with a given "blessed" ID.
     *
     * @param id The ID of the "blessed" class.
     * @return The class name associated with the ID.
     */
    public static String getBlessStr(int id) {
        return blessStrCache.get(id);
    }

    public static void anonymizeBlessId(String className) {
        Integer id = blessIdCache.get(className);
        if (id == null) {
            // Ensure subsequent blesses into this name also become anonymous.
            id = getBlessId(className);
        }
        blessStrCache.set(id, "__ANON__");
    }

    public static String getBlessStrForClassName(String className) {
        Integer id = blessIdCache.get(className);
        if (id == null) {
            return className;
        }
        return getBlessStr(id);
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

        if (variable.isEmpty()) {
            return cacheKey;
        }

        // Check the cache first
        if (nameCache.containsKey(cacheKey)) {
            return nameCache.get(cacheKey);
        }

        char firstLetter = variable.charAt(0);
        if (variable.equals("_") || !(firstLetter == '_' || Character.isLetter(firstLetter)) || SPECIAL_VARIABLES.contains(variable)) {
            if (variable.length() > 1 && variable.startsWith("(")) {
                // Looks like a overload method
            } else {
                defaultPackage = "main";    // special variables are always in main
                if (variable.length() == 2 && variable.charAt(0) == '^' && variable.charAt(1) >= 'A' && variable.charAt(1) <= 'Z') {
                    // For $^A to $^Z, convert the second character to the corresponding ASCII control character.
                    // For example, $^A should become ${chr(1)}
                    char controlChar = (char) (variable.charAt(1) - 64);
                    variable = String.valueOf(controlChar);
                }
            }
        }

        StringBuilder normalized = new StringBuilder(variable.length() + defaultPackage.length() + 2);
        if (variable.startsWith("::")) {
            // $::x
            normalized.append("main").append(variable);
        } else if (variable.contains("::")) {
            // If already in a package, return as-is
            normalized.append(variable);
        } else {
            // Prepend default package
            // Check if defaultPackage already ends with :: to avoid Math::BigInt::::((
            if (defaultPackage.endsWith("::")) {
                normalized.append(defaultPackage).append(variable);
            } else {
                normalized.append(defaultPackage).append("::").append(variable);
            }
        }

        // Convert to string and store in cache
        String normalizedStr = normalized.toString();
        nameCache.put(cacheKey, normalizedStr);

        return normalizedStr;
    }

    /**
     * Converts a Perl module name to a corresponding filename by replacing '::' with '/'
     * and appending '.pm'.
     *
     * @param moduleName The name of the Perl module.
     * @return The filename corresponding to the module name.
     * @throws PerlCompilerException if the module name is null or empty.
     */
    public static String moduleToFilename(String moduleName) {
        if (moduleName == null || moduleName.isEmpty()) {
            throw new PerlCompilerException("Module name cannot be null or empty");
        }

        // Replace '::' with '/' and append '.pm'
        return moduleName.replace("::", "/") + ".pm";
    }
}
