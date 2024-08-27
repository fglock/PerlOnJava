package org.perlonjava.runtime;

import java.util.*;

public class InheritanceResolver {

    /**
     * Linearizes the inheritance hierarchy of a class using the C3 algorithm.
     *
     * @param className The name of the class to linearize.
     * @return A list of class names in the order of method resolution.
     */
    public static List<String> linearizeC3(String className) {
        Map<String, List<String>> isaMap = new HashMap<>();
        // Populate isaMap with @ISA arrays for each class
        // Example: isaMap.put("D", Arrays.asList("B", "C"));

        return linearizeC3Helper(className, isaMap);
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
            for (List<String> linearization : linearizations) {
                if (!linearization.isEmpty() && linearization.get(0).equals(candidate)) {
                    linearization.remove(0);
                }
            }
            linearizations.removeIf(List::isEmpty);
        }

        result.add(className);
        return result;
    }
}

