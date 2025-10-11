package org.perlonjava.codegen;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.*;
import org.perlonjava.astvisitor.EmitterVisitor;
import org.perlonjava.operators.OperatorHandler;
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

        if (node.operand instanceof ListNode listNode && listNode.elements.size() == 1) {
            Node operand2 = listNode.elements.getFirst();
            if (operand2 instanceof OperatorNode operatorNode && operatorNode.operator.equals("+")) {
                // Unwrap the `+` operation
                listNode.elements.set(0, operatorNode.operand);
            }
        }

        String operator = node.operator;
        if (node.operand instanceof ListNode operand) {
            if (operand.elements.size() == 1) {
                if (operand.elements.getFirst() instanceof OperatorNode operatorNode) {
                    if ((operator.equals("exists") || operator.equals("defined")) && operatorNode.operator.equals("&")) {
                        emitterVisitor.ctx.logDebug(operator + " & " + operatorNode.operand);
                        if (operatorNode.operand instanceof IdentifierNode identifierNode) {
                            // exists/defined &sub
                            handleExistsSubroutine(emitterVisitor, operator, identifierNode);
                            return;
                        }
                        if (operatorNode.operand instanceof OperatorNode operatorNode1) {
                            // exists/defined &{"sub"}
                            handleExistsSubroutine(emitterVisitor, operator, operatorNode1);
                            return;
                        }
                        if (operatorNode.operand instanceof BlockNode blockNode &&
                                blockNode.elements.size() == 1 &&
                                blockNode.elements.get(0) instanceof StringNode stringNode) {
                            // exists/defined &{"string"} - literal string
                            handleExistsSubroutineWithPackage(emitterVisitor, operator, stringNode);
                            return;
                        }
                        if (operatorNode.operand instanceof BlockNode blockNode) {
                            // exists/defined &{$variable} or other expressions - handle dynamically
                            // This handles cases like exists(&{$var}) where the content is evaluated at runtime
                            handleExistsSubroutineWithDynamicName(emitterVisitor, operator, blockNode);
                            return;
                        }
                    }
                    // Handle original &{string} pattern for defined/exists/delete operators (no transformation)
                    if ((operator.equals("defined") || operator.equals("exists") || operator.equals("delete")) && operatorNode.operator.equals("&")) {
                        if (operatorNode.operand instanceof BlockNode blockNode &&
                                blockNode.elements.size() == 1 &&
                                blockNode.elements.get(0) instanceof StringNode stringNode) {
                            // Handle original &{string} pattern with proper package name resolution
                            // For defined/exists/delete(&{string}), this checks actual existence
                            handleExistsSubroutineWithPackage(emitterVisitor, operator, stringNode);
                            return;
                        }
                    }
                    // Handle transformed \&{string} pattern for defined operator only
                    if (operator.equals("defined") && operatorNode.operator.equals("\\")) {
                        if (operatorNode.operand instanceof OperatorNode innerOperatorNode &&
                                innerOperatorNode.operator.equals("&") &&
                                innerOperatorNode.operand instanceof BlockNode blockNode &&
                                blockNode.elements.size() == 1 &&
                                blockNode.elements.get(0) instanceof StringNode stringNode) {
                            // Handle transformed \&{string} pattern with proper package name resolution
                            handleExistsSubroutineWithPackage(emitterVisitor, operator, stringNode);
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
                                    arrayLiteral.elements.getFirst().accept(emitterVisitor.with(RuntimeContextType.SCALAR));
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

        // If we reach here, it means we have an exists/defined/delete pattern that we don't specifically handle
        // (like exists(&{$variable}) where the content is evaluated at runtime)
        // For now, we'll throw an exception with a more helpful message
        throw new PerlCompilerException(node.tokenIndex, "Not implemented: operator: " + operator + " with dynamic patterns like &{$variable}. Only literal strings like &{\"method\"} are supported.", emitterVisitor.ctx.errorUtil);
    }

    /**
     * Handles defined()
     *
     * @param node           The operator node
     * @param operator       The operator string
     * @param emitterVisitor The visitor walking the AST
     */
    static void handleDefined(OperatorNode node, String operator,
                              EmitterVisitor emitterVisitor) {
        MethodVisitor mv = emitterVisitor.ctx.mv;

        if (node.operand instanceof ListNode listNode && listNode.elements.size() == 1) {
            Node operand2 = listNode.elements.getFirst();
            if (operand2 instanceof OperatorNode operatorNode && operatorNode.operator.equals("+")) {
                // Unwrap the `+` operation
                listNode.elements.set(0, operatorNode.operand);
            }
        }

        if (node.operand instanceof ListNode operand) {
            if (operand.elements.size() == 1) {
                if (operand.elements.getFirst() instanceof OperatorNode operatorNode) {
                    if (operator.equals("defined") && operatorNode.operator.equals("&")) {
                        emitterVisitor.ctx.logDebug("defined & " + operatorNode.operand);
                        if (operatorNode.operand instanceof IdentifierNode identifierNode) {
                            // exists &sub
                            handleExistsSubroutine(emitterVisitor, operator, identifierNode);
                            return;
                        }
                        if (operatorNode.operand instanceof OperatorNode operatorNode1) {
                            // exists &{"sub"}
                            handleExistsSubroutine(emitterVisitor, operator, operatorNode1);
                            return;
                        }
                        if (operatorNode.operand instanceof BlockNode blockNode &&
                                blockNode.elements.size() == 1 &&
                                blockNode.elements.get(0) instanceof StringNode stringNode) {
                            // defined &{"string"} - BlockNode containing StringNode
                            handleExistsSubroutineWithPackage(emitterVisitor, operator, stringNode);
                            return;
                        }
                        if (operatorNode.operand instanceof BlockNode blockNode) {
                            // defined &{$variable} or other expressions - handle dynamically
                            handleExistsSubroutineWithDynamicName(emitterVisitor, operator, blockNode);
                            return;
                        }
                    }
                }
            }
        }

        node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        OperatorHandler operatorHandler = OperatorHandler.get(node.operator);
        if (operatorHandler != null) {
            EmitOperator.emitOperator(node, emitterVisitor);
        } else {
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/RuntimeScalar",
                    operator,
                    "()Lorg/perlonjava/runtime/RuntimeScalar;",
                    false);
            EmitOperator.handleVoidContext(emitterVisitor);
        }
    }

    private static void handleExistsSubroutine(EmitterVisitor emitterVisitor, String operator, IdentifierNode identifierNode) {
        // exists &sub
        String name = identifierNode.name;
        String fullName = NameNormalizer.normalizeVariableName(name, emitterVisitor.ctx.symbolTable.getCurrentPackage());
        emitterVisitor.ctx.mv.visitLdcInsn(fullName); // emit string
        emitterVisitor.ctx.mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/GlobalVariable",
                operator + "GlobalCodeRefAsScalar",
                "(Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeScalar;",
                false);
        EmitOperator.handleVoidContext(emitterVisitor);
    }

    private static void handleExistsSubroutine(EmitterVisitor emitterVisitor, String operator, OperatorNode operatorNode) {
        // exists &{"sub"}
        operatorNode.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        emitterVisitor.ctx.mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/GlobalVariable",
                operator + "GlobalCodeRefAsScalar",
                "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;",
                false);
        EmitOperator.handleVoidContext(emitterVisitor);
    }

    private static void handleExistsSubroutineWithPackage(EmitterVisitor emitterVisitor, String operator, StringNode stringNode) {
        // Handle transformed \&{string} pattern with proper package name resolution
        // This directly calls the string-based method with package name, avoiding the RuntimeCode conversion
        MethodVisitor mv = emitterVisitor.ctx.mv;

        // Create a RuntimeScalar from the string value
        stringNode.accept(emitterVisitor.with(RuntimeContextType.SCALAR));

        // Push the current package name onto the stack
        emitterVisitor.pushCurrentPackage();

        // Call the package-aware method
        mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/GlobalVariable",
                operator + "GlobalCodeRefAsScalar",
                "(Lorg/perlonjava/runtime/RuntimeScalar;Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeScalar;",
                false);

        EmitOperator.handleVoidContext(emitterVisitor);
    }

    /**
     * Handles exists/defined with dynamic method names like exists(&{$variable})
     * This evaluates the expression inside the block and uses it as the method name
     */
    private static void handleExistsSubroutineWithDynamicName(EmitterVisitor emitterVisitor, String operator, BlockNode blockNode) {
        MethodVisitor mv = emitterVisitor.ctx.mv;

        // Evaluate the expression inside the block to get the method name as a scalar
        if (blockNode.elements.size() == 1) {
            Node expression = blockNode.elements.get(0);
            expression.accept(emitterVisitor.with(RuntimeContextType.SCALAR));

            // Push current package for context
            emitterVisitor.pushCurrentPackage();

            // Call the runtime method to handle dynamic method name resolution
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/GlobalVariable", operator + "GlobalCodeRefAsScalar", "(Lorg/perlonjava/runtime/RuntimeScalar;Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
            EmitOperator.handleVoidContext(emitterVisitor);
        } else {
            // Handle complex expressions inside the block
            // For now, we'll evaluate all elements and use the last one as the method name
            for (int i = 0; i < blockNode.elements.size(); i++) {
                Node element = blockNode.elements.get(i);
                if (i == blockNode.elements.size() - 1) {
                    // Last element - use as method name
                    element.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                } else {
                    // Intermediate elements - evaluate in void context
                    element.accept(emitterVisitor.with(RuntimeContextType.VOID));
                }
            }

            // Push current package for context
            emitterVisitor.pushCurrentPackage();

            // Call the runtime method to handle dynamic method name resolution
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/GlobalVariable", operator + "GlobalCodeRefAsScalar", "(Lorg/perlonjava/runtime/RuntimeScalar;Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
            EmitOperator.handleVoidContext(emitterVisitor);
        }
    }

}
