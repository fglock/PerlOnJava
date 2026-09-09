package org.perlonjava.frontend.analysis;

import org.perlonjava.frontend.astnode.BinaryOperatorNode;
import org.perlonjava.frontend.astnode.BlockNode;
import org.perlonjava.frontend.astnode.For3Node;
import org.perlonjava.frontend.astnode.IdentifierNode;
import org.perlonjava.frontend.astnode.Node;
import org.perlonjava.frontend.astnode.NumberNode;
import org.perlonjava.frontend.astnode.OperatorNode;

import java.util.HashSet;
import java.util.Set;

/**
 * Identifies the deliberately small first primitive-numeric slice.
 *
 * <p>The annotation is intentionally conservative: it only covers a direct
 * assignment to a {@code my} scalar with an integer-literal initializer, where
 * the right hand side is one integer binary operation over similarly proven
 * lexicals and integer literals.  It never crosses a basic-block boundary and
 * does not claim that a scalar has a permanently primitive representation.
 * The emitter still installs a runtime type/taint guard and uses the ordinary
 * Perl operator if that guard cannot hold.</p>
 */
public final class NumericFlowAnalyzer {
    public static final String PRIMITIVE_INTEGER_ASSIGNMENT = "primitiveIntegerAssignment";

    private NumericFlowAnalyzer() {}

    public static void analyze(BlockNode block) {
        analyze(block, new HashSet<>());
    }

    private static void analyze(BlockNode block, Set<String> inheritedIntegerLexicals) {
        Set<String> integerLexicals = new HashSet<>(inheritedIntegerLexicals);
        for (Node statement : block.elements) {
            collectIntegerDeclarations(statement, integerLexicals);
        }
        for (Node statement : block.elements) {
            removeEscapingOrReassignedLexicals(statement, integerLexicals);
        }
        for (Node statement : block.elements) {
            annotate(statement, integerLexicals, false);
        }
    }

    private static void collectIntegerDeclarations(Node node, Set<String> integerLexicals) {
        if (node instanceof BinaryOperatorNode assignment && "=".equals(assignment.operator)
                && assignment.left instanceof OperatorNode declaration
                && "my".equals(declaration.operator)
                && scalarName(declaration.operand) != null
                && isIntegerLiteral(assignment.right)) {
            integerLexicals.add(scalarName(declaration.operand));
        }
    }

    private static void annotate(Node node, Set<String> integerLexicals, boolean insideLoop) {
        if (node instanceof For3Node loop) {
            annotate(loop.initialization, integerLexicals, true);
            annotate(loop.condition, integerLexicals, true);
            annotate(loop.increment, integerLexicals, true);
            if (loop.body instanceof BlockNode body) {
                analyze(body, integerLexicals);
            }
            if (loop.continueBlock instanceof BlockNode continuation) {
                analyze(continuation, integerLexicals);
            }
            return;
        }
        if (node instanceof BlockNode nested) {
            analyze(nested, integerLexicals);
            return;
        }
        if (insideLoop && node instanceof BinaryOperatorNode assignment && "=".equals(assignment.operator)
                && scalarName(assignment.left) != null
                && integerLexicals.contains(scalarName(assignment.left))
                && assignment.right instanceof BinaryOperatorNode expression
                && isSupportedOperation(expression.operator)
                && isIntegerOperand(expression.left, integerLexicals)
                && isIntegerOperand(expression.right, integerLexicals)) {
            assignment.setAnnotation(PRIMITIVE_INTEGER_ASSIGNMENT, expression.operator);
        }
    }

    /**
     * This first slice has no representation for an observable lexical cell.
     * Reject references, call arguments, and non-integer writes before any
     * code generation can select the primitive path.
     */
    private static void removeEscapingOrReassignedLexicals(Node node, Set<String> integerLexicals) {
        if (node == null) return;
        if (node instanceof OperatorNode operator) {
            if ("\\".equals(operator.operator)) {
                removeDirectScalar(operator.operand, integerLexicals);
            }
            removeEscapingOrReassignedLexicals(operator.operand, integerLexicals);
            return;
        }
        if (node instanceof BinaryOperatorNode binary) {
            if ("(".equals(binary.operator)) {
                removeDirectScalar(binary.right, integerLexicals);
            }
            if ("=".equals(binary.operator)) {
                String target = scalarName(binary.left);
                if (target != null && integerLexicals.contains(target)
                        && !isIntegerLiteral(binary.right)
                        && !(binary.right instanceof BinaryOperatorNode expression
                        && isSupportedOperation(expression.operator)
                        && isIntegerOperand(expression.left, integerLexicals)
                        && isIntegerOperand(expression.right, integerLexicals))) {
                    integerLexicals.remove(target);
                }
            }
            removeEscapingOrReassignedLexicals(binary.left, integerLexicals);
            removeEscapingOrReassignedLexicals(binary.right, integerLexicals);
            return;
        }
        if (node instanceof BlockNode block) {
            for (Node child : block.elements) removeEscapingOrReassignedLexicals(child, integerLexicals);
            return;
        }
        if (node instanceof For3Node loop) {
            removeEscapingOrReassignedLexicals(loop.initialization, integerLexicals);
            removeEscapingOrReassignedLexicals(loop.condition, integerLexicals);
            removeEscapingOrReassignedLexicals(loop.increment, integerLexicals);
            removeEscapingOrReassignedLexicals(loop.body, integerLexicals);
            removeEscapingOrReassignedLexicals(loop.continueBlock, integerLexicals);
        }
    }

    private static void removeDirectScalar(Node node, Set<String> integerLexicals) {
        String name = scalarName(node);
        if (name != null) integerLexicals.remove(name);
    }

    private static boolean isSupportedOperation(String operator) {
        return "+".equals(operator) || "-".equals(operator) || "*".equals(operator)
                || "%".equals(operator);
    }

    private static boolean isIntegerOperand(Node node, Set<String> integerLexicals) {
        String name = scalarName(node);
        return isIntegerLiteral(node) || name != null && integerLexicals.contains(name);
    }

    private static boolean isIntegerLiteral(Node node) {
        return node instanceof NumberNode number && number.value.matches("[+-]?\\d+");
    }

    private static String scalarName(Node node) {
        if (!(node instanceof OperatorNode scalar) || !"$".equals(scalar.operator)
                || !(scalar.operand instanceof IdentifierNode identifier)) {
            return null;
        }
        return identifier.name;
    }
}
