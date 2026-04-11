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
     * Stack for save/restore during local $, and for $, (list).
     * Now held per-PerlRuntime.
     */
    private static Stack<String> ofsStack() {
        return PerlRuntime.current().ofsStack;
    }

    public OutputFieldSeparator() {
        super();
    }

    /**
     * Returns the internal OFS value for use by print.
     * Now per-PerlRuntime for multiplicity thread-safety.
     */
    public static String getInternalOFS() {
        return PerlRuntime.current().internalOFS;
    }

    /**
     * Save the current internalOFS onto the stack.
     * Called from GlobalRuntimeScalar.dynamicSaveState() when localizing $,.
     */
    public static void saveInternalOFS() {
        PerlRuntime rt = PerlRuntime.current();
        ofsStack().push(rt.internalOFS);
    }

    /**
     * Restore internalOFS from the stack.
     * Called from GlobalRuntimeScalar.dynamicRestoreState() when restoring $,.
     */
    public static void restoreInternalOFS() {
        if (!ofsStack().isEmpty()) {
            PerlRuntime.current().internalOFS = ofsStack().pop();
        }
    }

    @Override
    public RuntimeScalar set(RuntimeScalar value) {
        super.set(value);
        PerlRuntime.current().internalOFS = this.toString();
        return this;
    }

    @Override
    public RuntimeScalar set(String value) {
        super.set(value);
        PerlRuntime.current().internalOFS = this.toString();
        return this;
    }

    @Override
    public RuntimeScalar set(int value) {
        super.set(value);
        PerlRuntime.current().internalOFS = this.toString();
        return this;
    }

    @Override
    public RuntimeScalar set(long value) {
        super.set(value);
        PerlRuntime.current().internalOFS = this.toString();
        return this;
    }

    @Override
    public RuntimeScalar set(boolean value) {
        super.set(value);
        PerlRuntime.current().internalOFS = this.toString();
        return this;
    }

    @Override
    public RuntimeScalar set(Object value) {
        super.set(value);
        PerlRuntime.current().internalOFS = this.toString();
        return this;
    }
}
