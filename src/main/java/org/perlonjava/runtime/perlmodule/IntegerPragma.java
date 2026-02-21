package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.frontend.semantic.ScopedSymbolTable;

import static org.perlonjava.frontend.parser.SpecialBlockParser.getCurrentScope;

/**
 * The IntegerPragma class provides functionalities similar to the Perl integer module.
 * When enabled, it forces all arithmetic operations to use integer arithmetic.
 */
public class IntegerPragma extends PerlModuleBase {

    /**
     * Runtime flag to track if integer mode is enabled.
     * This is used by math operators to determine whether to use integer arithmetic.
     */
    public static boolean useIntegerMode = false;

    /**
     * Constructor for IntegerPragma.
     * Initializes the module with the name "integer".
     */
    public IntegerPragma() {
        super("integer");
    }

    /**
     * Static initializer to set up the Integer module.
     */
    public static void initialize() {
        IntegerPragma integer = new IntegerPragma();
        try {
            integer.registerMethod("import", "useInteger", ";$");
            integer.registerMethod("unimport", "noInteger", ";$");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Integer method: " + e.getMessage());
        }
    }

    /**
     * Implements the 'use integer' pragma.
     *
     * @param args Arguments passed to the pragma
     * @param ctx  Context
     * @return RuntimeList indicating success
     */
    public static RuntimeList useInteger(RuntimeArray args, int ctx) {
        // Enable integer arithmetic by setting the HINT_INTEGER flag
        ScopedSymbolTable currentScope = getCurrentScope();
        if (currentScope != null) {
            currentScope.enableStrictOption(Strict.HINT_INTEGER);
        }
        return new RuntimeList();
    }

    /**
     * Implements the 'no integer' pragma.
     *
     * @param args Arguments passed to the pragma
     * @param ctx  Context
     * @return RuntimeList indicating success
     */
    public static RuntimeList noInteger(RuntimeArray args, int ctx) {
        // Disable integer arithmetic by clearing the HINT_INTEGER flag
        ScopedSymbolTable currentScope = getCurrentScope();
        if (currentScope != null) {
            currentScope.disableStrictOption(Strict.HINT_INTEGER);
        }
        return new RuntimeList();
    }
}
