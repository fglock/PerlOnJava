package org.perlonjava.runtime;

import org.perlonjava.symbols.ScopedSymbolTable;

import java.util.*;

import static org.perlonjava.parser.SpecialBlockParser.getCurrentScope;

/**
 * A class to control lexical warnings flags based on a hierarchy of categories.
 */
public class WarningFlags {
    // A hierarchy of warning categories
    private static final Map<String, String[]> warningHierarchy = new HashMap<>();

    static {
        // Initialize the hierarchy of warning categories
        warningHierarchy.put("all", new String[]{"closure", "deprecated", "exiting", "experimental", "glob", "imprecision", "io", "locale", "misc", "missing", "numeric", "once", "overflow", "pack", "portable", "recursion", "redefine", "redundant", "regexp", "scalar", "severe", "shadow", "signal", "substr", "syntax", "taint", "threads", "uninitialized", "unpack", "untie", "utf8", "void"});
        warningHierarchy.put("deprecated", new String[]{"deprecated::apostrophe_as_package_separator", "deprecated::delimiter_will_be_paired", "deprecated::dot_in_inc", "deprecated::goto_construct", "deprecated::missing_import_called_with_args", "deprecated::smartmatch", "deprecated::subsequent_use_version", "deprecated::unicode_property_name", "deprecated::version_downgrade"});
        warningHierarchy.put("experimental", new String[]{"experimental::args_array_with_signatures", "experimental::builtin", "experimental::class", "experimental::declared_refs", "experimental::defer", "experimental::extra_paired_delimiters", "experimental::private_use", "experimental::re_strict", "experimental::refaliasing", "experimental::regex_sets", "experimental::try", "experimental::uniprop_wildcards", "experimental::vlb", "experimental::keyword_any", "experimental::keyword_all"});
        warningHierarchy.put("io", new String[]{"io::closed", "io::exec", "io::layer", "io::newline", "io::pipe", "io::syscalls", "io::unopened"});
        warningHierarchy.put("severe", new String[]{"severe::debugging", "severe::inplace", "severe::internal", "severe::malloc"});
        warningHierarchy.put("syntax", new String[]{"syntax::ambiguous", "syntax::bareword", "syntax::digit", "syntax::illegalproto", "syntax::parenthesis", "syntax::precedence", "syntax::printf", "syntax::prototype", "syntax::qw", "syntax::reserved", "syntax::semicolon"});
        warningHierarchy.put("utf8", new String[]{"utf8::non_unicode", "utf8::nonchar", "utf8::surrogate"});
        warningHierarchy.put("layer", new String[]{});
        warningHierarchy.put("syscalls", new String[]{});
        warningHierarchy.put("pipe", new String[]{});
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
        return new ArrayList<>(warningSet);
    }

    public void initializeEnabledWarnings() {
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

        // Enable other warnings
        enableWarning("glob");
        enableWarning("locale");
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
     *
     * @param category The name of the warning category to check.
     * @return True if the category is enabled, false otherwise.
     */
    public boolean isWarningEnabled(String category) {
        return getCurrentScope().isWarningCategoryEnabled(category);
    }
}
