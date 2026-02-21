package org.perlonjava.frontend.astnode;

import org.perlonjava.frontend.analysis.Visitor;

/**
 * The NumberNode class represents a node in the abstract syntax tree (AST) that holds
 * a numeric value. This class implements the Node interface, allowing it to be visited
 * by a Visitor.
 */
public class NumberNode extends AbstractNode {
    /**
     * The numeric value represented by this node. It is stored as a string to
     * preserve the exact representation of the number as it appears in the source code.
     */
    public final String value;

    /**
     * Constructs a new NumberNode with the specified numeric value.
     *
     * @param value the numeric value to be stored in this node
     */
    public NumberNode(String value, int tokenIndex) {
        // Check if the value ends with ".0" and remove it if present
        if (value.endsWith(".0")) {
            this.value = value.substring(0, value.length() - 2);
        } else {
            this.value = value;
        }
        this.tokenIndex = tokenIndex;
    }

    /**
     * Accepts a visitor to process this NumberNode.
     *
     * @param visitor the visitor to process this node
     */
    @Override
    public void accept(Visitor visitor) {
        visitor.visit(this);
    }
}
