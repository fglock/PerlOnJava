package org.perlonjava.codegen;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.MethodVisitor;

import java.util.HashMap;
import java.util.Map;

/**
 * Manager for handling closure capture variables with type consistency
 * across anonymous class boundaries to prevent slot type collisions.
 */
public class ClosureCaptureManager {
    
    private static class CaptureDescriptor {
        String variableName;
        Class<?> capturedType;
        int originalSlot;
        int mappedSlot;
        
        CaptureDescriptor(String variableName, Class<?> capturedType, int originalSlot, int mappedSlot) {
            this.variableName = variableName;
            this.capturedType = capturedType;
            this.originalSlot = originalSlot;
            this.mappedSlot = mappedSlot;
        }
    }
    
    private final Map<String, CaptureDescriptor> captureTable = new HashMap<>();
    private int nextCaptureSlot = 3; // Start after 'this' and parameters
    
    /**
     * Allocate a capture slot for a variable, ensuring type consistency.
     */
    public int allocateCaptureSlot(String varName, Class<?> type, String anonymousClassName) {
        String key = anonymousClassName + ":" + varName;
        
        CaptureDescriptor existing = captureTable.get(key);
        if (existing != null) {
            // Verify type consistency
            if (!existing.capturedType.equals(type)) {
                // Type mismatch - allocate new slot
                return allocateNewSlot(varName, type, anonymousClassName);
            }
            return existing.mappedSlot;
        }
        
        // New capture - allocate slot based on type
        return allocateNewSlot(varName, type, anonymousClassName);
    }
    
    private int allocateNewSlot(String varName, Class<?> type, String anonymousClassName) {
        // Use type-specific slot pools to avoid conflicts
        int slot = getSlotForType(type);
        
        CaptureDescriptor descriptor = new CaptureDescriptor(varName, type, -1, slot);
        captureTable.put(anonymousClassName + ":" + varName, descriptor);
        return slot;
    }
    
    private final Map<Class<?>, Integer> typeSlotPools = new HashMap<>();
    
    private int getSlotForType(Class<?> type) {
        Integer slot = typeSlotPools.get(type);
        if (slot == null) {
            slot = nextCaptureSlot++;
            typeSlotPools.put(type, slot);
        }
        return slot;
    }
    
    /**
     * Initialize a capture slot with the correct type to prevent VerifyError.
     */
    public void initializeCaptureSlot(MethodVisitor mv, int slot, Class<?> type) {
        // Initialize slot with null of correct type
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitVarInsn(Opcodes.ASTORE, slot);
        
        // For RuntimeHash, initialize with empty hash instead of null
        if (type == org.perlonjava.runtime.RuntimeHash.class) {
            mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/RuntimeHash");
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/perlonjava/runtime/RuntimeHash", "<init>", "()V", false);
            mv.visitVarInsn(Opcodes.ASTORE, slot);
        }
        // For RuntimeScalar, initialize with undef scalar
        else if (type == org.perlonjava.runtime.RuntimeScalar.class) {
            mv.visitFieldInsn(Opcodes.GETSTATIC, "org/perlonjava/runtime/RuntimeScalarCache", "scalarUndef", "Lorg/perlonjava/runtime/RuntimeScalarReadOnly;");
            mv.visitVarInsn(Opcodes.ASTORE, slot);
        }
    }
    
    /**
     * Get the expected type for a capture slot.
     */
    public Class<?> getCaptureType(String varName, String anonymousClassName) {
        String key = anonymousClassName + ":" + varName;
        CaptureDescriptor descriptor = captureTable.get(key);
        return descriptor != null ? descriptor.capturedType : null;
    }
    
    /**
     * Reset the capture manager for a new compilation unit.
     */
    public void reset() {
        captureTable.clear();
        typeSlotPools.clear();
        nextCaptureSlot = 3;
    }
}
