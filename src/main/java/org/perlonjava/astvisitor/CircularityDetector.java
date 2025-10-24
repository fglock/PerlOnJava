package org.perlonjava.astvisitor;

import org.perlonjava.astnode.*;

import java.util.HashSet;
import java.util.Set;

/**
 * Detects circular references in the AST to prevent infinite recursion.
 * This is a diagnostic tool to identify AST construction bugs.
 */
public class CircularityDetector implements Visitor {
    // Track the current path (ancestry) to detect true cycles, not just shared nodes (DAG)
    private final Set<Node> currentPath = new HashSet<>();
    private boolean circularityDetected = false;
    private Node circularNode = null;

    public boolean hasCircularity() {
        return circularityDetected;
    }

    public Node getCircularNode() {
        return circularNode;
    }

    private boolean checkNode(Node node) {
        if (node == null) return false;
        
        // Check if this node is already in the CURRENT PATH (true cycle)
        // Not just if we've visited it before (which would flag shared nodes/DAG)
        if (currentPath.contains(node)) {
            circularityDetected = true;
            circularNode = node;
            System.err.println("TRUE CIRCULAR REFERENCE DETECTED: " + node.getClass().getSimpleName() + 
                             " at index " + node.getIndex());
            return true;
        }
        currentPath.add(node);
        return false;
    }
    
    private void exitNode(Node node) {
        if (node != null) {
            currentPath.remove(node);
        }
    }

    @Override
    public void visit(NumberNode node) {
        checkNode(node);
        exitNode(node);
    }

    @Override
    public void visit(StringNode node) {
        checkNode(node);
        exitNode(node);
    }

    @Override
    public void visit(IdentifierNode node) {
        checkNode(node);
        exitNode(node);
    }

    @Override
    public void visit(BinaryOperatorNode node) {
        if (checkNode(node)) return;
        if (node.left != null) node.left.accept(this);
        if (node.right != null) node.right.accept(this);
        exitNode(node);
    }

    @Override
    public void visit(OperatorNode node) {
        if (checkNode(node)) {
            System.err.println("  Operator: " + node.operator);
            return;
        }
        if (node.operand != null) node.operand.accept(this);
        exitNode(node);
    }

    @Override
    public void visit(TernaryOperatorNode node) {
        if (checkNode(node)) return;
        if (node.condition != null) node.condition.accept(this);
        if (node.trueExpr != null) node.trueExpr.accept(this);
        if (node.falseExpr != null) node.falseExpr.accept(this);
        exitNode(node);
    }

    @Override
    public void visit(BlockNode node) {
        if (checkNode(node)) return;
        if (node.elements != null) {
            for (Node element : node.elements) {
                if (element != null) element.accept(this);
            }
        }
        exitNode(node);
    }

    @Override
    public void visit(ListNode node) {
        if (checkNode(node)) return;
        if (node.elements != null) {
            for (Node element : node.elements) {
                if (element != null) element.accept(this);
            }
        }
        exitNode(node);
    }

    @Override
    public void visit(HashLiteralNode node) {
        if (checkNode(node)) return;
        if (node.elements != null) {
            for (Node element : node.elements) {
                if (element != null) element.accept(this);
            }
        }
    }

    @Override
    public void visit(ArrayLiteralNode node) {
        if (checkNode(node)) return;
        if (node.elements != null) {
            for (Node element : node.elements) {
                if (element != null) element.accept(this);
            }
        }
    }

    @Override
    public void visit(SubroutineNode node) {
        if (checkNode(node)) return;
        if (node.block != null) node.block.accept(this);
    }

    @Override
    public void visit(IfNode node) {
        if (checkNode(node)) return;
        if (node.condition != null) node.condition.accept(this);
        if (node.thenBranch != null) node.thenBranch.accept(this);
        if (node.elseBranch != null) node.elseBranch.accept(this);
    }

    @Override
    public void visit(For1Node node) {
        if (checkNode(node)) return;
        if (node.variable != null) node.variable.accept(this);
        if (node.list != null) node.list.accept(this);
        if (node.body != null) node.body.accept(this);
        if (node.continueBlock != null) node.continueBlock.accept(this);
    }

    @Override
    public void visit(For3Node node) {
        if (checkNode(node)) return;
        if (node.initialization != null) node.initialization.accept(this);
        if (node.condition != null) node.condition.accept(this);
        if (node.increment != null) node.increment.accept(this);
        if (node.body != null) node.body.accept(this);
    }

    @Override
    public void visit(TryNode node) {
        if (checkNode(node)) return;
        if (node.tryBlock != null) node.tryBlock.accept(this);
        if (node.catchBlock != null) node.catchBlock.accept(this);
        if (node.finallyBlock != null) node.finallyBlock.accept(this);
    }

    @Override
    public void visit(LabelNode node) {
        checkNode(node);
    }

    @Override
    public void visit(CompilerFlagNode node) {
        checkNode(node);
    }

    @Override
    public void visit(FormatNode node) {
        if (checkNode(node)) return;
        if (node.templateLines != null) {
            for (FormatLine line : node.templateLines) {
                if (line != null) line.accept(this);
            }
        }
    }

    @Override
    public void visit(FormatLine node) {
        checkNode(node);
    }
}

