package org.perlonjava.astnode;

import org.perlonjava.astvisitor.Visitor;
import org.perlonjava.parser.Parser;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a list expression in the AST, corresponding to Perl's {@code (...)} syntax.
 * <p>
 * ListNode is used for:
 * <ul>
 *   <li>Parenthesized lists: {@code (1, 2, 3)}</li>
 *   <li>Function arguments: {@code foo($a, $b, $c)}</li>
 *   <li>Assignment targets: {@code ($x, $y) = @array}</li>
 *   <li>qw// lists: {@code qw(a b c)}</li>
 * </ul>
 * <p>
 * Unlike {@link ArrayLiteralNode} which creates an array reference, ListNode represents
 * a flat list that can be assigned to arrays or used in list context.
 *
 * @see ArrayLiteralNode
 * @see HashLiteralNode
 */
public class ListNode extends AbstractNode {
    /**
     * The list of element nodes contained in this list expression.
     * <p>
     * Each element is an AST node representing an expression. Elements are evaluated
     * in the context determined by how the list is used (list context for assignments,
     * scalar context for the last element in scalar context, etc.).
     */
    public List<Node> elements;

    /**
     * Optional handle node for I/O operations or block for list transformations.
     * <p>
     * This field is used in several contexts:
     * <ul>
     *   <li>{@code print HANDLE @list} - HANDLE is stored here</li>
     *   <li>{@code say HANDLE @list} - HANDLE is stored here</li>
     *   <li>{@code map BLOCK @list} - BLOCK is stored here</li>
     *   <li>{@code grep BLOCK @list} - BLOCK is stored here</li>
     *   <li>{@code sort BLOCK @list} - BLOCK is stored here</li>
     * </ul>
     */
    public Node handle;

    /**
     * Constructs a ListNode with the specified elements.
     *
     * @param elements   the list of child nodes to be stored in this ListNode
     * @param tokenIndex the token index in the source for error reporting
     */
    public ListNode(List<Node> elements, int tokenIndex) {
        this(elements, tokenIndex, null);
    }

    /**
     * Constructs a ListNode with the specified elements and parser context.
     *
     * @param elements   the list of child nodes to be stored in this ListNode
     * @param tokenIndex the token index in the source for error reporting
     * @param parser     the parser instance (unused, kept for API compatibility)
     */
    public ListNode(List<Node> elements, int tokenIndex, Parser parser) {
        this.tokenIndex = tokenIndex;
        this.elements = elements;
        this.handle = null;
    }

    /**
     * Constructs an empty ListNode.
     * <p>
     * This constructor creates a list with no elements, useful for building
     * lists incrementally or representing empty argument lists.
     *
     * @param tokenIndex the token index in the source for error reporting
     */
    public ListNode(int tokenIndex) {
        this.elements = new ArrayList<>();
        this.tokenIndex = tokenIndex;
        this.handle = null;
    }

    /**
     * Creates a ListNode from a single node, or returns the node if it's already a ListNode.
     * <p>
     * This is a convenience method for normalizing nodes to list form.
     *
     * @param left the node to wrap in a list (or return as-is if already a ListNode)
     * @return a ListNode containing the input node
     */
    public static ListNode makeList(Node left) {
        if (left instanceof ListNode) {
            return (ListNode) left;
        }
        List<Node> list = new ArrayList<>();
        list.add(left);
        return new ListNode(list, left.getIndex());
    }

    /**
     * Creates a ListNode by concatenating two nodes.
     * <p>
     * Both nodes are first converted to ListNodes (if not already), then their
     * elements are combined into a single list.
     *
     * @param left  the first node (or list of nodes)
     * @param right the second node (or list of nodes)
     * @return a ListNode containing all elements from both inputs
     */
    public static ListNode makeList(Node left, Node right) {
        ListNode leftList = ListNode.makeList(left);
        ListNode rightList = ListNode.makeList(right);
        int size = rightList.elements.size();
        for (int i = 0; i < size; i++) {
            leftList.elements.add(rightList.elements.get(i));
        }
        return leftList;
    }

    /**
     * Creates a ListNode by concatenating two nodes and appending a third.
     *
     * @param left  the first node (or list of nodes)
     * @param right the second node (or list of nodes)
     * @param more  an additional node to append
     * @return a ListNode containing all elements
     */
    public static ListNode makeList(Node left, Node right, Node more) {
        ListNode leftList = ListNode.makeList(left, right);
        leftList.elements.add(more);
        return leftList;
    }

    /**
     * Creates a ListNode by concatenating two nodes and appending two more.
     *
     * @param left  the first node (or list of nodes)
     * @param right the second node (or list of nodes)
     * @param more  an additional node to append
     * @param more2 another additional node to append
     * @return a ListNode containing all elements
     */
    public static ListNode makeList(Node left, Node right, Node more, Node more2) {
        ListNode leftList = ListNode.makeList(left, right);
        leftList.elements.add(more);
        leftList.elements.add(more2);
        return leftList;
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

