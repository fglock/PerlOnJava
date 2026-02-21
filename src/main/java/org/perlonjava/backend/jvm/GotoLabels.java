package org.perlonjava.backend.jvm;

import org.objectweb.asm.Label;

/**
 * Represents a labeled destination for GOTO statements in bytecode generation.
 * Maintains the mapping between source code labels and ASM labels along with stack state.
 */
public class GotoLabels {
    /**
     * The name of the label as it appears in the source code
     */
    public String labelName;

    /**
     * The ASM Label object used for bytecode generation
     */
    public Label gotoLabel;

    /**
     * Creates a new GotoLabels instance.
     *
     * @param labelName     The name of the label in source code
     * @param gotoLabel     The ASM Label object for bytecode generation
     */
    public GotoLabels(String labelName, Label gotoLabel) {
        this.labelName = labelName;
        this.gotoLabel = gotoLabel;
    }

    /**
     * Returns a string representation of the GotoLabels object.
     * Useful for debugging and logging purposes.
     *
     * @return A string containing all fields of this object
     */
    @Override
    public String toString() {
        return "GotoLabels{" +
                "labelName='" + labelName + '\'' +
                ", gotoLabel=" + gotoLabel +
                '}';
    }
}
