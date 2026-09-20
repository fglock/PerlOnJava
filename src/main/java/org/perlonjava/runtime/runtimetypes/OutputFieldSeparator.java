package org.perlonjava.runtime.runtimetypes;

import java.util.Stack;

/**
 * Special variable for $, (output field separator).
 *
 * <p>Like $\ (OutputRecordSeparator), $, has special semantics in Perl:
 * print uses an internal copy that is only updated by direct assignment
 * to $,. Aliasing via "for $, (@list)" does NOT affect the separator
 * print uses between arguments.
 *
 * <p>This class maintains a static {@code internalOFS} that print reads,
 * separate from the variable's value in the global symbol table.
 */
public class OutputFieldSeparator extends RuntimeScalar {

    /**
     * The internal OFS value that print reads.
     * Only updated by OutputFieldSeparator.set() calls.
     */
    private static ExecutionRuntimeState state() {
        return PerlRuntime.current().executionState();
    }

    public OutputFieldSeparator() {
        super();
    }

    /**
     * Returns the internal OFS value for use by print.
     */
    public static String getInternalOFS() {
        return state().outputFieldSeparator;
    }

    /**
     * Save the current internalOFS onto the stack.
     * Called from GlobalRuntimeScalar.dynamicSaveState() when localizing $,.
     */
    public static void saveInternalOFS() {
        ExecutionRuntimeState state = state();
        state.outputFieldSeparatorStates.push(state.outputFieldSeparator);
    }

    /**
     * Restore internalOFS from the stack.
     * Called from GlobalRuntimeScalar.dynamicRestoreState() when restoring $,.
     */
    public static void restoreInternalOFS() {
        ExecutionRuntimeState state = state();
        if (!state.outputFieldSeparatorStates.isEmpty()) {
            state.outputFieldSeparator = state.outputFieldSeparatorStates.pop();
        }
    }

    @Override
    public RuntimeScalar set(RuntimeScalar value) {
        super.set(value);
        updateInternalOFSIfUntied();
        return this;
    }

    @Override
    public RuntimeScalar set(String value) {
        super.set(value);
        updateInternalOFSIfUntied();
        return this;
    }

    @Override
    public RuntimeScalar set(int value) {
        super.set(value);
        updateInternalOFSIfUntied();
        return this;
    }

    @Override
    public RuntimeScalar set(long value) {
        super.set(value);
        updateInternalOFSIfUntied();
        return this;
    }

    @Override
    public RuntimeScalar set(boolean value) {
        super.set(value);
        updateInternalOFSIfUntied();
        return this;
    }

    @Override
    public RuntimeScalar set(Object value) {
        super.set(value);
        updateInternalOFSIfUntied();
        return this;
    }

    /**
     * A tied $, obtains its separator directly through FETCH for every gap in
     * a print argument list.  Stringifying this object after STORE would add
     * an erroneous FETCH merely to refresh the ordinary untied cache.
     */
    private void updateInternalOFSIfUntied() {
        if (type != RuntimeScalarType.TIED_SCALAR) {
            state().outputFieldSeparator = this.toString();
        }
    }
}
