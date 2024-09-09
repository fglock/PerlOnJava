package org.perlonjava.codegen;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.*;
import org.perlonjava.runtime.RuntimeContextType;

import java.util.HashMap;
import java.util.List;
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

    public static boolean isInteger(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        try {
            Integer.parseInt(str);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
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
        EmitterVisitor scalarVisitor =
                this.with(RuntimeContextType.SCALAR); // execute operands in scalar context

        switch (operator) { // handle operators that support short-circuit or other special cases
            case "||":
            case "or":
                handleOrOperator(node, Opcodes.IFNE);
                return;
            case "||=":
                handleOrEqualOperator(node, Opcodes.IFNE);
                return;
            case "&&":
            case "and":
                handleOrOperator(node, Opcodes.IFEQ);
                return;
            case "&&=":
                handleOrEqualOperator(node, Opcodes.IFEQ);
                return;
            case "=":
                EmitVariable.handleAssignOperator(this, node);
                return;
            case ".":
                handleConcatOperator(node, scalarVisitor);
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
                handlePushOperator(operator, node);
                return;
            case "map":
            case "sort":
            case "grep":
                handleMapOperator(operator, node);
                return;
            case "eof":
            case "close":
            case "readline":
            case "open":
            case "printf":
            case "print":
            case "say":
                handleSayOperator(operator, node);
                return;
            case "sprintf":
            case "substr":
                handleSubstr(node, operator);
                return;
            case "x":
                handleRepeat(node);
                return;
            case "join":
                handleJoinOperator(operator, node);
                return;
            case "split":
                handleSplitOperator(operator, node);
                return;
            case "!~":
                EmitRegex.handleNotBindRegex(this, node);
                return;
            case "=~":
                EmitRegex.handleBindRegex(this, node, scalarVisitor);
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
            case "//=":
            case "x=":
                String newOp = operator.substring(0, operator.length() - 1);
                String methodStr = operatorHandlers.get(newOp);
                if (methodStr != null) {
                    handleCompoundAssignment(node, scalarVisitor, methodStr);
                    return;
                }
                break;
            case "..":
                node.left.accept(this.with(RuntimeContextType.SCALAR));
                node.right.accept(this.with(RuntimeContextType.SCALAR));
                // static RuntimeList generateList(int start, int end)
                ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/RuntimeList",
                        "generateList",
                        "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeList;", false);
                if (ctx.contextType == RuntimeContextType.VOID) {
                    ctx.mv.visitInsn(Opcodes.POP);
                }
                return;
        }

        String methodStr = operatorHandlers.get(operator);
        if (methodStr != null) {
            handleBinaryOperator(node, scalarVisitor, methodStr);
            return;
        }
        throw new RuntimeException("Unexpected infix operator: " + operator);
    }

    private void handleSubstr(BinaryOperatorNode node, String operator) {
        node.left.accept(this.with(RuntimeContextType.SCALAR));
        node.right.accept(this.with(RuntimeContextType.LIST));
        ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/Operator", operator, "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    private void handleRepeat(BinaryOperatorNode node) {
        Node left = node.left;
        if (node.left instanceof ListNode) {
            node.left.accept(this.with(RuntimeContextType.LIST));
        } else {
            node.left.accept(this.with(RuntimeContextType.SCALAR));
        }
        node.right.accept(this.with(RuntimeContextType.SCALAR));
        pushCallContext();
        ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/Operator",
                "repeat",
                "(Lorg/perlonjava/runtime/RuntimeDataProvider;Lorg/perlonjava/runtime/RuntimeScalar;I)Lorg/perlonjava/runtime/RuntimeDataProvider;", false);
        if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    private void handleConcatOperator(BinaryOperatorNode node, EmitterVisitor scalarVisitor) {
        node.left.accept(scalarVisitor); // target - left parameter
        node.right.accept(scalarVisitor); // right parameter
        ctx.mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "org/perlonjava/runtime/RuntimeScalar",
                "stringConcat",
                "(Lorg/perlonjava/runtime/RuntimeDataProvider;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    private void handleBinaryOperator(BinaryOperatorNode node, EmitterVisitor scalarVisitor, String methodStr) {
        node.left.accept(scalarVisitor); // target - left parameter
        node.right.accept(scalarVisitor); // right parameter
        // stack: [left, right]
        // perform the operation
        ctx.mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", methodStr, "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    private void handleCompoundAssignment(BinaryOperatorNode node, EmitterVisitor scalarVisitor, String methodStr) {
        // compound assignment operators like `+=`
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

    private void handlePushOperator(String operator, BinaryOperatorNode node) {
        node.left.accept(this.with(RuntimeContextType.LIST));
        node.right.accept(this.with(RuntimeContextType.LIST));
        ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeArray", operator, "(Lorg/perlonjava/runtime/RuntimeDataProvider;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    private void handleMapOperator(String operator, BinaryOperatorNode node) {
        node.right.accept(this.with(RuntimeContextType.LIST));  // list
        node.left.accept(this.with(RuntimeContextType.SCALAR)); // subroutine
        ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/Operator", operator, "(Lorg/perlonjava/runtime/RuntimeList;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeList;", false);
        if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    private void handleSplitOperator(String operator, BinaryOperatorNode node) {
        node.left.accept(this.with(RuntimeContextType.SCALAR));
        node.right.accept(this.with(RuntimeContextType.LIST));
        ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/Operator", operator, "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeList;", false);
        if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    private void handleJoinOperator(String operator, BinaryOperatorNode node) {
        node.left.accept(this.with(RuntimeContextType.SCALAR));
        node.right.accept(this.with(RuntimeContextType.LIST));
        ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/Operator", operator, "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeDataProvider;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    private void handleOrEqualOperator(BinaryOperatorNode node, int compareOpcode) {
        EmitLogicalOperator.emitLogicalAssign(this, node, compareOpcode);
    }

    private void handleOrOperator(BinaryOperatorNode node, int compareOpcode) {
        EmitLogicalOperator.emitLogicalOperator(this, node, compareOpcode);
    }

    /**
     * Handles the postfix `[]` operator.
     */
    private void handleArrayElementOperator(BinaryOperatorNode node) {
        ctx.logDebug("handleArrayElementOperator " + node + " in context " + ctx.contextType);
        EmitterVisitor scalarVisitor =
                this.with(RuntimeContextType.SCALAR); // execute operands in scalar context

        // check if node.left is a `$` or `@` variable - it means we have a RuntimeArray instead of RuntimeScalar
        if (node.left instanceof OperatorNode) { // $ @ %
            OperatorNode sigilNode = (OperatorNode) node.left;
            String sigil = sigilNode.operator;
            if (sigil.equals("$") && sigilNode.operand instanceof IdentifierNode) {
                /*  $a[10]
                 *  BinaryOperatorNode: [
                 *    OperatorNode: $
                 *      IdentifierNode: a
                 *    ArrayLiteralNode:
                 *      NumberNode: 10
                 */
                IdentifierNode identifierNode = (IdentifierNode) sigilNode.operand;
                // Rewrite the variable node from `$` to `@`
                OperatorNode varNode = new OperatorNode("@", identifierNode, sigilNode.tokenIndex);

                ctx.logDebug("visit(BinaryOperatorNode) $var[] ");
                varNode.accept(this.with(RuntimeContextType.LIST)); // target - left parameter

                // emit the [0] as a RuntimeList
                ListNode nodeRight = ((ArrayLiteralNode) node.right).asListNode();
                nodeRight.accept(scalarVisitor);

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
        if (node.left instanceof ListNode) { // ("a","b","c")[2]
            // transform to:  ["a","b","c"]->2]
            ListNode list = (ListNode) node.left;
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
    private void handleHashElementOperator(BinaryOperatorNode node, String hashOperation) {
        ctx.logDebug("handleHashElementOperator " + node + " in context " + ctx.contextType);
        EmitterVisitor scalarVisitor =
                this.with(RuntimeContextType.SCALAR); // execute operands in scalar context

        // check if node.left is a `$` or `@` variable
        if (node.left instanceof OperatorNode) { // $ @ %
            OperatorNode sigilNode = (OperatorNode) node.left;
            String sigil = sigilNode.operator;
            if (sigil.equals("$") && sigilNode.operand instanceof IdentifierNode) {
                /*  $a{"a"}
                 *  BinaryOperatorNode: {
                 *    OperatorNode: $
                 *      IdentifierNode: a
                 *    ArrayLiteralNode:
                 *      StringNode: a
                 */
                IdentifierNode identifierNode = (IdentifierNode) sigilNode.operand;
                // Rewrite the variable node from `$` to `%`
                OperatorNode varNode = new OperatorNode("%", identifierNode, sigilNode.tokenIndex);

                ctx.logDebug("visit(BinaryOperatorNode) $var{} ");
                varNode.accept(this.with(RuntimeContextType.LIST)); // target - left parameter

                // emit the {x} as a RuntimeList
                ListNode nodeRight = ((HashLiteralNode) node.right).asListNode();

                Node nodeZero = nodeRight.elements.get(0);
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
            if (sigil.equals("@") && sigilNode.operand instanceof IdentifierNode) {
                /*  @a{"a", "b"}
                 *  BinaryOperatorNode: {
                 *    OperatorNode: @
                 *      IdentifierNode: a
                 *    ArrayLiteralNode:
                 *      StringNode: a
                 *      StringNode: b
                 */
                IdentifierNode identifierNode = (IdentifierNode) sigilNode.operand;
                // Rewrite the variable node from `@` to `%`
                OperatorNode varNode = new OperatorNode("%", identifierNode, sigilNode.tokenIndex);

                ctx.logDebug("visit(BinaryOperatorNode) @var{} ");
                varNode.accept(this.with(RuntimeContextType.LIST)); // target - left parameter

                // emit the {x} as a RuntimeList
                ListNode nodeRight = ((HashLiteralNode) node.right).asListNode();

                Node nodeZero = nodeRight.elements.get(0);
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
            if (method instanceof OperatorNode) {
                OperatorNode op = (OperatorNode) method;
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

        // Optimization: Extract the single element if the list has only one item
        ArrayLiteralNode right = (ArrayLiteralNode) node.right;
        boolean emitIndexAsList = true;
        if (right.elements.size() == 1) {
            Node elem = right.elements.get(0);
            if (elem instanceof NumberNode || elem instanceof OperatorNode
                    || elem instanceof BinaryOperatorNode) {
                // TODO more optimizations
                elem.accept(this.with(RuntimeContextType.SCALAR));
                emitIndexAsList = false;
            }
        }
        if (emitIndexAsList) {
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
                throw new IllegalArgumentException("Unexpected array operation: " + arrayOperation);
        }

        ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", "arrayDerefGet", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
    }

    private void handleArrowHashDeref(BinaryOperatorNode node, String hashOperation) {
        ctx.logDebug("visit(BinaryOperatorNode) ->{} ");
        EmitterVisitor scalarVisitor =
                this.with(RuntimeContextType.SCALAR); // execute operands in scalar context

        node.left.accept(scalarVisitor); // target - left parameter

        // emit the {0} as a RuntimeList
        ListNode nodeRight = ((HashLiteralNode) node.right).asListNode();

        Node nodeZero = nodeRight.elements.get(0);
        if (nodeRight.elements.size() == 1 && nodeZero instanceof IdentifierNode) {
            // Convert IdentifierNode to StringNode:  {a} to {"a"}
            nodeRight.elements.set(0, new StringNode(((IdentifierNode) nodeZero).name, ((IdentifierNode) nodeZero).tokenIndex));
        }

        ctx.logDebug("visit -> (HashLiteralNode) autoquote " + node.right);
        nodeRight.accept(this.with(RuntimeContextType.SCALAR));

        String methodName;
        switch (hashOperation) {
            case "get":
                methodName = "hashDerefGet";
                break;
            case "delete":
                methodName = "hashDerefDelete";
                break;
            case "exists":
                methodName = "hashDerefExists";
                break;
            default:
                throw new IllegalArgumentException("Unexpected hash operation: " + hashOperation);
        }

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
        } else if (operator.equals("gmtime") || operator.equals("localtime")) {
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

    private void handleIndexBuiltin(OperatorNode node) {
        MethodVisitor mv = ctx.mv;
        EmitterVisitor scalarVisitor = this.with(RuntimeContextType.SCALAR);
        if (node.operand instanceof ListNode) {
            ListNode operand = (ListNode) node.operand;
            if (!operand.elements.isEmpty()) {
                operand.elements.get(0).accept(scalarVisitor);
                operand.elements.get(1).accept(scalarVisitor);
                if (operand.elements.size() == 3) {
                    operand.elements.get(2).accept(scalarVisitor);
                } else {
                    new OperatorNode("undef", null, node.tokenIndex).accept(scalarVisitor);
                }
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/RuntimeScalar",
                        node.operator,
                        "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
                if (ctx.contextType == RuntimeContextType.VOID) {
                    mv.visitInsn(Opcodes.POP);
                }
                return;
            }
        }
        throw new UnsupportedOperationException("Unsupported operator: " + node.operator);
    }

    private void handleAtan2(OperatorNode node) {
        MethodVisitor mv = ctx.mv;
        EmitterVisitor scalarVisitor = this.with(RuntimeContextType.SCALAR);
        if (node.operand instanceof ListNode) {
            ListNode operand = (ListNode) node.operand;
            if (operand.elements.size() == 2) {
                operand.elements.get(0).accept(scalarVisitor);
                operand.elements.get(1).accept(scalarVisitor);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/RuntimeScalar",
                        node.operator,
                        "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
                if (ctx.contextType == RuntimeContextType.VOID) {
                    mv.visitInsn(Opcodes.POP);
                }
                return;
            }
        }
        throw new UnsupportedOperationException("Unsupported operator: " + node.operator);
    }

    private void handleArrayUnaryBuiltin(OperatorNode node, String operator) {
        // Handle:  $#array  $#$array_ref  shift @array  pop @array
        Node operand = node.operand;
        ctx.logDebug("handleArrayUnaryBuiltin " + operand);
        if (operand instanceof ListNode) {
            operand = ((ListNode) operand).elements.get(0);
        }
        operand.accept(this.with(RuntimeContextType.LIST));
        ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeArray", operator, "()Lorg/perlonjava/runtime/RuntimeScalar;", false);
        if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    private void handleReverseBuiltin(OperatorNode node, String operator) {
        // Handle:  reverse LIST
        //   static RuntimeDataProvider reverse(RuntimeDataProvider value, int ctx)
        ctx.logDebug("handleReverseBuiltin " + node);
        node.operand.accept(this.with(RuntimeContextType.LIST));
        pushCallContext();
        ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/Operator", operator, "(Lorg/perlonjava/runtime/RuntimeDataProvider;I)Lorg/perlonjava/runtime/RuntimeDataProvider;", false);
        if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    private void handleSpliceBuiltin(OperatorNode node, String operator) {
        // Handle:  splice @array, LIST
        ctx.logDebug("handleSpliceBuiltin " + node);
        Node args = node.operand;
        Node operand = ((ListNode) args).elements.remove(0);
        operand.accept(this.with(RuntimeContextType.LIST));
        args.accept(this.with(RuntimeContextType.LIST));
        ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/Operator", operator, "(Lorg/perlonjava/runtime/RuntimeArray;Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeList;", false);
        if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    @Override
    public void visit(OperatorNode node) {
        MethodVisitor mv = ctx.mv;
        String operator = node.operator;
        ctx.logDebug("visit(OperatorNode) " + operator + " in context " + ctx.contextType);

        switch (operator) {
            case "package":
                handlePackageOperator(node);
                break;
            case "$":
            case "@":
            case "%":
            case "*":
            case "&":
                EmitVariable.handleVariableOperator(this, node, operator);
                break;
            case "keys":
            case "values":
                handleKeysOperator(node, operator);
                break;
            case "each":
                handleEachOperator(node, operator);
                break;
            case "our":
            case "my":
                EmitVariable.handleMyOperator(this, node, operator);
                break;
            case "return":
                handleReturnOperator(node);
                break;
            case "eval":
                EmitEval.handleEvalOperator(this, node);
                break;
            case "-":
                handleUnaryBuiltin(node, "unaryMinus");
                break;
            case "+":
                handleUnaryPlusOperator(node);
                break;
            case "~":
                handleUnaryBuiltin(node, "bitwiseNot");
                break;
            case "!":
            case "not":
                handleUnaryBuiltin(node, "not");
                break;
            case "abs":
            case "defined":
            case "length":
            case "log":
            case "sqrt":
            case "cos":
            case "sin":
            case "exp":
            case "quotemeta":
            case "rand":
            case "sleep":
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
            case "chop":
            case "chomp":
            case "undef":
            case "wantarray":
            case "time":
            case "times":
            case "localtime":
            case "gmtime":
                handleUnaryBuiltin(node, operator);
                break;
            case "rindex":
            case "index":
                handleIndexBuiltin(node);
                break;
            case "atan2":
                handleAtan2(node);
                break;
            case "scalar":
                handleScalar(node, mv);
                break;
            case "delete":
            case "exists":
                handleDeleteExists(node, operator);
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
                    node.operand.accept(this.with(RuntimeContextType.LIST));
                    ctx.mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "createReference", "()Lorg/perlonjava/runtime/RuntimeScalar;", true);
                    if (ctx.contextType == RuntimeContextType.VOID) {
                        mv.visitInsn(Opcodes.POP);
                    }
                }
                break;
            case "$#":
                node = new OperatorNode("$#", new OperatorNode("@", node.operand, node.tokenIndex), node.tokenIndex);
                handleArrayUnaryBuiltin(node, "indexLastElem");
                break;
            case "reverse":
                handleReverseBuiltin(node, operator);
                break;
            case "splice":
                handleSpliceBuiltin(node, operator);
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
                throw new UnsupportedOperationException("Unsupported operator: " + operator);
        }
    }

    private void handleScalar(OperatorNode node, MethodVisitor mv) {
        node.operand.accept(this.with(RuntimeContextType.SCALAR));
        ctx.mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "scalar", "()Lorg/perlonjava/runtime/RuntimeScalar;", true);
        if (ctx.contextType == RuntimeContextType.VOID) {
            mv.visitInsn(Opcodes.POP);
        }
    }

    private void handleDeleteExists(OperatorNode node, String operator) {
        //   OperatorNode: delete
        //    ListNode:
        //      BinaryOperatorNode: {
        //        OperatorNode: $
        //          IdentifierNode: a
        //        HashLiteralNode:
        //          NumberNode: 10
        if (node.operand instanceof ListNode) {
            ListNode operand = (ListNode) node.operand;
            if (operand.elements.size() == 1) {
                BinaryOperatorNode binop = (BinaryOperatorNode) operand.elements.get(0);
                if (binop.operator.equals("{")) {
                    handleHashElementOperator(binop, operator);
                    return;
                }
                if (binop.operator.equals("->")) {
                    if (binop.right instanceof HashLiteralNode) { // ->{x}
                        handleArrowHashDeref(binop, operator);
                        return;
                    }
                }
            }
        }
        throw new UnsupportedOperationException("Unsupported operator: " + operator);
    }

    private void handlePackageOperator(OperatorNode node) {
        String name = ((IdentifierNode) node.operand).name;
        ctx.symbolTable.setCurrentPackage(name);
        if (ctx.contextType != RuntimeContextType.VOID) {
            // if context is not void, return an empty list
            ListNode listNode = new ListNode(node.tokenIndex);
            listNode.accept(this);
        }
    }

    private void handleEachOperator(OperatorNode node, String operator) {
        node.operand.accept(this.with(RuntimeContextType.LIST));
        ctx.mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", operator, "()Lorg/perlonjava/runtime/RuntimeList;", true);
        if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    private void handleKeysOperator(OperatorNode node, String operator) {
        node.operand.accept(this.with(RuntimeContextType.LIST));
        ctx.mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", operator, "()Lorg/perlonjava/runtime/RuntimeArray;", true);
        if (ctx.contextType == RuntimeContextType.LIST) {
        } else if (ctx.contextType == RuntimeContextType.SCALAR) {
            ctx.mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "scalar", "()Lorg/perlonjava/runtime/RuntimeScalar;", true);
        } else if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    private void handleSayOperator(String operator, BinaryOperatorNode node) {
        // Emit the argument list
        node.right.accept(this.with(RuntimeContextType.LIST));

        // Emit the File Handle
        if (node.left instanceof OperatorNode) {
            // my $fh  $fh
            node.left.accept(this.with(RuntimeContextType.SCALAR));
        } else {
            emitFileHandle(node.left);
        }

        // Call the operator, return Scalar
        ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/Operator", operator, "(Lorg/perlonjava/runtime/RuntimeList;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    private void emitFileHandle(Node node) {
        // Emit File Handle
        if (node instanceof IdentifierNode) {
            // `print FILE 123`
            // retrieve STDOUT, STDERR from GlobalIORef
            // fetch a global fileHandle by name
            ctx.mv.visitLdcInsn(((IdentifierNode) node).name);
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

    private void handleUnaryPlusOperator(OperatorNode node) {
        node.operand.accept(this.with(RuntimeContextType.SCALAR));
        if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    private void handleReturnOperator(OperatorNode node) {
        ctx.logDebug("visit(return) in context " + ctx.contextType);
        ctx.logDebug("visit(return) will visit " + node.operand + " in context " + ctx.with(RuntimeContextType.RUNTIME).contextType);

        if (node.operand instanceof ListNode) {
            ListNode list = (ListNode) node.operand;
            if (list.elements.size() == 1) {
                // special case for a list with 1 element
                list.elements.get(0).accept(this.with(RuntimeContextType.RUNTIME));
                ctx.mv.visitJumpInsn(Opcodes.GOTO, ctx.returnLabel);
                return;
            }
        }

        node.operand.accept(this.with(RuntimeContextType.RUNTIME));
        ctx.mv.visitJumpInsn(Opcodes.GOTO, ctx.returnLabel);
        // TODO return (1,2), 3
    }

    @Override
    public void visit(AnonSubNode node) {
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
        ctx.logDebug("generateCodeBlock start");
        ctx.symbolTable.enterScope();
        EmitterVisitor voidVisitor =
                this.with(RuntimeContextType.VOID); // statements in the middle of the block have context VOID
        List<Node> list = node.elements;
        for (int i = 0; i < list.size(); i++) {
            Node element = list.get(i);

            // Annotate the bytecode with Perl source code line numbers
            int lineNumber = ctx.errorUtil.getLineNumber(element.getIndex());
            Label thisLabel = new Label();
            ctx.mv.visitLabel(thisLabel);
            ctx.mv.visitLineNumber(lineNumber, thisLabel); // Associate line number with thisLabel

            // Emit the statement with current context
            if (i == list.size() - 1) {
                // Special case for the last element
                ctx.logDebug("Last element: " + element);
                element.accept(this);
            } else {
                // General case for all other elements
                ctx.logDebug("Element: " + element);
                element.accept(voidVisitor);
            }
        }
        ctx.symbolTable.exitScope();
        ctx.logDebug("generateCodeBlock end");
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
