package org.perlonjava.codegen;

import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.*;
import org.perlonjava.astvisitor.EmitterVisitor;
import org.perlonjava.runtime.NameNormalizer;
import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeContextType;

public class EmitOperatorDeleteExists {
    // Handles the 'delete' and 'exists' operators for hash elements.
    static void handleDeleteExists(EmitterVisitor emitterVisitor, OperatorNode node) {
        //   OperatorNode: delete
        //    ListNode:
        //      BinaryOperatorNode: {
        //        OperatorNode: $
        //          IdentifierNode: a
        //        HashLiteralNode:
        //          NumberNode: 10
        String operator = node.operator;
        if (node.operand instanceof ListNode operand) {
            if (operand.elements.size() == 1) {
                if (operand.elements.getFirst() instanceof OperatorNode operatorNode) {
                    if (operator.equals("exists") && operatorNode.operator.equals("&")) {
                        emitterVisitor.ctx.logDebug("exists & " + operatorNode.operand);
                        if (operatorNode.operand instanceof IdentifierNode identifierNode) {
                            // exists &sub
                            handleExistsSubroutine(emitterVisitor, identifierNode);
                            return;
                        }
                    }
                } else {
                    BinaryOperatorNode binop = (BinaryOperatorNode) operand.elements.getFirst();
                    switch (binop.operator) {
                        case "{" -> {
                            // Handle hash element operator.
                            Dereference.handleHashElementOperator(emitterVisitor, binop, operator);
                            return;
                        }
                        case "[" -> {
                            // Check if this is a compound expression like $hash->{key}[index]
                            if (binop.left instanceof BinaryOperatorNode leftBinop && leftBinop.operator.equals("->")) {
                                // Handle compound hash->array dereference for exists/delete
                                // First evaluate the hash dereference to get the array
                                leftBinop.accept(emitterVisitor.with(RuntimeContextType.SCALAR));

                                // Now emit the index
                                if (binop.right instanceof ArrayLiteralNode arrayLiteral &&
                                        arrayLiteral.elements.size() == 1) {
                                    arrayLiteral.elements.get(0).accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                                } else {
                                    throw new PerlCompilerException(node.tokenIndex,
                                            "Invalid array index in " + operator + " operator",
                                            emitterVisitor.ctx.errorUtil);
                                }

                                // Call the appropriate method
                                if (operator.equals("exists")) {
                                    emitterVisitor.ctx.mv.visitMethodInsn(
                                            Opcodes.INVOKEVIRTUAL,
                                            "org/perlonjava/runtime/RuntimeScalar",
                                            "arrayDerefExists",
                                            "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;",
                                            false);
                                } else if (operator.equals("delete")) {
                                    emitterVisitor.ctx.mv.visitMethodInsn(
                                            Opcodes.INVOKEVIRTUAL,
                                            "org/perlonjava/runtime/RuntimeScalar",
                                            "arrayDerefDelete",
                                            "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;",
                                            false);
                                }
                                return;
                            }

                            // Handle simple array element operator.
                            Dereference.handleArrayElementOperator(emitterVisitor, binop, operator);
                            return;
                        }
                        case "->" -> {
                            if (binop.right instanceof HashLiteralNode) { // ->{x}
                                // Handle arrow hash dereference
                                Dereference.handleArrowHashDeref(emitterVisitor, binop, operator);
                                return;
                            }
                            if (binop.right instanceof ArrayLiteralNode) { // ->[x]
                                // Handle arrow array dereference
                                Dereference.handleArrowArrayDeref(emitterVisitor, binop, operator);
                                return;
                            }
                        }
                    }
                }
            }
        }
        // Throw an exception if the operator is not implemented.
        throw new PerlCompilerException(node.tokenIndex, "Not implemented: operator: " + operator, emitterVisitor.ctx.errorUtil);
    }

    private static void handleExistsSubroutine(EmitterVisitor emitterVisitor, IdentifierNode identifierNode) {
        // exists &sub
        String name = identifierNode.name;
        String fullName = NameNormalizer.normalizeVariableName(name, emitterVisitor.ctx.symbolTable.getCurrentPackage());
        emitterVisitor.ctx.mv.visitLdcInsn(fullName); // emit string
        emitterVisitor.ctx.mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/GlobalVariable",
                "existsGlobalCodeRefAsScalar",
                "(Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeScalar;",
                false);
        EmitOperator.handleVoidContext(emitterVisitor);
    }
}
