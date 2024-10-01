package org.perlonjava.codegen;

import java.util.ArrayDeque;
import java.util.Deque;
import org.objectweb.asm.Label;

public class JavaClassInfo {

    /**
     * The name of the Java class being generated.
     */
    public String javaClassName;

    /**
     * The label to which the current method should return.
     */
    public Label returnLabel;

    /**
     * ASM stack level
     */
    public int asmStackLevel;

    /**
     * Stack to hold loop label information
     */
    public Deque<LoopLabels> loopLabelStack;

    public JavaClassInfo() {
        this.javaClassName = EmitterMethodCreator.generateClassName();
        this.returnLabel = null;
        this.asmStackLevel = 0;
        this.loopLabelStack = new ArrayDeque<>();
    }

    /**
     * Push a new LoopLabels object onto the stack.
     */
    public void pushLoopLabels(String labelName, Label nextLabel, Label redoLabel, Label lastLabel) {
        loopLabelStack.push(new LoopLabels(labelName, nextLabel, redoLabel, lastLabel));
    }

    /**
     * Pop the top LoopLabels object off the stack.
     */
    public LoopLabels popLoopLabels() {
        return loopLabelStack.pop();
    }

    /**
     * Peek at the top LoopLabels object without removing it.
     */
    public LoopLabels peekLoopLabels() {
        return loopLabelStack.peek();
    }

    @Override
    public String toString() {
        return "JavaClassInfo{\n" +
                "    javaClassName='" + javaClassName + "',\n" +
                "    returnLabel=" + (returnLabel != null ? returnLabel.toString() : "null") + ",\n" +
                "    asmStackLevel=" + asmStackLevel + ",\n" +
                "    loopLabelStack=" + loopLabelStack + "\n" +
                "}";
    }
}
