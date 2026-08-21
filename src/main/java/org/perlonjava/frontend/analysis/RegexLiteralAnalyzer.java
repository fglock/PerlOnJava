package org.perlonjava.frontend.analysis;

import org.perlonjava.frontend.astnode.BinaryOperatorNode;
import org.perlonjava.frontend.astnode.ListNode;
import org.perlonjava.frontend.astnode.Node;
import org.perlonjava.frontend.astnode.OperatorNode;
import org.perlonjava.frontend.astnode.StringNode;
import org.perlonjava.runtime.operators.StringOperators;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

/** Compile-time evaluation of regex strings made entirely from literals. */
public final class RegexLiteralAnalyzer {
    private RegexLiteralAnalyzer() {}

    /**
     * Return the exact constant string, or {@code null} when runtime data is
     * required. In particular, {@code \Q...\E} is parsed as concatenation with
     * a quotemeta operator, but remains a compile-time regex literal in Perl.
     */
    public static String constantString(Node node) {
        if (node instanceof StringNode string) return string.value;
        if (node instanceof BinaryOperatorNode binary && ".".equals(binary.operator)) {
            String left = constantString(binary.left);
            String right = constantString(binary.right);
            return left == null || right == null ? null : left + right;
        }
        if (node instanceof OperatorNode operator && "quotemeta".equals(operator.operator)) {
            Node operand = operator.operand;
            if (operand instanceof ListNode list) {
                if (list.elements.size() != 1) return null;
                operand = list.elements.getFirst();
            }
            String value = constantString(operand);
            return value == null ? null
                    : StringOperators.quotemeta(new RuntimeScalar(value)).toString();
        }
        return null;
    }

    /**
     * Return a syntax-equivalent literal source, or {@code null} when runtime
     * interpolation is required. Parser-created callbacks are replaced by
     * same-width neutral constructs so later diagnostic offsets remain in the
     * original literal coordinate space.
     */
    public static String constantSyntaxString(Node node) {
        return constantTemplateString(node, true);
    }

    /** Return the complete literal source, including parser-created callbacks. */
    public static String constantSourceString(Node node) {
        return constantTemplateString(node, false);
    }

    /** Return the parser-owned callback slots in a constant regex template. */
    public static int callbackCount(Node node) {
        if (node instanceof OperatorNode operator) {
            if ("regexCallback".equals(operator.operator)) return 1;
            if ("regexTemplate".equals(operator.operator)
                    && operator.operand instanceof ListNode parts) {
                int count = 0;
                for (Node part : parts.elements) count += callbackCount(part);
                return count;
            }
        }
        if (node instanceof BinaryOperatorNode binary
                && ".".equals(binary.operator)) {
            return callbackCount(binary.left) + callbackCount(binary.right);
        }
        return 0;
    }

    private static String constantTemplateString(Node node, boolean maskCallbacks) {
        String constant = constantString(node);
        if (constant != null) return constant;
        if (node instanceof BinaryOperatorNode binary && ".".equals(binary.operator)) {
            String left = constantTemplateString(binary.left, maskCallbacks);
            String right = constantTemplateString(binary.right, maskCallbacks);
            return left == null || right == null ? null : left + right;
        }
        if (node instanceof OperatorNode operator && "regexTemplate".equals(operator.operator)
                && operator.operand instanceof ListNode parts) {
            StringBuilder source = new StringBuilder();
            for (Node part : parts.elements) {
                String value = constantTemplateString(part, maskCallbacks);
                if (value == null) return null;
                source.append(value);
            }
            return source.toString();
        }
        if (node instanceof OperatorNode operator && "regexCallback".equals(operator.operator)) {
            Object callbackSource = operator.getAnnotation("regexCallbackSource");
            if (!(callbackSource instanceof String source)) return null;
            if (!maskCallbacks) return source;
            Object kind = operator.getAnnotation("regexCallbackKind");
            String skeleton = "CONDITION".equals(kind) ? "?=)" : "(?:)";
            if (source.length() < skeleton.length()) return null;
            return skeleton.substring(0, skeleton.length() - 1)
                    + " ".repeat(source.length() - skeleton.length())
                    + skeleton.charAt(skeleton.length() - 1);
        }
        return null;
    }
}
