package org.perlonjava.astnode;

import org.perlonjava.codegen.Visitor;

/**
 * The AnonSubNode class represents a node in the abstract syntax tree (AST) that holds an anonymous sub.
 * The parts of the node are: "block".
 * This class implements the Node interface, allowing it to be visited by a Visitor.
 */
public class AnonSubNode extends AbstractNode {
    /**
     * The block of the subroutine
     */
    public final Node block;

    /**
     * useTryCatch if we are compiling an eval block
     */
    public final boolean useTryCatch;

    /**
     * Constructs a new AnonSubNode with the specified parts.
     *
     * @param block the block of the subroutine
     */
    public AnonSubNode(Node block, boolean useTryCatch, int tokenIndex) {
        this.block = block;
        this.useTryCatch = useTryCatch;
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

