package org.perlonjava.codegen;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.*;
import org.perlonjava.operators.OperatorHandler;
import org.perlonjava.runtime.NameNormalizer;
import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeContextType;
import org.perlonjava.runtime.ScalarUtils;

import java.util.HashMap;
import java.util.Map;

public class EmitterVisitor implements Visitor {

    public final EmitterContext ctx;
    /**
     * Cache for EmitterVisitor instances with different ContextTypes
     */
    private final Map<Integer, EmitterVisitor> visitorCache = new HashMap<>();

    public EmitterVisitor(EmitterContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Returns an EmitterVisitor with the specified context type. Uses a cache to avoid creating new
     * instances unnecessarily.
     *
     * <p>Example usage:
     *
     * <pre>
     *   // emits the condition code in scalar context
     *   node.condition.accept(this.with(RuntimeContextType.SCALAR));
     * </pre>
     *
     * @param contextType The context type for the new EmitterVisitor.
     * @return An EmitterVisitor with the specified context type.
     */
    public EmitterVisitor with(int contextType) {
        // Check if the visitor is already cached
        if (visitorCache.containsKey(contextType)) {
            return visitorCache.get(contextType);
        }
        // Create a new visitor and cache it
        EmitterVisitor newVisitor = new EmitterVisitor(ctx.with(contextType));
        visitorCache.put(contextType, newVisitor);
        return newVisitor;
    }

    public void pushCallContext() {
        // push call context to stack
        if (ctx.contextType == RuntimeContextType.RUNTIME) {
            // Retrieve wantarray value from JVM local vars
            ctx.mv.visitVarInsn(Opcodes.ILOAD, ctx.symbolTable.getVariableIndex("wantarray"));
        } else {
            ctx.mv.visitLdcInsn(ctx.contextType);
        }
    }

    @Override
    public void visit(NumberNode node) {
        EmitLiteral.emitNumber(ctx, node);
    }

    @Override
    public void visit(IdentifierNode node) {
        EmitLiteral.emitIdentifier(ctx, node);
    }

    @Override
    public void visit(BinaryOperatorNode node) {
        String operator = node.operator;
        ctx.logDebug("visit(BinaryOperatorNode) " + operator + " in context " + ctx.contextType);

        switch (operator) { // handle operators that support short-circuit or other special cases
            case "||":
            case "or":
                EmitLogicalOperator.emitLogicalOperator(this, node, Opcodes.IFNE, "getBoolean");
                return;
            case "||=":
                EmitLogicalOperator.emitLogicalAssign(this, node, Opcodes.IFNE, "getBoolean");
                return;
            case "&&":
            case "and":
                EmitLogicalOperator.emitLogicalOperator(this, node, Opcodes.IFEQ, "getBoolean");
                return;
            case "&&=":
                EmitLogicalOperator.emitLogicalAssign(this, node, Opcodes.IFEQ, "getBoolean");
                return;
            case "//":
                EmitLogicalOperator.emitLogicalOperator(this, node, Opcodes.IFNE, "getDefinedBoolean");
                return;
            case "//=":
                EmitLogicalOperator.emitLogicalAssign(this, node, Opcodes.IFNE, "getDefinedBoolean");
                return;
            case "=":
                EmitVariable.handleAssignOperator(this, node);
                return;
            case ".":
                EmitOperator.handleConcatOperator(this, node);
                return;
            case "->":
                Dereference.handleArrowOperator(this, node);
                return;
            case "[":
                Dereference.handleArrayElementOperator(this, node);
                return;
            case "{":
                Dereference.handleHashElementOperator(this, node, "get");
                return;
            case "(":
                EmitSubroutine.handleApplyOperator(this, node);
                return;
            case "push":
            case "unshift":
                EmitOperator.handlePushOperator(this, node);
                return;
            case "map":
            case "sort":
            case "grep":
                EmitOperator.handleMapOperator(this, node);
                return;
            case "eof":
            case "open":
            case "printf":
            case "print":
            case "say":
                EmitOperator.handleSayOperator(this, node);
                return;
            case "close":
            case "readline":
            case "fileno":
            case "getc":
            case "truncate":
                EmitOperator.handleReadlineOperator(this, node);
                return;
            case "sprintf":
            case "substr":
                EmitOperator.handleSubstr(this, node);
                return;
            case "x":
                EmitOperator.handleRepeat(this, node);
                return;
            case "join":
                EmitOperator.handleJoinOperator(this, node);
                return;
            case "split":
                EmitOperator.handleSplitOperator(this, node);
                return;
            case "!~":
                EmitRegex.handleNotBindRegex(this, node);
                return;
            case "=~":
                EmitRegex.handleBindRegex(this, node);
                return;
            case "**=":
            case "+=":
            case "*=":
            case "&=":
            case "&.=":
            case "binary&=":
            case "<<=":
            case "-=":
            case "/=":
            case "|=":
            case "|.=":
            case "binary|=":
            case ">>=":
            case ".=":
            case "%=":
            case "^=":
            case "^.=":
            case "binary^=":
            case "x=":
                String newOp = operator.substring(0, operator.length() - 1);
                OperatorHandler operatorHandler = OperatorHandler.get(newOp);
                if (operatorHandler != null) {
                    handleCompoundAssignment(node, operatorHandler);
                    return;
                }
                break;
            case "...":
                EmitLogicalOperator.emitFlipFlopOperator(this, node);
                return;
            case "..":
                if (ctx.contextType == RuntimeContextType.SCALAR) {
                    EmitLogicalOperator.emitFlipFlopOperator(this, node);
                    return;
                }
                EmitOperator.handleRangeOperator(this, node);
                return;
        }

        OperatorHandler operatorHandler = OperatorHandler.get(operator);
        if (operatorHandler != null) {
            handleBinaryOperator(node, operatorHandler);
            return;
        }

        throw new PerlCompilerException(node.tokenIndex, "Unexpected infix operator: " + operator, ctx.errorUtil);
    }

    private void handleBinaryOperator(BinaryOperatorNode node, OperatorHandler operatorHandler) {
        EmitterVisitor scalarVisitor =
                this.with(RuntimeContextType.SCALAR); // execute operands in scalar context
        node.left.accept(scalarVisitor); // target - left parameter

        // Optimization
        if ((node.operator.equals("+")
                || node.operator.equals("-")
                || node.operator.equals("=="))
                && node.right instanceof NumberNode right) {
            String value = right.value;
            boolean isInteger = ScalarUtils.isInteger(value);
            if (isInteger) {
                int intValue = Integer.parseInt(value);
                ctx.mv.visitLdcInsn(intValue);
                ctx.mv.visitMethodInsn(
                        operatorHandler.getMethodType(),
                        operatorHandler.getClassName(),
                        operatorHandler.getMethodName(),
                        operatorHandler.getDescriptorWithIntParameter(),
                        false);
                if (ctx.contextType == RuntimeContextType.VOID) {
                    ctx.mv.visitInsn(Opcodes.POP);
                }
                return;
            }
        }

        node.right.accept(scalarVisitor); // right parameter
        // stack: [left, right]
        // perform the operation
        ctx.mv.visitMethodInsn(
                operatorHandler.getMethodType(),
                operatorHandler.getClassName(),
                operatorHandler.getMethodName(),
                operatorHandler.getDescriptor(),
                false);
        if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    private void handleCompoundAssignment(BinaryOperatorNode node, OperatorHandler operatorHandler) {
        // compound assignment operators like `+=`
        EmitterVisitor scalarVisitor =
                this.with(RuntimeContextType.SCALAR); // execute operands in scalar context
        node.left.accept(scalarVisitor); // target - left parameter
        ctx.mv.visitInsn(Opcodes.DUP);
        node.right.accept(scalarVisitor); // right parameter
        // stack: [left, left, right]
        // perform the operation
        ctx.mv.visitMethodInsn(
                operatorHandler.getMethodType(),
                operatorHandler.getClassName(),
                operatorHandler.getMethodName(),
                operatorHandler.getDescriptor(),
                false);
        // assign to the Lvalue
        ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", "set", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    /**
     * Emits a call to a unary built-in method on the RuntimeScalar class.
     *
     * @param operator The name of the built-in method to call.
     */
    private void handleUnaryBuiltin(OperatorNode node, String operator) {
        MethodVisitor mv = ctx.mv;
        OperatorHandler operatorHandler = OperatorHandler.get(operator);
        if (node.operand == null) {
            // Unary operator with no arguments, or with optional arguments called without arguments
            // example: undef()  wantarray()  time()  times()
            if (operator.equals("wantarray")) {
                // Retrieve wantarray value from JVM local vars
                mv.visitVarInsn(Opcodes.ILOAD, ctx.symbolTable.getVariableIndex("wantarray"));
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/RuntimeScalar", operator, "(I)Lorg/perlonjava/runtime/RuntimeScalar;", false);
            } else if (operator.equals("times") || operator.equals("time")) {
                // Call static RuntimeScalar method with no arguments; returns RuntimeList
                mv.visitMethodInsn(
                        operatorHandler.getMethodType(),
                        operatorHandler.getClassName(),
                        operator,
                        operatorHandler.getDescriptor(),
                        false
                );
            } else {
                // Call static RuntimeScalar method with no arguments
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/RuntimeScalar", operator, "()Lorg/perlonjava/runtime/RuntimeScalar;", false);
            }
        } else if (operator.equals("undef")) {
            operator = "undefine";
            node.operand.accept(this.with(RuntimeContextType.RUNTIME));
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeList", operator, "()Lorg/perlonjava/runtime/RuntimeList;", false);
        } else if (operator.equals("gmtime") || operator.equals("localtime") || operator.equals("caller") || operator.equals("reset") || operator.equals("select")) {
            node.operand.accept(this.with(RuntimeContextType.LIST));
            pushCallContext();
            mv.visitMethodInsn(
                    operatorHandler.getMethodType(),
                    operatorHandler.getClassName(),
                    operator,
                    operatorHandler.getDescriptor(),
                    false);
        } else if (operator.equals("prototype")) {
            node.operand.accept(this.with(RuntimeContextType.SCALAR));
            ctx.mv.visitLdcInsn(ctx.symbolTable.getCurrentPackage());
            ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/RuntimeScalar",
                    "prototype",
                    "(Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeScalar;",
                    false);
        } else if ((operator.equals("stat") || operator.equals("lstat"))
                && (node.operand instanceof IdentifierNode && ((IdentifierNode) node.operand).name.equals("_"))) {
                // `stat _`
                ctx.mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "org/perlonjava/operators/Stat",
                        operator + "LastHandle",
                        "()Lorg/perlonjava/runtime/RuntimeList;", false);
        } else {
            node.operand.accept(this.with(RuntimeContextType.SCALAR));
            if (operatorHandler != null) {
                mv.visitMethodInsn(
                        operatorHandler.getMethodType(),
                        operatorHandler.getClassName(),
                        operator,
                        operatorHandler.getDescriptor(),
                        false);
            } else {
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", operator, "()Lorg/perlonjava/runtime/RuntimeScalar;", false);
            }
        }
        if (ctx.contextType == RuntimeContextType.VOID) {
            mv.visitInsn(Opcodes.POP);
        }
    }

    private void handleArrayUnaryBuiltin(OperatorNode node, String operator) {
        // Handle:  $#array  $#$array_ref  shift @array  pop @array
        Node operand = node.operand;
        ctx.logDebug("handleArrayUnaryBuiltin " + operand);
        if (operand instanceof ListNode) {
            operand = ((ListNode) operand).elements.getFirst();
        }
        operand.accept(this.with(RuntimeContextType.LIST));
        ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeArray", operator, "()Lorg/perlonjava/runtime/RuntimeScalar;", false);
        if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    private void handleFileTestBuiltin(OperatorNode node) {
        // Handle:  -d FILE
        String operator = node.operator;
        ctx.logDebug("handleFileTestBuiltin " + node);

        // push the operator string to the JVM stack
        ctx.mv.visitLdcInsn(node.operator);
        if (node.operand instanceof IdentifierNode && ((IdentifierNode) node.operand).name.equals("_")) {
            // use the `_` file handle
            ctx.mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "org/perlonjava/operators/FileTestOperator",
                    "fileTestLastHandle",
                    "(Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        } else {
            // push the file name to the JVM stack
            node.operand.accept(this.with(RuntimeContextType.SCALAR));
            ctx.mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "org/perlonjava/operators/FileTestOperator",
                    "fileTest",
                    "(Ljava/lang/String;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        }
        if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    @Override
    public void visit(OperatorNode node) {
        String operator = node.operator;
        ctx.logDebug("visit(OperatorNode) " + operator + " in context " + ctx.contextType);

        switch (operator) {
            case "__SUB__":
                EmitSubroutine.handleSelfCallOperator(this, node);
                break;
            case "package":
                EmitOperator.handlePackageOperator(this, node);
                break;
            case "$":
            case "@":
            case "%":
            case "*":
            case "&":
                EmitVariable.handleVariableOperator(this, node);
                break;
            case "keys":
            case "values":
                EmitOperator.handleKeysOperator(this, node);
                break;
            case "each":
                EmitOperator.handleEachOperator(this, node);
                break;
            case "our":
            case "state":
            case "my":
                EmitVariable.handleMyOperator(this, node);
                break;
            case "next":
            case "redo":
            case "last":
                EmitOperator.handleNextOperator(ctx, node);
                break;
            case "return":
                EmitOperator.handleReturnOperator(this, node);
                break;
            case "eval":
                EmitEval.handleEvalOperator(this, node);
                break;
            case "-":
                handleUnaryBuiltin(node, "unaryMinus");
                break;
            case "+":
                EmitOperator.handleUnaryPlusOperator(this, node);
                break;
            case "~":
                handleUnaryBuiltin(node, "bitwiseNot");
                break;
            case "binary~":
                handleUnaryBuiltin(node, "bitwiseNotBinary");
                break;
            case "~.":
                handleUnaryBuiltin(node, "bitwiseNotDot");
                break;
            case "!":
            case "not":
                handleUnaryBuiltin(node, "not");
                break;
            case "<>":
                EmitOperator.handleDiamondBuiltin(this, node);
                break;
            case "abs":
            case "defined":
            case "doFile":
            case "require":
            case "length":
            case "log":
            case "sqrt":
            case "cos":
            case "sin":
            case "exp":
            case "quotemeta":
            case "rand":
            case "srand":
            case "sleep":
            case "study":
            case "ref":
            case "oct":
            case "hex":
            case "chr":
            case "ord":
            case "fc":
            case "lc":
            case "lcfirst":
            case "uc":
            case "ucfirst":
            case "undef":
            case "exit":
            case "wantarray":
            case "time":
            case "times":
            case "localtime":
            case "gmtime":
            case "caller":
            case "reset":
            case "rewinddir":
            case "telldir":
            case "closedir":
            case "rmdir":
            case "pos":
            case "select":
            case "prototype":
            case "stat":
            case "lstat":
            case "chdir":
                handleUnaryBuiltin(node, operator);
                break;
            case "chop":
            case "chomp":
                EmitOperator.handleChompBuiltin(this, node);
                break;
            case "mkdir":
            case "opendir":
            case "seekdir":
                EmitOperator.handleMkdirOperator(this, node);
                break;
            case "readdir":
                EmitOperator.handleReaddirOperator(this, node);
                break;
            case "pack":
                EmitOperator.handlePackBuiltin(this, node);
                break;
            case "unpack":
                EmitOperator.handleUnpackBuiltin(this, node);
                break;
            case "crypt":
                EmitOperator.handleCryptBuiltin(this, node);
                break;
            case "glob":
                EmitOperator.handleGlobBuiltin(this, node);
                break;
            case "rindex":
            case "index":
                EmitOperator.handleIndexBuiltin(this, node);
                break;
            case "vec":
                EmitOperator.handleVecBuiltin(this, node);
                break;
            case "atan2":
                EmitOperator.handleAtan2(this, node);
                break;
            case "scalar":
                EmitOperator.handleScalar(this, node);
                break;
            case "delete":
            case "exists":
                EmitOperator.handleDeleteExists(this, node);
                break;
            case "local":
                EmitOperator.handleLocal(this, node);
                break;
            case "int":
                handleUnaryBuiltin(node, "integer");
                break;
            case "++":
                handleUnaryBuiltin(node, "preAutoIncrement");
                break;
            case "--":
                handleUnaryBuiltin(node, "preAutoDecrement");
                break;
            case "++postfix":
                handleUnaryBuiltin(node, "postAutoIncrement");
                break;
            case "--postfix":
                handleUnaryBuiltin(node, "postAutoDecrement");
                break;
            case "\\":
                handleCreateReference(node);
                break;
            case "$#":
                node = new OperatorNode("$#", new OperatorNode("@", node.operand, node.tokenIndex), node.tokenIndex);
                handleArrayUnaryBuiltin(node, "indexLastElem");
                break;
            case "die":
            case "warn":
                EmitOperator.handleDieBuiltin(this, node);
                break;
            case "reverse":
            case "unlink":
                EmitOperator.handleReverseBuiltin(this, node);
                break;
            case "splice":
                EmitOperator.handleSpliceBuiltin(this, node);
                break;
            case "pop":
            case "shift":
                handleArrayUnaryBuiltin(node, operator);
                break;
            case "matchRegex":
            case "quoteRegex":
            case "replaceRegex":
            case "tr":
            case "y":
            case "qx":
                EmitRegex.handleRegex(this, node);
                break;
            default:
                if (operator.length() == 2 && operator.charAt(0) == '-') {
                    // -d -e -f
                    handleFileTestBuiltin(node);
                    return;
                }
                throw new PerlCompilerException(node.tokenIndex, "Not implemented: operator: " + operator, ctx.errorUtil);
        }
    }

    private void handleCreateReference(OperatorNode node) {
        MethodVisitor mv = ctx.mv;
        if (node.operand instanceof ListNode) {
            // operand is a list:  `\(1,2,3)`
            node.operand.accept(this.with(RuntimeContextType.LIST));
            ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/RuntimeList",
                    "createListReference",
                    "()Lorg/perlonjava/runtime/RuntimeList;", false);
            if (ctx.contextType == RuntimeContextType.VOID) {
                mv.visitInsn(Opcodes.POP);
            }
        } else {
            if (node.operand instanceof OperatorNode operatorNode && operatorNode.operator.equals("&")) {
                ctx.logDebug("Handle \\& " + operatorNode.operand);
                if (operatorNode.operand instanceof OperatorNode || operatorNode.operand instanceof BlockNode) {
                    // \&$a or \&{$a}
                    operatorNode.operand.accept(this.with(RuntimeContextType.SCALAR));
                    ctx.mv.visitLdcInsn(ctx.symbolTable.getCurrentPackage());
                    ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                            "org/perlonjava/runtime/RuntimeScalar",
                            "createCodeReference",
                            "(Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeScalar;",
                            false);
                } else {
                    // assume \&var, which is already a reference
                    node.operand.accept(this.with(RuntimeContextType.LIST));
                }
            } else {
                node.operand.accept(this.with(RuntimeContextType.LIST));
                ctx.mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "createReference", "()Lorg/perlonjava/runtime/RuntimeScalar;", true);
            }
            if (ctx.contextType == RuntimeContextType.VOID) {
                mv.visitInsn(Opcodes.POP);
            }
        }
    }

    public void emitFileHandle(Node node) {
        // Emit File Handle
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
                    "(Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeScalar;",
                    false);
        } else if (node instanceof BlockNode) {
            // {STDERR}  or  {$fh}
            // TODO
        }
    }

    @Override
    public void visit(TryNode node) {
        EmitStatement.emitTryCatch(this, node);
    }

    @Override
    public void visit(SubroutineNode node) {
        EmitSubroutine.emitSubroutine(ctx, node);
    }

    @Override
    public void visit(For1Node node) {
        EmitStatement.emitFor1(this, node);
    }

    @Override
    public void visit(For3Node node) {
        EmitStatement.emitFor3(this, node);
    }

    @Override
    public void visit(IfNode node) {
        EmitStatement.emitIf(this, node);
    }

    @Override
    public void visit(TernaryOperatorNode node) {
        EmitLogicalOperator.emitTernaryOperator(this, node);
    }

    @Override
    public void visit(BlockNode node) {
        EmitStatement.emitBlock(this, node);
    }

    @Override
    public void visit(ListNode node) {
        EmitLiteral.emitList(this, node);
    }

    @Override
    public void visit(StringNode node) {
        EmitLiteral.emitString(ctx, node);
    }

    @Override
    public void visit(HashLiteralNode node) {
        EmitLiteral.emitHashLiteral(this, node);
    }

    @Override
    public void visit(ArrayLiteralNode node) {
        EmitLiteral.emitArrayLiteral(this, node);
    }

}
