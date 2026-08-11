package org.perlonjava.runtime.runtimetypes;

import java.util.Set;

/**
 * Runtime-owned registry of packages that are Perl 5.38+ classes (not just regular packages).
 * This is used to determine whether blessed objects should stringify as OBJECT or HASH.
 */
public class ClassRegistry {
    private static Set<String> classNames() {
        return PerlRuntime.current().globalState().classNames();
    }

    /**
     * Register a package name as a Perl 5.38+ class.
     * Called when parsing a class block.
     *
     * @param className The name of the class
     */
    public static void registerClass(String className) {
        classNames().add(className);
    }

    /**
     * Check if a package name is a registered Perl 5.38+ class.
     *
     * @param packageName The package name to check
     * @return true if this is a class, false if it's a regular package
     */
    public static boolean isClass(String packageName) {
        return classNames().contains(packageName);
    }

    /**
     * Clear all registered classes (useful for testing).
     */
    public static void clear() {
        classNames().clear();
    }
}
