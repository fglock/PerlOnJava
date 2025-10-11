package org.perlonjava.astvisitor;

import org.perlonjava.astnode.*;

/**
 * Visitor that detects control flow statements (next, last, redo, goto)
 * that could potentially jump outside of a refactored block.
 */
public class ControlFlowDetectorVisitor implements Visitor {
    private boolean hasUnsafeControlFlow = false;

    /**
     * Check if unsafe control flow was detected during traversal.
     *
     * @return true if unsafe control flow statements were found
     */
    public boolean hasUnsafeControlFlow() {
        return hasUnsafeControlFlow;
    }

    /**
     * Reset the detector for reuse.
     */
    public void reset() {
        hasUnsafeControlFlow = false;
    }

    @Override
    public void visit(OperatorNode node) {
        // Check for control flow operators
        if ("next".equals(node.operator) || "last".equals(node.operator) ||
                "redo".equals(node.operator) || "goto".equals(node.operator)) {
            hasUnsafeControlFlow = true;
            return;
        }
        // Continue traversing
        if (node.operand != null) {
            node.operand.accept(this);
        }
    }

    @Override
    public void visit(BlockNode node) {
        for (Node element : node.elements) {
            if (element != null) {
                element.accept(this);
                if (hasUnsafeControlFlow) {
                    return; // Early exit once found
                }
            }
        }
    }

    @Override
    public void visit(ListNode node) {
        for (Node element : node.elements) {
            if (element != null) {
                element.accept(this);
                if (hasUnsafeControlFlow) {
                    return; // Early exit once found
                }
            }
        }
    }

    @Override
    public void visit(BinaryOperatorNode node) {
        if (node.left != null) {
            node.left.accept(this);
        }
        if (!hasUnsafeControlFlow && node.right != null) {
            node.right.accept(this);
        }
    }

    @Override
    public void visit(TernaryOperatorNode node) {
        if (node.condition != null) {
            node.condition.accept(this);
        }
        if (!hasUnsafeControlFlow && node.trueExpr != null) {
            node.trueExpr.accept(this);
        }
        if (!hasUnsafeControlFlow && node.falseExpr != null) {
            node.falseExpr.accept(this);
        }
    }

    @Override
    public void visit(IfNode node) {
        if (node.condition != null) {
            node.condition.accept(this);
        }
        if (!hasUnsafeControlFlow && node.thenBranch != null) {
            node.thenBranch.accept(this);
        }
        if (!hasUnsafeControlFlow && node.elseBranch != null) {
            node.elseBranch.accept(this);
        }
    }

    // For loops can contain control flow
    @Override
    public void visit(For1Node node) {
        if (node.variable != null) {
            node.variable.accept(this);
        }
        if (!hasUnsafeControlFlow && node.list != null) {
            node.list.accept(this);
        }
        if (!hasUnsafeControlFlow && node.body != null) {
            node.body.accept(this);
        }
    }

    @Override
    public void visit(For3Node node) {
        if (node.initialization != null) {
            node.initialization.accept(this);
        }
        if (!hasUnsafeControlFlow && node.condition != null) {
            node.condition.accept(this);
        }
        if (!hasUnsafeControlFlow && node.increment != null) {
            node.increment.accept(this);
        }
        if (!hasUnsafeControlFlow && node.body != null) {
            node.body.accept(this);
        }
    }

    @Override
    public void visit(TryNode node) {
        if (node.tryBlock != null) {
            node.tryBlock.accept(this);
        }
        if (!hasUnsafeControlFlow && node.catchBlock != null) {
            node.catchBlock.accept(this);
        }
        if (!hasUnsafeControlFlow && node.finallyBlock != null) {
            node.finallyBlock.accept(this);
        }
    }

    // Simple implementations for other node types
    @Override
    public void visit(IdentifierNode node) {
        // No control flow in identifiers
    }

    @Override
    public void visit(NumberNode node) {
        // No control flow in numbers
    }

    @Override
    public void visit(StringNode node) {
        // No control flow in strings
    }

    @Override
    public void visit(HashLiteralNode node) {
        for (Node element : node.elements) {
            if (element != null) {
                element.accept(this);
                if (hasUnsafeControlFlow) return;
            }
        }
    }

    @Override
    public void visit(ArrayLiteralNode node) {
        for (Node element : node.elements) {
            if (element != null) {
                element.accept(this);
                if (hasUnsafeControlFlow) return;
            }
        }
    }

    @Override
    public void visit(SubroutineNode node) {
        if (node.block != null) {
            node.block.accept(this);
        }
    }

    @Override
    public void visit(LabelNode node) {
        // Labels themselves don't have control flow
        // The labeled statement is handled separately in the AST
    }

    @Override
    public void visit(CompilerFlagNode node) {
        // Compiler flags don't have control flow
    }

    @Override
    public void visit(FormatNode node) {
        // Formats don't have control flow statements
    }

    @Override
    public void visit(FormatLine node) {
        // Format lines don't have control flow statements
    }
}
