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

    /**
     * The name of the Java class.
     */
    public String javaClassName;

    /**
     * The label to return to after method execution.
     */
    public Label returnLabel;

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
        loopLabelStack.push(new LoopLabels(labelName, nextLabel, redoLabel, lastLabel, stackLevelManager.getStackLevel(), context));
    }

    /**
     * Pops the top set of loop labels from the loop label stack.
     */
    public void popLoopLabels() {
        loopLabelStack.pop();
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

    public GotoLabels findGotoLabelsByName(String labelName) {
        if (labelName == null) {
            return gotoLabelStack.peek();
        }
        for (GotoLabels gotoLabels : gotoLabelStack) {
            if (gotoLabels.labelName != null && gotoLabels.labelName.equals(labelName)) {
                return gotoLabels;
            }
        }
        return null;
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
