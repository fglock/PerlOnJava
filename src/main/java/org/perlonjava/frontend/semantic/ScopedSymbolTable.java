package org.perlonjava.frontend.semantic;

import org.perlonjava.frontend.astnode.OperatorNode;
import org.perlonjava.runtime.runtimetypes.FeatureFlags;
import org.perlonjava.runtime.runtimetypes.PerlCompilerException;
import org.perlonjava.runtime.runtimetypes.WarningFlags;

import java.util.*;

import static org.perlonjava.core.Configuration.getPerlVersionNoV;

/**
 * A scoped symbol table that supports nested scopes for lexical variables, package declarations, warnings, features, and strict options.
 * This class manages the state of variables, warnings, features, and strict options across different scopes, allowing for nested and isolated environments.
 */
public class ScopedSymbolTable {
    // Mapping of warning and feature names to bit positions
    private static final Map<String, Integer> warningBitPositions = new HashMap<>();
    private static final Map<String, Integer> featureBitPositions = new HashMap<>();
    // Global package version storage (static so it persists across all symbol table instances)
    private static final Map<String, String> packageVersions = new HashMap<>();

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
    
    // Track the next available bit position for dynamic categories
    private static int nextWarningBitPosition = -1;
    
    /**
     * Registers a custom warning category (used by warnings::register).
     * This adds the category to the bit position map so it can be enabled/disabled.
     *
     * @param category The name of the custom warning category.
     */
    public static void registerCustomWarningCategory(String category) {
        if (!warningBitPositions.containsKey(category)) {
            if (nextWarningBitPosition < 0) {
                // Initialize to one past the last position
                nextWarningBitPosition = warningBitPositions.size();
            }
            warningBitPositions.put(category, nextWarningBitPosition++);
        }
    }

    // Stack to manage warning categories for each scope
    public final Stack<BitSet> warningFlagsStack = new Stack<>();
    // Stack to track explicitly disabled warning categories (for proper $^W interaction)
    public final Stack<BitSet> warningDisabledStack = new Stack<>();
    // Stack to track FATAL warning categories for each scope
    public final Stack<BitSet> warningFatalStack = new Stack<>();
    // Stack to manage feature categories for each scope
    public final Stack<Integer> featureFlagsStack = new Stack<>();
    // Stack to manage strict options for each scope
    public final Stack<Integer> strictOptionsStack = new Stack<>();
    // A stack to manage nested scopes of symbol tables.
    private final Stack<SymbolTable> symbolTableStack = new Stack<>();
    private final Stack<PackageInfo> packageStack = new Stack<>();
    // Stack to manage nested subroutine names for error messages
    private final Stack<String> subroutineStack = new Stack<>();
    // Stack to track whether we are inside a subroutine body (named or anonymous)
    private final Stack<Boolean> inSubroutineBodyStack = new Stack<>();
    // Cache for the getAllVisibleVariables method
    private Map<Integer, SymbolTable.SymbolEntry> visibleVariablesCache;

    /**
     * Constructs a ScopedSymbolTable.
     * Initializes the warning, feature categories, and strict options stacks with default values for the global scope.
     */
    public ScopedSymbolTable() {
        // Initialize the warning categories stack with empty warnings by default
        // This matches Perl behavior where code without explicit 'use warnings'
        // has no warning bits set. Experimental warnings will be enabled when
        // the relevant features are used (e.g., 'use feature "try"').
        BitSet defaultWarnings = new BitSet();
        warningFlagsStack.push(defaultWarnings);
        // Initialize the disabled warnings stack (empty by default)
        warningDisabledStack.push(new BitSet());
        // Initialize the fatal warnings stack (empty by default)
        warningFatalStack.push(new BitSet());
        // Initialize the feature categories stack with an empty map for the global scope
        featureFlagsStack.push(0);
        // Initialize the strict options stack with 0 for the global scope
        strictOptionsStack.push(0);
        // Initialize the package name
        packageStack.push(new PackageInfo("main", false, null));
        // Initialize the subroutine stack with empty string (no subroutine)
        subroutineStack.push("");
        // Initialize the subroutine-body flag stack (false at top level)
        inSubroutineBodyStack.push(false);
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

    public static String stringifyWarningFlags(BitSet warningFlags) {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, Integer> entry : warningBitPositions.entrySet()) {
            String warningName = entry.getKey();
            int bitPosition = entry.getValue();
            if (bitPosition >= 0 && warningFlags.get(bitPosition)) {
                if (!result.isEmpty()) {
                    result.append(", ");
                }
                result.append(warningName);
            }
        }
        return result.toString();
    }

    /**
     * Clears all package versions. Called during global initialization.
     */
    public static void clearPackageVersions() {
        packageVersions.clear();
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
        // Push a copy of the current subroutine-body flag onto the stack
        inSubroutineBodyStack.push(inSubroutineBodyStack.peek());
        // Push a copy of the current warning categories map onto the stack
        warningFlagsStack.push((BitSet) warningFlagsStack.peek().clone());
        // Push a copy of the current disabled warnings map onto the stack
        warningDisabledStack.push((BitSet) warningDisabledStack.peek().clone());
        // Push a copy of the current fatal warnings map onto the stack
        warningFatalStack.push((BitSet) warningFatalStack.peek().clone());
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
     * <p>
     * The child scope's local variable index is propagated to the parent scope
     * to prevent slot reuse across conditional branches. Without this, the JVM
     * verifier can fail with VerifyError when the same slot holds different types
     * (e.g., int vs reference, or RuntimeScalar vs RegexState) in different branches,
     * causing ASM's COMPUTE_FRAMES to merge them as Top or java/lang/Object.
     *
     * @param scopeIndex The index representing the starting point of the scope to exit.
     */
    public void exitScope(int scopeIndex) {
        clearVisibleVariablesCache();
        // Capture the child scope's max local variable index before popping
        int childIndex = symbolTableStack.peek().index;
        // Pop entries from the stacks until reaching the specified scope index
        while (symbolTableStack.size() > scopeIndex) {
            symbolTableStack.pop();
            packageStack.pop();
            subroutineStack.pop();
            inSubroutineBodyStack.pop();
            warningFlagsStack.pop();
            warningDisabledStack.pop();
            warningFatalStack.pop();
            featureFlagsStack.pop();
            strictOptionsStack.pop();
        }
        // Propagate the child scope's index to the parent to prevent slot reuse.
        // This ensures that local variable slots allocated inside conditional branches
        // (e.g., if/else blocks) are not reused in subsequent code, avoiding type
        // conflicts at JVM branch merge points.
        if (symbolTableStack.peek().index < childIndex) {
            symbolTableStack.peek().index = childIndex;
        }
    }

    /**
     * Returns the JVM local variable slot indices for all {@code my} variables
     * declared in the scopes being exited (from the current top of the symbol
     * table stack down to {@code scopeIndex}).
     * <p>
     * Used by the JVM bytecode emitter to null out local variable slots at
     * scope exit, enabling GC to collect objects (like anonymous filehandle
     * globs) that are no longer accessible from Perl code but would otherwise
     * be held alive by the JVM stack frame.
     *
     * @param scopeIndex The scope boundary (inclusive lower bound)
     * @return list of local variable slot indices to null
     */
    public java.util.List<Integer> getMyVariableIndicesInScope(int scopeIndex) {
        java.util.List<Integer> indices = new java.util.ArrayList<>();
        for (int i = symbolTableStack.size() - 1; i >= scopeIndex; i--) {
            for (SymbolTable.SymbolEntry entry : symbolTableStack.get(i).variableIndex.values()) {
                if ("my".equals(entry.decl())) {
                    indices.add(entry.index());
                }
            }
        }
        return indices;
    }

    /**
     * Returns the JVM local slot indices for hash ({@code %}) {@code my}
     * variables declared in or after the given scope. Used by scope-exit
     * cleanup to defer refCount decrements for blessed objects stored in hashes.
     */
    public java.util.List<Integer> getMyHashIndicesInScope(int scopeIndex) {
        java.util.List<Integer> indices = new java.util.ArrayList<>();
        for (int i = symbolTableStack.size() - 1; i >= scopeIndex; i--) {
            for (SymbolTable.SymbolEntry entry : symbolTableStack.get(i).variableIndex.values()) {
                if ("my".equals(entry.decl()) && entry.name() != null && entry.name().startsWith("%")) {
                    indices.add(entry.index());
                }
            }
        }
        return indices;
    }

    /**
     * Returns the JVM local slot indices for array ({@code @}) {@code my}
     * variables declared in or after the given scope. Used by scope-exit
     * cleanup to defer refCount decrements for blessed objects stored in arrays.
     */
    public java.util.List<Integer> getMyArrayIndicesInScope(int scopeIndex) {
        java.util.List<Integer> indices = new java.util.ArrayList<>();
        for (int i = symbolTableStack.size() - 1; i >= scopeIndex; i--) {
            for (SymbolTable.SymbolEntry entry : symbolTableStack.get(i).variableIndex.values()) {
                if ("my".equals(entry.decl()) && entry.name() != null && entry.name().startsWith("@")) {
                    indices.add(entry.index());
                }
            }
        }
        return indices;
    }

    /**
     * Returns the JVM local slot indices for scalar ({@code $}) {@code my}
     * variables declared in or after the given scope. Used by
     * {@link org.perlonjava.backend.jvm.EmitStatement#emitScopeExitNullStores}
     * to call {@link RuntimeScalar#scopeExitCleanup} for eager fd recycling.
     *
     * @param scopeIndex The scope boundary (inclusive lower bound)
     * @return list of scalar variable slot indices
     */
    public java.util.List<Integer> getMyScalarIndicesInScope(int scopeIndex) {
        java.util.List<Integer> indices = new java.util.ArrayList<>();
        for (int i = symbolTableStack.size() - 1; i >= scopeIndex; i--) {
            for (SymbolTable.SymbolEntry entry : symbolTableStack.get(i).variableIndex.values()) {
                if ("my".equals(entry.decl()) && entry.name() != null && entry.name().startsWith("$")) {
                    indices.add(entry.index());
                }
            }
        }
        return indices;
    }

    /**
     * Returns true if we are currently parsing inside a subroutine body (named or anonymous).
     */
    public boolean isInSubroutineBody() {
        return inSubroutineBodyStack.peek();
    }

    /**
     * Sets whether we are currently parsing inside a subroutine body (named or anonymous).
     */
    public void setInSubroutineBody(boolean inSubroutineBody) {
        inSubroutineBodyStack.pop();
        inSubroutineBodyStack.push(inSubroutineBody);
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
     * Sets the strict options directly (used by $^H assignment).
     *
     * @param options The new strict options bitmask.
     */
    public void setStrictOptions(int options) {
        strictOptionsStack.pop();
        strictOptionsStack.push(options);
    }

    /**
     * Propagates strict options to ALL levels of the stack.
     * Used by BEGIN block $^H propagation — compile-time hint changes
     * must persist across scope exits within the same compilation unit.
     *
     * @param options The new strict options bitmask.
     */
    public void propagateStrictOptionsToAllLevels(int options) {
        java.util.Deque<Integer> temp = new java.util.ArrayDeque<>();
        while (!strictOptionsStack.isEmpty()) {
            strictOptionsStack.pop();
            temp.push(options);
        }
        while (!temp.isEmpty()) {
            strictOptionsStack.push(temp.pop());
        }
    }

    /**
     * Gets the current strict options bitmask.
     *
     * @return The current strict options.
     */
    public int getStrictOptions() {
        return strictOptionsStack.peek();
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
     * Adds a variable to the current scope with an explicit package.
     * This is needed when copying 'our' variables to subroutine scopes,
     * where the original package must be preserved for correct global lookup.
     *
     * @param name The name of the variable to add.
     * @param variableDeclType The declaration type (my/our/state).
     * @param perlPackage The Perl package where the variable was declared.
     * @param ast The AST node for the declaration.
     * @return The index of the variable in the current scope.
     */
    public int addVariable(String name, String variableDeclType, String perlPackage, OperatorNode ast) {
        clearVisibleVariablesCache();
        return symbolTableStack.peek().addVariable(name, variableDeclType, perlPackage, ast);
    }

    public void addVariableWithIndex(String name, int index, String variableDeclType) {
        clearVisibleVariablesCache();
        symbolTableStack.peek().addVariableWithIndex(name, index, variableDeclType, getCurrentPackage());
    }

    /**
     * Overload that lets the caller specify an explicit Perl package for the
     * symbol entry. Used when seeding an eval STRING's symbol table with the
     * caller's `our` declarations: the declaring package must be preserved
     * (not overwritten with the current package) so that the lexical alias
     * remains correct even after `package Foo;` inside the eval body.
     */
    public void addVariableWithIndex(String name, int index, String variableDeclType, String perlPackage) {
        clearVisibleVariablesCache();
        symbolTableStack.peek().addVariableWithIndex(name, index, variableDeclType, perlPackage);
    }

    public Map<String, Integer> getVisibleVariableRegistry() {
        Map<String, Integer> registry = new HashMap<>();
        Map<Integer, SymbolTable.SymbolEntry> visible = getAllVisibleVariables();
        for (SymbolTable.SymbolEntry entry : visible.values()) {
            registry.put(entry.name(), entry.index());
        }
        return registry;
    }

    /**
     * Returns a map of visible `our` variable names to their declaring package.
     * This is used by eval STRING to inherit the caller's `our` aliases so that
     * even after a `package Foo;` directive inside the eval body, references to
     * `$bar` still resolve through the outer scope's `our $bar` alias to the
     * original package (matching Perl 5 lexical-scoping semantics).
     */
    public Map<String, String> getVisibleOurRegistry() {
        Map<String, String> registry = new HashMap<>();
        Map<Integer, SymbolTable.SymbolEntry> visible = getAllVisibleVariables();
        for (SymbolTable.SymbolEntry entry : visible.values()) {
            if ("our".equals(entry.decl()) && entry.perlPackage() != null) {
                registry.put(entry.name(), entry.perlPackage());
            }
        }
        return registry;
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

    public SymbolTable.SymbolEntry getSymbolEntry(String name) {
        // Iterate from innermost scope to outermost
        for (int i = symbolTableStack.size() - 1; i >= 0; i--) {
            SymbolTable.SymbolEntry decl = symbolTableStack.get(i).getSymbolEntry(name);
            if (decl != null) {
                return decl;
            }
        }
        return null;
    }

    /**
     * Replaces an existing symbol table entry in the current scope.
     * This is used for lexical subs where a redefinition creates a new pad entry that shadows the forward declaration.
     */
    public void replaceVariable(String name, String variableDeclType, OperatorNode ast) {
        if (!symbolTableStack.isEmpty()) {
            SymbolTable currentScope = symbolTableStack.peek();
            // Get the existing entry to preserve the index
            SymbolTable.SymbolEntry existing = currentScope.getSymbolEntry(name);
            if (existing != null) {
                // Replace with new entry using the same index
                currentScope.variableIndex.put(name,
                        new SymbolTable.SymbolEntry(existing.index(), name, variableDeclType,
                                getCurrentPackage(), ast));
            } else {
                // If it doesn't exist in current scope, just add it
                currentScope.addVariable(name, variableDeclType, getCurrentPackage(), ast);
            }
            clearVisibleVariablesCache();
        }
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
     * Checks if an 'our' variable was previously declared in the same package.
     * This is used to generate the "our variable redeclared" warning only when
     * the redeclaration occurs in the same package (matching Perl behavior).
     *
     * @param name The name of the variable to check.
     * @return true if the variable was previously declared with 'our' in the same package.
     */
    public boolean isOurVariableRedeclaredInSamePackage(String name) {
        String currentPackage = getCurrentPackage();
        SymbolTable.SymbolEntry entry = symbolTableStack.peek().getSymbolEntry(name);
        if (entry != null && "our".equals(entry.decl())) {
            // Check if the previous declaration was in the same package
            return currentPackage.equals(entry.perlPackage());
        }
        return false;
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
        // Create array sized for actual variables, indexed by their slot numbers
        // We need to find the maximum slot index to properly size the array
        int maxIndex = -1;
        for (Integer index : visibleVariables.keySet()) {
            if (index > maxIndex) {
                maxIndex = index;
            }
        }
        String[] vars = new String[maxIndex + 1];
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
        // Preserve existing version if the package already exists
        String existingVersion = null;
        if (!packageStack.isEmpty()) {
            PackageInfo current = packageStack.peek();
            if (current.packageName().equals(packageName)) {
                existingVersion = current.version();
            }
        }
        packageStack.pop();
        packageStack.push(new PackageInfo(packageName, isClass, existingVersion));
    }

    /**
     * Sets the version for a package.
     *
     * @param packageName The name of the package.
     * @param version     The version string.
     */
    public void setPackageVersion(String packageName, String version) {
        // Store in global map so version persists across scopes
        packageVersions.put(packageName, version);

        // Also update the current package on the stack if it matches
        if (!packageStack.isEmpty()) {
            PackageInfo current = packageStack.peek();
            if (current.packageName().equals(packageName)) {
                packageStack.pop();
                packageStack.push(new PackageInfo(packageName, current.isClass(), version));
            }
        }
    }

    /**
     * Gets the version for a package.
     *
     * @param packageName The name of the package.
     * @return The version string, or null if not set.
     */
    public String getPackageVersion(String packageName) {
        // First check the global map
        return packageVersions.get(packageName);
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

        // Clone visible variables (preserve the original `perlPackage` for
        // `our` entries — otherwise eval STRING compiled against this snapshot
        // would lose the caller's `our` aliases and resolve names against
        // whatever package is active inside the eval body).
        Map<Integer, SymbolTable.SymbolEntry> visibleVariables = this.getAllVisibleVariables();
        for (Integer index : visibleVariables.keySet()) {
            SymbolTable.SymbolEntry entry = visibleVariables.get(index);
            st.addVariable(entry.name(), entry.decl(), entry.perlPackage(), entry.ast());
        }

        // Clone the current package
        st.setCurrentPackage(this.getCurrentPackage(), this.currentPackageIsClass());

        // Clone the current subroutine
        st.setCurrentSubroutine(this.getCurrentSubroutine());

        // Clone whether we are inside a subroutine body
        st.setInSubroutineBody(this.isInSubroutineBody());

        // Clone warning flags
        st.warningFlagsStack.pop(); // Remove the initial value pushed by enterScope
        st.warningFlagsStack.push((BitSet) this.warningFlagsStack.peek().clone());

        // Clone disabled warnings flags
        st.warningDisabledStack.pop(); // Remove the initial value pushed by enterScope
        st.warningDisabledStack.push((BitSet) this.warningDisabledStack.peek().clone());

        // Clone fatal warnings flags
        st.warningFatalStack.pop(); // Remove the initial value pushed by enterScope
        st.warningFatalStack.push((BitSet) this.warningFatalStack.peek().clone());

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
     * Gets the current local variable index counter.
     *
     * @return The current index value.
     */
    public int getCurrentLocalVariableIndex() {
        return symbolTableStack.peek().index;
    }

    /**
     * Resets the local variable index counter for the current scope.
     * This is used when creating closures to ensure new local variables
     * don't overlap with uninitialized closure variable slots.
     *
     * @param newIndex The new starting index for local variable allocation.
     */
    public void resetLocalVariableIndex(int newIndex) {
        symbolTableStack.peek().index = newIndex;
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
        BitSet warningFlags = warningFlagsStack.peek();
        for (Map.Entry<String, Integer> entry : warningBitPositions.entrySet()) {
            String warningName = entry.getKey();
            int bitPosition = entry.getValue();
            boolean isEnabled = bitPosition >= 0 && warningFlags.get(bitPosition);
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
            warningFlagsStack.peek().set(bitPosition);
            // Clear the disabled bit when enabling
            warningDisabledStack.peek().clear(bitPosition);
        }
    }

    public void disableWarningCategory(String category) {
        Integer bitPosition = warningBitPositions.get(category);
        if (bitPosition != null) {
            warningFlagsStack.peek().clear(bitPosition);
            // Mark as explicitly disabled (for proper $^W interaction)
            warningDisabledStack.peek().set(bitPosition);
        }
    }

    public boolean isWarningCategoryEnabled(String category) {
        Integer bitPosition = warningBitPositions.get(category);
        return bitPosition != null && warningFlagsStack.peek().get(bitPosition);
    }

    /**
     * Checks if a warning category was explicitly disabled via 'no warnings'.
     * This is used to determine if $^W should be overridden.
     */
    public boolean isWarningCategoryDisabled(String category) {
        Integer bitPosition = warningBitPositions.get(category);
        return bitPosition != null && warningDisabledStack.peek().get(bitPosition);
    }

    /**
     * Enables FATAL mode for a warning category.
     * When a warning is FATAL, it throws an exception instead of printing a warning.
     */
    public void enableFatalWarningCategory(String category) {
        Integer bitPosition = warningBitPositions.get(category);
        if (bitPosition != null) {
            warningFatalStack.peek().set(bitPosition);
            // FATAL implies enabled
            warningFlagsStack.peek().set(bitPosition);
            warningDisabledStack.peek().clear(bitPosition);
        }
    }

    /**
     * Disables FATAL mode for a warning category (warning will be printed, not thrown).
     */
    public void disableFatalWarningCategory(String category) {
        Integer bitPosition = warningBitPositions.get(category);
        if (bitPosition != null) {
            warningFatalStack.peek().clear(bitPosition);
        }
    }

    /**
     * Checks if a warning category is in FATAL mode.
     */
    public boolean isFatalWarningCategory(String category) {
        Integer bitPosition = warningBitPositions.get(category);
        return bitPosition != null && warningFatalStack.peek().get(bitPosition);
    }

    /**
     * Gets the current warning bits as a Perl 5 compatible string.
     * This is used for caller()[9] to return the compile-time warning bits.
     * Format: each category uses 2 bits - bit 0 = enabled, bit 1 = fatal.
     *
     * @return A string of bytes representing the warning bits in Perl 5 format.
     */
    public String getWarningBitsString() {
        BitSet enabled = warningFlagsStack.peek();
        BitSet fatal = warningFatalStack.peek();
        return WarningFlags.toWarningBitsString(enabled, fatal, warningBitPositions);
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
            
            // Enable the corresponding experimental warning if this is an experimental feature
            // In Perl 5, experimental warnings are ON by default for experimental features
            String experimentalWarning = "experimental::" + feature;
            Integer warnBitPos = warningBitPositions.get(experimentalWarning);
            if (warnBitPos != null) {
                // Only enable if not explicitly disabled
                if (!warningDisabledStack.peek().get(warnBitPos)) {
                    warningFlagsStack.peek().set(warnBitPos);
                }
            }
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
        this.warningFlagsStack.push((BitSet) source.warningFlagsStack.peek().clone());

        // Copy disabled warnings flags
        this.warningDisabledStack.pop();
        this.warningDisabledStack.push((BitSet) source.warningDisabledStack.peek().clone());

        // Copy fatal warnings flags
        this.warningFatalStack.pop();
        this.warningFatalStack.push((BitSet) source.warningFatalStack.peek().clone());

        // Copy feature flags
        this.featureFlagsStack.pop();
        this.featureFlagsStack.push(source.featureFlagsStack.peek());

        // Copy strict options
        this.strictOptionsStack.pop();
        this.strictOptionsStack.push(source.strictOptionsStack.peek());
    }

    public record PackageInfo(String packageName, boolean isClass, String version) {
    }
}
