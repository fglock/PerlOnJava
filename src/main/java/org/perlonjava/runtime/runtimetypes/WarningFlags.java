package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.frontend.semantic.ScopedSymbolTable;

import java.util.*;

import static org.perlonjava.frontend.parser.SpecialBlockParser.getCurrentScope;

/**
 * A class to control lexical warnings flags based on a hierarchy of categories.
 * Warning state is managed at compile time through the symbol table (like strict).
 */
public class WarningFlags {
    // A hierarchy of warning categories
    private static final Map<String, String[]> warningHierarchy = new HashMap<>();
    
    // Custom warning categories registered via warnings::register
    private static final Set<String> customCategories = new HashSet<>();
    
    // Global flag to track if "use warnings" has been called (for runtime checks)
    private static boolean globalWarningsEnabled = false;

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
