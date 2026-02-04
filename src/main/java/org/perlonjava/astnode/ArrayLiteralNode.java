package org.perlonjava.astnode;

import org.perlonjava.astvisitor.Visitor;
import org.perlonjava.parser.Parser;

import java.util.List;

/**
 * Represents an array literal in the AST, corresponding to Perl's {@code [...]} syntax.
 * <p>
 * Examples:
 * <ul>
 *   <li>{@code [1, 2, 3]} - simple array literal</li>
 *   <li>{@code [$a, @b, %c]} - array with mixed elements</li>
 *   <li>{@code [[1,2], [3,4]]} - nested array literals</li>
 * </ul>
 *
 * @see HashLiteralNode
 * @see ListNode
 */
public class ArrayLiteralNode extends AbstractNode {
    /**
     * The list of element nodes contained in this array literal.
     * <p>
     * Each element is evaluated in LIST context when the array is constructed.
     * Elements may be scalars, arrays (which flatten), hashes (which flatten to key-value pairs),
     * or any expression.
     */
    public List<Node> elements;

    /**
     * Constructs a new ArrayLiteralNode with the specified list of child nodes.
     *
     * @param elements   the list of child nodes to be stored in this ArrayLiteralNode
     * @param tokenIndex the token index in the source for error reporting
     */
    public ArrayLiteralNode(List<Node> elements, int tokenIndex) {
        this(elements, tokenIndex, null);
    }

    /**
     * Constructs a new ArrayLiteralNode with the specified list of child nodes and parser context.
     *
     * @param elements   the list of child nodes to be stored in this ArrayLiteralNode
     * @param tokenIndex the token index in the source for error reporting
     * @param parser     the parser instance (unused, kept for API compatibility)
     */
    public ArrayLiteralNode(List<Node> elements, int tokenIndex, Parser parser) {
        this.tokenIndex = tokenIndex;
        this.elements = elements;
    }

    /**
     * Converts this array literal to a ListNode containing the same elements.
     * <p>
     * This is useful when the array elements need to be processed as a flat list
     * rather than as an array reference.
     *
     * @return a new ListNode containing this array's elements
     */
    public ListNode asListNode() {
        return new ListNode(elements, tokenIndex);
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

