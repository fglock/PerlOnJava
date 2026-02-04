package org.perlonjava.codegen;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * The StackLevelManager class manages the stack level during bytecode generation.
 * It tracks the current stack level and provides methods to adjust it, ensuring
 * the stack is balanced, especially when using control flow instructions like GOTO.
 */
public class StackLevelManager {
    // The current stack level.
    private int stackLevel;

    /**
     * Constructs a new StackLevelManager with an initial stack level of zero.
     */
    public StackLevelManager() {
        this.stackLevel = 0;
    }

    /**
     * Returns the current stack level.
     *
     * @return the current stack level.
     */
    public int getStackLevel() {
        return stackLevel;
    }

    /**
     * Resets the stack level to zero.
     */
    public void reset() {
        stackLevel = 0;
    }

    /**
     * Emits the necessary POP instructions to adjust the stack to the target
     * stack level. This ensures the stack is balanced when control flow
     * instructions like GOTO are executed.
     *
     * @param mv               the MethodVisitor used to emit the bytecode instructions.
     * @param targetStackLevel the desired stack level to adjust to.
     */
    public void emitPopInstructions(MethodVisitor mv, int targetStackLevel) {
        int current = this.stackLevel;
        if (current <= targetStackLevel) {
            return;
        }

        int s = current;
        while (s-- > targetStackLevel) {
            mv.visitInsn(Opcodes.POP);
        }

        // Keep the tracked stack level consistent with the actual JVM operand stack.
        // If we emitted POPs, the stack height is now the target level.
        this.stackLevel = targetStackLevel;
    }
}
