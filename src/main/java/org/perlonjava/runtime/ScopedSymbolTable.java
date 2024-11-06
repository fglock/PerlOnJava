package org.perlonjava.runtime;

import java.util.*;

/**
 * A scoped symbol table that supports nested scopes for lexical variables, package declarations, warnings, features, and strict options.
 * This class manages the state of variables, warnings, features, and strict options across different scopes, allowing for nested and isolated environments.
 */
public class ScopedSymbolTable {
    // A stack to manage nested scopes of symbol tables.
    private final Stack<SymbolTable> stack = new Stack<>();
    private final Stack<String> packageStack = new Stack<>();
    // Stack to manage warning categories for each scope
    private final Stack<Integer> warningFlagsStack = new Stack<>();
    // Stack to manage feature categories for each scope
    private final Stack<Integer> featureFlagsStack = new Stack<>();
    // Stack to manage strict options for each scope
    private final Stack<Integer> strictOptionsStack = new Stack<>();
    // Cache for the getAllVisibleVariables method
    private Map<Integer, String> visibleVariablesCache;

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
    }

    /**
     * Enters a new scope by pushing a new SymbolTable onto the stack.
     * Copies the current state of warnings, features, and strict options to the new scope.
     */
    public void enterScope() {
        clearVisibleVariablesCache();

        int lastIndex = 0;
        String packageName = "main";
        // If there are existing scopes, get the last index from the current scope
        if (!stack.isEmpty()) {
            lastIndex = stack.peek().index;
            packageName = packageStack.peek();
        }
        // Push a new SymbolTable onto the stack and set its index
        stack.push(new SymbolTable());
        stack.peek().index = lastIndex;
        packageStack.push(packageName);

        // Push a copy of the current warning categories map onto the stack
        warningFlagsStack.push(warningFlagsStack.peek());
        // Push a copy of the current feature categories map onto the stack
        featureFlagsStack.push(featureFlagsStack.peek());
        // Push a copy of the current strict options onto the stack
        strictOptionsStack.push(strictOptionsStack.peek());
    }

    /**
     * Exits the current scope by popping the top SymbolTable from the stack.
     * Also removes the top state of warnings, features, and strict options.
     */
    public void exitScope() {
        clearVisibleVariablesCache();
        stack.pop();
        packageStack.pop();
        warningFlagsStack.pop();
        featureFlagsStack.pop();
        strictOptionsStack.pop();
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
    public int addVariable(String name) {
        clearVisibleVariablesCache();
        return stack.peek().addVariable(name);
    }

    /**
     * Retrieves the index of a variable, searching from the innermost to the outermost scope.
     *
     * @param name The name of the variable to look up.
     * @return The index of the variable, or -1 if the variable is not found.
     */
    public int getVariableIndex(String name) {
        // Iterate from innermost scope to outermost
        for (int i = stack.size() - 1; i >= 0; i--) {
            int index = stack.get(i).getVariableIndex(name);
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
        return stack.peek().getVariableIndex(name);
    }

    /**
     * Retrieves all visible variables from the current scope to the outermost scope.
     * This method is used to track closure variables.
     * The returned TreeMap is sorted by variable index.
     *
     * @return A TreeMap of variable index to variable name for all visible variables.
     */
    public Map<Integer, String> getAllVisibleVariables() {
        // Check if the result is already cached
        if (visibleVariablesCache != null) {
            return visibleVariablesCache;
        }

        // TreeMap to store variable indices as keys and variable names as values.
        // TreeMap is used to keep the entries sorted by the keys (variable indices).
        Map<Integer, String> visibleVariables = new TreeMap<>();

        // HashSet to keep track of variable names that have already been added to visibleVariables.
        // This helps to avoid adding the same variable multiple times if it appears in multiple scopes.
        Set<String> seenVariables = new HashSet<>();

        // Iterate from innermost scope (top of the stack) to outermost scope (bottom of the stack).
        for (int i = stack.size() - 1; i >= 0; i--) {
            // Retrieve the symbol table for the current scope.
            Map<String, Integer> scope = stack.get(i).table;

            // Iterate through all variables in the current scope.
            for (Map.Entry<String, Integer> entry : scope.entrySet()) {
                // Check if the variable name has already been seen.
                if (!seenVariables.contains(entry.getKey())) {
                    // If not seen, add the variable's index and name to visibleVariables.
                    visibleVariables.put(entry.getValue(), entry.getKey());

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
        Map<Integer, String> visibleVariables = this.getAllVisibleVariables();
        String[] vars = new String[visibleVariables.size()];
        for (Integer index : visibleVariables.keySet()) {
            vars[index] = visibleVariables.get(index);
        }
        return vars;
    }

    /**
     * Gets the current package scope.
     *
     * @return The name of the current package, or "main" if no package is in scope.
     */
    public String getCurrentPackage() {
        return packageStack.isEmpty() ? "main" : packageStack.peek();
    }

    /**
     * Sets the current package scope.
     *
     * @param packageName The name of the package to set as the current scope.
     */
    public void setCurrentPackage(String packageName) {
        if (!packageStack.isEmpty()) {
            packageStack.pop();
        }
        packageStack.push(packageName);
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
        Map<Integer, String> visibleVariables = this.getAllVisibleVariables();
        for (Integer index : visibleVariables.keySet()) {
            st.addVariable(visibleVariables.get(index));
        }

        // Clone the current package
        st.setCurrentPackage(this.getCurrentPackage());

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

    /**
     * Allocates a new JVM local variable index in the current scope.
     * This method is used to create internal variables, such as those needed
     * for tracking the dynamic variable stack state.
     *
     * @return The index of the newly allocated local variable.
     * @throws IllegalStateException if there is no current scope available for allocation.
     */
    public int allocateLocalVariable() {
        // Ensure there's a current scope available for allocation
        if (stack.isEmpty()) {
            throw new IllegalStateException("No scope available for JVM local variable allocation.");
        }
        // Allocate a new index in the current scope by incrementing the index counter
        return stack.peek().index++;
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
        for (SymbolTable symbolTable : stack) {
            sb.append("    ").append(symbolTable.toString()).append(",\n");
        }
        sb.append("  ],\n");

        sb.append("  packageStack: [\n");
        for (String pkg : packageStack) {
            sb.append("    ").append(pkg).append(",\n");
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

    // Methods for managing warnings

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
        Integer bitPosition = featureBitPositions.get(feature);
        if (bitPosition != null) {
            featureFlagsStack.push(featureFlagsStack.pop() | (1 << bitPosition));
        }
    }

    public void disableFeatureCategory(String feature) {
        Integer bitPosition = featureBitPositions.get(feature);
        if (bitPosition != null) {
            featureFlagsStack.push(featureFlagsStack.pop() & ~(1 << bitPosition));
        }
    }

    public boolean isFeatureCategoryEnabled(String feature) {
        Integer bitPosition = featureBitPositions.get(feature);
        return bitPosition != null && (featureFlagsStack.peek() & (1 << bitPosition)) != 0;
    }

    /**
     * A simple symbol table that maps variable names to unique integer indices.
     */
    static class SymbolTable {
        // A map to store variable names and their corresponding indices
        public Map<String, Integer> table = new HashMap<>();

        // A counter to generate unique indices for variables
        public int index = 0;

        /**
         * Adds a variable to the symbol table if it does not already exist.
         *
         * @param name The name of the variable to add.
         * @return The index of the variable in the symbol table.
         */
        public int addVariable(String name) {
            // Check if the variable is not already in the table
            if (!table.containsKey(name)) {
                // Add the variable with a unique index
                table.put(name, index++);
            }
            // Return the index of the variable
            return table.get(name);
        }

        /**
         * Retrieves the index of a variable from the symbol table.
         *
         * @param name The name of the variable to look up.
         * @return The index of the variable, or -1 if the variable is not found.
         */
        public int getVariableIndex(String name) {
            // Return the index of the variable, or -1 if not found
            return table.getOrDefault(name, -1);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("SymbolTable {\n");

            sb.append("  table: {\n");
            for (Map.Entry<String, Integer> entry : table.entrySet()) {
                sb.append("    ").append(entry.getKey()).append(": ").append(entry.getValue()).append(",\n");
            }
            sb.append("  },\n");

            sb.append("  index: ").append(index).append("\n");
            sb.append("}");
            return sb.toString();
        }
    }
}
