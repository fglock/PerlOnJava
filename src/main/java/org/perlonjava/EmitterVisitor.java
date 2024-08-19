package org.perlonjava;

import java.util.*;

import org.objectweb.asm.*;
import org.perlonjava.node.*;

public class EmitterVisitor implements Visitor {
    private final EmitterContext ctx;

    /**
     * Cache for EmitterVisitor instances with different ContextTypes
     */
    private final Map<RuntimeContextType, EmitterVisitor> visitorCache = new EnumMap<>(RuntimeContextType.class);

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
    public EmitterVisitor with(RuntimeContextType contextType) {
        // Check if the visitor is already cached
        if (visitorCache.containsKey(contextType)) {
            return visitorCache.get(contextType);
        }
        // Create a new visitor and cache it
        EmitterVisitor newVisitor = new EmitterVisitor(ctx.with(contextType));
        visitorCache.put(contextType, newVisitor);
        return newVisitor;
    }

    @Override
    public void visit(NumberNode node) {
        ctx.logDebug("visit(NumberNode) in context " + ctx.contextType);
        if (ctx.contextType == RuntimeContextType.VOID) {
            return;
        }
        MethodVisitor mv = ctx.mv;
        String value = node.value.replace("_", "");
        boolean isInteger = !value.contains(".");
        if (ctx.isBoxed) { // expect a RuntimeScalar object
            if (isInteger) {
                ctx.logDebug("visit(NumberNode) emit boxed integer");
                mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/RuntimeScalar");
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn(
                        Integer.valueOf(value)); // Push the integer argument onto the stack
                mv.visitMethodInsn(
                        Opcodes.INVOKESPECIAL, "org/perlonjava/RuntimeScalar", "<init>", "(I)V", false); // Call new RuntimeScalar(int)
            } else {
                mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/RuntimeScalar");
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn(Double.valueOf(value)); // Push the double argument onto the stack
                mv.visitMethodInsn(
                        Opcodes.INVOKESPECIAL, "org/perlonjava/RuntimeScalar", "<init>", "(D)V", false); // Call new RuntimeScalar(double)
            }
            // if (ctx.contextType == RuntimeContextType.LIST) {
            //   // Transform the value in the stack to List
            //   mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/RuntimeScalar", "getList", "()Lorg/perlonjava/RuntimeList;", false);
            // }
        } else {
            if (isInteger) {
                mv.visitLdcInsn(Integer.parseInt(value)); // emit native integer
            } else {
                mv.visitLdcInsn(Double.parseDouble(value)); // emit native double
            }
        }
    }

    @Override
    public void visit(IdentifierNode node) throws Exception {
        // Emit code for identifier
        throw new PerlCompilerException(
                node.tokenIndex, "Not implemented: bare word " + node.name, ctx.errorUtil);
    }

    /**
     * Emits a call to a binary built-in method on the RuntimeScalar class. It assumes that the parameter to
     * the call is already in the stack.
     *
     * @param operator The name of the built-in method to call.
     */
    private void handleBinaryBuiltin(String operator) {
        ctx.mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, "org/perlonjava/RuntimeScalar", operator, "(Lorg/perlonjava/RuntimeScalar;)Lorg/perlonjava/RuntimeScalar;", false);
        if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    @Override
    public void visit(BinaryOperatorNode node) throws Exception {
        String operator = node.operator;
        ctx.logDebug("visit(BinaryOperatorNode) " + operator + " in context " + ctx.contextType);
        EmitterVisitor scalarVisitor =
                this.with(RuntimeContextType.SCALAR); // execute operands in scalar context

        switch (operator) { // handle operators that support short-circuit or other special cases
            case "||":
            case "or":
                node.left.accept(scalarVisitor); // target - left parameter
                handleOrOperator(node);
                return;
            case "&&":
            case "and":
                node.left.accept(scalarVisitor); // target - left parameter
                handleAndOperator(node);
                return;
            case "=":
                handleSetOperator(node);
                return;
            case "->":
                handleArrowOperator(node);
                return;
            case "[":
                handleArrayElementOperator(node);
                return;
            case "{":
                handleHashElementOperator(node);
                return;
            case "(":
                handleApplyOperator(node);
                return;
            case "join":
                handleJoinOperator(operator, node);
                return;
        }

        node.left.accept(scalarVisitor); // target - left parameter
        node.right.accept(scalarVisitor); // right parameter

        switch (operator) {
            case "+":
                handleBinaryBuiltin("add"); // TODO optimize use: ctx.mv.visitInsn(IADD)
                break;
            case "-":
                handleBinaryBuiltin("subtract");
                break;
            case "*":
                handleBinaryBuiltin("multiply");
                break;
            case "/":
                handleBinaryBuiltin("divide");
                break;
            case "%":
                handleBinaryBuiltin("modulus");
                break;
            case "**":
                handleBinaryBuiltin("pow");
                break;

            case "<":
                handleBinaryBuiltin("lessThan");
                break;
            case "<=":
                handleBinaryBuiltin("lessThanOrEqual");
                break;
            case ">":
                handleBinaryBuiltin("greaterThan");
                break;
            case ">=":
                handleBinaryBuiltin("greaterThanOrEqual");
                break;
            case "==":
                handleBinaryBuiltin("equalTo");
                break;
            case "!=":
                handleBinaryBuiltin("notEqualTo");
                break;
            case "<=>":
                handleBinaryBuiltin("spaceship");
                break;
            case "eq":
                handleBinaryBuiltin("eq");
                break;
            case "ne":
                handleBinaryBuiltin("ne");
                break;
            case "lt":
                handleBinaryBuiltin("lt");
                break;
            case "le":
                handleBinaryBuiltin("le");
                break;
            case "gt":
                handleBinaryBuiltin("gt");
                break;
            case "ge":
                handleBinaryBuiltin("ge");
                break;
            case "cmp":
                handleBinaryBuiltin("cmp");
                break;

            case ".":
                handleBinaryBuiltin("stringConcat");
                break;

            case "&":
                handleBinaryBuiltin("bitwiseAnd");
                break;
            case "|":
                handleBinaryBuiltin("bitwiseOr");
                break;
            case "^":
                handleBinaryBuiltin("bitwiseXor");
                break;
            case "<<":
                handleBinaryBuiltin("shiftLeft");
                break;
            case ">>":
                handleBinaryBuiltin("shiftRight");
                break;
            default:
                throw new RuntimeException("Unexpected infix operator: " + operator);
        }
    }

    private void handleJoinOperator(String operator, BinaryOperatorNode node) throws Exception {
        node.left.accept(this.with(RuntimeContextType.SCALAR));
        node.right.accept(this.with(RuntimeContextType.LIST));
        // Transform the value in the stack to List
        ctx.mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/RuntimeDataProvider", "getList", "()Lorg/perlonjava/RuntimeList;", true);
        ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/RuntimeScalar", operator, "(Lorg/perlonjava/RuntimeList;)Lorg/perlonjava/RuntimeScalar;", false);
        if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    private void handleAndOperator(BinaryOperatorNode node) throws Exception {
        MethodVisitor mv = ctx.mv;
        Label endLabel = new Label(); // Label for the end of the operation

        // the left parameter is in the stack
        mv.visitInsn(Opcodes.DUP);
        // stack is [left, left]

        // Convert the result to a boolean
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/RuntimeScalar", "getBoolean", "()Z", false);
        // stack is [left, boolean]

        // If the left operand boolean value is false, return left operand
        mv.visitJumpInsn(Opcodes.IFEQ, endLabel);

        mv.visitInsn(Opcodes.POP); // remove left operand
        node.right.accept(this.with(RuntimeContextType.SCALAR)); // right operand in scalar context
        // stack is [right]

        mv.visitLabel(endLabel);
        if (ctx.contextType == RuntimeContextType.VOID) {
            mv.visitInsn(Opcodes.POP);
        }
    }

    private void handleOrOperator(BinaryOperatorNode node) throws Exception {
        MethodVisitor mv = ctx.mv;
        Label endLabel = new Label(); // Label for the end of the operation

        // the left parameter is in the stack
        mv.visitInsn(Opcodes.DUP);
        // stack is [left, left]

        // Convert the result to a boolean
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/RuntimeScalar", "getBoolean", "()Z", false);
        // stack is [left, boolean]

        // If the left operand boolean value is true, return left operand
        mv.visitJumpInsn(Opcodes.IFNE, endLabel);

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
    private void handleArrayElementOperator(BinaryOperatorNode node) throws Exception {
        ctx.logDebug("handleArrayElementOperator " + node + " in context " + ctx.contextType);
        EmitterVisitor scalarVisitor =
                this.with(RuntimeContextType.SCALAR); // execute operands in scalar context

        // check if node.left is a `$` variable - it means we have a RuntimeArray instead of RuntimeScalar

    /*
      BinaryOperatorNode: [
        UnaryOperatorNode: $
          IdentifierNode: a
        ArrayLiteralNode:
          NumberNode: 10
    */

        if (node.left instanceof UnaryOperatorNode) { // $ @ %
            UnaryOperatorNode sigilNode = (UnaryOperatorNode) node.left;
            String sigil = sigilNode.operator;
            if (sigil.equals("$")) {
                if (sigilNode.operand instanceof IdentifierNode) { // $a
                    IdentifierNode identifierNode = (IdentifierNode) sigilNode.operand;
                    // Rewrite the variable node from `$` to `@`
                    UnaryOperatorNode varNode = new UnaryOperatorNode("@", identifierNode, sigilNode.tokenIndex);

                    ctx.logDebug("visit(BinaryOperatorNode) $var[] ");
                    varNode.accept(this.with(RuntimeContextType.LIST)); // target - left parameter

                    // emit the [0] as a RuntimeList
                    ListNode nodeRight = ((ArrayLiteralNode) node.right).asListNode();
                    nodeRight.accept(scalarVisitor);

                    ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/RuntimeArray", "get", "(Lorg/perlonjava/RuntimeScalar;)Lorg/perlonjava/RuntimeScalar;", false);

                    if (ctx.contextType == RuntimeContextType.VOID) {
                        ctx.mv.visitInsn(Opcodes.POP);
                    }
                    return;
                }
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
     */
    private void handleHashElementOperator(BinaryOperatorNode node) throws Exception {
        ctx.logDebug("handleHashElementOperator " + node + " in context " + ctx.contextType);
        EmitterVisitor scalarVisitor =
                this.with(RuntimeContextType.SCALAR); // execute operands in scalar context

        // check if node.left is a `$` variable - it means we have a RuntimeHash instead of RuntimeScalar

    /*
      BinaryOperatorNode: {
        UnaryOperatorNode: $
          IdentifierNode: a
        ArrayLiteralNode:
          NumberNode: 10
    */

        if (node.left instanceof UnaryOperatorNode) { // $ @ %
            UnaryOperatorNode sigilNode = (UnaryOperatorNode) node.left;
            String sigil = sigilNode.operator;
            if (sigil.equals("$")) {
                if (sigilNode.operand instanceof IdentifierNode) { // $a
                    IdentifierNode identifierNode = (IdentifierNode) sigilNode.operand;
                    // Rewrite the variable node from `$` to `%`
                    UnaryOperatorNode varNode = new UnaryOperatorNode("%", identifierNode, sigilNode.tokenIndex);

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

                    ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/RuntimeHash", "get", "(Lorg/perlonjava/RuntimeScalar;)Lorg/perlonjava/RuntimeScalar;", false);

                    if (ctx.contextType == RuntimeContextType.VOID) {
                        ctx.mv.visitInsn(Opcodes.POP);
                    }
                    return;
                }
            }
        }

        // default: call `->{}`
        BinaryOperatorNode refNode = new BinaryOperatorNode("->", node.left, node.right, node.tokenIndex);
        refNode.accept(this);
    }

    /**
     * Handles the postfix `()` node.
     */
    private void handleApplyOperator(BinaryOperatorNode node) throws Exception {
        ctx.logDebug("handleApplyElementOperator " + node + " in context " + ctx.contextType);
        EmitterVisitor scalarVisitor =
                this.with(RuntimeContextType.SCALAR); // execute operands in scalar context

        node.left.accept(scalarVisitor); // target - left parameter: Code ref
        node.right.accept(this.with(RuntimeContextType.LIST)); // right parameter: parameter list

        // Transform the value in the stack to RuntimeArray
        ctx.mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/RuntimeDataProvider", "getArray", "()Lorg/perlonjava/RuntimeArray;", true);
        ctx.mv.visitFieldInsn(
                Opcodes.GETSTATIC,
                "org/perlonjava/RuntimeContextType",
                ctx.contextType.toString(),
                "Lorg/perlonjava/RuntimeContextType;"); // call context
        ctx.mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "org/perlonjava/RuntimeScalar",
                "apply",
                "(Lorg/perlonjava/RuntimeArray;Lorg/perlonjava/RuntimeContextType;)Lorg/perlonjava/RuntimeList;",
                false); // generate an .apply() call
        if (ctx.contextType == RuntimeContextType.SCALAR) {
            // Transform the value in the stack to RuntimeScalar
            ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/RuntimeList", "getScalar", "()Lorg/perlonjava/RuntimeScalar;", false);
        } else if (ctx.contextType == RuntimeContextType.VOID) {
            // Remove the value from the stack
            ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    /**
     * Handles the `->` operator.
     */
    private void handleArrowOperator(BinaryOperatorNode node) throws Exception {
        ctx.logDebug("handleArrowOperator " + node + " in context " + ctx.contextType);
        EmitterVisitor scalarVisitor =
                this.with(RuntimeContextType.SCALAR); // execute operands in scalar context

        if (node.right instanceof ListNode) { // ->()

            BinaryOperatorNode applyNode = new BinaryOperatorNode("(", node.left, node.right, node.tokenIndex);
            applyNode.accept(this);
            return;

        } else if (node.right instanceof ArrayLiteralNode) { // ->[0]
            ctx.logDebug("visit(BinaryOperatorNode) ->[] ");
            node.left.accept(scalarVisitor); // target - left parameter

            // emit the [0] as a RuntimeList
            ListNode nodeRight = ((ArrayLiteralNode) node.right).asListNode();
            nodeRight.accept(this.with(RuntimeContextType.SCALAR));

            ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/RuntimeScalar", "arrayDerefGet", "(Lorg/perlonjava/RuntimeScalar;)Lorg/perlonjava/RuntimeScalar;", false);

        } else if (node.right instanceof HashLiteralNode) { // ->{x}
            ctx.logDebug("visit(BinaryOperatorNode) ->{} ");
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

            ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/RuntimeScalar", "hashDerefGet", "(Lorg/perlonjava/RuntimeScalar;)Lorg/perlonjava/RuntimeScalar;", false);

        } else {
            throw new RuntimeException("Unexpected right operand for `->` operator: " + node.right);
        }
    }

    /**
     * Emits a call to a unary built-in method on the RuntimeScalar class.
     *
     * @param operator The name of the built-in method to call.
     */
    private void handleUnaryBuiltin(UnaryOperatorNode node, String operator) throws Exception {
        if (node.operand == null) {
            // Unary operator with optional arguments, called without arguments
            // example: undef()
            ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/RuntimeScalar", operator, "()Lorg/perlonjava/RuntimeScalar;", false);
        } else {
            node.operand.accept(this.with(RuntimeContextType.SCALAR));
            ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/RuntimeScalar", operator, "()Lorg/perlonjava/RuntimeScalar;", false);
        }
        if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    @Override
    public void visit(UnaryOperatorNode node) throws Exception {
        String operator = node.operator;
        ctx.logDebug("visit(UnaryOperatorNode) " + operator + " in context " + ctx.contextType);

        switch (operator) {
            case "$":
            case "@":
            case "%":
                handleVariableOperator(node, operator);
                break;
            case "keys":
            case "values":
                handleKeysOperator(node, operator);
                break;
            case "print":
            case "say":
                handleSayOperator(node, operator);
                break;
            case "our":
            case "my":
                handleMyOperator(node, operator);
                break;
            case "return":
                handleReturnOperator(node);
                break;
            case "eval":
                handleEvalOperator(node);
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
            case "log":
            case "rand":
            case "undef":
                handleUnaryBuiltin(node, operator);
                break;
            case "++":
                handleUnaryBuiltin(node, "preAutoIncrement");
                break;
            case "--":
                handleUnaryBuiltin(node, "preAutoDecrement");
                break;
            case "\\":
                handleUnaryBuiltin(node, "createReference");
                break;
            default:
                throw new UnsupportedOperationException("Unsupported operator: " + operator);
        }
    }

    private void handleKeysOperator(UnaryOperatorNode node, String operator) throws Exception {
        node.operand.accept(this.with(RuntimeContextType.LIST));
        ctx.mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/RuntimeDataProvider", operator, "()Lorg/perlonjava/RuntimeArray;", true);
        if (ctx.contextType == RuntimeContextType.LIST) {
            ctx.mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/RuntimeDataProvider", "getList", "()Lorg/perlonjava/RuntimeList;", true);
        } else if (ctx.contextType == RuntimeContextType.SCALAR) {
            ctx.mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/RuntimeDataProvider", "getScalar", "()Lorg/perlonjava/RuntimeScalar;", true);
        } else if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    private void handleSayOperator(UnaryOperatorNode node, String operator) throws Exception {
        // TODO print FILE 123
        node.operand.accept(this.with(RuntimeContextType.LIST));
        // Transform the value in the stack to List
        ctx.mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/RuntimeDataProvider", "getList", "()Lorg/perlonjava/RuntimeList;", true);
        // Call the operator, return Scalar
        ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/RuntimeList", operator, "()Lorg/perlonjava/RuntimeScalar;", false);
        if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    private void handleUnaryPlusOperator(UnaryOperatorNode node) throws Exception {
        node.operand.accept(this.with(RuntimeContextType.SCALAR));
        if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    private void fetchGlobalVariable(boolean createIfNotExists, String sigil, String var, int tokenIndex) {
        if (sigil.equals("$") && (createIfNotExists || Namespace.existsGlobalVariable(var))) {
            // fetch a global variable
            ctx.mv.visitLdcInsn(var);
            ctx.mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "org/perlonjava/Namespace",
                    "getGlobalVariable",
                    "(Ljava/lang/String;)Lorg/perlonjava/RuntimeScalar;",
                    false);
        } else if (sigil.equals("@") && (createIfNotExists || Namespace.existsGlobalArray(var))) {
            // fetch a global variable
            ctx.mv.visitLdcInsn(var);
            ctx.mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "org/perlonjava/Namespace",
                    "getGlobalArray",
                    "(Ljava/lang/String;)Lorg/perlonjava/RuntimeArray;",
                    false);
        } else if (sigil.equals("%") && (createIfNotExists || Namespace.existsGlobalHash(var))) {
            // fetch a global variable
            ctx.mv.visitLdcInsn(var);
            ctx.mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "org/perlonjava/Namespace",
                    "getGlobalHash",
                    "(Ljava/lang/String;)Lorg/perlonjava/RuntimeHash;",
                    false);
        } else {
            // variable not found
            System.err.println(
                    ctx.errorUtil.errorMessage(tokenIndex,
                            "Warning: Global symbol \""
                                    + var
                                    + "\" requires explicit package name (did you forget to declare \"my "
                                    + var
                                    + "\"?)"));
        }
    }

    private void handleVariableOperator(UnaryOperatorNode node, String operator) throws Exception {
        if (ctx.contextType == RuntimeContextType.VOID) {
            return;
        }
        String sigil = operator;
        if (node.operand instanceof IdentifierNode) { // $a @a %a
            String var = sigil + ((IdentifierNode) node.operand).name;
            ctx.logDebug("GETVAR " + var);
            int varIndex = ctx.symbolTable.getVariableIndex(var);
            if (varIndex == -1) {
                // not a declared `my` or `our` variable
                // Create and fetch a global variable
                fetchGlobalVariable(false, sigil, var, node.getIndex());
            } else {
                // retrieve the `my` or `our` variable from local vars
                ctx.mv.visitVarInsn(Opcodes.ALOAD, varIndex);
            }
            if (ctx.contextType == RuntimeContextType.SCALAR && !sigil.equals("$")) {
                // scalar context: transform the value in the stack to scalar
                ctx.mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/RuntimeDataProvider", "getScalar", "()Lorg/perlonjava/RuntimeScalar;", true);
            }
            ctx.logDebug("GETVAR end " + varIndex);
            return;
        }
        // TODO special variables $1 $`
        // TODO ${a} ${[ 123 ]}
        throw new PerlCompilerException(node.tokenIndex, "Not implemented: " + operator, ctx.errorUtil);
    }

    private void handleSetOperator(BinaryOperatorNode node) throws Exception {
        ctx.logDebug("SET " + node);
        MethodVisitor mv = ctx.mv;
        // Determine the assign type based on the left side.
        // Inspect the AST and get the L-value context: SCALAR or LIST
        RuntimeContextType lvalueContext = LValueVisitor.getContext(node);
        ctx.logDebug("SET Lvalue context: " + lvalueContext);
        // Execute the right side first: assignment is right-associative
        switch (lvalueContext) {
            case SCALAR:
                ctx.logDebug("SET right side scalar");
                node.right.accept(this.with(RuntimeContextType.SCALAR));   // emit the value
                node.left.accept(this.with(RuntimeContextType.SCALAR));   // emit the variable
                mv.visitInsn(Opcodes.SWAP); // move the target first
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/RuntimeScalar", "set", "(Lorg/perlonjava/RuntimeScalar;)Lorg/perlonjava/RuntimeScalar;", false);
                break;
            case LIST:
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
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/RuntimeDataProvider", "set", "(Lorg/perlonjava/RuntimeList;)Lorg/perlonjava/RuntimeList;", true);
                if (ctx.contextType == RuntimeContextType.SCALAR) {
                    // Transform the value in the stack to Scalar
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/RuntimeList", "getScalar", "()Lorg/perlonjava/RuntimeScalar;", false);
                }
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

    private void handleMyOperator(UnaryOperatorNode node, String operator) throws Exception {
        if (node.operand instanceof ListNode) { // my ($a, $b)  our ($a, $b)
            // process each item of the list; then returns the list
            ListNode listNode = (ListNode) node.operand;
            for (Node element : listNode.elements) {
                if (element instanceof UnaryOperatorNode && "undef".equals(((UnaryOperatorNode) element).operator)) {
                    continue; // skip "undef"
                }
                UnaryOperatorNode myNode = new UnaryOperatorNode(operator, element, listNode.tokenIndex);
                myNode.accept(this.with(RuntimeContextType.VOID));
            }
            if (ctx.contextType != RuntimeContextType.VOID) {
                listNode.accept(this);
            }
            return;
        } else if (node.operand instanceof UnaryOperatorNode) { //  [my our] followed by [$ @ %]
            Node sigilNode = node.operand;
            String sigil = ((UnaryOperatorNode) sigilNode).operator;
            if (Parser.isSigil(sigil)) {
                Node identifierNode = ((UnaryOperatorNode) sigilNode).operand;
                if (identifierNode instanceof IdentifierNode) { // my $a
                    String var = sigil + ((IdentifierNode) identifierNode).name;
                    ctx.logDebug("MY " + operator + " " + var);
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
                        fetchGlobalVariable(true, sigil, var, node.getIndex());
                    }
                    if (ctx.contextType != RuntimeContextType.VOID) {
                        ctx.mv.visitInsn(Opcodes.DUP);
                    }
                    // Store in a JVM local variable
                    ctx.mv.visitVarInsn(Opcodes.ASTORE, varIndex);
                    if (ctx.contextType == RuntimeContextType.SCALAR && !sigil.equals("$")) {
                        // scalar context: transform the value in the stack to scalar
                        ctx.mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/RuntimeDataProvider", "getScalar", "()Lorg/perlonjava/RuntimeScalar;", true);
                    }
                    return;
                }
            }
        }
        throw new PerlCompilerException(
                node.tokenIndex, "Not implemented: " + node.operator, ctx.errorUtil);
    }

    private void handleReturnOperator(UnaryOperatorNode node) throws Exception {
        node.operand.accept(this.with(RuntimeContextType.RUNTIME));
        ctx.mv.visitJumpInsn(Opcodes.GOTO, ctx.returnLabel);
        // TODO return (1,2), 3
    }

    private void handleEvalOperator(UnaryOperatorNode node) throws Exception {
        // eval string

        // TODO - this can be cached and reused at runtime for performance
        // retrieve the closure variable list into "newEnv" array
        // we save all variables, because we don't yet what code we are going to compile.
        Map<Integer, String> visibleVariables = ctx.symbolTable.getAllVisibleVariables();
        String[] newEnv = new String[visibleVariables.size()];
        ctx.logDebug("(eval) ctx.symbolTable.getAllVisibleVariables");
        for (Integer index : visibleVariables.keySet()) {
            String variableName = visibleVariables.get(index);
            ctx.logDebug("  " + index + " " + variableName);
            newEnv[index] = variableName;
        }

        // save the eval context in a HashMap in RuntimeScalar class
        String evalTag = "eval" + EmitterMethodCreator.classCounter++;
        // create the eval context
        EmitterContext evalCtx =
                new EmitterContext(
                        "(eval)", // filename
                        null, // internal java class name will be created at runtime
                        ctx.symbolTable.clone(), // clone the symbolTable
                        null, // return label
                        null, // method visitor
                        ctx.contextType, // call context
                        true, // is boxed
                        ctx.errorUtil, // error message utility
                        ctx.debugEnabled,
                        ctx.tokenizeOnly,
                        ctx.compileOnly,
                        ctx.parseOnly);
        RuntimeCode.evalContext.put(evalTag, evalCtx);

        // Here the compiled code will call RuntimeCode.eval_string(code, evalTag) method.
        // It will compile the string and return a new Class.
        //
        // XXX TODO - We need to catch any errors and set Perl error variable "$@"
        //
        // The generated method closure variables are going to be initialized in the next step.
        // Then we can call the method.

        // Retrieve the eval argument and push to the stack
        // This is the code string that we will compile into a class.
        // The string is evaluated outside of the try-catch block.
        node.operand.accept(this.with(RuntimeContextType.SCALAR));

        int skipVariables = EmitterMethodCreator.skipVariables; // skip (this, @_, wantarray)

        MethodVisitor mv = ctx.mv;

        // Stack at this step: [RuntimeScalar(String)]

        // 1. Call RuntimeCode.eval_string(code, evalTag)

        // Push the evalTag String to the stack
        // the compiled code will use this tag to retrieve the compiler environment
        mv.visitLdcInsn(evalTag);
        // Stack: [RuntimeScalar(String), String]

        mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "org/perlonjava/RuntimeCode",
                "eval_string",
                "(Lorg/perlonjava/RuntimeScalar;Ljava/lang/String;)Ljava/lang/Class;",
                false);

        // Stack after this step: [Class]

        // 2. Find the constructor (RuntimeScalar, RuntimeScalar, ...)
        mv.visitIntInsn(
                Opcodes.BIPUSH, newEnv.length - skipVariables); // Push the length of the array
        // Stack: [Class, int]
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Class"); // Create a new array of Class
        // Stack: [Class, Class[]]

        for (int i = 0; i < newEnv.length - skipVariables; i++) {
            mv.visitInsn(Opcodes.DUP); // Duplicate the array reference
            // Stack: [Class, Class[], Class[]]

            mv.visitIntInsn(Opcodes.BIPUSH, i); // Push the index
            // Stack: [Class, Class[], Class[], int]

            // select Array/Hash/Scalar depending on env value
            String descriptor = EmitterMethodCreator.getVariableDescriptor(newEnv[i + skipVariables]);

            mv.visitLdcInsn(Type.getType(descriptor)); // Push the Class object for RuntimeScalar
            // Stack: [Class, Class[], Class[], int, Class]

            mv.visitInsn(Opcodes.AASTORE); // Store the Class object in the array
            // Stack: [Class, Class[]]
        }
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/Class",
                "getConstructor",
                "([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;",
                false);
        // Stack: [Constructor]

        // 3. Instantiate the class
        mv.visitIntInsn(
                Opcodes.BIPUSH, newEnv.length - skipVariables); // Push the length of the array
        // Stack: [Constructor, int]
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object"); // Create a new array of Object
        // Stack: [Constructor, Object[]]
        for (int i = skipVariables; i < newEnv.length; i++) {
            mv.visitInsn(Opcodes.DUP); // Duplicate the array reference
            // Stack: [Constructor, Object[], Object[]]
            mv.visitIntInsn(Opcodes.BIPUSH, i - skipVariables); // Push the index
            // Stack: [Constructor, Object[], Object[], int]
            mv.visitVarInsn(Opcodes.ALOAD, i); // Load the constructor argument
            // Stack: [Constructor, Object[], Object[], int, arg]
            mv.visitInsn(Opcodes.AASTORE); // Store the argument in the array
            // Stack: [Constructor, Object[]]
        }
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/reflect/Constructor",
                "newInstance",
                "([Ljava/lang/Object;)Ljava/lang/Object;",
                false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Object");

        // Stack after this step: [initialized class Instance]

        // 4. Create a CODE variable using RuntimeScalar.make_sub
        mv.visitMethodInsn(
                Opcodes.INVOKESTATIC, "org/perlonjava/RuntimeScalar", "make_sub", "(Ljava/lang/Object;)Lorg/perlonjava/RuntimeScalar;", false);
        // Stack: [RuntimeScalar(Code)]

        mv.visitVarInsn(Opcodes.ALOAD, 1); // push @_ to the stack
        // Transform the value in the stack to RuntimeArray
        // XXX not needed
        // mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/RuntimeDataProvider", "getArray", "()Lorg/perlonjava/RuntimeArray;", true);

        mv.visitFieldInsn(
                Opcodes.GETSTATIC,
                "org/perlonjava/RuntimeContextType",
                ctx.contextType.toString(),
                "Lorg/perlonjava/RuntimeContextType;"); // call context
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "org/perlonjava/RuntimeScalar",
                "apply",
                "(Lorg/perlonjava/RuntimeArray;Lorg/perlonjava/RuntimeContextType;)Lorg/perlonjava/RuntimeList;",
                false); // generate an .apply() call

        // 5. Clean up the stack according to context
        if (ctx.contextType == RuntimeContextType.SCALAR) {
            // Transform the value in the stack to RuntimeScalar
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/RuntimeList", "getScalar", "()Lorg/perlonjava/RuntimeScalar;", false);
        } else if (ctx.contextType == RuntimeContextType.VOID) {
            // Remove the value from the stack
            mv.visitInsn(Opcodes.POP);
        }

        // If the context is LIST or RUNTIME, the stack should contain [RuntimeList]
        // If the context is SCALAR, the stack should contain [RuntimeScalar]
        // If the context is VOID, the stack should be empty

    }

    @Override
    public void visit(AnonSubNode node) throws Exception {
        ctx.logDebug("SUB start");
        if (ctx.contextType == RuntimeContextType.VOID) {
            return;
        }
        MethodVisitor mv = ctx.mv;

        // XXX TODO - if the sub has an empty block, we return an empty list
        // XXX TODO - when calling a sub with no arguments, we use an empty list argument

        // retrieve closure variable list
        // alternately, scan the AST for variables and capture only the ones that are used
        Map<Integer, String> visibleVariables = ctx.symbolTable.getAllVisibleVariables();
        String[] newEnv = new String[visibleVariables.size()];
        ctx.logDebug(" ctx.symbolTable.getAllVisibleVariables");
        for (Integer index : visibleVariables.keySet()) {
            String variableName = visibleVariables.get(index);
            ctx.logDebug("  " + index + " " + variableName);
            newEnv[index] = variableName;
        }

        // create the new method
        EmitterContext subCtx =
                new EmitterContext(
                        ctx.fileName, // same source filename
                        EmitterMethodCreator.generateClassName(), // internal java class name
                        ctx.symbolTable, // closure symbolTable
                        null, // return label
                        null, // method visitor
                        RuntimeContextType.RUNTIME, // call context
                        true, // is boxed
                        ctx.errorUtil, // error message utility
                        ctx.debugEnabled,
                        ctx.tokenizeOnly,
                        ctx.compileOnly,
                        ctx.parseOnly);
        Class<?> generatedClass =
                EmitterMethodCreator.createClassWithMethod(
                        subCtx, newEnv, node.block, node.useTryCatch
                );
        String newClassNameDot = subCtx.javaClassName.replace('/', '.');
        ctx.logDebug("Generated class name: " + newClassNameDot + " internal " + subCtx.javaClassName);
        ctx.logDebug("Generated class env:  " + Arrays.toString(newEnv));
        RuntimeCode.anonSubs.put(subCtx.javaClassName, generatedClass); // cache the class

        /* The following ASM code is equivalent to:
         *  // get the class
         *  Class<?> generatedClass = RuntimeCode.anonSubs.get("java.Class.Name");
         *  // Find the constructor
         *  Constructor<?> constructor = generatedClass.getConstructor(RuntimeScalar.class, RuntimeScalar.class);
         *  // Instantiate the class
         *  Object instance = constructor.newInstance();
         *  // Find the apply method
         *  Method applyMethod = generatedClass.getMethod("apply", RuntimeArray.class, RuntimeContextType.class);
         *  // construct a CODE variable
         *  RuntimeScalar.new(applyMethod);
         */

        int skipVariables = EmitterMethodCreator.skipVariables; // skip (this, @_, wantarray)

        // 1. Get the class from RuntimeCode.anonSubs
        mv.visitFieldInsn(Opcodes.GETSTATIC, "org/perlonjava/RuntimeCode", "anonSubs", "Ljava/util/HashMap;");
        mv.visitLdcInsn(subCtx.javaClassName);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/util/HashMap",
                "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Class");

        // Stack after this step: [Class]

        // 2. Find the constructor (RuntimeScalar, RuntimeScalar, ...)
        mv.visitInsn(Opcodes.DUP);
        mv.visitIntInsn(
                Opcodes.BIPUSH, newEnv.length - skipVariables); // Push the length of the array
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Class"); // Create a new array of Class
        for (int i = 0; i < newEnv.length - skipVariables; i++) {
            mv.visitInsn(Opcodes.DUP); // Duplicate the array reference
            mv.visitIntInsn(Opcodes.BIPUSH, i); // Push the index

            // select Array/Hash/Scalar depending on env value
            String descriptor = EmitterMethodCreator.getVariableDescriptor(newEnv[i + skipVariables]);

            mv.visitLdcInsn(Type.getType(descriptor)); // Push the Class object for RuntimeScalar
            mv.visitInsn(Opcodes.AASTORE); // Store the Class object in the array
        }
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/Class",
                "getConstructor",
                "([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;",
                false);

        // Stack after this step: [Class, Constructor]

        // 3. Instantiate the class
        mv.visitIntInsn(
                Opcodes.BIPUSH, newEnv.length - skipVariables); // Push the length of the array
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object"); // Create a new array of Object
        for (int i = skipVariables; i < newEnv.length; i++) {
            mv.visitInsn(Opcodes.DUP); // Duplicate the array reference
            mv.visitIntInsn(Opcodes.BIPUSH, i - skipVariables); // Push the index
            mv.visitVarInsn(Opcodes.ALOAD, i); // Load the constructor argument
            mv.visitInsn(Opcodes.AASTORE); // Store the argument in the array
        }
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/reflect/Constructor",
                "newInstance",
                "([Ljava/lang/Object;)Ljava/lang/Object;",
                false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Object");

        // Stack after this step: [Class, Constructor, Object]

        // 4. Create a CODE variable using RuntimeScalar.make_sub
        mv.visitMethodInsn(
                Opcodes.INVOKESTATIC, "org/perlonjava/RuntimeScalar", "make_sub", "(Ljava/lang/Object;)Lorg/perlonjava/RuntimeScalar;", false);

        // Stack after this step: [Class, Constructor, RuntimeScalar]
        mv.visitInsn(Opcodes.SWAP); // move the RuntimeScalar object up
        mv.visitInsn(Opcodes.POP); // Remove the Constructor

        // 5. Clean up the stack if context is VOID
        if (ctx.contextType == RuntimeContextType.VOID) {
            mv.visitInsn(Opcodes.POP); // Remove the RuntimeScalar object from the stack
        }

        // If the context is not VOID, the stack should contain [RuntimeScalar] (the CODE variable)
        // If the context is VOID, the stack should be empty

        ctx.logDebug("SUB end");
    }

    @Override
    public void visit(For1Node node) throws Exception {
        ctx.logDebug("FOR1 start");

        // Enter a new scope in the symbol table
        if (node.useNewScope) {
            ctx.symbolTable.enterScope();
        }

        MethodVisitor mv = ctx.mv;

        // For1Node fields:
        //  variable
        //  list
        //  body

        // Create labels for the loop
        Label loopStart = new Label();
        Label loopEnd = new Label();

        // Emit the list and create an Iterator<Runtime>
        node.list.accept(this.with(RuntimeContextType.LIST));
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/RuntimeDataProvider", "iterator", "()Ljava/util/Iterator;", true);

        // Start of the loop
        mv.visitLabel(loopStart);

        // Check if the iterator has more elements
        mv.visitInsn(Opcodes.DUP); // Duplicate the iterator on the stack to use it for hasNext and next
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
        mv.visitJumpInsn(Opcodes.IFEQ, loopEnd);

        // Retrieve the next element from the iterator
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "org/perlonjava/RuntimeScalar"); // Cast the object to the appropriate type

        // Assign it to the loop variable
        node.variable.accept(this.with(RuntimeContextType.SCALAR));
        mv.visitInsn(Opcodes.SWAP); // move the target first
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/RuntimeScalar", "set", "(Lorg/perlonjava/RuntimeScalar;)Lorg/perlonjava/RuntimeScalar;", false);
        mv.visitInsn(Opcodes.POP);  // we don't need the variable in the stack

        // Visit the body of the loop
        node.body.accept(this.with(RuntimeContextType.VOID));

        // Jump back to the start of the loop
        mv.visitJumpInsn(Opcodes.GOTO, loopStart);

        // End of the loop
        mv.visitLabel(loopEnd);

        // Pop the iterator from the stack
        mv.visitInsn(Opcodes.POP);

        // If the context is not VOID, push "undef" to the stack
        if (ctx.contextType != RuntimeContextType.VOID) {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/RuntimeScalar", "undef", "()Lorg/perlonjava/RuntimeScalar;", false);
        }

        // Exit the scope in the symbol table
        if (node.useNewScope) {
            ctx.symbolTable.exitScope();
        }

        ctx.logDebug("FOR1 end");
    }


    @Override
    public void visit(For3Node node) throws Exception {
        ctx.logDebug("FOR3 start");
        MethodVisitor mv = ctx.mv;

        EmitterVisitor voidVisitor = this.with(RuntimeContextType.VOID); // some parts have context VOID

        // Enter a new scope in the symbol table
        if (node.useNewScope) {
            ctx.symbolTable.enterScope();
        }

        // Create labels for the start of the loop, the condition check, and the end of the loop
        Label startLabel = new Label();
        Label conditionLabel = new Label();
        Label endLabel = new Label();

        // Visit the initialization node
        if (node.initialization != null) {
            node.initialization.accept(voidVisitor);
        }

        // Jump to the condition check
        mv.visitJumpInsn(Opcodes.GOTO, conditionLabel);

        // Visit the start label
        mv.visitLabel(startLabel);

        // Visit the loop body
        node.body.accept(voidVisitor);

        // Visit the increment node
        if (node.increment != null) {
            node.increment.accept(voidVisitor);
        }

        // Visit the condition label
        mv.visitLabel(conditionLabel);

        // Visit the condition node in scalar context
        if (node.condition != null) {
            node.condition.accept(this.with(RuntimeContextType.SCALAR));

            // Convert the result to a boolean
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/RuntimeScalar", "getBoolean", "()Z", false);

            // Jump to the end label if the condition is false
            mv.visitJumpInsn(Opcodes.IFEQ, endLabel);
        }

        // Jump to the start label to continue the loop
        mv.visitJumpInsn(Opcodes.GOTO, startLabel);

        // Visit the end label
        mv.visitLabel(endLabel);

        // Exit the scope in the symbol table
        if (node.useNewScope) {
            ctx.symbolTable.exitScope();
        }

        // If the context is not VOID, push "undef" to the stack
        if (ctx.contextType != RuntimeContextType.VOID) {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/RuntimeScalar", "undef", "()Lorg/perlonjava/RuntimeScalar;", false);
        }

        ctx.logDebug("FOR end");
    }

    @Override
    public void visit(IfNode node) throws Exception {
        ctx.logDebug("IF start: " + node.operator);

        // Enter a new scope in the symbol table
        ctx.symbolTable.enterScope();

        // Create labels for the else and end branches
        Label elseLabel = new Label();
        Label endLabel = new Label();

        // Visit the condition node in scalar context
        node.condition.accept(this.with(RuntimeContextType.SCALAR));

        // Convert the result to a boolean
        ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/RuntimeScalar", "getBoolean", "()Z", false);

        // Jump to the else label if the condition is false
        ctx.mv.visitJumpInsn(node.operator.equals("unless") ? Opcodes.IFNE : Opcodes.IFEQ, elseLabel);

        // Visit the then branch
        node.thenBranch.accept(this);

        // Jump to the end label after executing the then branch
        ctx.mv.visitJumpInsn(Opcodes.GOTO, endLabel);

        // Visit the else label
        ctx.mv.visitLabel(elseLabel);

        // Visit the else branch if it exists
        if (node.elseBranch != null) {
            node.elseBranch.accept(this);
        } else {
            // If the context is not VOID, push "undef" to the stack
            if (ctx.contextType != RuntimeContextType.VOID) {
                ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/RuntimeScalar", "undef", "()Lorg/perlonjava/RuntimeScalar;", false);
            }
        }

        // Visit the end label
        ctx.mv.visitLabel(endLabel);

        // Exit the scope in the symbol table
        ctx.symbolTable.exitScope();

        ctx.logDebug("IF end");
    }

    @Override
    public void visit(TernaryOperatorNode node) throws Exception {
        ctx.logDebug("TERNARY_OP start");

        // Create labels for the else and end branches
        Label elseLabel = new Label();
        Label endLabel = new Label();

        // Visit the condition node in scalar context
        node.condition.accept(this.with(RuntimeContextType.SCALAR));

        // Convert the result to a boolean
        ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/RuntimeScalar", "getBoolean", "()Z", false);

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
    public void visit(PostfixOperatorNode node) throws Exception {
        node.operand.accept(this);
        // Emit code for postfix operator
        String operator = node.operator;
        ctx.logDebug("visit(PostfixOperatorNode) " + operator + " in context " + ctx.contextType);
        switch (operator) {
            case "++":
            case "--":
                String runtimeMethod = (operator.equals("++") ? "postAutoIncrement" : "postAutoDecrement");
                node.operand.accept(this.with(RuntimeContextType.SCALAR));
                ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/RuntimeScalar", runtimeMethod, "()Lorg/perlonjava/RuntimeScalar;", false);
                if (ctx.contextType == RuntimeContextType.VOID) {
                    ctx.mv.visitInsn(Opcodes.POP);
                }
                return;
        }
        throw new PerlCompilerException(
                node.tokenIndex, "Not implemented: postfix operator " + node.operator, ctx.errorUtil);
    }

    @Override
    public void visit(BlockNode node) throws Exception {
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
    public void visit(ListNode node) throws Exception {
        ctx.logDebug("visit(ListNode) in context " + ctx.contextType);
        MethodVisitor mv = ctx.mv;

        // Create a new instance of RuntimeList
        mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/RuntimeList");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/perlonjava/RuntimeList", "<init>", "()V", false);

        // The stack now has the new RuntimeList instance

        for (Node element : node.elements) {
            // Visit each element to generate code for it

            // Duplicate the RuntimeList instance to keep it on the stack
            mv.visitInsn(Opcodes.DUP);

            // emit the list element
            element.accept(this);

            // Call the add method to add the element to the RuntimeList
            // This calls RuntimeDataProvider.addToList() in order to allow (1, 2, $x, @x, %x)
            mv.visitInsn(Opcodes.SWAP);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/RuntimeDataProvider", "addToList", "(Lorg/perlonjava/RuntimeList;)V", true);

            // The stack now has the RuntimeList instance again
        }

        // At this point, the stack has the fully populated RuntimeList instance
        if (ctx.contextType == RuntimeContextType.SCALAR) {
            // Transform the value in the stack to RuntimeScalar
            ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/RuntimeList", "getScalar", "()Lorg/perlonjava/RuntimeScalar;", false);
        } else if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
        ctx.logDebug("visit(ListNode) end");
    }

    @Override
    public void visit(StringNode node) throws Exception {
        if (ctx.contextType == RuntimeContextType.VOID) {
            return;
        }
        MethodVisitor mv = ctx.mv;
        if (ctx.isBoxed) { // expect a RuntimeScalar object
            mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/RuntimeScalar");
            mv.visitInsn(Opcodes.DUP);
            mv.visitLdcInsn(node.value); // emit string
            mv.visitMethodInsn(
                    Opcodes.INVOKESPECIAL,
                    "org/perlonjava/RuntimeScalar",
                    "<init>",
                    "(Ljava/lang/String;)V",
                    false); // Call new RuntimeScalar(String)
        } else {
            mv.visitLdcInsn(node.value); // emit string
        }
    }

    @Override
    public void visit(HashLiteralNode node) throws Exception {
        ctx.logDebug("visit(HashLiteralNode) in context " + ctx.contextType);
        MethodVisitor mv = ctx.mv;

        // Create a new instance of RuntimeHash
        mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/RuntimeHash");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/perlonjava/RuntimeHash", "<init>", "()V", false);

        // Create a RuntimeList
        ListNode listNode = new ListNode(node.elements, node.tokenIndex);
        listNode.accept(this.with(RuntimeContextType.LIST));

        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/RuntimeHash", "createHashRef", "(Lorg/perlonjava/RuntimeList;)Lorg/perlonjava/RuntimeScalar;", false);

        if (ctx.contextType == RuntimeContextType.VOID) {
            mv.visitInsn(Opcodes.POP);
        }
        ctx.logDebug("visit(HashLiteralNode) end");
    }

    @Override
    public void visit(ArrayLiteralNode node) throws Exception {
        ctx.logDebug("visit(ArrayLiteralNode) in context " + ctx.contextType);
        MethodVisitor mv = ctx.mv;

        // Create a new instance of RuntimeArray
        mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/RuntimeArray");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/perlonjava/RuntimeArray", "<init>", "()V", false);

        // The stack now has the new RuntimeArray instance

        for (Node element : node.elements) {
            // Visit each element to generate code for it

            // Duplicate the RuntimeArray instance to keep it on the stack
            mv.visitInsn(Opcodes.DUP);

            // emit the list element
            element.accept(this.with(RuntimeContextType.LIST));

            // Call the add method to add the element to the RuntimeArray
            // This calls RuntimeDataProvider.addToArray() in order to allow [ 1, 2, $x, @x, %x ]
            mv.visitInsn(Opcodes.SWAP);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/RuntimeDataProvider", "addToArray", "(Lorg/perlonjava/RuntimeArray;)V", true);

            // The stack now has the RuntimeArray instance again
        }
        ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/RuntimeArray", "createReference", "()Lorg/perlonjava/RuntimeScalar;", false);

        if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
        ctx.logDebug("visit(ArrayLiteralNode) end");
    }

    // Add other visit methods as needed
}
