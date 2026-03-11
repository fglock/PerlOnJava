package org.perlonjava.frontend.astnode;

import org.perlonjava.frontend.analysis.Visitor;

/**
 * The DeferNode class represents a node in the abstract syntax tree (AST) 
 * that holds a "defer" statement.
 *
 * <p>A defer statement registers a block of code to be executed when the
 * enclosing scope exits, regardless of how it exits (normal completion,
 * return, exception, etc.).</p>
 *
 * <p>Syntax: {@code defer { BLOCK }}</p>
 *
 * <p>Multiple defer blocks in the same scope execute in LIFO order
 * (last registered, first executed).</p>
 *
 * @see org.perlonjava.runtime.runtimetypes.DeferBlock
 */
public class DeferNode extends AbstractNode {

    /**
     * The block of code to execute when the scope exits.
     */
    public final Node block;

    /**
     * Constructs a new DeferNode with the specified block.
     *
     * @param block      the block of code to execute at scope exit
     * @param tokenIndex the index of the 'defer' token for error reporting
     */
    public DeferNode(Node block, int tokenIndex) {
        this.block = block;
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
