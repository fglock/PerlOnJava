package org.perlonjava.runtime.perlmodule;

import org.perlonjava.frontend.semantic.ScopedSymbolTable;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import static org.perlonjava.frontend.parser.SpecialBlockParser.getCurrentScope;

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

    // Bitmask for `no overloading` pragma
    public static final int HINT_NO_AMAGIC = 0x00000010;

    // Bitmask for `use re` regex modifiers
    public static final int HINT_RE_ASCII = 0x01000000;     // use re '/a'
    public static final int HINT_RE_UNICODE = 0x02000000;   // use re '/u'
    public static final int HINT_RE_ASCII_AA = 0x04000000;  // use re '/aa'

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
            strict.registerMethod("bits", "strictBits", null);
            strict.registerMethod("all_bits", "strictAllBits", null);
            strict.registerMethod("all_explicit_bits", "strictAllExplicitBits", null);
            // Set $VERSION so CPAN.pm can detect our bundled version
            GlobalVariable.getGlobalVariable("strict::VERSION").set(new RuntimeScalar("1.14"));
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

    // Combined bitmask for all strict options
    private static final int ALL_BITS = HINT_STRICT_REFS | HINT_STRICT_SUBS | HINT_STRICT_VARS;
    // Combined bitmask for all explicit strict options
    private static final int ALL_EXPLICIT_BITS = HINT_EXPLICIT_STRICT_REFS | HINT_EXPLICIT_STRICT_SUBS | HINT_EXPLICIT_STRICT_VARS;

    /**
     * Returns the bitmask for the given strict categories.
     * Called as strict::bits('refs', 'subs', 'vars') from Perl.
     * When called from the strict package itself, also includes explicit bits.
     *
     * @param args The category names.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList containing the integer bitmask.
     */
    public static RuntimeList strictBits(RuntimeArray args, int ctx) {
        int bits = 0;
        if (args.size() == 0) {
            bits = ALL_BITS;
        } else {
            for (int i = 0; i < args.size(); i++) {
                String category = args.get(i).toString();
                switch (category) {
                    case "refs":
                        bits |= HINT_STRICT_REFS;
                        break;
                    case "subs":
                        bits |= HINT_STRICT_SUBS;
                        break;
                    case "vars":
                        bits |= HINT_STRICT_VARS;
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown 'strict' tag(s) '" + category + "'");
                }
            }
        }
        return new RuntimeScalar(bits).getList();
    }

    /**
     * Returns the combined bitmask for all strict categories (refs | subs | vars).
     *
     * @param args Unused.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList containing the integer bitmask 0x602.
     */
    public static RuntimeList strictAllBits(RuntimeArray args, int ctx) {
        return new RuntimeScalar(ALL_BITS).getList();
    }

    /**
     * Returns the combined bitmask for all explicit strict categories.
     *
     * @param args Unused.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList containing the integer bitmask 0xe0.
     */
    public static RuntimeList strictAllExplicitBits(RuntimeArray args, int ctx) {
        return new RuntimeScalar(ALL_EXPLICIT_BITS).getList();
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
