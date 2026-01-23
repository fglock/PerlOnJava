package org.perlonjava.codegen;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.symbols.ScopedSymbolTable;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Represents information about a Java class being generated.
 * This includes the class name, return label, stack level management,
 * and a stack of loop labels for managing nested loops.
 */
public class JavaClassInfo {

     private static final boolean LABEL_DEBUG = System.getenv("JPERL_LABEL_DEBUG") != null;
     private static final boolean SPILL_DEBUG = System.getenv("JPERL_SPILL_DEBUG") != null;

    /**
     * The name of the Java class.
     */
    public String javaClassName;

    /**
     * The label to return to after method execution.
     */
    public Label returnLabel;
    
    /**
     * Local variable slot for tail call trampoline - stores codeRef.
     */
    public int tailCallCodeRefSlot;
    
    /**
     * Local variable slot for tail call trampoline - stores args.
     */
    public int tailCallArgsSlot;
    
    /**
     * Local variable slot for temporarily storing marked RuntimeControlFlowList during call-site checks.
     */
    public int controlFlowTempSlot;

    public int controlFlowActionSlot;

    public int[] spillSlots;
    public int spillTop;

    public static final class SpillRef {
        public final int slot;
        public final boolean pooled;

        public SpillRef(int slot, boolean pooled) {
            this.slot = slot;
            this.pooled = pooled;
        }
    }

    /**
     * Manages the stack level for the class.
     */
    public StackLevelManager stackLevelManager;

    /**
     * A stack of loop labels for managing nested loops.
     */
    public Deque<LoopLabels> loopLabelStack;

    public Deque<GotoLabels> gotoLabelStack;

    /**
     * Tracks local variables that need consistent initialization at merge points.
     */
    public LocalVariableTracker localVariableTracker;

    /**
     * Current local variable index counter for tracking allocated slots.
     */
    public int localVariableIndex;

    /**
     * Constructs a new JavaClassInfo object.
     * Initializes the class name, stack level manager, and loop label stack.
     */
    public JavaClassInfo() {
        this.javaClassName = EmitterMethodCreator.generateClassName();
        this.returnLabel = null;
        this.stackLevelManager = new StackLevelManager();
        this.loopLabelStack = new ArrayDeque<>();
        this.gotoLabelStack = new ArrayDeque<>();
        this.spillSlots = new int[0];
        this.spillTop = 0;
        this.localVariableTracker = new LocalVariableTracker();
    }

     public Label newLabel(String kind) {
         return newLabel(kind, null);
     }

     public Label newLabel(String kind, String name) {
         Label l = new Label();
         if (LABEL_DEBUG) {
             StackTraceElement[] stack = Thread.currentThread().getStackTrace();
             String caller = "?";
             if (stack.length > 3) {
                 StackTraceElement e = stack[3];
                 caller = e.getClassName() + "." + e.getMethodName() + ":" + e.getLineNumber();
             }
             System.err.println("LABEL new kind=" + kind + (name != null ? (" name=" + name) : "") + " label=" + l + " caller=" + caller);
         }
         return l;
     }

    public int acquireSpillSlot() {
        if (spillTop >= spillSlots.length) {
            return -1;
        }
        int slot = spillSlots[spillTop++];
        if (SPILL_DEBUG) {
            System.err.println("SPILL acquire slot=" + slot + " top=" + spillTop + "/" + spillSlots.length);
        }
        return slot;
    }

    public void releaseSpillSlot() {
        if (spillTop > 0) {
            spillTop--;
            if (SPILL_DEBUG) {
                System.err.println("SPILL release top=" + spillTop + "/" + spillSlots.length);
            }
        }
    }

    public SpillRef tryAcquirePooledSpillRef() {
        int slot = acquireSpillSlot();
        if (slot < 0) {
            return null;
        }
        return new SpillRef(slot, true);
    }

    public SpillRef acquireSpillRefOrAllocate(ScopedSymbolTable symbolTable) {
        int slot = acquireSpillSlot();
        if (slot >= 0) {
            return new SpillRef(slot, true);
        }
        return new SpillRef(symbolTable.allocateLocalVariable("spillRef"), false);
    }

    public void storeSpillRef(MethodVisitor mv, SpillRef ref) {
        mv.visitVarInsn(Opcodes.ASTORE, ref.slot);
    }

    public void loadSpillRef(MethodVisitor mv, SpillRef ref) {
        mv.visitVarInsn(Opcodes.ALOAD, ref.slot);
    }

    public void releaseSpillRef(SpillRef ref) {
        if (ref.pooled) {
            releaseSpillSlot();
        }
    }

    public void emitClearSpillSlots(MethodVisitor mv) {
        if (spillSlots == null) {
            return;
        }
        for (int slot : spillSlots) {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitVarInsn(Opcodes.ASTORE, slot);
        }
    }

    /**
     * Pushes a new set of loop labels onto the loop label stack.
     *
     * @param labelName the name of the loop label
     * @param nextLabel the label for the next iteration
     * @param redoLabel the label for redoing the current iteration
     * @param lastLabel the label for exiting the loop
     */
    public void pushLoopLabels(String labelName, Label nextLabel, Label redoLabel, Label lastLabel, int context) {
        loopLabelStack.push(new LoopLabels(labelName, nextLabel, redoLabel, lastLabel, stackLevelManager.getStackLevel(), context));
    }

    /**
     * Pushes a new set of loop labels with isTrueLoop flag.
     *
     * @param labelName     the name of the loop label
     * @param nextLabel     the label for the next iteration
     * @param redoLabel     the label for redoing the current iteration
     * @param lastLabel     the label for exiting the loop
     * @param stackLevel    the current stack level
     * @param context       the context type
     * @param isTrueLoop    whether this is a true loop (for/while/until) or pseudo-loop (do-while/bare)
     */
    public void pushLoopLabels(String labelName, Label nextLabel, Label redoLabel, Label lastLabel, int stackLevel, int context, boolean isTrueLoop) {
        loopLabelStack.push(new LoopLabels(labelName, nextLabel, redoLabel, lastLabel, stackLevel, context, isTrueLoop));
    }
    
    /**
     * Pushes a LoopLabels object onto the loop label stack.
     * This is useful when you've already constructed a LoopLabels object with a control flow handler.
     *
     * @param loopLabels the LoopLabels object to push
     */
    public void pushLoopLabels(LoopLabels loopLabels) {
        loopLabelStack.push(loopLabels);
    }

    /**
     * Pops the top set of loop labels from the loop label stack and returns it.
     *
     * @return the popped LoopLabels object
     */
    public LoopLabels popLoopLabels() {
        return loopLabelStack.pop();
    }
    
    /**
     * Gets the innermost (current) loop labels.
     * Returns null if not currently inside a loop.
     *
     * @return the innermost LoopLabels object, or null if none
     */
    public LoopLabels getInnermostLoopLabels() {
        return loopLabelStack.peek();
    }

    /**
     * Gets the parent loop labels (the loop containing the current loop).
     * Returns null if there's no parent loop.
     *
     * @return the parent LoopLabels object, or null if none
     */
    public LoopLabels getParentLoopLabels() {
        if (loopLabelStack.size() < 2) {
            return null;
        }
        // Convert deque to array to access second-to-top element
        LoopLabels[] array = loopLabelStack.toArray(new LoopLabels[0]);
        return array[array.length - 2];
    }

    /**
     * Finds loop labels by their name.
     *
     * @param labelName the name of the loop label to find
     * @return the LoopLabels object with the specified name, or the top of the stack if the name is null
     */
    public LoopLabels findLoopLabelsByName(String labelName) {
        if (labelName == null) {
            return loopLabelStack.peek();
        }
        for (LoopLabels loopLabels : loopLabelStack) {
            if (loopLabels.labelName != null && loopLabels.labelName.equals(labelName)) {
                return loopLabels;
            }
        }
        return null;
    }

    public void pushGotoLabels(String labelName, Label gotoLabel) {
        gotoLabelStack.push(new GotoLabels(labelName, gotoLabel, stackLevelManager.getStackLevel()));
    }

    public GotoLabels findGotoLabelsByName(String labelName) {
        for (GotoLabels gotoLabels : gotoLabelStack) {
            if (gotoLabels.labelName.equals(labelName)) {
                return gotoLabels;
            }
        }
        return null;
    }

    public void popGotoLabels() {
        gotoLabelStack.pop();
    }

    /**
     * Increments the stack level by a specified amount.
     *
     * @param level the amount to increment the stack level by
     */
    public void incrementStackLevel(int level) {
        stackLevelManager.increment(level);
    }

    /**
     * Decrements the stack level by a specified amount.
     *
     * @param level the amount to decrement the stack level by
     */
    public void decrementStackLevel(int level) {
        stackLevelManager.decrement(level);
    }

    /**
     * Resets the stack level to its initial state.
     */
    public void resetStackLevel() {
        stackLevelManager.reset();
    }

    /**
     * Returns a string representation of the JavaClassInfo object.
     *
     * @return a string representation of the JavaClassInfo object
     */
    @Override
    public String toString() {
        return "JavaClassInfo{\n" +
                "    javaClassName='" + javaClassName + "',\n" +
                "    returnLabel=" + (returnLabel != null ? returnLabel.toString() : "null") + ",\n" +
                "    asmStackLevel=" + stackLevelManager.getStackLevel() + ",\n" +
                "    loopLabelStack=" + loopLabelStack + "\n" +
                "    gotoLabelStack=" + gotoLabelStack + "\n" +
                "}";
    }
}
