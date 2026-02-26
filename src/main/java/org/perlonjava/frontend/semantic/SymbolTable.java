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
        // Lexical redeclaration in the same scope masks the earlier declaration.
        // Perl may warn, but the new variable must become the visible one.
        if (variableIndex.containsKey(name)) {
            if ("my".equals(variableDeclType) || "state".equals(variableDeclType)) {
                variableIndex.put(name, new SymbolEntry(index++, name, variableDeclType, perlPackage, ast));
                return variableIndex.get(name).index;
            }
            return variableIndex.get(name).index;
        }

        // Add the variable with a unique index
        variableIndex.put(name, new SymbolEntry(index++, name, variableDeclType, perlPackage, ast));
        return variableIndex.get(name).index;
    }

    /**
     * Retrieves the index of a variable from the symbol table.
     *
     * @param name The name of the variable to look up.
     * @return The index of the variable, or -1 if the variable is not found.
     */
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
