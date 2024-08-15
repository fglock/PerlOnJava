package org.perlonjava.node;

import org.perlonjava.Visitor;

/**
 * The For3Node class represents a node in the abstract syntax tree (AST) that holds a "for" loop statement.
 * The parts of the statement are: "initialization", "condition", "increment", and "body".
 * This class implements the Node interface, allowing it to be visited by a Visitor.
 *
 * <p>The For3Node class is used to encapsulate "for" loops in the AST, providing
 * a way to store and manipulate "for" loops and their parts within the tree structure.</p>
 */
public class For3Node extends AbstractNode {
    /**
     * This loop creates a new variable scope
     */
    public final boolean useNewScope;

    /**
     * The initialization part of the for loop.
     */
    public final Node initialization;

    /**
     * The condition part of the for loop.
     */
    public final Node condition;

    /**
     * The increment part of the for loop.
     */
    public final Node increment;

    /**
     * The body of the for loop.
     */
    public final Node body;

    /**
     * Constructs a new For3Node with the specified parts of the for loop.
     *
     * @param variable the variable part of the for loop
     * @param initialization the initialization part of the for loop
     * @param condition the condition part of the for loop
     * @param increment the increment part of the for loop
     * @param body the body of the for loop
     * @param tokenIndex the index of the token in the source code
     */
    public For3Node(boolean useNewScope, Node initialization, Node condition, Node increment, Node body, int tokenIndex) {
        this.useNewScope = useNewScope;
        this.initialization = initialization;
        this.condition = condition;
        this.increment = increment;
        this.body = body;
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

