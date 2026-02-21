package org.perlonjava.mro;

import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.PerlCompilerException;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeBase;

import java.util.*;

public class DFS {
    private static final boolean DEBUG_DFS = Boolean.getBoolean("debug.dfs");

    public static List<String> linearizeDFS(String className) {
        if (DEBUG_DFS) {
            System.out.println("DEBUG DFS: Starting linearization for " + className);
        }

        // Check cache first
        String cacheKey = className + "::DFS";
        List<String> cached = InheritanceResolver.linearizedClassesCache.get(cacheKey);
        if (cached != null) {
            if (DEBUG_DFS) {
                System.out.println("DEBUG DFS: Using cached result for " + className + ": " + cached);
            }
            return new ArrayList<>(cached); // Return a copy to prevent modification of cached version
        }

        // Populate ISA map with current state
        Map<String, List<String>> isaMap = new HashMap<>();
        try {
            populateIsaMapWithCycleDetection(className, isaMap, new HashSet<>());
        } catch (PerlCompilerException e) {
            if (DEBUG_DFS) {
                System.out.println("DEBUG DFS: Circular inheritance detected during ISA population: " + e.getMessage());
            }
            throw new PerlCompilerException("Recursive inheritance detected in hierarchy of class '" + className + "'");
        }

        if (DEBUG_DFS) {
            System.out.println("DEBUG DFS: ISA map: " + isaMap);
        }

        // Perform DFS linearization using a list to maintain order
        List<String> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> currentPath = new HashSet<>();

        linearizeDFSHelper(className, isaMap, result, visited, currentPath);

        // Add UNIVERSAL only if not already present and this is not UNIVERSAL itself
        if (!result.contains("UNIVERSAL") && !className.equals("UNIVERSAL")) {
            result.add("UNIVERSAL");
        }

        if (DEBUG_DFS) {
            System.out.println("DEBUG DFS: Final linearization: " + result);
        }

        // Cache the result (store a copy to prevent external modifications)
        InheritanceResolver.linearizedClassesCache.put(cacheKey, new ArrayList<>(result));
        return result;
    }

    /**
     * Populate ISA map with cycle detection
     */
    private static void populateIsaMapWithCycleDetection(String className,
                                                         Map<String, List<String>> isaMap,
                                                         Set<String> visiting) {
        if (DEBUG_DFS) {
            System.out.println("DEBUG DFS: Populating ISA for " + className + ", visiting: " + visiting);
        }

        if (visiting.contains(className)) {
            if (DEBUG_DFS) {
                System.out.println("DEBUG DFS: CYCLE DETECTED! " + className + " already in visiting set: " + visiting);
            }
            throw new PerlCompilerException("Recursive inheritance detected involving class '" + className + "'");
        }

        if (isaMap.containsKey(className)) {
            return; // Already processed
        }

        visiting.add(className);

        // Get current @ISA array - FORCE fresh read
        RuntimeArray isaArray = GlobalVariable.getGlobalArray(className + "::ISA");
        List<String> parents = new ArrayList<>();
        for (RuntimeBase entity : isaArray.elements) {
            String parentName = entity.toString();
            // FIXED: Skip empty or null parent names
            if (parentName != null && !parentName.isEmpty()) {
                parents.add(parentName);
            }
        }

        if (DEBUG_DFS) {
            System.out.println("DEBUG DFS: " + className + " @ISA = " + parents);
        }

        isaMap.put(className, parents);

        // Recursively process parents
        for (String parent : parents) {
            populateIsaMapWithCycleDetection(parent, isaMap, visiting);
        }

        visiting.remove(className);
    }

    private static void linearizeDFSHelper(String className,
                                           Map<String, List<String>> isaMap,
                                           List<String> result,
                                           Set<String> visited,
                                           Set<String> currentPath) {
        if (DEBUG_DFS) {
            System.out.println("DEBUG DFS: Helper visiting " + className + ", path: " + currentPath);
        }

        if (currentPath.contains(className)) {
            if (DEBUG_DFS) {
                System.out.println("DEBUG DFS: CYCLE in linearization! " + className + " in path: " + currentPath);
            }
            throw new PerlCompilerException("Recursive inheritance detected in hierarchy of class '" + className + "'");
        }

        if (visited.contains(className)) {
            return; // Already processed
        }

        currentPath.add(className);
        visited.add(className);
        result.add(className);

        if (DEBUG_DFS) {
            System.out.println("DEBUG DFS: Added " + className + " to result, result so far: " + result);
        }

        // Process parents in order (depth-first)
        List<String> parents = isaMap.getOrDefault(className, Collections.emptyList());
        if (DEBUG_DFS) {
            System.out.println("DEBUG DFS: " + className + " parents: " + parents);
        }

        for (String parent : parents) {
            linearizeDFSHelper(parent, isaMap, result, visited, currentPath);
        }

        currentPath.remove(className);
    }
}
