package org.perlonjava.runtime.perlmodule;

import org.perlonjava.frontend.semantic.ScopedSymbolTable;
import org.perlonjava.runtime.operators.WarnDie;
import org.perlonjava.runtime.runtimetypes.*;

import java.util.HashSet;
import java.util.Set;

import static org.perlonjava.frontend.parser.SpecialBlockParser.getCurrentScope;

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
        // Level 0 = the Perl code that called the warnings:: function
        // We add 1 to skip the Java implementation frame (Warnings.java) that appears
        // in the caller() stack trace when called from Java code
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
     * Gets the package name from caller() at the specified level.
     * 
     * @param level The stack level (0 = immediate caller of the warnings:: function)
     * @return The package name, or null if not available
     */
    private static String getCallerPackageAtLevel(int level) {
        RuntimeList caller = RuntimeCode.caller(
            new RuntimeList(RuntimeScalarCache.getScalarInt(level + 1)), 
            RuntimeContextType.LIST
        );
        if (caller.size() > 0) {
            return caller.elements.get(0).toString();
        }
        return null;
    }

    /**
     * Walks up the call stack past frames in warnings-registered packages to find
     * the "external caller" whose warning bits should be checked. This implements
     * Perl 5's _error_loc() behavior: skip frames in any package that has used
     * warnings::register (i.e., any custom warning category package).
     * 
     * @return The warning bits string from the first caller outside registered packages,
     *         or null if not found
     */
    private static String findExternalCallerBits() {
        for (int level = 0; level < 50; level++) {
            RuntimeList callerInfo = RuntimeCode.caller(
                new RuntimeList(RuntimeScalarCache.getScalarInt(level + 1)),
                RuntimeContextType.LIST
            );
            if (callerInfo.size() <= 0) break;
            
            String pkg = callerInfo.elements.get(0).toString();
            // Skip frames in any warnings-registered package
            if (!WarningFlags.isCustomCategory(pkg)) {
                // Found a caller outside registered packages
                if (callerInfo.size() > 9) {
                    RuntimeBase bitsBase = callerInfo.elements.get(9);
                    if (bitsBase instanceof RuntimeScalar) {
                        RuntimeScalar bitsScalar = (RuntimeScalar) bitsBase;
                        if (bitsScalar.type != RuntimeScalarType.UNDEF) {
                            return bitsScalar.toString();
                        }
                    }
                }
                return null;
            }
        }
        return null;
    }

    /**
     * Checks if the $^W global warning flag is set.
     * $^W is stored using Perl's internal encoding: "main::" + Character.toString('W' - 'A' + 1).
     * 
     * @return true if $^W is set to a true value, false otherwise
     */
    public static boolean isWarnFlagSet() {
        // $^W is stored as main:: + character code 23 (W - 'A' + 1 = 87 - 65 + 1 = 23)
        String varName = "main::" + Character.toString('W' - 'A' + 1);
        return GlobalVariable.getGlobalVariable(varName).getBoolean();
    }

    /**
     * Checks if warnings should be emitted for a specific category at runtime.
     * This is used by warn methods (like getNumberWarn) to determine if warnings
     * are enabled either via lexical warnings or $^W.
     * 
     * @param category The warning category to check (e.g., "uninitialized")
     * @return true if warnings should be emitted, false otherwise
     */
    public static boolean shouldWarn(String category) {
        // First check lexical warnings
        String bits = getWarningBitsAtLevel(1);
        if (bits != null && WarningFlags.isEnabledInBits(bits, category)) {
            return true;
        }
        // Fall back to $^W
        return isWarnFlagSet();
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
     * Enables warning categories, with support for FATAL/NONFATAL modifiers.
     * 
     * Supported syntax:
     * - use warnings;                      - enable all warnings
     * - use warnings 'category';           - enable specific category
     * - use warnings FATAL => 'all';       - enable all as FATAL
     * - use warnings FATAL => 'category';  - enable category as FATAL
     * - use warnings NONFATAL => 'all';    - downgrade FATAL to warnings
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

        ScopedSymbolTable symbolTable = getCurrentScope();
        
        // Track current modifier: null = normal, "FATAL" = make fatal, "NONFATAL" = make non-fatal
        String currentModifier = null;
        
        for (int i = 1; i < args.size(); i++) {
            String arg = args.get(i).toString();
            
            // Check for FATAL/NONFATAL modifiers
            if ("FATAL".equals(arg)) {
                currentModifier = "FATAL";
                continue;
            }
            if ("NONFATAL".equals(arg)) {
                currentModifier = "NONFATAL";
                continue;
            }
            
            // Handle disabled category (with - prefix)
            if (arg.startsWith("-")) {
                String category = arg.substring(1);
                if (!warningExists(category) && !"all".equals(category)) {
                    throw new PerlCompilerException("Unknown warnings category '" + category + "'");
                }
                warningManager.disableWarning(category);
                currentModifier = null;  // Reset modifier after use
                continue;
            }
            
            // Normal category
            String category = arg;
            if (!warningExists(category) && !"all".equals(category) && !"FATAL".equals(category) && !"NONFATAL".equals(category)) {
                throw new PerlCompilerException("Unknown warnings category '" + category + "'");
            }
            
            // Apply based on current modifier
            if ("FATAL".equals(currentModifier)) {
                // Enable as FATAL
                enableFatalCategory(symbolTable, category);
            } else if ("NONFATAL".equals(currentModifier)) {
                // Downgrade from FATAL to normal warning
                disableFatalCategory(symbolTable, category);
                warningManager.enableWarning(category);  // Still enable the warning
            } else {
                // Normal enable
                warningManager.enableWarning(category);
            }
            
            currentModifier = null;  // Reset modifier after use
        }
        
        return new RuntimeScalar().getList();
    }

    /**
     * Enables a warning category as FATAL (including subcategories).
     */
    private static void enableFatalCategory(ScopedSymbolTable symbolTable, String category) {
        // Enable the category
        symbolTable.enableWarningCategory(category);
        symbolTable.enableFatalWarningCategory(category);
        
        // Propagate to subcategories
        String[] subcategories = WarningFlags.getSubcategories(category);
        if (subcategories != null) {
            for (String sub : subcategories) {
                enableFatalCategory(symbolTable, sub);
            }
        }
    }

    /**
     * Disables FATAL mode for a warning category (including subcategories).
     */
    private static void disableFatalCategory(ScopedSymbolTable symbolTable, String category) {
        symbolTable.disableFatalWarningCategory(category);
        
        // Propagate to subcategories
        String[] subcategories = WarningFlags.getSubcategories(category);
        if (subcategories != null) {
            for (String sub : subcategories) {
                disableFatalCategory(symbolTable, sub);
            }
        }
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
        String category;
        if (args.size() > 0) {
            category = args.get(0).toString();
        } else {
            // No args: use calling package as category (Perl 5 behavior)
            String pkg = getCallerPackageAtLevel(0);
            category = (pkg != null) ? pkg : "all";
        }
        
        // Check scope-based runtime suppression first (from "no warnings 'category'" blocks)
        if (WarningFlags.isWarningSuppressedAtRuntime(category)) {
            return new RuntimeScalar(false).getList();
        }
        
        // For custom (registered) categories, walk past the registered package
        // to find the external caller's warning bits
        String bits;
        if (WarningFlags.isCustomCategory(category)) {
            bits = findExternalCallerBits();
        } else {
            bits = getWarningBitsAtLevel(0);
        }
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
        String category;
        if (args.size() > 0) {
            category = args.get(0).toString();
        } else {
            // No args: use calling package as category (Perl 5 behavior)
            String pkg = getCallerPackageAtLevel(0);
            category = (pkg != null) ? pkg : "all";
        }
        
        // Check scope-based runtime suppression first
        if (WarningFlags.isWarningSuppressedAtRuntime(category)) {
            return new RuntimeScalar(false).getList();
        }
        
        // For custom categories, walk past the registered package
        String bits;
        if (WarningFlags.isCustomCategory(category)) {
            bits = findExternalCallerBits();
        } else {
            bits = getWarningBitsAtLevel(0);
        }
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
            String pkg = getCallerPackageAtLevel(0);
            category = (pkg != null) ? pkg : "main";
        }
        
        // Check scope-based runtime suppression first (from "no warnings 'category'" blocks)
        if (WarningFlags.isWarningSuppressedAtRuntime(category)) {
            return new RuntimeScalar().getList();
        }
        
        // For custom (registered) categories, walk past the registered package
        // to find the external caller's warning bits
        String bits;
        if (WarningFlags.isCustomCategory(category)) {
            bits = findExternalCallerBits();
        } else {
            bits = getWarningBitsAtLevel(0);
        }
        
        // Check if category is enabled in lexical warnings
        boolean categoryEnabled = bits != null && WarningFlags.isEnabledInBits(bits, category);
        
        if (!categoryEnabled) {
            // Category not enabled via lexical warnings - fall back to $^W
            if (isWarnFlagSet()) {
                WarnDie.warn(message, new RuntimeScalar(""));
            }
            return new RuntimeScalar().getList();
        }
        
        // Category is enabled via lexical warnings
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
        
        // Check if category is enabled in lexical warnings
        boolean categoryEnabled = bits != null && WarningFlags.isEnabledInBits(bits, category);
        
        if (!categoryEnabled) {
            // Category not enabled via lexical warnings - fall back to $^W
            if (isWarnFlagSet()) {
                WarnDie.warn(message, new RuntimeScalar(""));
            }
            return new RuntimeScalar().getList();
        }
        
        // Category is enabled via lexical warnings
        // Check if FATAL - if so, die instead of warn
        if (WarningFlags.isFatalInBits(bits, category)) {
            WarnDie.die(message, new RuntimeScalar(""));
        } else {
            WarnDie.warn(message, new RuntimeScalar(""));
        }
        
        return new RuntimeScalar().getList();
    }
}
