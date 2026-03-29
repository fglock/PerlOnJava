package org.perlonjava.runtime;

import java.util.ArrayDeque;
import java.util.Deque;
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
 * 
 * Additionally, a ThreadLocal stack tracks the "current" warning bits
 * for runtime code that needs to check FATAL warnings.
 */
public class WarningBitsRegistry {
    
    // Map from fully-qualified class name to warning bits string
    private static final ConcurrentHashMap<String, String> registry = 
        new ConcurrentHashMap<>();
    
    // ThreadLocal stack of warning bits for the current execution context
    // This allows runtime code to find warning bits even at top-level (no subroutine frame)
    private static final ThreadLocal<Deque<String>> currentBitsStack = 
        ThreadLocal.withInitial(ArrayDeque::new);
    
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
     * Pushes warning bits onto the current context stack.
     * Called when entering a subroutine or code block with warning settings.
     *
     * @param bits The warning bits string
     */
    public static void pushCurrent(String bits) {
        if (bits != null) {
            currentBitsStack.get().push(bits);
        }
    }
    
    /**
     * Pops warning bits from the current context stack.
     * Called when exiting a subroutine or code block.
     */
    public static void popCurrent() {
        Deque<String> stack = currentBitsStack.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
    }
    
    /**
     * Gets the current warning bits from the context stack.
     * Used by runtime code to check FATAL warnings.
     *
     * @return The current warning bits string, or null if stack is empty
     */
    public static String getCurrent() {
        Deque<String> stack = currentBitsStack.get();
        return stack.isEmpty() ? null : stack.peek();
    }
    
    /**
     * Clears all registered warning bits and the current context stack.
     * Called by PerlLanguageProvider.resetAll() during reinitialization.
     */
    public static void clear() {
        registry.clear();
        currentBitsStack.get().clear();
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
