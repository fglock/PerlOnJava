package org.perlonjava.backend.bytecode;

import org.perlonjava.frontend.analysis.ConstantFoldingVisitor;
import org.perlonjava.frontend.astnode.*;
import org.perlonjava.runtime.runtimetypes.NameNormalizer;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

public class CompileBinaryOperator {
    static void visitBinaryOperator(BytecodeCompiler bytecodeCompiler, BinaryOperatorNode node) {
        // Track token index for error reporting
        bytecodeCompiler.currentTokenIndex = node.getIndex();

        // Handle print/say early (special handling for filehandle)
        if (node.operator.equals("print") || node.operator.equals("say")) {
            // print/say FILEHANDLE LIST
            // left = filehandle reference (\*STDERR)
            // right = list to print

            bytecodeCompiler.compileNode(node.left, -1, bytecodeCompiler.currentCallContext);
            int filehandleReg = bytecodeCompiler.lastResultReg;

            // Compile the content (right operand) in LIST context
            bytecodeCompiler.compileNode(node.right, -1, RuntimeContextType.LIST);
            int contentReg = bytecodeCompiler.lastResultReg;

            // Emit PRINT or SAY with both registers
            bytecodeCompiler.emit(node.operator.equals("say") ? Opcodes.SAY : Opcodes.PRINT);
            bytecodeCompiler.emitReg(contentReg);
            bytecodeCompiler.emitReg(filehandleReg);

            // print/say return 1 on success
            int rd = bytecodeCompiler.allocateOutputRegister();
            bytecodeCompiler.emit(Opcodes.LOAD_INT);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitInt(1);

            bytecodeCompiler.lastResultReg = rd;
            return;
        }

        // Handle sprintf early (special handling for list of arguments)
        if (node.operator.equals("sprintf")) {
            // sprintf $format, @args
            // left = format string
            // right = ListNode of arguments

            bytecodeCompiler.compileNode(node.left, -1, bytecodeCompiler.currentCallContext);
            int formatReg = bytecodeCompiler.lastResultReg;

            // Compile the arguments (right operand) into a list
            // Use LIST context only for array/hash args so they expand;
            // scalar expressions keep current context to avoid wrapping in RuntimeList
            int argsListReg = bytecodeCompiler.allocateRegister();
            if (node.right instanceof ListNode argsList) {
                java.util.List<Integer> argRegs = new java.util.ArrayList<>();
                for (Node arg : argsList.elements) {
                    bytecodeCompiler.compileNode(arg, -1, RuntimeContextType.LIST);
                    argRegs.add(bytecodeCompiler.lastResultReg);
                }
                bytecodeCompiler.emit(Opcodes.CREATE_LIST);
                bytecodeCompiler.emitReg(argsListReg);
                bytecodeCompiler.emit(argRegs.size());
                for (int argReg : argRegs) {
                    bytecodeCompiler.emitReg(argReg);
                }
            } else {
                int rightCtx = isArrayLikeNode(node.right) ? RuntimeContextType.LIST : bytecodeCompiler.currentCallContext;
                bytecodeCompiler.compileNode(node.right, -1, rightCtx);
                int argReg = bytecodeCompiler.lastResultReg;
                bytecodeCompiler.emit(Opcodes.CREATE_LIST);
                bytecodeCompiler.emitReg(argsListReg);
                bytecodeCompiler.emit(1);
                bytecodeCompiler.emitReg(argReg);
            }

            // Call sprintf
            int rd = bytecodeCompiler.allocateOutputRegister();
            bytecodeCompiler.emit(Opcodes.SPRINTF);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(formatReg);
            bytecodeCompiler.emitReg(argsListReg);

            bytecodeCompiler.lastResultReg = rd;
            return;
        }

        // Handle I/O and misc binary operators that use MiscOpcodeHandler (filehandle + args → list)
        switch (node.operator) {
            case "binmode", "seek", "eof", "close", "fileno", "getc", "printf":
                compileBinaryAsListOp(bytecodeCompiler, node);
                return;
            case "tell":
                compileTellBinaryOp(bytecodeCompiler, node);
                return;
            case "join":
                compileJoinBinaryOp(bytecodeCompiler, node);
                return;
            default:
                break;
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
                node.operator.equals("//=") ||
                node.operator.equals("binary&=") ||
                node.operator.equals("binary|=") ||
                node.operator.equals("binary^=")) {
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

            if (node.right instanceof HashLiteralNode keyNode) {
                // Hashref dereference: $ref->{key}
                // left: scalar containing hash reference
                // right: HashLiteralNode containing key

                bytecodeCompiler.compileNode(node.left, -1, RuntimeContextType.SCALAR);
                int scalarRefReg = bytecodeCompiler.lastResultReg;

                if (keyNode.elements.isEmpty()) {
                    bytecodeCompiler.throwCompilerException("Hash dereference requires key");
                }

                // Use helper for hash deref get (handles superoperator + fallback)
                bytecodeCompiler.lastResultReg = bytecodeCompiler.emitHashDerefGet(
                        scalarRefReg, keyNode.elements.get(0), node.getIndex());
                return;
            } else if (node.right instanceof ArrayLiteralNode indexNode) {
                // Arrayref dereference: $ref->[index]
                // left: scalar containing array reference
                // right: ArrayLiteralNode containing index

                bytecodeCompiler.compileNode(node.left, -1, RuntimeContextType.SCALAR);
                int scalarRefReg = bytecodeCompiler.lastResultReg;

                if (indexNode.elements.isEmpty()) {
                    bytecodeCompiler.throwCompilerException("Array dereference requires index");
                }

                // Use helper for array deref get (handles superoperator + fallback)
                bytecodeCompiler.lastResultReg = bytecodeCompiler.emitArrayDerefGet(
                        scalarRefReg, indexNode.elements.get(0), node.getIndex());
                return;
            }
            // Code reference call: $code->() or $code->(@args)
            // right is ListNode with arguments
            else if (node.right instanceof ListNode) {
                // Special case: eval { ... }->() is parsed as BinaryOperatorNode("->", SubroutineNode[useTryCatch], ListNode)
                // The interpreter compiles eval blocks inline (EVAL_TRY/END), so we should NOT emit CALL_SUB
                if (node.left instanceof SubroutineNode sn && sn.useTryCatch) {
                    node.left.accept(bytecodeCompiler);
                    return;
                }

                // This is a code reference call: $coderef->(args)
                bytecodeCompiler.compileNode(node.left, -1, RuntimeContextType.SCALAR);
                int coderefReg = bytecodeCompiler.lastResultReg;

                bytecodeCompiler.compileNode(node.right, -1, RuntimeContextType.LIST);
                int argsReg = bytecodeCompiler.lastResultReg;

                // Allocate result register
                int rd = bytecodeCompiler.allocateOutputRegister();

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
            else if (node.right instanceof BinaryOperatorNode rightCall) {
                if (rightCall.operator.equals("(")) {
                    // object.call(method, arguments, context)
                    Node invocantNode = node.left;
                    Node methodNode = rightCall.left;
                    Node argsNode = rightCall.right;

                    // Convert class name to string if needed: Class->method()
                    if (invocantNode instanceof IdentifierNode) {
                        // Perl strips a trailing `::` from a bareword class name:
                        //   Foo::->bar()  is equivalent to  Foo->bar()
                        String className = ((IdentifierNode) invocantNode).name;
                        if (className.length() > 2 && className.endsWith("::")) {
                            className = className.substring(0, className.length() - 2);
                        }
                        invocantNode = new StringNode(className, invocantNode.getIndex());
                    }

                    // Convert method name to string if needed
                    if (methodNode instanceof OperatorNode methodOp) {
                        // &method is introduced by parser if method is predeclared
                        if (methodOp.operator.equals("&")) {
                            methodNode = methodOp.operand;
                        }
                    }
                    if (methodNode instanceof IdentifierNode) {
                        String methodName = ((IdentifierNode) methodNode).name;
                        methodNode = new StringNode(methodName, methodNode.getIndex());
                    }

                    // Compile invocant in scalar context
                    bytecodeCompiler.compileNode(invocantNode, -1, RuntimeContextType.SCALAR);
                    int invocantReg = bytecodeCompiler.lastResultReg;

                    // Compile method name in scalar context
                    bytecodeCompiler.compileNode(methodNode, -1, RuntimeContextType.SCALAR);
                    int methodReg = bytecodeCompiler.lastResultReg;

                    // Get currentSub (__SUB__ for SUPER:: resolution)
                    int currentSubReg = bytecodeCompiler.allocateRegister();
                    bytecodeCompiler.emit(Opcodes.LOAD_GLOBAL_CODE);
                    bytecodeCompiler.emitReg(currentSubReg);
                    int subIdx = bytecodeCompiler.addToStringPool("__SUB__");
                    bytecodeCompiler.emit(subIdx);

                    // Compile arguments in list context
                    bytecodeCompiler.compileNode(argsNode, -1, RuntimeContextType.LIST);
                    int argsReg = bytecodeCompiler.lastResultReg;

                    // Allocate result register
                    int rd = bytecodeCompiler.allocateOutputRegister();

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
            if (node.left instanceof OperatorNode leftOp) {
                if (leftOp.operator.equals("@")) {
                    // This is an array slice - handle it specially
                    bytecodeCompiler.handleArraySlice(node, leftOp);
                    return;
                }

                // Index/value slice: %array[indices] (and postfix ->%[...] parses into this form)
                if (leftOp.operator.equals("%")) {
                    bytecodeCompiler.handleArrayKeyValueSlice(node, leftOp);
                    return;
                }

                // Handle normal array element access: $array[index]
                if (leftOp.operator.equals("$") && leftOp.operand instanceof IdentifierNode) {
                    bytecodeCompiler.handleArrayElementAccess(node, leftOp);
                    return;
                }

                // Handle symbolic array element access: ${"name"}[index] or ${$ref}[index]
                // In Perl, ${EXPR}[index] does NOT call scalarDeref on EXPR.
                // Instead, it evaluates EXPR and applies the subscript directly.
                // This allows ${$aref}[0] to work even though ${$aref} alone would fail.
                if (leftOp.operator.equals("$") && leftOp.operand instanceof BlockNode blockNode) {
                    bytecodeCompiler.handleSymbolicArrayElementAccess(node, blockNode);
                    return;
                }
            }

            // Handle ListNode case: (expr)[indices] like (caller(0))[0] or (1,2,3,4)[1,2]
            // Use proper list slice semantics: evaluate list, then slice by indices
            if (node.left instanceof ListNode listNode) {
                // Compile the list in LIST context
                bytecodeCompiler.compileNode(listNode, -1, RuntimeContextType.LIST);
                int listReg = bytecodeCompiler.lastResultReg;

                // Compile the indices in LIST context
                ListNode indices = ((ArrayLiteralNode) node.right).asListNode();
                bytecodeCompiler.compileNode(indices, -1, RuntimeContextType.LIST);
                int indicesReg = bytecodeCompiler.lastResultReg;

                // Emit LIST_SLICE opcode: rd = list.getSlice(indices)
                int sliceReg = bytecodeCompiler.allocateOutputRegister();
                bytecodeCompiler.emit(Opcodes.LIST_SLICE);
                bytecodeCompiler.emitReg(sliceReg);
                bytecodeCompiler.emitReg(listReg);
                bytecodeCompiler.emitReg(indicesReg);

                // Handle context conversion: LIST_SLICE returns a RuntimeList,
                // but in scalar context we need to extract the scalar value
                if (bytecodeCompiler.currentCallContext == RuntimeContextType.SCALAR) {
                    int scalarReg = bytecodeCompiler.allocateOutputRegister();
                    bytecodeCompiler.emit(Opcodes.LIST_TO_SCALAR);
                    bytecodeCompiler.emitReg(scalarReg);
                    bytecodeCompiler.emitReg(sliceReg);
                    bytecodeCompiler.lastResultReg = scalarReg;
                } else {
                    bytecodeCompiler.lastResultReg = sliceReg;
                }
                return;
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
            if (node.left instanceof OperatorNode leftOp) {
                if (leftOp.operator.equals("@")) {
                    // This is a hash slice - handle it specially
                    bytecodeCompiler.handleHashSlice(node, leftOp);
                    return;
                }

                // Key/value slice: %hash{keys} (and postfix ->%{...} parses into this form)
                if (leftOp.operator.equals("%")) {
                    bytecodeCompiler.handleHashKeyValueSlice(node, leftOp);
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
            bytecodeCompiler.compileNode(node.left, -1, RuntimeContextType.SCALAR);
            int rs1 = bytecodeCompiler.lastResultReg;

            bytecodeCompiler.compileNode(node.right, -1, RuntimeContextType.LIST);
            int rs2 = bytecodeCompiler.lastResultReg;

            int rd = bytecodeCompiler.allocateOutputRegister();
            bytecodeCompiler.emit(Opcodes.JOIN);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(rs1);
            bytecodeCompiler.emitReg(rs2);

            bytecodeCompiler.lastResultReg = rd;
            return;
        }

        if (node.operator.equals("(") || node.operator.equals("()")) {
            bytecodeCompiler.compileNode(node.left, -1, RuntimeContextType.SCALAR);
            int rs1 = bytecodeCompiler.lastResultReg;

            bytecodeCompiler.compileNode(node.right, -1, RuntimeContextType.LIST);
            int rs2 = bytecodeCompiler.lastResultReg;

            // Check if this is a &func (no parens) call that should share caller's @_
            boolean shareCallerArgs = node.getBooleanAnnotation("shareCallerArgs");

            // Emit CALL_SUB or CALL_SUB_SHARE_ARGS opcode
            int rd = CompileBinaryOperatorHelper.compileBinaryOperatorSwitch(
                    bytecodeCompiler, node.operator, rs1, rs2, node.getIndex(),
                    shareCallerArgs);
            bytecodeCompiler.lastResultReg = rd;
            return;
        }

        // Handle short-circuit operators specially - don't compile right operand yet!
        // But first, try constant folding: if LHS is a compile-time constant, eliminate the branch.
        if (node.operator.equals("&&") || node.operator.equals("and") ||
                node.operator.equals("||") || node.operator.equals("or") ||
                node.operator.equals("//")) {
            Node foldedLHS = ConstantFoldingVisitor.foldConstants(node.left);
            RuntimeScalar constantLHS = ConstantFoldingVisitor.getConstantValue(foldedLHS);
            if (constantLHS != null) {
                boolean testResult;
                if (node.operator.equals("//")) {
                    testResult = constantLHS.getDefinedBoolean();
                } else {
                    testResult = constantLHS.getBoolean();
                }
                // For &&/and: true → emit RHS, false → emit LHS
                // For ||/or: true → emit LHS, false → emit RHS
                // For //: defined → emit LHS, undef → emit RHS
                boolean emitLHS;
                if (node.operator.equals("&&") || node.operator.equals("and")) {
                    emitLHS = !testResult;
                } else {
                    emitLHS = testResult;
                }
                if (emitLHS) {
                    bytecodeCompiler.compileNode(foldedLHS, -1, bytecodeCompiler.currentCallContext);
                } else {
                    bytecodeCompiler.compileNode(node.right, -1, bytecodeCompiler.currentCallContext);
                }
                return;
            }
        }

        if (node.operator.equals("&&") || node.operator.equals("and")) {
            int rd = bytecodeCompiler.allocateOutputRegister();

            bytecodeCompiler.compileNode(node.left, rd, RuntimeContextType.SCALAR);
            int rs1 = bytecodeCompiler.lastResultReg;
            bytecodeCompiler.emitAliasWithTarget(rd, rs1);

            int skipRightPos = bytecodeCompiler.bytecode.size();
            bytecodeCompiler.emit(Opcodes.GOTO_IF_FALSE);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitInt(0);

            int rightCtx = bytecodeCompiler.currentCallContext;
            bytecodeCompiler.compileNode(node.right, rd, rightCtx);
            int rs2 = bytecodeCompiler.lastResultReg;
            if (rs2 >= 0) {
                bytecodeCompiler.emitAliasWithTarget(rd, rs2);
            }

            int skipRightTarget = bytecodeCompiler.bytecode.size();
            bytecodeCompiler.patchIntOffset(skipRightPos + 2, skipRightTarget);

            bytecodeCompiler.lastResultReg = rd;
            return;
        }

        if (node.operator.equals("||") || node.operator.equals("or")) {
            int rd = bytecodeCompiler.allocateOutputRegister();

            bytecodeCompiler.compileNode(node.left, rd, RuntimeContextType.SCALAR);
            int rs1 = bytecodeCompiler.lastResultReg;
            bytecodeCompiler.emitAliasWithTarget(rd, rs1);

            int skipRightPos = bytecodeCompiler.bytecode.size();
            bytecodeCompiler.emit(Opcodes.GOTO_IF_TRUE);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitInt(0);

            int rightCtx = bytecodeCompiler.currentCallContext;
            bytecodeCompiler.compileNode(node.right, rd, rightCtx);
            int rs2 = bytecodeCompiler.lastResultReg;
            if (rs2 >= 0) {
                bytecodeCompiler.emitAliasWithTarget(rd, rs2);
            }

            int skipRightTarget = bytecodeCompiler.bytecode.size();
            bytecodeCompiler.patchIntOffset(skipRightPos + 2, skipRightTarget);

            bytecodeCompiler.lastResultReg = rd;
            return;
        }

        if (node.operator.equals("//")) {
            int rd = bytecodeCompiler.allocateOutputRegister();

            bytecodeCompiler.compileNode(node.left, rd, RuntimeContextType.SCALAR);
            int rs1 = bytecodeCompiler.lastResultReg;
            bytecodeCompiler.emitAliasWithTarget(rd, rs1);

            int definedReg = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.DEFINED);
            bytecodeCompiler.emitReg(definedReg);
            bytecodeCompiler.emitReg(rd);

            int skipRightPos = bytecodeCompiler.bytecode.size();
            bytecodeCompiler.emit(Opcodes.GOTO_IF_TRUE);
            bytecodeCompiler.emitReg(definedReg);
            bytecodeCompiler.emitInt(0);

            int rightCtx = bytecodeCompiler.currentCallContext;
            bytecodeCompiler.compileNode(node.right, rd, rightCtx);
            int rs2 = bytecodeCompiler.lastResultReg;
            if (rs2 >= 0) {
                bytecodeCompiler.emitAliasWithTarget(rd, rs2);
            }

            int skipRightTarget = bytecodeCompiler.bytecode.size();
            bytecodeCompiler.patchIntOffset(skipRightPos + 2, skipRightTarget);

            bytecodeCompiler.lastResultReg = rd;
            return;
        }

        // Handle =~ and !~ binding with regex operators
        // When we have: $string =~ s/pattern/replacement/flags
        // The right side is: OperatorNode(replaceRegex, ListNode[pattern, replacement, flags])
        // We need to add $string to the operand list and compile the operator
        if ((node.operator.equals("=~") || node.operator.equals("!~"))
                && node.right instanceof OperatorNode rightOp) {
            if (rightOp.operand instanceof ListNode originalList
                    && !rightOp.operator.equals("quoteRegex")) {
                // Check if it's a regex operator (replaceRegex, matchRegex, tr, transliterate)
                if (rightOp.operator.equals("replaceRegex")
                        || rightOp.operator.equals("matchRegex")
                        || rightOp.operator.equals("tr")
                        || rightOp.operator.equals("transliterate")) {

                    // For !~, check for s///r and y///r which don't make sense (mirrors JVM handleNotBindRegex)
                    if (node.operator.equals("!~")) {
                        if ((rightOp.operator.equals("tr") || rightOp.operator.equals("transliterate"))
                                && originalList.elements.size() >= 3
                                && originalList.elements.get(2) instanceof StringNode) {
                            String mods = ((StringNode) originalList.elements.get(2)).value;
                            if (mods.contains("r")) {
                                bytecodeCompiler.throwCompilerException("Using !~ with tr///r doesn't make sense");
                            }
                        }
                        if (rightOp.operator.equals("replaceRegex")
                                && originalList.elements.size() >= 2
                                && originalList.elements.get(1) instanceof StringNode) {
                            String mods = ((StringNode) originalList.elements.get(1)).value;
                            if (mods.contains("r")) {
                                bytecodeCompiler.throwCompilerException("Using !~ with s///r doesn't make sense");
                            }
                        }
                    }

                    // Create a copy of the operand list and add the left side (string)
                    ListNode boundList = new ListNode(new java.util.ArrayList<>(originalList.elements), originalList.tokenIndex);
                    boundList.elements.add(node.left);

                    // Create a new OperatorNode with the modified operand list
                    OperatorNode boundOp = new OperatorNode(rightOp.operator, boundList, rightOp.tokenIndex);

                    // For !~, we need to negate the result
                    if (node.operator.equals("!~")) {
                        bytecodeCompiler.compileNode(boundOp, -1, bytecodeCompiler.currentCallContext);
                        int matchReg = bytecodeCompiler.lastResultReg;

                        // Negate the result
                        int rd = bytecodeCompiler.allocateOutputRegister();
                        bytecodeCompiler.emit(Opcodes.NOT);
                        bytecodeCompiler.emitReg(rd);
                        bytecodeCompiler.emitReg(matchReg);
                        bytecodeCompiler.lastResultReg = rd;
                    } else {
                        // For =~, just compile the bound operator
                        boundOp.accept(bytecodeCompiler);
                    }

                    return;
                }
            }
        }

        // Handle split specially: each argument (EXPR, LIMIT) should be in SCALAR context,
        // but the result is assembled into a list for the SPLIT opcode.
        // This ensures `split //, reverse $str` evaluates `reverse` in scalar context
        // (string reverse) not list context (list reverse).
        if (node.operator.equals("split")) {
            bytecodeCompiler.compileNode(node.left, -1, RuntimeContextType.SCALAR);
            int rs1 = bytecodeCompiler.lastResultReg;

            int rs2;
            if (node.right instanceof ListNode listNode && !listNode.elements.isEmpty()) {
                // Compile each element in SCALAR context, then assemble into a list
                java.util.List<Integer> argRegs = new java.util.ArrayList<>();
                for (Node element : listNode.elements) {
                    bytecodeCompiler.compileNode(element, -1, RuntimeContextType.SCALAR);
                    argRegs.add(bytecodeCompiler.lastResultReg);
                }
                rs2 = bytecodeCompiler.allocateRegister();
                bytecodeCompiler.emit(Opcodes.CREATE_LIST);
                bytecodeCompiler.emitReg(rs2);
                bytecodeCompiler.emit(argRegs.size());
                for (int argReg : argRegs) {
                    bytecodeCompiler.emitReg(argReg);
                }
            } else {
                bytecodeCompiler.compileNode(node.right, -1, RuntimeContextType.SCALAR);
                rs2 = bytecodeCompiler.lastResultReg;
            }

            int rd = CompileBinaryOperatorHelper.compileBinaryOperatorSwitch(
                    bytecodeCompiler, node.operator, rs1, rs2, node.getIndex());
            bytecodeCompiler.lastResultReg = rd;
            return;
        }

        // Compile left and right operands (for non-short-circuit operators).
        // For arithmetic/bitwise operators, force SCALAR context to prevent
        // parenthesized expressions from producing RuntimeList in LIST context.
        boolean forceScalar = switch (node.operator) {
            case "+", "-", "*", "/", "%", "**",
                 "&", "|", "^", "<<", ">>",
                 "binary&", "binary|", "binary^",
                 "&.", "|.", "^." -> true;
            default -> false;
        };
        // For grep/map/sort/all/any, the right operand (list) must always be in LIST context
        // and the left operand (closure) in SCALAR context, matching the JVM backend.
        boolean isListOp = switch (node.operator) {
            case "grep", "map", "sort", "all", "any" -> true;
            default -> false;
        };
        int outerCtx = bytecodeCompiler.currentCallContext;
        int leftCtx = (forceScalar || isListOp) ? RuntimeContextType.SCALAR : outerCtx;
        bytecodeCompiler.compileNode(node.left, -1, leftCtx);
        int rs1 = bytecodeCompiler.lastResultReg;

        int rightCtx;
        if (isListOp) {
            rightCtx = RuntimeContextType.LIST;
        } else if (forceScalar || node.operator.equals("=~") || node.operator.equals("!~")) {
            rightCtx = RuntimeContextType.SCALAR;
        } else {
            rightCtx = outerCtx;
        }
        bytecodeCompiler.compileNode(node.right, -1, rightCtx);
        int rs2 = bytecodeCompiler.lastResultReg;

        // Emit opcode based on operator (delegated to helper method)
        int rd = CompileBinaryOperatorHelper.compileBinaryOperatorSwitch(bytecodeCompiler, node.operator, rs1, rs2, node.getIndex());


        bytecodeCompiler.lastResultReg = rd;
    }

    private static void compileBinaryAsListOp(BytecodeCompiler bytecodeCompiler, BinaryOperatorNode node) {
        if (node.left instanceof IdentifierNode idNode) {
            String name = NameNormalizer.normalizeVariableName(idNode.name, bytecodeCompiler.getCurrentPackage());
            int fhReg = bytecodeCompiler.allocateRegister();
            int nameIdx = bytecodeCompiler.addToStringPool(name);
            bytecodeCompiler.emit(Opcodes.LOAD_GLOB);
            bytecodeCompiler.emitReg(fhReg);
            bytecodeCompiler.emit(nameIdx);
            bytecodeCompiler.lastResultReg = fhReg;
        } else {
            bytecodeCompiler.compileNode(node.left, -1, RuntimeContextType.SCALAR);
        }
        int fhReg = bytecodeCompiler.lastResultReg;

        java.util.List<Integer> argRegs = new java.util.ArrayList<>();
        argRegs.add(fhReg);

        if (node.right instanceof ListNode argsList) {
            for (Node arg : argsList.elements) {
                bytecodeCompiler.compileNode(arg, -1, RuntimeContextType.LIST);
                argRegs.add(bytecodeCompiler.lastResultReg);
            }
        } else {
            bytecodeCompiler.compileNode(node.right, -1, RuntimeContextType.LIST);
            argRegs.add(bytecodeCompiler.lastResultReg);
        }

        int argsListReg = bytecodeCompiler.allocateRegister();
        bytecodeCompiler.emit(Opcodes.CREATE_LIST);
        bytecodeCompiler.emitReg(argsListReg);
        bytecodeCompiler.emit(argRegs.size());
        for (int argReg : argRegs) {
            bytecodeCompiler.emitReg(argReg);
        }

        int opcode = switch (node.operator) {
            case "binmode" -> Opcodes.BINMODE;
            case "seek" -> Opcodes.SEEK;
            case "eof" -> Opcodes.EOF_OP;
            case "close" -> Opcodes.CLOSE;
            case "fileno" -> Opcodes.FILENO;
            case "getc" -> Opcodes.GETC;
            case "printf" -> Opcodes.PRINTF;
            default -> throw new RuntimeException("Unknown operator: " + node.operator);
        };

        int rd = bytecodeCompiler.allocateOutputRegister();
        bytecodeCompiler.emit(opcode);
        bytecodeCompiler.emitReg(rd);
        bytecodeCompiler.emitReg(argsListReg);
        bytecodeCompiler.emit(bytecodeCompiler.currentCallContext);

        bytecodeCompiler.lastResultReg = rd;
    }

    private static void compileTellBinaryOp(BytecodeCompiler bytecodeCompiler, BinaryOperatorNode node) {
        bytecodeCompiler.compileNode(node.left, -1, RuntimeContextType.SCALAR);
        int fhReg = bytecodeCompiler.lastResultReg;

        int rd = bytecodeCompiler.allocateOutputRegister();
        bytecodeCompiler.emit(Opcodes.TELL);
        bytecodeCompiler.emitReg(rd);
        bytecodeCompiler.emitReg(fhReg);

        bytecodeCompiler.lastResultReg = rd;
    }

    private static void compileJoinBinaryOp(BytecodeCompiler bytecodeCompiler, BinaryOperatorNode node) {
        bytecodeCompiler.compileNode(node.left, -1, RuntimeContextType.SCALAR);
        int separatorReg = bytecodeCompiler.lastResultReg;

        int listReg;
        if (node.right instanceof ListNode listNode) {
            java.util.List<Integer> argRegs = new java.util.ArrayList<>();
            for (Node arg : listNode.elements) {
                bytecodeCompiler.compileNode(arg, -1, RuntimeContextType.LIST);
                argRegs.add(bytecodeCompiler.lastResultReg);
            }
            listReg = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.CREATE_LIST);
            bytecodeCompiler.emitReg(listReg);
            bytecodeCompiler.emit(argRegs.size());
            for (int argReg : argRegs) {
                bytecodeCompiler.emitReg(argReg);
            }
        } else {
            bytecodeCompiler.compileNode(node.right, -1, RuntimeContextType.LIST);
            listReg = bytecodeCompiler.lastResultReg;
        }

        int rd = bytecodeCompiler.allocateOutputRegister();
        bytecodeCompiler.emit(bytecodeCompiler.isNoOverloadingEnabled() ? Opcodes.JOIN_NO_OVERLOAD : Opcodes.JOIN);
        bytecodeCompiler.emitReg(rd);
        bytecodeCompiler.emitReg(separatorReg);
        bytecodeCompiler.emitReg(listReg);

        bytecodeCompiler.lastResultReg = rd;
    }

    static boolean isArrayLikeNode(Node node) {
        if (node instanceof OperatorNode op) {
            String o = op.operator;
            if (o.equals("@") || o.equals("%")) return true;
            if (o.equals("unpack") || o.equals("split") || o.equals("sort") ||
                    o.equals("reverse") || o.equals("grep") || o.equals("map") ||
                    o.equals("keys") || o.equals("values") || o.equals("each")) return true;
        }
        if (node instanceof BinaryOperatorNode bin) {
            return bin.operator.equals("(") || bin.operator.equals("()");
        }
        return false;
    }
}
