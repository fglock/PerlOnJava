package org.perlonjava.codegen;

import org.perlonjava.astnode.*;

public class LocalOperatorVisitor implements Visitor {
    private boolean containsLocalOperator = false;

    public static boolean containsLocalOperator(BlockNode blockNode) {
        LocalOperatorVisitor visitor = new LocalOperatorVisitor();
        blockNode.accept(visitor);
        return visitor.containsLocalOperator;
    }

    @Override
    public void visit(BlockNode node) {
        if (!containsLocalOperator) { // Only continue if not already found
            for (Node element : node.elements) {
                element.accept(this);
                if (containsLocalOperator) {
                    break;
                }
            }
        }
    }

    @Override
    public void visit(OperatorNode node) {
        if ("local".equals(node.operator)) {
            containsLocalOperator = true;
        }
    }

    @Override
    public void visit(For1Node node) {
        if (!node.useNewScope) {
            node.variable.accept(this);
            node.list.accept(this);
            node.body.accept(this);
            node.continueBlock.accept(this);
        }
    }

    @Override
    public void visit(For3Node node) {
        if (!node.useNewScope) {
            node.initialization.accept(this);
            node.condition.accept(this);
            node.increment.accept(this);
            node.body.accept(this);
        }
    }

    // Implement other visit methods for nodes that can contain OperatorNodes
    @Override
    public void visit(NumberNode node) {
        // No action needed
    }

    @Override
    public void visit(IdentifierNode node) {
        // No action needed
    }

    @Override
    public void visit(BinaryOperatorNode node) {
        node.left.accept(this);
        node.right.accept(this);
    }

    @Override
    public void visit(IfNode node) {
        node.condition.accept(this);
        node.thenBranch.accept(this);
        if (node.elseBranch != null) {
            node.elseBranch.accept(this);
        }
    }

    @Override
    public void visit(SubroutineNode node) {
        node.block.accept(this);
    }

    @Override
    public void visit(TernaryOperatorNode node) {
        node.condition.accept(this);
        node.trueExpr.accept(this);
        node.falseExpr.accept(this);
    }

    @Override
    public void visit(StringNode node) {
        // No action needed
    }

    @Override
    public void visit(ListNode node) {
        for (Node element : node.elements) {
            element.accept(this);
        }
    }

    @Override
    public void visit(ArrayLiteralNode node) {
        for (Node element : node.elements) {
            element.accept(this);
        }
    }

    @Override
    public void visit(HashLiteralNode node) {
        for (Node element : node.elements) {
            element.accept(this);
        }
    }
}