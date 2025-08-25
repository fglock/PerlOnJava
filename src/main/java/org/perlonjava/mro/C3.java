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

            result.add("UNIVERSAL");
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

        List<String> result = new ArrayList<>();
        List<String> parents = isaMap.getOrDefault(className, Collections.emptyList());

        // If the class has no parents, return the class itself
        if (parents.isEmpty()) {
            visiting.remove(className);
            result.add(className);
            return result;
        }

        // List of linearizations of each parent
        List<List<String>> linearizations = new ArrayList<>();
        try {
            for (String parent : parents) {
                linearizations.add(new ArrayList<>(linearizeC3Helper(parent, isaMap, visiting)));
            }
        } finally {
            visiting.remove(className);  // Ensure we remove even if exception occurs
        }

        // Add the parents list itself to the linearizations
        linearizations.add(new ArrayList<>(parents));

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
                throw new PerlCompilerException("Inconsistent hierarchy detected in C3 linearization");
            }

            result.add(candidate);
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
}
