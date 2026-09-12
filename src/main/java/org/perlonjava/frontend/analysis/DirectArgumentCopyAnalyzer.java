package org.perlonjava.frontend.analysis;

import org.perlonjava.frontend.astnode.*;

import java.util.HashSet;
import java.util.Set;

/** Conservative proof for borrowing immediate @_ copies in JVM-only code. */
public final class DirectArgumentCopyAnalyzer {
    /** Annotation placed on the proven {@code my ($x, ...) = @_} declaration. */
    public static final String ELIGIBLE_UNPACK = "directArgumentCopyEligible";

    private DirectArgumentCopyAnalyzer() {}

    public static boolean bodyCannotObserveCopyCells(Node block) {
        if (!(block instanceof BlockNode body) || body.elements == null
                || body.elements.size() < 2) return false;
        Set<String> names = unpackNames(body.elements.getFirst());
        if (names == null) return false;
        for (int i = 1; i < body.elements.size(); i++) {
            if (!safeUse(body.elements.get(i), names, false)) return false;
        }
        return true;
    }

    /**
     * Marks the immediate unpack after proving the complete body cannot expose
     * the independent lexical cells normally created for the copies.
     */
    public static boolean markEligibleUnpack(Node block) {
        if (!bodyCannotObserveCopyCells(block)) return false;
        BlockNode body = (BlockNode) block;
        BinaryOperatorNode assignment = (BinaryOperatorNode) body.elements.getFirst();
        OperatorNode declaration = (OperatorNode) assignment.left;
        if (declaration.annotations != null && !declaration.annotations.isEmpty()) return false;
        declaration.setAnnotation(ELIGIBLE_UNPACK, true);
        return true;
    }

    private static Set<String> unpackNames(Node node) {
        if (!(node instanceof BinaryOperatorNode assignment) || !"=".equals(assignment.operator)
                || !(assignment.left instanceof OperatorNode declaration)
                || !"my".equals(declaration.operator)
                || !(declaration.operand instanceof ListNode list)
                || !(assignment.right instanceof OperatorNode args) || !"@".equals(args.operator)
                || !(args.operand instanceof IdentifierNode id) || !"_".equals(id.name)) return null;
        Set<String> names = new HashSet<>();
        for (Node target : list.elements) {
            if (!(target instanceof OperatorNode scalar) || !"$".equals(scalar.operator)
                    || !(scalar.operand instanceof IdentifierNode name) || !names.add(name.name)) return null;
        }
        return names.isEmpty() ? null : names;
    }

    private static boolean safeUse(Node node, Set<String> names, boolean lvalue) {
        if (node == null || node instanceof NumberNode || node instanceof StringNode
                || node instanceof IdentifierNode) return true;
        if (node instanceof SubroutineNode || node instanceof For1Node || node instanceof For3Node) return false;
        if (node instanceof BlockNode block) {
            for (Node child : block.elements) if (!safeUse(child, names, false)) return false;
            return true;
        }
        if (node instanceof ListNode list) {
            for (Node child : list.elements) if (!safeUse(child, names, false)) return false;
            return true;
        }
        if (node instanceof OperatorNode op) {
            if ("\\".equals(op.operator) || "@".equals(op.operator)
                    || "eval".equals(op.operator) || "local".equals(op.operator)) return false;
            if ("$".equals(op.operator) && op.operand instanceof IdentifierNode id
                    && names.contains(id.name)) return !lvalue;
            return ("return".equals(op.operator) || "$".equals(op.operator)
                    || "scalar".equals(op.operator)) && safeUse(op.operand, names, false);
        }
        if (node instanceof BinaryOperatorNode binary) {
            if ("(".equals(binary.operator)) return false; // any call may expose a cell
            if ("=".equals(binary.operator) || "+=".equals(binary.operator)
                    || "-=".equals(binary.operator) || ".=".equals(binary.operator)) {
                return safeUse(binary.left, names, true) && safeUse(binary.right, names, false);
            }
            return switch (binary.operator) {
                case "+", "-", "*", "/", "%", "->", "{", "[" ->
                        safeUse(binary.left, names, false) && safeUse(binary.right, names, false);
                default -> false;
            };
        }
        return false;
    }
}
