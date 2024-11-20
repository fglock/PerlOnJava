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

    /**
     * Invalidates the caches for method resolution and linearized class hierarchies.
     * This should be called whenever the class hierarchy or method definitions change.
     */
    public static void invalidateCache() {
        methodCache.clear();
        linearizedClassesCache.clear();
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
        for (RuntimeBaseEntity entity : isaArray.elements) {
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
                candidate = linearization.get(0);
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
                if (!linearization.isEmpty() && linearization.get(0).equals(candidate)) {
                    linearization.remove(0);
                }
            }
            linearizations.removeIf(List::isEmpty);
        }

        // Ensure the current class is added at the beginning of the result
        result.add(0, className);
        return result;
    }
}
