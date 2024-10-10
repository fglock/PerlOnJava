package org.perlonjava.codegen;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.*;
import org.perlonjava.runtime.NameNormalizer;
import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeContextType;
import org.perlonjava.runtime.ScalarGlobOperator;

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
                handleConcatOperator(node);
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
                handlePushOperator(node);
                return;
            case "map":
            case "sort":
            case "grep":
                handleMapOperator(node);
                return;
            case "eof":
            case "open":
            case "printf":
            case "print":
            case "say":
                handleSayOperator(node);
                return;
            case "close":
            case "readline":
                handleReadlineOperator(node);
                return;
            case "sprintf":
            case "substr":
                handleSubstr(node);
                return;
            case "x":
                handleRepeat(node);
                return;
            case "join":
                handleJoinOperator(node);
                return;
            case "split":
                handleSplitOperator(node);
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
            case "//=":
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
                handleRangeOperator(node);
                return;
        }

        String methodStr = operatorHandlers.get(operator);
        if (methodStr != null) {
            handleBinaryOperator(node, methodStr);
            return;
        }

        throw new PerlCompilerException(node.tokenIndex, "Unexpected infix operator: " + operator, ctx.errorUtil);
    }

    private void handleRangeOperator(BinaryOperatorNode node) {
        node.left.accept(this.with(RuntimeContextType.SCALAR));
        node.right.accept(this.with(RuntimeContextType.SCALAR));
        // static PerlRange generateList(int start, int end)
        ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/PerlRange",
                "createRange",
                "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/PerlRange;", false);
        if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    private void handleSubstr(BinaryOperatorNode node) {
        String operator = node.operator;
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

    private void handleConcatOperator(BinaryOperatorNode node) {
        EmitterVisitor scalarVisitor =
                this.with(RuntimeContextType.SCALAR); // execute operands in scalar context
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

    private void handleBinaryOperator(BinaryOperatorNode node, String methodStr) {
        EmitterVisitor scalarVisitor =
                this.with(RuntimeContextType.SCALAR); // execute operands in scalar context
        node.left.accept(scalarVisitor); // target - left parameter

        // Optimization
        if ((node.operator.equals("+")
                || node.operator.equals("-")
                || node.operator.equals("=="))
                && node.right instanceof NumberNode) {
            NumberNode right = (NumberNode) node.right;
            String value = right.value;
            boolean isInteger = isInteger(value);
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

    private void handlePushOperator(BinaryOperatorNode node) {
        String operator = node.operator;
        node.left.accept(this.with(RuntimeContextType.LIST));
        node.right.accept(this.with(RuntimeContextType.LIST));
        ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeArray", operator, "(Lorg/perlonjava/runtime/RuntimeDataProvider;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    private void handleMapOperator(BinaryOperatorNode node) {
        String operator = node.operator;
        node.right.accept(this.with(RuntimeContextType.LIST));  // list
        node.left.accept(this.with(RuntimeContextType.SCALAR)); // subroutine
        ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/Operator", operator, "(Lorg/perlonjava/runtime/RuntimeList;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeList;", false);
        if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    private void handleSplitOperator(BinaryOperatorNode node) {
        String operator = node.operator;
        node.left.accept(this.with(RuntimeContextType.SCALAR));
        node.right.accept(this.with(RuntimeContextType.LIST));
        ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/Operator", operator, "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeList;", false);
        if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    private void handleJoinOperator(BinaryOperatorNode node) {
        String operator = node.operator;
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

                ArrayLiteralNode right = (ArrayLiteralNode) node.right;
                if (right.elements.size() == 1) {
                    // Optimization: Extract the single element if the list has only one item
                    Node elem = right.elements.get(0);
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

        ArrayLiteralNode right = (ArrayLiteralNode) node.right;
        if (right.elements.size() == 1) {
            // Optimization: Extract the single element if the list has only one item
            Node elem = right.elements.get(0);
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
                throw new PerlCompilerException(node.tokenIndex, "Not implemented: hash operation: " + hashOperation, ctx.errorUtil);
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
        } else if (operator.equals("gmtime") || operator.equals("localtime") || operator.equals("caller")) {
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

    void handleDiamondBuiltin(OperatorNode node) {
        MethodVisitor mv = ctx.mv;
        String argument = ((StringNode) ((ListNode) node.operand).elements.get(0)).value;
        ctx.logDebug("visit diamond " + argument);
        if (argument.equals("") || argument.equals("<>")) {
            // null filehandle:  <>  <<>>
            node.operand.accept(this.with(RuntimeContextType.SCALAR));
            pushCallContext();
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/DiamondIO",
                    "readline",
                    "(Lorg/perlonjava/runtime/RuntimeScalar;I)Lorg/perlonjava/runtime/RuntimeDataProvider;", false);
            if (ctx.contextType == RuntimeContextType.VOID) {
                mv.visitInsn(Opcodes.POP);
            }
        } else {
            node.operator = "glob";
            handleGlobBuiltin(node);
        }
    }

    void handleChompBuiltin(OperatorNode node) {
        MethodVisitor mv = ctx.mv;
        node.operand.accept(this.with(RuntimeContextType.LIST));
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "org/perlonjava/runtime/RuntimeDataProvider",
                node.operator,
                "()Lorg/perlonjava/runtime/RuntimeScalar;", true);
        if (ctx.contextType == RuntimeContextType.VOID) {
            mv.visitInsn(Opcodes.POP);
        }
    }

    void handleGlobBuiltin(OperatorNode node) {
        MethodVisitor mv = ctx.mv;

        // Generate unique IDs for this glob instance
        int globId = ScalarGlobOperator.currentId++;

        // public static RuntimeDataProvider evaluate(id, patternArg, ctx)
        mv.visitLdcInsn(globId);
        node.operand.accept(this.with(RuntimeContextType.SCALAR));
        pushCallContext();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/ScalarGlobOperator", "evaluate", "(ILorg/perlonjava/runtime/RuntimeScalar;I)Lorg/perlonjava/runtime/RuntimeDataProvider;", false);

        // If the context is VOID, we need to pop the result from the stack
        if (ctx.contextType == RuntimeContextType.VOID) {
            mv.visitInsn(Opcodes.POP);
        }
    }

    private void handleVecBuiltin(OperatorNode node) {
        MethodVisitor mv = ctx.mv;
        node.operand.accept(this.with(RuntimeContextType.LIST));
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/Vec",
                node.operator,
                "(Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
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
        throw new PerlCompilerException(node.tokenIndex, "Not implemented: operator: " + node.operator, ctx.errorUtil);
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
        throw new PerlCompilerException(node.tokenIndex, "Not implemented: operator: " + node.operator, ctx.errorUtil);
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

    private void handleDieBuiltin(OperatorNode node) {
        // Handle:  die LIST
        //   static RuntimeDataProvider die(RuntimeDataProvider value, int ctx)
        String operator = node.operator;
        ctx.logDebug("handleDieBuiltin " + node);
        node.operand.accept(this.with(RuntimeContextType.LIST));

        // push the formatted line number
        Node message = new StringNode(ctx.errorUtil.errorMessage(node.tokenIndex, ""), node.tokenIndex);
        message.accept(this.with(RuntimeContextType.SCALAR));

        ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/Operator",
                operator,
                "(Lorg/perlonjava/runtime/RuntimeDataProvider;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeDataProvider;", false);
        if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    private void handleReverseBuiltin(OperatorNode node) {
        // Handle:  reverse LIST
        //   static RuntimeDataProvider reverse(RuntimeDataProvider value, int ctx)
        String operator = node.operator;
        ctx.logDebug("handleReverseBuiltin " + node);
        node.operand.accept(this.with(RuntimeContextType.LIST));
        pushCallContext();
        ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/Operator", operator, "(Lorg/perlonjava/runtime/RuntimeDataProvider;I)Lorg/perlonjava/runtime/RuntimeDataProvider;", false);
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

    private void handleCryptBuiltin(OperatorNode node) {
        // Handle:  crypt PLAINTEXT,SALT
        ctx.logDebug("handleCryptBuiltin " + node);
        node.operand.accept(this.with(RuntimeContextType.LIST));
        ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/Crypt",
                node.operator,
                "(Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeScalar;",
                false);
        if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    private void handleUnpackBuiltin(OperatorNode node) {
        // Handle:  unpack TEMPLATE, EXPR
        ctx.logDebug("handleUnpackBuiltin " + node);
        node.operand.accept(this.with(RuntimeContextType.LIST));
        ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/Unpack",
                node.operator,
                "(Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeList;",
                false);
        if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    private void handlePackBuiltin(OperatorNode node) {
        // Handle:  pack TEMPLATE, LIST
        ctx.logDebug("handlePackBuiltin " + node);
        node.operand.accept(this.with(RuntimeContextType.LIST));
        ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/Pack",
                node.operator,
                "(Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeScalar;",
                false);
        if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    private void handleSpliceBuiltin(OperatorNode node) {
        // Handle:  splice @array, LIST
        String operator = node.operator;
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
                EmitVariable.handleVariableOperator(this, node);
                break;
            case "keys":
            case "values":
                handleKeysOperator(node);
                break;
            case "each":
                handleEachOperator(node);
                break;
            case "our":
            case "my":
                EmitVariable.handleMyOperator(this, node);
                break;
            case "next":
            case "redo":
            case "last":
                handleNextOperator(node);
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
            case "<":
                handleDiamondBuiltin(node);
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
            case "rewinddir":
            case "telldir":
            case "closedir":
            case "rmdir":
                handleUnaryBuiltin(node, operator);
                break;
            case "chop":
            case "chomp":
                handleChompBuiltin(node);
                break;
            case "mkdir":
            case "opendir":
                handleMkdirOperator(node);
                break;
            case "readdir":
                handleReaddirOperator(node);
                break;
            case "pack":
                handlePackBuiltin(node);
                break;
            case "unpack":
                handleUnpackBuiltin(node);
                break;
            case "crypt":
                handleCryptBuiltin(node);
                break;
            case "glob":
                handleGlobBuiltin(node);
                break;
            case "rindex":
            case "index":
                handleIndexBuiltin(node);
                break;
            case "vec":
                handleVecBuiltin(node);
                break;
            case "atan2":
                handleAtan2(node);
                break;
            case "scalar":
                handleScalar(node);
                break;
            case "delete":
            case "exists":
                handleDeleteExists(node);
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
                handleDieBuiltin(node);
                break;
            case "reverse":
            case "unlink":
                handleReverseBuiltin(node);
                break;
            case "splice":
                handleSpliceBuiltin(node);
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
            if (node.operand instanceof OperatorNode && ((OperatorNode) node.operand).operator.equals("&")) {
                // System.out.println("handleCreateReference of &var");
                //  &var is already a reference
                node.operand.accept(this.with(RuntimeContextType.LIST));
            } else {
                node.operand.accept(this.with(RuntimeContextType.LIST));
                ctx.mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "createReference", "()Lorg/perlonjava/runtime/RuntimeScalar;", true);
            }
            if (ctx.contextType == RuntimeContextType.VOID) {
                mv.visitInsn(Opcodes.POP);
            }
        }
    }

    private void handleScalar(OperatorNode node) {
        MethodVisitor mv = ctx.mv;
        node.operand.accept(this.with(RuntimeContextType.SCALAR));
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "scalar", "()Lorg/perlonjava/runtime/RuntimeScalar;", true);
        if (ctx.contextType == RuntimeContextType.VOID) {
            mv.visitInsn(Opcodes.POP);
        }
    }

    private void handleDeleteExists(OperatorNode node) {
        //   OperatorNode: delete
        //    ListNode:
        //      BinaryOperatorNode: {
        //        OperatorNode: $
        //          IdentifierNode: a
        //        HashLiteralNode:
        //          NumberNode: 10
        String operator = node.operator;
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
        throw new PerlCompilerException(node.tokenIndex, "Not implemented: operator: " + operator, ctx.errorUtil);
    }

    private void handlePackageOperator(OperatorNode node) {
        String name = ((IdentifierNode) node.operand).name;
        ctx.symbolTable.setCurrentPackage(name);
        DebugInfo.setDebugInfoFileName(ctx);
        if (ctx.contextType != RuntimeContextType.VOID) {
            // if context is not void, return an empty list
            ListNode listNode = new ListNode(node.tokenIndex);
            listNode.accept(this);
        }
    }

    private void handleReaddirOperator(OperatorNode node) {
        String operator = node.operator;
        node.operand.accept(this.with(RuntimeContextType.SCALAR));
        pushCallContext();
        ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/Operator",
                operator,
                "(Lorg/perlonjava/runtime/RuntimeScalar;I)Lorg/perlonjava/runtime/RuntimeDataProvider;", false);
        if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    private void handleMkdirOperator(OperatorNode node) {
        String operator = node.operator;
        node.operand.accept(this.with(RuntimeContextType.LIST));
        ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/Operator",
                operator,
                "(Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    private void handleEachOperator(OperatorNode node) {
        String operator = node.operator;
        node.operand.accept(this.with(RuntimeContextType.LIST));
        ctx.mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", operator, "()Lorg/perlonjava/runtime/RuntimeList;", true);
        if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    private void handleKeysOperator(OperatorNode node) {
        String operator = node.operator;
        node.operand.accept(this.with(RuntimeContextType.LIST));
        ctx.mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", operator, "()Lorg/perlonjava/runtime/RuntimeArray;", true);
        if (ctx.contextType == RuntimeContextType.LIST) {
        } else if (ctx.contextType == RuntimeContextType.SCALAR) {
            ctx.mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "scalar", "()Lorg/perlonjava/runtime/RuntimeScalar;", true);
        } else if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    private void handleReadlineOperator(BinaryOperatorNode node) {
        String operator = node.operator;

        // Emit the File Handle
        if (node.left instanceof OperatorNode) {
            // my $fh  $fh
            node.left.accept(this.with(RuntimeContextType.SCALAR));
        } else {
            emitFileHandle(node.left);
        }

        if (operator.equals("readline")) {
            pushCallContext();  // SCALAR or LIST context
            ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/Operator", operator, "(Lorg/perlonjava/runtime/RuntimeScalar;I)Lorg/perlonjava/runtime/RuntimeDataProvider;", false);
        } else {
            ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/Operator", operator, "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        }
        
        if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    private void handleSayOperator(BinaryOperatorNode node) {
        String operator = node.operator;
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

    private void handleUnaryPlusOperator(OperatorNode node) {
        node.operand.accept(this.with(RuntimeContextType.SCALAR));
        if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    private void handleNextOperator(OperatorNode node) {
        ctx.logDebug("visit(next)");

        String labelStr = null;
        ListNode labelNode = (ListNode) node.operand;
        if (labelNode.elements.isEmpty()) {
            // next
        } else {
            // next LABEL
            Node arg = labelNode.elements.get(0);
            if (arg instanceof IdentifierNode) {
                // L1
                labelStr = ((IdentifierNode) arg).name;
            } else {
                throw new RuntimeException("Not implemented: " + node);
            }
        }

        String operator = node.operator;
        LoopLabels loopLabels = ctx.javaClassInfo.findLoopLabelsByName(labelStr);
        ctx.logDebug("visit(next) operator: " + operator + " label: " + labelStr + " labels: " + loopLabels);
        if (loopLabels == null) {
            throw new RuntimeException("Label not found: " + node);
        }

        ctx.logDebug("visit(next): asmStackLevel: " + ctx.javaClassInfo.asmStackLevel);

        int consumeStack = ctx.javaClassInfo.asmStackLevel;
        int targetStack = loopLabels.asmStackLevel;
        while (consumeStack-- > targetStack) {
            // consume the JVM stack
            ctx.mv.visitInsn(Opcodes.POP);
        }

        Label label = operator.equals("next") ? loopLabels.nextLabel
                : operator.equals("last") ? loopLabels.lastLabel
                : loopLabels.redoLabel;
        ctx.mv.visitJumpInsn(Opcodes.GOTO, label);
    }

    private void handleReturnOperator(OperatorNode node) {
        ctx.logDebug("visit(return) in context " + ctx.contextType);
        ctx.logDebug("visit(return) will visit " + node.operand + " in context " + ctx.with(RuntimeContextType.RUNTIME).contextType);

        int consumeStack = ctx.javaClassInfo.asmStackLevel;
        while (consumeStack-- > 0) {
            // consume the JVM stack
            ctx.mv.visitInsn(Opcodes.POP);
        }

        if (node.operand instanceof ListNode) {
            ListNode list = (ListNode) node.operand;
            if (list.elements.size() == 1) {
                // special case for a list with 1 element
                list.elements.get(0).accept(this.with(RuntimeContextType.RUNTIME));
                ctx.mv.visitJumpInsn(Opcodes.GOTO, ctx.javaClassInfo.returnLabel);
                return;
            }
        }

        node.operand.accept(this.with(RuntimeContextType.RUNTIME));
        ctx.mv.visitJumpInsn(Opcodes.GOTO, ctx.javaClassInfo.returnLabel);
        // TODO return (1,2), 3
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
