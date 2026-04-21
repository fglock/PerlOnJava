package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.runtime.mro.InheritanceResolver;

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
    // Using composite key avoids ~12ns string concatenation per lookup
    // Two-level cache to avoid per-lookup CacheKey allocation.
    // Outer map key: default package (few distinct values, populated once
    // per package). Inner map key: variable name. Lookups are:
    //   nameCache.get(pkg).get(var)
    // - two hash probes, zero allocation on the hot path. Prior
    // implementation used a Map<CacheKey, String> which allocated a
    // CacheKey record on every lookup — showed up as ~100 sampled
    // allocations/sec under JFR in sub-call-heavy benchmarks.
    //
    // ConcurrentHashMap so multiple threads populating the same package
    // bucket don't race on insertion.
    private static final Map<String, Map<String, String>> nameCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, Integer> blessIdCache = new HashMap<>();
    // Changed to HashMap to support non-contiguous IDs (positive: normal, negative: overloaded)
    private static final Map<Integer, String> blessStrCache = new HashMap<>();
    private static final Set<String> SPECIAL_VARIABLES = Set.of(
            "ARGV", "ARGVOUT", "ENV", "INC", "SIG", "STDOUT", "STDERR", "STDIN"
    );
    // Cache to store blessed class lookups
    // Positive blessIds (1, 2, 3, ...): normal classes without overloads
    // Negative blessIds (-1, -2, -3, ...): classes with overloads (enables fast rejection in OverloadContext.prepare)
    private static int currentBlessId = 0;
    private static int currentOverloadedBlessId = -1;

    static {
        blessStrCache.put(0, "");  // Reserve 0 for unblessed
    }

    /**
     * Retrieves the unique ID associated with a "blessed" class name.
     * If the class name is not already cached, it assigns a new ID.
     * Classes with overloads get negative IDs for fast rejection.
     *
     * @param str The name of the class to be "blessed".
     * @return The unique ID associated with the class name.
     */
    public static int getBlessId(String str) {
        Integer id = blessIdCache.get(str);
        if (id == null) {
            // Check if class has overload marker "(("
            boolean hasOverload = hasOverloadMarker(str);

            if (hasOverload) {
                id = currentOverloadedBlessId;
                currentOverloadedBlessId--;  // Next overloaded class gets -2, -3, etc.
            } else {
                currentBlessId++;  // Next normal class gets 2, 3, etc.
                id = currentBlessId;
            }

            blessIdCache.put(str, id);
            blessStrCache.put(id, str);
        }
        return id;
    }

    /**
     * Quick check if a class has an overload marker using full MRO resolution.
     * A class is considered overloaded if either of the following markers is
     * installed in its stash (anywhere in the @ISA chain):
     *   ((   — the canonical marker created by `use overload`
     *   ()   — the fallback-method glob, which modules that hand-roll overloads
     *          (e.g. AnyEvent::CondVar) install directly via typeglob manipulation
     *          to avoid loading overload.pm.
     * This is called at bless time to assign the appropriate ID range.
     */
    private static boolean hasOverloadMarker(String className) {
        // Use InheritanceResolver to do full MRO check
        // This avoids circular dependency because findMethodInHierarchy uses
        // normalizeVariableName (not getBlessId)
        try {
            RuntimeScalar method = InheritanceResolver.findMethodInHierarchy(
                    "((", className, null, 0);
            if (method != null) return true;
            // Fall back to `()` — Perl 5 treats this glob as an overload marker too
            method = InheritanceResolver.findMethodInHierarchy(
                    "()", className, null, 0);
            return method != null;
        } catch (Exception e) {
            // If we can't check (e.g., during early initialization), assume no overload
            return false;
        }
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
        blessStrCache.put(id, "__ANON__");
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

        if (variable.isEmpty()) {
            // Fast path for empty variable - don't cache, just concatenate
            return defaultPackage + "::" + variable;
        }

        // Two-level cache lookup (no allocation, two hash probes).
        Map<String, String> perPackageCache = nameCache.get(defaultPackage);
        if (perPackageCache != null) {
            String cached = perPackageCache.get(variable);
            if (cached != null) {
                return cached;
            }
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

        // Convert to string and store in cache (insert into the
        // per-package inner map, creating it on first encounter).
        String normalizedStr = normalized.toString();
        if (perPackageCache == null) {
            perPackageCache = nameCache.computeIfAbsent(
                    defaultPackage, k -> new java.util.concurrent.ConcurrentHashMap<>());
        }
        perPackageCache.put(variable, normalizedStr);

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

    /**
     * Normalizes a package name by converting old-style single-quote separator to '::'.
     * In Perl, "Foo'Bar" is equivalent to "Foo::Bar".
     *
     * @param packageName The package name to normalize.
     * @return The normalized package name with '::' separators.
     */
    public static String normalizePackageName(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return packageName;
        }
        // Replace old-style ' separator with ::
        if (packageName.indexOf('\'') >= 0) {
            return packageName.replace("'", "::");
        }
        return packageName;
    }
}
