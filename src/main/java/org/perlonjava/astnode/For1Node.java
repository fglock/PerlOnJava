package org.perlonjava.astnode;

import org.perlonjava.astvisitor.Visitor;

/**
 * The For1Node class represents a node in the abstract syntax tree (AST) that holds a "for" loop statement.
 * The parts of the statement are: "variable", "list", and "body".
 * This class implements the Node interface, allowing it to be visited by a Visitor.
 */
public class For1Node extends AbstractNode {
    /**
     * This loop creates a new variable scope
     */
    public final boolean useNewScope;
    /**
     * The variable part of the for loop.
     */
    public final Node list;
    /**
     * The body of the for loop.
     */
    public final Node body;
    public final Node continueBlock;
    /**
     * The list part of the for loop.
     */
    public Node variable;
    /**
     * the label name for this loop
     */
    public String labelName;
    /**
     * If true, use array of aliases for list evaluation (for global $_ loops)
     */
    public boolean needsArrayOfAlias = false;
    /**
     * If >= 0, use this pre-evaluated array variable instead of evaluating list
     * This is set by EmitBlock when it pre-evaluates the list before localizing $_
     */
    public int preEvaluatedArrayIndex = -1;

    /**
     * Constructs a new For1Node with the specified parts of the for loop.
     *
     * @param useNewScope this loop creates a new variable scope
     * @param variable    the variable part of the for loop
     * @param list        the list part of the for loop
     * @param body        the body of the for loop
     * @param tokenIndex  the index of the token in the source code
     */
    public For1Node(String labelName, boolean useNewScope, Node variable, Node list, Node body, Node continueBlock, int tokenIndex) {
        this.labelName = labelName;
        this.useNewScope = useNewScope;
        this.variable = variable;
        this.list = list;
        this.body = body;
        this.continueBlock = continueBlock;
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

