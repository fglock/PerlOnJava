package org.perlonjava.runtime.perlmodule;

import org.perlonjava.frontend.semantic.ScopedSymbolTable;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeList;

import static org.perlonjava.frontend.parser.SpecialBlockParser.getCurrentScope;

/**
 * The OverloadingPragma class implements the Perl 'overloading' pragma.
 * {@code no overloading} suppresses overload dispatch (stringification, numification, etc.)
 * for the enclosing lexical scope.
 * {@code use overloading} re-enables it.
 */
public class OverloadingPragma extends PerlModuleBase {

    /**
     * Constructor for OverloadingPragma.
     * Initializes the module with the name "overloading".
     */
    public OverloadingPragma() {
        super("overloading");
    }

    /**
     * Static initializer to set up the overloading module.
     */
    public static void initialize() {
        OverloadingPragma pragma = new OverloadingPragma();
        try {
            pragma.registerMethod("import", "useOverloading", ";$");
            pragma.registerMethod("unimport", "noOverloading", ";$");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing overloading method: " + e.getMessage());
        }
    }

    /**
     * Implements the 'use overloading' pragma.
     * Re-enables overload dispatch by clearing the HINT_NO_AMAGIC flag.
     *
     * @param args Arguments passed to the pragma
     * @param ctx  Context
     * @return RuntimeList indicating success
     */
    public static RuntimeList useOverloading(RuntimeArray args, int ctx) {
        ScopedSymbolTable currentScope = getCurrentScope();
        if (currentScope != null) {
            currentScope.disableStrictOption(Strict.HINT_NO_AMAGIC);
        }
        return new RuntimeList();
    }

    /**
     * Implements the 'no overloading' pragma.
     * Suppresses overload dispatch by setting the HINT_NO_AMAGIC flag.
     *
     * @param args Arguments passed to the pragma
     * @param ctx  Context
     * @return RuntimeList indicating success
     */
    public static RuntimeList noOverloading(RuntimeArray args, int ctx) {
        ScopedSymbolTable currentScope = getCurrentScope();
        if (currentScope != null) {
            currentScope.enableStrictOption(Strict.HINT_NO_AMAGIC);
        }
        return new RuntimeList();
    }
}
