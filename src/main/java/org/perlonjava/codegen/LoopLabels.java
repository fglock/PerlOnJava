package org.perlonjava.codegen;

import org.objectweb.asm.Label;

/**
 * Represents control flow labels for loop constructs in bytecode generation.
 * Manages the next, redo, and last labels commonly used in loop structures,
 * along with their context and stack state information.
 */
public class LoopLabels {
    /**
     * The name of the loop label as it appears in the source code
     */
    public String labelName;

    /**
     * The ASM Label for the 'next' statement (continues to next iteration)
     */
    public Label nextLabel;

    /**
     * The ASM Label for the 'redo' statement (restarts current iteration)
     */
    public Label redoLabel;

    /**
     * The ASM Label for the 'last' statement (exits the loop)
     */
    public Label lastLabel;
    
    /**
     * The ASM Label for the control flow handler (processes marked RuntimeList)
     * This handler checks the control flow type and label, then either handles
     * it or propagates to parent loop handler
     */
    public Label controlFlowHandler;

    /**
     * The context type in which this loop operates
     */
    public int context;

    /**
     * The stack level at the point where these loop labels are defined
     */
    public int asmStackLevel;
    
    /**
     * Whether this is a "true" loop (for/while/until) vs a pseudo-loop (do-while/bare block).
     * True loops allow last/next/redo. Pseudo-loops cause compile errors.
     */
    public boolean isTrueLoop;

    /**
     * Whether unlabeled next/last/redo should target this loop/block.
     *
     * Perl semantics:
     * - Unlabeled next/last/redo target the nearest enclosing true loop.
     * - Labeled next/last/redo can target labeled blocks (e.g. next SKIP in SKIP: { ... }).
     *
     * We keep block loops on the stack so labeled control flow can find them,
     * but prevent them from being selected as the target for unlabeled control flow.
     */
    public boolean isUnlabeledControlFlowTarget;

    /**
     * Creates a new LoopLabels instance with all necessary label information.
     *
     * @param labelName     The name of the loop label in source code
     * @param nextLabel     The ASM Label for 'next' operations
     * @param redoLabel     The ASM Label for 'redo' operations
     * @param lastLabel     The ASM Label for 'last' operations
     * @param asmStackLevel The stack level at label definition
     * @param context       The context type for this loop
     */
    public LoopLabels(String labelName, Label nextLabel, Label redoLabel, Label lastLabel, int asmStackLevel, int context) {
        this(labelName, nextLabel, redoLabel, lastLabel, asmStackLevel, context, true, true);
    }
    
    /**
     * Creates a new LoopLabels instance with all necessary label information.
     *
     * @param labelName     The name of the loop label in source code
     * @param nextLabel     The ASM Label for 'next' operations
     * @param redoLabel     The ASM Label for 'redo' operations
     * @param lastLabel     The ASM Label for 'last' operations
     * @param asmStackLevel The stack level at label definition
     * @param context       The context type for this loop
     * @param isTrueLoop    Whether this is a true loop (for/while/until) or pseudo-loop (do-while/bare)
     */
    public LoopLabels(String labelName, Label nextLabel, Label redoLabel, Label lastLabel, int asmStackLevel, int context, boolean isTrueLoop) {
        this(labelName, nextLabel, redoLabel, lastLabel, asmStackLevel, context, isTrueLoop, true);
    }

    public LoopLabels(String labelName, Label nextLabel, Label redoLabel, Label lastLabel, int asmStackLevel, int context, boolean isTrueLoop, boolean isUnlabeledControlFlowTarget) {
        this.labelName = labelName;
        this.nextLabel = nextLabel;
        this.redoLabel = redoLabel;
        this.lastLabel = lastLabel;
        this.asmStackLevel = asmStackLevel;
        this.context = context;
        this.isTrueLoop = isTrueLoop;
        this.isUnlabeledControlFlowTarget = isUnlabeledControlFlowTarget;
    }

    /**
     * Returns a string representation of the LoopLabels object.
     * Useful for debugging and logging purposes.
     *
     * @return A string containing all fields of this object
     */
    @Override
    public String toString() {
        return "LoopLabels{" +
                "labelName='" + labelName + '\'' +
                ", nextLabel=" + nextLabel +
                ", redoLabel=" + redoLabel +
                ", lastLabel=" + lastLabel +
                ", asmStackLevel=" + asmStackLevel +
                ", context=" + context +
                '}';
    }
}
