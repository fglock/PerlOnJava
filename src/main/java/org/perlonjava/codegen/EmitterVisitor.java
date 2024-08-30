package org.perlonjava.codegen;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.perlonjava.ArgumentParser;
import org.perlonjava.astnode.*;
import org.perlonjava.parser.Parser;
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
        operatorHandlers.put("=~", "bindMatch");
        operatorHandlers.put("!~", "bindNotMatch");
        operatorHandlers.put("bless", "bless");
    }

    private final EmitterContext ctx;
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
                mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/RuntimeScalar");
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn(
                        Integer.valueOf(value)); // Push the integer argument onto the stack
                mv.visitMethodInsn(
                        Opcodes.INVOKESPECIAL, "org/perlonjava/runtime/RuntimeScalar", "<init>", "(I)V", false); // Call new RuntimeScalar(int)
            } else {
                mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/RuntimeScalar");
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn(Double.valueOf(value)); // Push the double argument onto the stack
                mv.visitMethodInsn(
                        Opcodes.INVOKESPECIAL, "org/perlonjava/runtime/RuntimeScalar", "<init>", "(D)V", false); // Call new RuntimeScalar(double)
            }
            // if (ctx.contextType == RuntimeContextType.LIST) {
            //   // Transform the value in the stack to List
            //   mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", "getList", "()Lorg/perlonjava/runtime/RuntimeList;", false);
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
            case "push":
            case "unshift":
                handlePushOperator(operator, node);
                return;
            case "map":
            case "sort":
            case "grep":
                handleMapOperator(operator, node);
                return;
            case "join":
                handleJoinOperator(operator, node);
                return;
            case "**=":
            case "+=":
            case "*=":
            case "&=":
            case "&.=":
            case "<<=":
            case "&&=":
            case "-=":
            case "/=":
            case "|=":
            case "|.=":
            case ">>=":
            case "||=":
            case ".=":
            case "%=":
            case "^=":
            case "^.=":
            case "//=":
            case "x=":
                String newOp = operator.substring(0, operator.length() - 1);
                String methodStr = operatorHandlers.get(newOp);
                if (methodStr != null) {
                    // compound assignment operators like `+=`
                    // XXX TODO - special lazy evaluation case for: `&&=` `||=`
                    node.left.accept(scalarVisitor); // target - left parameter
                    ctx.mv.visitInsn(Opcodes.DUP);
                    node.right.accept(scalarVisitor); // right parameter
                    // stack: [left, left, right]
                    // perform the operation
                    ctx.mv.visitMethodInsn(
                            Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", methodStr, "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
                    // assign to the Lvalue
                    ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", "set", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
                    return;
                }
        }

        String methodStr = operatorHandlers.get(operator);
        if (methodStr != null) {
            node.left.accept(scalarVisitor); // target - left parameter
            node.right.accept(scalarVisitor); // right parameter
            // stack: [left, right]
            // perform the operation
            ctx.mv.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", methodStr, "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
            if (ctx.contextType == RuntimeContextType.VOID) {
                ctx.mv.visitInsn(Opcodes.POP);
            }
            return;
        }
        throw new RuntimeException("Unexpected infix operator: " + operator);
    }

    private void handlePushOperator(String operator, BinaryOperatorNode node) throws Exception {
        node.left.accept(this.with(RuntimeContextType.LIST));
        node.right.accept(this.with(RuntimeContextType.LIST));
        // Transform the value in the stack to List
        ctx.mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "getList", "()Lorg/perlonjava/runtime/RuntimeList;", true);
        ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeArray", operator, "(Lorg/perlonjava/runtime/RuntimeDataProvider;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    private void handleMapOperator(String operator, BinaryOperatorNode node) throws Exception {
        node.right.accept(this.with(RuntimeContextType.LIST));  // list
        // Transform the value in the stack to List
        ctx.mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "getList", "()Lorg/perlonjava/runtime/RuntimeList;", true);
        node.left.accept(this.with(RuntimeContextType.SCALAR)); // subroutine
        ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeList", operator, "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeList;", false);
        if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    private void handleJoinOperator(String operator, BinaryOperatorNode node) throws Exception {
        node.left.accept(this.with(RuntimeContextType.SCALAR));
        node.right.accept(this.with(RuntimeContextType.LIST));
        // Transform the value in the stack to List
        ctx.mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "getList", "()Lorg/perlonjava/runtime/RuntimeList;", true);
        ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", operator, "(Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
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
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", "getBoolean", "()Z", false);
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
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", "getBoolean", "()Z", false);
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
        OperatorNode: $
          IdentifierNode: a
        ArrayLiteralNode:
          NumberNode: 10
    */

        if (node.left instanceof OperatorNode) { // $ @ %
            OperatorNode sigilNode = (OperatorNode) node.left;
            String sigil = sigilNode.operator;
            if (sigil.equals("$")) {
                if (sigilNode.operand instanceof IdentifierNode) { // $a
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
        OperatorNode: $
          IdentifierNode: a
        ArrayLiteralNode:
          NumberNode: 10
    */

        if (node.left instanceof OperatorNode) { // $ @ %
            OperatorNode sigilNode = (OperatorNode) node.left;
            String sigil = sigilNode.operator;
            if (sigil.equals("$")) {
                if (sigilNode.operand instanceof IdentifierNode) { // $a
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

                    ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeHash", "get", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);

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
        ctx.mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "getArrayOfAlias", "()Lorg/perlonjava/runtime/RuntimeArray;", true);
        pushCallContext();   // push call context to stack
        ctx.mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "org/perlonjava/runtime/RuntimeScalar",
                "apply",
                "(Lorg/perlonjava/runtime/RuntimeArray;I)Lorg/perlonjava/runtime/RuntimeList;",
                false); // generate an .apply() call
        if (ctx.contextType == RuntimeContextType.SCALAR) {
            // Transform the value in the stack to RuntimeScalar
            ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeList", "scalar", "()Lorg/perlonjava/runtime/RuntimeScalar;", false);
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

        } else if (node.right instanceof ArrayLiteralNode) { // ->[0]
            ctx.logDebug("visit(BinaryOperatorNode) ->[] ");
            node.left.accept(scalarVisitor); // target - left parameter

            // emit the [0] as a RuntimeList
            ListNode nodeRight = ((ArrayLiteralNode) node.right).asListNode();
            nodeRight.accept(this.with(RuntimeContextType.SCALAR));

            ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", "arrayDerefGet", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);

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

            ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", "hashDerefGet", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);

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

    /**
     * Emits a call to a unary built-in method on the RuntimeScalar class.
     *
     * @param operator The name of the built-in method to call.
     */
    private void handleUnaryBuiltin(OperatorNode node, String operator) throws Exception {
        MethodVisitor mv = ctx.mv;
        if (node.operand == null) {
            // Unary operator with optional arguments, called without arguments
            // example: undef()  wantarray()
            if (operator.equals("wantarray")) {
                // Retrieve wantarray value from JVM local vars
                mv.visitVarInsn(Opcodes.ILOAD, ctx.symbolTable.getVariableIndex("wantarray"));
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/RuntimeScalar", operator, "(I)Lorg/perlonjava/runtime/RuntimeScalar;", false);
            } else {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/RuntimeScalar", operator, "()Lorg/perlonjava/runtime/RuntimeScalar;", false);
            }
        } else if (operator.equals("undef")) {
            operator = "undefine";
            node.operand.accept(this.with(RuntimeContextType.RUNTIME));
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeList", operator, "()Lorg/perlonjava/runtime/RuntimeList;", false);
        } else {
            node.operand.accept(this.with(RuntimeContextType.SCALAR));
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", operator, "()Lorg/perlonjava/runtime/RuntimeScalar;", false);
        }
        if (ctx.contextType == RuntimeContextType.VOID) {
            mv.visitInsn(Opcodes.POP);
        }
    }

    private void handleArrayUnaryBuiltin(OperatorNode node, String operator) throws Exception {
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

    private void handleSpliceBuiltin(OperatorNode node, String operator) throws Exception {
        // Handle:  splice @array, LIST
        ctx.logDebug("handleSpliceBuiltin " + node);
        Node args = node.operand;
        Node operand = ((ListNode) args).elements.remove(0);
        operand.accept(this.with(RuntimeContextType.LIST));
        args.accept(this.with(RuntimeContextType.LIST));
        ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeArray", operator, "(Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeList;", false);
        if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    @Override
    public void visit(OperatorNode node) throws Exception {
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
            case "defined":
            case "length":
            case "log":
            case "quotemeta":
            case "rand":
            case "ref":
            case "scalar":
            case "undef":
            case "wantarray":
                handleUnaryBuiltin(node, operator);
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
                handleUnaryBuiltin(node, "createReference");
                break;
            case "$#":
                node = new OperatorNode("$#", new OperatorNode("@", node.operand, node.tokenIndex), node.tokenIndex);
                handleArrayUnaryBuiltin(node, "indexLastElem");
                break;
            case "splice":
                handleSpliceBuiltin(node, operator);
                break;
            case "pop":
            case "shift":
                handleArrayUnaryBuiltin(node, operator);
                break;
            default:
                throw new UnsupportedOperationException("Unsupported operator: " + operator);
        }
    }

    private void handlePackageOperator(OperatorNode node) throws Exception {
        String name = ((IdentifierNode) node.operand).name;
        ctx.symbolTable.setCurrentPackage(name);
        if (ctx.contextType != RuntimeContextType.VOID) {
            // if context is not void, return an empty list
            ListNode listNode = new ListNode(node.tokenIndex);
            listNode.accept(this);
        }
    }

    private void handleKeysOperator(OperatorNode node, String operator) throws Exception {
        node.operand.accept(this.with(RuntimeContextType.LIST));
        ctx.mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", operator, "()Lorg/perlonjava/runtime/RuntimeArray;", true);
        if (ctx.contextType == RuntimeContextType.LIST) {
            ctx.mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "getList", "()Lorg/perlonjava/runtime/RuntimeList;", true);
        } else if (ctx.contextType == RuntimeContextType.SCALAR) {
            ctx.mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "scalar", "()Lorg/perlonjava/runtime/RuntimeScalar;", true);
        } else if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    private void handleSayOperator(OperatorNode node, String operator) throws Exception {
        // TODO print FILE 123
        node.operand.accept(this.with(RuntimeContextType.LIST));
        // Transform the value in the stack to List
        ctx.mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "getList", "()Lorg/perlonjava/runtime/RuntimeList;", true);
        // Call the operator, return Scalar
        ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeList", operator, "()Lorg/perlonjava/runtime/RuntimeScalar;", false);
        if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    private void handleUnaryPlusOperator(OperatorNode node) throws Exception {
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

    private void handleVariableOperator(OperatorNode node, String operator) throws Exception {
        if (ctx.contextType == RuntimeContextType.VOID) {
            return;
        }
        MethodVisitor mv = ctx.mv;
        String sigil = operator;
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
        switch (operator) {
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
        throw new PerlCompilerException(node.tokenIndex, "Not implemented: " + operator, ctx.errorUtil);
    }

    private void handleSetOperator(BinaryOperatorNode node) throws Exception {
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

    private void handleMyOperator(OperatorNode node, String operator) throws Exception {
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
            Node sigilNode = node.operand;
            String sigil = ((OperatorNode) sigilNode).operator;
            if (Parser.isSigil(sigil)) {
                Node identifierNode = ((OperatorNode) sigilNode).operand;
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

    private void handleReturnOperator(OperatorNode node) throws Exception {
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

    private void handleEvalOperator(OperatorNode node) throws Exception {
        // eval string

        // TODO - this can be cached and reused at runtime for performance
        // retrieve the closure variable list into "newEnv" array
        // we save all variables, because we don't yet what code we are going to compile.
        Map<Integer, String> visibleVariables = ctx.symbolTable.getAllVisibleVariables();
        ctx.logDebug("(eval) ctx.symbolTable.getAllVisibleVariables");

        ScopedSymbolTable newSymbolTable = new ScopedSymbolTable();
        newSymbolTable.enterScope();
        newSymbolTable.setCurrentPackage(ctx.symbolTable.getCurrentPackage());
        for (Integer index : visibleVariables.keySet()) {
            newSymbolTable.addVariable(visibleVariables.get(index));
        }
        String[] newEnv = newSymbolTable.getVariableNames();
        ctx.logDebug("evalStringHelper " + newSymbolTable);

        ArgumentParser.CompilerOptions compilerOptions = ctx.compilerOptions.clone();
        compilerOptions.fileName = "(eval)";

        // save the eval context in a HashMap in RuntimeScalar class
        String evalTag = "eval" + EmitterMethodCreator.classCounter++;
        // create the eval context
        EmitterContext evalCtx =
                new EmitterContext(
                        null, // internal java class name will be created at runtime
                        newSymbolTable.clone(), // clone the symbolTable
                        null, // return label
                        null, // method visitor
                        ctx.contextType, // call context
                        true, // is boxed
                        ctx.errorUtil, // error message utility
                        compilerOptions);
        RuntimeCode.evalContext.put(evalTag, evalCtx);

        // Here the compiled code will call RuntimeCode.evalStringHelper(code, evalTag) method.
        // It will compile the string and return a new Class.
        //
        // XXX TODO - We need to catch any errors and set Perl error variable "$@"
        //
        // The generated method closure variables are going to be initialized in the next step.
        // Then we can call the method.

        // Retrieve the eval argument and push to the stack
        // This is the code string that we will compile into a class.
        // The string is evaluated outside the try-catch block.
        node.operand.accept(this.with(RuntimeContextType.SCALAR));

        int skipVariables = EmitterMethodCreator.skipVariables; // skip (this, @_, wantarray)

        MethodVisitor mv = ctx.mv;

        // Stack at this step: [RuntimeScalar(String)]

        // 1. Call RuntimeCode.evalStringHelper(code, evalTag)

        // Push the evalTag String to the stack
        // the compiled code will use this tag to retrieve the compiler environment
        mv.visitLdcInsn(evalTag);
        // Stack: [RuntimeScalar(String), String]

        mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/RuntimeCode",
                "evalStringHelper",
                "(Lorg/perlonjava/runtime/RuntimeScalar;Ljava/lang/String;)Ljava/lang/Class;",
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


        // Load the closure variables.
        // Here we translate the "local variable" index from the current symbol table to the new symbol table
        for (Integer index : newSymbolTable.getAllVisibleVariables().keySet()) {
            if (index >= skipVariables) {
                String varName = newEnv[index];
                mv.visitInsn(Opcodes.DUP); // Duplicate the array reference
                mv.visitIntInsn(Opcodes.BIPUSH, index - skipVariables); // Push the new index
                mv.visitVarInsn(Opcodes.ALOAD, ctx.symbolTable.getVariableIndex(varName)); // Load the constructor argument
                mv.visitInsn(Opcodes.AASTORE); // Store the argument in the array
                ctx.logDebug("Put variable " + ctx.symbolTable.getVariableIndex(varName) + " at parameter #" + (index - skipVariables) + " " + varName);
            }
        }

        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/reflect/Constructor",
                "newInstance",
                "([Ljava/lang/Object;)Ljava/lang/Object;",
                false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Object");

        // Stack after this step: [initialized class Instance]

        // 4. Create a CODE variable using RuntimeCode.makeCodeObject
        mv.visitMethodInsn(
                Opcodes.INVOKESTATIC, "org/perlonjava/runtime/RuntimeCode", "makeCodeObject", "(Ljava/lang/Object;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        // Stack: [RuntimeScalar(Code)]

        mv.visitVarInsn(Opcodes.ALOAD, 1); // push @_ to the stack
        // Transform the value in the stack to RuntimeArray
        // XXX not needed
        // mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "getArrayOfAlias", "()Lorg/perlonjava/runtime/RuntimeArray;", true);

        pushCallContext();   // push call context to stack
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "org/perlonjava/runtime/RuntimeScalar",
                "apply",
                "(Lorg/perlonjava/runtime/RuntimeArray;I)Lorg/perlonjava/runtime/RuntimeList;",
                false); // generate an .apply() call

        // 5. Clean up the stack according to context
        if (ctx.contextType == RuntimeContextType.SCALAR) {
            // Transform the value in the stack to RuntimeScalar
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeList", "scalar", "()Lorg/perlonjava/runtime/RuntimeScalar;", false);
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
        ctx.logDebug("AnonSub ctx.symbolTable.getAllVisibleVariables");

        ScopedSymbolTable newSymbolTable = new ScopedSymbolTable();
        newSymbolTable.enterScope();
        newSymbolTable.setCurrentPackage(ctx.symbolTable.getCurrentPackage());
        for (Integer index : visibleVariables.keySet()) {
            newSymbolTable.addVariable(visibleVariables.get(index));
        }
        String[] newEnv = newSymbolTable.getVariableNames();
        ctx.logDebug("AnonSub " + newSymbolTable);

        // create the new method
        EmitterContext subCtx =
                new EmitterContext(
                        EmitterMethodCreator.generateClassName(), // internal java class name
                        newSymbolTable.clone(), // closure symbolTable
                        null, // return label
                        null, // method visitor
                        RuntimeContextType.RUNTIME, // call context
                        true, // is boxed
                        ctx.errorUtil, // error message utility
                        ctx.compilerOptions);
        Class<?> generatedClass =
                EmitterMethodCreator.createClassWithMethod(
                        subCtx, node.block, node.useTryCatch
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
         *  Method applyMethod = generatedClass.getMethod("apply", RuntimeArray.class, int.class);
         *  // construct a CODE variable
         *  RuntimeScalar.new(applyMethod);
         */

        int skipVariables = EmitterMethodCreator.skipVariables; // skip (this, @_, wantarray)

        // 1. Get the class from RuntimeCode.anonSubs
        mv.visitFieldInsn(Opcodes.GETSTATIC, "org/perlonjava/runtime/RuntimeCode", "anonSubs", "Ljava/util/HashMap;");
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

        // Load the closure variables.
        // Here we translate the "local variable" index from the current symbol table to the new symbol table
        int newIndex = 0;  // new variable index
        for (Integer currentIndex : visibleVariables.keySet()) {
            if (newIndex >= skipVariables) {
                mv.visitInsn(Opcodes.DUP); // Duplicate the array reference
                mv.visitIntInsn(Opcodes.BIPUSH, newIndex - skipVariables); // Push the new index
                mv.visitVarInsn(Opcodes.ALOAD, currentIndex); // Load the constructor argument
                mv.visitInsn(Opcodes.AASTORE); // Store the argument in the array
            }
            newIndex++;
        }
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/reflect/Constructor",
                "newInstance",
                "([Ljava/lang/Object;)Ljava/lang/Object;",
                false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Object");

        // Stack after this step: [Class, Constructor, Object]

        // 4. Create a CODE variable using RuntimeCode.makeCodeObject
        mv.visitMethodInsn(
                Opcodes.INVOKESTATIC, "org/perlonjava/runtime/RuntimeCode", "makeCodeObject", "(Ljava/lang/Object;)Lorg/perlonjava/runtime/RuntimeScalar;", false);

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
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "iterator", "()Ljava/util/Iterator;", true);

        // Start of the loop
        mv.visitLabel(loopStart);

        // Check if the iterator has more elements
        mv.visitInsn(Opcodes.DUP); // Duplicate the iterator on the stack to use it for hasNext and next
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
        mv.visitJumpInsn(Opcodes.IFEQ, loopEnd);

        // Retrieve the next element from the iterator
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "org/perlonjava/runtime/RuntimeScalar"); // Cast the object to the appropriate type

        // Assign it to the loop variable
        node.variable.accept(this.with(RuntimeContextType.SCALAR));
        mv.visitInsn(Opcodes.SWAP); // move the target first
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", "set", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
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
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/RuntimeScalar", "undef", "()Lorg/perlonjava/runtime/RuntimeScalar;", false);
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
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", "getBoolean", "()Z", false);

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
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/RuntimeScalar", "undef", "()Lorg/perlonjava/runtime/RuntimeScalar;", false);
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
        ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", "getBoolean", "()Z", false);

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
                ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/RuntimeScalar", "undef", "()Lorg/perlonjava/runtime/RuntimeScalar;", false);
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
        ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", "getBoolean", "()Z", false);

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
        mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/RuntimeList");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/perlonjava/runtime/RuntimeList", "<init>", "()V", false);

        // The stack now has the new RuntimeList instance

        for (Node element : node.elements) {
            // Visit each element to generate code for it

            // Duplicate the RuntimeList instance to keep it on the stack
            mv.visitInsn(Opcodes.DUP);

            // emit the list element
            element.accept(this.with(RuntimeContextType.LIST));

            // Call the add method to add the element to the RuntimeList
            // This calls RuntimeDataProvider.addToList() in order to allow (1, 2, $x, @x, %x)
            mv.visitInsn(Opcodes.SWAP);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "addToList", "(Lorg/perlonjava/runtime/RuntimeList;)V", true);

            // The stack now has the RuntimeList instance again
        }

        // At this point, the stack has the fully populated RuntimeList instance
        if (ctx.contextType == RuntimeContextType.SCALAR) {
            // Transform the value in the stack to RuntimeScalar
            ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeList", "scalar", "()Lorg/perlonjava/runtime/RuntimeScalar;", false);
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
            mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/RuntimeScalar");
            mv.visitInsn(Opcodes.DUP);
            mv.visitLdcInsn(node.value); // emit string
            mv.visitMethodInsn(
                    Opcodes.INVOKESPECIAL,
                    "org/perlonjava/runtime/RuntimeScalar",
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
        mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/RuntimeHash");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/perlonjava/runtime/RuntimeHash", "<init>", "()V", false);

        // Create a RuntimeList
        ListNode listNode = new ListNode(node.elements, node.tokenIndex);
        listNode.accept(this.with(RuntimeContextType.LIST));

        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/RuntimeHash", "createHashRef", "(Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeScalar;", false);

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
        mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/RuntimeArray");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/perlonjava/runtime/RuntimeArray", "<init>", "()V", false);

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
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "addToArray", "(Lorg/perlonjava/runtime/RuntimeArray;)V", true);

            // The stack now has the RuntimeArray instance again
        }
        ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeArray", "createReference", "()Lorg/perlonjava/runtime/RuntimeScalar;", false);

        if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
        ctx.logDebug("visit(ArrayLiteralNode) end");
    }

    // Add other visit methods as needed
}
