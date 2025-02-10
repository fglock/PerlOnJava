package org.perlonjava.perlmodule;

import org.perlonjava.runtime.RuntimeArray;
import org.perlonjava.runtime.RuntimeList;
import org.perlonjava.runtime.RuntimeScalar;
import org.perlonjava.symbols.ScopedSymbolTable;

import static org.perlonjava.parser.SpecialBlockParser.getCurrentScope;

/**
 * The Strict class provides functionalities similar to the Perl strict module.
 */
public class Strict extends PerlModuleBase {

    // Bitmask for strict options
    public static final int STRICT_REFS = 0x00000002;
    public static final int STRICT_SUBS = 0x00000200;
    public static final int STRICT_VARS = 0x00000400;

    // Bitmask for explicit strict options
    public static final int EXPLICIT_STRICT_REFS = 0x00000020;
    public static final int EXPLICIT_STRICT_SUBS = 0x00000040;
    public static final int EXPLICIT_STRICT_VARS = 0x00000080;

    /**
     * Constructor for Strict.
     * Initializes the module with the name "strict".
     */
    public Strict() {
        super("strict");
    }

    /**
     * Static initializer to set up the Strict module.
     */
    public static void initialize() {
        Strict strict = new Strict();
        try {
            strict.registerMethod("import", "useStrict", ";$");
            strict.registerMethod("unimport", "noStrict", ";$");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Strict method: " + e.getMessage());
        }
    }

    /**
     * Enables strict options.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList.
     */
    public static RuntimeList useStrict(RuntimeArray args, int ctx) {
        ScopedSymbolTable symbolTable = getCurrentScope();
        if (args.size() == 1) {
            // Enable all strict options if no specific category is provided
            symbolTable.enableStrictOption(STRICT_REFS | STRICT_SUBS | STRICT_VARS);
        } else {
            for (int i = 1; i < args.size(); i++) {
                String category = args.get(i).toString();
                switch (category) {
                    case "refs":
                        symbolTable.enableStrictOption(STRICT_REFS);
                        break;
                    case "subs":
                        symbolTable.enableStrictOption(STRICT_SUBS);
                        break;
                    case "vars":
                        symbolTable.enableStrictOption(STRICT_VARS);
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown strict category: " + category);
                }
            }
        }
        return new RuntimeScalar().getList();
    }

    /**
     * Disables strict options.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList.
     */
    public static RuntimeList noStrict(RuntimeArray args, int ctx) {
        ScopedSymbolTable symbolTable = getCurrentScope();
        if (args.size() == 1) {
            // Disable all strict options if no specific category is provided
            symbolTable.disableStrictOption(STRICT_REFS | STRICT_SUBS | STRICT_VARS);
        } else {
            for (int i = 1; i < args.size(); i++) {
                String category = args.get(i).toString();
                switch (category) {
                    case "refs":
                        symbolTable.disableStrictOption(STRICT_REFS);
                        break;
                    case "subs":
                        symbolTable.disableStrictOption(STRICT_SUBS);
                        break;
                    case "vars":
                        symbolTable.disableStrictOption(STRICT_VARS);
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown strict category: " + category);
                }
            }
        }
        return new RuntimeScalar().getList();
    }
}
