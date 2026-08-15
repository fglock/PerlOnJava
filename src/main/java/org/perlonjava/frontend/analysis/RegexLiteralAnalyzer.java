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
}
