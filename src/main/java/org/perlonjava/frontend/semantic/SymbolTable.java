package org.perlonjava.frontend.semantic;

import org.perlonjava.frontend.astnode.OperatorNode;

import java.util.HashMap;
import java.util.Map;

/**
 * A simple symbol table that maps variable names to unique integer indices.
 */
public class SymbolTable {
    // A map to store variable names and their corresponding indices
    public Map<String, SymbolEntry> variableIndex = new HashMap<>();

    // A counter to generate unique indices for variables
    public int index;

    public SymbolTable(int index) {
        this.index = index;
    }

    /**
     * Adds a variable to the symbol table if it does not already exist.
     *
     * @param name The name of the variable to add.
     * @return The index of the variable in the symbol table.
     */
    public int addVariable(String name, String variableDeclType, String perlPackage, OperatorNode ast) {
        // Check if the variable is not already in the table
        // XXX TODO under 'no strict', we may need to allow variable redeclaration
        SymbolEntry existing = variableIndex.get(name);
        if (existing == null) {
            // Variable doesn't exist, add it
            variableIndex.put(name, new SymbolEntry(index++, name, variableDeclType, perlPackage, ast));
        } else if ("our".equals(variableDeclType) && existing.perlPackage != null 
                   && !existing.perlPackage.equals(perlPackage)) {
            // For 'our' declarations in a different package, create a new entry
            // This handles the case where 'our $AUTOLOAD' is declared in multiple packages
            // within the same lexical scope - each should refer to its own package's variable
            variableIndex.put(name, new SymbolEntry(index++, name, variableDeclType, perlPackage, ast));
        }
        // Return the index of the variable
        return variableIndex.get(name).index;
    }

    /**
     * Retrieves the index of a variable from the symbol table.
     *
     * @param name The name of the variable to look up.
     * @return The index of the variable, or -1 if the variable is not found.
     */
    public void addVariableWithIndex(String name, int specificIndex, String variableDeclType, String perlPackage) {
        variableIndex.put(name, new SymbolEntry(specificIndex, name, variableDeclType, perlPackage, null));
        if (specificIndex >= index) {
            index = specificIndex + 1;
        }
    }

    public int getVariableIndex(String name) {
        // Return the index of the variable, or -1 if not found
        return variableIndex.getOrDefault(name, new SymbolEntry(-1, null, null, null, null)).index;
    }

    public SymbolEntry getSymbolEntry(String name) {
        // Return the index of the variable, or null if not found
        return variableIndex.getOrDefault(name, null);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("SymbolTable {\n");

        sb.append("  variableIndex: {\n");
        for (Map.Entry<String, SymbolEntry> entry : variableIndex.entrySet()) {
            sb.append("    ").append(entry.getValue()).append(",\n");
        }
        sb.append("  },\n");

        sb.append("  index: ").append(index).append("\n");
        sb.append("}");
        return sb.toString();
    }

    public record SymbolEntry(
            Integer index,
            String name,
            String decl,
            String perlPackage,
            OperatorNode ast
    ) {
    }
}
