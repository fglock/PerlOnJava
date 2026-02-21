package org.perlonjava.backend.jvm;

import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.IdentifierNode;
import org.perlonjava.astnode.ListNode;
import org.perlonjava.astnode.Node;
import org.perlonjava.astnode.OperatorNode;
import org.perlonjava.frontend.analysis.EmitterVisitor;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;

import java.util.ArrayList;
import java.util.List;

public class EmitOperatorFileTest {
    static void handleFileTestBuiltin(EmitterVisitor emitterVisitor, OperatorNode node) {
        // Handle:  -d FILE,  -r -w -x FILE
        emitterVisitor.ctx.logDebug("handleFileTestBuiltin " + node);

        // Collect stacked operators by traversing nested OperatorNodes
        List<String> operators = new ArrayList<>();
        Node currentNode = node;
        Node fileOperand = null;

        // Traverse the nested structure to collect operators and find file operand
        while (currentNode instanceof OperatorNode opNode
                && opNode.operator.length() == 2 && opNode.operator.startsWith("-")) {
            operators.add(0, opNode.operator);
            if (opNode.operand instanceof ListNode listNode) {
                currentNode = listNode.elements.getFirst();
                // Store the file operand when we reach the innermost ListNode
                if (!(currentNode instanceof OperatorNode)) {
                    fileOperand = currentNode;
                    break;
                }
            } else if (opNode.operand instanceof OperatorNode) {
                // Continue traversing if the operand is another filetest operator
                currentNode = opNode.operand;
            } else {
                // Found the file operand
                fileOperand = opNode.operand;
                break;
            }
        }

        if (operators.size() > 1) {
            // Handle chained operators

            // Create String array at runtime
            emitterVisitor.ctx.mv.visitIntInsn(Opcodes.BIPUSH, operators.size());
            emitterVisitor.ctx.mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String");

            for (int i = 0; i < operators.size(); i++) {
                emitterVisitor.ctx.mv.visitInsn(Opcodes.DUP);
                emitterVisitor.ctx.mv.visitIntInsn(Opcodes.BIPUSH, i);
                emitterVisitor.ctx.mv.visitLdcInsn(operators.get(i));
                emitterVisitor.ctx.mv.visitInsn(Opcodes.AASTORE);
            }

            if (fileOperand == null || (fileOperand instanceof IdentifierNode && ((IdentifierNode) fileOperand).name.equals("_"))) {
                emitterVisitor.ctx.mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/operators/FileTestOperator",
                        "chainedFileTestLastHandle",
                        "([Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
                        false);
            } else {
                fileOperand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                emitterVisitor.ctx.mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/operators/FileTestOperator",
                        "chainedFileTest",
                        "([Ljava/lang/String;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
                        false);
            }
        } else {
            // Original single operator logic remains unchanged
            emitterVisitor.ctx.mv.visitLdcInsn(node.operator);
            if (node.operand instanceof IdentifierNode && ((IdentifierNode) node.operand).name.equals("_")) {
                emitterVisitor.ctx.mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/operators/FileTestOperator",
                        "fileTestLastHandle",
                        "(Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
                        false);
            } else {
                node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                emitterVisitor.ctx.mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/operators/FileTestOperator",
                        "fileTest",
                        "(Ljava/lang/String;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
                        false);
            }
        }

        // File test operators return a RuntimeScalar; only pop it in VOID context.
        EmitOperator.handleVoidContext(emitterVisitor);
    }
}
