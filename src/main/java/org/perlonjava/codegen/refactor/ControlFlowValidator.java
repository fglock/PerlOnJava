package org.perlonjava.codegen.refactor;

import org.perlonjava.astnode.*;
import org.perlonjava.astvisitor.ControlFlowDetectorVisitor;

import java.util.List;

/**
 * Validates control flow safety for refactoring operations.
 * <p>
 * Control flow statements (next, last, redo, goto) cannot be wrapped in closures
 * because closures create a new scope, breaking the loop context. A 'next' statement
 * inside a closure will fail with "Can't 'next' outside a loop block" even if the
 * closure is called from within a loop.
 * <p>
 * This validator checks if a list of nodes contains control flow that would be
 * unsafe to wrap in a closure, considering the target scope.
 *
 * @see ControlFlowDetectorVisitor
 */
public class ControlFlowValidator {

    private static final ControlFlowDetectorVisitor detector = new ControlFlowDetectorVisitor();

    /**
     * Check if a list of nodes contains unsafe control flow for refactoring.
     * <p>
     * Control flow is considered unsafe if it would break when wrapped in a closure:
     * <ul>
     *   <li>next/last/redo targeting an outer loop</li>
     *   <li>goto targeting an outer label</li>
     *   <li>Labels that are referenced from outside the node list</li>
     * </ul>
     *
     * @param nodes the list of nodes to check
     * @return true if the nodes contain unsafe control flow
     */
    public static boolean hasUnsafeControlFlow(List<Node> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return false;
        }

        detector.reset();
        for (Node node : nodes) {
            node.accept(detector);
            if (detector.hasUnsafeControlFlow()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if a single node contains unsafe control flow for refactoring.
     *
     * @param node the node to check
     * @return true if the node contains unsafe control flow
     */
    public static boolean hasUnsafeControlFlow(Node node) {
        if (node == null) {
            return false;
        }

        detector.reset();
        node.accept(detector);
        return detector.hasUnsafeControlFlow();
    }

    /**
     * Check if a node is a label node.
     * Labels are special because they define control flow targets.
     *
     * @param node the node to check
     * @return true if the node is a LabelNode
     */
    public static boolean isLabel(Node node) {
        return node instanceof LabelNode;
    }

    /**
     * Check if a node contains variable declarations (my, our, local).
     * Variable declarations affect scoping and may prevent safe refactoring
     * if subsequent code depends on those variables.
     *
     * @param node the node to check
     * @return true if the node contains variable declarations
     */
    public static boolean hasVariableDeclaration(Node node) {
        // Pattern 1: Direct declaration without assignment
        if (node instanceof OperatorNode op) {
            return "my".equals(op.operator) || "our".equals(op.operator) || "local".equals(op.operator);
        }

        // Pattern 2: Declaration with assignment
        if (node instanceof BinaryOperatorNode bin) {
            if ("=".equals(bin.operator) && bin.left instanceof OperatorNode left) {
                return "my".equals(left.operator) || "our".equals(left.operator) || "local".equals(left.operator);
            }
        }

        return false;
    }

    /**
     * Check if a node is a complete block/loop with its own scope.
     * Complete blocks can be safely extracted as units because they
     * already define their own scope boundaries.
     *
     * @param node the node to check
     * @return true if the node is a complete scoped structure
     */
    public static boolean isCompleteBlock(Node node) {
        return node instanceof BlockNode ||
                node instanceof For1Node ||
                node instanceof For3Node ||
                node instanceof IfNode ||
                node instanceof TryNode;
    }

    /**
     * Check if a block is a loop body.
     * Loop bodies contain valid control flow (next/last/redo) that should
     * not be refactored.
     *
     * @param node the node to check
     * @return true if the node is marked as a loop body
     */
    public static boolean isLoopBody(Node node) {
        if (node instanceof BlockNode block) {
            return block.isLoop;
        }
        return false;
    }
}
