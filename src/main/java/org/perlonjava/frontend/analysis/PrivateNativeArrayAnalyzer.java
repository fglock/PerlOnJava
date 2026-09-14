package org.perlonjava.frontend.analysis;

import org.perlonjava.frontend.astnode.ArrayLiteralNode;
import org.perlonjava.frontend.astnode.BinaryOperatorNode;
import org.perlonjava.frontend.astnode.BlockNode;
import org.perlonjava.frontend.astnode.For1Node;
import org.perlonjava.frontend.astnode.For3Node;
import org.perlonjava.frontend.astnode.IdentifierNode;
import org.perlonjava.frontend.astnode.ListNode;
import org.perlonjava.frontend.astnode.Node;
import org.perlonjava.frontend.astnode.NumberNode;
import org.perlonjava.frontend.astnode.OperatorNode;
import org.perlonjava.frontend.astnode.SubroutineNode;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * First, intentionally non-emitting phase of the private native-array proof.
 *
 * <p>This pass recognizes only declarations whose every use occurs in one
 * lexical block through a tiny direct read/write/copy surface. It is not a
 * general escape analysis yet: unsupported syntax rejects the declaration.
 * In particular, it never follows a name across a nested scope, so shadowing
 * cannot accidentally be treated as the outer declaration. The annotation is
 * currently evidence for unit tests and later code generation only; no emitter
 * consults it in this phase.</p>
 */
public final class PrivateNativeArrayAnalyzer {
    public static final String PRIVATE_NATIVE_ARRAY = "privateNativeArray";

    private PrivateNativeArrayAnalyzer() {
    }

    /** Analyze one lexical block without changing runtime behavior. */
    public static void analyze(BlockNode block) {
        if (block == null) return;
        Set<ArrayDeclaration> declarations = new LinkedHashSet<>();
        for (Node statement : block.elements) collectDeclaration(statement, declarations);
        for (ArrayDeclaration declaration : declarations) {
            if (isPrivateNativeLifetime(block, declaration.name)) {
                declaration.node.setAnnotation(PRIVATE_NATIVE_ARRAY, Boolean.TRUE);
            }
        }
    }

    private static void collectDeclaration(Node node, Set<ArrayDeclaration> declarations) {
        if (!(node instanceof BinaryOperatorNode assignment) || !"=".equals(assignment.operator)) return;
        if (!(assignment.left instanceof OperatorNode my) || !"my".equals(my.operator)) return;
        String name = arrayName(my.operand);
        if (name == null || !isFreshEmptyArray(assignment.right)) return;
        declarations.add(new ArrayDeclaration(name, my));
    }

    private static boolean isPrivateNativeLifetime(BlockNode block, String candidate) {
        for (Node statement : block.elements) {
            if (!isSafe(statement, candidate, true)) return false;
        }
        return true;
    }

    private static boolean isSafe(Node node, String candidate, boolean topLevel) {
        if (node == null || node instanceof NumberNode || node instanceof IdentifierNode) return true;
        if (node instanceof SubroutineNode || node instanceof BlockNode && !topLevel) return false;
        if (node instanceof For1Node loop) {
            return isSafe(loop.variable, candidate, false)
                    && isSafe(loop.list, candidate, false)
                    && isSafe(loop.body, candidate, false)
                    && isSafe(loop.continueBlock, candidate, false);
        }
        if (node instanceof For3Node loop) {
            return isSafe(loop.initialization, candidate, false)
                    && isSafe(loop.condition, candidate, false)
                    && isSafe(loop.increment, candidate, false)
                    && isSafe(loop.body, candidate, false)
                    && isSafe(loop.continueBlock, candidate, false);
        }
        if (node instanceof OperatorNode operator) {
            if ("\\".equals(operator.operator) && mentionsArray(operator.operand, candidate)) return false;
            if ("my".equals(operator.operator)) return arrayName(operator.operand) == null
                    && isSafe(operator.operand, candidate, false);
            if ("@".equals(operator.operator)) return candidate.equals(arrayName(operator));
            return isSafe(operator.operand, candidate, false);
        }
        if (node instanceof BinaryOperatorNode binary) {
            if ("(".equals(binary.operator)) return !mentionsArray(binary.right, candidate);
            if ("=".equals(binary.operator)) return isSafeAssignment(binary, candidate);
            return isSafe(binary.left, candidate, false) && isSafe(binary.right, candidate, false);
        }
        if (node instanceof ListNode list) {
            for (Node element : list.elements) if (!isSafe(element, candidate, false)) return false;
            return true;
        }
        if (node instanceof ArrayLiteralNode array) {
            for (Node element : array.elements) if (!isSafe(element, candidate, false)) return false;
            return true;
        }
        // Every remaining AST family can carry a callback, alias, dynamic
        // lookup, or unmodeled control-flow boundary. Reject by default.
        return !mentionsArray(node, candidate);
    }

    private static boolean isSafeAssignment(BinaryOperatorNode assignment, String candidate) {
        if (isDeclarationOf(assignment.left, candidate)) return isFreshEmptyArray(assignment.right);
        if (isDirectArrayElement(assignment.left, candidate)) return isNativeWordExpression(assignment.right, candidate);
        if (isWholeArray(assignment.left, candidate)) return isWholeArray(assignment.right, candidate);
        return !mentionsArray(assignment.left, candidate) && !mentionsArray(assignment.right, candidate);
    }

    private static boolean isNativeWordExpression(Node node, String candidate) {
        node = unwrapSingletonList(node);
        if (node instanceof NumberNode) return true;
        if (isDirectArrayElement(node, candidate)) return true;
        if (!(node instanceof BinaryOperatorNode binary)) return false;
        return switch (binary.operator) {
            case "&", "|", "^" -> isNativeWordExpression(binary.left, candidate)
                    && isNativeWordExpression(binary.right, candidate);
            case "<<", ">>" -> isNativeWordExpression(binary.left, candidate)
                    && binary.right instanceof NumberNode;
            default -> false;
        };
    }

    private static boolean isDeclarationOf(Node node, String candidate) {
        return node instanceof OperatorNode my && "my".equals(my.operator)
                && candidate.equals(arrayName(my.operand));
    }

    private static boolean isWholeArray(Node node, String candidate) {
        return candidate.equals(arrayName(node));
    }

    private static boolean isDirectArrayElement(Node node, String candidate) {
        node = unwrapSingletonList(node);
        return node instanceof BinaryOperatorNode element && "[".equals(element.operator)
                && element.left instanceof OperatorNode scalar && "$".equals(scalar.operator)
                && scalar.operand instanceof IdentifierNode identifier
                && candidate.equals(identifier.name)
                && isSafeIndex(element.right);
    }

    private static boolean isSafeIndex(Node node) {
        node = unwrapSingletonList(node);
        if (node instanceof ArrayLiteralNode indexes && indexes.elements.size() == 1) {
            node = unwrapSingletonList(indexes.elements.getFirst());
        }
        return node instanceof NumberNode;
    }

    private static boolean mentionsArray(Node node, String candidate) {
        if (node == null) return false;
        if (isArrayElementOf(node, candidate)) return true;
        if (candidate.equals(arrayName(node))) return true;
        if (node instanceof OperatorNode operator) return mentionsArray(operator.operand, candidate);
        if (node instanceof BinaryOperatorNode binary) {
            return mentionsArray(binary.left, candidate) || mentionsArray(binary.right, candidate);
        }
        if (node instanceof ListNode list) {
            for (Node element : list.elements) if (mentionsArray(element, candidate)) return true;
        }
        if (node instanceof ArrayLiteralNode array) {
            for (Node element : array.elements) if (mentionsArray(element, candidate)) return true;
        }
        if (node instanceof For1Node loop) {
            return mentionsArray(loop.variable, candidate) || mentionsArray(loop.list, candidate)
                    || mentionsArray(loop.body, candidate) || mentionsArray(loop.continueBlock, candidate);
        }
        if (node instanceof For3Node loop) {
            return mentionsArray(loop.initialization, candidate) || mentionsArray(loop.condition, candidate)
                    || mentionsArray(loop.increment, candidate) || mentionsArray(loop.body, candidate)
                    || mentionsArray(loop.continueBlock, candidate);
        }
        return false;
    }

    /**
     * An unsupported index is still an observation of its owning array. Keep
     * this separate from {@link #isDirectArrayElement(Node, String)}, whose
     * index restriction is only for the positive native-word subset.
     */
    private static boolean isArrayElementOf(Node node, String candidate) {
        node = unwrapSingletonList(node);
        return node instanceof BinaryOperatorNode element && "[".equals(element.operator)
                && element.left instanceof OperatorNode scalar && "$".equals(scalar.operator)
                && scalar.operand instanceof IdentifierNode identifier
                && candidate.equals(identifier.name);
    }

    private static boolean isFreshEmptyArray(Node node) {
        node = unwrapSingletonList(node);
        return node instanceof ArrayLiteralNode array && array.elements.isEmpty();
    }

    private static String arrayName(Node node) {
        node = unwrapSingletonList(node);
        if (!(node instanceof OperatorNode array) || !"@".equals(array.operator)
                || !(array.operand instanceof IdentifierNode identifier)) return null;
        return identifier.name;
    }

    private static Node unwrapSingletonList(Node node) {
        return node instanceof ListNode list && list.elements.size() == 1 ? list.elements.getFirst() : node;
    }

    private record ArrayDeclaration(String name, OperatorNode node) {
    }
}
