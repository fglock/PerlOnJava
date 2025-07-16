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
            throw new PerlCompilerException(node.tokenIndex, "Can't \"" + operator + "\" outside a loop block", ctx.errorUtil);
        }

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
     * Validates label existence and manages stack cleanup before jumping.
     *
     * @param emitterVisitor The visitor handling the bytecode emission
     * @param node           The operator node representing the goto statement
     * @throws PerlCompilerException if the label is missing or invalid
     */
    static void handleGotoLabel(EmitterVisitor emitterVisitor, OperatorNode node) {
        EmitterContext ctx = emitterVisitor.ctx;

        // Parse and validate the goto label
        String labelName = null;
        if (node.operand instanceof ListNode labelNode && !labelNode.elements.isEmpty()) {
            Node arg = labelNode.elements.getFirst();
            if (arg instanceof IdentifierNode) {
                labelName = ((IdentifierNode) arg).name;
            } else {
                throw new PerlCompilerException(node.tokenIndex, "Invalid goto label: " + node, ctx.errorUtil);
            }
        }

        // Ensure label is provided
        if (labelName == null) {
            throw new PerlCompilerException(node.tokenIndex, "goto must be given label", ctx.errorUtil);
        }

        // Locate the target label in the current scope
        GotoLabels targetLabel = ctx.javaClassInfo.findGotoLabelsByName(labelName);
        if (targetLabel == null) {
            throw new PerlCompilerException(node.tokenIndex, "Can't find label " + labelName, ctx.errorUtil);
        }

        // Clean up stack before jumping to maintain stack consistency
        ctx.javaClassInfo.stackLevelManager.emitPopInstructions(ctx.mv, targetLabel.asmStackLevel);

        // Emit the goto instruction
        ctx.mv.visitJumpInsn(Opcodes.GOTO, targetLabel.gotoLabel);
    }
}
