package org.perlonjava.codegen;

import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.*;
import org.perlonjava.astvisitor.EmitterVisitor;
import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeContextType;

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
        EmitterVisitor scalarVisitor = emitterVisitor.with(RuntimeContextType.SCALAR);

        if (node.right instanceof OperatorNode right
                && right.operand instanceof ListNode listNode
                && !right.operator.equals("quoteRegex")) {
            // Regex operator: $v =~ /regex/; (but NOT qr//)
            // Bind the variable to the regex operation
            listNode.elements.add(node.left);
            right.accept(emitterVisitor);  // Use caller's context for regex operations
            return;
        }

        // Handle non-regex operator case (e.g., $v =~ $qr OR $v =~ qr//)
        node.right.accept(scalarVisitor);

        int regexSlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
        boolean pooledRegex = regexSlot >= 0;
        if (!pooledRegex) {
            regexSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
        }
        emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ASTORE, regexSlot);

        node.left.accept(scalarVisitor);

        emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ALOAD, regexSlot);
        emitterVisitor.ctx.mv.visitInsn(Opcodes.SWAP);

        if (pooledRegex) {
            emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
        }
        emitMatchRegex(emitterVisitor);  // Use caller's context for regex matching
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
        // Check if using !~ with tr///r or y///r (which doesn't make sense)
        if (node.right instanceof OperatorNode operatorNode
                && (operatorNode.operator.equals("tr") || operatorNode.operator.equals("transliterate"))
                && operatorNode.operand instanceof ListNode listNode
                && listNode.elements.size() >= 3) {
            // Check if the modifiers (third element) contain 'r'
            Node modifiersNode = listNode.elements.get(2);
            if (modifiersNode instanceof StringNode stringNode) {
                String modifiers = stringNode.value;
                if (modifiers.contains("r")) {
                    throw new PerlCompilerException(node.tokenIndex,
                            "Using !~ with tr///r doesn't make sense",
                            emitterVisitor.ctx.errorUtil);
                }
            }
        }

        // Check if using !~ with s///r (which doesn't make sense)
        if (node.right instanceof OperatorNode operatorNode
                && operatorNode.operator.equals("replaceRegex")
                && operatorNode.operand instanceof ListNode listNode
                && listNode.elements.size() >= 2) {
            // Check if the modifiers (second element) contain 'r'
            Node modifiersNode = listNode.elements.get(1);
            if (modifiersNode instanceof StringNode stringNode) {
                String modifiers = stringNode.value;
                if (modifiers.contains("r")) {
                    throw new PerlCompilerException(node.tokenIndex,
                            "Using !~ with s///r doesn't make sense",
                            emitterVisitor.ctx.errorUtil);
                }
            }
        }

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
     * Example: `command` or qx/command/ or readpipe($expr)
     */
    static void handleSystemCommand(EmitterVisitor emitterVisitor, OperatorNode node) {
        EmitterVisitor scalarVisitor = emitterVisitor.with(RuntimeContextType.SCALAR);
        Node commandNode;
        
        // Handle two cases:
        // 1. readpipe() with no args -> operand is OperatorNode for $_
        // 2. readpipe($expr) or `cmd` -> operand is ListNode with command
        if (node.operand instanceof ListNode) {
            ListNode operand = (ListNode) node.operand;
            commandNode = operand.elements.getFirst();
        } else {
            // readpipe() with no arguments uses $_
            commandNode = node.operand;
        }
        
        commandNode.accept(scalarVisitor);
        emitterVisitor.pushCallContext();
        // Create an OperatorNode for systemCommand
        OperatorNode systemCmdNode = new OperatorNode("systemCommand", commandNode, node.tokenIndex);
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

        int regexSlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
        boolean pooledRegex = regexSlot >= 0;
        if (!pooledRegex) {
            regexSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
        }
        emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ASTORE, regexSlot);

        // Use default variable $_ if none specified
        handleVariableBinding(operand, 3, scalarVisitor);

        emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ALOAD, regexSlot);
        emitterVisitor.ctx.mv.visitInsn(Opcodes.SWAP);

        if (pooledRegex) {
            emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
        }

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

        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        } else {
            emitterVisitor.ctx.javaClassInfo.incrementStackLevel(1);
        }
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

        int regexSlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
        boolean pooledRegex = regexSlot >= 0;
        if (!pooledRegex) {
            regexSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
        }
        emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ASTORE, regexSlot);

        // Use default variable $_ if none specified
        handleVariableBinding(operand, 2, scalarVisitor);

        emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ALOAD, regexSlot);
        emitterVisitor.ctx.mv.visitInsn(Opcodes.SWAP);

        if (pooledRegex) {
            emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
        }

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
                "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;I)Lorg/perlonjava/runtime/RuntimeBase;", false);

        emitterVisitor.ctx.javaClassInfo.incrementStackLevel(1);

        // Handle the result based on context type
        if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR) {
            // Convert result to Scalar if in scalar context
            emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeBase", "scalar", "()Lorg/perlonjava/runtime/RuntimeScalar;", false);
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
