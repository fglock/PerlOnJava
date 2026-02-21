package org.perlonjava.backend.jvm;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.frontend.semantic.ScopedSymbolTable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Represents information about a Java class being generated.
 * This includes the class name, return label, stack level management,
 * and a stack of loop labels for managing nested loops.
 */
public class JavaClassInfo {

    /**
     * The name of the Java class.
     */
    public String javaClassName;

    /**
     * The label to return to after method execution.
     */
    public Label returnLabel;

    /**
     * Local variable slot used to spill the value returned by `return`, `goto` markers,
     * and other non-local control-flow paths before jumping to {@link #returnLabel}.
     *
     * The {@link #returnLabel} join point must be stack-neutral: no incoming edge may
     * rely on operand stack contents.
     */
    public int returnValueSlot;
    
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
     * A stack of loop labels for managing nested loops.
     */
    public Deque<LoopLabels> loopLabelStack;

    public Deque<GotoLabels> gotoLabelStack;

    /**
     * Map of loop state signature to block-level dispatcher label.
     * Allows multiple call sites with the same visible loops to share one dispatcher.
     */
    public Map<String, Label> blockDispatcherLabels;

    /**
     * Constructs a new JavaClassInfo object.
     * Initializes the class name, stack level manager, and loop label stack.
     */
    public JavaClassInfo() {
        this.javaClassName = EmitterMethodCreator.generateClassName();
        this.returnLabel = null;
        this.returnValueSlot = -1;
        this.loopLabelStack = new ArrayDeque<>();
        this.gotoLabelStack = new ArrayDeque<>();
        this.blockDispatcherLabels = new HashMap<>();
        this.spillSlots = new int[0];
        this.spillTop = 0;
    }

    public int acquireSpillSlot() {
        if (spillTop >= spillSlots.length) {
            return -1;
        }
        return spillSlots[spillTop++];
    }

    public void releaseSpillSlot() {
        if (spillTop > 0) {
            spillTop--;
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
        return new SpillRef(symbolTable.allocateLocalVariable(), false);
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

    /**
     * Pushes a new set of loop labels onto the loop label stack.
     *
     * @param labelName the name of the loop label
     * @param nextLabel the label for the next iteration
     * @param redoLabel the label for redoing the current iteration
     * @param lastLabel the label for exiting the loop
     */
    public void pushLoopLabels(String labelName, Label nextLabel, Label redoLabel, Label lastLabel, int context) {
        loopLabelStack.push(new LoopLabels(labelName, nextLabel, redoLabel, lastLabel, context));
    }

    /**
     * Pushes a new set of loop labels with isTrueLoop flag.
     *
     * @param labelName     the name of the loop label
     * @param nextLabel     the label for the next iteration
     * @param redoLabel     the label for redoing the current iteration
     * @param lastLabel     the label for exiting the loop
     * @param context       the context type
     * @param isTrueLoop    whether this is a true loop (for/while/until) or pseudo-loop (do-while/bare)
     */
    public void pushLoopLabels(String labelName, Label nextLabel, Label redoLabel, Label lastLabel, int context, boolean isTrueLoop) {
        loopLabelStack.push(new LoopLabels(labelName, nextLabel, redoLabel, lastLabel, context, isTrueLoop));
    }

    public void pushLoopLabels(String labelName, Label nextLabel, Label redoLabel, Label lastLabel, int context, boolean isTrueLoop, boolean isUnlabeledControlFlowTarget) {
        loopLabelStack.push(new LoopLabels(labelName, nextLabel, redoLabel, lastLabel, context, isTrueLoop, isUnlabeledControlFlowTarget));
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

    /**
     * Finds the innermost LoopLabels that is a true loop (for/while/until).
     *
     * @return the innermost LoopLabels with isTrueLoop=true, or null if none
     */
    public LoopLabels findInnermostTrueLoopLabels() {
        for (LoopLabels loopLabels : loopLabelStack) {
            if (loopLabels != null && loopLabels.isTrueLoop && loopLabels.isUnlabeledControlFlowTarget) {
                return loopLabels;
            }
        }
        return null;
    }

    public void pushGotoLabels(String labelName, Label gotoLabel) {
        gotoLabelStack.push(new GotoLabels(labelName, gotoLabel));
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
     * Computes a unique signature for the current loop state.
     * This signature identifies which loops are visible at the current point.
     * Call sites with the same signature can share a block-level dispatcher.
     *
     * @return a string signature representing the current loop state
     */
    public String getLoopStateSignature() {
        if (loopLabelStack.isEmpty()) {
            return "NO_LOOPS";
        }

        StringBuilder sb = new StringBuilder();
        boolean first = true;
        // Iterate from innermost to outermost (stack order)
        for (LoopLabels loop : loopLabelStack) {
            if (!first) {
                sb.append("|");
            }
            first = false;
            sb.append(loop.labelName != null ? loop.labelName : "UNLABELED");
            sb.append("@").append(System.identityHashCode(loop));
        }
        return sb.toString();
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
                "    loopLabelStack=" + loopLabelStack + "\n" +
                "    gotoLabelStack=" + gotoLabelStack + "\n" +
                "}";
    }
}
