package org.perlonjava.perlmodule;

import org.perlonjava.runtime.RuntimeArray;
import org.perlonjava.runtime.RuntimeList;
import org.perlonjava.runtime.RuntimeScalar;
import org.perlonjava.runtime.ScopedSymbolTable;

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
        Internals strict = new Internals();
        try {
            strict.registerMethod("SvREADONLY", "svReadonly", ";$");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Internals method: " + e.getMessage());
        }
    }

    /**
     * No-op, returns false.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList.
     */
    public static RuntimeList svReadonly(RuntimeArray args, int ctx) {
        return new RuntimeList();
    }
}
