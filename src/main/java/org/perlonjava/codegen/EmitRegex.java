package org.perlonjava.codegen;

import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.*;
import org.perlonjava.runtime.RuntimeContextType;

import static org.perlonjava.codegen.EmitOperator.emitOperator;

/**
 * The EmitRegex class is responsible for handling regex-related operations
 * within the code generation process. It provides methods to handle binding
 * and non-binding regex operations, as well as specific regex operations like
 * transliteration and replacement.
 */
public class EmitRegex {

    /**
     * Handles the binding regex operation where a variable is bound to a regex operation.
     * This method processes the binary operator node representing the binding operation.
     *
     * @param emitterVisitor The visitor used to emit bytecode.
     * @param node           The binary operator node representing the binding regex operation.
     */
    static void handleBindRegex(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        //
        //  BinaryOperatorNode: =~
        //    OperatorNode: $
        //      IdentifierNode: a
        //    OperatorNode: matchRegex (or `qr` object)
        //      ListNode:
        //        StringNode: 'abc'
        //        StringNode: 'i'
        //

        // Execute operands in scalar context
        EmitterVisitor scalarVisitor = emitterVisitor.with(RuntimeContextType.SCALAR);

        if (node.right instanceof OperatorNode right) {
            if (right.operand instanceof ListNode listNode) {
                // Regex operator: $v =~ /regex/;
                // Bind the variable to the regex operation
                listNode.elements.add(node.left);

                if (right.operator.equals("replaceRegex")
                        && listNode.elements.get(2) instanceof StringNode modifier
                        && modifier.value.contains("r")
                ) {
                    // regex replace usually returns a list,
                    // but with "r" modifier it returns scalar
                    right = new OperatorNode("scalar", right, right.tokenIndex);
                }

                right.accept(emitterVisitor);
                return;
            }
        }

        // Not a regex operator: $v =~ $qr;
        node.right.accept(scalarVisitor);
        node.left.accept(scalarVisitor);
        emitterVisitor.pushCallContext();
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/regex/RuntimeRegex", "matchRegex",
                "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;I)Lorg/perlonjava/runtime/RuntimeDataProvider;", false);

        // If the context type is VOID, pop the result off the stack
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    /**
     * Handles the non-binding regex operation by wrapping the operation in a "not" and "scalar" context.
     *
     * @param emitterVisitor The visitor used to emit bytecode.
     * @param node           The binary operator node representing the non-binding regex operation.
     */
    static void handleNotBindRegex(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        emitterVisitor.visit(
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
    }

    /**
     * Handles various regex operations such as transliteration, replacement, and quoting.
     *
     * @param emitterVisitor The visitor used to emit bytecode.
     * @param node           The operator node representing the regex operation.
     */
    static void handleRegex(EmitterVisitor emitterVisitor, OperatorNode node) {
        ListNode operand = (ListNode) node.operand;
        EmitterVisitor scalarVisitor = emitterVisitor.with(RuntimeContextType.SCALAR);
        Node variable = null;

        switch (node.operator) {
            case "qx" -> {
                // Handle system command execution
                operand.elements.getFirst().accept(scalarVisitor);
                emitterVisitor.pushCallContext();
                emitOperator("systemCommand", emitterVisitor);
                return;
            }
            case "tr", "y" -> {
                // Handle transliteration operation
                operand.elements.get(0).accept(scalarVisitor);
                operand.elements.get(1).accept(scalarVisitor);
                operand.elements.get(2).accept(scalarVisitor);
                if (operand.elements.size() > 3) {
                    variable = operand.elements.get(3);
                }
                emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/perlonjava/operators/RuntimeTransliterate", "compile",
                        "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/operators/RuntimeTransliterate;", false);

                // Handle transliteration of the original string
                if (variable == null) {
                    // Use `$_` if no variable is provided
                    variable = new OperatorNode("$", new IdentifierNode("_", node.tokenIndex), node.tokenIndex);
                }
                variable.accept(scalarVisitor);
                emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/operators/RuntimeTransliterate", "transliterate", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);

                // If the context type is VOID, pop the result off the stack
                if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
                    emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
                }
                return;
            }
            case "replaceRegex" -> {
                // Handle regex replacement operation
                operand.elements.get(0).accept(scalarVisitor);
                operand.elements.get(1).accept(scalarVisitor);
                operand.elements.get(2).accept(scalarVisitor);
                if (operand.elements.size() > 3) {
                    variable = operand.elements.get(3);
                }
                emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/perlonjava/regex/RuntimeRegex", "getReplacementRegex",
                        "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
            }
            default -> {
                // Handle quoted regex operation
                operand.elements.get(0).accept(scalarVisitor);
                operand.elements.get(1).accept(scalarVisitor);
                if (operand.elements.size() > 2) {
                    variable = operand.elements.get(2);
                }
                emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/perlonjava/regex/RuntimeRegex", "getQuotedRegex",
                        "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
            }
        }

        if (node.operator.equals("quoteRegex")) {
            // Do not execute `qr//`
            return;
        }

        if (variable == null) {
            // Use `$_` if no variable is provided
            variable = new OperatorNode("$", new IdentifierNode("_", node.tokenIndex), node.tokenIndex);
        }
        variable.accept(scalarVisitor);

        emitterVisitor.pushCallContext();
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/regex/RuntimeRegex", "matchRegex",
                "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;I)Lorg/perlonjava/runtime/RuntimeDataProvider;", false);

        // If the context type is VOID, pop the result off the stack
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }
}
