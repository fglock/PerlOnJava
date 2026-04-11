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
    private static String internalOFS = "";

    /**
     * Stack for save/restore during local $, and for $, (list).
     */
    private static final Stack<String> ofsStack = new Stack<>();

    public OutputFieldSeparator() {
        super();
    }

    /**
     * Returns the internal OFS value for use by print.
     */
    public static String getInternalOFS() {
        return internalOFS;
    }

    /**
     * Save the current internalOFS onto the stack.
     * Called from GlobalRuntimeScalar.dynamicSaveState() when localizing $,.
     */
    public static void saveInternalOFS() {
        ofsStack.push(internalOFS);
    }

    /**
     * Restore internalOFS from the stack.
     * Called from GlobalRuntimeScalar.dynamicRestoreState() when restoring $,.
     */
    public static void restoreInternalOFS() {
        if (!ofsStack.isEmpty()) {
            internalOFS = ofsStack.pop();
        }
    }

    @Override
    public RuntimeScalar set(RuntimeScalar value) {
        super.set(value);
        internalOFS = this.toString();
        return this;
    }

    @Override
    public RuntimeScalar set(String value) {
        super.set(value);
        internalOFS = this.toString();
        return this;
    }

    @Override
    public RuntimeScalar set(int value) {
        super.set(value);
        internalOFS = this.toString();
        return this;
    }

    @Override
    public RuntimeScalar set(long value) {
        super.set(value);
        internalOFS = this.toString();
        return this;
    }

    @Override
    public RuntimeScalar set(boolean value) {
        super.set(value);
        internalOFS = this.toString();
        return this;
    }

    @Override
    public RuntimeScalar set(Object value) {
        super.set(value);
        internalOFS = this.toString();
        return this;
    }
}
