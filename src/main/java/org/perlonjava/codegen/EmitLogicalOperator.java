package org.perlonjava.codegen;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.BinaryOperatorNode;
import org.perlonjava.astnode.TernaryOperatorNode;
import org.perlonjava.runtime.RuntimeContextType;
import org.perlonjava.operators.ScalarFlipFlopOperator;

import static org.perlonjava.operators.ScalarFlipFlopOperator.flipFlops;

public class EmitLogicalOperator {

    static void emitFlipFlopOperator(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        EmitterContext ctx = emitterVisitor.ctx;
        MethodVisitor mv = ctx.mv;

        // Generate unique IDs for this flip-flop instance
        int flipFlopId = ScalarFlipFlopOperator.currentId++;

        // Constructor to initialize the flip-flop operator with a unique identifier
        ScalarFlipFlopOperator op = new ScalarFlipFlopOperator(node.operator.equals("..."));
        flipFlops.putIfAbsent(flipFlopId, op);   // Initialize to false state

        //     public static String evaluate(int id, boolean leftOperand, boolean rightOperand) {
        mv.visitLdcInsn(flipFlopId);
        node.left.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        node.right.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/operators/ScalarFlipFlopOperator", "evaluate", "(ILorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);

        // If the context is VOID, we need to pop the result from the stack
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            mv.visitInsn(Opcodes.POP);
        }
    }

    static void emitLogicalAssign(EmitterVisitor emitterVisitor, BinaryOperatorNode node, int compareOpcode, String getBoolean) {
        // Implements `||=` `&&=`, depending on compareOpcode

        MethodVisitor mv = emitterVisitor.ctx.mv;
        Label endLabel = new Label(); // Label for the end of the operation

        node.left.accept(emitterVisitor.with(RuntimeContextType.SCALAR)); // target - left parameter
        // the left parameter is in the stack

        mv.visitInsn(Opcodes.DUP);
        // stack is [left, left]

        // Convert the result to a boolean
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", getBoolean, "()Z", true);
        // stack is [left, boolean]

        // If the boolean value is true, jump to endLabel (we keep the left operand)
        mv.visitJumpInsn(compareOpcode, endLabel);

        node.right.accept(emitterVisitor.with(RuntimeContextType.SCALAR)); // Evaluate right operand in scalar context
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
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            mv.visitInsn(Opcodes.POP);
        }
    }

    static void emitLogicalOperator(EmitterVisitor emitterVisitor, BinaryOperatorNode node, int compareOpcode, String getBoolean) {
        // Implements `||` `&&`, depending on compareOpcode

        MethodVisitor mv = emitterVisitor.ctx.mv;
        Label endLabel = new Label(); // Label for the end of the operation

        node.left.accept(emitterVisitor.with(RuntimeContextType.SCALAR)); // target - left parameter
        // the left parameter is in the stack

        mv.visitInsn(Opcodes.DUP);
        // stack is [left, left]

        // Convert the result to a boolean
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", getBoolean, "()Z", true);
        // stack is [left, boolean]

        // If the left operand boolean value is true, return left operand
        mv.visitJumpInsn(compareOpcode, endLabel);

        mv.visitInsn(Opcodes.POP); // remove left operand
        node.right.accept(emitterVisitor.with(RuntimeContextType.SCALAR)); // right operand in scalar context
        // stack is [right]

        mv.visitLabel(endLabel);
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            mv.visitInsn(Opcodes.POP);
        }
    }

    static void emitTernaryOperator(EmitterVisitor emitterVisitor, TernaryOperatorNode node) {
        emitterVisitor.ctx.logDebug("TERNARY_OP start");

        // Create labels for the else and end branches
        Label elseLabel = new Label();
        Label endLabel = new Label();

        // Visit the condition node in scalar context
        node.condition.accept(emitterVisitor.with(RuntimeContextType.SCALAR));

        // Convert the result to a boolean
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "getBoolean", "()Z", true);

        // Jump to the else label if the condition is false
        emitterVisitor.ctx.mv.visitJumpInsn(Opcodes.IFEQ, elseLabel);

        // Visit the then branch
        node.trueExpr.accept(emitterVisitor);

        // Jump to the end label after executing the then branch
        emitterVisitor.ctx.mv.visitJumpInsn(Opcodes.GOTO, endLabel);

        // Visit the else label
        emitterVisitor.ctx.mv.visitLabel(elseLabel);

        // Visit the else branch
        node.falseExpr.accept(emitterVisitor);

        // Visit the end label
        emitterVisitor.ctx.mv.visitLabel(endLabel);

        emitterVisitor.ctx.logDebug("TERNARY_OP end");
    }
}
