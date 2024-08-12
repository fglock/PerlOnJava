import java.util.*;

/**
 * The ListNode class represents a node in the abstract syntax tree (AST) that holds
 * a list of other nodes. This class implements the Node interface, allowing it to be
 * visited by a Visitor.
 *
 * <p>The ListNode class is used to encapsulate a list of child nodes in the AST,
 * providing a way to represent and manipulate collections of nodes within the tree structure.</p>
 */
public class ListNode extends AbstractNode {
    /**
     * The list of child nodes contained in this ListNode.
     */
    public List<Node> elements;

    /**
     * Constructs a new ListNode with the specified list of child nodes.
     *
     * @param elements the list of child nodes to be stored in this ListNode
     */
    ListNode(List<Node> elements, int tokenIndex) {
        this.elements = elements;
        this.tokenIndex = tokenIndex;
    }

    public static ListNode add(Node left, Node right) {
        List<Node> list = new ArrayList<>();
        list.add(left);
        list.add(right);
        return new ListNode(list, left.getIndex());
    }

    public static ListNode add(ListNode left, Node right) {
        left.elements.add(right);
        return left;
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

