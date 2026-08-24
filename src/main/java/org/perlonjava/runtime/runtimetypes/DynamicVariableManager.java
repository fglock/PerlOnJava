package org.perlonjava.runtime.runtimetypes;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The DynamicVariableManager class is responsible for managing a stack of dynamic variables.
 * It allows pushing and popping of variables to maintain and restore their states dynamically.
 * This is useful in scenarios where variables need to be temporarily overridden and later restored
 * to their original states.
 */
public class DynamicVariableManager {
    public record SuspendedState(DynamicState state, Object token) {}
    public record CapturedFrame<T>(T result, List<SuspendedState> states) {}

    static final class FrameCapture {
        final int localLevel;
        List<SuspendedState> states;

        FrameCapture(int localLevel) {
            this.localLevel = localLevel;
        }
    }
    // A stack to hold the dynamic states of variables.
    // Using ArrayDeque instead of Stack for better performance (no synchronization overhead).
    private static Deque<DynamicState> variableStack() {
        return PerlRuntime.current().executionState().dynamicVariableStack;
    }

    private static Deque<FrameCapture> frameCaptures() {
        return PerlRuntime.current().executionState().dynamicFrameCaptures;
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

    /** Return whether a package scalar currently has an active local() frame. */
    public static boolean isGlobalScalarLocalized(String fullName) {
        for (DynamicState state : variableStack()) {
            if (state instanceof GlobalRuntimeScalar scalar
                    && scalar.localizes(fullName)) {
                return true;
            }
        }
        return false;
    }

    /** Return active package-scalar localizations by dynamic binding identity. */
    public static Map<String, RuntimeScalar> activeLocalizedGlobalScalars() {
        Map<String, RuntimeScalar> localized = new LinkedHashMap<>();
        for (DynamicState state : variableStack()) {
            if (!(state instanceof GlobalRuntimeScalar scalar)) continue;
            RuntimeScalar value = scalar.activeLocalizedValue();
            if (value != null) localized.put(scalar.localizedName(), value);
        }
        return localized;
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
        // Ensure the target level is non-negative and does not exceed the current stack size
        Deque<DynamicState> variableStack = variableStack();
        if (targetLocalLevel < 0 || targetLocalLevel > variableStack.size()) {
            throw new IllegalArgumentException("Invalid target local level: " + targetLocalLevel);
        }

        // Track the last exception so we can re-throw after all cleanup
        Throwable pendingException = null;

        // Pop variables until the stack size matches the target local level
        while (variableStack.size() > targetLocalLevel) {
            DynamicState variable = variableStack.removeLast();
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

    /**
     * Performs an outer subroutine-frame teardown.  Regex executable callbacks
     * may temporarily take ownership of the dynamic states that survive the
     * callback body, so Joni can commit or abandon them with the match path.
     * Ordinary calls retain the normal pop-and-restore behavior.
     */
    public static void teardownFrameToLocalLevel(int targetLocalLevel) {
        FrameCapture capture = frameCaptures().peekLast();
        if (capture != null && capture.localLevel == targetLocalLevel
                && capture.states == null) {
            capture.states = suspendAbove(targetLocalLevel);
            return;
        }
        popToLocalLevel(targetLocalLevel);
    }

    /** Execute one subroutine call while retaining its surviving local() states. */
    public static <T> CapturedFrame<T> captureFrameLocals(Supplier<T> action) {
        FrameCapture capture = new FrameCapture(getLocalLevel());
        Deque<FrameCapture> captures = frameCaptures();
        captures.addLast(capture);
        boolean completed = false;
        try {
            T result = action.get();
            completed = true;
            return new CapturedFrame<>(result,
                    capture.states == null ? List.of() : capture.states);
        } finally {
            if (captures.peekLast() != capture) {
                if (completed) {
                    throw new IllegalStateException(
                            "Dynamic frame capture closed out of order");
                }
                // A deep executable-regex recursion can exhaust the Java stack
                // while descendant callbacks are themselves unwinding.  Their
                // Java frames are already gone by the time this outer finally
                // runs, but the VM may have been unable to finish each metadata
                // cleanup.  Drop only descendants of this failed capture so an
                // internal lifecycle assertion cannot replace Perl's original
                // exception (notably "Infinite recursion via empty pattern").
                if (captures.contains(capture)) {
                    while (captures.peekLast() != capture) {
                        captures.removeLast();
                    }
                }
            }
            if (captures.peekLast() == capture) {
                captures.removeLast();
            }
            if (!completed && getLocalLevel() > capture.localLevel) {
                popToLocalLevel(capture.localLevel);
            }
        }
    }

    /**
     * Detach states belonging to a suspended interpreter frame.  Unlike
     * {@link #popToLocalLevel(int)}, this returns the states in their original
     * bottom-to-top order so they can be reinstated on a later callback.
     */
    public static List<SuspendedState> suspendAbove(int targetLocalLevel) {
        Deque<DynamicState> variableStack = variableStack();
        if (targetLocalLevel < 0 || targetLocalLevel > variableStack.size()) {
            throw new IllegalArgumentException("Invalid target local level: " + targetLocalLevel);
        }
        List<SuspendedState> result = new ArrayList<>();
        while (variableStack.size() > targetLocalLevel) {
            DynamicState state = variableStack.removeLast();
            result.add(0, new SuspendedState(state, state.dynamicSuspendState()));
        }
        return result;
    }

    /** Reinstall detached states in their original stack order. */
    public static void resumeSuspended(List<SuspendedState> states) {
        if (states == null) return;
        Deque<DynamicState> variableStack = variableStack();
        for (SuspendedState suspended : states) {
            suspended.state().dynamicResumeState(suspended.token());
            variableStack.addLast(suspended.state());
        }
    }
}
