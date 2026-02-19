package org.perlonjava.interpreter;

import org.perlonjava.astnode.*;
import org.perlonjava.runtime.RuntimeContextType;

public class CompileBinaryOperator {
    static void visitBinaryOperator(BytecodeCompiler bytecodeCompiler, BinaryOperatorNode node) {
        // Track token index for error reporting
        bytecodeCompiler.currentTokenIndex = node.getIndex();

        // Handle print/say early (special handling for filehandle)
        if (node.operator.equals("print") || node.operator.equals("say")) {
            // print/say FILEHANDLE LIST
            // left = filehandle reference (\*STDERR)
            // right = list to print

            // Compile the filehandle (left operand)
            node.left.accept(bytecodeCompiler);
            int filehandleReg = bytecodeCompiler.lastResultReg;

            // Compile the content (right operand)
            node.right.accept(bytecodeCompiler);
            int contentReg = bytecodeCompiler.lastResultReg;

            // Emit PRINT or SAY with both registers
            bytecodeCompiler.emit(node.operator.equals("say") ? Opcodes.SAY : Opcodes.PRINT);
            bytecodeCompiler.emitReg(contentReg);
            bytecodeCompiler.emitReg(filehandleReg);

            // print/say return 1 on success
            int rd = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.LOAD_INT);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitInt(1);

            bytecodeCompiler.lastResultReg = rd;
            return;
        }

        // Handle compound assignment operators (+=, -=, *=, /=, %=, .=, &=, |=, ^=, &.=, |.=, ^.=, binary&=, binary|=, binary^=, x=, **=, <<=, >>=, &&=, ||=)
        if (node.operator.equals("+=") || node.operator.equals("-=") ||
                node.operator.equals("*=") || node.operator.equals("/=") ||
                node.operator.equals("%=") || node.operator.equals(".=") ||
                node.operator.equals("&=") || node.operator.equals("|=") || node.operator.equals("^=") ||
                node.operator.equals("&.=") || node.operator.equals("|.=") || node.operator.equals("^.=") ||
                node.operator.equals("x=") || node.operator.equals("**=") ||
                node.operator.equals("<<=") || node.operator.equals(">>=") ||
                node.operator.equals("&&=") || node.operator.equals("||=") ||
                node.operator.startsWith("binary")) {  // Handle binary&=, binary|=, binary^=
            bytecodeCompiler.handleCompoundAssignment(node);
            return;
        }

        // Handle assignment separately (doesn't follow standard left-right-op pattern)
        if (node.operator.equals("=")) {
            CompileAssignment.compileAssignmentOperator(bytecodeCompiler, node);
            return;
        }


        // Handle -> operator specially for hashref/arrayref dereference
        if (node.operator.equals("->")) {
            bytecodeCompiler.currentTokenIndex = node.getIndex();  // Track token for error reporting

            if (node.right instanceof HashLiteralNode) {
                // Hashref dereference: $ref->{key}
                // left: scalar containing hash reference
                // right: HashLiteralNode containing key

                // Compile the reference (left side)
                node.left.accept(bytecodeCompiler);
                int scalarRefReg = bytecodeCompiler.lastResultReg;

                // Dereference the scalar to get the actual hash
                int hashReg = bytecodeCompiler.allocateRegister();
                bytecodeCompiler.emitWithToken(Opcodes.DEREF_HASH, node.getIndex());
                bytecodeCompiler.emitReg(hashReg);
                bytecodeCompiler.emitReg(scalarRefReg);

                // Get the key
                HashLiteralNode keyNode = (HashLiteralNode) node.right;
                if (keyNode.elements.isEmpty()) {
                    bytecodeCompiler.throwCompilerException("Hash dereference requires key");
                }

                // Compile the key - handle bareword autoquoting
                int keyReg;
                Node keyElement = keyNode.elements.get(0);
                if (keyElement instanceof IdentifierNode) {
                    // Bareword key: $ref->{key} -> key is autoquoted
                    String keyString = ((IdentifierNode) keyElement).name;
                    keyReg = bytecodeCompiler.allocateRegister();
                    int keyIdx = bytecodeCompiler.addToStringPool(keyString);
                    bytecodeCompiler.emit(Opcodes.LOAD_STRING);
                    bytecodeCompiler.emitReg(keyReg);
                    bytecodeCompiler.emit(keyIdx);
                } else {
                    // Expression key: $ref->{$var}
                    keyElement.accept(bytecodeCompiler);
                    keyReg = bytecodeCompiler.lastResultReg;
                }

                // Access hash element
                int rd = bytecodeCompiler.allocateRegister();
                bytecodeCompiler.emit(Opcodes.HASH_GET);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(hashReg);
                bytecodeCompiler.emitReg(keyReg);

                bytecodeCompiler.lastResultReg = rd;
                return;
            } else if (node.right instanceof ArrayLiteralNode) {
                // Arrayref dereference: $ref->[index]
                // left: scalar containing array reference
                // right: ArrayLiteralNode containing index

                // Compile the reference (left side)
                node.left.accept(bytecodeCompiler);
                int scalarRefReg = bytecodeCompiler.lastResultReg;

                // Dereference the scalar to get the actual array
                int arrayReg = bytecodeCompiler.allocateRegister();
                bytecodeCompiler.emitWithToken(Opcodes.DEREF_ARRAY, node.getIndex());
                bytecodeCompiler.emitReg(arrayReg);
                bytecodeCompiler.emitReg(scalarRefReg);

                // Get the index
                ArrayLiteralNode indexNode = (ArrayLiteralNode) node.right;
                if (indexNode.elements.isEmpty()) {
                    bytecodeCompiler.throwCompilerException("Array dereference requires index");
                }

                // Compile the index expression
                indexNode.elements.get(0).accept(bytecodeCompiler);
                int indexReg = bytecodeCompiler.lastResultReg;

                // Access array element
                int rd = bytecodeCompiler.allocateRegister();
                bytecodeCompiler.emit(Opcodes.ARRAY_GET);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(arrayReg);
                bytecodeCompiler.emitReg(indexReg);

                bytecodeCompiler.lastResultReg = rd;
                return;
            }
            // Code reference call: $code->() or $code->(@args)
            // right is ListNode with arguments
            else if (node.right instanceof ListNode) {
                // This is a code reference call: $coderef->(args)
                // Compile the code reference in scalar context
                int savedContext = bytecodeCompiler.currentCallContext;
                bytecodeCompiler.currentCallContext = RuntimeContextType.SCALAR;
                node.left.accept(bytecodeCompiler);
                int coderefReg = bytecodeCompiler.lastResultReg;

                // Compile arguments in list context
                bytecodeCompiler.currentCallContext = RuntimeContextType.LIST;
                node.right.accept(bytecodeCompiler);
                int argsReg = bytecodeCompiler.lastResultReg;
                bytecodeCompiler.currentCallContext = savedContext;

                // Allocate result register
                int rd = bytecodeCompiler.allocateRegister();

                // Emit CALL_SUB opcode
                bytecodeCompiler.emit(Opcodes.CALL_SUB);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(coderefReg);
                bytecodeCompiler.emitReg(argsReg);
                bytecodeCompiler.emit(bytecodeCompiler.currentCallContext);

                bytecodeCompiler.lastResultReg = rd;
                return;
            }
            // Method call: ->method() or ->$method()
            // right is BinaryOperatorNode with operator "("
            else if (node.right instanceof BinaryOperatorNode) {
                BinaryOperatorNode rightCall = (BinaryOperatorNode) node.right;
                if (rightCall.operator.equals("(")) {
                    // object.call(method, arguments, context)
                    Node invocantNode = node.left;
                    Node methodNode = rightCall.left;
                    Node argsNode = rightCall.right;

                    // Convert class name to string if needed: Class->method()
                    if (invocantNode instanceof IdentifierNode) {
                        String className = ((IdentifierNode) invocantNode).name;
                        invocantNode = new StringNode(className, ((IdentifierNode) invocantNode).getIndex());
                    }

                    // Convert method name to string if needed
                    if (methodNode instanceof OperatorNode) {
                        OperatorNode methodOp = (OperatorNode) methodNode;
                        // &method is introduced by parser if method is predeclared
                        if (methodOp.operator.equals("&")) {
                            methodNode = methodOp.operand;
                        }
                    }
                    if (methodNode instanceof IdentifierNode) {
                        String methodName = ((IdentifierNode) methodNode).name;
                        methodNode = new StringNode(methodName, ((IdentifierNode) methodNode).getIndex());
                    }

                    // Compile invocant in scalar context
                    int savedContext = bytecodeCompiler.currentCallContext;
                    bytecodeCompiler.currentCallContext = RuntimeContextType.SCALAR;
                    invocantNode.accept(bytecodeCompiler);
                    int invocantReg = bytecodeCompiler.lastResultReg;

                    // Compile method name in scalar context
                    methodNode.accept(bytecodeCompiler);
                    int methodReg = bytecodeCompiler.lastResultReg;

                    // Get currentSub (__SUB__ for SUPER:: resolution)
                    int currentSubReg = bytecodeCompiler.allocateRegister();
                    bytecodeCompiler.emit(Opcodes.LOAD_GLOBAL_CODE);
                    bytecodeCompiler.emitReg(currentSubReg);
                    int subIdx = bytecodeCompiler.addToStringPool("__SUB__");
                    bytecodeCompiler.emit(subIdx);

                    // Compile arguments in list context
                    bytecodeCompiler.currentCallContext = RuntimeContextType.LIST;
                    argsNode.accept(bytecodeCompiler);
                    int argsReg = bytecodeCompiler.lastResultReg;
                    bytecodeCompiler.currentCallContext = savedContext;

                    // Allocate result register
                    int rd = bytecodeCompiler.allocateRegister();

                    // Emit CALL_METHOD
                    bytecodeCompiler.emit(Opcodes.CALL_METHOD);
                    bytecodeCompiler.emitReg(rd);
                    bytecodeCompiler.emitReg(invocantReg);
                    bytecodeCompiler.emitReg(methodReg);
                    bytecodeCompiler.emitReg(currentSubReg);
                    bytecodeCompiler.emitReg(argsReg);
                    bytecodeCompiler.emit(bytecodeCompiler.currentCallContext);

                    bytecodeCompiler.lastResultReg = rd;
                    return;
                }
            }
            // Otherwise, fall through to normal -> handling (method call)
        }

        // Handle [] operator for array access
        // Must be before automatic operand compilation to handle array slices
        if (node.operator.equals("[")) {
            bytecodeCompiler.currentTokenIndex = node.getIndex();

            // Check if this is an array slice: @array[indices]
            if (node.left instanceof OperatorNode) {
                OperatorNode leftOp = (OperatorNode) node.left;
                if (leftOp.operator.equals("@")) {
                    // This is an array slice - handle it specially
                    bytecodeCompiler.handleArraySlice(node, leftOp);
                    return;
                }

                // Handle normal array element access: $array[index]
                if (leftOp.operator.equals("$") && leftOp.operand instanceof IdentifierNode) {
                    bytecodeCompiler.handleArrayElementAccess(node, leftOp);
                    return;
                }
            }

            // Handle general case: expr[index]
            // This covers cases like $matrix[1][0] where $matrix[1] is an expression
            bytecodeCompiler.handleGeneralArrayAccess(node);
            return;
        }

        // Handle {} operator specially for hash slice operations
        // Must be before automatic operand compilation to avoid compiling @ operator
        if (node.operator.equals("{")) {
            bytecodeCompiler.currentTokenIndex = node.getIndex();

            // Check if this is a hash slice: @hash{keys} or @$hashref{keys}
            if (node.left instanceof OperatorNode) {
                OperatorNode leftOp = (OperatorNode) node.left;
                if (leftOp.operator.equals("@")) {
                    // This is a hash slice - handle it specially
                    bytecodeCompiler.handleHashSlice(node, leftOp);
                    return;
                }

                // Handle normal hash element access: $hash{key}
                if (leftOp.operator.equals("$") && leftOp.operand instanceof IdentifierNode) {
                    bytecodeCompiler.handleHashElementAccess(node, leftOp);
                    return;
                }
            }

            // Handle general case: expr{key}
            // This covers cases like $hash{outer}{inner} where $hash{outer} is an expression
            bytecodeCompiler.handleGeneralHashAccess(node);
            return;
        }

        // Handle push/unshift operators
        if (node.operator.equals("push") || node.operator.equals("unshift")) {
            bytecodeCompiler.handlePushUnshift(node);
            return;
        }

        // Handle "join" operator specially to ensure proper context
        // Left operand (separator) needs SCALAR context, right operand (list) needs LIST context
        if (node.operator.equals("join")) {
            // Save and set context for left operand (separator)
            int savedContext = bytecodeCompiler.currentCallContext;
            bytecodeCompiler.currentCallContext = RuntimeContextType.SCALAR;
            node.left.accept(bytecodeCompiler);
            int rs1 = bytecodeCompiler.lastResultReg;

            // Set context for right operand (array/list)
            bytecodeCompiler.currentCallContext = RuntimeContextType.LIST;
            node.right.accept(bytecodeCompiler);
            int rs2 = bytecodeCompiler.lastResultReg;
            bytecodeCompiler.currentCallContext = savedContext;

            // Emit JOIN opcode
            int rd = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.JOIN);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(rs1);
            bytecodeCompiler.emitReg(rs2);

            bytecodeCompiler.lastResultReg = rd;
            return;
        }

        // Handle function call operators specially to ensure arguments are in LIST context
        if (node.operator.equals("(") || node.operator.equals("()")) {
            // Function call: subname(args) or $coderef->(args)
            // Save and set context for left operand (code reference)
            int savedContext = bytecodeCompiler.currentCallContext;
            bytecodeCompiler.currentCallContext = RuntimeContextType.SCALAR;
            node.left.accept(bytecodeCompiler);
            int rs1 = bytecodeCompiler.lastResultReg;

            // Arguments must ALWAYS be evaluated in LIST context
            // Even if the call itself is in SCALAR context (e.g., scalar(func()))
            bytecodeCompiler.currentCallContext = RuntimeContextType.LIST;
            node.right.accept(bytecodeCompiler);
            int rs2 = bytecodeCompiler.lastResultReg;
            bytecodeCompiler.currentCallContext = savedContext;

            // Emit CALL_SUB opcode
            int rd = CompileBinaryOperatorHelper.compileBinaryOperatorSwitch(bytecodeCompiler, node.operator, rs1, rs2, node.getIndex());
            bytecodeCompiler.lastResultReg = rd;
            return;
        }

        // Handle short-circuit operators specially - don't compile right operand yet!
        if (node.operator.equals("&&") || node.operator.equals("and")) {
            // Logical AND with short-circuit evaluation
            // Only evaluate right side if left side is true

            // Compile left operand in scalar context (need boolean value)
            int savedContext = bytecodeCompiler.currentCallContext;
            bytecodeCompiler.currentCallContext = RuntimeContextType.SCALAR;
            node.left.accept(bytecodeCompiler);
            int rs1 = bytecodeCompiler.lastResultReg;
            bytecodeCompiler.currentCallContext = savedContext;

            // Allocate result register and move left value to it
            int rd = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.MOVE);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(rs1);

            // Mark position for forward jump
            int skipRightPos = bytecodeCompiler.bytecode.size();

            // Emit conditional jump: if (!rd) skip right evaluation
            bytecodeCompiler.emit(Opcodes.GOTO_IF_FALSE);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitInt(0); // Placeholder for offset (will be patched)

            // NOW compile right operand (only executed if left was true)
            node.right.accept(bytecodeCompiler);
            int rs2 = bytecodeCompiler.lastResultReg;

            // Move right result to rd (overwriting left value)
            bytecodeCompiler.emit(Opcodes.MOVE);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(rs2);

            // Patch the forward jump offset
            int skipRightTarget = bytecodeCompiler.bytecode.size();
            bytecodeCompiler.patchIntOffset(skipRightPos + 2, skipRightTarget);

            bytecodeCompiler.lastResultReg = rd;
            return;
        }

        if (node.operator.equals("||") || node.operator.equals("or")) {
            // Logical OR with short-circuit evaluation
            // Only evaluate right side if left side is false

            // Compile left operand in scalar context (need boolean value)
            int savedContext = bytecodeCompiler.currentCallContext;
            bytecodeCompiler.currentCallContext = RuntimeContextType.SCALAR;
            node.left.accept(bytecodeCompiler);
            int rs1 = bytecodeCompiler.lastResultReg;
            bytecodeCompiler.currentCallContext = savedContext;

            // Allocate result register and move left value to it
            int rd = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.MOVE);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(rs1);

            // Mark position for forward jump
            int skipRightPos = bytecodeCompiler.bytecode.size();

            // Emit conditional jump: if (rd) skip right evaluation
            bytecodeCompiler.emit(Opcodes.GOTO_IF_TRUE);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitInt(0); // Placeholder for offset (will be patched)

            // NOW compile right operand (only executed if left was false)
            node.right.accept(bytecodeCompiler);
            int rs2 = bytecodeCompiler.lastResultReg;

            // Move right result to rd (overwriting left value)
            bytecodeCompiler.emit(Opcodes.MOVE);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(rs2);

            // Patch the forward jump offset
            int skipRightTarget = bytecodeCompiler.bytecode.size();
            bytecodeCompiler.patchIntOffset(skipRightPos + 2, skipRightTarget);

            bytecodeCompiler.lastResultReg = rd;
            return;
        }

        if (node.operator.equals("//")) {
            // Defined-OR with short-circuit evaluation
            // Only evaluate right side if left side is undefined

            // Compile left operand in scalar context (need to test definedness)
            int savedContext = bytecodeCompiler.currentCallContext;
            bytecodeCompiler.currentCallContext = RuntimeContextType.SCALAR;
            node.left.accept(bytecodeCompiler);
            int rs1 = bytecodeCompiler.lastResultReg;
            bytecodeCompiler.currentCallContext = savedContext;

            // Allocate result register and move left value to it
            int rd = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.MOVE);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(rs1);

            // Check if left is defined
            int definedReg = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.DEFINED);
            bytecodeCompiler.emitReg(definedReg);
            bytecodeCompiler.emitReg(rd);

            // Mark position for forward jump
            int skipRightPos = bytecodeCompiler.bytecode.size();

            // Emit conditional jump: if (defined) skip right evaluation
            bytecodeCompiler.emit(Opcodes.GOTO_IF_TRUE);
            bytecodeCompiler.emitReg(definedReg);
            bytecodeCompiler.emitInt(0); // Placeholder for offset (will be patched)

            // NOW compile right operand (only executed if left was undefined)
            node.right.accept(bytecodeCompiler);
            int rs2 = bytecodeCompiler.lastResultReg;

            // Move right result to rd (overwriting left value)
            bytecodeCompiler.emit(Opcodes.MOVE);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(rs2);

            // Patch the forward jump offset
            int skipRightTarget = bytecodeCompiler.bytecode.size();
            bytecodeCompiler.patchIntOffset(skipRightPos + 2, skipRightTarget);

            bytecodeCompiler.lastResultReg = rd;
            return;
        }

        // Compile left and right operands (for non-short-circuit operators)
        node.left.accept(bytecodeCompiler);
        int rs1 = bytecodeCompiler.lastResultReg;

        node.right.accept(bytecodeCompiler);
        int rs2 = bytecodeCompiler.lastResultReg;

        // Emit opcode based on operator (delegated to helper method)
        int rd = CompileBinaryOperatorHelper.compileBinaryOperatorSwitch(bytecodeCompiler, node.operator, rs1, rs2, node.getIndex());


        bytecodeCompiler.lastResultReg = rd;
    }
}
