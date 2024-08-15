package org.perlonjava;

import java.util.*;

/**
 * The BlockNode class represents a node in the abstract syntax tree (AST) that holds
 * a list of other nodes. This class implements the Node interface, allowing it to be
 * visited by a Visitor.
 *
 * <p>The BlockNode class is used to encapsulate a list of child nodes in the AST,
 * providing a way to represent and manipulate collections of nodes within the tree structure.</p>
 */
public class BlockNode extends AbstractNode {
    /**
     * The list of child nodes contained in this BlockNode.
     */
    public final List<Node> elements;

    /**
     * Constructs a new BlockNode with the specified list of child nodes.
     *
     * @param useNewScope this loop creates a new variable scope
     * @param elements the list of child nodes to be stored in this BlockNode
     * @param tokenIndex the index of the token in the source code
     */
    BlockNode(List<Node> elements, int tokenIndex) {
        this.elements = elements;
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
    public void accept(Visitor visitor) throws Exception {
        visitor.visit(this);
    }
}

