package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.operators.WarnDie;
import org.perlonjava.runtime.runtimetypes.*;

import java.util.HashSet;
import java.util.Set;

/**
 * The Warnings class provides functionalities similar to the Perl warnings module.
 * 
 * Key methods use caller()[9] to check the warning bits at the caller's scope,
 * enabling lexical warning control to work correctly across subroutine calls.
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
            warnings.registerMethod("enabled", "enabled", ";$");
            warnings.registerMethod("fatal_enabled", "fatalEnabled", ";$");
            warnings.registerMethod("enabled_at_level", "enabledAtLevel", "$$");
            warnings.registerMethod("fatal_enabled_at_level", "fatalEnabledAtLevel", "$$");
            warnings.registerMethod("import", "useWarnings", ";$");
            warnings.registerMethod("unimport", "noWarnings", ";$");
            warnings.registerMethod("warn", "warn", "$;$");
            warnings.registerMethod("warnif", "warnIf", "$;$");
            warnings.registerMethod("warnif_at_level", "warnIfAtLevel", "$$$");
            warnings.registerMethod("register_categories", "registerCategories", ";@");
            // Set $VERSION so CPAN.pm can detect our bundled version
            GlobalVariable.getGlobalVariable("warnings::VERSION").set(new RuntimeScalar("1.74"));
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Warnings method: " + e.getMessage());
        }
    }

    /**
     * Gets the warning bits string from caller() at the specified level.
     * Level 0 is the immediate caller of the warnings:: function.
     * 
     * @param level The stack level (0 = immediate caller)
     * @return The warning bits string, or null if not available
     */
    private static String getWarningBitsAtLevel(int level) {
        // Add 1 because we're inside a warnings:: function
        RuntimeList caller = RuntimeCode.caller(
            new RuntimeList(RuntimeScalarCache.getScalarInt(level + 1)), 
            RuntimeContextType.LIST
        );
        if (caller.size() > 9) {
            RuntimeBase bitsBase = caller.elements.get(9);
            if (bitsBase instanceof RuntimeScalar) {
                RuntimeScalar bits = (RuntimeScalar) bitsBase;
                if (bits.type != RuntimeScalarType.UNDEF) {
                    return bits.toString();
                }
            }
        }
        return null;
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
     * Checks if a warning category is enabled at the caller's scope.
     * Uses caller()[9] to get the warning bits from the calling scope.
     *
     * @param args The arguments passed to the method (optional category).
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList containing a boolean value.
     */
    public static RuntimeList enabled(RuntimeArray args, int ctx) {
        String category = "all";
        if (args.size() > 0) {
            category = args.get(0).toString();
        }
        
        String bits = getWarningBitsAtLevel(0);
        boolean isEnabled = bits != null && WarningFlags.isEnabledInBits(bits, category);
        return new RuntimeScalar(isEnabled).getList();
    }

    /**
     * Checks if a warning category is enabled at the specified stack level.
     *
     * @param args The arguments: level, category
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList containing a boolean value.
     */
    public static RuntimeList enabledAtLevel(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            throw new IllegalStateException("Usage: warnings::enabled_at_level(level, category)");
        }
        int level = args.get(0).getInt();
        String category = args.get(1).toString();
        
        String bits = getWarningBitsAtLevel(level);
        boolean isEnabled = bits != null && WarningFlags.isEnabledInBits(bits, category);
        return new RuntimeScalar(isEnabled).getList();
    }

    /**
     * Checks if a warning category is FATAL at the caller's scope.
     *
     * @param args The arguments passed to the method (optional category).
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList containing a boolean value.
     */
    public static RuntimeList fatalEnabled(RuntimeArray args, int ctx) {
        String category = "all";
        if (args.size() > 0) {
            category = args.get(0).toString();
        }
        
        String bits = getWarningBitsAtLevel(0);
        boolean isFatal = bits != null && WarningFlags.isFatalInBits(bits, category);
        return new RuntimeScalar(isFatal).getList();
    }

    /**
     * Checks if a warning category is FATAL at the specified stack level.
     *
     * @param args The arguments: level, category
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList containing a boolean value.
     */
    public static RuntimeList fatalEnabledAtLevel(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            throw new IllegalStateException("Usage: warnings::fatal_enabled_at_level(level, category)");
        }
        int level = args.get(0).getInt();
        String category = args.get(1).toString();
        
        String bits = getWarningBitsAtLevel(level);
        boolean isFatal = bits != null && WarningFlags.isFatalInBits(bits, category);
        return new RuntimeScalar(isFatal).getList();
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
     * Issues a warning if the category is enabled at the caller's scope.
     * Uses caller()[9] to check warning bits from the calling scope.
     * If the category is FATAL, dies instead of warning.
     *
     * @param args The arguments: category, message OR just message
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
            // warnif(message) - use calling package as category
            message = args.get(0);
            RuntimeList caller = RuntimeCode.caller(
                new RuntimeList(RuntimeScalarCache.getScalarInt(1)), 
                RuntimeContextType.LIST
            );
            if (caller.size() > 0) {
                category = caller.elements.get(0).toString();
            } else {
                category = "main";
            }
        }
        
        // Check warning bits from caller's scope
        String bits = getWarningBitsAtLevel(0);
        if (bits == null) {
            return new RuntimeScalar().getList();
        }
        
        // Check if category is enabled
        if (!WarningFlags.isEnabledInBits(bits, category)) {
            return new RuntimeScalar().getList();
        }
        
        // Check if FATAL - if so, die instead of warn
        if (WarningFlags.isFatalInBits(bits, category)) {
            WarnDie.die(message, new RuntimeScalar(""));
        } else {
            WarnDie.warn(message, new RuntimeScalar(""));
        }
        
        return new RuntimeScalar().getList();
    }

    /**
     * Issues a warning if the category is enabled at the specified stack level.
     * If the category is FATAL at that level, dies instead of warning.
     *
     * @param args The arguments: level, category, message
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList.
     */
    public static RuntimeList warnIfAtLevel(RuntimeArray args, int ctx) {
        if (args.size() < 3) {
            throw new IllegalStateException("Usage: warnings::warnif_at_level(level, category, message)");
        }
        
        int level = args.get(0).getInt();
        String category = args.get(1).toString();
        RuntimeScalar message = args.get(2);
        
        // Check warning bits at specified level
        String bits = getWarningBitsAtLevel(level);
        if (bits == null) {
            return new RuntimeScalar().getList();
        }
        
        // Check if category is enabled
        if (!WarningFlags.isEnabledInBits(bits, category)) {
            return new RuntimeScalar().getList();
        }
        
        // Check if FATAL - if so, die instead of warn
        if (WarningFlags.isFatalInBits(bits, category)) {
            WarnDie.die(message, new RuntimeScalar(""));
        } else {
            WarnDie.warn(message, new RuntimeScalar(""));
        }
        
        return new RuntimeScalar().getList();
    }
}
