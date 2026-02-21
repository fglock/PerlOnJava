package org.perlonjava.frontend.astnode;

import org.perlonjava.frontend.semantic.ScopedSymbolTable;

/**
 * The EvalOperatorNode class represents a specialized OperatorNode
 * that includes an "eval" operation and an additional field for
 * ScopedSymbolTable.
 */
public class EvalOperatorNode extends OperatorNode {
    /**
     * The symbol table associated with this eval operation.
     */
    private final ScopedSymbolTable symbolTable;

    /**
     * Constructs a new EvalOperatorNode with the specified operator, operand, and symbol table.
     *
     * @param operator    the unary operator to be stored in this node
     * @param operand     the operand on which the unary operator is applied
     * @param tokenIndex  the index of the token in the source code
     * @param symbolTable the symbol table to be used during the eval operation
     */
    public EvalOperatorNode(String operator, Node operand, ScopedSymbolTable symbolTable, int tokenIndex) {
        super(operator, operand, tokenIndex);
        this.symbolTable = symbolTable;
    }

    /**
     * Returns the symbol table associated with this eval operation.
     *
     * @return the symbol table
     */
    public ScopedSymbolTable getSymbolTable() {
        return symbolTable;
    }
}
