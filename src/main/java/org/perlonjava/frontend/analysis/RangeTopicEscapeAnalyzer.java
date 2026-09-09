package org.perlonjava.frontend.analysis;

import org.perlonjava.frontend.astnode.*;

/** Conservative eligibility check for reuse of an implicit foreach topic cell. */
public final class RangeTopicEscapeAnalyzer {
    private RangeTopicEscapeAnalyzer() {}

    public static boolean bodyCannotRetainTopic(Node node) {
        if (node == null || node instanceof IdentifierNode || node instanceof NumberNode
                || node instanceof StringNode) return true;
        if (node instanceof SubroutineNode || node instanceof For1Node || node instanceof For3Node) return false;
        if (node instanceof BlockNode block) {
            for (Node child : block.elements) if (!bodyCannotRetainTopic(child)) return false;
            return true;
        }
        if (node instanceof ListNode list) {
            for (Node child : list.elements) if (!bodyCannotRetainTopic(child)) return false;
            return true;
        }
        if (node instanceof OperatorNode op) {
            // Keep this whitelist deliberately small.  Any operation that can
            // invoke user code, preserve regex state, or create a reference
            // must use the ordinary per-element range iterator.
            return ("$".equals(op.operator) || "my".equals(op.operator)
                    || "our".equals(op.operator) || "local".equals(op.operator)
                    || "+".equals(op.operator) || "-".equals(op.operator)
                    || "++".equals(op.operator) || "--".equals(op.operator)
                    || "!".equals(op.operator) || "~".equals(op.operator))
                    && bodyCannotRetainTopic(op.operand);
        }
        if (node instanceof BinaryOperatorNode binary) {
            // Calls, dereferences, regexes, and overloadable operators are
            // intentionally excluded.  These primitive operators operate on
            // values and cannot expose the topic cell's identity.
            return isPrimitiveValueOperator(binary.operator)
                    && bodyCannotRetainTopic(binary.left) && bodyCannotRetainTopic(binary.right);
        }
        if (node instanceof TernaryOperatorNode ternary) {
            return bodyCannotRetainTopic(ternary.condition)
                    && bodyCannotRetainTopic(ternary.trueExpr)
                    && bodyCannotRetainTopic(ternary.falseExpr);
        }
        return false;
    }

    private static boolean isPrimitiveValueOperator(String operator) {
        return switch (operator) {
            case "=", "+=", "-=", "*=", "/=", "%=", ".=",
                 "+", "-", "*", "/", "%", "**", ".",
                 "<<", ">>", "&", "|", "^",
                 "<", "<=", ">", ">=", "==", "!=", "<=>",
                 "eq", "ne", "lt", "le", "gt", "ge", "cmp",
                 "&&", "||", "//" -> true;
            default -> false;
        };
    }
}
