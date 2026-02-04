package org.perlonjava.astnode;

import org.perlonjava.astvisitor.Visitor;
import org.perlonjava.parser.Parser;

import java.util.List;

/**
 * Represents a hash literal in the AST, corresponding to Perl's {@code {...}} syntax.
 * <p>
 * Examples:
 * <ul>
 *   <li>{@code {a => 1, b => 2}} - simple hash literal with fat comma</li>
 *   <li>{@code {$key => $value}} - hash with variable key/value</li>
 *   <li>{@code {nested => {inner => 1}}} - nested hash literals</li>
 * </ul>
 * <p>
 * The elements list contains key-value pairs in sequence: key1, value1, key2, value2, etc.
 *
 * @see ArrayLiteralNode
 * @see ListNode
 */
public class HashLiteralNode extends AbstractNode {
    /**
     * The list of element nodes contained in this hash literal.
     * <p>
     * Elements are stored as alternating key-value pairs: [key1, value1, key2, value2, ...].
     * Each element is evaluated in LIST context when the hash is constructed.
     */
    public List<Node> elements;

    /**
     * Constructs a new HashLiteralNode with the specified list of child nodes.
     *
     * @param elements   the list of key-value pairs (alternating keys and values)
     * @param tokenIndex the token index in the source for error reporting
     */
    public HashLiteralNode(List<Node> elements, int tokenIndex) {
        this(elements, tokenIndex, null);
    }

    /**
     * Constructs a new HashLiteralNode with the specified list of child nodes and parser context.
     *
     * @param elements   the list of key-value pairs (alternating keys and values)
     * @param tokenIndex the token index in the source for error reporting
     * @param parser     the parser instance (unused, kept for API compatibility)
     */
    public HashLiteralNode(List<Node> elements, int tokenIndex, Parser parser) {
        this.tokenIndex = tokenIndex;
        this.elements = elements;
    }

    /**
     * Converts this hash literal to a ListNode containing the same elements.
     * <p>
     * This is useful when the hash elements need to be processed as a flat list
     * of key-value pairs rather than as a hash reference.
     *
     * @return a new ListNode containing this hash's key-value pairs
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

