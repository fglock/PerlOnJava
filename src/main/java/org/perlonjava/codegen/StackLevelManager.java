package org.perlonjava.codegen;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class StackLevelManager {
    private int stackLevel;

    public StackLevelManager() {
        this.stackLevel = 0;
    }

    public int getStackLevel() {
        return stackLevel;
    }

    public void increment(int level) {
        stackLevel += level;
    }

    public void decrement(int level) {
        stackLevel -= level;
        if (stackLevel < 0) {
            stackLevel = 0;
        }
    }

    public void reset() {
        stackLevel = 0;
    }

    public void emitPopInstructions(MethodVisitor mv, int targetStackLevel) {
        while (stackLevel > targetStackLevel) {
            mv.visitInsn(Opcodes.POP);
            decrement(1);
        }
    }
}
