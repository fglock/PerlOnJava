package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.frontend.semantic.ScopedSymbolTable;

import static org.perlonjava.frontend.parser.SpecialBlockParser.getCurrentScope;

/**
 * The BytesPragma class provides functionalities similar to the Perl bytes module.
 * When enabled, it forces string operations to work with bytes rather than characters.
 */
public class BytesPragma extends PerlModuleBase {

    /**
     * Constructor for BytesPragma.
     * Initializes the module with the name "bytes".
     */
    public BytesPragma() {
        super("bytes");
    }

    /**
     * Static initializer to set up the Bytes module.
     */
    public static void initialize() {
        BytesPragma bytes = new BytesPragma();
        try {
            bytes.registerMethod("import", "useBytes", ";$");
            bytes.registerMethod("unimport", "noBytes", ";$");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Bytes method: " + e.getMessage());
        }
    }

    /**
     * Implements the 'use bytes' pragma.
     *
     * @param args Arguments passed to the pragma
     * @param ctx  Context
     * @return RuntimeList indicating success
     */
    public static RuntimeList useBytes(RuntimeArray args, int ctx) {
        // Enable bytes mode by setting the HINT_BYTES flag
        ScopedSymbolTable currentScope = getCurrentScope();
        if (currentScope != null) {
            currentScope.enableStrictOption(Strict.HINT_BYTES);
        }
        return new RuntimeList();
    }

    /**
     * Implements the 'no bytes' pragma.
     *
     * @param args Arguments passed to the pragma
     * @param ctx  Context
     * @return RuntimeList indicating success
     */
    public static RuntimeList noBytes(RuntimeArray args, int ctx) {
        // Disable bytes mode by clearing the HINT_BYTES flag
        ScopedSymbolTable currentScope = getCurrentScope();
        if (currentScope != null) {
            currentScope.disableStrictOption(Strict.HINT_BYTES);
        }
        return new RuntimeList();
    }
}
