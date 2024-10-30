package org.perlonjava.codegen;

import org.perlonjava.astnode.*;

public class DynamicVariableVisitor implements Visitor {
    private boolean containsLocalOperator = false;

    public static boolean containsLocalOperator(Node blockNode) {
        DynamicVariableVisitor visitor = new DynamicVariableVisitor();
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
        node.variable.accept(this);
        node.list.accept(this);
        node.body.accept(this);
        if (node.continueBlock != null) {
            node.continueBlock.accept(this);
        }
    }

    @Override
    public void visit(For3Node node) {
        if (node.initialization != null) {
            node.initialization.accept(this);
        }
        if (node.condition != null) {
            node.condition.accept(this);
        }
        if (node.increment != null) {
            node.increment.accept(this);
        }
        node.body.accept(this);
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

    @Override
    public void visit(TryNode node) {
        // Visit the try block
        if (node.tryBlock != null) {
            node.tryBlock.accept(this);
        }

        // Visit the catch block
        if (node.catchBlock != null) {
            node.catchBlock.accept(this);
        }

        // Visit the finally block, if it exists
        if (node.finallyBlock != null) {
            node.finallyBlock.accept(this);
        }
    }
}