package org.perlonjava.astvisitor;

import org.perlonjava.astnode.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Deep clones AST nodes to avoid circular references when refactoring.
 * This visitor creates new instances of all nodes and their children.
 */
public class CloneVisitor implements Visitor {
    private Node clonedNode;

    public static Node clone(Node node) {
        if (node == null) return null;
        CloneVisitor visitor = new CloneVisitor();
        node.accept(visitor);
        return visitor.clonedNode;
    }

    public static List<Node> cloneList(List<Node> nodes) {
        List<Node> cloned = new ArrayList<>(nodes.size());
        for (Node node : nodes) {
            cloned.add(clone(node));
        }
        return cloned;
    }

    @Override
    public void visit(NumberNode node) {
        // Leaf nodes are immutable, safe to share
        clonedNode = node;
    }

    @Override
    public void visit(StringNode node) {
        // Leaf nodes are immutable, safe to share
        clonedNode = node;
    }

    @Override
    public void visit(IdentifierNode node) {
        // Leaf nodes are immutable, safe to share
        clonedNode = node;
    }

    @Override
    public void visit(BinaryOperatorNode node) {
        BinaryOperatorNode copy = new BinaryOperatorNode(
            node.operator,
            clone(node.left),
            clone(node.right),
            node.tokenIndex
        );
        copyAnnotations(node, copy);
        clonedNode = copy;
    }

    @Override
    public void visit(OperatorNode node) {
        OperatorNode copy = new OperatorNode(
            node.operator,
            clone(node.operand),
            node.tokenIndex
        );
        copy.id = node.id;
        copyAnnotations(node, copy);
        clonedNode = copy;
    }

    @Override
    public void visit(TernaryOperatorNode node) {
        TernaryOperatorNode copy = new TernaryOperatorNode(
            node.operator,
            clone(node.condition),
            clone(node.trueExpr),
            clone(node.falseExpr),
            node.tokenIndex
        );
        copyAnnotations(node, copy);
        clonedNode = copy;
    }

    @Override
    public void visit(BlockNode node) {
        BlockNode copy = new BlockNode(cloneList(node.elements), node.tokenIndex);
        copy.isLoop = node.isLoop;
        copy.labelName = node.labelName;
        copy.labels = new ArrayList<>(node.labels);
        copyAnnotations(node, copy);
        clonedNode = copy;
    }

    @Override
    public void visit(ListNode node) {
        // ListNode.elements is final, can't be set after construction
        // We need to use the constructor that takes elements
        ListNode copy = new ListNode(cloneList(node.elements), node.tokenIndex);
        // Note: handle is set separately if needed
        copyAnnotations(node, copy);
        clonedNode = copy;
    }

    @Override
    public void visit(HashLiteralNode node) {
        HashLiteralNode copy = new HashLiteralNode(cloneList(node.elements), node.tokenIndex);
        copyAnnotations(node, copy);
        clonedNode = copy;
    }

    @Override
    public void visit(ArrayLiteralNode node) {
        ArrayLiteralNode copy = new ArrayLiteralNode(cloneList(node.elements), node.tokenIndex);
        copyAnnotations(node, copy);
        clonedNode = copy;
    }

    @Override
    public void visit(SubroutineNode node) {
        SubroutineNode copy = new SubroutineNode(
            node.name,
            node.prototype,
            node.attributes != null ? new ArrayList<>(node.attributes) : null,
            (BlockNode) clone(node.block),
            node.useTryCatch,
            node.tokenIndex
        );
        copyAnnotations(node, copy);
        clonedNode = copy;
    }

    @Override
    public void visit(IfNode node) {
        IfNode copy = new IfNode(
            node.operator,
            clone(node.condition),
            clone(node.thenBranch),
            clone(node.elseBranch),
            node.tokenIndex
        );
        copyAnnotations(node, copy);
        clonedNode = copy;
    }

    @Override
    public void visit(For1Node node) {
        // For loops have complex constructors, use shallow copy for now
        // The key issue is cloning the compound statements, not the loop structure
        clonedNode = node;
    }

    @Override
    public void visit(For3Node node) {
        // For loops have complex constructors, use shallow copy for now
        clonedNode = node;
    }

    @Override
    public void visit(TryNode node) {
        // Try blocks have complex structure, use shallow copy for now
        clonedNode = node;
    }

    @Override
    public void visit(LabelNode node) {
        LabelNode copy = new LabelNode(node.label, node.tokenIndex);
        copyAnnotations(node, copy);
        clonedNode = copy;
    }

    @Override
    public void visit(CompilerFlagNode node) {
        // Compiler flags have complex structure, use shallow copy
        clonedNode = node;
    }

    @Override
    public void visit(FormatNode node) {
        // FormatNode is not typically refactored, but provide basic support
        clonedNode = node; // Shallow copy for now
    }

    @Override
    public void visit(FormatLine node) {
        // FormatLine is not typically refactored
        clonedNode = node; // Shallow copy for now
    }

    private void copyAnnotations(AbstractNode source, AbstractNode target) {
        if (source.annotations != null && !source.annotations.isEmpty()) {
            // Shallow copy annotations - they're typically immutable metadata
            source.annotations.forEach(target::setAnnotation);
        }
    }
}

