package org.perlonjava.runtime.runtimetypes;

import java.util.Stack;

/**
 * Filehandle-backed magic scalar for Perl's $= (page length), $- (lines
 * remaining), and $% (page number).  Like $~ and $^, these belong to the
 * currently selected output handle rather than a process-global slot.
 */
public final class OutputFormatVariable extends RuntimeScalar {
    public enum Id { PAGE_LENGTH, LINES_LEFT, PAGE_NUMBER }

    private final Id id;

    public OutputFormatVariable(Id id) {
        this.id = id;
    }

    @SuppressWarnings("unchecked")
    private static Stack<State> stateStack() {
        return (Stack<State>) (Stack<?>) PerlRuntime.current().executionState().outputFormatVariableStates;
    }

    private static RuntimeIO currentHandle() {
        RuntimeIO handle = RuntimeIO.getSelectedHandle();
        return handle != null ? handle : RuntimeIO.getStdout();
    }

    private int getValue(RuntimeIO handle) {
        return switch (id) {
            case PAGE_LENGTH -> handle.formatPageLength;
            case LINES_LEFT -> handle.formatLinesLeft;
            case PAGE_NUMBER -> handle.formatPageNumber;
        };
    }

    private void setValue(RuntimeIO handle, int value) {
        switch (id) {
            case PAGE_LENGTH -> handle.formatPageLength = value;
            case LINES_LEFT -> handle.formatLinesLeft = value;
            case PAGE_NUMBER -> handle.formatPageNumber = value;
        }
    }

    @Override
    public RuntimeScalar set(RuntimeScalar value) {
        setValue(currentHandle(), value.getInt());
        return this;
    }

    @Override public RuntimeScalar set(int value) { setValue(currentHandle(), value); return this; }
    @Override public RuntimeScalar set(long value) { setValue(currentHandle(), (int) value); return this; }
    @Override public RuntimeScalar set(String value) { setValue(currentHandle(), new RuntimeScalar(value).getInt()); return this; }
    @Override public RuntimeScalar set(boolean value) { setValue(currentHandle(), value ? 1 : 0); return this; }
    @Override public int getInt() { return getValue(currentHandle()); }
    @Override public long getLong() { return getInt(); }
    @Override public double getDouble() { return getInt(); }
    @Override public boolean getBoolean() { return getInt() != 0; }
    @Override public boolean getDefinedBoolean() { return true; }
    @Override public String toString() { return Integer.toString(getInt()); }

    @Override
    public void dynamicSaveState() {
        RuntimeIO handle = currentHandle();
        stateStack().push(new State(handle, getValue(handle)));
        setValue(handle, 0);
    }

    @Override
    public void dynamicRestoreState() {
        if (stateStack().isEmpty()) return;
        State state = stateStack().pop();
        setValue(state.handle, state.value);
    }

    private record State(RuntimeIO handle, int value) { }
}
