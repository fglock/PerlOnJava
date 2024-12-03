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
     * Increments the stack level by the specified amount.
     *
     * @param level the amount to increment the stack level by.
     */
    public void increment(int level) {
        stackLevel += level;
    }

    /**
     * Decrements the stack level by the specified amount. If the resulting
     * stack level is negative, it is reset to zero to maintain a valid state.
     *
     * @param level the amount to decrement the stack level by.
     */
    public void decrement(int level) {
        stackLevel -= level;
        if (stackLevel < 0) {
            stackLevel = 0;
        }
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
     * @param mv the MethodVisitor used to emit the bytecode instructions.
     * @param targetStackLevel the desired stack level to adjust to.
     */
    public void emitPopInstructions(MethodVisitor mv, int targetStackLevel) {
        while (stackLevel > targetStackLevel) {
            mv.visitInsn(Opcodes.POP);
            decrement(1);
        }
    }
}
