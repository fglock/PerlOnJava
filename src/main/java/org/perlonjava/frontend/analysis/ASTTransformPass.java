package org.perlonjava.frontend.analysis;

import org.perlonjava.frontend.astnode.*;

/**
 * Base class for AST transformation passes in the shared transformer pipeline.
 * Each pass performs a specific analysis or transformation on the AST.
 *
 * <p>Subclasses override visit methods to implement their specific logic.
 * The default implementation visits all children, allowing passes to only
 * override the node types they care about.</p>
 *
 * <p>Passes are run in sequence by {@link ASTTransformer}. Each pass may:
 * <ul>
 *   <li>Annotate nodes with computed information</li>
 *   <li>Transform the AST structure (return modified nodes)</li>
 *   <li>Emit warnings or errors</li>
 * </ul></p>
 *
 * <p>Example usage:
 * <pre>
 * public class ContextResolver extends ASTTransformPass {
 *     {@literal @}Override
 *     public void visit(BinaryOperatorNode node) {
 *         // Propagate context to children
 *         if (node.operator.equals("=")) {
 *             propagateContext(node.right, getContextFromTarget(node.left));
 *         }
 *         visitChildren(node);
 *     }
 * }
 * </pre>
 * </p>
 */
public abstract class ASTTransformPass implements Visitor {

    /**
     * Transform the entire AST starting from the root node.
     * Override this method if the pass needs to do something special
     * before or after visiting the tree.
     *
     * @param root The root node of the AST
     */
    public void transform(Node root) {
        if (root != null) {
            root.accept(this);
        }
    }

    // ========== Default visitor implementations (visit children) ==========

    @Override
    public void visit(BinaryOperatorNode node) {
        visitChildren(node);
    }

    @Override
    public void visit(IdentifierNode node) {
        // Leaf node, no children to visit
    }

    @Override
    public void visit(BlockNode node) {
        visitChildren(node);
    }

    @Override
    public void visit(ListNode node) {
        visitChildren(node);
    }

    @Override
    public void visit(HashLiteralNode node) {
        visitChildrenOfHashLiteral(node);
    }

    @Override
    public void visit(ArrayLiteralNode node) {
        visitChildrenOfArrayLiteral(node);
    }

    @Override
    public void visit(NumberNode node) {
        // Leaf node, no children to visit
    }

    @Override
    public void visit(StringNode node) {
        // Leaf node, no children to visit
    }

    @Override
    public void visit(For1Node node) {
        visitChildren(node);
    }

    @Override
    public void visit(For3Node node) {
        visitChildren(node);
    }

    @Override
    public void visit(IfNode node) {
        visitChildren(node);
    }

    @Override
    public void visit(TernaryOperatorNode node) {
        visitChildren(node);
    }

    @Override
    public void visit(OperatorNode node) {
        visitChildren(node);
    }

    @Override
    public void visit(SubroutineNode node) {
        visitChildren(node);
    }

    @Override
    public void visit(TryNode node) {
        visitChildren(node);
    }

    @Override
    public void visit(LabelNode node) {
        // Leaf node, no children to visit
    }

    @Override
    public void visit(CompilerFlagNode node) {
        // Leaf node, no children to visit
    }

    @Override
    public void visit(FormatNode node) {
        // Format nodes are special, usually no transformation needed
    }

    @Override
    public void visit(FormatLine node) {
        // Format lines are special, usually no transformation needed
    }

    // ========== Helper methods for visiting children ==========

    /**
     * Visit all children of a BinaryOperatorNode.
     */
    protected void visitChildren(BinaryOperatorNode node) {
        if (node.left != null) {
            node.left.accept(this);
        }
        if (node.right != null) {
            node.right.accept(this);
        }
    }

    /**
     * Visit all children of a BlockNode.
     */
    protected void visitChildren(BlockNode node) {
        for (Node element : node.elements) {
            if (element != null) {
                element.accept(this);
            }
        }
    }

    /**
     * Visit all children of a ListNode (or subclasses ArrayLiteralNode, HashLiteralNode).
     */
    protected void visitChildren(ListNode node) {
        if (node.handle != null) {
            node.handle.accept(this);
        }
        for (Node element : node.elements) {
            if (element != null) {
                element.accept(this);
            }
        }
    }

    /**
     * Visit all children of a For1Node (foreach loop).
     */
    protected void visitChildren(For1Node node) {
        if (node.variable != null) {
            node.variable.accept(this);
        }
        if (node.list != null) {
            node.list.accept(this);
        }
        if (node.body != null) {
            node.body.accept(this);
        }
        if (node.continueBlock != null) {
            node.continueBlock.accept(this);
        }
    }

    /**
     * Visit all children of a For3Node (C-style for loop).
     */
    protected void visitChildren(For3Node node) {
        if (node.initialization != null) {
            node.initialization.accept(this);
        }
        if (node.condition != null) {
            node.condition.accept(this);
        }
        if (node.increment != null) {
            node.increment.accept(this);
        }
        if (node.body != null) {
            node.body.accept(this);
        }
        if (node.continueBlock != null) {
            node.continueBlock.accept(this);
        }
    }

    /**
     * Visit all children of an IfNode.
     */
    protected void visitChildren(IfNode node) {
        if (node.condition != null) {
            node.condition.accept(this);
        }
        if (node.thenBranch != null) {
            node.thenBranch.accept(this);
        }
        if (node.elseBranch != null) {
            node.elseBranch.accept(this);
        }
    }

    /**
     * Visit all children of a TernaryOperatorNode.
     */
    protected void visitChildren(TernaryOperatorNode node) {
        if (node.condition != null) {
            node.condition.accept(this);
        }
        if (node.trueExpr != null) {
            node.trueExpr.accept(this);
        }
        if (node.falseExpr != null) {
            node.falseExpr.accept(this);
        }
    }

    /**
     * Visit all children of an OperatorNode.
     */
    protected void visitChildren(OperatorNode node) {
        if (node.operand != null) {
            node.operand.accept(this);
        }
    }

    /**
     * Visit all children of a SubroutineNode.
     */
    protected void visitChildren(SubroutineNode node) {
        if (node.block != null) {
            node.block.accept(this);
        }
    }

    /**
     * Visit all children of a TryNode.
     */
    protected void visitChildren(TryNode node) {
        if (node.tryBlock != null) {
            node.tryBlock.accept(this);
        }
        if (node.catchParameter != null) {
            node.catchParameter.accept(this);
        }
        if (node.catchBlock != null) {
            node.catchBlock.accept(this);
        }
        if (node.finallyBlock != null) {
            node.finallyBlock.accept(this);
        }
    }

    /**
     * Visit all children of a HashLiteralNode.
     */
    protected void visitChildrenOfHashLiteral(HashLiteralNode node) {
        for (Node element : node.elements) {
            if (element != null) {
                element.accept(this);
            }
        }
    }

    /**
     * Visit all children of an ArrayLiteralNode.
     */
    protected void visitChildrenOfArrayLiteral(ArrayLiteralNode node) {
        for (Node element : node.elements) {
            if (element != null) {
                element.accept(this);
            }
        }
    }

    // ========== Utility methods for passes ==========

    /**
     * Gets the AbstractNode cast of a Node for accessing annotations.
     * Returns null if the node is not an AbstractNode.
     */
    protected AbstractNode asAbstractNode(Node node) {
        return node instanceof AbstractNode ? (AbstractNode) node : null;
    }

    /**
     * Convenience method to get or create the ASTAnnotation for a node.
     */
    protected ASTAnnotation getAnnotation(Node node) {
        AbstractNode abstractNode = asAbstractNode(node);
        return abstractNode != null ? abstractNode.getAstAnnotation() : null;
    }

    /**
     * Convenience method to set the cached context on a node.
     */
    protected void setContext(Node node, int context) {
        AbstractNode abstractNode = asAbstractNode(node);
        if (abstractNode != null) {
            abstractNode.setCachedContext(context);
        }
    }

    /**
     * Convenience method to set the cached lvalue status on a node.
     */
    protected void setIsLvalue(Node node, boolean isLvalue) {
        AbstractNode abstractNode = asAbstractNode(node);
        if (abstractNode != null) {
            abstractNode.setCachedIsLvalue(isLvalue);
        }
    }
}
