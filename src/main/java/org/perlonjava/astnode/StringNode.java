package org.perlonjava.astnode;

import org.perlonjava.astvisitor.Visitor;

import static org.perlonjava.runtime.ScalarUtils.printable;

/**
 * The StringNode class represents a node in the abstract syntax tree (AST) that holds
 * a string value. This class implements the Node interface, allowing it to be visited
 * by a Visitor.
 */
public class StringNode extends AbstractNode {
    /**
     * The string value represented by this node.
     */
    public final String value;

    /**
     * Flag indicating whether this node represents a v-string.
     */
    public final boolean isVString;

    /**
     * Constructs a new StringNode with the specified string value.
     *
     * @param value the string value to be stored in this node
     */
    public StringNode(String value, int tokenIndex) {
        this.value = value;
        this.tokenIndex = tokenIndex;
        this.isVString = false;
    }

    /**
     * Constructs a new StringNode with the specified string value and v-string flag.
     *
     * @param value      the string value to be stored in this node
     * @param isVString  flag indicating whether this is a v-string
     * @param tokenIndex the index of the token
     */
    public StringNode(String value, boolean isVString, int tokenIndex) {
        this.value = value;
        this.tokenIndex = tokenIndex;
        this.isVString = isVString;
    }

    /**
     * Returns a string representation of this StringNode.
     *
     * @return a string representation of this StringNode
     */
    @Override
    public String toString() {
        return "String(" + printable(value) + ")";
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

