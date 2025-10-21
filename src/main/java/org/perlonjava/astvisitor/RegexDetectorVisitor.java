package org.perlonjava.astvisitor;

import org.perlonjava.astnode.*;

/**
 * Visitor that detects regex operations (match, substitution, etc.)
 * Used to determine if a block needs to save/restore regex capture variables.
 */
public class RegexDetectorVisitor implements Visitor {
    private boolean hasRegexOperation = false;

    /**
     * Check if regex operations were detected during traversal.
     *
     * @return true if regex operations were found
     */
    public boolean hasRegexOperation() {
        return hasRegexOperation;
    }

    /**
     * Reset the detector for reuse.
     */
    public void reset() {
        hasRegexOperation = false;
    }

    /**
     * Static helper to check if a block contains regex operations.
     *
     * @param node the node to check
     * @return true if the node contains regex operations
     */
    public static boolean containsRegex(Node node) {
        if (node == null) {
            return false;
        }
        RegexDetectorVisitor visitor = new RegexDetectorVisitor();
        node.accept(visitor);
        return visitor.hasRegexOperation();
    }

    @Override
    public void visit(OperatorNode node) {
        // Check for regex operators: match, substitute, transliterate, quote regex
        // Note: These are the actual operator names in the AST, not the Perl syntax
        String op = node.operator;
        if ("matchRegex".equals(op) || "replaceRegex".equals(op) || 
            "tr".equals(op) || "y".equals(op) || "quoteRegex".equals(op)) {
            hasRegexOperation = true;
            return;
        }
        // Continue traversing
        if (node.operand != null) {
            node.operand.accept(this);
        }
    }

    @Override
    public void visit(BinaryOperatorNode node) {
        // Check for binding operators: =~, !~
        if ("=~".equals(node.operator) || "!~".equals(node.operator)) {
            hasRegexOperation = true;
            return;
        }
        // Continue traversing
        if (node.left != null) {
            node.left.accept(this);
        }
        if (!hasRegexOperation && node.right != null) {
            node.right.accept(this);
        }
    }

    @Override
    public void visit(BlockNode node) {
        for (Node element : node.elements) {
            if (element != null) {
                element.accept(this);
                if (hasRegexOperation) {
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
                if (hasRegexOperation) {
                    return; // Early exit once found
                }
            }
        }
    }

    @Override
    public void visit(TernaryOperatorNode node) {
        if (node.condition != null) {
            node.condition.accept(this);
        }
        if (!hasRegexOperation && node.trueExpr != null) {
            node.trueExpr.accept(this);
        }
        if (!hasRegexOperation && node.falseExpr != null) {
            node.falseExpr.accept(this);
        }
    }

    @Override
    public void visit(For1Node node) {
        if (node.variable != null) {
            node.variable.accept(this);
        }
        if (!hasRegexOperation && node.list != null) {
            node.list.accept(this);
        }
        if (!hasRegexOperation && node.body != null) {
            node.body.accept(this);
        }
    }

    @Override
    public void visit(For3Node node) {
        if (node.initialization != null) {
            node.initialization.accept(this);
        }
        if (!hasRegexOperation && node.condition != null) {
            node.condition.accept(this);
        }
        if (!hasRegexOperation && node.increment != null) {
            node.increment.accept(this);
        }
        if (!hasRegexOperation && node.body != null) {
            node.body.accept(this);
        }
    }

    @Override
    public void visit(IfNode node) {
        if (node.condition != null) {
            node.condition.accept(this);
        }
        if (!hasRegexOperation && node.thenBranch != null) {
            node.thenBranch.accept(this);
        }
        if (!hasRegexOperation && node.elseBranch != null) {
            node.elseBranch.accept(this);
        }
    }

    @Override
    public void visit(TryNode node) {
        if (node.tryBlock != null) {
            node.tryBlock.accept(this);
        }
        if (!hasRegexOperation && node.catchBlock != null) {
            node.catchBlock.accept(this);
        }
        if (!hasRegexOperation && node.finallyBlock != null) {
            node.finallyBlock.accept(this);
        }
    }

    // Simple implementations for other node types
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
                if (hasRegexOperation) return;
            }
        }
    }

    @Override
    public void visit(ArrayLiteralNode node) {
        for (Node element : node.elements) {
            if (element != null) {
                element.accept(this);
                if (hasRegexOperation) return;
            }
        }
    }

    @Override
    public void visit(SubroutineNode node) {
        // Don't traverse into nested subroutines - they have their own scope
    }

    @Override
    public void visit(LabelNode node) {}

    @Override
    public void visit(CompilerFlagNode node) {}

    @Override
    public void visit(FormatNode node) {}

    @Override
    public void visit(FormatLine node) {}
}

