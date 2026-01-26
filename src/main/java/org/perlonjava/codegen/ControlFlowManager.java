package org.perlonjava.codegen;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.HashSet;
import java.util.Set;

/**
 * Manager for handling control flow jumps with local variable consistency
 * to prevent StackMap frame verification errors.
 */
public class ControlFlowManager {
    
    // Track which locals need initialization at merge points
    private final Set<Integer> mergePointLocals = new HashSet<>();
    
    /**
     * Emit a jump with proper local variable consistency handling.
     * This ensures that all locals have consistent types at merge points.
     */
    public void emitJumpWithLocalConsistency(MethodVisitor mv, Label target, 
                                            JavaClassInfo classInfo, 
                                            int targetStackLevel) {
        // 1. Pop stack to target level
        classInfo.stackLevelManager.emitPopInstructions(mv, targetStackLevel);
        
        // 2. Initialize any locals that might be TOP at merge point
        for (int local : mergePointLocals) {
            if (local < classInfo.localVariableIndex) {
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitVarInsn(Opcodes.ASTORE, local);
            }
        }
        
        // 3. Clear spill slots
        classInfo.emitClearSpillSlots(mv);
        
        // 4. Jump
        mv.visitJumpInsn(Opcodes.GOTO, target);
    }
    
    /**
     * Mark a label as a merge point that requires local variable consistency.
     * @param label The merge point label
     * @param criticalLocals Array of local variable indices that need consistency
     */
    public void markMergePoint(Label label, int... criticalLocals) {
        // Track locals that need consistency at this merge point
        for (int local : criticalLocals) {
            mergePointLocals.add(local);
        }
    }
    
    /**
     * Initialize a specific local variable to prevent TOP state at merge points.
     * @param mv Method visitor
     * @param localIndex Local variable index
     */
    public void initializeLocalForMerge(MethodVisitor mv, int localIndex) {
        // Initialize as null to ensure consistent type
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitVarInsn(Opcodes.ASTORE, localIndex);
    }
    
    /**
     * Reset the control flow manager for a new compilation unit.
     */
    public void reset() {
        mergePointLocals.clear();
    }
}
