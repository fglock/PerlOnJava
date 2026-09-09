package org.perlonjava.backend.jvm;

import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.perlonjava.frontend.analysis.EmitterVisitor;
import org.perlonjava.frontend.astnode.BinaryOperatorNode;
import org.perlonjava.frontend.astnode.Node;
import org.perlonjava.frontend.astnode.OperatorNode;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class EmitOperatorChained {
    public static final String[] CHAIN_COMPARISON_OP = new String[]{"<", ">", "<=", ">=", "lt", "gt", "le", "ge"};
    public static final String[] CHAIN_EQUALITY_OP = new String[]{"==", "!=", "===", "!==", "eq", "ne", "equ", "neu"};

    static public void emitChainedComparison(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        EmitterVisitor scalarVisitor = emitterVisitor.with(RuntimeContextType.SCALAR);
        List<Node> operands = new ArrayList<>();
        List<String> operators = new ArrayList<>();
        boolean isComparisonChain = isComparisonOperator(node.operator);
        boolean isEqualityChain = isEqualityOperator(node.operator);

        BinaryOperatorNode current = node;
        while (true) {
            operators.add(0, current.operator);
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
            int leftSlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
            boolean pooledLeft = leftSlot >= 0;
            if (!pooledLeft) {
                leftSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
            }
            emitComparisonOperand(emitterVisitor, scalarVisitor, operands.get(0));
            emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ASTORE, leftSlot);
            emitComparisonOperand(emitterVisitor, scalarVisitor, operands.get(1));
            emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ALOAD, leftSlot);
            emitterVisitor.ctx.mv.visitInsn(Opcodes.SWAP);
            if (pooledLeft) {
                emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
            }
            EmitOperator.emitOperator(new BinaryOperatorNode(
                    operators.getFirst(), operands.get(0), operands.get(1), node.tokenIndex), scalarVisitor);
            EmitOperator.handleVoidContext(emitterVisitor);
            return;
        }

        int leftSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
        int rightSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
        emitComparisonOperand(emitterVisitor, scalarVisitor, operands.get(0));
        emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ASTORE, leftSlot);

        Label endLabel = new Label();
        Label falseLabel = new Label();
        for (int i = 0; i < operators.size(); i++) {
            emitComparisonOperand(emitterVisitor, scalarVisitor, operands.get(i + 1));
            emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ASTORE, rightSlot);
            emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ALOAD, leftSlot);
            emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ALOAD, rightSlot);
            BinaryOperatorNode compNode = new BinaryOperatorNode(
                    operators.get(i), operands.get(i), operands.get(i + 1), node.tokenIndex);
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

        emitterVisitor.ctx.mv.visitJumpInsn(Opcodes.GOTO, endLabel);
        emitterVisitor.ctx.mv.visitLabel(falseLabel);
        emitterVisitor.ctx.mv.visitLabel(endLabel);
        EmitOperator.handleVoidContext(emitterVisitor);
    }

    static boolean isComparisonOperator(String operator) {
        return Arrays.asList(CHAIN_COMPARISON_OP).contains(operator);
    }

    static boolean isEqualityOperator(String operator) {
        return Arrays.asList(CHAIN_EQUALITY_OP).contains(operator);
    }

    private static void emitComparisonOperand(EmitterVisitor emitterVisitor,
                                              EmitterVisitor scalarVisitor,
                                              Node operand) {
        operand.accept(operand instanceof OperatorNode node && node.operator.equals("substr")
                ? emitterVisitor.with(RuntimeContextType.SNAPSHOT) : scalarVisitor);
    }
}
