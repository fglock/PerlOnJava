package org.perlonjava.perlmodule;

import org.perlonjava.runtime.*;

import static org.perlonjava.runtime.RuntimeScalarCache.*;

/**
 * The Strict class provides functionalities similar to the Perl builtin module.
 */
public class builtin extends PerlModuleBase {

    /**
     * Constructor initializes the module.
     */
    public builtin() {
        super("builtin");
    }

    /**
     * Static initializer to set up the module.
     */
    public static void initialize() {
        builtin internals = new builtin();
        try {
            internals.registerMethod("is_bool", "isBoolean", "$");
            internals.registerMethod("true", "scalarTrue", "");
            internals.registerMethod("false", "scalarFalse", "");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Internals method: " + e.getMessage());
        }
    }

    /**
     * Returns true when given a distinguished boolean value, or false if not.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return Empty list
     */
    public static RuntimeList isBoolean(RuntimeArray args, int ctx) {
        RuntimeScalar res = args.get(0);
        return new RuntimeList(getScalarBoolean(res.type == RuntimeScalarType.BOOLEAN));
    }

    public static RuntimeList scalarTrue(RuntimeArray args, int ctx) {
        return new RuntimeList(scalarTrue);
    }

    public static RuntimeList scalarFalse(RuntimeArray args, int ctx) {
        return new RuntimeList(scalarFalse);
    }
}
