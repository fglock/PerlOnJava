package org.perlonjava.codegen;

import org.objectweb.asm.Label;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Represents information about a Java class being generated.
 * This includes the class name, return label, stack level management,
 * and a stack of loop labels for managing nested loops.
 */
public class JavaClassInfo {
    // Debug flag - set to true to enable loop label tracking debug output
    private static final boolean DEBUG = false;

    /**
     * The name of the Java class.
     */
    public String javaClassName;

    /**
     * The label to return to after method execution.
     */
    public Label returnLabel;

    /**
     * Cleanup labels for non-local control flow.
     * When a non-local last/next/redo/goto is encountered, we first jump to these
     * cleanup labels (which are at the end of the subroutine with a clean stack),
     * and THEN throw the exception to propagate to outer call frames.
     * This two-phase approach ensures stack consistency.
     */
    public Label nonLocalLastCleanup;
    public Label nonLocalNextCleanup;
    public Label nonLocalRedoCleanup;
    public Label nonLocalGotoCleanup;

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
     * Constructs a new JavaClassInfo object.
     * Initializes the class name, stack level manager, and loop label stack.
     */
    public JavaClassInfo() {
        this.javaClassName = EmitterMethodCreator.generateClassName();
        this.returnLabel = null;
        this.stackLevelManager = new StackLevelManager();
        this.loopLabelStack = new ArrayDeque<>();
        this.gotoLabelStack = new ArrayDeque<>();
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
        int currentStackLevel = stackLevelManager.getStackLevel();
        if (DEBUG) {
            System.err.println("pushLoopLabels: labelName=" + labelName + ", asmStackLevel=" + currentStackLevel);
        }
        loopLabelStack.push(new LoopLabels(labelName, nextLabel, redoLabel, lastLabel, currentStackLevel, context));
    }

    /**
     * Pops the top set of loop labels from the loop label stack.
     */
    public void popLoopLabels() {
        loopLabelStack.pop();
    }

    /**
     * Gets the innermost (current) loop labels from the stack.
     * 
     * @return the innermost LoopLabels, or null if not inside a loop
     */
    public LoopLabels getCurrentLoopLabels() {
        return loopLabelStack.isEmpty() ? null : loopLabelStack.peek();
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
