package org.perlonjava.astnode;

import org.perlonjava.astvisitor.Visitor;

/**
 * The IdentifierNode class represents a node in the abstract syntax tree (AST) that holds
 * an identifier name. This class implements the Node interface, allowing it to be visited
 * by a Visitor.
 */
public class IdentifierNode extends AbstractNode {
    /**
     * The identifier name represented by this node.
     */
    public String name;

    /**
     * Constructs a new IdentifierNode with the specified identifier name.
     *
     * @param name the identifier name to be stored in this node
     */
    public IdentifierNode(String name, int tokenIndex) {
        this.name = name;
        this.tokenIndex = tokenIndex;
    }

    /**
     * Accepts a visitor that performs some operation on this node.
     * This method is part of the Visitor design pattern, which allows
     * for defining new operations on the AST nodes without changing
     * the node classes.
     *
     * @param visitor the visitor that will perform the operation on this node
     */
    @Override
    public void accept(Visitor visitor) {
        visitor.visit(this);
    }
}

