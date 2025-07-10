package org.perlonjava.codegen;

import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.*;
import org.perlonjava.astvisitor.EmitterVisitor;
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
     * Example: $variable =~ /pattern/
     *
     * @param emitterVisitor The visitor used to emit bytecode.
     * @param node           The binary operator node representing the binding regex operation.
     */
    static void handleBindRegex(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        // AST Structure for reference:
        //  BinaryOperatorNode: =~
        //    OperatorNode: $
        //      IdentifierNode: a
        //    OperatorNode: matchRegex (or `qr` object)
        //      ListNode:
        //        StringNode: 'abc'
        //        StringNode: 'i'

        // Execute operands in scalar context
        EmitterVisitor scalarVisitor = emitterVisitor.with(RuntimeContextType.SCALAR);

        if (node.right instanceof OperatorNode right
                && right.operand instanceof ListNode listNode) {
            // Regex operator: $v =~ /regex/;
            // Bind the variable to the regex operation
            listNode.elements.add(node.left);
            right.accept(emitterVisitor);
            return;
        }

        // Handle non-regex operator case (e.g., $v =~ $qr)
        node.right.accept(scalarVisitor);
        node.left.accept(scalarVisitor);
        emitMatchRegex(emitterVisitor);
    }

    /**
     * Handles the non-binding regex operation (!~).
     * Negates the result of a binding regex operation.
     * Example: $variable !~ /pattern/
     *
     * @param emitterVisitor The visitor used to emit bytecode.
     * @param node           The binary operator node representing the non-binding regex operation.
     */
    static void handleNotBindRegex(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        emitterVisitor.visit(
                new OperatorNode("not",
                        new BinaryOperatorNode(
                                "=~",
                                node.left,
                                node.right,
                                node.tokenIndex
                        ), node.tokenIndex
                ));
    }

    /**
     * Handles system command execution (backticks or qx operator).
     * Example: `command` or qx/command/
     */
    static void handleSystemCommand(EmitterVisitor emitterVisitor, OperatorNode node) {
        ListNode operand = (ListNode) node.operand;
        EmitterVisitor scalarVisitor = emitterVisitor.with(RuntimeContextType.SCALAR);
        operand.elements.getFirst().accept(scalarVisitor);
        emitterVisitor.pushCallContext();
        // Create an OperatorNode for systemCommand
        OperatorNode systemCmdNode = new OperatorNode("systemCommand", operand.elements.getFirst(), node.tokenIndex);
        EmitOperator.emitOperator(systemCmdNode, emitterVisitor);
    }


    /**
     * Handles transliteration operations (tr/// or y///).
     * Example: $string =~ tr/abc/def/
     */
    static void handleTransliterate(EmitterVisitor emitterVisitor, OperatorNode node) {
        ListNode operand = (ListNode) node.operand;
        EmitterVisitor scalarVisitor = emitterVisitor.with(RuntimeContextType.SCALAR);

        // Process the three required components: source, target, and flags
        operand.elements.get(0).accept(scalarVisitor);  // Source characters
        operand.elements.get(1).accept(scalarVisitor);  // Target characters
        operand.elements.get(2).accept(scalarVisitor);  // Flags/modifiers

        // Compile the transliteration operation
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/operators/RuntimeTransliterate", "compile",
                "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/operators/RuntimeTransliterate;", false);

        // Use default variable $_ if none specified
        handleVariableBinding(operand, 3, scalarVisitor);

        // Push call context for SCALAR or LIST context.
        emitterVisitor.pushCallContext();

        // Execute the transliteration
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/operators/RuntimeTransliterate", "transliterate", "(Lorg/perlonjava/runtime/RuntimeScalar;I)Lorg/perlonjava/runtime/RuntimeScalar;", false);

        // Clean up stack if in void context
        EmitOperator.handleVoidContext(emitterVisitor);
    }

    /**
     * Handles regex replacement operations (s///).
     * Example: $string =~ s/pattern/replacement/
     */
    static void handleReplaceRegex(EmitterVisitor emitterVisitor, OperatorNode node) {
        ListNode operand = (ListNode) node.operand;
        EmitterVisitor scalarVisitor = emitterVisitor.with(RuntimeContextType.SCALAR);

        // Process pattern, replacement, and flags
        operand.elements.get(0).accept(scalarVisitor);  // Pattern
        operand.elements.get(1).accept(scalarVisitor);  // Replacement
        operand.elements.get(2).accept(scalarVisitor);  // Flags

        // Create the replacement regex
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/regex/RuntimeRegex", "getReplacementRegex",
                "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);

        // Use default variable $_ if none specified
        handleVariableBinding(operand, 3, scalarVisitor);

        emitMatchRegex(emitterVisitor);
    }

    /**
     * Handles quoted regex operations (qr//).
     * Example: qr/pattern/
     */
    static void handleQuoteRegex(EmitterVisitor emitterVisitor, OperatorNode node) {
        ListNode operand = (ListNode) node.operand;
        EmitterVisitor scalarVisitor = emitterVisitor.with(RuntimeContextType.SCALAR);

        // Process pattern and flags
        operand.elements.get(0).accept(scalarVisitor);  // Pattern
        operand.elements.get(1).accept(scalarVisitor);  // Flags

        // Create the quoted regex
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/regex/RuntimeRegex", "getQuotedRegex",
                "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
    }

    /**
     * Handles regex match operations (m//).
     * Example: $string =~ m/pattern/
     */
    static void handleMatchRegex(EmitterVisitor emitterVisitor, OperatorNode node) {
        ListNode operand = (ListNode) node.operand;
        EmitterVisitor scalarVisitor = emitterVisitor.with(RuntimeContextType.SCALAR);

        // Process pattern and flags
        operand.elements.get(0).accept(scalarVisitor);  // Pattern
        operand.elements.get(1).accept(scalarVisitor);  // Flags

        // Create the regex matcher
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/regex/RuntimeRegex", "getQuotedRegex",
                "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);

        // Use default variable $_ if none specified
        handleVariableBinding(operand, 2, scalarVisitor);

        emitMatchRegex(emitterVisitor);
    }

    /**
     * Helper method to emit bytecode for regex matching operations.
     * Handles different context types (SCALAR, VOID) appropriately.
     */
    private static void emitMatchRegex(EmitterVisitor emitterVisitor) {
        emitterVisitor.pushCallContext();
        // Invoke the regex matching operation
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/regex/RuntimeRegex", "matchRegex",
                "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;I)Lorg/perlonjava/runtime/RuntimeDataProvider;", false);

        // Handle the result based on context type
        if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR) {
            // Convert result to Scalar if in scalar context
            emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "scalar", "()Lorg/perlonjava/runtime/RuntimeScalar;", true);
        } else if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            // Discard result if in void context
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    /**
     * Handles variable binding for regex operations, using $_ as default if no variable is specified.
     *
     * @param operand       The ListNode containing operation elements
     * @param variableIndex The index where the variable binding should be found in the operand list
     * @param scalarVisitor The visitor used to emit scalar context bytecode
     */
    private static void handleVariableBinding(ListNode operand, int variableIndex, EmitterVisitor scalarVisitor) {
        // Check if a variable was provided in the operand list
        Node variable = null;
        if (operand.elements.size() > variableIndex) {
            variable = operand.elements.get(variableIndex);
        }

        // If no variable was specified, use the default $_ variable
        if (variable == null) {
            variable = new OperatorNode("$", new IdentifierNode("_", operand.tokenIndex), operand.tokenIndex);
        }

        // Generate bytecode for the variable access
        variable.accept(scalarVisitor);
    }
}
