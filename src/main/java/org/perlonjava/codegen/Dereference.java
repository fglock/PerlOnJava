package org.perlonjava.codegen;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.*;
import org.perlonjava.astvisitor.EmitterVisitor;
import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeContextType;

public class Dereference {
    /**
     * Handles the postfix `[]` operator.
     */
    static void handleArrayElementOperator(EmitterVisitor emitterVisitor, BinaryOperatorNode node, String arrayOperation) {
        emitterVisitor.ctx.logDebug("handleArrayElementOperator " + node + " in context " + emitterVisitor.ctx.contextType);
        EmitterVisitor scalarVisitor =
                emitterVisitor.with(RuntimeContextType.SCALAR); // execute operands in scalar context

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

                emitterVisitor.ctx.logDebug("visit(BinaryOperatorNode) $var[] ");
                varNode.accept(emitterVisitor.with(RuntimeContextType.LIST)); // target - left parameter

                ArrayLiteralNode right = (ArrayLiteralNode) node.right;
                if (right.elements.size() == 1) {
                    Node elem = right.elements.getFirst();

                    // Special case: numeric literal - use get(int) directly
                    if (elem instanceof NumberNode numberNode && numberNode.value.indexOf('.') == -1) {
                        try {
                            int index = Integer.parseInt(numberNode.value);
                            emitterVisitor.ctx.mv.visitLdcInsn(index);
                            emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeArray",
                                    arrayOperation, "(I)Lorg/perlonjava/runtime/RuntimeScalar;", false);
                        } catch (NumberFormatException e) {
                            // Fall back to RuntimeScalar if the number is too large
                            elem.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                            emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeArray",
                                    arrayOperation, "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
                        }
                    } else {
                        // Single element but not an integer literal
                        elem.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeArray",
                                arrayOperation, "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
                    }
                } else {
                    // emit the [0] as a RuntimeList
                    ListNode nodeRight = right.asListNode();
                    nodeRight.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                    emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeArray",
                            arrayOperation, "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
                }

                EmitOperator.handleVoidContext(emitterVisitor);
                return;
            }
            if (sigil.equals("@")) {
                /*  @a[10, 20]
                 *  BinaryOperatorNode: [
                 *    OperatorNode: @
                 *      IdentifierNode: a
                 *    ArrayLiteralNode:
                 *      NumberNode: 10
                 *      NumberNode: 20
                 */
                emitterVisitor.ctx.logDebug("visit(BinaryOperatorNode) @var[] ");
                sigilNode.accept(emitterVisitor.with(RuntimeContextType.LIST)); // target - left parameter

                // emit the [10, 20] as a RuntimeList
                ListNode nodeRight = ((ArrayLiteralNode) node.right).asListNode();
                nodeRight.accept(emitterVisitor.with(RuntimeContextType.LIST));

                emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeArray",
                        arrayOperation + "Slice", "(Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeList;", false);

                EmitOperator.handleVoidContext(emitterVisitor);
                return;
            }
        }
        if (node.left instanceof ListNode list) { // ("a","b","c")[2]
            // transform to:  ["a","b","c"]->[2]
            BinaryOperatorNode refNode = new BinaryOperatorNode("->",
                    new ArrayLiteralNode(list.elements, list.getIndex()),
                    node.right, node.tokenIndex);
            refNode.accept(emitterVisitor);
            return;
        }

        // default: call `->[]`
        BinaryOperatorNode refNode = new BinaryOperatorNode("->", node.left, node.right, node.tokenIndex);
        refNode.accept(emitterVisitor);
    }

    /**
     * Handles the postfix `{}` node.
     * <p>
     * hashOperation is one of: "get", "delete", "exists"
     */
    public static void handleHashElementOperator(EmitterVisitor emitterVisitor, BinaryOperatorNode node, String hashOperation) {
        emitterVisitor.ctx.logDebug("handleHashElementOperator " + node + " in context " + emitterVisitor.ctx.contextType);
        EmitterVisitor scalarVisitor =
                emitterVisitor.with(RuntimeContextType.SCALAR); // execute operands in scalar context

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

                emitterVisitor.ctx.logDebug("visit(BinaryOperatorNode) $var{} ");
                varNode.accept(emitterVisitor.with(RuntimeContextType.LIST)); // target - left parameter

                // emit the {x} as a RuntimeList
                ListNode nodeRight = ((HashLiteralNode) node.right).asListNode();

                Node nodeZero = nodeRight.elements.getFirst();
                if (nodeRight.elements.size() == 1 && nodeZero instanceof IdentifierNode) {
                    // Convert IdentifierNode to StringNode:  {a} to {"a"}
                    nodeRight.elements.set(0, new StringNode(((IdentifierNode) nodeZero).name, ((IdentifierNode) nodeZero).tokenIndex));
                    nodeZero = nodeRight.elements.getFirst(); // Update nodeZero to the new StringNode
                }

                emitterVisitor.ctx.logDebug("visit(BinaryOperatorNode) $var{}  autoquote " + node.right);

                // Optimization: if there's only one element and it's a string literal
                if (nodeRight.elements.size() == 1 && nodeZero instanceof StringNode) {
                    // Special case: string literal - use get(String) directly
                    emitterVisitor.ctx.mv.visitLdcInsn(((StringNode) nodeZero).value);
                    emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeHash",
                            hashOperation, "(Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
                } else if (nodeRight.elements.size() == 1) {
                    // Single element but not a string literal
                    Node elem = nodeRight.elements.getFirst();
                    elem.accept(scalarVisitor);
                    emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeHash",
                            hashOperation, "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
                } else {
                    // Multiple elements
                    nodeRight.accept(scalarVisitor);
                    emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeHash",
                            hashOperation, "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
                }

                EmitOperator.handleVoidContext(emitterVisitor);
                return;
            }
            if (sigil.equals("@")) {
                /*  @a{"a", "b"}
                 *  BinaryOperatorNode: {
                 *    OperatorNode: @
                 *      IdentifierNode: a
                 *    ArrayLiteralNode:
                 *      StringNode: a
                 *      StringNode: b
                 */
                // Rewrite the variable node from `@` to `%`
                OperatorNode varNode = new OperatorNode("%", sigilNode.operand, sigilNode.tokenIndex);

                emitterVisitor.ctx.logDebug("visit(BinaryOperatorNode) @var{} " + varNode);
                varNode.accept(emitterVisitor.with(RuntimeContextType.LIST)); // target - left parameter

                // emit the {x} as a RuntimeList
                ListNode nodeRight = ((HashLiteralNode) node.right).asListNode();

                Node nodeZero = nodeRight.elements.getFirst();
                if (nodeRight.elements.size() == 1 && nodeZero instanceof IdentifierNode) {
                    // Convert IdentifierNode to StringNode:  {a} to {"a"}
                    nodeRight.elements.set(0, new StringNode(((IdentifierNode) nodeZero).name, ((IdentifierNode) nodeZero).tokenIndex));
                }

                emitterVisitor.ctx.logDebug("visit(BinaryOperatorNode) $var{}  autoquote " + node.right);
                nodeRight.accept(emitterVisitor.with(RuntimeContextType.LIST));

                emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeHash",
                        hashOperation + "Slice", "(Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeList;", false);

                EmitOperator.handleVoidContext(emitterVisitor);
                return;
            }
        }

        // default: call `->{}`
        BinaryOperatorNode refNode = new BinaryOperatorNode("->", node.left, node.right, node.tokenIndex);
        handleArrowHashDeref(emitterVisitor, refNode, hashOperation);
    }

    /**
     * Handles the `->` operator.
     */
    static void handleArrowOperator(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        MethodVisitor mv = emitterVisitor.ctx.mv;
        emitterVisitor.ctx.logDebug("handleArrowOperator " + node + " in context " + emitterVisitor.ctx.contextType);
        EmitterVisitor scalarVisitor =
                emitterVisitor.with(RuntimeContextType.SCALAR); // execute operands in scalar context

        if (node.right instanceof ListNode) { // ->()

            BinaryOperatorNode applyNode = new BinaryOperatorNode("(", node.left, node.right, node.tokenIndex);
            applyNode.accept(emitterVisitor);

        } else if (node.right instanceof ArrayLiteralNode) { // ->[0]
            handleArrowArrayDeref(emitterVisitor, node, "get");

        } else if (node.right instanceof HashLiteralNode) { // ->{x}
            handleArrowHashDeref(emitterVisitor, node, "get");

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
            emitterVisitor.pushCurrentPackage();

            arguments.accept(emitterVisitor.with(RuntimeContextType.LIST)); // right parameter: parameter list

            // Transform the value in the stack to RuntimeArray
            emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "getArrayOfAlias", "()Lorg/perlonjava/runtime/RuntimeArray;", true);
            emitterVisitor.ctx.mv.visitLdcInsn(emitterVisitor.ctx.contextType);   // push call context to stack
            emitterVisitor.ctx.mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/RuntimeCode",
                    "call",
                    "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;Ljava/lang/String;Lorg/perlonjava/runtime/RuntimeArray;I)Lorg/perlonjava/runtime/RuntimeList;",
                    false); // generate an .call()
            if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR) {
                // Transform the value in the stack to RuntimeScalar
                emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeList", "scalar", "()Lorg/perlonjava/runtime/RuntimeScalar;", false);
            } else if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
                // Remove the value from the stack
                emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
            }
        }
    }

    public static void handleArrowArrayDeref(EmitterVisitor emitterVisitor, BinaryOperatorNode node, String arrayOperation) {
        emitterVisitor.ctx.logDebug("visit(BinaryOperatorNode) ->[] ");
        EmitterVisitor scalarVisitor =
                emitterVisitor.with(RuntimeContextType.SCALAR); // execute operands in scalar context

        node.left.accept(scalarVisitor); // target - left parameter

        ArrayLiteralNode right = (ArrayLiteralNode) node.right;
        if (right.elements.size() == 1) {
            // Optimization: Extract the single element if the list has only one item
            Node elem = right.elements.getFirst();
            elem.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        } else {
            // emit the [0] as a RuntimeList
            ListNode nodeRight = right.asListNode();
            nodeRight.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        }

        String methodName = switch (arrayOperation) {
            case "get" -> "arrayDerefGet";
            case "delete" -> "arrayDerefDelete";
            case "exists" -> "arrayDerefExists";
            default ->
                    throw new PerlCompilerException(node.tokenIndex, "Not implemented: array operation: " + arrayOperation, emitterVisitor.ctx.errorUtil);
        };

        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", methodName, "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        EmitOperator.handleVoidContext(emitterVisitor);
    }

    public static void handleArrowHashDeref(EmitterVisitor emitterVisitor, BinaryOperatorNode node, String hashOperation) {
        emitterVisitor.ctx.logDebug("visit(BinaryOperatorNode) ->{} ");
        EmitterVisitor scalarVisitor =
                emitterVisitor.with(RuntimeContextType.SCALAR); // execute operands in scalar context

        node.left.accept(scalarVisitor); // target - left parameter

        // emit the {0} as a RuntimeList
        ListNode nodeRight = ((HashLiteralNode) node.right).asListNode();

        Node nodeZero = nodeRight.elements.getFirst();
        if (nodeRight.elements.size() == 1 && nodeZero instanceof IdentifierNode) {
            // Convert IdentifierNode to StringNode:  {a} to {"a"}
            nodeRight.elements.set(0, new StringNode(((IdentifierNode) nodeZero).name, ((IdentifierNode) nodeZero).tokenIndex));
        }

        emitterVisitor.ctx.logDebug("visit -> (HashLiteralNode) autoquote " + node.right);
        nodeRight.accept(emitterVisitor.with(RuntimeContextType.SCALAR));

        String methodName = switch (hashOperation) {
            case "get" -> "hashDerefGet";
            case "delete" -> "hashDerefDelete";
            case "exists" -> "hashDerefExists";
            default ->
                    throw new PerlCompilerException(node.tokenIndex, "Not implemented: hash operation: " + hashOperation, emitterVisitor.ctx.errorUtil);
        };

        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", methodName, "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        EmitOperator.handleVoidContext(emitterVisitor);
    }
}
