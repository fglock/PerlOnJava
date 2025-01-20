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
     * The context type in which this loop operates
     */
    public int context;

    /**
     * The stack level at the point where these loop labels are defined
     */
    public int asmStackLevel;

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
        this.labelName = labelName;
        this.nextLabel = nextLabel;
        this.redoLabel = redoLabel;
        this.lastLabel = lastLabel;
        this.asmStackLevel = asmStackLevel;
        this.context = context;
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
