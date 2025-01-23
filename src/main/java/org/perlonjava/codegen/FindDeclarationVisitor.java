package org.perlonjava.codegen;

import org.perlonjava.astnode.*;

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
     * The name of the operator we are searching for (e.g., "local", "my")
     */
    private String operatorName = null;

    /**
     * Stores the found operator node when located
     */
    private OperatorNode operatorNode = null;

    /**
     * Static factory method to find a specific operator within an AST node.
     *
     * @param blockNode    The AST node to search within
     * @param operatorName The name of the operator to find
     * @return The found OperatorNode, or null if not found
     */
    public static OperatorNode findOperator(Node blockNode, String operatorName) {
        FindDeclarationVisitor visitor = new FindDeclarationVisitor();
        visitor.operatorName = operatorName;
        blockNode.accept(visitor);
        return visitor.operatorNode;
    }

    /**
     * Visits a block node and searches through its elements.
     * Stops searching once an operator is found.
     */
    @Override
    public void visit(BlockNode node) {
        if (!containsLocalOperator) {
            for (Node element : node.elements) {
                element.accept(this);
                if (containsLocalOperator) {
                    break;
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

    @Override
    public void visit(LabelNode node) {
    }
}
