package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.frontend.semantic.ScopedSymbolTable;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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
        warningHierarchy.put("exec", new String[]{"io::exec"});
        warningHierarchy.put("reserved", new String[]{});
        warningHierarchy.put("prototype", new String[]{});
        warningHierarchy.put("newline", new String[]{"io::newline"});
        warningHierarchy.put("NONFATAL", new String[]{});
        warningHierarchy.put("non_unicode", new String[]{});
        warningHierarchy.put("surrogate", new String[]{});
        warningHierarchy.put("nonchar", new String[]{});
    }
    
    // ==================== Perl 5 Compatible Bit Offsets ====================
    // These match the offsets from Perl 5's warnings.h for caller()[9] compatibility.
    // Each category uses 2 bits: bit 0 = enabled, bit 1 = fatal.
    
    /**
     * Perl 5 compatible category offsets (from warnings.h).
     * These are used for caller()[9] return value compatibility.
     */
    private static final Map<String, Integer> PERL5_OFFSETS;
    
    /**
     * User-defined category offsets (dynamically assigned starting at 128).
     */
    private static final ConcurrentHashMap<String, Integer> userCategoryOffsets = 
        new ConcurrentHashMap<>();
    
    /**
     * Next available offset for user-defined categories.
     */
    private static final AtomicInteger nextUserOffset = new AtomicInteger(128);
    
    /**
     * Size of warning bits string in bytes (Perl 5's WARNsize).
     */
    public static final int WARN_SIZE = 21;
    
    static {
        // Initialize Perl 5 compatible offsets
        Map<String, Integer> offsets = new HashMap<>();
        offsets.put("all", 0);
        offsets.put("closure", 1);
        offsets.put("deprecated", 2);
        offsets.put("exiting", 3);
        offsets.put("glob", 4);
        offsets.put("io", 5);
        offsets.put("closed", 6);
        offsets.put("exec", 7);
        offsets.put("layer", 8);
        offsets.put("newline", 9);
        offsets.put("pipe", 10);
        offsets.put("unopened", 11);
        offsets.put("misc", 12);
        offsets.put("numeric", 13);
        offsets.put("once", 14);
        offsets.put("overflow", 15);
        offsets.put("pack", 16);
        offsets.put("portable", 17);
        offsets.put("recursion", 18);
        offsets.put("redefine", 19);
        offsets.put("regexp", 20);
        offsets.put("severe", 21);
        offsets.put("debugging", 22);
        offsets.put("inplace", 23);
        offsets.put("internal", 24);
        offsets.put("malloc", 25);
        offsets.put("signal", 26);
        offsets.put("substr", 27);
        offsets.put("syntax", 28);
        offsets.put("ambiguous", 29);
        offsets.put("bareword", 30);
        offsets.put("digit", 31);
        offsets.put("parenthesis", 32);
        offsets.put("precedence", 33);
        offsets.put("printf", 34);
        offsets.put("prototype", 35);
        offsets.put("qw", 36);
        offsets.put("reserved", 37);
        offsets.put("semicolon", 38);
        offsets.put("taint", 39);
        offsets.put("threads", 40);
        offsets.put("uninitialized", 41);
        offsets.put("unpack", 42);
        offsets.put("untie", 43);
        offsets.put("utf8", 44);
        offsets.put("void", 45);
        offsets.put("imprecision", 46);
        offsets.put("illegalproto", 47);
        // Perl 5.011003+
        offsets.put("deprecated::unicode_property_name", 48);
        // Perl 5.013+
        offsets.put("non_unicode", 49);
        offsets.put("nonchar", 50);
        offsets.put("surrogate", 51);
        // Perl 5.017+
        offsets.put("experimental", 52);
        offsets.put("experimental::regex_sets", 53);
        // Perl 5.019+
        offsets.put("syscalls", 54);
        // Perl 5.021+
        offsets.put("experimental::re_strict", 55);
        offsets.put("experimental::refaliasing", 56);
        offsets.put("locale", 57);
        offsets.put("missing", 58);
        offsets.put("redundant", 59);
        // Perl 5.025+
        offsets.put("experimental::declared_refs", 60);
        offsets.put("deprecated::dot_in_inc", 61);
        // Perl 5.027+
        offsets.put("shadow", 62);
        // Perl 5.029+
        offsets.put("experimental::private_use", 63);
        offsets.put("experimental::uniprop_wildcards", 64);
        offsets.put("experimental::vlb", 65);
        // Perl 5.033+
        offsets.put("experimental::try", 66);
        // Perl 5.035+
        offsets.put("experimental::args_array_with_signatures", 67);
        offsets.put("experimental::builtin", 68);
        offsets.put("experimental::defer", 69);
        offsets.put("experimental::extra_paired_delimiters", 70);
        offsets.put("scalar", 71);
        offsets.put("deprecated::version_downgrade", 72);
        offsets.put("deprecated::delimiter_will_be_paired", 73);
        // Perl 5.037+
        offsets.put("experimental::class", 74);
        // Additional categories
        offsets.put("deprecated::subsequent_use_version", 75);
        offsets.put("experimental::keyword_all", 76);
        offsets.put("experimental::keyword_any", 77);
        offsets.put("experimental::signature_named_parameters", 78);
        
        PERL5_OFFSETS = Collections.unmodifiableMap(offsets);
    }
    
    // ==================== Warning Bits String Methods ====================
    
    /**
     * Gets the Perl 5 compatible bit offset for a category.
     * Returns -1 if the category is not known.
     *
     * @param category The warning category name
     * @return The bit offset, or -1 if unknown
     */
    public static int getPerl5Offset(String category) {
        Integer offset = PERL5_OFFSETS.get(category);
        if (offset != null) {
            return offset;
        }
        // Check user-defined categories
        offset = userCategoryOffsets.get(category);
        return offset != null ? offset : -1;
    }
    
    /**
     * Registers a user-defined category and returns its bit offset.
     * If already registered, returns the existing offset.
     *
     * @param category The category name to register
     * @return The assigned bit offset
     */
    public static int registerUserCategoryOffset(String category) {
        // Check if already in built-in offsets
        Integer existing = PERL5_OFFSETS.get(category);
        if (existing != null) {
            return existing;
        }
        // Check or assign user category offset
        return userCategoryOffsets.computeIfAbsent(category, 
            k -> nextUserOffset.getAndIncrement());
    }
    
    /**
     * Converts BitSets to a Perl 5 compatible warning bits string.
     * Each category uses 2 bits: bit 0 = enabled, bit 1 = fatal.
     *
     * @param enabled BitSet of enabled warning categories (by internal bit position)
     * @param fatal BitSet of fatal warning categories (by internal bit position), may be null
     * @param categoryToInternalBit Map from category name to internal bit position
     * @return The Perl 5 compatible warning bits string
     */
    public static String toWarningBitsString(BitSet enabled, BitSet fatal,
                                              Map<String, Integer> categoryToInternalBit) {
        // Calculate required size
        int maxOffset = WARN_SIZE * 4; // Default Perl 5 size in categories
        for (String category : userCategoryOffsets.keySet()) {
            int offset = userCategoryOffsets.get(category);
            if (offset >= maxOffset) {
                maxOffset = offset + 1;
            }
        }
        
        // Calculate bytes needed (2 bits per category)
        int numBytes = Math.max(WARN_SIZE, (maxOffset * 2 + 7) / 8);
        byte[] bytes = new byte[numBytes];
        
        if (enabled != null && categoryToInternalBit != null) {
            for (Map.Entry<String, Integer> entry : categoryToInternalBit.entrySet()) {
                String category = entry.getKey();
                int internalBit = entry.getValue();
                
                if (internalBit >= 0 && enabled.get(internalBit)) {
                    int perl5Offset = getPerl5Offset(category);
                    if (perl5Offset >= 0) {
                        // Set enabled bit (offset * 2)
                        int bitPos = perl5Offset * 2;
                        int byteIndex = bitPos / 8;
                        int bitInByte = bitPos % 8;
                        if (byteIndex < numBytes) {
                            bytes[byteIndex] |= (1 << bitInByte);
                        }
                    }
                }
            }
        }
        
        if (fatal != null && categoryToInternalBit != null) {
            for (Map.Entry<String, Integer> entry : categoryToInternalBit.entrySet()) {
                String category = entry.getKey();
                int internalBit = entry.getValue();
                
                if (internalBit >= 0 && fatal.get(internalBit)) {
                    int perl5Offset = getPerl5Offset(category);
                    if (perl5Offset >= 0) {
                        // Set fatal bit (offset * 2 + 1)
                        int bitPos = perl5Offset * 2 + 1;
                        int byteIndex = bitPos / 8;
                        int bitInByte = bitPos % 8;
                        if (byteIndex < numBytes) {
                            bytes[byteIndex] |= (1 << bitInByte);
                        }
                    }
                }
            }
        }
        
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }
    
    /**
     * Checks if a category is enabled in a warning bits string.
     *
     * @param bits The warning bits string (from caller()[9])
     * @param category The category to check
     * @return true if the category is enabled
     */
    public static boolean isEnabledInBits(String bits, String category) {
        if (bits == null || category == null) {
            return false;
        }
        
        int offset = getPerl5Offset(category);
        if (offset < 0) {
            // Unknown category - check if it might be a registered user category
            offset = userCategoryOffsets.get(category) != null ? 
                     userCategoryOffsets.get(category) : -1;
            if (offset < 0) {
                return false;
            }
        }
        
        int bitPos = offset * 2; // Enabled bit
        int byteIndex = bitPos / 8;
        int bitInByte = bitPos % 8;
        
        if (byteIndex >= bits.length()) {
            return false;
        }
        
        return (bits.charAt(byteIndex) & (1 << bitInByte)) != 0;
    }
    
    /**
     * Checks if a category is fatal in a warning bits string.
     *
     * @param bits The warning bits string (from caller()[9])
     * @param category The category to check
     * @return true if the category is fatal
     */
    public static boolean isFatalInBits(String bits, String category) {
        if (bits == null || category == null) {
            return false;
        }
        
        int offset = getPerl5Offset(category);
        if (offset < 0) {
            offset = userCategoryOffsets.get(category) != null ? 
                     userCategoryOffsets.get(category) : -1;
            if (offset < 0) {
                return false;
            }
        }
        
        int bitPos = offset * 2 + 1; // Fatal bit
        int byteIndex = bitPos / 8;
        int bitInByte = bitPos % 8;
        
        if (byteIndex >= bits.length()) {
            return false;
        }
        
        return (bits.charAt(byteIndex) & (1 << bitInByte)) != 0;
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
     * Gets the subcategories of a warning category.
     *
     * @param category The parent category
     * @return Array of subcategory names, or null if none
     */
    public static String[] getSubcategories(String category) {
        return warningHierarchy.get(category);
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
