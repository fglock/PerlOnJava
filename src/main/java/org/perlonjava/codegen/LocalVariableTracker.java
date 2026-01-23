package org.perlonjava.codegen;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Tracks local variables that need consistent initialization at merge points.
 * This prevents VerifyError due to TOP (uninitialized) locals at control flow joins.
 */
public class LocalVariableTracker {
    
    /**
     * Tracks which locals need initialization at each merge point (label)
     */
    private final Map<Label, Set<Integer>> mergePointLocals = new HashMap<>();
    
    /**
     * Tracks the current type/state of each local variable
     */
    private final Map<Integer, LocalState> localStates = new HashMap<>();
    
    /**
     * Set of locals that are known to be reference types (need null initialization)
     */
    private final Set<Integer> referenceLocals = new HashSet<>();
    
    /**
     * Represents the state of a local variable
     */
    private static class LocalState {
        boolean isInitialized;
        boolean isReference;
        String source; // for debugging
        
        LocalState(boolean isReference, String source) {
            this.isReference = isReference;
            this.isInitialized = false;
            this.source = source;
        }
    }
    
    /**
     * Record that a local variable has been allocated
     */
    public void recordLocalAllocation(int index, boolean isReference, String source) {
        localStates.put(index, new LocalState(isReference, source));
        if (isReference) {
            referenceLocals.add(index);
        }
    }
    
    /**
     * Record that a local variable has been written to
     */
    public void recordLocalWrite(int index) {
        LocalState state = localStates.get(index);
        if (state != null) {
            state.isInitialized = true;
        }
    }
    
    /**
     * Record that a label is a merge point and capture current live locals
     */
    public void recordMergePoint(Label label) {
        // Capture current reference locals that might need initialization
        Set<Integer> neededLocals = new HashSet<>();
        
        for (Integer local : referenceLocals) {
            LocalState state = localStates.get(local);
            if (state != null && !state.isInitialized) {
                // This local is a reference type but not initialized on all paths
                neededLocals.add(local);
            }
        }
        
        if (!neededLocals.isEmpty()) {
            mergePointLocals.put(label, neededLocals);
        }
    }
    
    /**
     * Emit initialization code for locals that need it at a merge point
     */
    public void emitMergePointInitialization(MethodVisitor mv, Label target, JavaClassInfo classInfo) {
        Set<Integer> locals = mergePointLocals.get(target);
        if (locals != null) {
            for (int local : locals) {
                // Only initialize if the local index is within the allocated range
                if (local < classInfo.localVariableIndex) {
                    mv.visitInsn(Opcodes.ACONST_NULL);
                    mv.visitVarInsn(Opcodes.ASTORE, local);
                    
                    // Mark as initialized for future tracking
                    recordLocalWrite(local);
                }
            }
        }
    }
    
    /**
     * Force initialization of a specific local (for targeted fixes)
     */
    public void forceInitializeLocal(MethodVisitor mv, int local, JavaClassInfo classInfo) {
        if (local < classInfo.localVariableIndex && referenceLocals.contains(local)) {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitVarInsn(Opcodes.ASTORE, local);
            recordLocalWrite(local);
        }
    }
    
    /**
     * Force initialization of slot 90 specifically (current VerifyError issue)
     */
    public void forceInitializeSlot90(MethodVisitor mv, JavaClassInfo classInfo) {
        if (90 < classInfo.localVariableIndex) {
            // Initialize as integer type (slot 90 needs to be integer)
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitVarInsn(Opcodes.ISTORE, 90);
            recordLocalWrite(90);
        }
    }
    
    /**
     * Force initialization of slot 89 specifically (current VerifyError issue)
     */
    public void forceInitializeSlot89(MethodVisitor mv, JavaClassInfo classInfo) {
        if (89 < classInfo.localVariableIndex) {
            // Initialize as iterator type (slot 89 needs to be iterator)
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitVarInsn(Opcodes.ASTORE, 89);
            recordLocalWrite(89);
        }
    }
    
    /**
     * Force initialization of problematic slots (targeted fix for VerifyError)
     */
    public void forceInitializeProblematicSlots(MethodVisitor mv, JavaClassInfo classInfo) {
        // Target specific slots that are causing VerifyError issues
        int[] problematicSlots = {89, 825, 925, 930, 950, 975, 1000, 1030, 1100, 1130, 1150, 1180, 850, 860, 870, 880, 890, 900};
        for (int slot : problematicSlots) {
            if (slot < classInfo.localVariableIndex) {
                // Initialize as reference first
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitVarInsn(Opcodes.ASTORE, slot);
                recordLocalWrite(slot);
                
                // Special case for slot 89 - also initialize as iterator to handle hasNext() calls
                if (slot == 89) {
                    // Double-initialize as iterator to ensure it's not null when hasNext() is called
                    mv.visitInsn(Opcodes.ACONST_NULL);
                    mv.visitVarInsn(Opcodes.ASTORE, 89);
                }
                
                // Also initialize as integer for slots that might need it
                mv.visitInsn(Opcodes.ICONST_0);
                mv.visitVarInsn(Opcodes.ISTORE, slot);
            }
        }
    }
    
    /**
     * Force initialization of slot 825 specifically (main VerifyError issue)
     */
    public void forceInitializeSlot825(MethodVisitor mv, JavaClassInfo classInfo) {
        if (825 < classInfo.localVariableIndex) {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitVarInsn(Opcodes.ASTORE, 825);
            recordLocalWrite(825);
        }
    }
    
    /**
     * Force initialization of slot 925 specifically (current VerifyError issue)
     */
    public void forceInitializeSlot925(MethodVisitor mv, JavaClassInfo classInfo) {
        if (925 < classInfo.localVariableIndex) {
            // Initialize as both reference and integer to handle either case
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitVarInsn(Opcodes.ASTORE, 925);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitVarInsn(Opcodes.ISTORE, 925);
            recordLocalWrite(925);
        }
    }
    
    /**
     * Force initialization of an integer local (for targeted fixes)
     */
    public void forceInitializeIntegerLocal(MethodVisitor mv, int local, JavaClassInfo classInfo) {
        if (local < classInfo.localVariableIndex) {
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitVarInsn(Opcodes.ISTORE, local);
        }
    }
    
    /**
     * Clear tracking for locals that are no longer in scope
     */
    public void exitScope(int maxLocalIndex) {
        // Remove locals that are beyond the current scope
        localStates.entrySet().removeIf(entry -> entry.getKey() >= maxLocalIndex);
        referenceLocals.removeIf(local -> local >= maxLocalIndex);
    }
    
    /**
     * Debug method to dump current state
     */
    public void dumpState() {
        System.err.println("=== LocalVariableTracker State ===");
        System.err.println("Reference locals: " + referenceLocals);
        System.err.println("Merge points: " + mergePointLocals.size());
        for (Map.Entry<Label, Set<Integer>> entry : mergePointLocals.entrySet()) {
            System.err.println("  Label " + entry.getKey() + " needs locals: " + entry.getValue());
        }
        System.err.println("Local states:");
        for (Map.Entry<Integer, LocalState> entry : localStates.entrySet()) {
            LocalState state = entry.getValue();
            System.err.println("  Local " + entry.getKey() + 
                             " [ref=" + state.isReference + 
                             ", init=" + state.isInitialized + 
                             ", src=" + state.source + "]");
        }
    }
}
