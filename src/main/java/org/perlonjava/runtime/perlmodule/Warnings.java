package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.operators.WarnDie;
import org.perlonjava.runtime.runtimetypes.*;

import java.util.HashSet;
import java.util.Set;

/**
 * The Warnings class provides functionalities similar to the Perl warnings module.
 */
public class Warnings extends PerlModuleBase {

    public static final WarningFlags warningManager = new WarningFlags();

    /**
     * Constructor for Warnings.
     * Initializes the module with the name "warnings".
     */
    public Warnings() {
        super("warnings", false);
    }

    /**
     * Static initializer to set up the Warnings module.
     */
    public static void initialize() {
        Warnings warnings = new Warnings();
        try {
            warnings.registerMethod("enabled", ";$");
            warnings.registerMethod("import", "useWarnings", ";$");
            warnings.registerMethod("unimport", "noWarnings", ";$");
            warnings.registerMethod("warn", "warn", "$;$");
            warnings.registerMethod("warnif", "warnIf", "$;$");
            warnings.registerMethod("register_categories", "registerCategories", ";@");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Warnings method: " + e.getMessage());
        }
    }

    /**
     * Registers custom warning categories (used by warnings::register).
     *
     * @param args The arguments - category names to register.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList.
     */
    public static RuntimeList registerCategories(RuntimeArray args, int ctx) {
        for (int i = 0; i < args.size(); i++) {
            String category = args.get(i).toString();
            WarningFlags.registerCategory(category);
        }
        return new RuntimeScalar().getList();
    }

    /**
     * Enables a warning category.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList.
     */
    public static RuntimeList useWarnings(RuntimeArray args, int ctx) {
        // If no arguments, enable all warnings (use warnings;)
        if (args.size() == 1) {
            warningManager.initializeEnabledWarnings();
            return new RuntimeScalar().getList();
        }

        for (int i = 1; i < args.size(); i++) {
            String category = args.get(i).toString();
            if (category.startsWith("-")) {
                category = category.substring(1);
                if (!warningExists(category)) {
                    throw new PerlCompilerException("Unknown warnings category '" + category + "'");
                }
                warningManager.disableWarning(category.substring(1));
            } else {
                if (!warningExists(category)) {
                    throw new PerlCompilerException("Unknown warnings category '" + category + "'");
                }
                warningManager.enableWarning(category);
            }
        }
        return new RuntimeScalar().getList();
    }

    /**
     * Disables a warning category.
     * This is called for "no warnings 'category'".
     * Warning state is handled at compile time via the symbol table (like strict).
     * Additionally, registers the disabled categories for runtime scope checking
     * via $^WARNING_SCOPE mechanism (see dev/design/warnings-scope.md).
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList.
     */
    public static RuntimeList noWarnings(RuntimeArray args, int ctx) {
        Set<String> categories = new HashSet<>();
        
        if (args.size() <= 1) {
            // no warnings; - suppress all warnings
            categories.add("all");
            warningManager.disableWarning("all");
        } else {
            for (int i = 1; i < args.size(); i++) {
                String category = args.get(i).toString();
                if (!warningExists(category)) {
                    throw new PerlCompilerException("Unknown warnings category '" + category + "'");
                }
                categories.add(category);
                warningManager.disableWarning(category);
            }
        }
        
        // Register scope for runtime checking (sets lastScopeId for StatementParser)
        WarningFlags.registerScopeWarnings(categories);
        
        return new RuntimeScalar().getList();
    }

    /**
     * Checks if a warning category exists.
     *
     * @param category The name of the warning category to check.
     * @return True if the warning category exists, false otherwise.
     */
    public static boolean warningExists(String category) {
        return WarningFlags.getWarningList().contains(category);
    }

    /**
     * Checks if a warning is enabled.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList containing a boolean value.
     */
    public static RuntimeList enabled(RuntimeArray args, int ctx) {
        if (args.size() < 1 || args.size() > 2) {
            throw new IllegalStateException("Bad number of arguments for warnings::enabled()");
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
     * When called with just a message, checks if the calling package's warning category is enabled.
     * Also checks ${^WARNING_SCOPE} for runtime warning suppression via "no warnings 'category'".
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList.
     */
    public static RuntimeList warnIf(RuntimeArray args, int ctx) {
        if (args.size() < 1) {
            throw new IllegalStateException("Bad number of arguments for warnIf()");
        }
        
        String category;
        RuntimeScalar message;
        
        if (args.size() > 1) {
            // warnif(category, message)
            category = args.get(0).toString();
            message = args.get(1);
        } else {
            // warnif(message) - check calling package's category
            message = args.get(0);
            // Get the calling package to use as category
            RuntimeList caller = RuntimeCode.caller(new RuntimeList(RuntimeScalarCache.getScalarInt(0)), RuntimeContextType.LIST);
            if (caller.size() > 0) {
                category = caller.elements.get(0).toString();
            } else {
                category = "main";
            }
        }
        
        // Check runtime scope suppression via ${^WARNING_SCOPE}
        // This allows "no warnings 'Category'" in user code to propagate to warnif() calls
        RuntimeScalar scopeVar = GlobalVariable.getGlobalVariable(GlobalContext.WARNING_SCOPE);
        int scopeId = scopeVar.getInt();
        if (scopeId > 0 && WarningFlags.isWarningDisabledInScope(scopeId, category)) {
            // Warning is suppressed by caller's "no warnings"
            return new RuntimeScalar().getList();
        }
        
        if (warningManager.isWarningEnabled(category)) {
            // Use WarnDie.warn to go through $SIG{__WARN__}
            WarnDie.warn(message, new RuntimeScalar(""));
        }
        return new RuntimeScalar().getList();
    }
}
