package org.perlonjava.astnode;

import org.perlonjava.astvisitor.Visitor;

import java.util.ArrayList;
import java.util.List;

/**
 * The BlockNode class represents a node in the abstract syntax tree (AST) that holds
 * a list of other nodes. This class implements the Node interface, allowing it to be
 * visited by a Visitor.
 */
public class BlockNode extends AbstractNode {
    /**
     * The list of child nodes contained in this BlockNode.
     */
    public final List<Node> elements;

    /**
     * This flag indicates if this BlockNode represents a loop.
     */
    public boolean isLoop;
    /**
     * the label name for this loop block, as in `L1: {...}`
     */
    public String labelName;

    /**
     * The list of labels inside this block, as in `{ L1: ..., L2:... }`
     */
    public List<String> labels;

    /**
     * Constructs a new BlockNode with the specified list of child nodes.
     *
     * @param elements   the list of child nodes to be stored in this BlockNode
     * @param tokenIndex the index of the token in the source code
     */
    public BlockNode(List<Node> elements, int tokenIndex) {
        this.elements = elements;
        this.tokenIndex = tokenIndex;
        this.labels = new ArrayList<>();
        this.labelName = null;
        this.isLoop = false;
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

