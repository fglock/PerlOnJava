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
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Internals method: " + e.getMessage());
        }
    }

    /**
     * No-op, returns false.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return     Empty list
     */
    public static RuntimeList svReadonly(RuntimeArray args, int ctx) {
        return new RuntimeList();
    }

    /**
     * Initialize a state variable exactly once
     *
     * @param args  Args: variable name with sigil; persistent variable id; value to initialize.
     * @param ctx   The context in which the method is called.
     * @return      Empty list
     */
    public static RuntimeList initializeStateVariable(RuntimeArray args, int ctx) {
        System.out.println("initializeStateVariable " + args);
        RuntimeScalar var = PersistentVariable.initializeStateVariable(
                args.get(0).toString(),
                args.get(1).getInt(),
                args.get(2));
        return new RuntimeList();
    }
}
