package org.perlonjava.backend.jvm;

import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.perlonjava.frontend.analysis.EmitterVisitor;
import org.perlonjava.frontend.astnode.BinaryOperatorNode;
import org.perlonjava.frontend.astnode.Node;
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

        boolean isComparisonChain = isComparisonOperator(node.operator);
        boolean isEqualityChain = isEqualityOperator(node.operator);

        // Build the chain
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
            // Keep the common non-chain case compact; this path is used by
            // thousands of ordinary comparisons in large generated methods.
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
            EmitOperator.emitOperator(new BinaryOperatorNode(
                    operators.getFirst(), operands.get(0), operands.get(1), node.tokenIndex), scalarVisitor);
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

    static boolean isComparisonOperator(String operator) {
        return Arrays.asList(CHAIN_COMPARISON_OP).contains(operator);
    }

    static boolean isEqualityOperator(String operator) {
        return Arrays.asList(CHAIN_EQUALITY_OP).contains(operator);
    }
}
