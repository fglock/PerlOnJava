package org.perlonjava.codegen;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.*;
import org.perlonjava.runtime.*;

import java.util.*;

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
        // Emit code for identifier
        throw new PerlCompilerException(
                node.tokenIndex, "Not implemented: bare word " + node.name, ctx.errorUtil);
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
                handleAssignOperator(node);
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
                node.left.accept(this.with(RuntimeContextType.SCALAR));
                node.right.accept(this.with(RuntimeContextType.LIST));
                ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/Operator", operator, "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
                if (ctx.contextType == RuntimeContextType.VOID) {
                    ctx.mv.visitInsn(Opcodes.POP);
                }
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
                this.visit(
                        new OperatorNode("not",
                                new OperatorNode("scalar",
                                        new BinaryOperatorNode(
                                                "=~",
                                                node.left,
                                                node.right,
                                                node.tokenIndex
                                        ), node.tokenIndex
                                ), node.tokenIndex
                        ));
                return;
            case "=~":
                //
                //  BinaryOperatorNode: =~
                //    OperatorNode: $
                //      IdentifierNode: a
                //    OperatorNode: matchRegex (or `qr` object)
                //      ListNode:
                //        StringNode: 'abc'
                //        StringNode: 'i'
                //
                if (node.right instanceof OperatorNode) {
                    OperatorNode right = (OperatorNode) node.right;
                    if (right.operand instanceof ListNode) {
                        // regex operator:  $v =~ /regex/;
                        // bind the variable to the regex operation
                        ((ListNode) right.operand).elements.add(node.left);
                        right.accept(this);
                        return;
                    }
                }
                // not a regex operator:  $v =~ $qr;
                node.right.accept(scalarVisitor);
                node.left.accept(scalarVisitor);
                pushCallContext();
                ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/RuntimeRegex", "matchRegex",
                        "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;I)Lorg/perlonjava/runtime/RuntimeDataProvider;", false);
                if (ctx.contextType == RuntimeContextType.VOID) {
                    ctx.mv.visitInsn(Opcodes.POP);
                }
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
        // Implements `||=` `&&=`, depending on compareOpcode

        MethodVisitor mv = ctx.mv;
        Label endLabel = new Label(); // Label for the end of the operation

        node.left.accept(this.with(RuntimeContextType.SCALAR)); // target - left parameter
        // the left parameter is in the stack

        mv.visitInsn(Opcodes.DUP);
        // stack is [left, left]

        // Convert the result to a boolean
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "getBoolean", "()Z", true);
        // stack is [left, boolean]

        // If the boolean value is true, jump to endLabel (we keep the left operand)
        mv.visitJumpInsn(compareOpcode, endLabel);

        node.right.accept(this.with(RuntimeContextType.SCALAR)); // Evaluate right operand in scalar context
        // stack is [left, right]

        mv.visitInsn(Opcodes.DUP_X1); // Stack becomes [right, left, right]
        mv.visitInsn(Opcodes.SWAP);   // Stack becomes [right, right, left]

        // Assign right to left
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "addToScalar", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", true);
        mv.visitInsn(Opcodes.POP);
        // stack is [right]

        // At this point, the stack either has the left (if it was true) or the right (if left was false)
        mv.visitLabel(endLabel);

        // If the context is VOID, we need to pop the result from the stack
        if (ctx.contextType == RuntimeContextType.VOID) {
            mv.visitInsn(Opcodes.POP);
        }
    }

    private void handleOrOperator(BinaryOperatorNode node, int compareOpcode) {
        // Implements `||` `&&`, depending on compareOpcode

        MethodVisitor mv = ctx.mv;
        Label endLabel = new Label(); // Label for the end of the operation

        node.left.accept(this.with(RuntimeContextType.SCALAR)); // target - left parameter
        // the left parameter is in the stack

        mv.visitInsn(Opcodes.DUP);
        // stack is [left, left]

        // Convert the result to a boolean
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "getBoolean", "()Z", true);
        // stack is [left, boolean]

        // If the left operand boolean value is true, return left operand
        mv.visitJumpInsn(compareOpcode, endLabel);

        mv.visitInsn(Opcodes.POP); // remove left operand
        node.right.accept(this.with(RuntimeContextType.SCALAR)); // right operand in scalar context
        // stack is [right]

        mv.visitLabel(endLabel);
        if (ctx.contextType == RuntimeContextType.VOID) {
            mv.visitInsn(Opcodes.POP);
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
                handleVariableOperator(node, operator);
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
                handleMyOperator(node, operator);
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
                node.operand.accept(this.with(RuntimeContextType.SCALAR));
                ctx.mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "scalar", "()Lorg/perlonjava/runtime/RuntimeScalar;", true);
                if (ctx.contextType == RuntimeContextType.VOID) {
                    mv.visitInsn(Opcodes.POP);
                }
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
                handleRegex(node);
                break;
            default:
                throw new UnsupportedOperationException("Unsupported operator: " + operator);
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

    private void handleRegex(OperatorNode node) {
        ListNode operand = (ListNode) node.operand;
        EmitterVisitor scalarVisitor = this.with(RuntimeContextType.SCALAR);
        Node variable = null;

        if (node.operator.equals("qx")) {
            // static RuntimeScalar systemCommand(RuntimeScalar command)
            operand.elements.get(0).accept(scalarVisitor);
            pushCallContext();
            ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/RuntimeIO",
                    "systemCommand",
                    "(Lorg/perlonjava/runtime/RuntimeScalar;I)Lorg/perlonjava/runtime/RuntimeDataProvider;", false);
            if (ctx.contextType == RuntimeContextType.VOID) {
                ctx.mv.visitInsn(Opcodes.POP);
            }
            return;
        }

        if (node.operator.equals("tr") || node.operator.equals("y")) {
            // static RuntimeTransliterate compile(RuntimeScalar search, RuntimeScalar replace, RuntimeScalar modifiers)
            operand.elements.get(0).accept(scalarVisitor);
            operand.elements.get(1).accept(scalarVisitor);
            operand.elements.get(2).accept(scalarVisitor);
            if (operand.elements.size() > 3) {
                variable = operand.elements.get(3);
            }
            ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/RuntimeTransliterate", "compile",
                    "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeTransliterate;", false);

            // RuntimeScalar transliterate(RuntimeScalar originalString)
            if (variable == null) {
                // use `$_`
                variable = new OperatorNode("$", new IdentifierNode("_", node.tokenIndex), node.tokenIndex);
            }
            variable.accept(scalarVisitor);
            ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeTransliterate", "transliterate", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
            if (ctx.contextType == RuntimeContextType.VOID) {
                ctx.mv.visitInsn(Opcodes.POP);
            }
            return;

        } else if (node.operator.equals("replaceRegex")) {
            // RuntimeBaseEntity replaceRegex(RuntimeScalar quotedRegex, RuntimeScalar string, RuntimeScalar replacement, int ctx)
            operand.elements.get(0).accept(scalarVisitor);
            operand.elements.get(1).accept(scalarVisitor);
            operand.elements.get(2).accept(scalarVisitor);
            if (operand.elements.size() > 3) {
                variable = operand.elements.get(3);
            }
            ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/RuntimeRegex", "getReplacementRegex",
                    "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);

        } else {
            // RuntimeRegex.getQuotedRegex(RuntimeScalar patternString, RuntimeScalar modifiers)
            operand.elements.get(0).accept(scalarVisitor);
            operand.elements.get(1).accept(scalarVisitor);
            if (operand.elements.size() > 2) {
                variable = operand.elements.get(2);
            }
            ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/RuntimeRegex", "getQuotedRegex",
                    "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        }
        if (node.operator.equals("quoteRegex")) {
            // do not execute  `qr//`
            return;
        }

        if (variable == null) {
            // use `$_`
            variable = new OperatorNode("$", new IdentifierNode("_", node.tokenIndex), node.tokenIndex);
        }
        variable.accept(scalarVisitor);

        pushCallContext();
        ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/RuntimeRegex", "matchRegex",
                "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;I)Lorg/perlonjava/runtime/RuntimeDataProvider;", false);

        if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
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

    private void fetchGlobalVariable(boolean createIfNotExists, String sigil, String varName, int tokenIndex) {

        String var = GlobalContext.normalizeVariableName(varName, ctx.symbolTable.getCurrentPackage());
        ctx.logDebug("GETVAR lookup global " + sigil + varName + " normalized to " + var + " createIfNotExists:" + createIfNotExists);

        if (sigil.equals("$") && (createIfNotExists || GlobalContext.existsGlobalVariable(var))) {
            // fetch a global variable
            ctx.mv.visitLdcInsn(var);
            ctx.mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/GlobalContext",
                    "getGlobalVariable",
                    "(Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeScalar;",
                    false);
        } else if (sigil.equals("@") && (createIfNotExists || GlobalContext.existsGlobalArray(var))) {
            // fetch a global variable
            ctx.mv.visitLdcInsn(var);
            ctx.mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/GlobalContext",
                    "getGlobalArray",
                    "(Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeArray;",
                    false);
        } else if (sigil.equals("%") && (createIfNotExists || GlobalContext.existsGlobalHash(var))) {
            // fetch a global variable
            ctx.mv.visitLdcInsn(var);
            ctx.mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/GlobalContext",
                    "getGlobalHash",
                    "(Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeHash;",
                    false);
        } else {
            // variable not found
            System.err.println(
                    ctx.errorUtil.errorMessage(tokenIndex,
                            "Warning: Global symbol \""
                                    + sigil + varName
                                    + "\" requires explicit package name (did you forget to declare \"my "
                                    + sigil + varName
                                    + "\"?)"));
        }
    }

    private void handleVariableOperator(OperatorNode node, String sigil) {
        if (ctx.contextType == RuntimeContextType.VOID) {
            return;
        }
        MethodVisitor mv = ctx.mv;
        if (node.operand instanceof IdentifierNode) { // $a @a %a
            String name = ((IdentifierNode) node.operand).name;
            ctx.logDebug("GETVAR " + sigil + name);

            if (sigil.equals("*")) {
                // typeglob
                String fullName = GlobalContext.normalizeVariableName(name, ctx.symbolTable.getCurrentPackage());
                mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/RuntimeGlob");
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn(fullName); // emit string
                mv.visitMethodInsn(
                        Opcodes.INVOKESPECIAL,
                        "org/perlonjava/runtime/RuntimeGlob",
                        "<init>",
                        "(Ljava/lang/String;)V",
                        false); // Call new RuntimeGlob(String)
                return;
            }

            if (sigil.equals("&")) {
                // Code
                String fullName = GlobalContext.normalizeVariableName(name, ctx.symbolTable.getCurrentPackage());
                mv.visitLdcInsn(fullName); // emit string
                ctx.mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/GlobalContext",
                        "getGlobalCodeRef",
                        "(Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeScalar;",
                        false);
                return;
            }

            int varIndex = ctx.symbolTable.getVariableIndex(sigil + name);
            if (varIndex == -1) {
                // not a declared `my` or `our` variable
                // Fetch a global variable.
                // Autovivify if the name is fully qualified, or if it is a regex variable like `$1`
                // TODO special variables: `$,` `$$`
                boolean createIfNotExists = name.contains("::") || isInteger(name);
                fetchGlobalVariable(createIfNotExists, sigil, name, node.getIndex());
            } else {
                // retrieve the `my` or `our` variable from local vars
                mv.visitVarInsn(Opcodes.ALOAD, varIndex);
            }
            if (ctx.contextType == RuntimeContextType.SCALAR && !sigil.equals("$")) {
                // scalar context: transform the value in the stack to scalar
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "scalar", "()Lorg/perlonjava/runtime/RuntimeScalar;", true);
            }
            ctx.logDebug("GETVAR end " + varIndex);
            return;
        }
        switch (sigil) {
            case "@":
                // `@$a`
                ctx.logDebug("GETVAR `@$a`");
                node.operand.accept(this.with(RuntimeContextType.LIST));
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", "arrayDeref", "()Lorg/perlonjava/runtime/RuntimeArray;", false);
                return;
            case "%":
                // `%$a`
                ctx.logDebug("GETVAR `%$a`");
                node.operand.accept(this.with(RuntimeContextType.LIST));
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", "hashDeref", "()Lorg/perlonjava/runtime/RuntimeHash;", false);
                return;
            case "$":
                // `$$a`
                ctx.logDebug("GETVAR `$$a`");
                node.operand.accept(this.with(RuntimeContextType.SCALAR));
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", "scalarDeref", "()Lorg/perlonjava/runtime/RuntimeScalar;", false);
                return;
        }

        // TODO ${a} ${[ 123 ]}
        throw new PerlCompilerException(node.tokenIndex, "Not implemented: " + sigil, ctx.errorUtil);
    }

    private void handleAssignOperator(BinaryOperatorNode node) {
        ctx.logDebug("SET " + node);
        MethodVisitor mv = ctx.mv;
        // Determine the assign type based on the left side.
        // Inspect the AST and get the L-value context: SCALAR or LIST
        int lvalueContext = LValueVisitor.getContext(node);
        ctx.logDebug("SET Lvalue context: " + lvalueContext);
        // Execute the right side first: assignment is right-associative
        switch (lvalueContext) {
            case RuntimeContextType.SCALAR:
                ctx.logDebug("SET right side scalar");
                node.right.accept(this.with(RuntimeContextType.SCALAR));   // emit the value
                node.left.accept(this.with(RuntimeContextType.SCALAR));   // emit the variable

                boolean isGlob = false;
                String leftDescriptor = "org/perlonjava/runtime/RuntimeScalar";
                if (node.left instanceof OperatorNode && ((OperatorNode) node.left).operator.equals("*")) {
                    leftDescriptor = "org/perlonjava/runtime/RuntimeGlob";
                    isGlob = true;
                }
                String rightDescriptor = "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;";
                if (node.right instanceof OperatorNode && ((OperatorNode) node.right).operator.equals("*")) {
                    rightDescriptor = "(Lorg/perlonjava/runtime/RuntimeGlob;)Lorg/perlonjava/runtime/RuntimeScalar;";
                    isGlob = true;
                }
                if (isGlob) {
                    mv.visitInsn(Opcodes.SWAP); // move the target first
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, leftDescriptor, "set", rightDescriptor, false);
                } else {
                    mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "addToScalar", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", true);
                }
                break;
            case RuntimeContextType.LIST:
                ctx.logDebug("SET right side list");
                Node nodeRight = node.right;
                // make sure the right node is a ListNode
                if (!(nodeRight instanceof ListNode)) {
                    List<Node> elements = new ArrayList<>();
                    elements.add(nodeRight);
                    nodeRight = new ListNode(elements, node.tokenIndex);
                }
                nodeRight.accept(this.with(RuntimeContextType.LIST));   // emit the value
                node.left.accept(this.with(RuntimeContextType.LIST));   // emit the variable
                mv.visitInsn(Opcodes.SWAP); // move the target first
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "set", "(Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeArray;", true);
                break;
            default:
                throw new IllegalArgumentException("Unsupported assignment context: " + lvalueContext);
        }
        if (ctx.contextType == RuntimeContextType.VOID) {
            // Remove the value from the stack
            mv.visitInsn(Opcodes.POP);
        }
        ctx.logDebug("SET end");
    }

    private void handleMyOperator(OperatorNode node, String operator) {
        if (node.operand instanceof ListNode) { // my ($a, $b)  our ($a, $b)
            // process each item of the list; then returns the list
            ListNode listNode = (ListNode) node.operand;
            for (Node element : listNode.elements) {
                if (element instanceof OperatorNode && "undef".equals(((OperatorNode) element).operator)) {
                    continue; // skip "undef"
                }
                OperatorNode myNode = new OperatorNode(operator, element, listNode.tokenIndex);
                myNode.accept(this.with(RuntimeContextType.VOID));
            }
            if (ctx.contextType != RuntimeContextType.VOID) {
                listNode.accept(this);
            }
            return;
        } else if (node.operand instanceof OperatorNode) { //  [my our] followed by [$ @ %]
            OperatorNode sigilNode = (OperatorNode) node.operand;
            String sigil = sigilNode.operator;
            if ("$@%".contains(sigil)) {
                Node identifierNode = sigilNode.operand;
                if (identifierNode instanceof IdentifierNode) { // my $a
                    String name = ((IdentifierNode) identifierNode).name;
                    String var = sigil + name;
                    ctx.logDebug("MY " + operator + " " + sigil + name);
                    if (ctx.symbolTable.getVariableIndexInCurrentScope(var) != -1) {
                        System.err.println(
                                ctx.errorUtil.errorMessage(node.getIndex(),
                                        "Warning: \"" + operator + "\" variable "
                                                + var
                                                + " masks earlier declaration in same ctx.symbolTable"));
                    }
                    int varIndex = ctx.symbolTable.addVariable(var);
                    // TODO optimization - SETVAR+MY can be combined

                    // Determine the class name based on the sigil
                    String className = EmitterMethodCreator.getVariableClassName(sigil);

                    if (operator.equals("my")) {
                        // "my":
                        // Create a new instance of the determined class
                        ctx.mv.visitTypeInsn(Opcodes.NEW, className);
                        ctx.mv.visitInsn(Opcodes.DUP);
                        ctx.mv.visitMethodInsn(
                                Opcodes.INVOKESPECIAL,
                                className,
                                "<init>",
                                "()V",
                                false);
                    } else {
                        // "our":
                        // Create and fetch a global variable
                        fetchGlobalVariable(true, sigil, name, node.getIndex());
                    }
                    if (ctx.contextType != RuntimeContextType.VOID) {
                        ctx.mv.visitInsn(Opcodes.DUP);
                    }
                    // Store in a JVM local variable
                    ctx.mv.visitVarInsn(Opcodes.ASTORE, varIndex);
                    if (ctx.contextType == RuntimeContextType.SCALAR && !sigil.equals("$")) {
                        // scalar context: transform the value in the stack to scalar
                        ctx.mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "scalar", "()Lorg/perlonjava/runtime/RuntimeScalar;", true);
                    }
                    return;
                }
            }
        }
        throw new PerlCompilerException(
                node.tokenIndex, "Not implemented: " + node.operator, ctx.errorUtil);
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
        ctx.logDebug("TERNARY_OP start");

        // Create labels for the else and end branches
        Label elseLabel = new Label();
        Label endLabel = new Label();

        // Visit the condition node in scalar context
        node.condition.accept(this.with(RuntimeContextType.SCALAR));

        // Convert the result to a boolean
        ctx.mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "getBoolean", "()Z", true);

        // Jump to the else label if the condition is false
        ctx.mv.visitJumpInsn(Opcodes.IFEQ, elseLabel);

        // Visit the then branch
        node.trueExpr.accept(this);

        // Jump to the end label after executing the then branch
        ctx.mv.visitJumpInsn(Opcodes.GOTO, endLabel);

        // Visit the else label
        ctx.mv.visitLabel(elseLabel);

        // Visit the else branch
        node.falseExpr.accept(this);

        // Visit the end label
        ctx.mv.visitLabel(endLabel);

        ctx.logDebug("TERNARY_OP end");
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
