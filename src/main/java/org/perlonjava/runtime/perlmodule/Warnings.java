package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.*;

import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.Deque;

/**
 * The Warnings class provides functionalities similar to the Perl warnings module.
 */
public class Warnings extends PerlModuleBase {

    public static final WarningFlags warningManager = new WarningFlags();
    
    /**
     * ThreadLocal stack of disabled warning categories for runtime lexical scoping.
     * Each entry is a BitSet where set bits indicate disabled categories.
     * This allows 'no warnings "numeric"' to properly suppress warnings even when $^W is set.
     */
    private static final ThreadLocal<Deque<BitSet>> runtimeDisabledStack = 
            ThreadLocal.withInitial(() -> {
                Deque<BitSet> stack = new ArrayDeque<>();
                stack.push(new BitSet());  // Initial empty disabled set
                return stack;
            });
    
    /**
     * Bit position for the "numeric" warning category in the runtime disabled stack.
     */
    public static final int WARN_NUMERIC = 0;
    public static final int WARN_ALL = 1;

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
        // If no arguments, enable all warnings (use warnings;)
        if (args.size() == 1) {
            warningManager.initializeEnabledWarnings();
            // Clear runtime disabled flags for common categories
            enableRuntimeWarning(WARN_NUMERIC);
            enableRuntimeWarning(WARN_ALL);
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
                // Also enable at runtime (clear disabled flag)
                if (category.equals("numeric")) {
                    enableRuntimeWarning(WARN_NUMERIC);
                } else if (category.equals("all")) {
                    enableRuntimeWarning(WARN_ALL);
                }
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
            if (!warningExists(category)) {
                throw new PerlCompilerException("Unknown warnings category '" + category + "'");
            }
            warningManager.disableWarning(category);
            // Also disable at runtime for proper lexical scoping
            if (category.equals("numeric")) {
                disableRuntimeWarning(WARN_NUMERIC);
            } else if (category.equals("all")) {
                disableRuntimeWarning(WARN_ALL);
            }
        }
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
    
    // ================== Runtime Warning Scope Management ==================
    
    /**
     * Enter a new runtime warning scope. Called at the start of blocks with 'no warnings'.
     */
    public static void enterWarningScope() {
        Deque<BitSet> stack = runtimeDisabledStack.get();
        stack.push((BitSet) stack.peek().clone());
    }
    
    /**
     * Exit the current runtime warning scope. Called at the end of blocks with 'no warnings'.
     */
    public static void exitWarningScope() {
        Deque<BitSet> stack = runtimeDisabledStack.get();
        if (stack.size() > 1) {
            stack.pop();
        }
    }
    
    /**
     * Disable a warning category at runtime.
     */
    public static void disableRuntimeWarning(int category) {
        runtimeDisabledStack.get().peek().set(category);
    }
    
    /**
     * Enable a warning category at runtime (clear the disabled bit).
     */
    public static void enableRuntimeWarning(int category) {
        runtimeDisabledStack.get().peek().clear(category);
    }
    
    /**
     * Check if a warning category is disabled at runtime.
     * This is used by NumberParser to check if numeric warnings should be suppressed.
     */
    public static boolean isRuntimeWarningDisabled(int category) {
        return runtimeDisabledStack.get().peek().get(category);
    }
    
    /**
     * Check if numeric warnings are disabled at runtime.
     * Convenience method for the common case.
     */
    public static boolean isNumericWarningDisabled() {
        BitSet disabled = runtimeDisabledStack.get().peek();
        return disabled.get(WARN_NUMERIC) || disabled.get(WARN_ALL);
    }
}
