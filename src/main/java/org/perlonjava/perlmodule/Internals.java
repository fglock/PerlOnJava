package org.perlonjava.perlmodule;

import org.perlonjava.runtime.RuntimeArray;
import org.perlonjava.runtime.RuntimeList;
import org.perlonjava.runtime.RuntimeScalar;
import org.perlonjava.runtime.StateVariable;

/**
 * The Strict class provides functionalities similar to the Perl strict module.
 */
public class Internals extends PerlModuleBase {

    /**
     * Constructor initializes the module.
     */
    public Internals() {
        super("Internals");
    }

    /**
     * Static initializer to set up the module.
     */
    public static void initialize() {
        Internals internals = new Internals();
        try {
            internals.registerMethod("SvREADONLY", "svReadonly", "$;$");
            internals.registerMethod("SvREFCNT", "svRefcount", "$;$");
            internals.registerMethod("initialize_state_variable", "initializeStateVariable", "$$");
            internals.registerMethod("initialize_state_array", "initializeStateArray", "$$");
            internals.registerMethod("initialize_state_hash", "initializeStateHash", "$$");
            internals.registerMethod("is_initialized_state_variable", "isInitializedStateVariable", "$$");
            internals.registerMethod("stack_refcounted", null);
            internals.registerMethod("V", "V", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Internals method: " + e.getMessage());
        }
    }

    public static RuntimeList stack_refcounted(RuntimeArray args, int ctx) {

        // XXX TODO placeholder

        return new RuntimeList();
    }

    /**
     * No-op, returns false.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return Empty list
     */
    public static RuntimeList V(RuntimeArray args, int ctx) {

        // XXX TODO

        return new RuntimeList();
    }

    /**
     * No-op, returns false.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return Empty list
     */
    public static RuntimeList svRefcount(RuntimeArray args, int ctx) {

        // XXX TODO rewrite this to emit a RuntimeScalarReadOnly
        // It needs to happen at the emitter, because the variable container needs to be replaced.

        return new RuntimeList();
    }

    /**
     * No-op, returns false.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return Empty list
     */
    public static RuntimeList svReadonly(RuntimeArray args, int ctx) {

        // XXX TODO rewrite this to emit a RuntimeScalarReadOnly
        // It needs to happen at the emitter, because the variable container needs to be replaced.

        return new RuntimeList();
    }

    /**
     * Initialize a state variable exactly once
     *
     * @param args Args: variable name with sigil; persistent variable id; value to initialize.
     * @param ctx  The context in which the method is called.
     * @return Empty list
     */
    public static RuntimeList initializeStateVariable(RuntimeArray args, int ctx) {
        StateVariable.initializeStateVariable(
                args.get(0),
                args.get(1).toString(),
                args.get(2).getInt(),
                args.get(3));
        return new RuntimeList();
    }

    public static RuntimeList initializeStateArray(RuntimeArray args, int ctx) {
        StateVariable.initializeStateArray(
                RuntimeArray.shift(args),
                RuntimeArray.shift(args).toString(),
                RuntimeArray.shift(args).getInt(),
                args);
        return new RuntimeList();
    }

    public static RuntimeList initializeStateHash(RuntimeArray args, int ctx) {
        StateVariable.initializeStateHash(
                RuntimeArray.shift(args),
                RuntimeArray.shift(args).toString(),
                RuntimeArray.shift(args).getInt(),
                args);
        return new RuntimeList();
    }

    /**
     * Check is a state variable was initialized
     *
     * @param args Args: variable name with sigil; persistent variable id.
     * @param ctx  The context in which the method is called.
     * @return RuntimeScalar with true or false.
     */
    public static RuntimeList isInitializedStateVariable(RuntimeArray args, int ctx) {
        RuntimeScalar var = StateVariable.isInitializedStateVariable(
                args.get(0),
                args.get(1).toString(),
                args.get(2).getInt());
        return var.getList();
    }
}
