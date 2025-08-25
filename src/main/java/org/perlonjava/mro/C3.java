package org.perlonjava.mro;

import org.perlonjava.runtime.PerlCompilerException;
import java.util.*;

public class C3 {
    /**
     * Linearizes the inheritance hierarchy of a class using the C3 algorithm.
     *
     * @param className The name of the class to linearize.
     * @return A list of class names in the order of method resolution.
     */
    public static List<String> linearizeC3(String className) {
        String cacheKey = className + "::C3";
        List<String> result = InheritanceResolver.linearizedClassesCache.get(cacheKey);
        if (result == null) {
            Map<String, List<String>> isaMap = new HashMap<>();
            InheritanceResolver.populateIsaMap(className, isaMap);

            Set<String> visiting = new HashSet<>();
            result = linearizeC3Helper(className, isaMap, visiting);

            // Add UNIVERSAL only if not already present and this is not UNIVERSAL itself
            if (!result.contains("UNIVERSAL") && !className.equals("UNIVERSAL")) {
                result.add("UNIVERSAL");
            }

            InheritanceResolver.linearizedClassesCache.put(cacheKey, result);
        }
        return result;
    }

    /**
     * Helper method to perform the C3 linearization.
     *
     * @param className The name of the class to linearize.
     * @param isaMap    A map containing the @ISA arrays for each class.
     * @return A list of class names in the order of method resolution.
     */
    private static List<String> linearizeC3Helper(String className,
                                                  Map<String, List<String>> isaMap,
                                                  Set<String> visiting) {
        // Check for circular inheritance
        if (visiting.contains(className)) {
            throw new PerlCompilerException("Recursive inheritance detected in hierarchy involving class '" + className + "'");
        }

        visiting.add(className);

        List<String> parents = isaMap.getOrDefault(className, Collections.emptyList());

        // If the class has no parents, return the class itself
        if (parents.isEmpty()) {
            visiting.remove(className);
            return Arrays.asList(className);
        }

        // Get linearizations of each parent
        List<List<String>> linearizations = new ArrayList<>();
        try {
            for (String parent : parents) {
                linearizations.add(new ArrayList<>(linearizeC3Helper(parent, isaMap, visiting)));
            }
        } finally {
            visiting.remove(className);  // Ensure we remove even if exception occurs
        }

        // Add the parents list itself to the linearizations for merging
        linearizations.add(new ArrayList<>(parents));

        // Start with the current class
        List<String> result = new ArrayList<>();
        result.add(className);

        // Merge the linearizations using the C3 algorithm
        while (!linearizations.isEmpty()) {
            String candidate = null;
            boolean found = false;

            // Find a good candidate (appears at the head of some list and not in the tail of any)
            for (List<String> linearization : linearizations) {
                if (!linearization.isEmpty()) {
                    candidate = linearization.get(0);
                    boolean isGoodCandidate = true;

                    // Check if this candidate appears in the tail of any other linearization
                    for (List<String> other : linearizations) {
                        if (other.size() > 1 && other.subList(1, other.size()).contains(candidate)) {
                            isGoodCandidate = false;
                            break;
                        }
                    }

                    if (isGoodCandidate) {
                        found = true;
                        break;
                    }
                }
            }

            if (!found || candidate == null) {
                throw new PerlCompilerException("Inconsistent hierarchy detected in C3 linearization for " + className);
            }

            // Add the candidate to result
            result.add(candidate);

            // Remove the candidate from all linearizations where it appears at the head
            Iterator<List<String>> iter = linearizations.iterator();
            while (iter.hasNext()) {
                List<String> linearization = iter.next();
                if (!linearization.isEmpty() && linearization.get(0).equals(candidate)) {
                    linearization.remove(0);
                }
                // Remove empty linearizations
                if (linearization.isEmpty()) {
                    iter.remove();
                }
            }
        }

        return result;
    }
}
