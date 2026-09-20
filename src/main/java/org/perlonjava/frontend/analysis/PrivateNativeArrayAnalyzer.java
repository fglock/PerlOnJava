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
import org.perlonjava.frontend.astnode.TryNode;
import org.perlonjava.frontend.astnode.IfNode;

import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
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
    /** A scalar occurrence proven to be a bounded native-array loop index. */
    public static final String PRIVATE_NATIVE_LOOP_INDEX = "privateNativeLoopIndex";
    /** A {@code $#array} read proven not to observe a private carrier. */
    public static final String PRIVATE_NATIVE_LAST_INDEX = "privateNativeLastIndex";

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
        if (node instanceof OperatorNode my && "my".equals(my.operator)) {
            String name = arrayName(my.operand);
            if (name != null) declarations.add(new ArrayDeclaration(name, my));
            return;
        }
        if (!(node instanceof BinaryOperatorNode assignment) || !"=".equals(assignment.operator)) return;
        if (!(assignment.left instanceof OperatorNode my) || !"my".equals(my.operator)) return;
        String name = arrayName(my.operand);
        if (name == null || !isFreshEmptyArray(assignment.right)) return;
        declarations.add(new ArrayDeclaration(name, my));
    }

    private static boolean isPrivateNativeLifetime(BlockNode block, String candidate) {
        Set<Integer> initializedIndexes = new LinkedHashSet<>();
        int initializedThrough = -1;
        boolean materialized = false;
        boolean hasNativeOperation = false;
        for (Node statement : block.elements) {
            LoopInitialization loopInitialization = !materialized && statement instanceof For1Node loop
                    ? nativeLoopInitialization(loop, candidate, initializedThrough,
                    maxInitializedIndex(initializedIndexes)) : null;
            if (loopInitialization != null) {
                hasNativeOperation = true;
                initializedThrough = Math.max(initializedThrough, loopInitialization.lastIndex());
                continue;
            }
            // Materialization is one-way: the ordinary lexical slot is now
            // authoritative, so later callbacks, closures, or dynamic code
            // cannot observe the retired carrier.
            if (materialized) continue;
            if (containsUnsupportedLifetimeBoundary(statement)) return false;
            if (isSafe(statement, candidate, true, initializedIndexes)) {
                hasNativeOperation |= directArrayElementIndex(
                        statement instanceof BinaryOperatorNode assignment ? assignment.left : null,
                        candidate) != null;
                continue;
            }
            // An ordinary operation that names this lexical is a one-way
            // materialization boundary. The JVM emitter installs the ordinary
            // RuntimeArray before evaluating it; all later operations use that
            // slot and never re-enter the carrier.
            if (mentionsArray(statement, candidate)) {
                materialized = true;
                continue;
            }
            return false;
        }
        return hasNativeOperation;
    }

    /**
     * Admit one deliberately small dynamic-index form before general loop
     * dataflow exists: {@code for my $i (0 .. N) { $array[$i] = EXPR }}.
     * The body may only initialize elements, never read them, so each loop
     * iteration is independent and no back-edge initialization fact is needed.
     */
    private static LoopInitialization nativeLoopInitialization(For1Node loop, String candidate,
                                                               int initializedThrough, int maxInitializedIndex) {
        String indexName = loopIndexName(loop.variable);
        LoopRange range = zeroBasedLoopRange(loop.list, candidate, initializedThrough, maxInitializedIndex);
        if (indexName == null || loop.continueBlock != null || range == null) return null;
        if (!(loop.body instanceof BlockNode body) || body.elements.isEmpty()) return null;
        List<OperatorNode> indexOccurrences = new ArrayList<>();
        Set<String> temporaryNames = new LinkedHashSet<>();
        boolean wroteCandidate = false;
        for (int position = 0; position < body.elements.size(); position++) {
            Node statement = body.elements.get(position);
            if (!(statement instanceof BinaryOperatorNode assignment) || !"=".equals(assignment.operator)) return null;
            String temporary = loopTemporaryName(assignment.left);
            if (temporary != null && !wroteCandidate) {
                if (temporaryNames.contains(temporary)
                        || !isLoopNativeWordExpression(assignment.right, candidate, indexName, range.lastIndex,
                        initializedThrough, temporaryNames, indexOccurrences)) return null;
                temporaryNames.add(temporary);
                continue;
            }
            if (position != body.elements.size() - 1 || wroteCandidate
                    || !isLoopArrayElement(assignment.left, candidate, indexName, indexOccurrences)
                    || !isLoopNativeWordExpression(assignment.right, candidate, indexName, range.lastIndex,
                    initializedThrough, temporaryNames, indexOccurrences)) return null;
            wroteCandidate = true;
        }
        if (!wroteCandidate) return null;
        for (OperatorNode occurrence : indexOccurrences) {
            occurrence.setAnnotation(PRIVATE_NATIVE_LOOP_INDEX, Boolean.TRUE);
        }
        if (range.privateLastIndex != null) {
            range.privateLastIndex.setAnnotation(PRIVATE_NATIVE_LAST_INDEX, Boolean.TRUE);
        }
        return new LoopInitialization(range.lastIndex);
    }

    private static String loopIndexName(Node node) {
        if (!(node instanceof OperatorNode my) || !"my".equals(my.operator)
                || !(my.operand instanceof OperatorNode scalar) || !"$".equals(scalar.operator)
                || !(scalar.operand instanceof IdentifierNode identifier)) return null;
        return identifier.name;
    }

    private static LoopRange zeroBasedLoopRange(Node node, String candidate, int initializedThrough,
                                                int maxInitializedIndex) {
        node = unwrapSingletonList(node);
        if (!(node instanceof BinaryOperatorNode range) || !"..".equals(range.operator)
                || literalIndex(range.left) == null || literalIndex(range.left) != 0) return null;
        Integer literalEnd = literalIndex(range.right);
        if (literalEnd != null) return new LoopRange(literalEnd, null);
        OperatorNode lastIndex = privateLastIndex(range.right, candidate);
        // A complete preceding 0..N initializer is the only fact that makes
        // $#array an initialized native-read boundary.  A direct write past N
        // could make $#array expose a hole, so reject it before codegen.
        if (lastIndex == null || initializedThrough < 0 || maxInitializedIndex > initializedThrough) return null;
        return new LoopRange(initializedThrough, lastIndex);
    }

    private static OperatorNode privateLastIndex(Node node, String candidate) {
        node = unwrapSingletonList(node);
        if (!(node instanceof OperatorNode lastIndex) || !"$#".equals(lastIndex.operator)) return null;
        Node operand = unwrapSingletonList(lastIndex.operand);
        if (operand instanceof IdentifierNode identifier && candidate.equals(identifier.name)) return lastIndex;
        if (operand instanceof OperatorNode array && "@".equals(array.operator)
                && array.operand instanceof IdentifierNode identifier && candidate.equals(identifier.name)) return lastIndex;
        return null;
    }

    private static int maxInitializedIndex(Set<Integer> initializedIndexes) {
        int max = -1;
        for (int index : initializedIndexes) max = Math.max(max, index);
        return max;
    }

    private static boolean isLoopArrayElement(Node node, String candidate, String indexName,
                                              List<OperatorNode> indexOccurrences) {
        node = unwrapSingletonList(node);
        if (!(node instanceof BinaryOperatorNode element) || !"[".equals(element.operator)
                || !(element.left instanceof OperatorNode scalar) || !"$".equals(scalar.operator)
                || !(scalar.operand instanceof IdentifierNode identifier) || !candidate.equals(identifier.name)) {
            return false;
        }
        OperatorNode index = loopIndex(element.right, indexName);
        if (index == null) return false;
        indexOccurrences.add(index);
        return true;
    }

    private static boolean isLoopNativeWordExpression(Node node, String candidate, String indexName, int lastIndex,
                                                      int initializedThrough,
                                                      Set<String> temporaryNames,
                                                      List<OperatorNode> indexOccurrences) {
        node = unwrapSingletonList(node);
        if (node instanceof NumberNode) return true;
        OperatorNode index = loopIndex(node, indexName);
        if (index != null) {
            indexOccurrences.add(index);
            return true;
        }
        if (isLoopTemporary(node, temporaryNames)) return true;
        if (initializedThrough >= lastIndex
                && isLoopArrayElement(node, candidate, indexName, indexOccurrences)) return true;
        // A distinct lexical array may supply a native-word input to a private
        // destination. The emitter guards that ordinary source at runtime and
        // materializes the destination before the unchanged path on a miss.
        // Keep the carrier's own reads on the initialized-prefix path above:
        // accepting them here would turn a hole into a zero word.
        if (isLoopOrdinaryArrayElement(node, candidate, indexName)) return true;
        if (!(node instanceof BinaryOperatorNode binary)) return false;
        return switch (binary.operator) {
            case "&", "|", "^" -> isLoopNativeWordExpression(binary.left, candidate, indexName, lastIndex,
                    initializedThrough, temporaryNames, indexOccurrences)
                    && isLoopNativeWordExpression(binary.right, candidate, indexName, lastIndex,
                    initializedThrough, temporaryNames, indexOccurrences);
            case "<<", ">>" -> isLoopNativeWordExpression(binary.left, candidate, indexName, lastIndex,
                    initializedThrough, temporaryNames, indexOccurrences)
                    && binary.right instanceof NumberNode;
            default -> false;
        };
    }

    private static String loopTemporaryName(Node node) {
        node = unwrapSingletonList(node);
        if (!(node instanceof OperatorNode my) || !"my".equals(my.operator)
                || !(my.operand instanceof OperatorNode scalar) || !"$".equals(scalar.operator)
                || !(scalar.operand instanceof IdentifierNode identifier)) return null;
        return identifier.name;
    }

    private static boolean isLoopTemporary(Node node, Set<String> temporaryNames) {
        node = unwrapSingletonList(node);
        return node instanceof OperatorNode scalar && "$".equals(scalar.operator)
                && scalar.operand instanceof IdentifierNode identifier
                && temporaryNames.contains(identifier.name);
    }

    private static OperatorNode loopIndex(Node node, String indexName) {
        node = unwrapSingletonList(node);
        if (node instanceof ArrayLiteralNode array && array.elements.size() == 1) {
            node = unwrapSingletonList(array.elements.getFirst());
        }
        if (node instanceof OperatorNode scalar && "$".equals(scalar.operator)
                && scalar.operand instanceof IdentifierNode identifier && indexName.equals(identifier.name)) {
            return scalar;
        }
        return null;
    }

    private static boolean isLoopOrdinaryArrayElement(Node node, String candidate, String indexName) {
        node = unwrapSingletonList(node);
        if (!(node instanceof BinaryOperatorNode element) || !"[".equals(element.operator)
                || !(element.left instanceof OperatorNode scalar) || !"$".equals(scalar.operator)
                || !(scalar.operand instanceof IdentifierNode identifier)
                || candidate.equals(identifier.name)) return false;
        return isLoopNativeIndexExpression(element.right, indexName);
    }

    /** Match the side-effect-free integer-index subset emitted by EmitVariable. */
    private static boolean isLoopNativeIndexExpression(Node node, String indexName) {
        node = unwrapSingletonList(node);
        if (node instanceof ArrayLiteralNode indexes && indexes.elements.size() == 1) {
            node = unwrapSingletonList(indexes.elements.getFirst());
        }
        if (literalIndex(node) != null || loopIndex(node, indexName) != null) return true;
        if (node instanceof OperatorNode array && "@".equals(array.operator)
                && array.operand instanceof IdentifierNode) return true;
        if (!(node instanceof BinaryOperatorNode binary)
                || !("+".equals(binary.operator) || "-".equals(binary.operator)
                || "%".equals(binary.operator))) return false;
        return isLoopNativeIndexExpression(binary.left, indexName)
                && isLoopNativeIndexExpression(binary.right, indexName);
    }

    private static boolean containsUnsupportedLifetimeBoundary(Node node) {
        if (node == null) return false;
        if (node instanceof SubroutineNode || node instanceof For1Node || node instanceof For3Node
                || node instanceof IfNode || node instanceof TryNode) return true;
        if (node instanceof OperatorNode operator) {
            return "eval".equals(operator.operator) || containsUnsupportedLifetimeBoundary(operator.operand);
        }
        if (node instanceof BinaryOperatorNode binary) {
            return containsUnsupportedLifetimeBoundary(binary.left)
                    || containsUnsupportedLifetimeBoundary(binary.right);
        }
        if (node instanceof ListNode list) {
            for (Node child : list.elements) if (containsUnsupportedLifetimeBoundary(child)) return true;
        }
        if (node instanceof ArrayLiteralNode array) {
            for (Node child : array.elements) if (containsUnsupportedLifetimeBoundary(child)) return true;
        }
        return false;
    }

    private static boolean isSafe(Node node, String candidate, boolean topLevel,
                                  Set<Integer> initializedIndexes) {
        if (node == null || node instanceof NumberNode || node instanceof IdentifierNode) return true;
        if (node instanceof SubroutineNode || node instanceof BlockNode && !topLevel) return false;
        if (node instanceof For1Node loop) {
            return isSafe(loop.variable, candidate, false, initializedIndexes)
                    && isSafe(loop.list, candidate, false, initializedIndexes)
                    && isSafe(loop.body, candidate, false, initializedIndexes)
                    && isSafe(loop.continueBlock, candidate, false, initializedIndexes);
        }
        if (node instanceof For3Node loop) {
            return isSafe(loop.initialization, candidate, false, initializedIndexes)
                    && isSafe(loop.condition, candidate, false, initializedIndexes)
                    && isSafe(loop.increment, candidate, false, initializedIndexes)
                    && isSafe(loop.body, candidate, false, initializedIndexes)
                    && isSafe(loop.continueBlock, candidate, false, initializedIndexes);
        }
        if (node instanceof OperatorNode operator) {
            if ("\\".equals(operator.operator) && mentionsArray(operator.operand, candidate)) return false;
            if ("my".equals(operator.operator)) {
                return arrayName(operator.operand) != null
                        || isSafe(operator.operand, candidate, false, initializedIndexes);
            }
            if ("@".equals(operator.operator)) return !candidate.equals(arrayName(operator));
            return isSafe(operator.operand, candidate, false, initializedIndexes);
        }
        if (node instanceof BinaryOperatorNode binary) {
            if ("(".equals(binary.operator)) return !mentionsArray(binary.right, candidate);
            if ("=".equals(binary.operator)) return isSafeAssignment(binary, candidate, initializedIndexes);
            if ("[".equals(binary.operator)) return !mentionsArray(binary, candidate);
            return isSafe(binary.left, candidate, false, initializedIndexes)
                    && isSafe(binary.right, candidate, false, initializedIndexes);
        }
        if (node instanceof ListNode list) {
            for (Node element : list.elements) {
                if (!isSafe(element, candidate, false, initializedIndexes)) return false;
            }
            return true;
        }
        if (node instanceof ArrayLiteralNode array) {
            for (Node element : array.elements) {
                if (!isSafe(element, candidate, false, initializedIndexes)) return false;
            }
            return true;
        }
        // Every remaining AST family can carry a callback, alias, dynamic
        // lookup, or unmodeled control-flow boundary. Reject by default.
        return !mentionsArray(node, candidate);
    }

    private static boolean isSafeAssignment(BinaryOperatorNode assignment, String candidate,
                                            Set<Integer> initializedIndexes) {
        if (isDeclarationOf(assignment.left, candidate)) return isFreshEmptyArray(assignment.right);
        Integer targetIndex = directArrayElementIndex(assignment.left, candidate);
        if (targetIndex != null) {
            if (!isNativeWordExpression(assignment.right, candidate, initializedIndexes)) return false;
            initializedIndexes.add(targetIndex);
            return true;
        }
        if (isWholeArray(assignment.left, candidate)) return false;
        return !mentionsArray(assignment.left, candidate) && !mentionsArray(assignment.right, candidate);
    }

    private static boolean isNativeWordExpression(Node node, String candidate,
                                                  Set<Integer> initializedIndexes) {
        node = unwrapSingletonList(node);
        if (node instanceof NumberNode) return true;
        Integer sourceIndex = directArrayElementIndex(node, candidate);
        if (sourceIndex != null) return initializedIndexes.contains(sourceIndex);
        if (!(node instanceof BinaryOperatorNode binary)) return false;
        return switch (binary.operator) {
            case "&", "|", "^" -> isNativeWordExpression(binary.left, candidate, initializedIndexes)
                    && isNativeWordExpression(binary.right, candidate, initializedIndexes);
            case "<<", ">>" -> isNativeWordExpression(binary.left, candidate, initializedIndexes)
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
        return directArrayElementIndex(node, candidate) != null;
    }

    private static Integer directArrayElementIndex(Node node, String candidate) {
        node = unwrapSingletonList(node);
        if (!(node instanceof BinaryOperatorNode element) || !"[".equals(element.operator)
                || !(element.left instanceof OperatorNode scalar) || !"$".equals(scalar.operator)
                || !(scalar.operand instanceof IdentifierNode identifier)
                || !candidate.equals(identifier.name)) {
            return null;
        }
        return literalIndex(element.right);
    }

    private static boolean isSafeIndex(Node node) {
        return literalIndex(node) != null;
    }

    private static Integer literalIndex(Node node) {
        node = unwrapSingletonList(node);
        if (node instanceof ArrayLiteralNode indexes && indexes.elements.size() == 1) {
            node = unwrapSingletonList(indexes.elements.getFirst());
        }
        if (!(node instanceof NumberNode number) || !number.value.matches("[0-9]+")) return null;
        try {
            return Integer.parseInt(number.value);
        } catch (NumberFormatException ignored) {
            return null;
        }
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
        return node instanceof ArrayLiteralNode array && array.elements.isEmpty()
                || node instanceof ListNode list && list.elements.isEmpty();
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

    private record LoopRange(int lastIndex, OperatorNode privateLastIndex) {
    }

    private record LoopInitialization(int lastIndex) {
    }
}
