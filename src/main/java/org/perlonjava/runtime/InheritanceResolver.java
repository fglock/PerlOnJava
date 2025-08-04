package org.perlonjava.runtime;

import java.util.*;

/**
 * The InheritanceResolver class provides methods for resolving method inheritance
 * and linearizing class hierarchies using the C3 algorithm. It maintains caches
 * for method resolution and linearized class hierarchies to improve performance.
 */
public class InheritanceResolver {
    // Method resolution cache
    private static final Map<String, RuntimeScalar> methodCache = new HashMap<>();
    // Cache for linearized class hierarchies
    private static final Map<String, List<String>> linearizedClassesCache = new HashMap<>();
    // Cache for OverloadContext instances by blessing ID
    private static final Map<Integer, OverloadContext> overloadContextCache = new HashMap<>();

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
     * Linearizes the inheritance hierarchy of a class using the C3 algorithm.
     *
     * @param className The name of the class to linearize.
     * @return A list of class names in the order of method resolution.
     */
    public static List<String> linearizeC3(String className) {
        // System.out.println("linearizeC3: " + className);
        List<String> result = linearizedClassesCache.get(className);
        if (result == null) {
            Map<String, List<String>> isaMap = new HashMap<>();
            populateIsaMap(className, isaMap);
            result = linearizeC3Helper(className, isaMap);
            result.add("UNIVERSAL");
            linearizedClassesCache.put(className, result);
        }
        // System.out.println("Linearized hierarchy for " + className + ": " + result);
        return result;
    }

    /**
     * Populates the isaMap with @ISA arrays for each class.
     *
     * @param className The name of the class to populate.
     * @param isaMap    The map to populate with @ISA arrays.
     */
    private static void populateIsaMap(String className, Map<String, List<String>> isaMap) {
        if (isaMap.containsKey(className)) {
            return; // Already populated
        }

        // Retrieve @ISA array for the given class
        RuntimeArray isaArray = GlobalVariable.getGlobalArray(className + "::ISA");
        List<String> parents = new ArrayList<>();
        for (RuntimeBase entity : isaArray.elements) {
            parents.add(entity.toString());
        }

        isaMap.put(className, parents);
        // System.out.println("ISA for " + className + ": " + parents);

        // Recursively populate for parent classes
        for (String parent : parents) {
            populateIsaMap(parent, isaMap);
        }
    }

    /**
     * Helper method to perform the C3 linearization.
     *
     * @param className The name of the class to linearize.
     * @param isaMap    A map containing the @ISA arrays for each class.
     * @return A list of class names in the order of method resolution.
     */
    private static List<String> linearizeC3Helper(String className, Map<String, List<String>> isaMap) {
        List<String> result = new ArrayList<>();
        List<String> parents = isaMap.getOrDefault(className, Collections.emptyList());

        // If the class has no parents, return the class itself
        if (parents.isEmpty()) {
            result.add(className);
            return result;
        }

        // List of linearizations of each parent
        List<List<String>> linearizations = new ArrayList<>();
        for (String parent : parents) {
            linearizations.add(linearizeC3Helper(parent, isaMap));
        }
        // Add the parents list itself to the linearizations
        linearizations.add(parents);

        // Merge the linearizations using the C3 algorithm
        while (!linearizations.isEmpty()) {
            String candidate = null;
            for (List<String> linearization : linearizations) {
                if (linearization.isEmpty()) continue;
                candidate = linearization.getFirst();
                boolean isValidCandidate = true;
                for (List<String> other : linearizations) {
                    if (other.indexOf(candidate) > 0) {
                        isValidCandidate = false;
                        break;
                    }
                }
                if (isValidCandidate) break;
            }

            if (candidate == null) {
                throw new IllegalStateException("Cyclic inheritance detected");
            }

            result.add(candidate);
            // System.out.println("Selected candidate: " + candidate);
            for (List<String> linearization : linearizations) {
                if (!linearization.isEmpty() && linearization.getFirst().equals(candidate)) {
                    linearization.removeFirst();
                }
            }
            linearizations.removeIf(List::isEmpty);
        }

        // Ensure the current class is added at the beginning of the result
        result.addFirst(className);
        return result;
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

        // Get the linearized inheritance hierarchy using C3
        List<String> linearizedClasses = linearizeC3(perlClassName);

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
            if (methodName.equals("((") || methodName.equals("()")) {
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

                        // Don't cache AUTOLOAD methods as they need special handling
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
