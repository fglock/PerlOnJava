package org.perlonjava.frontend.analysis;

import org.perlonjava.frontend.astnode.*;

/**
 * A visitor that traverses the AST to find specific operator declarations.
 * Used primarily to locate 'local' and 'my' declarations within code blocks.
 * This visitor is utilized by Local.java for managing dynamic variable scopes
 * and by EmitLogicalOperator for handling variable declarations in logical operations.
 */
public class FindDeclarationVisitor implements Visitor {

    /**
     * Tracks whether the target operator has been found
     */
    private boolean containsLocalOperator = false;
    /**
     * Tracks whether a defer statement has been found
     */
    private boolean containsDefer = false;
    /**
     * The name of the operator we are searching for (e.g., "local", "my")
     */
    private String operatorName = null;
    /**
     * Stores the found operator node when located
     */
    private OperatorNode operatorNode = null;
    /**
     * When true, do not descend into BlockNode children.
     * Used by findOperator to avoid finding 'my' declarations inside
     * do-blocks/if-blocks that have their own scope.
     */
    private boolean stopAtBlockNode = false;

    /**
     * Static factory method to find a specific operator within an AST node.
     * Does not descend into BlockNode or SubroutineNode children, since
     * declarations inside those are scoped to their own blocks.
     *
     * @param blockNode    The AST node to search within
     * @param operatorName The name of the operator to find
     * @return The found OperatorNode, or null if not found
     */
    public static OperatorNode findOperator(Node blockNode, String operatorName) {
        FindDeclarationVisitor visitor = new FindDeclarationVisitor();
        visitor.operatorName = operatorName;
        visitor.stopAtBlockNode = true;
        blockNode.accept(visitor);
        return visitor.operatorNode;
    }

    /**
     * Static method to check if a block contains either local or defer statements.
     * This is used to determine if scope cleanup (popToLocalLevel) is needed.
     *
     * @param blockNode The AST node to search within
     * @return true if the block contains local or defer, false otherwise
     */
    public static boolean containsLocalOrDefer(Node blockNode) {
        FindDeclarationVisitor visitor = new FindDeclarationVisitor();
        visitor.operatorName = "local";
        blockNode.accept(visitor);
        return visitor.containsLocalOperator || visitor.containsDefer;
    }

    @Override
    public void visit(FormatLine node) {
        // Default implementation - no action needed for format lines
    }

    @Override
    public void visit(FormatNode node) {
        // Default implementation - no action needed for format nodes
    }

    /**
     * Visits a block node and searches through its elements.
     * Stops searching once an operator is found.
     * When stopAtBlockNode is true, does not descend (blocks create their own scope).
     */
    @Override
    public void visit(BlockNode node) {
        if (stopAtBlockNode) {
            return;
        }
        if (!containsLocalOperator) {
            for (Node element : node.elements) {
                if (element != null) {
                    element.accept(this);
                    if (containsLocalOperator) {
                        break;
                    }
                }
            }
        }
    }

    /**
     * Visits an operator node and checks if it matches the target operator.
     */
    @Override
    public void visit(OperatorNode node) {
        if (this.operatorName.equals(node.operator)) {
            containsLocalOperator = true;
            operatorNode = node;
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
        // Do NOT descend into subroutine bodies.
        // Variables declared with 'my' inside an anonymous sub are scoped to
        // that sub and must not be hoisted to the outer scope.
        // Similarly, 'local' and 'defer' inside a sub are handled by the
        // sub's own scope management, not the enclosing block.
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

    @Override
    public void visit(DeferNode node) {
        // Mark that we found a defer statement
        containsDefer = true;
        // Don't traverse into the defer block - those are compiled separately
    }

    @Override
    public void visit(LabelNode node) {
    }

    @Override
    public void visit(CompilerFlagNode node) {
        // CompilerFlagNode with warningScopeId > 0 emits a local assignment
        // for ${^WARNING_SCOPE}, which needs scope cleanup
        if (node.getWarningScopeId() > 0) {
            containsLocalOperator = true;
        }
    }
}
