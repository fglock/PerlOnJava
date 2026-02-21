package org.perlonjava.backend.jvm;

import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.BinaryOperatorNode;
import org.perlonjava.astnode.Node;
import org.perlonjava.frontend.analysis.EmitterVisitor;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class EmitOperatorChained {
    public static final String[] CHAIN_COMPARISON_OP = new String[]{"<", ">", "<=", ">=", "lt", "gt", "le", "ge"};
    public static final String[] CHAIN_EQUALITY_OP = new String[]{"==", "!=", "eq", "ne"};

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

        // Emit first comparison
        operands.get(0).accept(scalarVisitor);

        int leftSlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
        boolean pooledLeft = leftSlot >= 0;
        if (!pooledLeft) {
            leftSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
        }
        emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ASTORE, leftSlot);

        operands.get(1).accept(scalarVisitor);

        emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ALOAD, leftSlot);
        emitterVisitor.ctx.mv.visitInsn(Opcodes.SWAP);

        if (pooledLeft) {
            emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
        }
        // Create a BinaryOperatorNode for the first comparison
        BinaryOperatorNode firstCompNode = new BinaryOperatorNode(
                operators.get(0),
                operands.get(0),
                operands.get(1),
                node.tokenIndex
        );
        EmitOperator.emitOperator(firstCompNode, scalarVisitor);

        if (operators.size() > 1) {
            // Set up labels for the chain
            Label endLabel = new Label();
            Label falseLabel = new Label();

            // Emit remaining comparisons
            for (int i = 1; i < operators.size(); i++) {
                // Check previous result
                emitterVisitor.ctx.mv.visitInsn(Opcodes.DUP);
                emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/runtimetypes/RuntimeBase",
                        "getBoolean",
                        "()Z",
                        false);
                emitterVisitor.ctx.mv.visitJumpInsn(Opcodes.IFEQ, falseLabel);

                // Previous was true, do next comparison
                emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);

                operands.get(i).accept(scalarVisitor);

                int chainLeftSlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
                boolean pooledChainLeft = chainLeftSlot >= 0;
                if (!pooledChainLeft) {
                    chainLeftSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
                }
                emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ASTORE, chainLeftSlot);

                operands.get(i + 1).accept(scalarVisitor);

                emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ALOAD, chainLeftSlot);
                emitterVisitor.ctx.mv.visitInsn(Opcodes.SWAP);

                if (pooledChainLeft) {
                    emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
                }
                // Create a BinaryOperatorNode for this comparison
                BinaryOperatorNode compNode = new BinaryOperatorNode(
                        operators.get(i),
                        operands.get(i),
                        operands.get(i + 1),
                        node.tokenIndex
                );
                EmitOperator.emitOperator(compNode, scalarVisitor);
            }

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
