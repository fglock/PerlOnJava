package org.perlonjava.mro;

import org.perlonjava.runtime.GlobalVariable;
import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeArray;
import org.perlonjava.runtime.RuntimeBase;

import java.util.*;

public class DFS {
    private static final boolean DEBUG_DFS = Boolean.getBoolean("debug.dfs");

    public static List<String> linearizeDFS(String className) {
        if (DEBUG_DFS) {
            System.out.println("DEBUG DFS: Starting linearization for " + className);
        }

        // FIXED: Don't use cache for now to ensure we see ISA changes
        // Check cache first - but invalidate if we suspect changes
        String cacheKey = className + "::DFS";

        // Always recompute for now to catch ISA changes
        // In the future, this should be improved with proper cache invalidation
        if (DEBUG_DFS) {
            System.out.println("DEBUG DFS: Recomputing (cache disabled for debugging)");
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

        // Perform DFS linearization
        Set<String> visited = new LinkedHashSet<>();
        Set<String> currentPath = new HashSet<>();

        linearizeDFSHelper(className, isaMap, visited, currentPath);

        List<String> result = new ArrayList<>(visited);
        result.add("UNIVERSAL");

        if (DEBUG_DFS) {
            System.out.println("DEBUG DFS: Final linearization: " + result);
        }

        // Cache the result
        InheritanceResolver.linearizedClassesCache.put(cacheKey, result);
        return result;
    }

    /**
     * FIXED: New method to populate ISA map with proper cycle detection
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
            parents.add(entity.toString());
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

        currentPath.add(className);

        if (!visited.contains(className)) {
            visited.add(className);
        }

        List<String> parents = isaMap.getOrDefault(className, Collections.emptyList());
        for (String parent : parents) {
            if (!visited.contains(parent)) {
                linearizeDFSHelper(parent, isaMap, visited, currentPath);
            }
        }

        currentPath.remove(className);
    }
}
