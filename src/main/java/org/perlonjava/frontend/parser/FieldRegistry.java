package org.perlonjava.frontend.parser;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.perlonjava.runtime.runtimetypes.PerlRuntime;

/**
 * Runtime-owned registry for tracking field declarations across class hierarchy at parse time.
 * This works when parent classes are parsed before child classes (common case).
 * <p>
 * Unlike the symbol table which is scoped, this registry persists within a runtime
 * across class boundaries, allowing child classes to look up parent fields.
 */
public class FieldRegistry {
    private static Map<String, Set<String>> classFields() {
        return PerlRuntime.current().globalState().classFields();
    }

    private static Map<String, String> classParents() {
        return PerlRuntime.current().globalState().classParents();
    }

    private static Map<String, Set<String>> classParameters() {
        return PerlRuntime.current().globalState().classParameters();
    }

    /**
     * Register a field declaration in a class
     */
    public static void registerField(String className, String fieldName) {
        classFields().computeIfAbsent(className, k -> new HashSet<>()).add(fieldName);
    }

    /**
     * Register a parent class relationship from :isa() attribute
     * Called when we parse :isa(Parent) at parse time
     */
    public static void registerParentClass(String childClass, String parentClass) {
        classParents().put(childClass, parentClass);
    }

    /**
     * Get all fields from a class (not including inherited fields)
     */
    public static Set<String> getClassFields(String className) {
        return classFields().getOrDefault(className, new HashSet<>());
    }

    /**
     * Get the parent class of a given class
     */
    public static String getParentClass(String className) {
        return classParents().get(className);
    }

    public static Set<String> getParameterNamesInHierarchy(String className) {
        Set<String> names = new HashSet<>();
        Set<String> visited = new HashSet<>();
        String current = className;
        while (current != null && visited.add(current)) {
            names.addAll(classParameters().getOrDefault(current, Set.of()));
            current = classParents().get(current);
        }
        return names;
    }

    public static void registerParameterName(String className, String parameterName) {
        classParameters().computeIfAbsent(className, ignored -> new HashSet<>()).add(parameterName);
    }

    /**
     * Check if a field exists in the class hierarchy
     * This works if parent classes were parsed before child classes
     */
    public static boolean hasFieldInHierarchy(String className, String fieldName) {
        Set<String> visited = new HashSet<>();
        return hasFieldInHierarchyHelper(className, fieldName, visited);
    }

    /**
     * Returns whether {@code candidateAncestor} is {@code className} or one
     * of its declared class parents.  Field names alone are insufficient for
     * access control: a nested class can see its enclosing parser scope, but
     * must not inherit that enclosing class's fields.
     */
    public static boolean isClassOrAncestor(String className, String candidateAncestor) {
        Set<String> visited = new HashSet<>();
        String current = className;
        while (current != null && visited.add(current)) {
            if (current.equals(candidateAncestor)) {
                return true;
            }
            current = classParents().get(current);
        }
        return false;
    }

    private static boolean hasFieldInHierarchyHelper(String className, String fieldName, Set<String> visited) {
        if (className == null || visited.contains(className)) {
            return false;  // Avoid infinite loops
        }
        visited.add(className);

        // Check if field exists in current class
        Set<String> fields = classFields().get(className);
        if (fields != null && fields.contains(fieldName)) {
            return true;
        }

        // Check parent class recursively (if it was parsed)
        String parentClass = classParents().get(className);
        if (parentClass != null) {
            return hasFieldInHierarchyHelper(parentClass, fieldName, visited);
        }

        return false;
    }

    /**
     * Clear the registry (useful for testing)
     */
    public static void clear() {
        classParents().clear();
        classFields().clear();
        classParameters().clear();
    }
}
