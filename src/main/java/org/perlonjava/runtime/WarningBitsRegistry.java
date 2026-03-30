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
    
    // ThreadLocal tracking the warning bits at the current call site.
    // Updated at runtime when 'use warnings' / 'no warnings' pragmas are encountered.
    // This provides per-statement warning bits (like Perl 5's per-COP bits).
    private static final ThreadLocal<String> callSiteBits = 
        ThreadLocal.withInitial(() -> null);
    
    // ThreadLocal stack saving caller's call-site bits across subroutine calls.
    // Each apply() pushes the current callSiteBits before calling the subroutine,
    // and pops it when the subroutine returns. This allows caller()[9] to return
    // the correct per-call-site warning bits.
    private static final ThreadLocal<Deque<String>> callerBitsStack = 
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
        callSiteBits.remove();
        callerBitsStack.get().clear();
    }
    
    /**
     * Sets the warning bits for the current call site.
     * Called at runtime when 'use warnings' / 'no warnings' pragmas are encountered.
     * This provides per-statement granularity for caller()[9].
     *
     * @param bits The warning bits string for the current call site
     */
    public static void setCallSiteBits(String bits) {
        callSiteBits.set(bits);
    }
    
    /**
     * Gets the warning bits for the current call site.
     *
     * @return The current call-site warning bits, or null if not set
     */
    public static String getCallSiteBits() {
        return callSiteBits.get();
    }
    
    /**
     * Saves the current call-site bits onto the caller stack.
     * Called by RuntimeCode.apply() before entering a subroutine.
     * This preserves the caller's warning bits so caller()[9] can retrieve them.
     */
    public static void pushCallerBits() {
        String bits = callSiteBits.get();
        callerBitsStack.get().push(bits != null ? bits : "");
    }
    
    /**
     * Restores the caller's call-site bits from the caller stack.
     * Called by RuntimeCode.apply() after a subroutine returns.
     */
    public static void popCallerBits() {
        Deque<String> stack = callerBitsStack.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
    }
    
    /**
     * Gets the caller's warning bits at a given frame depth.
     * Frame 0 = immediate caller, frame 1 = caller's caller, etc.
     * Used by caller()[9] for per-call-site warning bits.
     *
     * @param frame The frame depth (0 = immediate caller)
     * @return The warning bits string, or null if not available
     */
    public static String getCallerBitsAtFrame(int frame) {
        Deque<String> stack = callerBitsStack.get();
        if (stack.isEmpty()) {
            return null;
        }
        // Stack is LIFO: top = most recent caller (frame 0)
        int index = 0;
        for (String bits : stack) {
            if (index == frame) {
                return bits.isEmpty() ? null : bits;
            }
            index++;
        }
        return null;
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
