package org.perlonjava.perlmodule;

import org.perlonjava.runtime.*;

/**
 * The Warnings class provides functionalities similar to the Perl warnings module.
 */
public class Warnings extends PerlModuleBase {

    private static final ScopedSymbolTable symbolTable = new ScopedSymbolTable();
    private static final WarningFlags warningManager = new WarningFlags(symbolTable);

    /**
     * Constructor for Warnings.
     * Initializes the module with the name "warnings".
     */
    public Warnings() {
        super("warnings");
    }

    /**
     * Static initializer to set up the Warnings module.
     */
    public static void initialize() {
        Warnings warnings = new Warnings();
        try {
            warnings.registerMethod("warnings_enabled", "$;$");
            warnings.registerMethod("warnings_enabled", ";$");
            warnings.registerMethod("import", "useWarnings", ";$");
            warnings.registerMethod("unimport", "noWarnings", ";$");
            warnings.registerMethod("warn", "warn", "$;$");
            warnings.registerMethod("warnif", "warnIf", "$;$");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Warnings method: " + e.getMessage());
        }
    }

    /**
     * Enables a warning category.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList.
     */
    public static RuntimeList useWarnings(RuntimeArray args, int ctx) {
        for (int i = 1; i < args.size(); i++) {
            String category = args.get(i).toString();
            if (category.startsWith("-")) {
                warningManager.disableWarning(category.substring(1));
            } else {
                warningManager.enableWarning(category);
            }
        }
        return new RuntimeScalar().getList();
    }

    /**
     * Disables a warning category.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList.
     */
    public static RuntimeList noWarnings(RuntimeArray args, int ctx) {
        for (int i = 1; i < args.size(); i++) {
            String category = args.get(i).toString();
            warningManager.disableWarning(category);
        }
        return new RuntimeScalar().getList();
    }

    /**
     * Checks if a warning is enabled.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList containing a boolean value.
     */
    public static RuntimeList warnings_enabled(RuntimeArray args, int ctx) {
        if (args.size() < 1 || args.size() > 2) {
            throw new IllegalStateException("Bad number of arguments for warnings_enabled()");
        }
        String category = args.get(0).toString();
        boolean isEnabled = warningManager.isWarningEnabled(category);
        return new RuntimeScalar(isEnabled).getList();
    }

    /**
     * Issues a warning.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList.
     */
    public static RuntimeList warn(RuntimeArray args, int ctx) {
        if (args.size() < 1) {
            throw new IllegalStateException("Bad number of arguments for warn()");
        }
        String message = args.get(0).toString();
        System.err.println("Warning: " + message);
        return new RuntimeScalar().getList();
    }

    /**
     * Issues a warning if the category is enabled.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList.
     */
    public static RuntimeList warnIf(RuntimeArray args, int ctx) {
        if (args.size() < 1) {
            throw new IllegalStateException("Bad number of arguments for warnIf()");
        }
        String category = args.size() > 1 ? args.get(0).toString() : "all";
        String message = args.get(args.size() - 1).toString();
        if (warningManager.isWarningEnabled(category)) {
            System.err.println("Warning: " + message);
        }
        return new RuntimeScalar().getList();
    }
}
