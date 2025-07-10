package org.perlonjava.codegen;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.*;
import org.perlonjava.astvisitor.EmitterVisitor;
import org.perlonjava.astvisitor.LValueVisitor;
import org.perlonjava.astvisitor.ReturnTypeVisitor;
import org.perlonjava.operators.OperatorHandler;
import org.perlonjava.operators.ScalarGlobOperator;
import org.perlonjava.runtime.NameNormalizer;
import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeContextType;
import org.perlonjava.runtime.RuntimeTypeConstants;

/**
 * The EmitOperator class is responsible for handling various operators
 * and generating the corresponding bytecode using ASM.
 */
public class EmitOperator {

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
        emitterVisitor.ctx.mv.visitMethodInsn(
                operatorHandler.getMethodType(),
                operatorHandler.getClassName(),
                operatorHandler.getMethodName(),
                operatorHandler.getDescriptor(),
                operatorHandler.getMethodType() == Opcodes.INVOKEINTERFACE
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
            // Push call context for SCALAR or LIST context.
            emitterVisitor.pushCallContext();
            // Invoke the static method for the operator.
            emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/operators/Readline", operator, "(Lorg/perlonjava/runtime/RuntimeScalar;I)Lorg/perlonjava/runtime/RuntimeDataProvider;", false);
        } else {
            // Invoke the static method for the operator without context.
            emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/operators/Operator", operator, "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        }

        // If the context is VOID, pop the result from the stack.
        handleVoidContext(emitterVisitor);
    }

    static void handleBinmodeOperator(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        // Emit the File Handle
        emitFileHandle(emitterVisitor.with(RuntimeContextType.SCALAR), node.left);

        // Accept the right operand in LIST context
        node.right.accept(emitterVisitor.with(RuntimeContextType.LIST));

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
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/operators/Operator", operator, "(Lorg/perlonjava/runtime/RuntimeList;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
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
                // Accept the first two elements in SCALAR context.
                operand.elements.get(0).accept(scalarVisitor);
                operand.elements.get(1).accept(scalarVisitor);
                if (operand.elements.size() == 3) {
                    // Accept the third element if it exists.
                    operand.elements.get(2).accept(scalarVisitor);
                } else {
                    // Otherwise, use 'undef' as the third element.
                    new OperatorNode("undef", null, node.tokenIndex).accept(scalarVisitor);
                }
                // Invoke the virtual method for the operator.
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

    // Handles the 'die' built-in function, which throws an exception.
    static void handleDieBuiltin(EmitterVisitor emitterVisitor, OperatorNode node) {
        // Handle:  die LIST
        //   static RuntimeDataProvider die(RuntimeDataProvider value, int ctx)
        emitterVisitor.ctx.logDebug("handleDieBuiltin " + node);
        // Accept the operand in LIST context.
        node.operand.accept(emitterVisitor.with(RuntimeContextType.LIST));

        // Push the formatted line number as a message.
        Node message = new StringNode(emitterVisitor.ctx.errorUtil.errorMessage(node.tokenIndex, ""), node.tokenIndex);
        message.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        emitOperator(node, emitterVisitor);
    }

    // Handles the 'reverse' built-in function, which reverses a list.
    static void handleReverseBuiltin(EmitterVisitor emitterVisitor, OperatorNode node) {
        // Handle:  reverse LIST
        //   static RuntimeDataProvider reverse(RuntimeDataProvider value, int ctx)
        emitterVisitor.ctx.logDebug("handleReverseBuiltin " + node);
        // Accept the operand in LIST context.
        node.operand.accept(emitterVisitor.with(RuntimeContextType.LIST));
        emitterVisitor.pushCallContext();
        emitOperator(node, emitterVisitor);
    }

    // Handles the 'splice' built-in function, which modifies an array.
    static void handleSpliceBuiltin(EmitterVisitor emitterVisitor, OperatorNode node) {
        // Handle:  splice @array, LIST
        emitterVisitor.ctx.logDebug("handleSpliceBuiltin " + node);
        Node args = node.operand;
        // Remove the first element from the list and accept it in LIST context.
        Node operand = ((ListNode) args).elements.removeFirst();
        operand.accept(emitterVisitor.with(RuntimeContextType.LIST));
        // Accept the remaining arguments in LIST context.
        args.accept(emitterVisitor.with(RuntimeContextType.LIST));
        emitOperator(node, emitterVisitor);
    }

    // Handles the 'push' operator, which adds elements to an array.
    static void handlePushOperator(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        // Accept both left and right operands in LIST context.
        node.left.accept(emitterVisitor.with(RuntimeContextType.LIST));
        node.right.accept(emitterVisitor.with(RuntimeContextType.LIST));
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
                    "(Lorg/perlonjava/runtime/RuntimeScalar;I)Lorg/perlonjava/runtime/RuntimeDataProvider;", false);
            // If the context is VOID, pop the result from the stack.
            handleVoidContext(emitterVisitor);
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
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "org/perlonjava/runtime/RuntimeDataProvider",
                node.operator,
                "()Lorg/perlonjava/runtime/RuntimeScalar;", true);
        // If the context is VOID, pop the result from the stack.
        handleVoidContext(emitterVisitor);
    }

    // Handles the 'glob' built-in function, which performs filename expansion.
    static void handleGlobBuiltin(EmitterVisitor emitterVisitor, OperatorNode node) {
        MethodVisitor mv = emitterVisitor.ctx.mv;

        // Generate unique IDs for this glob instance
        int globId = ScalarGlobOperator.currentId++;

        // public static RuntimeDataProvider evaluate(id, patternArg, ctx)
        mv.visitLdcInsn(globId);
        // Accept the operand in SCALAR context.
        node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
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
        node.left.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        node.right.accept(emitterVisitor.with(RuntimeContextType.LIST));
        emitOperator(node, emitterVisitor);
    }

    // Handles the 'repeat' operator, which repeats a string or list a specified number of times.
    static void handleRepeat(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        // Determine the context for the left operand.
        if (node.left instanceof ListNode) {
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
                "(Lorg/perlonjava/runtime/RuntimeDataProvider;Lorg/perlonjava/runtime/RuntimeScalar;I)Lorg/perlonjava/runtime/RuntimeDataProvider;", false);

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
        node.left.accept(scalarVisitor); // target - left parameter
        node.right.accept(scalarVisitor); // right parameter
        emitOperator(node, emitterVisitor);
    }

    // Handles the 'scalar' operator, which forces a list into scalar context.
    static void handleScalar(EmitterVisitor emitterVisitor, OperatorNode node) {
        // Accept the operand in SCALAR context.
        node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
    }

    // Handles the 'local' operator.
    static void handleLocal(EmitterVisitor emitterVisitor, OperatorNode node) {
        // emit the lvalue
        int lvalueContext = LValueVisitor.getContext(node.operand);
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
                    "(Lorg/perlonjava/runtime/RuntimeBaseEntity;)Lorg/perlonjava/runtime/RuntimeBaseEntity;",
                    false);
        } else {
            emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/DynamicVariableManager",
                    "pushLocalVariable",
                    "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;",
                    false);
        }
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
        if (RuntimeTypeConstants.SCALAR_TYPE.equals(rawType)) {
            return; // Already a scalar
        }

        MethodVisitor mv = emitterVisitor.ctx.mv;

        // Convert to scalar based on return type
        if (RuntimeTypeConstants.isKnownRuntimeType(rawType)) {
            // Extract the internal class name from the type descriptor
            String className = RuntimeTypeConstants.descriptorToInternalName(rawType);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, className,
                    "scalar", "()" + RuntimeTypeConstants.SCALAR_TYPE, false);
        } else {
            // For unknown types or any other RuntimeDataProvider implementations
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, RuntimeTypeConstants.DATA_PROVIDER_INTERFACE,
                    "scalar", "()" + RuntimeTypeConstants.SCALAR_TYPE, true);
        }
    }

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
                            // Handle array element operator.
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
        handleVoidContext(emitterVisitor);
    }

    // Handles the 'package' operator, which sets the current package for the symbol table.
    static void handlePackageOperator(EmitterVisitor emitterVisitor, OperatorNode node) {
        // Extract the package name from the operand.
        String name = ((IdentifierNode) node.operand).name;
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
        if (node instanceof OperatorNode || node instanceof BinaryOperatorNode) {
            // If the left node is an operator, accept it in SCALAR context
            node.accept(emitterVisitor);
        } else if (node instanceof IdentifierNode) {
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
        } else if (node instanceof BlockNode) {
            // {STDERR}  or  {$fh}
            // TODO
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
            emitOperator(node, emitterVisitor);
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
        node.operand.accept(emitterVisitor.with(RuntimeContextType.LIST));
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
        emitterVisitor.ctx.mv.visitLdcInsn(node.getBooleanAnnotation("module_true"));
        emitOperator(node, emitterVisitor);
    }

    static void handleStatOperator(EmitterVisitor emitterVisitor, OperatorNode node, String operator) {
        if (node.operand instanceof IdentifierNode identNode &&
                identNode.name.equals("_")) {
            emitterVisitor.ctx.mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "org/perlonjava/operators/Stat",
                    operator + "LastHandle",
                    "()Lorg/perlonjava/runtime/RuntimeList;",
                    false);
            handleVoidContext(emitterVisitor);
        } else {
            handleUnaryDefaultCase(node, operator, emitterVisitor);
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
        if (node.operand instanceof ListNode) {
            node.operand.accept(emitterVisitor.with(RuntimeContextType.LIST));
            emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/RuntimeList",
                    "createListReference",
                    "()Lorg/perlonjava/runtime/RuntimeList;",
                    false);
            handleVoidContext(emitterVisitor);
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
                node.operand.accept(emitterVisitor.with(RuntimeContextType.LIST));
                emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                        "org/perlonjava/runtime/RuntimeDataProvider",
                        "createReference",
                        "()Lorg/perlonjava/runtime/RuntimeScalar;",
                        true);
            }
            handleVoidContext(emitterVisitor);
        }
    }
}
