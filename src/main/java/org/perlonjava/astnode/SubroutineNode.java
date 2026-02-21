package org.perlonjava.astnode;

import org.perlonjava.frontend.analysis.Visitor;

import java.util.List;

/**
 * The SubroutineNode class represents a node in the abstract syntax tree (AST) that holds an anonymous sub.
 * The parts of the node are: "block".
 * This class implements the Node interface, allowing it to be visited by a Visitor.
 */
public class SubroutineNode extends AbstractNode {
    // Optional name
    public final String name;

    // Optional prototype
    public final String prototype;

    // Optional attributes
    public final List<String> attributes;

    /**
     * The block of the subroutine
     */
    public final Node block;

    /**
     * useTryCatch if we are compiling an eval block
     */
    public final boolean useTryCatch;

    /**
     * Constructs a new SubroutineNode with the specified parts.
     *
     * @param block the block of the subroutine
     */
    public SubroutineNode(String name, String prototype, List<String> attributes, Node block, boolean useTryCatch, int tokenIndex) {
        this.name = name;
        this.prototype = prototype;
        this.attributes = attributes;
        this.block = block;
        this.useTryCatch = useTryCatch;
        this.tokenIndex = tokenIndex;

        if (block instanceof AbstractNode abstractNode) {
            abstractNode.setAnnotation("blockIsSubroutine", true);
        }
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

