package org.perlonjava.frontend.analysis;

import org.perlonjava.frontend.astnode.BinaryOperatorNode;
import org.perlonjava.frontend.astnode.AbstractNode;
import org.perlonjava.frontend.astnode.IdentifierNode;
import org.perlonjava.frontend.astnode.ListNode;
import org.perlonjava.frontend.astnode.Node;
import org.perlonjava.frontend.astnode.OperatorNode;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;

/** Marks direct calls whose scalar result is immediately consumed as boolean. */
public final class BooleanCallContext {
    private static final String ANNOTATION = "wantBooleanContext";

    private BooleanCallContext() {}

    public static void markDirectCall(Node expression) {
        if (expression instanceof ListNode list && list.elements.size() == 1) {
            expression = list.elements.getFirst();
        }
        if (expression instanceof BinaryOperatorNode binary) {
            if ("(".equals(binary.operator)
                    || "->".equals(binary.operator) && isMethodCall(binary)) {
                binary.setAnnotation(ANNOTATION, true);
            }
        } else if (expression instanceof OperatorNode operator
                && "&".equals(operator.operator)
                && operator.getBooleanAnnotation("directNamedCall")) {
            operator.setAnnotation(ANNOTATION, true);
        }
    }

    public static int callContext(Node call, int ordinaryContext) {
        return call instanceof AbstractNode abstractNode
                && abstractNode.getBooleanAnnotation(ANNOTATION)
                ? RuntimeContextType.BOOLEAN : ordinaryContext;
    }

    private static boolean isMethodCall(BinaryOperatorNode arrow) {
        return arrow.right instanceof IdentifierNode
                || arrow.right instanceof OperatorNode
                || arrow.right instanceof BinaryOperatorNode right
                        && "(".equals(right.operator);
    }

}
