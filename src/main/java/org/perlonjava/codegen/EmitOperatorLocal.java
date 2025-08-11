package org.perlonjava.codegen;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.IdentifierNode;
import org.perlonjava.astnode.ListNode;
import org.perlonjava.astnode.Node;
import org.perlonjava.astnode.OperatorNode;
import org.perlonjava.astvisitor.EmitterVisitor;
import org.perlonjava.astvisitor.LValueVisitor;
import org.perlonjava.perlmodule.Warnings;
import org.perlonjava.runtime.NameNormalizer;
import org.perlonjava.runtime.RuntimeContextType;

public class EmitOperatorLocal {
    // Handles the 'local' operator.
    static void handleLocal(EmitterVisitor emitterVisitor, OperatorNode node) {
        MethodVisitor mv = emitterVisitor.ctx.mv;

        if (node.operand instanceof OperatorNode opNode && opNode.operator.equals("$")) {
            // Check if the variable is global
            if (opNode.operand instanceof IdentifierNode idNode) {
                String varName = opNode.operator + idNode.name;
                int varIndex = emitterVisitor.ctx.symbolTable.getVariableIndex(varName);
                if (varIndex == -1) {
                        // Variable is global
                        String fullName = NameNormalizer.normalizeVariableName(idNode.name, emitterVisitor.ctx.symbolTable.getCurrentPackage());
                        mv.visitLdcInsn(fullName);
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "org/perlonjava/runtime/GlobalRuntimeScalar",
                                    "makeLocal",
                                    "(Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeScalar;",
                                    false);
                        EmitOperator.handleVoidContext(emitterVisitor);
                        return;
                }
            }
        }

        // emit the lvalue
        int lvalueContext = LValueVisitor.getContext(node.operand);

        if (node.operand instanceof ListNode listNode) {
            for (Node child : listNode.elements) {
                handleLocal(emitterVisitor.with(RuntimeContextType.VOID), new OperatorNode("local", child, node.tokenIndex));
            }
            node.operand.accept(emitterVisitor.with(lvalueContext));
            EmitOperator.handleVoidContext(emitterVisitor);
            return;
        }

        node.operand.accept(emitterVisitor.with(lvalueContext));
        boolean isTypeglob = node.operand instanceof OperatorNode operatorNode && operatorNode.operator.equals("*");
        // save the old value
        if (isTypeglob) {
            emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/DynamicVariableManager",
                    "pushLocalVariable",
                    "(Lorg/perlonjava/runtime/RuntimeGlob;)Lorg/perlonjava/runtime/RuntimeGlob;",
                    false);
        } else if (lvalueContext == RuntimeContextType.LIST) {
            emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/DynamicVariableManager",
                    "pushLocalVariable",
                    "(Lorg/perlonjava/runtime/RuntimeBase;)Lorg/perlonjava/runtime/RuntimeBase;",
                    false);
        } else {
            emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/DynamicVariableManager",
                    "pushLocalVariable",
                    "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;",
                    false);
        }
        EmitOperator.handleVoidContext(emitterVisitor);
    }
}
