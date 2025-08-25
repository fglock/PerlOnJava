package org.perlonjava.mro;

import java.util.*;

/**
 * The DFS class provides depth-first search linearization for Perl class hierarchies.
 * This is an alternative to C3 linearization and represents the traditional Perl 5
 * method resolution order.
 */
public class DFS {
    /**
     * Linearizes the inheritance hierarchy of a class using depth-first search.
     *
     * @param className The name of the class to linearize.
     * @return A list of class names in the order of method resolution.
     */
    public static List<String> linearizeDFS(String className) {
        // Check cache first
        List<String> cached = InheritanceResolver.linearizedClassesCache.get(className + "::DFS");
        if (cached != null) {
            return cached;
        }

        // Populate ISA map
        Map<String, List<String>> isaMap = new HashMap<>();
        InheritanceResolver.populateIsaMap(className, isaMap);

        // Perform DFS linearization
        Set<String> visited = new LinkedHashSet<>();
        linearizeDFSHelper(className, isaMap, visited);

        // Convert to list and add UNIVERSAL
        List<String> result = new ArrayList<>(visited);
        result.add("UNIVERSAL");

        // Cache the result
        InheritanceResolver.linearizedClassesCache.put(className + "::DFS", result);

        return result;
    }

    /**
     * Helper method to perform depth-first search traversal.
     *
     * @param className The current class being processed.
     * @param isaMap    A map containing the @ISA arrays for each class.
     * @param visited   A set to track visited classes and maintain order.
     */
    private static void linearizeDFSHelper(String className,
                                         Map<String, List<String>> isaMap,
                                         Set<String> visited) {
        // Add current class first (pre-order traversal)
        visited.add(className);

        // Get parents from ISA
        List<String> parents = isaMap.getOrDefault(className, Collections.emptyList());

        // Visit each parent in order (depth-first)
        for (String parent : parents) {
            if (!visited.contains(parent)) {
                linearizeDFSHelper(parent, isaMap, visited);
            }
        }
    }
}