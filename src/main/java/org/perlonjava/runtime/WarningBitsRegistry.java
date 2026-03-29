package org.perlonjava.runtime;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for per-closure warning bits storage.
 * 
 * Each subroutine/closure stores its compile-time warning bits here,
 * allowing caller() to return accurate warning bits for any stack frame.
 * 
 * JVM Backend: Classes register their bits in static initializer.
 * Interpreter Backend: InterpretedCode registers bits in constructor.
 * 
 * At runtime, caller() looks up warning bits by class name.
 */
public class WarningBitsRegistry {
    
    // Map from fully-qualified class name to warning bits string
    private static final ConcurrentHashMap<String, String> registry = 
        new ConcurrentHashMap<>();
    
    /**
     * Registers the warning bits for a class.
     * Called at class load time (static initializer) for JVM backend,
     * or from InterpretedCode constructor for interpreter backend.
     *
     * @param className The fully-qualified class name
     * @param bits The Perl 5 compatible warning bits string
     */
    public static void register(String className, String bits) {
        if (className != null && bits != null) {
            registry.put(className, bits);
        }
    }
    
    /**
     * Gets the warning bits for a class.
     * Called by caller() to retrieve warning bits for a stack frame.
     *
     * @param className The fully-qualified class name
     * @return The warning bits string, or null if not registered
     */
    public static String get(String className) {
        if (className == null) {
            return null;
        }
        return registry.get(className);
    }
    
    /**
     * Clears all registered warning bits.
     * Called by PerlLanguageProvider.resetAll() during reinitialization.
     */
    public static void clear() {
        registry.clear();
    }
    
    /**
     * Returns the number of registered classes.
     * Useful for debugging and testing.
     *
     * @return The number of registered class → bits mappings
     */
    public static int size() {
        return registry.size();
    }
}
