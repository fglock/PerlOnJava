package org.perlonjava.codegen;

import org.perlonjava.runtime.RuntimeScalar;

import java.util.Stack;

public class DynamicVariableManager {
    private final Stack<Object> variableStack;

    public DynamicVariableManager() {
        this.variableStack = new Stack<>();
    }

    public int getLocalLevel() {
        return variableStack.size();
    }

    public void pushLocalVariable(RuntimeScalar variable) {
        // TODO save the variable state and reset the variable
        variableStack.push(variable);
    }

    public void popLocalVariable() {
        if (variableStack.size() > 1) {
            // TODO restore the variable state
            variableStack.pop();
        }
    }

    public void popToLocalLevel(int targetLocalLevel) {
        while (variableStack.size() > targetLocalLevel) {
            popLocalVariable();
        }
    }
}
