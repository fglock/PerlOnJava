package org.perlonjava.frontend.analysis;

import org.perlonjava.frontend.astnode.*;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** Finds operations whose side effects require list evaluation even when results are discarded. */
public final class ListContextSideEffectDetector {
    private ListContextSideEffectDetector() {}

    /**
     * {@code readline} in an empty-target list assignment must consume the
     * complete input list even when the assignment's own value is discarded.
     */
    public static boolean containsReadline(Node root) {
        if (root == null) return false;
        Deque<Node> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            Node node = pending.pop();
            if (node instanceof SubroutineNode) continue;
            if (node instanceof BinaryOperatorNode binary) {
                if ("readline".equals(binary.operator)) return true;
                if (binary.left != null) pending.push(binary.left);
                if (binary.right != null) pending.push(binary.right);
            } else if (node instanceof OperatorNode operator) {
                if (operator.operand != null) pending.push(operator.operand);
            } else if (node instanceof BlockNode block) {
                pushAll(pending, block.elements);
            } else if (node instanceof ListNode list) {
                pushAll(pending, list.elements);
                if (list.handle != null) pending.push(list.handle);
            } else if (node instanceof IfNode conditional) {
                if (conditional.condition != null) pending.push(conditional.condition);
                if (conditional.thenBranch != null) pending.push(conditional.thenBranch);
                if (conditional.elseBranch != null) pending.push(conditional.elseBranch);
            } else if (node instanceof For1Node loop) {
                if (loop.variable != null) pending.push(loop.variable);
                if (loop.list != null) pending.push(loop.list);
                if (loop.body != null) pending.push(loop.body);
                if (loop.continueBlock != null) pending.push(loop.continueBlock);
            } else if (node instanceof For3Node loop) {
                if (loop.initialization != null) pending.push(loop.initialization);
                if (loop.condition != null) pending.push(loop.condition);
                if (loop.increment != null) pending.push(loop.increment);
                if (loop.body != null) pending.push(loop.body);
                if (loop.continueBlock != null) pending.push(loop.continueBlock);
            } else if (node instanceof TernaryOperatorNode ternary) {
                if (ternary.condition != null) pending.push(ternary.condition);
                if (ternary.trueExpr != null) pending.push(ternary.trueExpr);
                if (ternary.falseExpr != null) pending.push(ternary.falseExpr);
            } else if (node instanceof TryNode tryNode) {
                if (tryNode.tryBlock != null) pending.push(tryNode.tryBlock);
                if (tryNode.catchBlock != null) pending.push(tryNode.catchBlock);
                if (tryNode.finallyBlock != null) pending.push(tryNode.finallyBlock);
            } else if (node instanceof HashLiteralNode hash) {
                pushAll(pending, hash.elements);
            } else if (node instanceof ArrayLiteralNode array) {
                pushAll(pending, array.elements);
            }
        }
        return false;
    }

    private static void pushAll(Deque<Node> pending, List<Node> elements) {
        for (int index = elements.size() - 1; index >= 0; index--) {
            Node element = elements.get(index);
            if (element != null) pending.push(element);
        }
    }
}
