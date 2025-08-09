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

    // Bitmask for use integer, bytes
    public static final int HINT_INTEGER = 0x00000001;
    public static final int HINT_BYTES = 0x00000008;

    // Bitmask for strict options
    public static final int HINT_STRICT_REFS = 0x00000002;
    public static final int HINT_STRICT_SUBS = 0x00000200;
    public static final int HINT_STRICT_VARS = 0x00000400;

    // Bitmask for explicit strict options
    public static final int HINT_EXPLICIT_STRICT_REFS = 0x00000020;
    public static final int HINT_EXPLICIT_STRICT_SUBS = 0x00000040;
    public static final int HINT_EXPLICIT_STRICT_VARS = 0x00000080;

    // Bitmask for utf8 source code
    public static final int HINT_UTF8 = 0x00800000;

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
            symbolTable.enableStrictOption(HINT_STRICT_REFS | HINT_STRICT_SUBS | HINT_STRICT_VARS);
        } else {
            for (int i = 1; i < args.size(); i++) {
                String category = args.get(i).toString();
                switch (category) {
                    case "refs":
                        symbolTable.enableStrictOption(HINT_STRICT_REFS);
                        break;
                    case "subs":
                        symbolTable.enableStrictOption(HINT_STRICT_SUBS);
                        break;
                    case "vars":
                        symbolTable.enableStrictOption(HINT_STRICT_VARS);
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
            symbolTable.disableStrictOption(HINT_STRICT_REFS | HINT_STRICT_SUBS | HINT_STRICT_VARS);
        } else {
            for (int i = 1; i < args.size(); i++) {
                String category = args.get(i).toString();
                switch (category) {
                    case "refs":
                        symbolTable.disableStrictOption(HINT_STRICT_REFS);
                        break;
                    case "subs":
                        symbolTable.disableStrictOption(HINT_STRICT_SUBS);
                        break;
                    case "vars":
                        symbolTable.disableStrictOption(HINT_STRICT_VARS);
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown strict category: " + category);
                }
            }
        }
        return new RuntimeScalar().getList();
    }

    public static String stringifyStrictOptions(int strictOptions) {
        StringBuilder result = new StringBuilder();

        // Handle integer and bytes hints
        if ((strictOptions & HINT_INTEGER) != 0) {
            result.append("INTEGER");
        }
        if ((strictOptions & HINT_BYTES) != 0) {
            if (!result.isEmpty()) {
                result.append(", ");
            }
            result.append("BYTES");
        }

        // Handle strict options
        if ((strictOptions & HINT_STRICT_REFS) != 0) {
            if (!result.isEmpty()) {
                result.append(", ");
            }
            result.append("STRICT_REFS");
        }
        if ((strictOptions & HINT_STRICT_SUBS) != 0) {
            if (!result.isEmpty()) {
                result.append(", ");
            }
            result.append("STRICT_SUBS");
        }
        if ((strictOptions & HINT_STRICT_VARS) != 0) {
            if (!result.isEmpty()) {
                result.append(", ");
            }
            result.append("STRICT_VARS");
        }

        // Handle explicit strict options
        if ((strictOptions & HINT_EXPLICIT_STRICT_REFS) != 0) {
            if (!result.isEmpty()) {
                result.append(", ");
            }
            result.append("EXPLICIT_STRICT_REFS");
        }
        if ((strictOptions & HINT_EXPLICIT_STRICT_SUBS) != 0) {
            if (!result.isEmpty()) {
                result.append(", ");
            }
            result.append("EXPLICIT_STRICT_SUBS");
        }
        if ((strictOptions & HINT_EXPLICIT_STRICT_VARS) != 0) {
            if (!result.isEmpty()) {
                result.append(", ");
            }
            result.append("EXPLICIT_STRICT_VARS");
        }

        // Handle UTF8 option
        if ((strictOptions & HINT_UTF8) != 0) {
            if (!result.isEmpty()) {
                result.append(", ");
            }
            result.append("UTF8");
        }

        return result.toString();
    }
}
