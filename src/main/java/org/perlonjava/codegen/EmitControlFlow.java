package org.perlonjava.codegen;

import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.IdentifierNode;
import org.perlonjava.astnode.ListNode;
import org.perlonjava.astnode.Node;
import org.perlonjava.astnode.OperatorNode;
import org.perlonjava.astvisitor.EmitterVisitor;
import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeContextType;

/**
 * Handles the emission of control flow bytecode instructions for Perl-like language constructs.
 * This class manages loop control operators (next, last, redo), subroutine returns, and goto statements.
 */
public class EmitControlFlow {
    /**
     * Handles the 'next', 'last', and 'redo' operators for loop control.
     * - 'next' is equivalent to 'continue' in Java
     * - 'last' is equivalent to 'break' in Java
     * - 'redo' restarts the current loop iteration
     *
     * @param ctx  The current emitter context containing compilation state
     * @param node The operator node representing the control flow statement
     * @throws PerlCompilerException if the operator is used outside a loop block
     */
    static void handleNextOperator(EmitterContext ctx, OperatorNode node) {
        ctx.logDebug("visit(next)");

        // Initialize label string for labeled loops
        String labelStr = null;
        ListNode labelNode = (ListNode) node.operand;
        if (!labelNode.elements.isEmpty()) {
            // Handle 'next' with a label.
            Node arg = labelNode.elements.getFirst();
            if (arg instanceof IdentifierNode) {
                // Extract the label name.
                labelStr = ((IdentifierNode) arg).name;
            } else {
                throw new PerlCompilerException(node.tokenIndex, "Not implemented: " + node, ctx.errorUtil);
            }
        }

        String operator = node.operator;
        // Find loop labels by name.
        LoopLabels loopLabels = ctx.javaClassInfo.findLoopLabelsByName(labelStr);
        ctx.logDebug("visit(next) operator: " + operator + " label: " + labelStr + " labels: " + loopLabels);
        
        if (loopLabels == null) {
            // TIER 2: NON-LOCAL JUMP - Clean stack and throw exception
            // Stack MUST be cleaned before throwing to help ASM compute correct stack map frames
            
            // Clean up the stack before throwing
            ctx.javaClassInfo.stackLevelManager.emitPopInstructions(ctx.mv, 0);
            
            // Load label name (or null)
            if (labelStr != null) {
                ctx.mv.visitLdcInsn(labelStr);
            } else {
                ctx.mv.visitInsn(Opcodes.ACONST_NULL);
            }
            
            // Create and throw the appropriate exception
            String exceptionClass = operator.equals("next") ? "org/perlonjava/runtime/NextException"
                    : operator.equals("last") ? "org/perlonjava/runtime/LastException"
                    : "org/perlonjava/runtime/RedoException";
            
            ctx.mv.visitTypeInsn(Opcodes.NEW, exceptionClass);
            ctx.mv.visitInsn(Opcodes.DUP_X1);  // Stack: exception, label, exception
            ctx.mv.visitInsn(Opcodes.SWAP);    // Stack: exception, exception, label
            ctx.mv.visitMethodInsn(Opcodes.INVOKESPECIAL, exceptionClass, "<init>", "(Ljava/lang/String;)V", false);
            ctx.mv.visitInsn(Opcodes.ATHROW);
            return;  // Exception thrown, no further code generation needed
        }

        // TIER 1: LOCAL JUMP - Use existing fast implementation (ZERO OVERHEAD)
        ctx.logDebug("visit(next): asmStackLevel: " + ctx.javaClassInfo.stackLevelManager.getStackLevel());

        // Clean up the stack before jumping by popping values up to the loop's stack level
        ctx.javaClassInfo.stackLevelManager.emitPopInstructions(ctx.mv, loopLabels.asmStackLevel);

        // Handle return values based on context
        if (loopLabels.context != RuntimeContextType.VOID) {
            if (operator.equals("next") || operator.equals("last")) {
                // For non-void contexts, ensure an 'undef' value is pushed to maintain stack consistency
                EmitOperator.emitUndef(ctx.mv);
            }
        }

        // Select the appropriate jump target based on the operator type
        Label label = operator.equals("next") ? loopLabels.nextLabel
                : operator.equals("last") ? loopLabels.lastLabel
                : loopLabels.redoLabel;
        ctx.mv.visitJumpInsn(Opcodes.GOTO, label);
    }

    /**
     * Handles the 'return' operator for subroutine exits.
     * Processes both single and multiple return values, ensuring proper stack management.
     *
     * @param emitterVisitor The visitor handling the bytecode emission
     * @param node           The operator node representing the return statement
     */
    static void handleReturnOperator(EmitterVisitor emitterVisitor, OperatorNode node) {
        EmitterContext ctx = emitterVisitor.ctx;

        ctx.logDebug("visit(return) in context " + emitterVisitor.ctx.contextType);
        ctx.logDebug("visit(return) will visit " + node.operand + " in context " + emitterVisitor.ctx.with(RuntimeContextType.RUNTIME).contextType);

        // Clean up stack before return
        ctx.javaClassInfo.stackLevelManager.emitPopInstructions(ctx.mv, 0);

        // Handle special case for single-element return lists
        if (node.operand instanceof ListNode list) {
            if (list.elements.size() == 1) {
                // Optimize single-value returns
                list.elements.getFirst().accept(emitterVisitor.with(RuntimeContextType.RUNTIME));
                emitterVisitor.ctx.mv.visitJumpInsn(Opcodes.GOTO, emitterVisitor.ctx.javaClassInfo.returnLabel);
                return;
            }
        }

        // Process the return value(s) and jump to the subroutine's return point
        node.operand.accept(emitterVisitor.with(RuntimeContextType.RUNTIME));
        emitterVisitor.ctx.mv.visitJumpInsn(Opcodes.GOTO, emitterVisitor.ctx.javaClassInfo.returnLabel);
    }

    /**
     * Handles goto statements with labels.
     * Supports both compile-time constant labels (goto LABEL) and runtime-evaluated labels (goto $var).
     * Validates label existence and manages stack cleanup before jumping.
     *
     * @param emitterVisitor The visitor handling the bytecode emission
     * @param node           The operator node representing the goto statement
     * @throws PerlCompilerException if the label is missing or invalid
     */
    static void handleGotoLabel(EmitterVisitor emitterVisitor, OperatorNode node) {
        EmitterContext ctx = emitterVisitor.ctx;

        // Parse the goto label - can be identifier (compile-time) or expression (runtime)
        String labelName = null;
        Node labelExpr = null;
        
        if (node.operand instanceof ListNode labelNode && !labelNode.elements.isEmpty()) {
            Node arg = labelNode.elements.getFirst();
            if (arg instanceof IdentifierNode) {
                // Compile-time constant label: goto LABEL
                labelName = ((IdentifierNode) arg).name;
            } else {
                // Runtime-evaluated label: goto $var or goto expr()
                labelExpr = arg;
            }
        }

        // Ensure label is provided
        if (labelName == null && labelExpr == null) {
            throw new PerlCompilerException(node.tokenIndex, "goto must be given label", ctx.errorUtil);
        }

        // Handle runtime-evaluated labels (goto $var)
        if (labelExpr != null) {
            // Evaluate the expression to get the label name at runtime
            labelExpr.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
            
            // Convert to string: labelName = expr.toString()
            ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, 
                "org/perlonjava/runtime/RuntimeScalar", 
                "toString", 
                "()Ljava/lang/String;", 
                false);
            
            // Store the label name in a local variable for repeated comparisons
            int labelVarIndex = ctx.symbolTable.allocateLocalVariable();
            ctx.mv.visitVarInsn(Opcodes.ASTORE, labelVarIndex);
            
            // Try to match against all known goto labels in scope
            for (GotoLabels gotoLabels : ctx.javaClassInfo.gotoLabelStack) {
                // Load the label name
                ctx.mv.visitVarInsn(Opcodes.ALOAD, labelVarIndex);
                ctx.mv.visitLdcInsn(gotoLabels.labelName);
                ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", 
                    "(Ljava/lang/Object;)Z", false);
                
                Label noMatch = new Label();
                ctx.mv.visitJumpInsn(Opcodes.IFEQ, noMatch);
                
                // Match found! Clean up stack and jump to label
                ctx.javaClassInfo.stackLevelManager.emitPopInstructions(ctx.mv, gotoLabels.asmStackLevel);
                ctx.mv.visitJumpInsn(Opcodes.GOTO, gotoLabels.gotoLabel);
                
                ctx.mv.visitLabel(noMatch);
            }
            
            // No local match found, throw GotoException for non-local handling
            ctx.mv.visitVarInsn(Opcodes.ALOAD, labelVarIndex);
            ctx.mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/GotoException");
            ctx.mv.visitInsn(Opcodes.DUP_X1);
            ctx.mv.visitInsn(Opcodes.SWAP);
            ctx.mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/perlonjava/runtime/GotoException", "<init>", 
                "(Ljava/lang/String;)V", false);
            ctx.mv.visitInsn(Opcodes.ATHROW);
            return;
        }

        // Handle compile-time constant labels (goto LABEL)
        // Locate the target label in the current scope
        GotoLabels targetLabel = ctx.javaClassInfo.findGotoLabelsByName(labelName);
        if (targetLabel == null) {
            // TIER 2: NON-LOCAL JUMP - Clean stack and throw exception
            // Stack MUST be cleaned before throwing to help ASM compute correct stack map frames
            
            // Clean up the stack before throwing
            ctx.javaClassInfo.stackLevelManager.emitPopInstructions(ctx.mv, 0);
            
            // Load label name
            ctx.mv.visitLdcInsn(labelName);
            
            // Create and throw GotoException
            ctx.mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/GotoException");
            ctx.mv.visitInsn(Opcodes.DUP_X1);  // Stack: exception, label, exception
            ctx.mv.visitInsn(Opcodes.SWAP);    // Stack: exception, exception, label
            ctx.mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/perlonjava/runtime/GotoException", "<init>", "(Ljava/lang/String;)V", false);
            ctx.mv.visitInsn(Opcodes.ATHROW);
            return;  // Exception thrown, no further code generation needed
        }

        // TIER 1: LOCAL JUMP - Use existing fast implementation
        // Clean up stack before jumping to maintain stack consistency
        ctx.javaClassInfo.stackLevelManager.emitPopInstructions(ctx.mv, targetLabel.asmStackLevel);

        // Emit the goto instruction
        ctx.mv.visitJumpInsn(Opcodes.GOTO, targetLabel.gotoLabel);
    }
}
