package org.perlonjava.codegen;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.*;
import org.perlonjava.astvisitor.EmitterVisitor;
import org.perlonjava.astvisitor.ReturnTypeVisitor;
import org.perlonjava.operators.OperatorHandler;
import org.perlonjava.operators.ScalarGlobOperator;
import org.perlonjava.perlmodule.Strict;
import org.perlonjava.runtime.NameNormalizer;
import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeContextType;
import org.perlonjava.runtime.RuntimeDescriptorConstants;
import org.perlonjava.symbols.ScopedSymbolTable;

/**
 * The EmitOperator class is responsible for handling various operators
 * and generating the corresponding bytecode using ASM.
 */
public class EmitOperator {

    private static final boolean ENABLE_SPILL_BINARY_LHS = System.getenv("JPERL_NO_SPILL_BINARY_LHS") == null;

    static void emitOperator(Node node, EmitterVisitor emitterVisitor) {
        // Extract operator string from the node
        String operator = null;
        if (node instanceof OperatorNode operatorNode) {
            operator = operatorNode.operator;
        } else if (node instanceof BinaryOperatorNode binaryOperatorNode) {
            operator = binaryOperatorNode.operator;
        } else {
            throw new PerlCompilerException(node.getIndex(), "Node must be OperatorNode or BinaryOperatorNode", emitterVisitor.ctx.errorUtil);
        }

        // Invoke the method for the operator.
        OperatorHandler operatorHandler = OperatorHandler.get(operator);
        if (operatorHandler == null) {
            throw new PerlCompilerException(node.getIndex(), "Operator \"" + operator + "\" doesn't have a defined JVM descriptor", emitterVisitor.ctx.errorUtil);
        }
        emitterVisitor.ctx.logDebug("emitOperator " +
                operatorHandler.methodType() + " " +
                operatorHandler.className() + " " +
                operatorHandler.methodName() + " " +
                operatorHandler.descriptor()
        );
        emitterVisitor.ctx.mv.visitMethodInsn(
                operatorHandler.methodType(),
                operatorHandler.className(),
                operatorHandler.methodName(),
                operatorHandler.descriptor(),
                false
        );

        // Handle context
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            handleVoidContext(emitterVisitor);
        } else if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR) {
            handleScalarContext(emitterVisitor, node);
        }
    }

    /**
     * Handles the 'readdir' operator, which reads directory contents.
     *
     * @param emitterVisitor The visitor used for code emission.
     * @param node           The operator node representing the 'readdir' operation.
     */
    static void handleReaddirOperator(EmitterVisitor emitterVisitor, OperatorNode node) {
        // Accept the operand in SCALAR context.
        node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        emitterVisitor.pushCallContext();
        emitOperator(node, emitterVisitor);
    }

    /**
     * Handles the 'keys' operator, which retrieves keys from a data structure.
     *
     * @param emitterVisitor The visitor used for code emission.
     * @param node           The operator node representing the 'keys' operation.
     */
    static void handleOpWithList(EmitterVisitor emitterVisitor, OperatorNode node) {
        // Accept the operand in LIST context.
        node.operand.accept(emitterVisitor.with(RuntimeContextType.LIST));

        // keys() depends on context (scalar/list/void), so pass call context.
        if (node.operator.equals("keys")) {
            emitterVisitor.pushCallContext();
        }

        emitOperator(node, emitterVisitor);
    }

    static void handleEach(EmitterVisitor emitterVisitor, OperatorNode node) {
        // Accept the operand in LIST context.
        node.operand.accept(emitterVisitor.with(RuntimeContextType.LIST));
        emitterVisitor.pushCallContext();
        emitOperator(node, emitterVisitor);
    }

    /**
     * Handles the 'readline' operator for reading lines from a file handle.
     *
     * @param emitterVisitor The visitor used for code emission.
     * @param node           The binary operator node representing the 'readline' operation.
     */
    static void handleReadlineOperator(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        String operator = node.operator;
        emitterVisitor.ctx.logDebug("handleReadlineOperator " + node);

        // Emit the File Handle
        emitFileHandle(emitterVisitor.with(RuntimeContextType.SCALAR), node.left);

        if (operator.equals("readline")) {
            emitterVisitor.pushCallContext();
        }
        emitOperator(node, emitterVisitor);
    }

    static void handleBinmodeOperator(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        // Emit the File Handle
        emitFileHandle(emitterVisitor.with(RuntimeContextType.SCALAR), node.left);

        int handleSlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
        boolean pooledHandle = handleSlot >= 0;
        if (!pooledHandle) {
            handleSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
        }
        emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ASTORE, handleSlot);

        // Accept the right operand in LIST context
        node.right.accept(emitterVisitor.with(RuntimeContextType.LIST));

        emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ALOAD, handleSlot);
        emitterVisitor.ctx.mv.visitInsn(Opcodes.SWAP);

        if (pooledHandle) {
            emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
        }

        // Emit the operator
        emitOperator(node, emitterVisitor);
    }

    static void handleTruncateOperator(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        // Emit the File Handle or file name
        if (node.left instanceof StringNode) {
            // If the left node is a filename, accept it in SCALAR context
            node.left.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        } else {
            emitFileHandle(emitterVisitor.with(RuntimeContextType.SCALAR), node.left);
        }

        // Accept the right operand in LIST context
        node.right.accept(emitterVisitor.with(RuntimeContextType.LIST));
        emitOperator(node, emitterVisitor);
    }

    // Handles the 'say' operator for outputting data.
    static void handleSayOperator(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        String operator = node.operator;
        // Emit the argument list in LIST context.
        node.right.accept(emitterVisitor.with(RuntimeContextType.LIST));

        // Emit the File Handle
        emitFileHandle(emitterVisitor.with(RuntimeContextType.SCALAR), node.left);

        // Call the operator, return Scalar
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/operators/IOOperator", operator, "(Lorg/perlonjava/runtime/RuntimeList;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        // If the context is VOID, pop the result from the stack.
        handleVoidContext(emitterVisitor);
    }

    // Handles the unary plus operator, which is a no-op in many contexts.
    static void handleUnaryPlusOperator(EmitterVisitor emitterVisitor, OperatorNode node) {
        node.operand.accept(emitterVisitor);
    }

    // Handles the 'index' built-in function, which finds the position of a substring.
    static void handleIndexBuiltin(EmitterVisitor emitterVisitor, OperatorNode node) {
        EmitterVisitor scalarVisitor = emitterVisitor.with(RuntimeContextType.SCALAR);
        if (node.operand instanceof ListNode operand) {
            if (!operand.elements.isEmpty()) {
                MethodVisitor mv = emitterVisitor.ctx.mv;

                int arg0Slot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
                boolean pooledArg0 = arg0Slot >= 0;
                if (!pooledArg0) {
                    arg0Slot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
                }

                int arg1Slot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
                boolean pooledArg1 = arg1Slot >= 0;
                if (!pooledArg1) {
                    arg1Slot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
                }

                int arg2Slot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
                boolean pooledArg2 = arg2Slot >= 0;
                if (!pooledArg2) {
                    arg2Slot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
                }

                operand.elements.get(0).accept(scalarVisitor);
                mv.visitVarInsn(Opcodes.ASTORE, arg0Slot);

                operand.elements.get(1).accept(scalarVisitor);
                mv.visitVarInsn(Opcodes.ASTORE, arg1Slot);

                if (operand.elements.size() == 3) {
                    operand.elements.get(2).accept(scalarVisitor);
                } else {
                    new OperatorNode("undef", null, node.tokenIndex).accept(scalarVisitor);
                }
                mv.visitVarInsn(Opcodes.ASTORE, arg2Slot);

                mv.visitVarInsn(Opcodes.ALOAD, arg0Slot);
                mv.visitVarInsn(Opcodes.ALOAD, arg1Slot);
                mv.visitVarInsn(Opcodes.ALOAD, arg2Slot);

                if (pooledArg2) {
                    emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
                }
                if (pooledArg1) {
                    emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
                }
                if (pooledArg0) {
                    emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
                }

                emitOperator(node, emitterVisitor);
            }
        }
    }

    // Handles the 'atan2' function, which calculates the arctangent of two numbers.
    static void handleAtan2(EmitterVisitor emitterVisitor, OperatorNode node) {
        EmitterVisitor scalarVisitor = emitterVisitor.with(RuntimeContextType.SCALAR);
        if (node.operand instanceof ListNode operand) {
            operand.elements.get(0).accept(scalarVisitor);
            operand.elements.get(1).accept(scalarVisitor);
            emitOperator(node, emitterVisitor);
        }
    }

    // Handle an operator that was parsed using a Perl prototype.
    static void handleOperator(EmitterVisitor emitterVisitor, OperatorNode node) {
        EmitterVisitor scalarVisitor = emitterVisitor.with(RuntimeContextType.SCALAR);
        EmitterVisitor listVisitor = emitterVisitor.with(RuntimeContextType.LIST);
        if (node.operand instanceof ListNode operand) {
            // Push context
            emitterVisitor.pushCallContext();

            int callContextSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
            emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ISTORE, callContextSlot);

            // Create array for varargs operators
            MethodVisitor mv = emitterVisitor.ctx.mv;

            // Create array of RuntimeScalar with size equal to number of arguments
            mv.visitIntInsn(Opcodes.SIPUSH, operand.elements.size());
            mv.visitTypeInsn(Opcodes.ANEWARRAY, "org/perlonjava/runtime/RuntimeBase");

            int argsArraySlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
            boolean pooledArgsArray = argsArraySlot >= 0;
            if (!pooledArgsArray) {
                argsArraySlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
            }
            mv.visitVarInsn(Opcodes.ASTORE, argsArraySlot);

            // Populate the array with arguments
            int index = 0;
            for (Node arg : operand.elements) {
                // Generate code for argument
                String argContext = (String) arg.getAnnotation("context");
                if (argContext != null && argContext.equals("SCALAR")) {
                    arg.accept(scalarVisitor);
                } else {
                    arg.accept(listVisitor);
                }

                int argSlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
                boolean pooledArg = argSlot >= 0;
                if (!pooledArg) {
                    argSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
                }
                mv.visitVarInsn(Opcodes.ASTORE, argSlot);

                mv.visitVarInsn(Opcodes.ALOAD, argsArraySlot);
                mv.visitIntInsn(Opcodes.SIPUSH, index);
                mv.visitVarInsn(Opcodes.ALOAD, argSlot);
                mv.visitInsn(Opcodes.AASTORE); // Store in array

                if (pooledArg) {
                    emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
                }
                index++;
            }

            mv.visitVarInsn(Opcodes.ILOAD, callContextSlot);
            mv.visitVarInsn(Opcodes.ALOAD, argsArraySlot);

            emitOperator(node, emitterVisitor);

            if (pooledArgsArray) {
                emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
            }
        }
    }

    // Handles the 'die' built-in function, which throws an exception.
    static void handleDieBuiltin(EmitterVisitor emitterVisitor, OperatorNode node) {
        // Handle:  die LIST
        //   static RuntimeBase die(RuntimeBase value, int ctx)
        emitterVisitor.ctx.logDebug("handleDieBuiltin " + node);
        MethodVisitor mv = emitterVisitor.ctx.mv;
        // Accept the operand in LIST context.
        node.operand.accept(emitterVisitor.with(RuntimeContextType.LIST));

        // Push the formatted line number as a message.
        Node message = new StringNode(" at " + node.getAnnotation("file") + " line " + node.getAnnotation("line"), node.tokenIndex);
        message.accept(emitterVisitor.with(RuntimeContextType.SCALAR));

        String fileName = emitterVisitor.ctx.errorUtil.getFileName();
        mv.visitLdcInsn(fileName);
        int lineNumber = emitterVisitor.ctx.errorUtil.getLineNumber(node.tokenIndex);
        mv.visitLdcInsn(lineNumber);

        emitOperator(node, emitterVisitor);
    }

    // Handles the 'system' and 'exec' built-in functions.
    static void handleSystemBuiltin(EmitterVisitor emitterVisitor, OperatorNode node) {
        // Handle:  reverse LIST
        //   static RuntimeBase reverse(RuntimeBase value, int ctx)
        emitterVisitor.ctx.logDebug("handleSystemBuiltin " + node);

        ListNode operand = (ListNode) node.operand;
        boolean hasHandle = false;
        if (operand.handle != null) {
            //  `system {handle} LIST`
            hasHandle = true;
            operand.elements.addFirst(operand.handle);
        }

        // Accept the operand in LIST context.
        operand.accept(emitterVisitor.with(RuntimeContextType.LIST));

        // Push the boolean value of hasHandle to the stack
        MethodVisitor mv = emitterVisitor.ctx.mv;
        mv.visitInsn(hasHandle ? Opcodes.ICONST_1 : Opcodes.ICONST_0);

        emitterVisitor.pushCallContext();
        emitOperator(node, emitterVisitor);
    }

    // Handles the 'splice' built-in function, which modifies an array.
    static void handleSpliceBuiltin(EmitterVisitor emitterVisitor, OperatorNode node) {
        // Handle:  splice @array, LIST
        emitterVisitor.ctx.logDebug("handleSpliceBuiltin " + node);
        Node args = node.operand;
        if (args instanceof ListNode listArgs) {
            if (!listArgs.elements.isEmpty()) {
                // Remove the first element from the list and accept it in LIST context.
                // Restore the list afterwards to avoid mutating the AST.
                Node first;
                try {
                    first = listArgs.elements.removeFirst();
                } catch (java.util.NoSuchElementException e) {
                    // Defensive: treat as no args.
                    first = null;
                }

                if (first != null) {
                    try {
                        first.accept(emitterVisitor.with(RuntimeContextType.LIST));
                        // Accept the remaining arguments in LIST context.
                        args.accept(emitterVisitor.with(RuntimeContextType.LIST));
                    } finally {
                        listArgs.elements.addFirst(first);
                    }
                } else {
                    // Accept all arguments in LIST context.
                    args.accept(emitterVisitor.with(RuntimeContextType.LIST));
                }
            } else {
                // Accept all arguments in LIST context.
                args.accept(emitterVisitor.with(RuntimeContextType.LIST));
            }
        } else {
            // Accept all arguments in LIST context.
            args.accept(emitterVisitor.with(RuntimeContextType.LIST));
        }
        emitOperator(node, emitterVisitor);
    }

    // Handles the 'push' operator, which adds elements to an array.
    static void handlePushOperator(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        // Spill the left operand before evaluating the right side so non-local control flow
        // propagation can't jump to returnLabel with an extra value on the JVM operand stack.
        if (ENABLE_SPILL_BINARY_LHS) {
            MethodVisitor mv = emitterVisitor.ctx.mv;
            node.left.accept(emitterVisitor.with(RuntimeContextType.LIST));

            int leftSlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
            boolean pooled = leftSlot >= 0;
            if (!pooled) {
                leftSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
            }
            mv.visitVarInsn(Opcodes.ASTORE, leftSlot);

            node.right.accept(emitterVisitor.with(RuntimeContextType.LIST));

            mv.visitVarInsn(Opcodes.ALOAD, leftSlot);
            mv.visitInsn(Opcodes.SWAP);

            if (pooled) {
                emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
            }
        } else {
            // Accept both left and right operands in LIST context.
            node.left.accept(emitterVisitor.with(RuntimeContextType.LIST));
            node.right.accept(emitterVisitor.with(RuntimeContextType.LIST));
        }
        emitOperator(node, emitterVisitor);
    }

    // Handles the 'map' operator, which applies a function to each element of a list.
    static void handleMapOperator(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        String operator = node.operator;

        // Accept the right operand in LIST context and the left operand in SCALAR context.
        node.right.accept(emitterVisitor.with(RuntimeContextType.LIST));  // list
        node.left.accept(emitterVisitor.with(RuntimeContextType.SCALAR)); // subroutine
        if (operator.equals("sort")) {
            emitterVisitor.pushCurrentPackage();
        } else {
            emitterVisitor.pushCallContext();
        }
        emitOperator(node, emitterVisitor);
    }

    // Handles the 'diamond' operator, which reads input from a file or standard input.
    static void handleDiamondBuiltin(EmitterVisitor emitterVisitor, OperatorNode node) {
        MethodVisitor mv = emitterVisitor.ctx.mv;
        String argument = ((StringNode) ((ListNode) node.operand).elements.getFirst()).value;
        emitterVisitor.ctx.logDebug("visit diamond " + argument);
        if (argument.isEmpty() || argument.equals("<>")) {
            // Handle null filehandle:  <>  <<>>
            node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
            emitterVisitor.pushCallContext();
            // Invoke the static method for reading lines.
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/DiamondIO",
                    "readline",
                    "(Lorg/perlonjava/runtime/RuntimeScalar;I)Lorg/perlonjava/runtime/RuntimeBase;", false);

            // Handle context
            if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
                handleVoidContext(emitterVisitor);
            } else if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR) {
                handleScalarContext(emitterVisitor, node);
            }
        } else {
            // Handle globbing if the argument is not empty or "<>".
            node.operator = "glob";
            handleGlobBuiltin(emitterVisitor, node);
        }
    }

    // Handles the 'chomp' built-in function, which removes trailing newlines from strings.
    static void handleChompBuiltin(EmitterVisitor emitterVisitor, OperatorNode node) {
        MethodVisitor mv = emitterVisitor.ctx.mv;
        // Accept the operand in LIST context.
        node.operand.accept(emitterVisitor.with(RuntimeContextType.LIST));
        // Invoke the interface method for the operator.
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "org/perlonjava/runtime/RuntimeBase",
                node.operator,
                "()Lorg/perlonjava/runtime/RuntimeScalar;", false);
        // If the context is VOID, pop the result from the stack.
        handleVoidContext(emitterVisitor);
    }

    // Handles the 'glob' built-in function, which performs filename expansion.
    static void handleGlobBuiltin(EmitterVisitor emitterVisitor, OperatorNode node) {
        MethodVisitor mv = emitterVisitor.ctx.mv;

        // Generate unique IDs for this glob instance
        int globId = ScalarGlobOperator.currentId++;

        int globIdSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
        mv.visitLdcInsn(globId);
        mv.visitVarInsn(Opcodes.ISTORE, globIdSlot);

        // Accept the operand in SCALAR context.
        node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        int patternSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
        mv.visitVarInsn(Opcodes.ASTORE, patternSlot);

        // public static RuntimeBase evaluate(id, patternArg, ctx)
        mv.visitVarInsn(Opcodes.ILOAD, globIdSlot);
        mv.visitVarInsn(Opcodes.ALOAD, patternSlot);
        emitterVisitor.pushCallContext();
        emitOperator(node, emitterVisitor);
    }

    // Handles the 'range' operator, which creates a range of values.
    static void handleRangeOperator(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        // Accept both left and right operands in SCALAR context.
        node.left.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        node.right.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        emitOperator(node, emitterVisitor);
    }

    // Handles the 'substr' operator, which extracts a substring from a string.
    static void handleSubstr(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        // Accept the left operand in SCALAR context and the right operand in LIST context.
        // Spill the left operand before evaluating the right side so non-local control flow
        // propagation can't jump to returnLabel with an extra value on the JVM operand stack.
        boolean isBytes = emitterVisitor.ctx.symbolTable != null &&
                emitterVisitor.ctx.symbolTable.isStrictOptionEnabled(Strict.HINT_BYTES);
        if (ENABLE_SPILL_BINARY_LHS) {
            MethodVisitor mv = emitterVisitor.ctx.mv;
            node.left.accept(emitterVisitor.with(RuntimeContextType.SCALAR));

            int leftSlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
            boolean pooled = leftSlot >= 0;
            if (!pooled) {
                leftSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
            }
            mv.visitVarInsn(Opcodes.ASTORE, leftSlot);

            node.right.accept(emitterVisitor.with(RuntimeContextType.LIST));

            mv.visitVarInsn(Opcodes.ALOAD, leftSlot);
            mv.visitInsn(Opcodes.SWAP);

            if (pooled) {
                emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
            }
        } else {
            node.left.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
            node.right.accept(emitterVisitor.with(RuntimeContextType.LIST));
        }

        if (node.operator.equals("sprintf") && isBytes) {
            emitterVisitor.ctx.mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "org/perlonjava/operators/SprintfOperator",
                    "sprintfBytes",
                    "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeScalar;",
                    false);

            if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
                handleVoidContext(emitterVisitor);
            } else if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR) {
                handleScalarContext(emitterVisitor, node);
            }
            return;
        }

        emitOperator(node, emitterVisitor);
    }

    // Handles the 'split' operator
    static void handleSplit(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        // Accept the left operand in SCALAR context and the right operand in LIST context.
        // Spill the left operand before evaluating the right side so non-local control flow
        // propagation can't jump to returnLabel with an extra value on the JVM operand stack.
        if (ENABLE_SPILL_BINARY_LHS) {
            MethodVisitor mv = emitterVisitor.ctx.mv;
            node.left.accept(emitterVisitor.with(RuntimeContextType.SCALAR));

            int leftSlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
            boolean pooled = leftSlot >= 0;
            if (!pooled) {
                leftSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
            }
            mv.visitVarInsn(Opcodes.ASTORE, leftSlot);

            node.right.accept(emitterVisitor.with(RuntimeContextType.LIST));

            mv.visitVarInsn(Opcodes.ALOAD, leftSlot);
            mv.visitInsn(Opcodes.SWAP);

            if (pooled) {
                emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
            }
        } else {
            node.left.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
            node.right.accept(emitterVisitor.with(RuntimeContextType.LIST));
        }
        emitterVisitor.pushCallContext();
        emitOperator(node, emitterVisitor);
    }

    // Handles the 'repeat' operator, which repeats a string or list a specified number of times.
    static void handleRepeat(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        // Determine the context for the left operand.
        // When x operator is in scalar context, left operand must be in scalar context too
        if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR) {
            node.left.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        } else if (node.left instanceof ListNode) {
            node.left.accept(emitterVisitor.with(RuntimeContextType.LIST));
        } else {
            node.left.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        }
        // Accept the right operand in SCALAR context.
        node.right.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        emitterVisitor.pushCallContext();
        // Invoke the static method for the 'repeat' operator.
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/operators/Operator",
                "repeat",
                "(Lorg/perlonjava/runtime/RuntimeBase;Lorg/perlonjava/runtime/RuntimeScalar;I)Lorg/perlonjava/runtime/RuntimeBase;", false);

        if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR) {
            emitterVisitor.ctx.mv.visitTypeInsn(Opcodes.CHECKCAST, "org/perlonjava/runtime/RuntimeScalar");
        }

        // If the context is VOID, pop the result from the stack.
        handleVoidContext(emitterVisitor);
    }

    // Handles the 'concat' operator, which concatenates two strings.
    static void handleConcatOperator(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        EmitterVisitor scalarVisitor =
                emitterVisitor.with(RuntimeContextType.SCALAR); // execute operands in scalar context
        // Accept both left and right operands in SCALAR context.
        if (ENABLE_SPILL_BINARY_LHS) {
            MethodVisitor mv = emitterVisitor.ctx.mv;
            node.left.accept(scalarVisitor); // target - left parameter
            int leftSlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
            boolean pooled = leftSlot >= 0;
            if (!pooled) {
                leftSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
            }
            mv.visitVarInsn(Opcodes.ASTORE, leftSlot);
            node.right.accept(scalarVisitor); // right parameter
            mv.visitVarInsn(Opcodes.ALOAD, leftSlot);
            mv.visitInsn(Opcodes.SWAP);
            if (pooled) {
                emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
            }
        } else {
            node.left.accept(scalarVisitor); // target - left parameter
            node.right.accept(scalarVisitor); // right parameter
        }

        ScopedSymbolTable symbolTable = emitterVisitor.ctx.symbolTable;
        boolean warnUninitialized = symbolTable != null && symbolTable.isWarningCategoryEnabled("uninitialized");
        if (warnUninitialized) {
            emitterVisitor.ctx.mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "org/perlonjava/operators/StringOperators",
                    "stringConcatWarnUninitialized",
                    "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;",
                    false);

            if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
                handleVoidContext(emitterVisitor);
            }
            return;
        }

        emitOperator(node, emitterVisitor);
    }

    // Handles the 'scalar' operator, which forces a list into scalar context.
    static void handleScalar(EmitterVisitor emitterVisitor, OperatorNode node) {
        if (node.operand instanceof OperatorNode operatorNode && operatorNode.operator.equals("%")) {
            // `scalar %a` needs an explicit call because tied hashes have a SCALAR method
            node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
            emitOperator(node, emitterVisitor); // This already calls handleVoidContext
            return;
        }

        // Accept the operand in SCALAR context.
        node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));

        // Handle VOID context - pop the result if not needed
        handleVoidContext(emitterVisitor);
    }

    static void handleVoidContext(EmitterVisitor emitterVisitor) {
        // If the context is VOID, pop the result from the stack.
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    /**
     * Ensures the value on the stack is converted to RuntimeScalar if needed,
     * based on the node's return type.
     *
     * @param emitterVisitor The visitor for emitting bytecode
     * @param node           The node that produced the value on the stack
     */
    public static void handleScalarContext(EmitterVisitor emitterVisitor, Node node) {
        if (emitterVisitor.ctx.contextType != RuntimeContextType.SCALAR) {
            return; // Not in scalar context, nothing to do
        }

        String rawType = ReturnTypeVisitor.getReturnType(node);
        if (RuntimeDescriptorConstants.SCALAR_TYPE.equals(rawType)) {
            return; // Already a scalar
        }

        MethodVisitor mv = emitterVisitor.ctx.mv;

        // Convert to scalar based on return type
        if (RuntimeDescriptorConstants.isKnownRuntimeType(rawType)) {
            // Extract the internal class name from the type descriptor
            String className = RuntimeDescriptorConstants.descriptorToInternalName(rawType);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, className,
                    "scalar", "()" + RuntimeDescriptorConstants.SCALAR_TYPE, false);
        } else {
            // For unknown types
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeBase", "scalar", "()Lorg/perlonjava/runtime/RuntimeScalar;", false);
        }
    }

    // Handles the 'package' operator, which sets the current package for the symbol table.
    static void handlePackageOperator(EmitterVisitor emitterVisitor, OperatorNode node) {
        // Extract the package name from the operand.
        String name = ((IdentifierNode) node.operand).name;

        // Check if there's a version associated with this package and set it at compile time
        String version = emitterVisitor.ctx.symbolTable.getPackageVersion(name);
        if (version != null) {
            // Set $PackageName::VERSION at compile time using GlobalVariable
            String versionVarName = name + "::VERSION";
            org.perlonjava.runtime.GlobalVariable.getGlobalVariable(versionVarName)
                    .set(new org.perlonjava.runtime.RuntimeScalar(version));
        }

        // Set the current package in the symbol table.
        emitterVisitor.ctx.symbolTable.setCurrentPackage(name, node.getBooleanAnnotation("isClass"));
        // Set debug information for the file name.
        ByteCodeSourceMapper.setDebugInfoFileName(emitterVisitor.ctx);
        if (emitterVisitor.ctx.contextType != RuntimeContextType.VOID) {
            // If context is not void, return an empty list.
            ListNode listNode = new ListNode(node.tokenIndex);
            listNode.accept(emitterVisitor);
        }
    }

    public static void emitFileHandle(EmitterVisitor emitterVisitor, Node node) {
        EmitterContext ctx = emitterVisitor.ctx;

        // Emit the File Handle or file name
        if (node instanceof IdentifierNode) {
            // `print FILE 123`
            // retrieve STDOUT, STDERR from GlobalIORef
            // fetch a global fileHandle by name

            // resolve the full name of the file handle
            String name = ((IdentifierNode) node).name;
            name = NameNormalizer.normalizeVariableName(name, ctx.symbolTable.getCurrentPackage());

            ctx.mv.visitLdcInsn(name);
            ctx.mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/GlobalVariable",
                    "getGlobalIO",
                    "(Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeGlob;",
                    false);
        } else {
            node.accept(emitterVisitor);
        }
    }

    static void handleTimeOperator(EmitterVisitor emitterVisitor, OperatorNode node) {
        emitOperator(node, emitterVisitor);
    }

    static void handleWantArrayOperator(EmitterVisitor emitterVisitor, OperatorNode node) {
        emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ILOAD,
                emitterVisitor.ctx.symbolTable.getVariableIndex("wantarray"));
        emitOperator(node, emitterVisitor);
    }

    static void handleUndefOperator(EmitterVisitor emitterVisitor, OperatorNode node) {
        if (node.operand == null) {
            if (emitterVisitor.ctx.contextType != RuntimeContextType.VOID) {
                emitUndef(emitterVisitor.ctx.mv);
            }
            return;
        }
        node.operand.accept(emitterVisitor.with(RuntimeContextType.RUNTIME));
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "org/perlonjava/runtime/RuntimeList",
                "undefine",
                "()Lorg/perlonjava/runtime/RuntimeList;",
                false);
        handleVoidContext(emitterVisitor);
    }

    static void handleTimeRelatedOperator(EmitterVisitor emitterVisitor, OperatorNode node) {
        if (node.operand != null) {
            node.operand.accept(emitterVisitor.with(RuntimeContextType.LIST));
        }
        emitterVisitor.pushCallContext();
        emitOperator(node, emitterVisitor);
    }

    static void handlePrototypeOperator(EmitterVisitor emitterVisitor, OperatorNode node) {
        node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        emitterVisitor.pushCurrentPackage();
        emitOperator(node, emitterVisitor);
    }

    static void handleRequireOperator(EmitterVisitor emitterVisitor, OperatorNode node) {
        node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        emitOperator(node, emitterVisitor);
    }

    static void handleDoFileOperator(EmitterVisitor emitterVisitor, OperatorNode node) {
        // Accept the operand (filename) in scalar context
        node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        // Push the context type
        emitterVisitor.ctx.mv.visitLdcInsn(emitterVisitor.ctx.contextType);
        // Call doFile with context
        emitOperator(node, emitterVisitor);
    }

    static void handleStatOperator(EmitterVisitor emitterVisitor, OperatorNode node, String operator) {
        // stat/lstat have special scalar context behavior:
        // - Empty list (failure) -> "" (empty string)
        // - Non-empty list (success) -> 1 (true)
        
        if (node.operand instanceof IdentifierNode identNode &&
                identNode.name.equals("_")) {
            // stat _ or lstat _ - still use the old methods since they don't take args
            emitterVisitor.ctx.mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "org/perlonjava/operators/Stat",
                    operator + "LastHandle",
                    "()Lorg/perlonjava/runtime/RuntimeList;",
                    false);
            // Handle context - treat as list that needs conversion
            if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
                handleVoidContext(emitterVisitor);
            } else if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR) {
                // Convert with stat's special semantics
                emitterVisitor.ctx.mv.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/RuntimeList",
                        "statScalar",
                        "()Lorg/perlonjava/runtime/RuntimeScalar;",
                        false);
            }
        } else {
            // stat EXPR or lstat EXPR - use context-aware methods
            node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
            emitterVisitor.pushCallContext();  // Push context onto stack
            emitterVisitor.ctx.mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "org/perlonjava/operators/Stat",
                    operator,
                    "(Lorg/perlonjava/runtime/RuntimeScalar;I)Lorg/perlonjava/runtime/RuntimeBase;",
                    false);
            
            // Cast to the appropriate type for the bytecode verifier
            if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR) {
                // In scalar context, stat returns RuntimeScalar
                emitterVisitor.ctx.mv.visitTypeInsn(Opcodes.CHECKCAST, "org/perlonjava/runtime/RuntimeScalar");
            } else if (emitterVisitor.ctx.contextType == RuntimeContextType.LIST) {
                // In list context, stat returns RuntimeList
                emitterVisitor.ctx.mv.visitTypeInsn(Opcodes.CHECKCAST, "org/perlonjava/runtime/RuntimeList");
            }
            // In RUNTIME or VOID context, leave as RuntimeBase (no cast needed)
            
            // Handle void context
            if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
                handleVoidContext(emitterVisitor);
            }
        }
    }

    /**
     * Handles standard unary operators with default processing logic.
     *
     * @param node           The operator node
     * @param operator       The operator string
     * @param emitterVisitor The visitor walking the AST
     */
    static void handleUnaryDefaultCase(OperatorNode node, String operator,
                                       EmitterVisitor emitterVisitor) {
        MethodVisitor mv = emitterVisitor.ctx.mv;
        node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        OperatorHandler operatorHandler = OperatorHandler.get(node.operator);
        if (operatorHandler != null) {
            emitOperator(node, emitterVisitor);
        } else {
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/RuntimeScalar",
                    operator,
                    "()Lorg/perlonjava/runtime/RuntimeScalar;",
                    false);
            handleVoidContext(emitterVisitor);
        }
    }

    /**
     * Handles the 'length' operator, which can be affected by 'use bytes'.
     *
     * @param node           The operator node
     * @param emitterVisitor The visitor walking the AST
     */
    static void handleLengthOperator(OperatorNode node, EmitterVisitor emitterVisitor) {
        MethodVisitor mv = emitterVisitor.ctx.mv;
        // Emit the operand in scalar context
        node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));

        // Check if 'use bytes' is in effect
        if (emitterVisitor.ctx.symbolTable != null &&
                emitterVisitor.ctx.symbolTable.isStrictOptionEnabled(Strict.HINT_BYTES)) {
            emitterVisitor.ctx.logDebug("handleLengthOperator: Using lengthBytes (bytes pragma enabled)");
            // Use lengthBytes when bytes pragma is in effect
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/operators/StringOperators",
                    "lengthBytes",
                    "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;",
                    false);
        } else {
            emitterVisitor.ctx.logDebug("handleLengthOperator: Using normal length (bytes pragma not enabled)");
            // Use normal length
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/operators/StringOperators",
                    "length",
                    "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;",
                    false);
        }
        handleVoidContext(emitterVisitor);
    }

    /**
     * Handles the 'chr' operator, which can be affected by 'use bytes'.
     *
     * @param node           The operator node
     * @param emitterVisitor The visitor walking the AST
     */
    static void handleChrOperator(OperatorNode node, EmitterVisitor emitterVisitor) {
        MethodVisitor mv = emitterVisitor.ctx.mv;
        // Emit the operand in scalar context
        node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));

        // Check if 'use bytes' is in effect
        if (emitterVisitor.ctx.symbolTable != null &&
                emitterVisitor.ctx.symbolTable.isStrictOptionEnabled(Strict.HINT_BYTES)) {
            emitterVisitor.ctx.logDebug("handleChrOperator: Using chrBytes (bytes pragma enabled)");
            // Use chrBytes when bytes pragma is in effect
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/operators/StringOperators",
                    "chrBytes",
                    "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;",
                    false);
        } else {
            emitterVisitor.ctx.logDebug("handleChrOperator: Using normal chr (bytes pragma not enabled)");
            // Use normal chr
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/operators/StringOperators",
                    "chr",
                    "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;",
                    false);
        }
        handleVoidContext(emitterVisitor);
    }

    /**
     * Handles the 'ord' operator, which can be affected by 'use bytes'.
     *
     * @param node           The operator node
     * @param emitterVisitor The visitor walking the AST
     */
    static void handleOrdOperator(OperatorNode node, EmitterVisitor emitterVisitor) {
        MethodVisitor mv = emitterVisitor.ctx.mv;
        // Emit the operand in scalar context
        node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));

        // Check if 'use bytes' is in effect
        if (emitterVisitor.ctx.symbolTable != null &&
                emitterVisitor.ctx.symbolTable.isStrictOptionEnabled(Strict.HINT_BYTES)) {
            emitterVisitor.ctx.logDebug("handleOrdOperator: Using ordBytes (bytes pragma enabled)");
            // Use ordBytes when bytes pragma is in effect
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/operators/ScalarOperators",
                    "ordBytes",
                    "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;",
                    false);
        } else {
            emitterVisitor.ctx.logDebug("handleOrdOperator: Using normal ord (bytes pragma not enabled)");
            // Use normal ord
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/operators/ScalarOperators",
                    "ord",
                    "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;",
                    false);
        }
        handleVoidContext(emitterVisitor);
    }

    /**
     * Handles the fc (case fold) operator with support for 'use bytes'.
     */
    static void handleFcOperator(OperatorNode node, EmitterVisitor emitterVisitor) {
        MethodVisitor mv = emitterVisitor.ctx.mv;
        node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));

        if (emitterVisitor.ctx.symbolTable != null &&
                emitterVisitor.ctx.symbolTable.isStrictOptionEnabled(Strict.HINT_BYTES)) {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/operators/StringOperators",
                    "fcBytes",
                    "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;",
                    false);
        } else {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/operators/StringOperators",
                    "fc",
                    "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;",
                    false);
        }
        handleVoidContext(emitterVisitor);
    }

    /**
     * Handles the lc (lowercase) operator with support for 'use bytes'.
     */
    static void handleLcOperator(OperatorNode node, EmitterVisitor emitterVisitor) {
        MethodVisitor mv = emitterVisitor.ctx.mv;
        node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));

        if (emitterVisitor.ctx.symbolTable != null &&
                emitterVisitor.ctx.symbolTable.isStrictOptionEnabled(Strict.HINT_BYTES)) {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/operators/StringOperators",
                    "lcBytes",
                    "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;",
                    false);
        } else {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/operators/StringOperators",
                    "lc",
                    "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;",
                    false);
        }
        handleVoidContext(emitterVisitor);
    }

    /**
     * Handles the uc (uppercase) operator with support for 'use bytes'.
     */
    static void handleUcOperator(OperatorNode node, EmitterVisitor emitterVisitor) {
        MethodVisitor mv = emitterVisitor.ctx.mv;
        node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));

        if (emitterVisitor.ctx.symbolTable != null &&
                emitterVisitor.ctx.symbolTable.isStrictOptionEnabled(Strict.HINT_BYTES)) {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/operators/StringOperators",
                    "ucBytes",
                    "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;",
                    false);
        } else {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/operators/StringOperators",
                    "uc",
                    "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;",
                    false);
        }
        handleVoidContext(emitterVisitor);
    }

    /**
     * Handles the lcfirst operator with support for 'use bytes'.
     */
    static void handleLcfirstOperator(OperatorNode node, EmitterVisitor emitterVisitor) {
        MethodVisitor mv = emitterVisitor.ctx.mv;
        node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));

        if (emitterVisitor.ctx.symbolTable != null &&
                emitterVisitor.ctx.symbolTable.isStrictOptionEnabled(Strict.HINT_BYTES)) {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/operators/StringOperators",
                    "lcfirstBytes",
                    "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;",
                    false);
        } else {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/operators/StringOperators",
                    "lcfirst",
                    "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;",
                    false);
        }
        handleVoidContext(emitterVisitor);
    }

    /**
     * Handles the ucfirst operator with support for 'use bytes'.
     */
    static void handleUcfirstOperator(OperatorNode node, EmitterVisitor emitterVisitor) {
        MethodVisitor mv = emitterVisitor.ctx.mv;
        node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));

        if (emitterVisitor.ctx.symbolTable != null &&
                emitterVisitor.ctx.symbolTable.isStrictOptionEnabled(Strict.HINT_BYTES)) {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/operators/StringOperators",
                    "ucfirstBytes",
                    "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;",
                    false);
        } else {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/operators/StringOperators",
                    "ucfirst",
                    "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;",
                    false);
        }
        handleVoidContext(emitterVisitor);
    }

    /**
     * Handles array-specific unary builtin operators.
     *
     * @param emitterVisitor The visitor walking the AST
     * @param node           The operator node
     * @param operator       The operator string
     */
    static void handleArrayUnaryBuiltin(EmitterVisitor emitterVisitor, OperatorNode node,
                                        String operator) {
        Node operand = node.operand;
        emitterVisitor.ctx.logDebug("handleArrayUnaryBuiltin " + operand);
        if (operand instanceof ListNode listNode) {
            operand = listNode.elements.getFirst();
        }
        operand.accept(emitterVisitor.with(RuntimeContextType.LIST));
        emitOperator(node, emitterVisitor);
    }

    /**
     * Handles creation of references (backslash operator).
     *
     * @param emitterVisitor The visitor walking the AST
     * @param node           The operator node
     */
    static void handleCreateReference(EmitterVisitor emitterVisitor, OperatorNode node) {
        MethodVisitor mv = emitterVisitor.ctx.mv;
        if (resultIsList(node)) {
            node.operand.accept(emitterVisitor.with(RuntimeContextType.LIST));
            emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/RuntimeList",
                    "createListReference",
                    "()Lorg/perlonjava/runtime/RuntimeList;",
                    false);
            if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
                handleVoidContext(emitterVisitor);
            } else if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR) {
                handleScalarContext(emitterVisitor, node);
            }
        } else {
            if (node.operand instanceof OperatorNode operatorNode &&
                    operatorNode.operator.equals("&")) {
                emitterVisitor.ctx.logDebug("Handle \\& " + operatorNode.operand);
                if (operatorNode.operand instanceof OperatorNode ||
                        operatorNode.operand instanceof BlockNode) {
                    operatorNode.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                    emitterVisitor.pushCurrentPackage();
                    emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                            "org/perlonjava/runtime/RuntimeCode",
                            "createCodeReference",
                            "(Lorg/perlonjava/runtime/RuntimeScalar;Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeScalar;",
                            false);
                } else {
                    node.operand.accept(emitterVisitor.with(RuntimeContextType.LIST));
                }
            } else {
                // Determine context based on operand type
                // Function calls and scalar expressions should use SCALAR context
                // Arrays, hashes, and lists should use LIST context
                int contextType = RuntimeContextType.LIST;
                
                if (node.operand instanceof BinaryOperatorNode binOp && binOp.operator.equals("(")) {
                    // Function call - use SCALAR context to get single return value
                    contextType = RuntimeContextType.SCALAR;
                } else if (node.operand instanceof OperatorNode op && op.operator.equals("$")) {
                    // Scalar variable - use SCALAR context
                    contextType = RuntimeContextType.SCALAR;
                }
                
                node.operand.accept(emitterVisitor.with(contextType));
                
                // Always create a proper reference - don't special case CODE references
                emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/RuntimeBase",
                        "createReference",
                        "()Lorg/perlonjava/runtime/RuntimeScalar;",
                        false);
            }
            handleVoidContext(emitterVisitor);
        }
    }

    private static boolean resultIsList(OperatorNode node) {
        if (node.operand instanceof ListNode) {
            return true;
        }

        // Check for my/our/state/local with a list operand: \my ($a, $b), \my \($a, $b)
        if (node.operand instanceof OperatorNode opNode) {
            String op = opNode.operator;
            if ("my".equals(op) || "our".equals(op) || "state".equals(op) || "local".equals(op)) {
                if (opNode.operand instanceof ListNode) {
                    return true;
                }
            }
        }

        // Check for slice operations: %x{...}, @x{...}, @x[...]
        if (node.operand instanceof BinaryOperatorNode binOp) {
            // Check if it's a hash slice "{" or array slice "["
            if ("{".equals(binOp.operator) || "[".equals(binOp.operator)) {
                // Check if the left side is % or @ operator
                if (binOp.left instanceof OperatorNode opNode) {
                    return "%".equals(opNode.operator) || "@".equals(opNode.operator);
                }
            }

            // Check if it is an apply `->()`
            return binOp.operator.equals("->") && binOp.right instanceof ListNode;
        }

        return false;
    }

    static void emitUndef(MethodVisitor mv) {
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/operators/Operator", "undef", "()Lorg/perlonjava/runtime/RuntimeScalar;", false);
    }

    // ... rest of the code remains the same ...
}
