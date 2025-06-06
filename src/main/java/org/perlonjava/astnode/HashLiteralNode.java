package org.perlonjava.astnode;

import org.perlonjava.astvisitor.Visitor;

import java.util.List;

/**
 * The HashLiteralNode class represents a node in the abstract syntax tree (AST) that holds
 * a list of other nodes surrounded by `{` `}`.
 */
public class HashLiteralNode extends AbstractNode {
    /**
     * The list of child nodes contained in this Node
     */
    public final List<Node> elements;

    /**
     * Constructs a new HashLiteralNode with the specified list of child nodes.
     *
     * @param elements the list of child nodes to be stored in this HashLiteralNode
     */
    public HashLiteralNode(List<Node> elements, int tokenIndex) {
        this.elements = elements;
        this.tokenIndex = tokenIndex;
    }

    public ListNode asListNode() {
        return new ListNode(elements, tokenIndex);
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

