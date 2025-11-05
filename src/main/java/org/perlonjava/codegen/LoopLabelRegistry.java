package org.perlonjava.codegen;

import org.objectweb.asm.Label;

import java.util.HashMap;
import java.util.Map;

/**
 * Registry for loop exception handler labels to enable handler chaining in nested loops.
 * 
 * <h2>The Problem with Nested Loops</h2>
 * When an outer loop's exception handler catches an exception meant for an inner loop,
 * it needs to delegate to the inner loop's handler instead of rethrowing (which would
 * propagate to the caller, not to the inner handler).
 * 
 * <h2>The Solution: Handler Chaining via GOTO</h2>
 * Instead of ATHROW, outer handlers can GOTO to inner handlers if the label matches:
 * <pre>{@code
 * OUTER_catch_next:
 *   if (exception.label == "OUTER") { handle locally }
 *   else if (exception.label == "INNER") { GOTO INNER_catch_next }  // Delegate!
 *   else { ATHROW }  // Unknown label, propagate up
 * }</pre>
 * 
 * This registry allows outer loops to look up inner loop handler labels by name.
 */
public class LoopLabelRegistry {
    
    /**
     * Thread-local registry mapping label names to their exception handler labels.
     * Each entry maps: "LABEL_NAME" -> {nextHandler, lastHandler, redoHandler}
     */
    private static final ThreadLocal<Map<String, HandlerLabels>> registry = 
        ThreadLocal.withInitial(HashMap::new);
    
    /**
     * Handler labels for a single loop.
     */
    public static class HandlerLabels {
        public final Label catchNext;
        public final Label catchLast;
        public final Label catchRedo;
        
        public HandlerLabels(Label catchNext, Label catchLast, Label catchRedo) {
            this.catchNext = catchNext;
            this.catchLast = catchLast;
            this.catchRedo = catchRedo;
        }
    }
    
    /**
     * Register a loop's exception handler labels.
     * 
     * @param labelName The loop's label name (e.g., "INNER"), or null for unlabeled loops
     * @param catchNext Label for NextException handler
     * @param catchLast Label for LastException handler  
     * @param catchRedo Label for RedoException handler
     */
    public static void register(String labelName, Label catchNext, Label catchLast, Label catchRedo) {
        if (labelName != null) {
            registry.get().put(labelName, new HandlerLabels(catchNext, catchLast, catchRedo));
        }
    }
    
    /**
     * Unregister a loop's handler labels when exiting the loop.
     * 
     * @param labelName The loop's label name to unregister
     */
    public static void unregister(String labelName) {
        if (labelName != null) {
            registry.get().remove(labelName);
        }
    }
    
    /**
     * Look up handler labels for a given loop label name.
     * 
     * @param labelName The label name to look up
     * @return The handler labels, or null if not found
     */
    public static HandlerLabels lookup(String labelName) {
        return labelName != null ? registry.get().get(labelName) : null;
    }
    
    /**
     * Get all currently registered handlers (for delegation logic).
     * 
     * @return A copy of the current registry map
     */
    public static Map<String, HandlerLabels> getAllRegistered() {
        return new HashMap<>(registry.get());
    }
    
    /**
     * Clear all registered handlers (for cleanup/testing).
     */
    public static void clear() {
        registry.get().clear();
    }
}

