package org.perlonjava.codegen;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.IdentifierNode;
import org.perlonjava.astnode.ListNode;
import org.perlonjava.astnode.Node;
import org.perlonjava.astnode.OperatorNode;
import org.perlonjava.astvisitor.EmitterVisitor;
import org.perlonjava.astvisitor.LValueVisitor;
import org.perlonjava.runtime.NameNormalizer;
import org.perlonjava.runtime.RuntimeContextType;

public class EmitOperatorLocal {
    // Handles the 'local' operator.
    static void handleLocal(EmitterVisitor emitterVisitor, OperatorNode node) {
        MethodVisitor mv = emitterVisitor.ctx.mv;
        
        // Check if this is a declared reference (local \$x)
        boolean isDeclaredReference = node.annotations != null &&
                Boolean.TRUE.equals(node.annotations.get("isDeclaredReference"));

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
                    
                    // If this is a declared reference and not void context, create and return a reference
                    if (isDeclaredReference && emitterVisitor.ctx.contextType != RuntimeContextType.VOID) {
                        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                "org/perlonjava/runtime/RuntimeBase",
                                "createReference",
                                "()Lorg/perlonjava/runtime/RuntimeScalar;",
                                false);
                    }
                    EmitOperator.handleVoidContext(emitterVisitor);
                    return;
                }
            }
        }

        // emit the lvalue
        int lvalueContext = LValueVisitor.getContext(node.operand);

        if (node.operand instanceof ListNode listNode) {
            for (Node child : listNode.elements) {
                // Skip undef in local lists - it's a no-op
                if (child instanceof OperatorNode opNode && opNode.operator.equals("undef")) {
                    continue;
                }
                handleLocal(emitterVisitor.with(RuntimeContextType.VOID), new OperatorNode("local", child, node.tokenIndex));
            }
            
            // Return the list with references if isDeclaredReference is set
            if (emitterVisitor.ctx.contextType != RuntimeContextType.VOID) {
                if (isDeclaredReference) {
                    // For declared references, return a list of references to the variables
                    mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/RuntimeList");
                    mv.visitInsn(Opcodes.DUP);
                    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/perlonjava/runtime/RuntimeList", "<init>", "()V", false);
                    
                    for (Node child : listNode.elements) {
                        if (child instanceof OperatorNode elemOpNode && "$@%".contains(elemOpNode.operator)) {
                            mv.visitInsn(Opcodes.DUP);
                            child.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                    "org/perlonjava/runtime/RuntimeBase",
                                    "createReference",
                                    "()Lorg/perlonjava/runtime/RuntimeScalar;",
                                    false);
                            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                    "org/perlonjava/runtime/RuntimeList",
                                    "add",
                                    "(Lorg/perlonjava/runtime/RuntimeBase;)V",
                                    false);
                        }
                    }
                } else {
                    node.operand.accept(emitterVisitor.with(lvalueContext));
                }
            }
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
