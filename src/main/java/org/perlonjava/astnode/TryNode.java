package org.perlonjava.astnode;

import org.perlonjava.codegen.Visitor;

/**
 * The TryNode class represents a node in the abstract syntax tree (AST) that holds a "try-catch-finally" statement.
 * The parts of the statement are: "tryBlock", "catchBlock", and "finallyBlock".
 * This class implements the Node interface, allowing it to be visited by a Visitor.
 */
public class TryNode extends AbstractNode {
    /**
     * The block of code to try.
     */
    public final Node tryBlock;

    /**
     * The parameter for the catch block (e.g., exception variable).
     */
    public final Node catchParameter;

    /**
     * The block of code to execute in case of an exception.
     */
    public final Node catchBlock;

    /**
     * The block of code to execute after try and catch blocks, regardless of an exception.
     * The finallyBlock can be null.
     */
    public final Node finallyBlock;

    /**
     * Constructs a new TryNode with the specified blocks and catch parameter.
     *
     * @param tryBlock      the block of code to try
     * @param catchParameter the parameter for the catch block
     * @param catchBlock    the block of code to execute in case of an exception
     * @param finallyBlock  the block of code to execute after try and catch blocks
     */
    public TryNode(Node tryBlock, Node catchParameter, Node catchBlock, Node finallyBlock, int tokenIndex) {
        this.tryBlock = tryBlock;
        this.catchBlock = catchBlock;
        this.catchParameter = catchParameter;
        this.finallyBlock = finallyBlock;
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
