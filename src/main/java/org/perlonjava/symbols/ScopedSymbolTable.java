package org.perlonjava.symbols;

import org.perlonjava.astnode.OperatorNode;
import org.perlonjava.perlmodule.Strict;
import org.perlonjava.runtime.FeatureFlags;
import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.WarningFlags;

import java.util.*;

import static org.perlonjava.Configuration.getPerlVersionNoV;

/**
 * A scoped symbol table that supports nested scopes for lexical variables, package declarations, warnings, features, and strict options.
 * This class manages the state of variables, warnings, features, and strict options across different scopes, allowing for nested and isolated environments.
 */
public class ScopedSymbolTable {
    // Mapping of warning and feature names to bit positions
    private static final Map<String, Integer> warningBitPositions = new HashMap<>();
    private static final Map<String, Integer> featureBitPositions = new HashMap<>();

    static {
        // Initialize warning bit positions
        int bitPosition = 0;
        for (String warning : WarningFlags.getWarningList()) {
            warningBitPositions.put(warning, bitPosition++);
        }

        // Initialize feature bit positions
        bitPosition = 0;
        for (String feature : FeatureFlags.getFeatureList()) {
            featureBitPositions.put(feature, bitPosition++);
        }
    }

    // Stack to manage warning categories for each scope
    public final Stack<Integer> warningFlagsStack = new Stack<>();
    // Stack to manage feature categories for each scope
    public final Stack<Integer> featureFlagsStack = new Stack<>();
    // Stack to manage strict options for each scope
    public final Stack<Integer> strictOptionsStack = new Stack<>();
    // A stack to manage nested scopes of symbol tables.
    private final Stack<SymbolTable> symbolTableStack = new Stack<>();
    private final Stack<PackageInfo> packageStack = new Stack<>();
    // Stack to manage nested subroutine names for error messages
    private final Stack<String> subroutineStack = new Stack<>();
    // Cache for the getAllVisibleVariables method
    private Map<Integer, SymbolTable.SymbolEntry> visibleVariablesCache;

    /**
     * Constructs a ScopedSymbolTable.
     * Initializes the warning, feature categories, and strict options stacks with default values for the global scope.
     */
    public ScopedSymbolTable() {
        // Initialize the warning categories stack with an empty map for the global scope
        warningFlagsStack.push(0);
        // Initialize the feature categories stack with an empty map for the global scope
        featureFlagsStack.push(0);
        // Initialize the strict options stack with 0 for the global scope
        strictOptionsStack.push(0);
        // Initialize the package name
        packageStack.push(new PackageInfo("main", false));
        // Initialize the subroutine stack with empty string (no subroutine)
        subroutineStack.push("");
        // Initialize an empty symbol table
        symbolTableStack.push(new SymbolTable(0));
    }

    public static String stringifyFeatureFlags(int featureFlags) {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, Integer> entry : featureBitPositions.entrySet()) {
            String featureName = entry.getKey();
            int bitPosition = entry.getValue();
            if ((featureFlags & (1 << bitPosition)) != 0) {
                if (!result.isEmpty()) {
                    result.append(", ");
                }
                result.append(featureName);
            }
        }
        return result.toString();
    }

    public static String stringifyWarningFlags(int warningFlags) {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, Integer> entry : warningBitPositions.entrySet()) {
            String warningName = entry.getKey();
            int bitPosition = entry.getValue();
            if ((warningFlags & (1 << bitPosition)) != 0) {
                if (!result.isEmpty()) {
                    result.append(", ");
                }
                result.append(warningName);
            }
        }
        return result.toString();
    }

    public static String stringifyStrictOptions(int strictOptions) {
        StringBuilder result = new StringBuilder();
        if ((strictOptions & Strict.STRICT_REFS) != 0) {
            result.append("STRICT_REFS");
        }
        if ((strictOptions & Strict.STRICT_SUBS) != 0) {
            if (!result.isEmpty()) {
                result.append(", ");
            }
            result.append("STRICT_SUBS");
        }
        if ((strictOptions & Strict.STRICT_VARS) != 0) {
            if (!result.isEmpty()) {
                result.append(", ");
            }
            result.append("STRICT_VARS");
        }
        return result.toString();
    }

    /**
     * Enters a new scope by pushing a new SymbolTable onto the stack.
     * Copies the current state of warnings, features, and strict options to the new scope.
     *
     * @return The index representing the starting point of the new scope.
     */
    public int enterScope() {
        clearVisibleVariablesCache();

        // Push a new SymbolTable onto the stack and set its index
        symbolTableStack.push(new SymbolTable(symbolTableStack.peek().index));
        // Push a copy of the current package name onto the stack
        packageStack.push(packageStack.peek());
        // Push a copy of the current subroutine name onto the stack
        subroutineStack.push(subroutineStack.peek());
        // Push a copy of the current warning categories map onto the stack
        warningFlagsStack.push(warningFlagsStack.peek());
        // Push a copy of the current feature categories map onto the stack
        featureFlagsStack.push(featureFlagsStack.peek());
        // Push a copy of the current strict options onto the stack
        strictOptionsStack.push(strictOptionsStack.peek());

        // Return the current size of the symbol table stack as the scope index
        return symbolTableStack.size() - 1;
    }

    /**
     * Exits the current scope by popping the top SymbolTable from the stack.
     * Also removes the top state of warnings, features, and strict options.
     *
     * @param scopeIndex The index representing the starting point of the scope to exit.
     */
    public void exitScope(int scopeIndex) {
        clearVisibleVariablesCache();
        // Pop entries from the stacks until reaching the specified scope index
        while (symbolTableStack.size() > scopeIndex) {
            symbolTableStack.pop();
            packageStack.pop();
            subroutineStack.pop();
            warningFlagsStack.pop();
            featureFlagsStack.pop();
            strictOptionsStack.pop();
        }
    }

    /**
     * Enables a strict option in the current scope.
     *
     * @param option The bitmask of the strict option to enable.
     */
    public void enableStrictOption(int option) {
        strictOptionsStack.push(strictOptionsStack.pop() | option);
    }

    /**
     * Disables a strict option in the current scope.
     *
     * @param option The bitmask of the strict option to disable.
     */
    public void disableStrictOption(int option) {
        strictOptionsStack.push(strictOptionsStack.pop() & ~option);
    }

    /**
     * Checks if a strict option is enabled in the current scope.
     *
     * @param option The bitmask of the strict option to check.
     * @return True if the option is enabled, false otherwise.
     */
    public boolean isStrictOptionEnabled(int option) {
        return (strictOptionsStack.peek() & option) != 0;
    }

    /**
     * Adds a variable to the current scope.
     *
     * @param name The name of the variable to add.
     * @return The index of the variable in the current scope.
     */
    public int addVariable(String name, String variableDeclType, OperatorNode ast) {
        clearVisibleVariablesCache();
        return symbolTableStack.peek().addVariable(name, variableDeclType, getCurrentPackage(), ast);
    }

    /**
     * Retrieves the index of a variable, searching from the innermost to the outermost scope.
     *
     * @param name The name of the variable to look up.
     * @return The index of the variable, or -1 if the variable is not found.
     */
    public int getVariableIndex(String name) {
        // Iterate from innermost scope to outermost
        for (int i = symbolTableStack.size() - 1; i >= 0; i--) {
            int index = symbolTableStack.get(i).getVariableIndex(name);
            if (index != -1) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Clears the cache for the getAllVisibleVariables method.
     * This method should be called whenever the symbol table is modified.
     */
    public void clearVisibleVariablesCache() {
        visibleVariablesCache = null;
    }

    /**
     * Retrieves the index of a variable in the current scope.
     * This method is used to track variable redeclarations in the same scope.
     *
     * @param name The name of the variable to look up.
     * @return The index of the variable, or -1 if the variable is not found in the current scope.
     */
    public int getVariableIndexInCurrentScope(String name) {
        return symbolTableStack.peek().getVariableIndex(name);
    }

    /**
     * Retrieves all visible variables from the current scope to the outermost scope.
     * This method is used to track closure variables.
     * The returned TreeMap is sorted by variable index.
     *
     * @return A TreeMap of variable index to variable name for all visible variables.
     */
    public Map<Integer, SymbolTable.SymbolEntry> getAllVisibleVariables() {
        // Check if the result is already cached
        if (visibleVariablesCache != null) {
            return visibleVariablesCache;
        }

        // TreeMap to store variable indices as keys and variable names as values.
        // TreeMap is used to keep the entries sorted by the keys (variable indices).
        Map<Integer, SymbolTable.SymbolEntry> visibleVariables = new TreeMap<>();

        // HashSet to keep track of variable names that have already been added to visibleVariables.
        // This helps to avoid adding the same variable multiple times if it appears in multiple scopes.
        Set<String> seenVariables = new HashSet<>();

        // Iterate from innermost scope (top of the stack) to outermost scope (bottom of the stack).
        for (int i = symbolTableStack.size() - 1; i >= 0; i--) {
            // Retrieve the symbol table for the current scope.
            Map<String, SymbolTable.SymbolEntry> scope = symbolTableStack.get(i).variableIndex;

            // Iterate through all variables in the current scope.
            for (Map.Entry<String, SymbolTable.SymbolEntry> entry : scope.entrySet()) {
                // Check if the variable name has already been seen.
                if (!seenVariables.contains(entry.getKey())) {
                    // If not seen, add the variable's index and name to visibleVariables.
                    visibleVariables.put(entry.getValue().index(), entry.getValue());

                    // Mark the variable name as seen by adding it to seenVariables.
                    seenVariables.add(entry.getKey());
                }
            }
        }

        // Cache the result
        visibleVariablesCache = visibleVariables;

        // Return the TreeMap containing all visible variables sorted by their indices.
        return visibleVariables;
    }

    // XXX TODO cache this
    public String[] getVariableNames() {
        Map<Integer, SymbolTable.SymbolEntry> visibleVariables = this.getAllVisibleVariables();
        String[] vars = new String[visibleVariables.size()];
        for (Integer index : visibleVariables.keySet()) {
            vars[index] = visibleVariables.get(index).name();
        }
        return vars;
    }

    /**
     * Gets the current package scope.
     *
     * @return The name of the current package, or "main" if no package is in scope.
     */
    public String getCurrentPackage() {
        return packageStack.peek().packageName;
    }

    public boolean currentPackageIsClass() {
        return packageStack.peek().isClass;
    }

    /**
     * Sets the current package scope.
     *
     * @param packageName The name of the package to set as the current scope.
     */
    public void setCurrentPackage(String packageName, boolean isClass) {
        packageStack.pop();
        packageStack.push(new PackageInfo(packageName, isClass));
    }

    /**
     * Gets the current subroutine name.
     *
     * @return The name of the current subroutine, or empty string if not in a subroutine.
     */
    public String getCurrentSubroutine() {
        return subroutineStack.peek();
    }

    /**
     * Sets the current subroutine name.
     *
     * @param subroutineName The name of the subroutine to set as the current scope.
     */
    public void setCurrentSubroutine(String subroutineName) {
        subroutineStack.pop();
        subroutineStack.push(subroutineName != null ? subroutineName : "");
    }

    /**
     * Clones the symbol table to be used at runtime - this is used by eval-string.
     *
     * @return A cloned instance of ScopedSymbolTable.
     */
    public ScopedSymbolTable snapShot() {
        ScopedSymbolTable st = new ScopedSymbolTable();
        st.enterScope();

        // Clone visible variables
        Map<Integer, SymbolTable.SymbolEntry> visibleVariables = this.getAllVisibleVariables();
        for (Integer index : visibleVariables.keySet()) {
            SymbolTable.SymbolEntry entry = visibleVariables.get(index);
            st.addVariable(entry.name(), entry.decl(), entry.ast());
        }

        // Clone the current package
        st.setCurrentPackage(this.getCurrentPackage(), this.currentPackageIsClass());

        // Clone the current subroutine
        st.setCurrentSubroutine(this.getCurrentSubroutine());

        // Clone warning flags
        st.warningFlagsStack.pop(); // Remove the initial value pushed by enterScope
        st.warningFlagsStack.push(this.warningFlagsStack.peek());

        // Clone feature flags
        st.featureFlagsStack.pop(); // Remove the initial value pushed by enterScope
        st.featureFlagsStack.push(this.featureFlagsStack.peek());

        // Clone strict options
        st.strictOptionsStack.pop(); // Remove the initial value pushed by enterScope
        st.strictOptionsStack.push(this.strictOptionsStack.peek());

        return st;
    }

    // Methods for managing warnings

    /**
     * Allocates a new JVM local variable index in the current scope.
     * This method is used to create internal variables, such as those needed
     * for tracking the dynamic variable stack state.
     *
     * @return The index of the newly allocated local variable.
     * @throws IllegalStateException if there is no current scope available for allocation.
     */
    public int allocateLocalVariable() {
        // Allocate a new index in the current scope by incrementing the index counter
        return symbolTableStack.peek().index++;
    }

    /**
     * toString() method for debugging.
     *
     * @return A string representation of the ScopedSymbolTable.
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("ScopedSymbolTable {\n");

        sb.append("  stack: [\n");
        for (SymbolTable symbolTable : symbolTableStack) {
            sb.append("    ").append(symbolTable.toString()).append(",\n");
        }
        sb.append("  ],\n");

        sb.append("  packageStack: [\n");
        for (PackageInfo pkg : packageStack) {
            sb.append("    ").append(pkg.packageName).append(" ").append(pkg.isClass).append(",\n");
        }
        sb.append("  ],\n");

        sb.append("  subroutineStack: [\n");
        for (String sub : subroutineStack) {
            sb.append("    \"").append(sub).append("\",\n");
        }
        sb.append("  ],\n");

        sb.append("  warningCategories: {\n");
        int warningFlags = warningFlagsStack.peek();
        for (Map.Entry<String, Integer> entry : warningBitPositions.entrySet()) {
            String warningName = entry.getKey();
            int bitPosition = entry.getValue();
            boolean isEnabled = (warningFlags & (1 << bitPosition)) != 0;
            sb.append("    ").append(warningName).append(": ").append(isEnabled).append(",\n");
        }
        sb.append("  },\n");

        sb.append("  featureCategories: {\n");
        int featureFlags = featureFlagsStack.peek();
        for (Map.Entry<String, Integer> entry : featureBitPositions.entrySet()) {
            String featureName = entry.getKey();
            int bitPosition = entry.getValue();
            boolean isEnabled = (featureFlags & (1 << bitPosition)) != 0;
            sb.append("    ").append(featureName).append(": ").append(isEnabled).append(",\n");
        }
        sb.append("  }\n");

        sb.append("}");
        return sb.toString();
    }

    // Methods for managing warnings using bit positions
    public void enableWarningCategory(String category) {
        Integer bitPosition = warningBitPositions.get(category);
        if (bitPosition != null) {
            warningFlagsStack.push(warningFlagsStack.pop() | (1 << bitPosition));
        }
    }

    public void disableWarningCategory(String category) {
        Integer bitPosition = warningBitPositions.get(category);
        if (bitPosition != null) {
            warningFlagsStack.push(warningFlagsStack.pop() & ~(1 << bitPosition));
        }
    }

    public boolean isWarningCategoryEnabled(String category) {
        Integer bitPosition = warningBitPositions.get(category);
        return bitPosition != null && (warningFlagsStack.peek() & (1 << bitPosition)) != 0;
    }

    // Methods for managing features using bit positions
    public void enableFeatureCategory(String feature) {
        if (isNoOpFeature(feature)) {
            return;
        }

        Integer bitPosition = featureBitPositions.get(feature);
        if (bitPosition == null) {
            throw new PerlCompilerException("Feature \"" + feature + "\" is not supported by Perl " + getPerlVersionNoV());
        } else {
            featureFlagsStack.push(featureFlagsStack.pop() | (1 << bitPosition));
        }
    }

    public void disableFeatureCategory(String feature) {
        if (isNoOpFeature(feature)) {
            return;
        }

        Integer bitPosition = featureBitPositions.get(feature);
        if (bitPosition == null) {
            throw new PerlCompilerException("Feature \"" + feature + "\" is not supported by Perl " + getPerlVersionNoV());
        } else {
            featureFlagsStack.push(featureFlagsStack.pop() & ~(1 << bitPosition));
        }
    }

    public boolean isFeatureCategoryEnabled(String feature) {
        if (isNoOpFeature(feature)) {
            return true;
        }

        Integer bitPosition = featureBitPositions.get(feature);
        if (bitPosition == null) {
            throw new PerlCompilerException("Feature \"" + feature + "\" is not supported by Perl " + getPerlVersionNoV());
        } else {
            return (featureFlagsStack.peek() & (1 << bitPosition)) != 0;
        }
    }

    private boolean isNoOpFeature(String feature) {
        // no-op features
        return feature.equals("postderef") || feature.equals("lexical_subs");
    }

    /**
     * Copies the flags (warnings, features, and strict options) from another ScopedSymbolTable.
     *
     * @param source The source ScopedSymbolTable from which to copy the flags.
     */
    public void copyFlagsFrom(ScopedSymbolTable source) {
        if (source == null) {
            throw new IllegalArgumentException("Source ScopedSymbolTable cannot be null.");
        }

        // Copy warning flags
        this.warningFlagsStack.pop();
        this.warningFlagsStack.push(source.warningFlagsStack.peek());

        // Copy feature flags
        this.featureFlagsStack.pop();
        this.featureFlagsStack.push(source.featureFlagsStack.peek());

        // Copy strict options
        this.strictOptionsStack.pop();
        this.strictOptionsStack.push(source.strictOptionsStack.peek());
    }

    public record PackageInfo(String packageName, boolean isClass) {
    }
}
