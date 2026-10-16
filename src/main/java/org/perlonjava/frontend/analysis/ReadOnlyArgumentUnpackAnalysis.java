package org.perlonjava.frontend.analysis;

import org.perlonjava.frontend.astnode.BinaryOperatorNode;
import org.perlonjava.frontend.astnode.BlockNode;
import org.perlonjava.frontend.astnode.HashLiteralNode;
import org.perlonjava.frontend.astnode.IdentifierNode;
import org.perlonjava.frontend.astnode.ListNode;
import org.perlonjava.frontend.astnode.Node;
import org.perlonjava.frontend.astnode.NumberNode;
import org.perlonjava.frontend.astnode.OperatorNode;
import org.perlonjava.frontend.astnode.SubroutineNode;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Finds a leading {@code my ($self, $n, ...) = @_} whose lexicals are read-only. */
public final class ReadOnlyArgumentUnpackAnalysis {
    public record Candidate(
            BinaryOperatorNode assignment,
            Map<String, Integer> argumentIndices,
            int hashArgumentIndex,
            int integerArgumentIndex,
            List<String> hashKeys,
            boolean freshScalarReturn) {}

    private record Visit(Node node, Node parent) {}

    private static final Set<String> COMPARISON_OPERATORS = Set.of(
            "==", "!=", "<=", ">=", "<=>", "=~", "!~", "=>");
    private static final Set<String> INTEGER_OPERATORS = Set.of(
            "+", "-", "*", "/", "%", "**", "&", "|", "^", "<<", ">>");
    private static final ClassValue<Field[]> AST_FIELDS = new ClassValue<>() {
        @Override
        protected Field[] computeValue(Class<?> type) {
            return type.getFields();
        }
    };

    private ReadOnlyArgumentUnpackAnalysis() {}

    public static Candidate analyze(Node root) {
        if (!(root instanceof BlockNode block)
                || !Boolean.TRUE.equals(root.getAnnotation("blockIsSubroutine"))
                || block.elements.isEmpty()) {
            return null;
        }
        Node first = block.elements.getFirst();
        while (first instanceof ListNode list && list.elements.size() == 1) {
            first = list.elements.getFirst();
        }
        if (!(first instanceof BinaryOperatorNode assignment)
                || !"=".equals(assignment.operator)
                || !(assignment.left instanceof OperatorNode declaration)
                || !"my".equals(declaration.operator)
                || !(declaration.operand instanceof ListNode targets)
                || targets.elements.size() != 2
                || !isArgumentArray(assignment.right)) {
            return null;
        }

        LinkedHashMap<String, Integer> argumentIndices = new LinkedHashMap<>();
        for (int i = 0; i < targets.elements.size(); i++) {
            if (!(targets.elements.get(i) instanceof OperatorNode scalar)
                    || !"$".equals(scalar.operator)
                    || !(scalar.operand instanceof IdentifierNode identifier)) {
                return null;
            }
            String name = "$" + identifier.name;
            if (argumentIndices.putIfAbsent(name, i) != null) return null;
        }

        Map<String, Integer> observedUseKinds = new LinkedHashMap<>();
        Set<String> hashKeys = new LinkedHashSet<>();
        int returnStatementCount = 0;
        Node onlyReturnStatement = null;
        Deque<Visit> pending = new ArrayDeque<>();
        Set<Node> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        pending.push(new Visit(root, null));
        while (!pending.isEmpty()) {
            Visit visit = pending.pop();
            Node node = visit.node();
            if (node == null || !seen.add(node)) continue;
            if (node == assignment) continue;
            if (node instanceof SubroutineNode) return null;
            if (node instanceof OperatorNode operator && "return".equals(operator.operator)) {
                returnStatementCount++;
                onlyReturnStatement = node;
            }

            if (node instanceof BinaryOperatorNode binary) {
                // A called subroutine could mutate an aliased argument cell.
                if ("(".equals(binary.operator)) return null;
                if ("->".equals(binary.operator) && !isHashElementDereference(binary.right)) {
                    return null;
                }
                if (isAssignment(binary.operator)
                        && isParameterVariable(binary.left, argumentIndices)) {
                    return null;
                }
            }
            if (node instanceof OperatorNode operator
                    && ("\\".equals(operator.operator)
                        || "++".equals(operator.operator)
                        || "++postfix".equals(operator.operator)
                        || "--".equals(operator.operator)
                        || "--postfix".equals(operator.operator)
                        || "local".equals(operator.operator)
                        || "state".equals(operator.operator)
                        || "our".equals(operator.operator)
                        || "eval".equals(operator.operator)
                        || "goto".equals(operator.operator))) {
                return null;
            }
            if (node instanceof OperatorNode lexicalDeclaration
                    && ("my".equals(lexicalDeclaration.operator)
                        || "state".equals(lexicalDeclaration.operator)
                        || "our".equals(lexicalDeclaration.operator))
                    && declaresParameterName(lexicalDeclaration.operand, argumentIndices)) {
                return null;
            }

            if (node instanceof OperatorNode variable
                    && "$".equals(variable.operator)
                    && variable.operand instanceof IdentifierNode identifier) {
                String name = "$" + identifier.name;
                if (argumentIndices.containsKey(name)) {
                    int useKind = parameterUseKind(variable, visit.parent());
                    if (useKind == 0) return null;
                    if (useKind == 1) {
                        String key = literalHashKey(visit.parent());
                        if (key == null) return null;
                        hashKeys.add(key);
                    }
                    Integer previous = observedUseKinds.putIfAbsent(name, useKind);
                    if (previous != null && previous != useKind) return null;
                }
            }

            if (!pushChildren(node, pending)) return null;
        }

        if (observedUseKinds.size() != argumentIndices.size()
                || observedUseKinds.get(argumentIndices.keySet().iterator().next()) != 1) {
            return null;
        }
        int integerIndex = -1;
        for (Map.Entry<String, Integer> entry : argumentIndices.entrySet()) {
            if (entry.getValue() == 0) continue;
            if (!Integer.valueOf(2).equals(observedUseKinds.get(entry.getKey()))) return null;
            integerIndex = entry.getValue();
        }
        if (integerIndex < 0 || hashKeys.isEmpty() || hashKeys.size() > 2) return null;
        boolean freshScalarReturn = false;
        if (returnStatementCount == 1 && onlyReturnStatement instanceof OperatorNode returnNode
                && returnNode.operand instanceof ListNode returnValues
                && returnValues.elements.size() == 1
                && lastNonemptyStatement(block) == onlyReturnStatement) {
            String integerArgumentName = argumentNameAtIndex(argumentIndices, integerIndex);
            String hashArgumentName = argumentNameAtIndex(argumentIndices, 0);
            freshScalarReturn = integerArgumentName != null && hashArgumentName != null
                    && isFreshNumericExpression(returnValues.elements.getFirst(),
                            integerArgumentName, hashArgumentName, hashKeys);
        }
        return new Candidate(assignment, Map.copyOf(argumentIndices), 0,
                integerIndex, List.copyOf(hashKeys), freshScalarReturn);
    }

    private static int parameterUseKind(Node variable, Node parent) {
        if (!(parent instanceof BinaryOperatorNode binary)) return 0;
        if ("->".equals(binary.operator) && binary.left == variable
                && isHashElementDereference(binary.right)) {
            return 1;
        }
        if (INTEGER_OPERATORS.contains(binary.operator)
                && (binary.left == variable || binary.right == variable)) {
            return 2;
        }
        if ("+=".equals(binary.operator) && binary.right == variable) return 2;
        return 0;
    }

    private static boolean isHashElementDereference(Node node) {
        return node instanceof HashLiteralNode;
    }

    private static String literalHashKey(Node node) {
        if (!(node instanceof BinaryOperatorNode arrow)
                || !"->".equals(arrow.operator)
                || !(arrow.right instanceof HashLiteralNode hash)
                || hash.elements.size() != 1) {
            return null;
        }
        Node key = hash.elements.getFirst();
        if (key instanceof org.perlonjava.frontend.astnode.StringNode string) {
            return string.value;
        }
        if (key instanceof IdentifierNode identifier) {
            return identifier.name;
        }
        return null;
    }

    private static boolean isFreshNumericExpression(
            Node node, String integerArgumentName, String hashArgumentName, Set<String> hashKeys) {
        if (node instanceof NumberNode) return true;
        if (node instanceof OperatorNode variable
                && "$".equals(variable.operator)
                && variable.operand instanceof IdentifierNode identifier) {
            return integerArgumentName.equals("$" + identifier.name);
        }
        if (node instanceof BinaryOperatorNode binary && "+".equals(binary.operator)) {
            return isFreshNumericExpression(binary.left,
                    integerArgumentName, hashArgumentName, hashKeys)
                    && isFreshNumericExpression(binary.right,
                    integerArgumentName, hashArgumentName, hashKeys);
        }
        if (node instanceof BinaryOperatorNode arrow && "->".equals(arrow.operator)
                && arrow.left instanceof OperatorNode self
                && "$".equals(self.operator)
                && self.operand instanceof IdentifierNode identifier
                && hashArgumentName.equals("$" + identifier.name)) {
            String key = literalHashKey(arrow);
            return key != null && hashKeys.contains(key);
        }
        return false;
    }

    private static Node lastNonemptyStatement(BlockNode block) {
        for (int i = block.elements.size() - 1; i >= 0; i--) {
            Node element = block.elements.get(i);
            while (element instanceof ListNode list && list.elements.size() == 1) {
                element = list.elements.getFirst();
            }
            if (!(element instanceof ListNode list && list.elements.isEmpty())) return element;
        }
        return null;
    }

    private static String argumentNameAtIndex(Map<String, Integer> argumentIndices, int index) {
        for (Map.Entry<String, Integer> entry : argumentIndices.entrySet()) {
            if (entry.getValue() == index) return entry.getKey();
        }
        return null;
    }

    private static boolean isParameterVariable(Node node, Map<String, Integer> names) {
        return node instanceof OperatorNode variable
                && "$".equals(variable.operator)
                && variable.operand instanceof IdentifierNode identifier
                && names.containsKey("$" + identifier.name);
    }

    private static boolean declaresParameterName(Node node, Map<String, Integer> names) {
        if (node instanceof OperatorNode variable
                && "$".equals(variable.operator)
                && variable.operand instanceof IdentifierNode identifier) {
            return names.containsKey("$" + identifier.name);
        }
        if (node instanceof ListNode list) {
            return list.elements.stream().anyMatch(element -> declaresParameterName(element, names));
        }
        return false;
    }

    private static boolean isArgumentArray(Node node) {
        while (node instanceof ListNode list && list.elements.size() == 1) {
            node = list.elements.getFirst();
        }
        return node instanceof OperatorNode operator
                && "@".equals(operator.operator)
                && operator.operand instanceof IdentifierNode identifier
                && "_".equals(identifier.name);
    }

    private static boolean isAssignment(String operator) {
        return operator != null && operator.endsWith("=")
                && !COMPARISON_OPERATORS.contains(operator);
    }

    private static boolean pushChildren(Node node, Deque<Visit> pending) {
        try {
            for (Field field : AST_FIELDS.get(node.getClass())) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                Object value = field.get(node);
                if (value instanceof Node child) {
                    pending.push(new Visit(child, node));
                } else if (value instanceof Iterable<?> iterable) {
                    for (Object item : iterable) {
                        if (item instanceof Node child) pending.push(new Visit(child, node));
                    }
                } else if (value instanceof Map<?, ?> map) {
                    for (Object item : map.values()) {
                        if (item instanceof Node child) pending.push(new Visit(child, node));
                    }
                }
            }
        } catch (IllegalAccessException | RuntimeException e) {
            return false;
        }
        return true;
    }
}
