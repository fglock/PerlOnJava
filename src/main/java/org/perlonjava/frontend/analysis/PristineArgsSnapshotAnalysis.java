package org.perlonjava.frontend.analysis;

import org.perlonjava.frontend.astnode.*;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** Determines whether a CV can safely share its live {@code @_} list snapshot. */
public final class PristineArgsSnapshotAnalysis {
    private static final Set<String> NON_ASSIGNMENT_OPERATORS =
            Set.of("==", "!=", "<=", ">=", "<=>", "=~", "!~", "=>");
    private static final ClassValue<Field[]> AST_FIELDS = new ClassValue<>() {
        @Override
        protected Field[] computeValue(Class<?> type) {
            return type.getFields();
        }
    };

    private PristineArgsSnapshotAnalysis() {}

    /**
     * Return true unless the CV is proven not to change the argument-list slots
     * or structure. Any unfamiliar use of {@code @_}, eval, or shared-args
     * control flow retains the normal pristine snapshot copy.
     */
    public static boolean requiresSnapshot(Node root) {
        return requiresSnapshot(root, null);
    }

    /** Analyze a named CV with its canonical name for direct self-tailcalls. */
    public static boolean requiresSnapshot(Node root, String currentSubroutineName) {
        if (root == null) return true;
        Deque<Node> pending = new ArrayDeque<>();
        Set<Node> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        pending.push(root);
        while (!pending.isEmpty()) {
            Node node = pending.pop();
            if (node == null || !seen.add(node)) continue;
            if (node != root && node instanceof SubroutineNode) continue;

            if (node instanceof OperatorNode operator) {
                if ("goto".equals(operator.operator)) {
                    if (isStaticLabelGoto(operator.operand)) continue;
                    if (!isSelfTailCall(operator.operand, currentSubroutineName)) {
                        return true;
                    }
                    // The implicit @_ is the tailcall argument list itself, not
                    // an independent escape or mutation of the current array.
                    continue;
                }
                if ("eval".equals(operator.operator)
                        || (("shift".equals(operator.operator)
                                || "pop".equals(operator.operator))
                            && (operator.operand == null
                                || isArgumentArray(operator.operand)))) {
                    return true;
                }

                // `my (...) = @_` copies values into pad cells and leaves the
                // argument array slots unchanged. The outer `my` node may wrap
                // the assignment, depending on how the parser lowered it.
                if ("my".equals(operator.operator)
                        && operator.operand instanceof BinaryOperatorNode assignment
                        && isAssignment(assignment.operator)
                        && isArgumentArray(assignment.right)
                        && !isArgumentMutationTarget(assignment.left)) {
                    pending.push(assignment.left);
                    continue;
                }
            }

            if (node instanceof BinaryOperatorNode binary) {
                if ("(".equals(binary.operator)
                        && binary.left instanceof OperatorNode codeRef
                        && "&".equals(codeRef.operator)) {
                    if (binary.getBooleanAnnotation("shareCallerArgs")) {
                        return true;
                    }
                    // A direct call with explicit arguments receives a fresh
                    // call list. The code reference itself does not expose @_;
                    // analyze its arguments for aliases and mutations.
                    pending.push(binary.right);
                    continue;
                }
                if ("==".equals(binary.operator)
                        && (isArgumentArray(binary.left) || isArgumentArray(binary.right))) {
                    // Reading the arity does not change the caller's argument
                    // list. Other uses remain fail-closed below.
                    pending.push(isArgumentArray(binary.left) ? binary.right : binary.left);
                    continue;
                }
                if ("[".equals(binary.operator)
                        && isScalarUnderscore(binary.left)) {
                    // Conservatively retain the copy for $_[n], including
                    // cases where a reference to the slot may escape.
                    return true;
                }
                if (isAssignment(binary.operator)) {
                    if (isArgumentMutationTarget(binary.left)) {
                        return true;
                    }
                    if (isArgumentArray(binary.right)
                            && isLexicalUnpackTarget(binary.left)) {
                        pending.push(binary.left);
                        continue;
                    }
                }
            }

            if (isArgumentArray(node)) {
                // Only the explicit lexical unpack and arity-read cases above
                // may inspect @_ without retaining a pristine copy.
                return true;
            }

            if (!pushChildren(node, pending)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSelfTailCall(Node operand, String currentSubroutineName) {
        if (currentSubroutineName == null) return false;
        Node codeRef = null;
        boolean sharesCurrentArgs = false;
        if (operand instanceof BinaryOperatorNode call
                && "(".equals(call.operator)
                && call.left instanceof OperatorNode reference
                && "&".equals(reference.operator)
                && call.getBooleanAnnotation("shareCallerArgs")) {
            codeRef = reference;
            sharesCurrentArgs = true;
        } else if (operand instanceof OperatorNode reference
                && "&".equals(reference.operator)
                && reference.getBooleanAnnotation("directNamedCall")) {
            // Some goto parse paths retain the bare CODE reference; goto
            // supplies the current @_ implicitly in that representation.
            codeRef = reference;
            sharesCurrentArgs = true;
        } else if (operand instanceof ListNode list && list.elements.size() == 1) {
            return isSelfTailCall(list.elements.getFirst(), currentSubroutineName);
        }
        if (!sharesCurrentArgs || !(codeRef instanceof OperatorNode reference)
                || !(reference.operand instanceof IdentifierNode target)) {
            return false;
        }
        String shortName = currentSubroutineName.substring(
                currentSubroutineName.lastIndexOf("::") + 2);
        return target.name.equals(shortName)
                || target.name.equals(currentSubroutineName);
    }

    private static boolean isStaticLabelGoto(Node operand) {
        if (operand instanceof ListNode list && list.elements.size() == 1) {
            operand = list.elements.getFirst();
        }
        return operand instanceof IdentifierNode || operand instanceof StringNode;
    }

    private static boolean isAssignment(String operator) {
        if (operator == null || !operator.endsWith("=")) return false;
        return !NON_ASSIGNMENT_OPERATORS.contains(operator);
    }

    private static boolean isArgumentArray(Node node) {
        return node instanceof OperatorNode operator
                && "@".equals(operator.operator)
                && operator.operand instanceof IdentifierNode identifier
                && "_".equals(identifier.name);
    }

    private static boolean isScalarUnderscore(Node node) {
        return node instanceof OperatorNode operator
                && "$".equals(operator.operator)
                && operator.operand instanceof IdentifierNode identifier
                && "_".equals(identifier.name);
    }

    private static boolean isArgumentMutationTarget(Node node) {
        if (isArgumentArray(node)) return true;
        if (node instanceof OperatorNode operator) {
            if ("$#".equals(operator.operator)
                    && operator.operand instanceof IdentifierNode identifier
                    && "_".equals(identifier.name)) return true;
            return ("my".equals(operator.operator)
                    || "local".equals(operator.operator)
                    || "state".equals(operator.operator)
                    || "our".equals(operator.operator))
                    && isArgumentMutationTarget(operator.operand);
        }
        if (node instanceof BinaryOperatorNode binary) {
            return "[".equals(binary.operator)
                    && isScalarUnderscore(binary.left);
        }
        if (node instanceof ListNode list) {
            return list.elements.stream().anyMatch(
                    PristineArgsSnapshotAnalysis::isArgumentMutationTarget);
        }
        return false;
    }

    private static boolean isLexicalUnpackTarget(Node node) {
        if (node instanceof OperatorNode operator && "my".equals(operator.operator)) {
            return true;
        }
        if (node instanceof ListNode list && !list.elements.isEmpty()) {
            return list.elements.stream().allMatch(element ->
                    element instanceof OperatorNode operator
                            && "my".equals(operator.operator));
        }
        return false;
    }

    /** Traverse public AST child fields, including hidden node annotations. */
    private static boolean pushChildren(Node node, Deque<Node> pending) {
        try {
            for (Field field : AST_FIELDS.get(node.getClass())) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                Object value = field.get(node);
                if (value instanceof Node child) {
                    pending.push(child);
                } else if (value instanceof Iterable<?> iterable) {
                    for (Object item : iterable) {
                        if (item instanceof Node child) pending.push(child);
                    }
                } else if (value instanceof Map<?, ?> map) {
                    for (Object item : map.values()) {
                        if (item instanceof Node child) pending.push(child);
                    }
                }
            }
        } catch (IllegalAccessException | RuntimeException e) {
            return false;
        }
        return true;
    }
}
