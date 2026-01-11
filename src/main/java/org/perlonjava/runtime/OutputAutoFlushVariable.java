package org.perlonjava.runtime;

import java.util.Stack;

/**
 * Special variable for $| (output autoflush).
 */
public class OutputAutoFlushVariable extends RuntimeScalar {

    private record State(RuntimeIO handle, boolean autoFlush) {
    }

    private static final Stack<State> stateStack = new Stack<>();

    private static RuntimeIO currentHandle() {
        RuntimeIO handle = RuntimeIO.selectedHandle;
        return handle != null ? handle : RuntimeIO.stdout;
    }

    @Override
    public RuntimeScalar set(RuntimeScalar value) {
        RuntimeIO handle = currentHandle();
        handle.setAutoFlush(value.getBoolean());
        this.type = RuntimeScalarType.INTEGER;
        this.value = handle.isAutoFlush() ? 1 : 0;
        return this;
    }

    @Override
    public int getInt() {
        return currentHandle().isAutoFlush() ? 1 : 0;
    }

    @Override
    public long getLong() {
        return getInt();
    }

    @Override
    public double getDouble() {
        return getInt();
    }

    @Override
    public boolean getBoolean() {
        return getInt() != 0;
    }

    @Override
    public String toString() {
        return Integer.toString(getInt());
    }

    @Override
    public RuntimeScalar preAutoIncrement() {
        int newVal = getInt() + 1;
        set(new RuntimeScalar(newVal));
        return this;
    }

    @Override
    public RuntimeScalar postAutoIncrement() {
        RuntimeScalar old = new RuntimeScalar(getInt());
        int newVal = old.getInt() + 1;
        set(new RuntimeScalar(newVal));
        return old;
    }

    @Override
    public RuntimeScalar preAutoDecrement() {
        int newVal = getInt() - 1;
        set(new RuntimeScalar(newVal));
        return this;
    }

    @Override
    public RuntimeScalar postAutoDecrement() {
        RuntimeScalar old = new RuntimeScalar(getInt());
        int newVal = old.getInt() - 1;
        set(new RuntimeScalar(newVal));
        return old;
    }

    @Override
    public void dynamicSaveState() {
        RuntimeIO handle = currentHandle();
        stateStack.push(new State(handle, handle.isAutoFlush()));
        handle.setAutoFlush(false);
    }

    @Override
    public void dynamicRestoreState() {
        if (!stateStack.isEmpty()) {
            State previous = stateStack.pop();
            if (previous.handle != null) {
                previous.handle.setAutoFlush(previous.autoFlush);
            }
        }
    }
}
