package org.perlonjava.astvisitor;

import org.perlonjava.astnode.*;

/**
 * Simple visitor that finds ANY control flow statement, ignoring loop depth.
 */
public class ControlFlowFinder implements Visitor {
    public boolean foundControlFlow = false;

    @Override
    public void visit(OperatorNode node) {
        if (foundControlFlow) return;
        if ("last".equals(node.operator) || "next".equals(node.operator) ||
                "redo".equals(node.operator) || "goto".equals(node.operator)) {
            foundControlFlow = true;
            return;
        }
        if (node.operand != null) {
            node.operand.accept(this);
        }
    }

    @Override
    public void visit(BlockNode node) {
        if (foundControlFlow) return;
        for (Node element : node.elements) {
            if (element != null) {
                element.accept(this);
                if (foundControlFlow) return;
            }
        }
    }

    @Override
    public void visit(ListNode node) {
        if (foundControlFlow) return;
        for (Node element : node.elements) {
            if (element != null) {
                element.accept(this);
                if (foundControlFlow) return;
            }
        }
    }

    @Override
    public void visit(BinaryOperatorNode node) {
        if (foundControlFlow) return;
        if (node.left != null) node.left.accept(this);
        if (!foundControlFlow && node.right != null) node.right.accept(this);
    }

    @Override
    public void visit(TernaryOperatorNode node) {
        if (foundControlFlow) return;
        if (node.condition != null) node.condition.accept(this);
        if (!foundControlFlow && node.trueExpr != null) node.trueExpr.accept(this);
        if (!foundControlFlow && node.falseExpr != null) node.falseExpr.accept(this);
    }

    @Override
    public void visit(IfNode node) {
        if (foundControlFlow) return;
        if (node.condition != null) node.condition.accept(this);
        if (!foundControlFlow && node.thenBranch != null) node.thenBranch.accept(this);
        if (!foundControlFlow && node.elseBranch != null) node.elseBranch.accept(this);
    }

    @Override
    public void visit(For1Node node) {
        // Traverse into loops to find ANY control flow for chunking purposes
        if (foundControlFlow) return;
        if (node.variable != null) node.variable.accept(this);
        if (!foundControlFlow && node.list != null) node.list.accept(this);
        if (!foundControlFlow && node.body != null) node.body.accept(this);
    }

    @Override
    public void visit(For3Node node) {
        // Traverse into loops to find ANY control flow for chunking purposes
        if (foundControlFlow) return;
        if (node.initialization != null) node.initialization.accept(this);
        if (!foundControlFlow && node.condition != null) node.condition.accept(this);
        if (!foundControlFlow && node.increment != null) node.increment.accept(this);
        if (!foundControlFlow && node.body != null) node.body.accept(this);
    }

    @Override
    public void visit(TryNode node) {
        if (foundControlFlow) return;
        if (node.tryBlock != null) node.tryBlock.accept(this);
        if (!foundControlFlow && node.catchBlock != null) node.catchBlock.accept(this);
        if (!foundControlFlow && node.finallyBlock != null) node.finallyBlock.accept(this);
    }

    @Override
    public void visit(HashLiteralNode node) {
        if (foundControlFlow) return;
        for (Node element : node.elements) {
            if (element != null) {
                element.accept(this);
                if (foundControlFlow) return;
            }
        }
    }

    @Override
    public void visit(ArrayLiteralNode node) {
        if (foundControlFlow) return;
        for (Node element : node.elements) {
            if (element != null) {
                element.accept(this);
                if (foundControlFlow) return;
            }
        }
    }

    @Override
    public void visit(SubroutineNode node) {
        // Do not traverse into subroutines - control flow inside is scoped to that subroutine
    }

    // Default implementations for leaf nodes
    @Override
    public void visit(IdentifierNode node) {
    }

    @Override
    public void visit(NumberNode node) {
    }

    @Override
    public void visit(StringNode node) {
    }

    @Override
    public void visit(LabelNode node) {
    }

    @Override
    public void visit(CompilerFlagNode node) {
    }

    @Override
    public void visit(FormatNode node) {
    }

    @Override
    public void visit(FormatLine node) {
    }
}
