package org.perlonjava.codegen;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.*;
import org.perlonjava.runtime.NameNormalizer;
import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeContextType;
import org.perlonjava.runtime.ScalarUtils;

import java.util.HashMap;
import java.util.Map;

public class EmitterVisitor implements Visitor {
    private static final Map<String, String> operatorHandlers = new HashMap<>();

    static {
        operatorHandlers.put("**", "pow");
        operatorHandlers.put("+", "add");
        operatorHandlers.put("-", "subtract");
        operatorHandlers.put("*", "multiply");
        operatorHandlers.put("/", "divide");
        operatorHandlers.put("%", "modulus");
        operatorHandlers.put(".", "stringConcat");
        operatorHandlers.put("&", "bitwiseAnd");
        operatorHandlers.put("|", "bitwiseOr");
        operatorHandlers.put("^", "bitwiseXor");
        operatorHandlers.put("<<", "shiftLeft");
        operatorHandlers.put(">>", "shiftRight");
        operatorHandlers.put("x", "repeat");
        operatorHandlers.put("&.", "bitwiseStringAnd");
        operatorHandlers.put("&&", "logicalAnd");
        operatorHandlers.put("|.", "bitwiseStringOr");
        operatorHandlers.put("||", "logicalOr");
        operatorHandlers.put("^.", "bitwiseStringXor");
        operatorHandlers.put("//", "logicalDefinedOr");
        operatorHandlers.put("isa", "isa");
        operatorHandlers.put("<", "lessThan");
        operatorHandlers.put("<=", "lessThanOrEqual");
        operatorHandlers.put(">", "greaterThan");
        operatorHandlers.put(">=", "greaterThanOrEqual");
        operatorHandlers.put("==", "equalTo");
        operatorHandlers.put("!=", "notEqualTo");
        operatorHandlers.put("<=>", "spaceship");
        operatorHandlers.put("eq", "eq");
        operatorHandlers.put("ne", "ne");
        operatorHandlers.put("lt", "lt");
        operatorHandlers.put("le", "le");
        operatorHandlers.put("gt", "gt");
        operatorHandlers.put("ge", "ge");
        operatorHandlers.put("cmp", "cmp");
        operatorHandlers.put("bless", "bless");
    }

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
                handleArrowOperator(node);
                return;
            case "[":
                handleArrayElementOperator(node);
                return;
            case "{":
                handleHashElementOperator(node, "get");
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
            case "<<=":
            case "-=":
            case "/=":
            case "|=":
            case "|.=":
            case ">>=":
            case ".=":
            case "%=":
            case "^=":
            case "^.=":
            case "x=":
                String newOp = operator.substring(0, operator.length() - 1);
                String methodStr = operatorHandlers.get(newOp);
                if (methodStr != null) {
                    handleCompoundAssignment(node, methodStr);
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

        String methodStr = operatorHandlers.get(operator);
        if (methodStr != null) {
            handleBinaryOperator(node, methodStr);
            return;
        }

        throw new PerlCompilerException(node.tokenIndex, "Unexpected infix operator: " + operator, ctx.errorUtil);
    }

    private void handleBinaryOperator(BinaryOperatorNode node, String methodStr) {
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
                        Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/RuntimeScalar",
                        methodStr,
                        "(I)Lorg/perlonjava/runtime/RuntimeScalar;", false);
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
                Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", methodStr, "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    private void handleCompoundAssignment(BinaryOperatorNode node, String methodStr) {
        // compound assignment operators like `+=`
        EmitterVisitor scalarVisitor =
                this.with(RuntimeContextType.SCALAR); // execute operands in scalar context
        node.left.accept(scalarVisitor); // target - left parameter
        ctx.mv.visitInsn(Opcodes.DUP);
        node.right.accept(scalarVisitor); // right parameter
        // stack: [left, left, right]
        // perform the operation
        ctx.mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", methodStr, "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        // assign to the Lvalue
        ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", "set", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    /**
     * Handles the postfix `[]` operator.
     */
    private void handleArrayElementOperator(BinaryOperatorNode node) {
        ctx.logDebug("handleArrayElementOperator " + node + " in context " + ctx.contextType);
        EmitterVisitor scalarVisitor =
                this.with(RuntimeContextType.SCALAR); // execute operands in scalar context

        // check if node.left is a `$` or `@` variable - it means we have a RuntimeArray instead of RuntimeScalar
        if (node.left instanceof OperatorNode sigilNode) { // $ @ %
            String sigil = sigilNode.operator;
            if (sigil.equals("$") && sigilNode.operand instanceof IdentifierNode identifierNode) {
                /*  $a[10]
                 *  BinaryOperatorNode: [
                 *    OperatorNode: $
                 *      IdentifierNode: a
                 *    ArrayLiteralNode:
                 *      NumberNode: 10
                 */
                // Rewrite the variable node from `$` to `@`
                OperatorNode varNode = new OperatorNode("@", identifierNode, sigilNode.tokenIndex);

                ctx.logDebug("visit(BinaryOperatorNode) $var[] ");
                varNode.accept(this.with(RuntimeContextType.LIST)); // target - left parameter

                ArrayLiteralNode right = (ArrayLiteralNode) node.right;
                if (right.elements.size() == 1) {
                    // Optimization: Extract the single element if the list has only one item
                    Node elem = right.elements.getFirst();
                    elem.accept(this.with(RuntimeContextType.SCALAR));
                } else {
                    // emit the [0] as a RuntimeList
                    ListNode nodeRight = right.asListNode();
                    nodeRight.accept(this.with(RuntimeContextType.SCALAR));
                }

                ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeArray", "get", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);

                if (ctx.contextType == RuntimeContextType.VOID) {
                    ctx.mv.visitInsn(Opcodes.POP);
                }
                return;
            }
            if (sigil.equals("@") && sigilNode.operand instanceof IdentifierNode) {
                /*  @a[10, 20]
                 *  BinaryOperatorNode: [
                 *    OperatorNode: @
                 *      IdentifierNode: a
                 *    ArrayLiteralNode:
                 *      NumberNode: 10
                 *      NumberNode: 20
                 */
                ctx.logDebug("visit(BinaryOperatorNode) @var[] ");
                sigilNode.accept(this.with(RuntimeContextType.LIST)); // target - left parameter

                // emit the [10, 20] as a RuntimeList
                ListNode nodeRight = ((ArrayLiteralNode) node.right).asListNode();
                nodeRight.accept(this.with(RuntimeContextType.LIST));

                ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeArray", "getSlice", "(Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeList;", false);

                if (ctx.contextType == RuntimeContextType.VOID) {
                    ctx.mv.visitInsn(Opcodes.POP);
                }
                return;
            }
        }
        if (node.left instanceof ListNode list) { // ("a","b","c")[2]
            // transform to:  ["a","b","c"]->2]
            BinaryOperatorNode refNode = new BinaryOperatorNode("->",
                    new ArrayLiteralNode(list.elements, list.getIndex()),
                    node.right, node.tokenIndex);
            refNode.accept(this);
            return;
        }

        // default: call `->[]`
        BinaryOperatorNode refNode = new BinaryOperatorNode("->", node.left, node.right, node.tokenIndex);
        refNode.accept(this);
    }

    /**
     * Handles the postfix `{}` node.
     * <p>
     * hashOperation is one of: "get", "delete", "exists"
     */
    public void handleHashElementOperator(BinaryOperatorNode node, String hashOperation) {
        ctx.logDebug("handleHashElementOperator " + node + " in context " + ctx.contextType);
        EmitterVisitor scalarVisitor =
                this.with(RuntimeContextType.SCALAR); // execute operands in scalar context

        // check if node.left is a `$` or `@` variable
        if (node.left instanceof OperatorNode sigilNode) { // $ @ %
            String sigil = sigilNode.operator;
            if (sigil.equals("$") && sigilNode.operand instanceof IdentifierNode identifierNode) {
                /*  $a{"a"}
                 *  BinaryOperatorNode: {
                 *    OperatorNode: $
                 *      IdentifierNode: a
                 *    ArrayLiteralNode:
                 *      StringNode: a
                 */
                // Rewrite the variable node from `$` to `%`
                OperatorNode varNode = new OperatorNode("%", identifierNode, sigilNode.tokenIndex);

                ctx.logDebug("visit(BinaryOperatorNode) $var{} ");
                varNode.accept(this.with(RuntimeContextType.LIST)); // target - left parameter

                // emit the {x} as a RuntimeList
                ListNode nodeRight = ((HashLiteralNode) node.right).asListNode();

                Node nodeZero = nodeRight.elements.getFirst();
                if (nodeRight.elements.size() == 1 && nodeZero instanceof IdentifierNode) {
                    // Convert IdentifierNode to StringNode:  {a} to {"a"}
                    nodeRight.elements.set(0, new StringNode(((IdentifierNode) nodeZero).name, ((IdentifierNode) nodeZero).tokenIndex));
                }

                ctx.logDebug("visit(BinaryOperatorNode) $var{}  autoquote " + node.right);
                nodeRight.accept(scalarVisitor);

                ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeHash", hashOperation, "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);

                if (ctx.contextType == RuntimeContextType.VOID) {
                    ctx.mv.visitInsn(Opcodes.POP);
                }
                return;
            }
            if (sigil.equals("@") && sigilNode.operand instanceof IdentifierNode identifierNode) {
                /*  @a{"a", "b"}
                 *  BinaryOperatorNode: {
                 *    OperatorNode: @
                 *      IdentifierNode: a
                 *    ArrayLiteralNode:
                 *      StringNode: a
                 *      StringNode: b
                 */
                // Rewrite the variable node from `@` to `%`
                OperatorNode varNode = new OperatorNode("%", identifierNode, sigilNode.tokenIndex);

                ctx.logDebug("visit(BinaryOperatorNode) @var{} ");
                varNode.accept(this.with(RuntimeContextType.LIST)); // target - left parameter

                // emit the {x} as a RuntimeList
                ListNode nodeRight = ((HashLiteralNode) node.right).asListNode();

                Node nodeZero = nodeRight.elements.getFirst();
                if (nodeRight.elements.size() == 1 && nodeZero instanceof IdentifierNode) {
                    // Convert IdentifierNode to StringNode:  {a} to {"a"}
                    nodeRight.elements.set(0, new StringNode(((IdentifierNode) nodeZero).name, ((IdentifierNode) nodeZero).tokenIndex));
                }

                ctx.logDebug("visit(BinaryOperatorNode) $var{}  autoquote " + node.right);
                nodeRight.accept(this.with(RuntimeContextType.LIST));

                ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeHash", hashOperation + "Slice", "(Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeList;", false);

                if (ctx.contextType == RuntimeContextType.VOID) {
                    ctx.mv.visitInsn(Opcodes.POP);
                }
                return;
            }
        }

        // default: call `->{}`
        BinaryOperatorNode refNode = new BinaryOperatorNode("->", node.left, node.right, node.tokenIndex);
        handleArrowHashDeref(refNode, hashOperation);
    }

    /**
     * Handles the `->` operator.
     */
    private void handleArrowOperator(BinaryOperatorNode node) {
        MethodVisitor mv = ctx.mv;
        ctx.logDebug("handleArrowOperator " + node + " in context " + ctx.contextType);
        EmitterVisitor scalarVisitor =
                this.with(RuntimeContextType.SCALAR); // execute operands in scalar context

        if (node.right instanceof ListNode) { // ->()

            BinaryOperatorNode applyNode = new BinaryOperatorNode("(", node.left, node.right, node.tokenIndex);
            applyNode.accept(this);

        } else if (node.right instanceof ArrayLiteralNode) { // ->[0]
            handleArrowArrayDeref(node, "get");

        } else if (node.right instanceof HashLiteralNode) { // ->{x}
            handleArrowHashDeref(node, "get");

        } else {
            // ->method()   ->$method()
            //
            // right is BinaryOperatorNode:"("
            BinaryOperatorNode right = (BinaryOperatorNode) node.right;

            // `object.call(method, arguments, context)`
            Node object = node.left;
            Node method = right.left;
            Node arguments = right.right;

            // Convert class to Stringnode if needed:  Class->method()
            if (object instanceof IdentifierNode) {
                object = new StringNode(((IdentifierNode) object).name, ((IdentifierNode) object).tokenIndex);
            }

            // Convert method to StringNode if needed
            if (method instanceof OperatorNode op) {
                // &method is introduced by the parser if the method is predeclared
                if (op.operator.equals("&")) {
                    method = op.operand;
                }
            }
            if (method instanceof IdentifierNode) {
                method = new StringNode(((IdentifierNode) method).name, ((IdentifierNode) method).tokenIndex);
            }

            object.accept(scalarVisitor);
            method.accept(scalarVisitor);
            arguments.accept(this.with(RuntimeContextType.LIST)); // right parameter: parameter list

            // Transform the value in the stack to RuntimeArray
            ctx.mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "getArrayOfAlias", "()Lorg/perlonjava/runtime/RuntimeArray;", true);
            ctx.mv.visitLdcInsn(ctx.contextType);   // push call context to stack
            ctx.mv.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/RuntimeScalar",
                    "call",
                    "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeArray;I)Lorg/perlonjava/runtime/RuntimeList;",
                    false); // generate an .call()
            if (ctx.contextType == RuntimeContextType.SCALAR) {
                // Transform the value in the stack to RuntimeScalar
                ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeList", "scalar", "()Lorg/perlonjava/runtime/RuntimeScalar;", false);
            } else if (ctx.contextType == RuntimeContextType.VOID) {
                // Remove the value from the stack
                ctx.mv.visitInsn(Opcodes.POP);
            }
        }
    }

    private void handleArrowArrayDeref(BinaryOperatorNode node, String arrayOperation) {
        ctx.logDebug("visit(BinaryOperatorNode) ->[] ");
        EmitterVisitor scalarVisitor =
                this.with(RuntimeContextType.SCALAR); // execute operands in scalar context

        node.left.accept(scalarVisitor); // target - left parameter

        ArrayLiteralNode right = (ArrayLiteralNode) node.right;
        if (right.elements.size() == 1) {
            // Optimization: Extract the single element if the list has only one item
            Node elem = right.elements.getFirst();
            elem.accept(this.with(RuntimeContextType.SCALAR));
        } else {
            // emit the [0] as a RuntimeList
            ListNode nodeRight = right.asListNode();
            nodeRight.accept(this.with(RuntimeContextType.SCALAR));
        }

        String methodName;
        switch (arrayOperation) {
            case "get":
                methodName = "arrayDerefGet";
                break;
            case "delete":
                methodName = "arrayDerefDelete";
                break;
            default:
                throw new PerlCompilerException(node.tokenIndex, "Not implemented: array operation: " + arrayOperation, ctx.errorUtil);
        }

        ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", "arrayDerefGet", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
    }

    public void handleArrowHashDeref(BinaryOperatorNode node, String hashOperation) {
        ctx.logDebug("visit(BinaryOperatorNode) ->{} ");
        EmitterVisitor scalarVisitor =
                this.with(RuntimeContextType.SCALAR); // execute operands in scalar context

        node.left.accept(scalarVisitor); // target - left parameter

        // emit the {0} as a RuntimeList
        ListNode nodeRight = ((HashLiteralNode) node.right).asListNode();

        Node nodeZero = nodeRight.elements.getFirst();
        if (nodeRight.elements.size() == 1 && nodeZero instanceof IdentifierNode) {
            // Convert IdentifierNode to StringNode:  {a} to {"a"}
            nodeRight.elements.set(0, new StringNode(((IdentifierNode) nodeZero).name, ((IdentifierNode) nodeZero).tokenIndex));
        }

        ctx.logDebug("visit -> (HashLiteralNode) autoquote " + node.right);
        nodeRight.accept(this.with(RuntimeContextType.SCALAR));

        String methodName = switch (hashOperation) {
            case "get" -> "hashDerefGet";
            case "delete" -> "hashDerefDelete";
            case "exists" -> "hashDerefExists";
            default ->
                    throw new PerlCompilerException(node.tokenIndex, "Not implemented: hash operation: " + hashOperation, ctx.errorUtil);
        };

        ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", methodName, "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
    }

    /**
     * Emits a call to a unary built-in method on the RuntimeScalar class.
     *
     * @param operator The name of the built-in method to call.
     */
    private void handleUnaryBuiltin(OperatorNode node, String operator) {
        MethodVisitor mv = ctx.mv;
        if (node.operand == null) {
            // Unary operator with no arguments, or with optional arguments called without arguments
            // example: undef()  wantarray()  time()  times()
            if (operator.equals("wantarray")) {
                // Retrieve wantarray value from JVM local vars
                mv.visitVarInsn(Opcodes.ILOAD, ctx.symbolTable.getVariableIndex("wantarray"));
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/RuntimeScalar", operator, "(I)Lorg/perlonjava/runtime/RuntimeScalar;", false);
            } else if (operator.equals("times")) {
                // Call static RuntimeScalar method with no arguments; returns RuntimeList
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/RuntimeScalar", operator, "()Lorg/perlonjava/runtime/RuntimeList;", false);
            } else {
                // Call static RuntimeScalar method with no arguments
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/RuntimeScalar", operator, "()Lorg/perlonjava/runtime/RuntimeScalar;", false);
            }
        } else if (operator.equals("undef")) {
            operator = "undefine";
            node.operand.accept(this.with(RuntimeContextType.RUNTIME));
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeList", operator, "()Lorg/perlonjava/runtime/RuntimeList;", false);
        } else if (operator.equals("gmtime") || operator.equals("localtime") || operator.equals("caller") || operator.equals("reset")) {
            node.operand.accept(this.with(RuntimeContextType.LIST));
            pushCallContext();
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/RuntimeScalar",
                    operator,
                    "(Lorg/perlonjava/runtime/RuntimeList;I)Lorg/perlonjava/runtime/RuntimeList;", false);
        } else {
            node.operand.accept(this.with(RuntimeContextType.SCALAR));
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", operator, "()Lorg/perlonjava/runtime/RuntimeScalar;", false);
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
                    "org/perlonjava/runtime/FileTestOperator",
                    "fileTestLastHandle",
                    "(Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        } else {
            // push the file name to the JVM stack
            node.operand.accept(this.with(RuntimeContextType.SCALAR));
            ctx.mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/FileTestOperator",
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
                handleUnaryBuiltin(node, operator);
                break;
            case "chop":
            case "chomp":
                EmitOperator.handleChompBuiltin(this, node);
                break;
            case "mkdir":
            case "opendir":
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
                    "org/perlonjava/runtime/GlobalContext",
                    "getGlobalIO",
                    "(Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeScalar;",
                    false);
        } else if (node instanceof BlockNode) {
            // {STDERR}  or  {$fh}
            // TODO
        }
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
