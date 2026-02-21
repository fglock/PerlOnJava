package org.perlonjava.frontend.astnode;

import org.perlonjava.frontend.analysis.Visitor;

/**
 * The OperatorNode class represents a node in the abstract syntax tree (AST) that holds
 * a unary or list operator and its operand. This class implements the Node interface, allowing it to be
 * visited by a Visitor.
 */
public class OperatorNode extends AbstractNode {
    /**
     * The operand on which the operator is applied.
     */
    public Node operand;
    /**
     * The operator represented by this node.
     */
    public String operator;
    /**
     * Node id is used by some operations like `state` to identify a specific node.
     * BEGIN blocks mark captured variables using this id.
     * TODO: move this to `annotations` field.
     */
    public int id = 0;

    /**
     * Constructs a new OperatorNode with the specified operator and operand.
     *
     * @param operator the unary operator to be stored in this node
     * @param operand  the operand on which the unary operator is applied; operand can be a single node or a ListNode
     */
    public OperatorNode(String operator, Node operand, int tokenIndex) {
        this.operator = operator;
        this.operand = operand;
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

