package org.perlonjava.codegen;

import java.util.Stack;

/**
 * The DynamicVariableManager class is responsible for managing a stack of dynamic variables.
 * It allows pushing and popping of variables to maintain and restore their states dynamically.
 * This is useful in scenarios where variables need to be temporarily overridden and later restored
 * to their original states.
 */
public class DynamicVariableManager {
    // A stack to hold the dynamic states of variables.
    private final Stack<DynamicState> variableStack;

    /**
     * Constructs a new DynamicVariableManager with an empty stack of dynamic variables.
     */
    public DynamicVariableManager() {
        this.variableStack = new Stack<>();
    }

    /**
     * Returns the current local level, which is the size of the variable stack.
     * This indicates how many dynamic states are currently being managed.
     *
     * @return the number of dynamic states in the stack.
     */
    public int getLocalLevel() {
        return variableStack.size();
    }

    /**
     * Pushes a new dynamic variable onto the stack. This method saves the current state
     * of the variable before pushing it onto the stack, allowing it to be restored later.
     *
     * @param variable the dynamic state to be pushed onto the stack.
     */
    public void pushLocalVariable(DynamicState variable) {
        // Save the current state of the variable and push it onto the stack.
        variable.dynamicSaveState();
        variableStack.push(variable);
    }

    /**
     * Pops the most recent dynamic variable from the stack and restores its state.
     * If the stack is empty, this method does nothing.
     */
    public void popLocalVariable() {
        if (!variableStack.isEmpty()) {
            // Pop the variable from the stack and restore its previous state.
            DynamicState variable = variableStack.pop();
            variable.dynamicRestoreState();
        }
    }

    /**
     * Pops dynamic variables from the stack until the stack size matches the specified target local level.
     * This is useful for restoring the stack to a previous state by removing any variables added after that state.
     *
     * @param targetLocalLevel the target size of the stack after popping variables.
     */
    public void popToLocalLevel(int targetLocalLevel) {
        // Continue popping variables until the stack size matches the target local level.
        while (variableStack.size() > targetLocalLevel) {
            popLocalVariable();
        }
    }
}