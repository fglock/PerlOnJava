package org.perlonjava.astnode;

import org.perlonjava.astvisitor.Visitor;

import java.util.ArrayList;
import java.util.List;

/**
 * The ListNode class represents a node in the abstract syntax tree (AST) that holds
 * a list of other nodes. This class implements the Node interface, allowing it to be
 * visited by a Visitor.
 */
public class ListNode extends AbstractNode {
    /**
     * The list of child nodes contained in this ListNode.
     */
    public final List<Node> elements;

    /**
     * Optional fileHandle or block.
     * Used in: print, say, map, grep, sort
     */
    public Node handle;

    /**
     * Constructs a new ListNode with the specified list of child nodes.
     *
     * @param elements the list of child nodes to be stored in this ListNode
     */
    public ListNode(List<Node> elements, int tokenIndex) {
        this.elements = elements;
        this.tokenIndex = tokenIndex;
        this.handle = null;
    }

    public ListNode(int tokenIndex) {
        this.elements = new ArrayList<>();
        this.tokenIndex = tokenIndex;
        this.handle = null;
    }

    public static ListNode makeList(Node left) {
        if (left instanceof ListNode) {
            return (ListNode) left;
        }
        List<Node> list = new ArrayList<>();
        list.add(left);
        return new ListNode(list, left.getIndex());
    }

    public static ListNode makeList(Node left, Node right) {
        ListNode leftList = ListNode.makeList(left);
        ListNode rightList = ListNode.makeList(right);
        int size = rightList.elements.size();
        for (int i = 0; i < size; i++) {
            leftList.elements.add(rightList.elements.get(i));
        }
        return leftList;
    }

    public static ListNode makeList(Node left, Node right, Node more) {
        ListNode leftList = ListNode.makeList(left, right);
        leftList.elements.add(more);
        return leftList;
    }

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

