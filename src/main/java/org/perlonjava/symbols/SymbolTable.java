package org.perlonjava.symbols;

import org.perlonjava.astnode.OperatorNode;

import java.util.HashMap;
import java.util.Map;

/**
 * A simple symbol table that maps variable names to unique integer indices.
 */
public class SymbolTable {
    // A map to store variable names and their corresponding indices
    public Map<String, SymbolEntry> variableIndex = new HashMap<>();

    // Counter to generate unique indices for lexical variables (pad slots)
    public int lexicalIndex;

    // Counter to allocate temporary JVM local variable slots during code emission
    public int localIndex;

    public SymbolTable(int index) {
        this.lexicalIndex = index;
        this.localIndex = index;
    }

    public SymbolTable(int lexicalIndex, int localIndex) {
        this.lexicalIndex = lexicalIndex;
        this.localIndex = localIndex;
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
        if (!variableIndex.containsKey(name)) {
            // Add the variable with a unique index.
            // IMPORTANT: lexical variables and compiler-generated temporaries live in the same
            // JVM local slot namespace. If we allocate temporaries first (advancing localIndex)
            // and later allocate a lexical with a lower index (from lexicalIndex), we will
            // reuse the same JVM local slot for different types and trigger verifier errors.
            int slot = Math.max(lexicalIndex, localIndex);
            variableIndex.put(name, new SymbolEntry(slot, name, variableDeclType, perlPackage, ast));

            lexicalIndex = slot + 1;
            localIndex = slot + 1;
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

        sb.append("  lexicalIndex: ").append(lexicalIndex).append("\n");
        sb.append("  localIndex: ").append(localIndex).append("\n");

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
