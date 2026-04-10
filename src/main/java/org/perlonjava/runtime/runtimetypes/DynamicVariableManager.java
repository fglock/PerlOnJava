package org.perlonjava.runtime.runtimetypes;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The DynamicVariableManager class is responsible for managing a stack of dynamic variables.
 * It allows pushing and popping of variables to maintain and restore their states dynamically.
 * This is useful in scenarios where variables need to be temporarily overridden and later restored
 * to their original states.
 */
public class DynamicVariableManager {
    // State is now held per-PerlRuntime. This accessor delegates to the current runtime.
    private static Deque<DynamicState> variableStack() {
        return PerlRuntime.current().dynamicVariableStack;
    }

    /**
     * Returns the current local level, which is the size of the variable stack.
     * This indicates how many dynamic states are currently being managed.
     *
     * @return the number of dynamic states in the stack.
     */
    public static int getLocalLevel() {
        return variableStack().size();
    }

    /**
     * Pushes a new dynamic variable onto the stack. This method saves the current state
     * of the variable before pushing it onto the stack, allowing it to be restored later.
     *
     * @param variable the dynamic state to be pushed onto the stack.
     */
    public static RuntimeBase pushLocalVariable(RuntimeBase variable) {
        // Save the current state of the variable and push it onto the stack.
        variable.dynamicSaveState();
        variableStack().addLast(variable);
        return variable;
    }

    public static RuntimeScalar pushLocalVariable(RuntimeScalar variable) {
        // Save the current state of the variable and push it onto the stack.
        variable.dynamicSaveState();
        variableStack().addLast(variable);
        return variable;
    }

    public static RuntimeGlob pushLocalVariable(RuntimeGlob variable) {
        // Save the current state of the variable and push it onto the stack.
        // dynamicSaveState() creates a NEW glob in globalIORefs for the local scope.
        variable.dynamicSaveState();
        variableStack().addLast(variable);
        // Return the NEW glob from globalIORefs (installed by dynamicSaveState),
        // not the old one. This ensures `local *FH` returns the fresh local glob,
        // so that \do { local *FH } captures a unique glob per call (Perl 5 parity).
        return GlobalVariable.getGlobalIO(variable.globName);
    }

    public static void pushLocalVariable(DynamicState variable) {
        variable.dynamicSaveState();
        variableStack().addLast(variable);
    }

    /**
     * Pops dynamic variables from the stack until the stack size matches the specified target local level.
     * This is useful for restoring the stack to a previous state by removing any variables added after that state.
     *
     * <p>This method is exception-safe: if a DynamicState (e.g., a DeferBlock) throws an exception
     * during restoration, the method continues processing remaining items on the stack. The last
     * exception thrown is re-thrown after all cleanup is complete. This implements Perl's defer
     * semantics where the last exception "wins".</p>
     *
     * @param targetLocalLevel the target size of the stack after popping variables.
     */
    public static void popToLocalLevel(int targetLocalLevel) {
        Deque<DynamicState> stack = variableStack();
        // Ensure the target level is non-negative and does not exceed the current stack size
        if (targetLocalLevel < 0 || targetLocalLevel > stack.size()) {
            throw new IllegalArgumentException("Invalid target local level: " + targetLocalLevel);
        }

        // Track the last exception so we can re-throw after all cleanup
        Throwable pendingException = null;

        // Pop variables until the stack size matches the target local level
        while (stack.size() > targetLocalLevel) {
            DynamicState variable = stack.removeLast();
            try {
                variable.dynamicRestoreState();
            } catch (Throwable t) {
                // For defer blocks: last exception wins (Perl semantics)
                // Continue cleanup even if an exception occurs
                pendingException = t;
            }
        }

        // Re-throw the last exception after all cleanup is done
        if (pendingException != null) {
            if (pendingException instanceof RuntimeException re) {
                throw re;
            } else if (pendingException instanceof Error e) {
                throw e;
            } else {
                throw new RuntimeException(pendingException);
            }
        }
    }
}
