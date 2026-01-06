package org.perlonjava.runtime;

/**
 * Thread-local registry for non-local control flow markers.
 * This provides an alternative to call-site checks that works around ASM frame computation issues.
 * 
 * When a control flow statement (last/next/redo/goto) executes inside a subroutine,
 * it registers the marker here. Loop boundaries check the registry and handle the marker.
 */
public class RuntimeControlFlowRegistry {
    // Thread-local storage for control flow markers
    private static final ThreadLocal<ControlFlowMarker> currentMarker = new ThreadLocal<>();
    
    /**
     * Register a control flow marker.
     * This is called by last/next/redo/goto when they need to propagate across subroutine boundaries.
     * 
     * @param marker The control flow marker to register
     */
    public static void register(ControlFlowMarker marker) {
        currentMarker.set(marker);
    }
    
    /**
     * Check if there's a pending control flow marker.
     * 
     * @return true if a marker is registered
     */
    public static boolean hasMarker() {
        return currentMarker.get() != null;
    }
    
    /**
     * Get the current control flow marker.
     * 
     * @return The marker, or null if none
     */
    public static ControlFlowMarker getMarker() {
        return currentMarker.get();
    }
    
    /**
     * Clear the current marker.
     * This is called after the marker has been handled by a loop.
     */
    public static void clear() {
        currentMarker.remove();
    }
    
    /**
     * Check if the current marker matches a given label and type.
     * If it matches, clears the marker and returns true.
     * 
     * @param type The control flow type to check (LAST, NEXT, REDO, GOTO)
     * @param label The label to match (null for unlabeled)
     * @return true if the marker matches and was cleared
     */
    public static boolean checkAndClear(ControlFlowType type, String label) {
        ControlFlowMarker marker = currentMarker.get();
        if (marker == null) {
            return false;
        }
        
        // Check if type matches
        if (marker.type != type) {
            return false;
        }
        
        // Check if label matches (Perl semantics)
        if (marker.label == null) {
            // Unlabeled control flow matches any loop
            clear();
            return true;
        }
        
        // Labeled control flow must match exactly
        if (marker.label.equals(label)) {
            clear();
            return true;
        }
        
        return false;
    }
    
    /**
     * Check if the current marker is a GOTO that matches a specific label.
     * Used for goto LABEL (not last/next/redo).
     * 
     * @param label The goto label to check
     * @return true if there's a GOTO marker for this label
     */
    public static boolean checkGoto(String label) {
        ControlFlowMarker marker = currentMarker.get();
        if (marker == null || marker.type != ControlFlowType.GOTO) {
            return false;
        }
        
        if (marker.label != null && marker.label.equals(label)) {
            clear();
            return true;
        }
        
        return false;
    }
    
    /**
     * Check if the current marker (if any) matches the given label.
     * Does NOT clear the marker - just checks if it matches.
     * 
     * @param labelName The label to check against
     * @return true if there's a marker and it matches this label
     */
    public static boolean markerMatchesLabel(String labelName) {
        ControlFlowMarker marker = currentMarker.get();
        if (marker == null) {
            return false;
        }
        
        // Check if marker's label matches (Perl semantics)
        if (marker.label == null) {
            // Unlabeled control flow matches any loop
            return true;
        } else if (labelName == null) {
            // Labeled control flow doesn't match unlabeled loop
            return false;
        } else {
            // Both labeled - must match exactly
            return marker.label.equals(labelName);
        }
    }
    
    /**
     * Check if there's a pending control flow marker for a specific loop label.
     * If the marker matches, clear it and return the action code.
     * If it doesn't match, leave it for an outer loop.
     * 
     * This is called at loop boundaries to check for non-local control flow.
     * 
     * @param labelName The label of the current loop (null for unlabeled loops)
     * @return Action code: 0=no match, 1=LAST, 2=NEXT, 3=REDO
     */
    public static int checkLoopAndGetAction(String labelName) {
        ControlFlowMarker marker = currentMarker.get();
        if (marker == null) {
            return 0;  // No marker
        }
        
        // Check if marker's label matches (Perl semantics)
        boolean labelMatches;
        if (marker.label == null) {
            // Unlabeled control flow matches any loop
            labelMatches = true;
        } else if (labelName == null) {
            // Labeled control flow doesn't match unlabeled loop
            labelMatches = false;
        } else {
            // Both labeled - must match exactly
            labelMatches = marker.label.equals(labelName);
        }
        
        if (!labelMatches) {
            return 0;  // Label doesn't match, leave marker for outer loop
        }
        
        // Label matches - return action and clear marker
        clear();
        
        switch (marker.type) {
            case LAST:
                return 1;
            case NEXT:
                return 2;
            case REDO:
                return 3;
            case GOTO:
                // GOTO markers are handled separately (not by loops)
                // Put it back and return 0
                register(marker);
                return 0;
            case TAILCALL:
                // Shouldn't reach here in a loop
                register(marker);
                return 0;
            default:
                return 0;
        }
    }
    
    /**
     * If there's an uncaught marker at the top level, throw an error.
     * This is called at program exit or when returning from a subroutine to the top level.
     */
    public static void throwIfUncaught() {
        ControlFlowMarker marker = currentMarker.get();
        if (marker != null) {
            clear();
            marker.throwError();
        }
    }
    
    /**
     * Check the registry and wrap the result if needed.
     * This is the SIMPLEST approach to avoid ASM frame computation issues:
     * - No branching in bytecode (no TABLESWITCH, no IF chains, no GOTOs)
     * - Just a single method call that returns either the original or a marked list
     * - All the logic is in Java code, not bytecode
     * 
     * @param result The original result from the subroutine call
     * @param labelName The loop's label (null for unlabeled)
     * @return Either the original result or a marked RuntimeControlFlowList
     */
    public static RuntimeList checkAndWrapIfNeeded(RuntimeList result, String labelName) {
        int action = checkLoopAndGetAction(labelName);
        if (action == 0) {
            // No action - return original result
            return result;
        }
        // Action detected - return a marked RuntimeControlFlowList
        return RuntimeControlFlowList.createFromAction(action, labelName);
    }
}

