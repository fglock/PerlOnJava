package org.perlonjava.frontend.analysis;

import org.perlonjava.frontend.astnode.*;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

public class RegexUsageDetector {

    private static final java.util.Set<String> REGEX_OPERATORS =
            java.util.Set.of("matchRegex", "replaceRegex");
    private static final java.util.Set<String> REGEX_BINARY_OPERATORS =
            java.util.Set.of("=~", "!~", "split");

    public static boolean containsRegexOperation(Node root) {
        if (root == null) return false;
        Deque<Node> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            Node node = stack.pop();
            if (node == null) continue;
            if (node instanceof SubroutineNode) continue;
            if (node instanceof OperatorNode op) {
                if (REGEX_OPERATORS.contains(op.operator)) return true;
                if (op.operand != null) stack.push(op.operand);
            } else if (node instanceof BinaryOperatorNode bop) {
                if (REGEX_BINARY_OPERATORS.contains(bop.operator)) return true;
                if (bop.left != null) stack.push(bop.left);
                if (bop.right != null) stack.push(bop.right);
            } else if (node instanceof BlockNode bn) {
                pushAll(stack, bn.elements);
            } else if (node instanceof ListNode ln) {
                pushAll(stack, ln.elements);
                if (ln.handle != null) stack.push(ln.handle);
            } else if (node instanceof IfNode ifn) {
                if (ifn.condition != null) stack.push(ifn.condition);
                if (ifn.thenBranch != null) stack.push(ifn.thenBranch);
                if (ifn.elseBranch != null) stack.push(ifn.elseBranch);
            } else if (node instanceof For1Node f1) {
                if (f1.variable != null) stack.push(f1.variable);
                if (f1.list != null) stack.push(f1.list);
                if (f1.body != null) stack.push(f1.body);
                if (f1.continueBlock != null) stack.push(f1.continueBlock);
            } else if (node instanceof For3Node f3) {
                if (f3.initialization != null) stack.push(f3.initialization);
                if (f3.condition != null) stack.push(f3.condition);
                if (f3.increment != null) stack.push(f3.increment);
                if (f3.body != null) stack.push(f3.body);
                if (f3.continueBlock != null) stack.push(f3.continueBlock);
            } else if (node instanceof TernaryOperatorNode tern) {
                if (tern.condition != null) stack.push(tern.condition);
                if (tern.trueExpr != null) stack.push(tern.trueExpr);
                if (tern.falseExpr != null) stack.push(tern.falseExpr);
            } else if (node instanceof TryNode tryN) {
                if (tryN.tryBlock != null) stack.push(tryN.tryBlock);
                if (tryN.catchBlock != null) stack.push(tryN.catchBlock);
                if (tryN.finallyBlock != null) stack.push(tryN.finallyBlock);
            } else if (node instanceof HashLiteralNode hn) {
                pushAll(stack, hn.elements);
            } else if (node instanceof ArrayLiteralNode an) {
                pushAll(stack, an.elements);
            }
        }
        return false;
    }

    private static void pushAll(Deque<Node> stack, List<Node> elements) {
        for (int i = elements.size() - 1; i >= 0; i--) {
            Node e = elements.get(i);
            if (e != null) stack.push(e);
        }
    }
}
