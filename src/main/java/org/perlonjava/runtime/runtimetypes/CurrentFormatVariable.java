package org.perlonjava.runtime.runtimetypes;

import java.util.Stack;

/**
 * Filehandle-backed magic scalar for $~ (current format) and $^ (top format).
 * Perl keeps these names on the currently selected output handle, rather than
 * in one process-global scalar slot.
 */
public final class CurrentFormatVariable extends RuntimeScalar {
    private final boolean topFormat;

    @SuppressWarnings("unchecked")
    private Stack<State> stateStack() {
        return (Stack<State>) (Stack<?>) PerlRuntime.current().executionState().currentFormatStates;
    }

    public CurrentFormatVariable(boolean topFormat) {
        this.topFormat = topFormat;
    }

    private RuntimeIO currentHandle() {
        RuntimeIO handle = RuntimeIO.getSelectedHandle();
        return handle != null ? handle : RuntimeIO.getStdout();
    }

    private static String defaultName(RuntimeIO handle, boolean topFormat) {
        String name = handle != null ? handle.globName : null;
        if (name == null || name.isEmpty()) return null;
        int separator = name.lastIndexOf("::");
        String shortName = separator >= 0 ? name.substring(separator + 2) : name;
        return topFormat ? shortName + "_TOP" : shortName;
    }

    public static String currentFormatName(RuntimeIO handle) {
        if (handle == null) handle = RuntimeIO.getStdout();
        return handle.currentFormatInitialized ? handle.currentFormatName : defaultName(handle, false);
    }

    private String getName() {
        RuntimeIO handle = currentHandle();
        boolean initialized = topFormat ? handle.currentTopFormatInitialized : handle.currentFormatInitialized;
        if (!initialized) return defaultName(handle, topFormat);
        return topFormat ? handle.currentTopFormatName : handle.currentFormatName;
    }

    private void setName(String name) {
        RuntimeIO handle = currentHandle();
        if (topFormat) {
            handle.currentTopFormatName = name;
            handle.currentTopFormatInitialized = true;
        } else {
            handle.currentFormatName = name;
            handle.currentFormatInitialized = true;
        }
        this.type = name == null ? RuntimeScalarType.UNDEF : RuntimeScalarType.STRING;
        this.value = name;
    }

    @Override
    public RuntimeScalar set(RuntimeScalar value) {
        setName(value.getDefinedBoolean() ? value.toString() : null);
        return this;
    }

    @Override public RuntimeScalar set(String value) { setName(value); return this; }
    @Override public RuntimeScalar set(int value) { setName(Integer.toString(value)); return this; }
    @Override public RuntimeScalar set(long value) { setName(Long.toString(value)); return this; }
    @Override public RuntimeScalar set(boolean value) { setName(value ? "1" : ""); return this; }

    @Override public String toString() { return getName() == null ? "" : getName(); }
    @Override public boolean getDefinedBoolean() { return getName() != null; }
    @Override public boolean getBoolean() { return getName() != null && !getName().isEmpty() && !"0".equals(getName()); }
    @Override public int getInt() { try { return Integer.parseInt(toString()); } catch (NumberFormatException ignored) { return 0; } }
    @Override public long getLong() { try { return Long.parseLong(toString()); } catch (NumberFormatException ignored) { return 0; } }
    @Override public double getDouble() { try { return Double.parseDouble(toString()); } catch (NumberFormatException ignored) { return 0; } }

    @Override
    public void dynamicSaveState() {
        RuntimeIO handle = currentHandle();
        stateStack().push(new State(handle,
                topFormat ? handle.currentTopFormatName : handle.currentFormatName,
                topFormat ? handle.currentTopFormatInitialized : handle.currentFormatInitialized));
        setName(null);
    }

    @Override
    public void dynamicRestoreState() {
        if (stateStack().isEmpty()) return;
        State state = stateStack().pop();
        if (topFormat) {
            state.handle.currentTopFormatName = state.name;
            state.handle.currentTopFormatInitialized = state.initialized;
        } else {
            state.handle.currentFormatName = state.name;
            state.handle.currentFormatInitialized = state.initialized;
        }
    }

    private record State(RuntimeIO handle, String name, boolean initialized) { }
}
