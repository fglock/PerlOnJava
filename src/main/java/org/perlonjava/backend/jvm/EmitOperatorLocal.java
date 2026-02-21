package org.perlonjava.backend.jvm;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.IdentifierNode;
import org.perlonjava.astnode.ListNode;
import org.perlonjava.astnode.Node;
import org.perlonjava.astnode.OperatorNode;
import org.perlonjava.frontend.analysis.EmitterVisitor;
import org.perlonjava.frontend.analysis.LValueVisitor;
import org.perlonjava.runtime.runtimetypes.NameNormalizer;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;

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
                            "org/perlonjava/runtime/runtimetypes/GlobalRuntimeScalar",
                            "makeLocal",
                            "(Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
                            false);
                    
                    // If this is a declared reference and not void context, create and return a reference
                    if (isDeclaredReference && emitterVisitor.ctx.contextType != RuntimeContextType.VOID) {
                        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                "org/perlonjava/runtime/runtimetypes/RuntimeBase",
                                "createReference",
                                "()Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
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
                // For declared references like local (\$f, $g), extract the variable from \$f
                Node varToLocalize = child;
                if (child instanceof OperatorNode opNode && opNode.operator.equals("\\")) {
                    // This is \$var - extract the inner variable
                    varToLocalize = opNode.operand;
                }
                handleLocal(emitterVisitor.with(RuntimeContextType.VOID), new OperatorNode("local", varToLocalize, node.tokenIndex));
            }
            
            // Return the list with references if isDeclaredReference is set
            if (emitterVisitor.ctx.contextType != RuntimeContextType.VOID) {
                if (isDeclaredReference) {
                    // For declared references, return a list of references to the variables
                    mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/runtimetypes/RuntimeList");
                    mv.visitInsn(Opcodes.DUP);
                    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/perlonjava/runtime/runtimetypes/RuntimeList", "<init>", "()V", false);
                    
                    for (Node child : listNode.elements) {
                        // Handle both direct variables ($f) and declared refs (\$f)
                        Node varNode = child;
                        if (child instanceof OperatorNode elemOpNode) {
                            if (elemOpNode.operator.equals("\\") && elemOpNode.operand instanceof OperatorNode innerOp) {
                                // This is \$var - use the inner variable
                                varNode = innerOp;
                            }
                        }
                        
                        if (varNode instanceof OperatorNode varOpNode && "$@%".contains(varOpNode.operator)) {
                            mv.visitInsn(Opcodes.DUP);
                            varNode.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                    "org/perlonjava/runtime/runtimetypes/RuntimeBase",
                                    "createReference",
                                    "()Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
                                    false);
                            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                    "org/perlonjava/runtime/runtimetypes/RuntimeList",
                                    "add",
                                    "(Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)V",
                                    false);
                        }
                    }
                    // List is on stack, don't call handleVoidContext
                } else {
                    node.operand.accept(emitterVisitor.with(lvalueContext));
                    EmitOperator.handleVoidContext(emitterVisitor);
                }
            }
            // In VOID context, nothing to return
            return;
        }

        // For declared references like local \%h, extract the actual variable
        Node varToLocal = node.operand;
        if (node.operand instanceof OperatorNode opNode && opNode.operator.equals("\\")) {
            varToLocal = opNode.operand;
            lvalueContext = LValueVisitor.getContext(varToLocal);
        }
        
        varToLocal.accept(emitterVisitor.with(lvalueContext));
        boolean isTypeglob = varToLocal instanceof OperatorNode operatorNode && operatorNode.operator.equals("*");
        // save the old value
        if (isTypeglob) {
            emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/runtimetypes/DynamicVariableManager",
                    "pushLocalVariable",
                    "(Lorg/perlonjava/runtime/runtimetypes/RuntimeGlob;)Lorg/perlonjava/runtime/runtimetypes/RuntimeGlob;",
                    false);
        } else if (lvalueContext == RuntimeContextType.LIST) {
            emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/runtimetypes/DynamicVariableManager",
                    "pushLocalVariable",
                    "(Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;",
                    false);
        } else {
            emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/runtimetypes/DynamicVariableManager",
                    "pushLocalVariable",
                    "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
                    false);
        }
        
        // If this is a declared reference and not void context, create and return a reference
        if (isDeclaredReference && emitterVisitor.ctx.contextType != RuntimeContextType.VOID) {
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/runtimetypes/RuntimeBase",
                    "createReference",
                    "()Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
                    false);
        }
        EmitOperator.handleVoidContext(emitterVisitor);
    }
}
