package org.perlonjava.astvisitor;

import org.perlonjava.astnode.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Visitor that collects all loop labels in an AST subtree.
 * 
 * <p>This is used to identify inner loop labels before emitting exception handlers,
 * allowing outer loops to generate proper delegation code for nested loops.
 */
public class LoopLabelCollectorVisitor implements Visitor {
    private final List<String> collectedLabels = new ArrayList<>();
    
    /**
     * Get all loop labels found during traversal.
     * 
     * @return List of label names (non-null labels only)
     */
    public List<String> getCollectedLabels() {
        return new ArrayList<>(collectedLabels);
    }
    
    @Override
    public void visit(For1Node node) {
        if (node.labelName != null && !collectedLabels.contains(node.labelName)) {
            collectedLabels.add(node.labelName);
        }
        if (node.body != null) node.body.accept(this);
    }
    
    @Override
    public void visit(For3Node node) {
        if (node.labelName != null && !collectedLabels.contains(node.labelName)) {
            collectedLabels.add(node.labelName);
        }
        if (node.body != null) node.body.accept(this);
    }
    
    @Override
    public void visit(BlockNode node) {
        for (Node element : node.elements) {
            if (element != null) element.accept(this);
        }
    }
    
    @Override
    public void visit(ListNode node) {
        for (Node element : node.elements) {
            if (element != null) element.accept(this);
        }
    }
    
    @Override
    public void visit(BinaryOperatorNode node) {
        if (node.left != null) node.left.accept(this);
        if (node.right != null) node.right.accept(this);
    }
    
    @Override
    public void visit(TernaryOperatorNode node) {
        if (node.condition != null) node.condition.accept(this);
        if (node.trueExpr != null) node.trueExpr.accept(this);
        if (node.falseExpr != null) node.falseExpr.accept(this);
    }
    
    @Override
    public void visit(OperatorNode node) {
        if (node.operand != null) node.operand.accept(this);
    }
    
    @Override
    public void visit(IfNode node) {
        if (node.condition != null) node.condition.accept(this);
        if (node.thenBranch != null) node.thenBranch.accept(this);
        if (node.elseBranch != null) node.elseBranch.accept(this);
    }
    
    @Override
    public void visit(TryNode node) {
        if (node.tryBlock != null) node.tryBlock.accept(this);
        if (node.catchBlock != null) node.catchBlock.accept(this);
        if (node.finallyBlock != null) node.finallyBlock.accept(this);
    }
    
    @Override
    public void visit(SubroutineNode node) {
        // Don't traverse into subroutines - different scope
    }
    
    // Leaf nodes - no traversal needed
    @Override
    public void visit(IdentifierNode node) {}
    
    @Override
    public void visit(NumberNode node) {}
    
    @Override
    public void visit(StringNode node) {}
    
    @Override
    public void visit(LabelNode node) {}
    
    @Override
    public void visit(CompilerFlagNode node) {}
    
    @Override
    public void visit(HashLiteralNode node) {
        for (Node element : node.elements) {
            if (element != null) element.accept(this);
        }
    }
    
    @Override
    public void visit(ArrayLiteralNode node) {
        for (Node element : node.elements) {
            if (element != null) element.accept(this);
        }
    }
    
    @Override
    public void visit(FormatNode node) {}
}

