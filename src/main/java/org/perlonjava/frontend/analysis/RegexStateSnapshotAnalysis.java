package org.perlonjava.frontend.analysis;

import org.perlonjava.frontend.astnode.BinaryOperatorNode;
import org.perlonjava.frontend.astnode.BlockNode;
import org.perlonjava.frontend.astnode.HashLiteralNode;
import org.perlonjava.frontend.astnode.IdentifierNode;
import org.perlonjava.frontend.astnode.ListNode;
import org.perlonjava.frontend.astnode.Node;
import org.perlonjava.frontend.astnode.NumberNode;
import org.perlonjava.frontend.astnode.OperatorNode;
import org.perlonjava.frontend.astnode.StringNode;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Conservatively identifies simple CVs that cannot change Perl match state. */
public final class RegexStateSnapshotAnalysis {
    private static final Set<String> SAFE_BINARY_OPERATORS = Set.of("=", "->", "+=", "+");
    private static final Set<String> SAFE_UNARY_OPERATORS = Set.of("$", "@", "my", "return");
    private static final ClassValue<Field[]> AST_FIELDS = new ClassValue<>() {
        @Override
        protected Field[] computeValue(Class<?> type) {
            return type.getFields();
        }
    };

    private RegexStateSnapshotAnalysis() {}

    /**
     * A no-snapshot result is limited to fresh-scalar read-only argument
     * candidates composed only of lexical unpacking, plain hash access, and
     * integer arithmetic. Calls, regex nodes, and unknown AST shapes fail
     * closed because they may change dynamically scoped match variables.
     */
    public static boolean canSkipSnapshot(Node root) {
        ReadOnlyArgumentUnpackAnalysis.Candidate candidate =
                ReadOnlyArgumentUnpackAnalysis.analyze(root);
        if (candidate == null || !candidate.freshScalarReturn()
                || RegexUsageDetector.containsRegexOperation(root)) {
            return false;
        }

        Deque<Node> pending = new ArrayDeque<>();
        Set<Node> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        pending.push(root);
        while (!pending.isEmpty()) {
            Node node = pending.pop();
            if (node == null || !seen.add(node) || !isSafeNode(node)) return false;
            if (node instanceof BinaryOperatorNode binary
                    && !SAFE_BINARY_OPERATORS.contains(binary.operator)) return false;
            if (node instanceof OperatorNode operator) {
                if (!SAFE_UNARY_OPERATORS.contains(operator.operator)) return false;
                if ("@".equals(operator.operator)
                        && (!(operator.operand instanceof IdentifierNode identifier)
                            || !"_".equals(identifier.name))) return false;
            }
            if (!pushChildren(node, pending)) return false;
        }
        return true;
    }

    private static boolean isSafeNode(Node node) {
        return node instanceof BlockNode
                || node instanceof ListNode
                || node instanceof BinaryOperatorNode
                || node instanceof OperatorNode
                || node instanceof IdentifierNode
                || node instanceof NumberNode
                || node instanceof StringNode
                || node instanceof HashLiteralNode;
    }

    private static boolean pushChildren(Node node, Deque<Node> pending) {
        for (Field field : AST_FIELDS.get(node.getClass())) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            try {
                Object value = field.get(node);
                if (value instanceof Node child) {
                    pending.push(child);
                } else if (value instanceof List<?> children) {
                    for (int i = children.size() - 1; i >= 0; i--) {
                        Object child = children.get(i);
                        if (child instanceof Node childNode) pending.push(childNode);
                    }
                } else if (value instanceof Iterable<?> children) {
                    for (Object child : children) {
                        if (child instanceof Node childNode) pending.push(childNode);
                    }
                } else if (value instanceof Map<?, ?> children) {
                    for (Map.Entry<?, ?> entry : children.entrySet()) {
                        if (entry.getValue() instanceof Node childNode) pending.push(childNode);
                        if (entry.getKey() instanceof Node childNode) pending.push(childNode);
                    }
                } else if (value != null && value.getClass().isArray()
                        && Node.class.isAssignableFrom(value.getClass().getComponentType())) {
                    for (int i = Array.getLength(value) - 1; i >= 0; i--) {
                        Object child = Array.get(value, i);
                        if (child instanceof Node childNode) pending.push(childNode);
                    }
                }
            } catch (IllegalAccessException | RuntimeException inaccessible) {
                return false;
            }
        }
        return true;
    }
}
