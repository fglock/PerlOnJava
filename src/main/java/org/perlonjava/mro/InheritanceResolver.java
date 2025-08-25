package org.perlonjava.mro;

import org.perlonjava.runtime.*;

import java.util.*;

/**
 * The InheritanceResolver class provides methods for resolving method inheritance
 * and linearizing class hierarchies using the C3 or dfs algorithm. It maintains caches
 * for method resolution and linearized class hierarchies to improve performance.
 */
public class InheritanceResolver {
    public static boolean autoloadEnabled = true;

    // MRO algorithm selection
    public enum MROAlgorithm {
        C3,
        DFS
    }

    // Default MRO algorithm
    private static MROAlgorithm currentMRO = MROAlgorithm.DFS;

    // Per-package MRO settings
    private static final Map<String, MROAlgorithm> packageMRO = new HashMap<>();

    // Method resolution cache
    private static final Map<String, RuntimeScalar> methodCache = new HashMap<>();
    // Cache for linearized class hierarchies
    static final Map<String, List<String>> linearizedClassesCache = new HashMap<>();
    // Cache for OverloadContext instances by blessing ID
    private static final Map<Integer, OverloadContext> overloadContextCache = new HashMap<>();

    /**
     * Sets the default MRO algorithm.
     *
     * @param algorithm The MRO algorithm to use as default.
     */
    public static void setDefaultMRO(MROAlgorithm algorithm) {
        currentMRO = algorithm;
        invalidateCache();
    }

    /**
     * Sets the MRO algorithm for a specific package.
     *
     * @param packageName The name of the package.
     * @param algorithm The MRO algorithm to use for this package.
     */
    public static void setPackageMRO(String packageName, MROAlgorithm algorithm) {
        packageMRO.put(packageName, algorithm);
        invalidateCache();
    }

    /**
     * Gets the MRO algorithm for a specific package.
     *
     * @param packageName The name of the package.
     * @return The MRO algorithm for the package, or the default if not set.
     */
    public static MROAlgorithm getPackageMRO(String packageName) {
        return packageMRO.getOrDefault(packageName, currentMRO);
    }

    /**
     * Linearizes the inheritance hierarchy for a class using the appropriate MRO algorithm.
     *
     * @param className The name of the class to linearize.
     * @return A list of class names in the order of method resolution.
     */
    public static List<String> linearizeHierarchy(String className) {
        MROAlgorithm mro = getPackageMRO(className);

        switch (mro) {
            case C3:
                return C3.linearizeC3(className);
            case DFS:
                return DFS.linearizeDFS(className);
            default:
                throw new IllegalStateException("Unknown MRO algorithm: " + mro);
        }
    }

    /**
     * Invalidates the caches for method resolution and linearized class hierarchies.
     * This should be called whenever the class hierarchy or method definitions change.
     */
    public static void invalidateCache() {
        methodCache.clear();
        linearizedClassesCache.clear();
        overloadContextCache.clear();
    }

    /**
     * Retrieves a cached OverloadContext for the given blessing ID.
     *
     * @param blessId The blessing ID of the class.
     * @return The cached OverloadContext, or null if not found.
     */
    public static OverloadContext getCachedOverloadContext(int blessId) {
        return overloadContextCache.get(blessId);
    }

    /**
     * Caches an OverloadContext for the given blessing ID.
     *
     * @param blessId The blessing ID of the class.
     * @param context The OverloadContext to cache (can be null to indicate no overloading).
     */
    public static void cacheOverloadContext(int blessId, OverloadContext context) {
        overloadContextCache.put(blessId, context);
    }

    /**
     * Retrieves a cached method for the given normalized method name.
     *
     * @param normalizedMethodName The normalized name of the method.
     * @return The cached RuntimeScalar representing the method, or null if not found.
     */
    public static RuntimeScalar getCachedMethod(String normalizedMethodName) {
        return methodCache.get(normalizedMethodName);
    }

    /**
     * Caches a method for the given normalized method name.
     *
     * @param normalizedMethodName The normalized name of the method.
     * @param method               The RuntimeScalar representing the method to cache.
     */
    public static void cacheMethod(String normalizedMethodName, RuntimeScalar method) {
        methodCache.put(normalizedMethodName, method);
    }

    /**
     * Populates the isaMap with @ISA arrays for each class.
     *
     * @param className The name of the class to populate.
     * @param isaMap    The map to populate with @ISA arrays.
     */
    static void populateIsaMap(String className, Map<String, List<String>> isaMap) {
        populateIsaMapHelper(className, isaMap, new HashSet<>());
    }

    private static void populateIsaMapHelper(String className,
                                             Map<String, List<String>> isaMap,
                                             Set<String> currentPath) {
        if (isaMap.containsKey(className)) {
            return; // Already populated
        }

        // Check for circular inheritance
        if (currentPath.contains(className)) {
            throw new PerlCompilerException("Recursive inheritance detected involving class '" + className + "'");
        }

        currentPath.add(className);

        // Retrieve @ISA array for the given class
        RuntimeArray isaArray = GlobalVariable.getGlobalArray(className + "::ISA");
        List<String> parents = new ArrayList<>();
        for (RuntimeBase entity : isaArray.elements) {
            parents.add(entity.toString());
        }

        isaMap.put(className, parents);

        // Recursively populate for parent classes
        for (String parent : parents) {
            populateIsaMapHelper(parent, isaMap, currentPath);
        }

        currentPath.remove(className);
    }

    /**
     * Searches for a method in the class hierarchy starting from a specific index.
     * Uses method caching to improve performance for both found and not-found methods.
     *
     * @param methodName     The name of the method to find
     * @param perlClassName  The Perl class name to start the search from
     * @param cacheKey       The cache key to use for the method cache (null to use default cache key)
     * @param startFromIndex The index in the linearized hierarchy to start searching from (used for SUPER:: calls)
     * @return RuntimeScalar representing the found method, or null if not found
     */
    public static RuntimeScalar findMethodInHierarchy(String methodName, String perlClassName, String cacheKey, int startFromIndex) {
        if (cacheKey == null) {
            // Normalize the method name for consistent caching
            cacheKey = NameNormalizer.normalizeVariableName(methodName, perlClassName);
        }

        // Check the method cache - handles both found and not-found cases
        if (methodCache.containsKey(cacheKey)) {
            return methodCache.get(cacheKey);
        }

        // Get the linearized inheritance hierarchy using the appropriate MRO
        List<String> linearizedClasses = linearizeHierarchy(perlClassName);

        // Search through the class hierarchy starting from the specified index
        for (int i = startFromIndex; i < linearizedClasses.size(); i++) {
            String className = linearizedClasses.get(i);
            String normalizedClassMethodName = NameNormalizer.normalizeVariableName(methodName, className);

            // Check if method exists in current class
            if (GlobalVariable.existsGlobalCodeRef(normalizedClassMethodName)) {
                RuntimeScalar codeRef = GlobalVariable.getGlobalCodeRef(normalizedClassMethodName);
                // Cache the found method
                cacheMethod(cacheKey, codeRef);
                return codeRef;
            }

            // Method not found in current class, check AUTOLOAD
            if (!autoloadEnabled || methodName.equals("((") || methodName.equals("()")) {
                // refuse to AUTOLOAD tie() flags
            } else {
                // Check for AUTOLOAD in current class
                String autoloadName = className + "::AUTOLOAD";
                if (GlobalVariable.existsGlobalCodeRef(autoloadName)) {
                    RuntimeScalar autoload = GlobalVariable.getGlobalCodeRef(autoloadName);
                    if (autoload.getDefinedBoolean()) {
                        // System.out.println("AUTOLOAD: " + autoloadName + " looking for " + methodName);

                        // The caller will need to set $AUTOLOAD before calling
                        ((RuntimeCode) autoload.value).autoloadVariableName = autoloadName;

                        // Cache the found method;
                        // In case AUTOLOAD creates the missing method, it will invalidate the cache
                        cacheMethod(cacheKey, autoload);

                        return autoload;
                    }
                }
            }
        }

        // Cache the fact that method was not found (using null)
        methodCache.put(cacheKey, null);
        return null;
    }
}
