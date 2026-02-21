package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.*;

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
            internals.registerMethod("SvREADONLY", "svReadonly", "\\[$@%];$");
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
     * Sets or gets the read-only status of a variable.
     *
     * @param args The arguments passed to the method: variable, optional flag to set readonly
     * @param ctx  The context in which the method is called.
     * @return Empty list
     */
    public static RuntimeList svReadonly(RuntimeArray args, int ctx) {
        if (args.size() >= 2) {
            RuntimeBase variable = args.get(0);
            RuntimeBase flag = args.get(1);

            // If flag is true (non-zero), make the variable readonly
            if (flag.getBoolean()) {
                if (variable instanceof RuntimeArray array) {
                    array.type = RuntimeArray.READONLY_ARRAY;
                } else if (variable instanceof RuntimeScalar scalar) {
                    // Check if it's a scalar reference (from \$var)
                    if (scalar.type == RuntimeScalarType.REFERENCE && scalar.value instanceof RuntimeScalar targetScalar) {
                        // Replace the target scalar with a readonly version
                        RuntimeScalarReadOnly readonlyScalar;
                        if (targetScalar.type == RuntimeScalarType.INTEGER) {
                            readonlyScalar = new RuntimeScalarReadOnly(targetScalar.getInt());
                        } else if (targetScalar.type == RuntimeScalarType.BOOLEAN) {
                            readonlyScalar = new RuntimeScalarReadOnly(targetScalar.getBoolean());
                        } else if (targetScalar.type == RuntimeScalarType.STRING) {
                            readonlyScalar = new RuntimeScalarReadOnly(targetScalar.toString());
                        } else {
                            readonlyScalar = new RuntimeScalarReadOnly(); // undef
                        }
                        // Copy readonly value into the actual target scalar (replace variable contents)
                        targetScalar.type = readonlyScalar.type;
                        targetScalar.value = readonlyScalar.value;
                    }
                }
            }
        }
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
