package org.perlonjava.runtime;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

import org.perlonjava.runtime.runtimetypes.GlobalContext;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.RuntimeHash;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

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
    
    // ThreadLocal tracking the compile-time $^H (hints) at the current call site.
    // Updated at runtime when pragmas (use strict, etc.) are encountered.
    // This provides per-statement hints for caller()[8].
    private static final ThreadLocal<Integer> callSiteHints = 
        ThreadLocal.withInitial(() -> 0);
    
    // ThreadLocal stack saving caller's $^H hints across subroutine calls.
    // Mirrors callerBitsStack but for $^H instead of warning bits.
    private static final ThreadLocal<Deque<Integer>> callerHintsStack = 
        ThreadLocal.withInitial(ArrayDeque::new);
    
    // ThreadLocal tracking the compile-time %^H (hints hash) at the current call site.
    // Updated at runtime when pragmas modify %^H.
    // This provides per-statement hints hash for caller()[10].
    private static final ThreadLocal<java.util.Map<String, org.perlonjava.runtime.runtimetypes.RuntimeScalar>> callSiteHintHash = 
        ThreadLocal.withInitial(java.util.HashMap::new);
    
    // ThreadLocal stack saving caller's %^H across subroutine calls.
    private static final ThreadLocal<Deque<java.util.Map<String, org.perlonjava.runtime.runtimetypes.RuntimeScalar>>> callerHintHashStack = 
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
        callSiteHints.remove();
        callerHintsStack.get().clear();
        callSiteHintHash.get().clear();
        callerHintHashStack.get().clear();
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
    
    // ===== $^H (hints) support for caller()[8] =====
    
    /**
     * Sets the compile-time $^H value for the current call site.
     * Called at runtime when pragmas (use strict, etc.) are encountered.
     *
     * @param hints The $^H bitmask
     */
    public static void setCallSiteHints(int hints) {
        callSiteHints.set(hints);
    }
    
    /**
     * Gets the $^H value for the current call site.
     *
     * @return The current call-site $^H value
     */
    public static int getCallSiteHints() {
        return callSiteHints.get();
    }
    
    /**
     * Saves the current call-site $^H onto the caller stack.
     * Called by RuntimeCode.apply() before entering a subroutine.
     */
    public static void pushCallerHints() {
        callerHintsStack.get().push(callSiteHints.get());
    }
    
    /**
     * Restores the caller's $^H from the caller stack.
     * Called by RuntimeCode.apply() after a subroutine returns.
     */
    public static void popCallerHints() {
        Deque<Integer> stack = callerHintsStack.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
    }
    
    /**
     * Gets the caller's $^H at a given frame depth.
     * Frame 0 = immediate caller, frame 1 = caller's caller, etc.
     * Used by caller()[8].
     *
     * @param frame The frame depth (0 = immediate caller)
     * @return The $^H value, or -1 if not available
     */
    public static int getCallerHintsAtFrame(int frame) {
        Deque<Integer> stack = callerHintsStack.get();
        if (stack.isEmpty()) {
            return -1;
        }
        int index = 0;
        for (int hints : stack) {
            if (index == frame) {
                return hints;
            }
            index++;
        }
        return -1;
    }
    
    // ===== %^H (hints hash) support for caller()[10] =====
    
    /**
     * Sets the compile-time %^H snapshot for the current call site.
     * Called at runtime when pragmas modify %^H.
     *
     * @param hintHash A snapshot of the %^H hash elements
     */
    public static void setCallSiteHintHash(java.util.Map<String, org.perlonjava.runtime.runtimetypes.RuntimeScalar> hintHash) {
        callSiteHintHash.set(hintHash != null ? new java.util.HashMap<>(hintHash) : new java.util.HashMap<>());
    }
    
    /**
     * Snapshots the current global %^H hash into callSiteHintHash.
     * Called from emitted bytecode when pragmas change.
     */
    public static void snapshotCurrentHintHash() {
        RuntimeHash hintHash = GlobalVariable.getGlobalHash(GlobalContext.encodeSpecialVar("H"));
        setCallSiteHintHash(hintHash.elements);
    }
    
    /**
     * Saves the current call-site %^H onto the caller stack.
     * Called by RuntimeCode.apply() before entering a subroutine.
     */
    public static void pushCallerHintHash() {
        callerHintHashStack.get().push(new java.util.HashMap<>(callSiteHintHash.get()));
    }
    
    /**
     * Restores the caller's %^H from the caller stack.
     * Called by RuntimeCode.apply() after a subroutine returns.
     */
    public static void popCallerHintHash() {
        Deque<java.util.Map<String, org.perlonjava.runtime.runtimetypes.RuntimeScalar>> stack = callerHintHashStack.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
    }
    
    /**
     * Gets the caller's %^H at a given frame depth.
     * Frame 0 = immediate caller, frame 1 = caller's caller, etc.
     * Used by caller()[10].
     *
     * @param frame The frame depth (0 = immediate caller)
     * @return A copy of the %^H hash elements, or null if not available
     */
    public static java.util.Map<String, org.perlonjava.runtime.runtimetypes.RuntimeScalar> getCallerHintHashAtFrame(int frame) {
        Deque<java.util.Map<String, org.perlonjava.runtime.runtimetypes.RuntimeScalar>> stack = callerHintHashStack.get();
        if (stack.isEmpty()) {
            return null;
        }
        int index = 0;
        for (java.util.Map<String, org.perlonjava.runtime.runtimetypes.RuntimeScalar> hash : stack) {
            if (index == frame) {
                return hash.isEmpty() ? null : hash;
            }
            index++;
        }
        return null;
    }
}
