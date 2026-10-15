package org.perlonjava.backend.jvm;

import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.perlonjava.frontend.analysis.EmitterVisitor;
import org.perlonjava.frontend.astnode.BinaryOperatorNode;
import org.perlonjava.frontend.astnode.Node;
import org.perlonjava.frontend.astnode.NumberNode;
import org.perlonjava.runtime.perlmodule.Strict;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class EmitOperatorChained {
    public static final String[] CHAIN_COMPARISON_OP = new String[]{"<", ">", "<=", ">=", "lt", "gt", "le", "ge"};
    public static final String[] CHAIN_EQUALITY_OP = new String[]{"==", "!=", "===", "!==", "eq", "ne", "equ", "neu"};

    static public void emitChainedComparison(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        EmitterVisitor scalarVisitor =
                emitterVisitor.with(RuntimeContextType.SCALAR); // execute operands in scalar context

        // Collect all nodes in the chain from left to right
        List<Node> operands = new ArrayList<>();
        List<String> operators = new ArrayList<>();
        List<Boolean> integerComparison = new ArrayList<>();

        boolean isComparisonChain = isComparisonOperator(node.operator);
        boolean isEqualityChain = isEqualityOperator(node.operator);

        // Build the chain
        BinaryOperatorNode current = node;
        while (true) {
            operators.add(0, current.operator);
            Object integer = current.getAnnotation("useInteger");
            integerComparison.add(0, integer instanceof Boolean value
                    ? value
                    : emitterVisitor.ctx.symbolTable.isStrictOptionEnabled(
                            org.perlonjava.runtime.perlmodule.Strict.HINT_INTEGER));
            operands.add(0, current.right);

            if (current.left instanceof BinaryOperatorNode leftNode) {
                boolean nextIsComparison = isComparisonOperator(leftNode.operator);
                boolean nextIsEquality = isEqualityOperator(leftNode.operator);

                if ((isComparisonChain && !nextIsComparison) || (isEqualityChain && !nextIsEquality)) {
                    operands.add(0, current.left);
                    break;
                }
                current = leftNode;
            } else {
                operands.add(0, current.left);
                break;
            }
        }

        if (operators.size() == 1) {
            // Keep the common non-chain case compact; this path is used by
            // thousands of ordinary comparisons in large generated methods.
            if (tryEmitAdjustedNumericComparison(emitterVisitor, node)) {
                EmitOperator.handleVoidContext(emitterVisitor);
                return;
            }
            int leftSlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
            boolean pooledLeft = leftSlot >= 0;
            if (!pooledLeft) {
                leftSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
            }
            operands.get(0).accept(scalarVisitor);
            emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ASTORE, leftSlot);
            operands.get(1).accept(scalarVisitor);
            emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ALOAD, leftSlot);
            emitterVisitor.ctx.mv.visitInsn(Opcodes.SWAP);
            if (pooledLeft) {
                emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
            }
            BinaryOperatorNode comparison = new BinaryOperatorNode(
                    operators.getFirst(), operands.get(0), operands.get(1), node.tokenIndex);
            comparison.setAnnotation("useInteger", integerComparison.getFirst());
            EmitOperator.emitOperator(comparison, scalarVisitor);
            EmitOperator.handleVoidContext(emitterVisitor);
            return;
        }

        // Preserve each evaluated RHS for the next comparison. In particular,
        // the middle operand of a chain must be evaluated exactly once while
        // later operands remain short-circuited after a false comparison.
        int leftSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
        int rightSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
        operands.get(0).accept(scalarVisitor);
        emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ASTORE, leftSlot);

        Label endLabel = new Label();
        Label falseLabel = new Label();
        for (int i = 0; i < operators.size(); i++) {
            operands.get(i + 1).accept(scalarVisitor);
            emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ASTORE, rightSlot);
            emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ALOAD, leftSlot);
            emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ALOAD, rightSlot);

            BinaryOperatorNode compNode = new BinaryOperatorNode(
                    operators.get(i), operands.get(i), operands.get(i + 1), node.tokenIndex);
            compNode.setAnnotation("useInteger", integerComparison.get(i));
            EmitOperator.emitOperator(compNode, scalarVisitor);

            if (i + 1 < operators.size()) {
                emitterVisitor.ctx.mv.visitInsn(Opcodes.DUP);
                emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/runtimetypes/RuntimeBase", "getBoolean", "()Z", false);
                emitterVisitor.ctx.mv.visitJumpInsn(Opcodes.IFEQ, falseLabel);
                emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
                int nextLeftSlot = leftSlot;
                leftSlot = rightSlot;
                rightSlot = nextLeftSlot;
            }
        }

        if (operators.size() > 1) {
            emitterVisitor.ctx.mv.visitJumpInsn(Opcodes.GOTO, endLabel);
            emitterVisitor.ctx.mv.visitLabel(falseLabel);
            emitterVisitor.ctx.mv.visitLabel(endLabel);
        }

        EmitOperator.handleVoidContext(emitterVisitor);
    }

    private static boolean tryEmitAdjustedNumericComparison(
            EmitterVisitor emitterVisitor, BinaryOperatorNode comparison) {
        int comparisonCode = switch (comparison.operator) {
            case "<" -> 0;
            case ">" -> 1;
            case "<=" -> 2;
            case ">=" -> 3;
            default -> -1;
        };
        if (comparisonCode < 0 || !(comparison.right instanceof BinaryOperatorNode arithmetic)
                || !(arithmetic.operator.equals("+") || arithmetic.operator.equals("-"))
                || !(arithmetic.right instanceof NumberNode literal)) {
            return false;
        }

        Object integerAnnotation = arithmetic.getAnnotation("useInteger");
        boolean integerMode = integerAnnotation instanceof Boolean value
                ? value
                : emitterVisitor.ctx.symbolTable.isStrictOptionEnabled(Strict.HINT_INTEGER);
        if (integerMode) return false;

        Integer adjustment = parseIntegerAdjustment(literal.value);
        if (adjustment == null) return false;

        EmitterVisitor scalarVisitor = emitterVisitor.with(RuntimeContextType.SCALAR);
        comparison.left.accept(scalarVisitor);
        arithmetic.left.accept(scalarVisitor);
        emitterVisitor.ctx.mv.visitLdcInsn(adjustment);
        emitterVisitor.ctx.mv.visitInsn(arithmetic.operator.equals("-")
                ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        emitterVisitor.ctx.mv.visitLdcInsn(comparisonCode);
        boolean warnArithmetic = emitterVisitor.ctx.symbolTable.isWarningCategoryEnabled("uninitialized")
                || emitterVisitor.ctx.compilerOptions.warnFlag;
        emitterVisitor.ctx.mv.visitInsn(warnArithmetic ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/operators/CompareOperators",
                "compareAfterIntAdjustment",
                "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;"
                        + "Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;"
                        + "IZIZ)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
                false);
        return true;
    }

    private static Integer parseIntegerAdjustment(String literal) {
        try {
            return Integer.valueOf(literal.replace("_", ""));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static boolean isComparisonOperator(String operator) {
        return Arrays.asList(CHAIN_COMPARISON_OP).contains(operator);
    }

    static boolean isEqualityOperator(String operator) {
        return Arrays.asList(CHAIN_EQUALITY_OP).contains(operator);
    }
}
