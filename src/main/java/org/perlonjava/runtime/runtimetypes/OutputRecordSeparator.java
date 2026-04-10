package org.perlonjava.runtime.runtimetypes;

import java.util.Stack;

/**
 * Special variable for $\ (output record separator).
 *
 * <p>In Perl, the output record separator ($\) has special semantics:
 * when print reads $\, it uses an internal copy (PL_ors_sv in C Perl)
 * that is only updated by direct assignment to $\. This means that
 * aliasing $\ via "for $\ (@list)" does NOT affect what print appends,
 * because the alias changes the Perl-visible variable but not the
 * internal ORS value.
 *
 * <p>This class maintains a static {@code internalORS} that print reads,
 * separate from the variable's value in the global symbol table.
 * Only {@code set()} on an OutputRecordSeparator instance updates
 * {@code internalORS}; aliasing replaces the map entry with a plain
 * RuntimeScalar whose set() does not touch internalORS.
 */
public class OutputRecordSeparator extends RuntimeScalar {

    /**
     * Stack for save/restore during local $\ and for $\ (list).
     * Now held per-PerlRuntime.
     */
    private static Stack<String> orsStack() {
        return PerlRuntime.current().orsStack;
    }

    public OutputRecordSeparator() {
        super();
    }

    /**
     * Returns the internal ORS value for use by print.
     * Now per-PerlRuntime for multiplicity thread-safety.
     */
    public static String getInternalORS() {
        return PerlRuntime.current().internalORS;
    }

    /**
     * Save the current internalORS onto the stack.
     * Called from GlobalRuntimeScalar.dynamicSaveState() when localizing $\.
     */
    public static void saveInternalORS() {
        PerlRuntime rt = PerlRuntime.current();
        orsStack().push(rt.internalORS);
    }

    /**
     * Restore internalORS from the stack.
     * Called from GlobalRuntimeScalar.dynamicRestoreState() when restoring $\.
     */
    public static void restoreInternalORS() {
        if (!orsStack().isEmpty()) {
            PerlRuntime.current().internalORS = orsStack().pop();
        }
    }

    @Override
    public RuntimeScalar set(RuntimeScalar value) {
        super.set(value);
        PerlRuntime.current().internalORS = this.toString();
        return this;
    }

    @Override
    public RuntimeScalar set(String value) {
        super.set(value);
        PerlRuntime.current().internalORS = this.toString();
        return this;
    }

    @Override
    public RuntimeScalar set(int value) {
        super.set(value);
        PerlRuntime.current().internalORS = this.toString();
        return this;
    }

    @Override
    public RuntimeScalar set(long value) {
        super.set(value);
        PerlRuntime.current().internalORS = this.toString();
        return this;
    }

    @Override
    public RuntimeScalar set(boolean value) {
        super.set(value);
        PerlRuntime.current().internalORS = this.toString();
        return this;
    }

    @Override
    public RuntimeScalar set(Object value) {
        super.set(value);
        PerlRuntime.current().internalORS = this.toString();
        return this;
    }
}
