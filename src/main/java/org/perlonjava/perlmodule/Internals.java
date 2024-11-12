package org.perlonjava.perlmodule;

import org.perlonjava.runtime.PersistentVariable;
import org.perlonjava.runtime.RuntimeArray;
import org.perlonjava.runtime.RuntimeList;
import org.perlonjava.runtime.RuntimeScalar;

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
            internals.registerMethod("SvREADONLY", "svReadonly", ";$");
            internals.registerMethod("initialize_state_variable", "initializeStateVariable", "$$");
            internals.registerMethod("initialize_state_array", "initializeStateArray", "$$");
            internals.registerMethod("initialize_state_hash", "initializeStateHash", "$$");
            internals.registerMethod("is_initialized_state_variable", "isInitializedStateVariable", "$$");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Internals method: " + e.getMessage());
        }
    }

    /**
     * No-op, returns false.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return Empty list
     */
    public static RuntimeList svReadonly(RuntimeArray args, int ctx) {
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
        PersistentVariable.initializeStateVariable(
                args.get(0),
                args.get(1).toString(),
                args.get(2).getInt(),
                args.get(3));
        return new RuntimeList();
    }

    public static RuntimeList initializeStateArray(RuntimeArray args, int ctx) {
        PersistentVariable.initializeStateArray(
                args.shift(),
                args.shift().toString(),
                args.shift().getInt(),
                args);
        return new RuntimeList();
    }

    public static RuntimeList initializeStateHash(RuntimeArray args, int ctx) {
        PersistentVariable.initializeStateHash(
                args.shift(),
                args.shift().toString(),
                args.shift().getInt(),
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
        RuntimeScalar var = PersistentVariable.isInitializedStateVariable(
                args.get(0),
                args.get(1).toString(),
                args.get(2).getInt());
        return var.getList();
    }
}
