package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.frontend.semantic.ScopedSymbolTable;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.perlonjava.frontend.parser.SpecialBlockParser.getCurrentScope;

/**
 * A class to control lexical warnings flags based on a hierarchy of categories.
 * Warning state is managed at compile time through the symbol table (like strict).
 * 
 * For runtime checking (e.g., warnif), we use a scope ID mechanism:
 * - Each block with "no warnings" gets a unique scope ID at compile time
 * - The disabled categories for each scope ID are stored in a static map
 * - At runtime, "local $^WARNING_SCOPE" tracks the current scope ID
 * - warnif() looks up the current scope's disabled categories
 */
public class WarningFlags {
    // A hierarchy of warning categories
    private static final Map<String, String[]> warningHierarchy = new HashMap<>();
    
    // Custom warning categories registered via warnings::register
    private static final Set<String> customCategories = new HashSet<>();
    
    // Global flag to track if "use warnings" has been called (for runtime checks)
    private static boolean globalWarningsEnabled = false;
    
    // Scope ID counter for generating unique scope IDs
    private static final AtomicInteger scopeIdCounter = new AtomicInteger(0);
    
    // Map from scope ID to set of disabled warning categories
    // This is populated at compile time and read at runtime
    private static final Map<Integer, Set<String>> scopeDisabledWarnings = new HashMap<>();
    
    // The scope ID from the last noWarnings() call (read by StatementParser)
    private static int lastScopeId = 0;

    static {
        // Initialize the hierarchy of warning categories
        warningHierarchy.put("all", new String[]{"closure", "deprecated", "exiting", "experimental", "glob", "imprecision", "io", "locale", "misc", "missing", "numeric", "once", "overflow", "pack", "portable", "recursion", "redefine", "redundant", "regexp", "scalar", "severe", "shadow", "signal", "substr", "syntax", "taint", "threads", "uninitialized", "unpack", "untie", "utf8", "void"});
        warningHierarchy.put("deprecated", new String[]{"deprecated::apostrophe_as_package_separator", "deprecated::delimiter_will_be_paired", "deprecated::dot_in_inc", "deprecated::goto_construct", "deprecated::missing_import_called_with_args", "deprecated::smartmatch", "deprecated::subsequent_use_version", "deprecated::unicode_property_name", "deprecated::version_downgrade"});
        warningHierarchy.put("experimental", new String[]{"experimental::args_array_with_signatures", "experimental::bitwise", "experimental::builtin", "experimental::class", "experimental::declared_refs", "experimental::defer", "experimental::extra_paired_delimiters", "experimental::private_use", "experimental::re_strict", "experimental::refaliasing", "experimental::regex_sets", "experimental::try", "experimental::uniprop_wildcards", "experimental::vlb", "experimental::keyword_any", "experimental::keyword_all", "experimental::lexical_subs", "experimental::signature_named_parameters"});
        warningHierarchy.put("io", new String[]{"io::closed", "io::exec", "io::layer", "io::newline", "io::pipe", "io::syscalls", "io::unopened"});
        warningHierarchy.put("severe", new String[]{"severe::debugging", "severe::inplace", "severe::internal", "severe::malloc"});
        warningHierarchy.put("syntax", new String[]{"syntax::ambiguous", "syntax::bareword", "syntax::digit", "syntax::illegalproto", "syntax::parenthesis", "syntax::precedence", "syntax::printf", "syntax::prototype", "syntax::qw", "syntax::reserved", "syntax::semicolon"});
        warningHierarchy.put("utf8", new String[]{"utf8::non_unicode", "utf8::nonchar", "utf8::surrogate"});
        warningHierarchy.put("layer", new String[]{"io::layer"});
        warningHierarchy.put("syscalls", new String[]{"io::syscalls"});
        warningHierarchy.put("pipe", new String[]{"io::pipe"});
        warningHierarchy.put("unopened", new String[]{"io::unopened"});
        warningHierarchy.put("FATAL", new String[]{});
        warningHierarchy.put("illegalproto", new String[]{});
        warningHierarchy.put("digit", new String[]{});
        warningHierarchy.put("closed", new String[]{"io::closed"});
        warningHierarchy.put("reserved", new String[]{});
        warningHierarchy.put("prototype", new String[]{});
        warningHierarchy.put("newline", new String[]{"io::newline"});
        warningHierarchy.put("NONFATAL", new String[]{});
        warningHierarchy.put("non_unicode", new String[]{});
        warningHierarchy.put("surrogate", new String[]{});
        warningHierarchy.put("nonchar", new String[]{});
    }

    /**
     * Constructs a WarningFlags object associated with a ScopedSymbolTable.
     */
    public WarningFlags() {
    }

    /**
     * Returns a list of all warning categories and subcategories.
     *
     * @return A list of all warning categories.
     */
    public static List<String> getWarningList() {
        Set<String> warningSet = new HashSet<>();
        for (Map.Entry<String, String[]> entry : warningHierarchy.entrySet()) {
            warningSet.add(entry.getKey());
            warningSet.addAll(Arrays.asList(entry.getValue()));
        }
        // Include custom categories registered via warnings::register
        warningSet.addAll(customCategories);
        return new ArrayList<>(warningSet);
    }

    /**
     * Registers a custom warning category (used by warnings::register).
     * If "all" warnings are already enabled in the current scope, also enables this category.
     *
     * @param category The name of the custom warning category to register.
     */
    public static void registerCategory(String category) {
        customCategories.add(category);
        // Add it to the hierarchy with no subcategories
        if (!warningHierarchy.containsKey(category)) {
            warningHierarchy.put(category, new String[]{});
        }
        // Register in the symbol table so it gets a bit position
        ScopedSymbolTable.registerCustomWarningCategory(category);
        
        // If "all" warnings are already enabled, enable this new category too
        ScopedSymbolTable symbolTable = getCurrentScope();
        if (symbolTable != null && symbolTable.isWarningCategoryEnabled("all")) {
            symbolTable.enableWarningCategory(category);
        }
    }

    /**
     * Checks if a category is a registered custom warning category.
     *
     * @param category The name of the category to check.
     * @return True if it's a registered custom category.
     */
    public static boolean isCustomCategory(String category) {
        return customCategories.contains(category);
    }
    
    /**
     * Checks if warnings have been globally enabled via "use warnings".
     * This is used for runtime warning checks where lexical scope isn't available.
     *
     * @return True if "use warnings" has been called.
     */
    public static boolean isGlobalWarningsEnabled() {
        return globalWarningsEnabled;
    }
    
    // ==================== Scope-based Warning Suppression ====================
    // These methods support lexical "no warnings" that propagates through calls.
    // At compile time, registerScopeWarnings() is called to get a scope ID.
    // At runtime, the scope ID is set via "local $^WARNING_SCOPE".
    // warnif() checks isWarningDisabledInScope() using the current scope ID.
    
    /**
     * Registers disabled warning categories for a new scope.
     * Called at compile time when "no warnings 'category'" is encountered.
     * Also sets lastScopeId so StatementParser can emit the local assignment.
     *
     * @param categories The set of warning categories to disable in this scope.
     * @return The unique scope ID for this block.
     */
    public static int registerScopeWarnings(Set<String> categories) {
        int scopeId = scopeIdCounter.incrementAndGet();
        
        // Expand categories to include subcategories
        Set<String> expanded = new HashSet<>(categories);
        for (String category : categories) {
            expandCategory(category, expanded);
        }
        
        scopeDisabledWarnings.put(scopeId, expanded);
        
        // Set lastScopeId for StatementParser to read
        lastScopeId = scopeId;
        
        return scopeId;
    }
    
    /**
     * Registers disabled warning categories for a single category.
     *
     * @param category The warning category to disable.
     * @return The unique scope ID for this block.
     */
    public static int registerScopeWarnings(String category) {
        Set<String> categories = new HashSet<>();
        categories.add(category);
        return registerScopeWarnings(categories);
    }
    
    /**
     * Checks if a warning category is disabled in the given scope.
     *
     * @param scopeId The scope ID to check (from $^WARNING_SCOPE).
     * @param category The warning category to check.
     * @return True if the category is disabled in this scope.
     */
    public static boolean isWarningDisabledInScope(int scopeId, String category) {
        Set<String> disabled = scopeDisabledWarnings.get(scopeId);
        if (disabled != null) {
            return disabled.contains(category) || disabled.contains("all");
        }
        return false;
    }
    
    /**
     * Checks if a warning category is suppressed at runtime via ${^WARNING_SCOPE}.
     * This is a convenience method for checking runtime suppression without
     * needing to access GlobalVariable directly.
     *
     * @param category The warning category to check.
     * @return True if the category is suppressed in the current runtime scope.
     */
    public static boolean isWarningSuppressedAtRuntime(String category) {
        RuntimeScalar scopeVar = GlobalVariable.getGlobalVariable(GlobalContext.WARNING_SCOPE);
        int scopeId = scopeVar.getInt();
        return scopeId > 0 && isWarningDisabledInScope(scopeId, category);
    }
    
    /**
     * Expands a warning category to include all its subcategories.
     *
     * @param category The category to expand.
     * @param result   The set to add expanded categories to.
     */
    private static void expandCategory(String category, Set<String> result) {
        if (warningHierarchy.containsKey(category)) {
            for (String sub : warningHierarchy.get(category)) {
                result.add(sub);
                expandCategory(sub, result);
            }
        }
    }
    
    /**
     * Gets the scope ID from the last noWarnings() call.
     * Called by StatementParser after processing "no warnings".
     *
     * @return The last scope ID, or 0 if no scope was registered.
     */
    public static int getLastScopeId() {
        return lastScopeId;
    }
    
    /**
     * Clears the last scope ID (called after StatementParser reads it).
     */
    public static void clearLastScopeId() {
        lastScopeId = 0;
    }

    public void initializeEnabledWarnings() {
        // Set global flag for runtime checks
        globalWarningsEnabled = true;
        
        // Enable all warnings by enabling the "all" category
        enableWarning("all");
        
        // Enable deprecated warnings
        enableWarning("deprecated");
        enableWarning("deprecated::apostrophe_as_package_separator");
        enableWarning("deprecated::delimiter_will_be_paired");
        enableWarning("deprecated::dot_in_inc");
        enableWarning("deprecated::goto_construct");
        enableWarning("deprecated::smartmatch");
        enableWarning("deprecated::unicode_property_name");
        enableWarning("deprecated::version_downgrade");

        // Enable experimental warnings
        enableWarning("experimental::args_array_with_signatures");
        enableWarning("experimental::bitwise");
        enableWarning("experimental::builtin");
        enableWarning("experimental::class");
        enableWarning("experimental::declared_refs");
        enableWarning("experimental::defer");
        enableWarning("experimental::extra_paired_delimiters");
        enableWarning("experimental::private_use");
        enableWarning("experimental::re_strict");
        enableWarning("experimental::refaliasing");
        enableWarning("experimental::try");
        enableWarning("experimental::uniprop_wildcards");
        enableWarning("experimental::vlb");

        // Enable IO warnings
        enableWarning("io");

        // Enable other warnings
        enableWarning("glob");
        enableWarning("locale");
        enableWarning("substr");
        
        // Enable all custom categories that have been registered
        for (String customCategory : customCategories) {
            enableWarning(customCategory);
        }
    }

    /**
     * Enables a warning category and its subcategories.
     *
     * @param category The name of the warning category to enable.
     */
    public void enableWarning(String category) {
        setWarningState(category, true);
    }

    /**
     * Disables a warning category and its subcategories.
     *
     * @param category The name of the warning category to disable.
     */
    public void disableWarning(String category) {
        setWarningState(category, false);
    }

    /**
     * Sets the state of a warning category and its subcategories.
     *
     * @param category The name of the warning category.
     * @param state    The state to set (true for enabled, false for disabled).
     */
    public void setWarningState(String category, boolean state) {
        ScopedSymbolTable symbolTable = getCurrentScope();
        if (state) {
            symbolTable.enableWarningCategory(category);
        } else {
            symbolTable.disableWarningCategory(category);
        }
        // Propagate the state to subcategories if necessary
        if (warningHierarchy.containsKey(category)) {
            for (String subcategory : warningHierarchy.get(category)) {
                setWarningState(subcategory, state);
            }
        }
    }

    /**
     * Checks if a warning category is enabled.
     * First checks the lexical scope, then falls back to global warnings flag.
     *
     * @param category The name of the warning category to check.
     * @return True if the category is enabled, false otherwise.
     */
    public boolean isWarningEnabled(String category) {
        ScopedSymbolTable scope = getCurrentScope();
        if (scope != null && scope.isWarningCategoryEnabled(category)) {
            return true;
        }
        // Fall back to global flag for runtime checks
        // If warnings are globally enabled and this isn't a disabled category, return true
        if (globalWarningsEnabled) {
            // Check if this specific category was explicitly disabled
            if (scope != null && scope.isWarningCategoryDisabled(category)) {
                return false;
            }
            // Built-in categories or custom categories are enabled when global warnings are on
            return true;
        }
        return false;
    }

    /**
     * Checks if a warning category was explicitly disabled via 'no warnings'.
     * This is used to determine if $^W should be overridden.
     *
     * @param category The name of the warning category to check.
     * @return True if the category was explicitly disabled, false otherwise.
     */
    public boolean isWarningDisabled(String category) {
        return getCurrentScope().isWarningCategoryDisabled(category);
    }
}
