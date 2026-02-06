package org.perlonjava.astvisitor;

import org.perlonjava.astnode.*;

/**
 * Visitor that counts the maximum number of temporary local variables
 * that will be needed during bytecode emission.
 * 
 * This is used to pre-initialize the correct number of slots to avoid
 * VerifyError when slots are in TOP state.
 */
public class TempLocalCountVisitor implements Visitor {
    private int tempCount = 0;

    /**
     * Get the estimated number of temporary locals needed.
     *
     * @return The temp count
     */
    public int getMaxTempCount() {
        return tempCount;
    }

    /**
     * Reset the counter for reuse.
     */
    public void reset() {
        tempCount = 0;
    }

    private void countTemp() {
        tempCount++;
    }

    @Override
    public void visit(BinaryOperatorNode node) {
        // Logical operators (&&, ||, //) allocate a temp for left operand
        if (node.operator.equals("&&") || node.operator.equals("||") || node.operator.equals("//")) {
            countTemp();
        }
        // Dereference operations (->) allocate temps for complex access patterns
        if (node.operator.equals("->")) {
            countTemp();  // Conservative: 1 temp for dereference
        }
        if (node.left != null) node.left.accept(this);
        if (node.right != null) node.right.accept(this);
    }

    @Override
    public void visit(BlockNode node) {
        for (Node element : node.elements) {
            if (element != null) {
                element.accept(this);
            }
        }
    }

    @Override
    public void visit(For1Node node) {
        // For loops may allocate temp for array storage
        countTemp();
        if (node.variable != null) node.variable.accept(this);
        if (node.list != null) node.list.accept(this);
        if (node.body != null) node.body.accept(this);
    }

    @Override
    public void visit(For3Node node) {
        if (node.initialization != null) node.initialization.accept(this);
        if (node.condition != null) node.condition.accept(this);
        if (node.increment != null) node.increment.accept(this);
        if (node.body != null) node.body.accept(this);
    }

    @Override
    public void visit(ListNode node) {
        for (Node element : node.elements) {
            if (element != null) {
                element.accept(this);
            }
        }
    }

    @Override
    public void visit(OperatorNode node) {
        // local() allocates a temp for dynamic variable tracking
        if ("local".equals(node.operator)) {
            countTemp();
        }
        // eval block allocates temps for exception handling
        if ("eval".equals(node.operator)) {
            countTemp();
        }
        if (node.operand != null) {
            node.operand.accept(this);
        }
    }

    @Override
    public void visit(SubroutineNode node) {
        // Nested subroutines have their own EmitterMethodCreator context
        // and separate temp local space, so we don't need to count their temps
        // Don't recurse into the subroutine body
    }

    // Default implementations for other node types
    @Override
    public void visit(IdentifierNode node) {}

    @Override
    public void visit(NumberNode node) {}

    @Override
    public void visit(StringNode node) {}

    @Override
    public void visit(HashLiteralNode node) {
        for (Node element : node.elements) {
            if (element != null) {
                element.accept(this);
            }
        }
    }

    @Override
    public void visit(ArrayLiteralNode node) {
        for (Node element : node.elements) {
            if (element != null) {
                element.accept(this);
            }
        }
    }

    @Override
    public void visit(TernaryOperatorNode node) {
        if (node.condition != null) node.condition.accept(this);
        if (node.trueExpr != null) node.trueExpr.accept(this);
        if (node.falseExpr != null) node.falseExpr.accept(this);
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
    public void visit(LabelNode node) {
        // LabelNode only has a label string, no child nodes to visit
    }

    @Override
    public void visit(CompilerFlagNode node) {}

    @Override
    public void visit(FormatNode node) {}
}
