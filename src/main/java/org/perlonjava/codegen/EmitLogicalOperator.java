package org.perlonjava.codegen;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.BinaryOperatorNode;
import org.perlonjava.astnode.OperatorNode;
import org.perlonjava.astnode.TernaryOperatorNode;
import org.perlonjava.astvisitor.EmitterVisitor;
import org.perlonjava.astvisitor.FindDeclarationVisitor;
import org.perlonjava.operators.ScalarFlipFlopOperator;
import org.perlonjava.runtime.RuntimeContextType;

import static org.perlonjava.operators.ScalarFlipFlopOperator.flipFlops;

/**
 * The EmitLogicalOperator class is responsible for handling logical operators
 * and generating the corresponding bytecode using ASM.
 */
public class EmitLogicalOperator {

    /**
     * Emits bytecode for the flip-flop operator, which is used in range-like conditions.
     *
     * @param emitterVisitor The visitor used for code emission.
     * @param node           The binary operator node representing the flip-flop operation.
     */
    static void emitFlipFlopOperator(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        EmitterContext ctx = emitterVisitor.ctx;
        MethodVisitor mv = ctx.mv;

        // Generate unique IDs for this flip-flop instance
        int flipFlopId = ScalarFlipFlopOperator.currentId++;

        // Constructor to initialize the flip-flop operator with a unique identifier
        ScalarFlipFlopOperator op = new ScalarFlipFlopOperator(node.operator.equals("..."));
        flipFlops.putIfAbsent(flipFlopId, op);   // Initialize to false state

        // Emit bytecode to evaluate the flip-flop operator
        mv.visitLdcInsn(flipFlopId);
        
        // Emit left operand - convert quoteRegex to matchRegex
        emitFlipFlopOperand(emitterVisitor, node.left);
        
        // Emit right operand - convert quoteRegex to matchRegex
        emitFlipFlopOperand(emitterVisitor, node.right);
        
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/operators/ScalarFlipFlopOperator", "evaluate", "(ILorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);

        // If the context is VOID, pop the result from the stack
        EmitOperator.handleVoidContext(emitterVisitor);
    }

    /**
     * Emits bytecode for a flip-flop operand, with special handling for regex patterns.
     * If the operand is a quoteRegex node, it's emitted as a match operation instead.
     *
     * @param emitterVisitor The visitor used for code emission.
     * @param operandNode    The operand node to emit.
     */
    private static void emitFlipFlopOperand(EmitterVisitor emitterVisitor, org.perlonjava.astnode.Node operandNode) {
        // Special handling: if operand is a quoteRegex node, emit a match operation
        if (operandNode instanceof OperatorNode opNode && "quoteRegex".equals(opNode.operator)) {
            // Emit a match operation against $_
            EmitRegex.handleMatchRegex(emitterVisitor.with(RuntimeContextType.SCALAR), opNode);
        } else {
            // Normal evaluation
            operandNode.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        }
    }

    /**
     * Emits bytecode for logical assignment operators such as `||=` and `&&=`.
     *
     * @param emitterVisitor The visitor used for code emission.
     * @param node           The binary operator node representing the logical assignment operation.
     * @param compareOpcode  The opcode used for comparison (e.g., IFEQ for `&&=`).
     * @param getBoolean     The method name to convert the result to a boolean.
     */
    static void emitLogicalAssign(EmitterVisitor emitterVisitor, BinaryOperatorNode node, int compareOpcode, String getBoolean) {
        MethodVisitor mv = emitterVisitor.ctx.mv;
        Label endLabel = new Label(); // Label for the end of the operation

        node.left.accept(emitterVisitor.with(RuntimeContextType.SCALAR)); // target - left parameter
        // The left parameter is in the stack

        mv.visitInsn(Opcodes.DUP);
        // Stack is [left, left]

        // Convert the result to a boolean
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeBase", getBoolean, "()Z", false);
        // Stack is [left, boolean]

        // If the boolean value is true, jump to endLabel (we keep the left operand)
        mv.visitJumpInsn(compareOpcode, endLabel);

        node.right.accept(emitterVisitor.with(RuntimeContextType.SCALAR)); // Evaluate right operand in scalar context
        // Stack is [left, right]

        mv.visitInsn(Opcodes.SWAP);   // Stack becomes [right, left]

        // Assign right to left
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeBase", "addToScalar", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        // Stack is [right]

        // At this point, the stack either has the left (if it was true) or the right (if left was false)
        mv.visitLabel(endLabel);

        // If the context is VOID, pop the result from the stack
        EmitOperator.handleVoidContext(emitterVisitor);
    }

    /**
     * Emits bytecode for logical operators such as `||` and `&&`.
     *
     * @param emitterVisitor The visitor used for code emission.
     * @param node           The binary operator node representing the logical operation.
     * @param compareOpcode  The opcode used for comparison (e.g., IFEQ for `&&`).
     * @param getBoolean     The method name to convert the result to a boolean.
     */
    static void emitLogicalOperator(EmitterVisitor emitterVisitor, BinaryOperatorNode node, int compareOpcode, String getBoolean) {
        MethodVisitor mv = emitterVisitor.ctx.mv;
        int callerContext = emitterVisitor.ctx.contextType;

        // In SCALAR, VOID, or RUNTIME context, use simple implementation (no context conversion needed)
        if (callerContext == RuntimeContextType.SCALAR || callerContext == RuntimeContextType.VOID || callerContext == RuntimeContextType.RUNTIME) {
            emitLogicalOperatorSimple(emitterVisitor, node, compareOpcode, getBoolean);
            return;
        }

        // LIST context: Need special handling to convert scalar LHS to list
        Label convertLabel = new Label();
        Label endLabel = new Label();

        // check if the right operand contains a variable declaration,
        // if so, move the declaration outside of the logical operator
        OperatorNode declaration = FindDeclarationVisitor.findOperator(node.right, "my");
        if (declaration != null) {
            if (declaration.operand instanceof OperatorNode operatorNode) {
                // emit bytecode for the declaration
                declaration.accept(emitterVisitor.with(RuntimeContextType.VOID));
                // replace the declaration with it's operand
                declaration.operator = operatorNode.operator;
                declaration.operand = operatorNode.operand;
            } else {
                // TODO: find an example where this happens
            }
        }

        // Evaluate LHS in scalar context (for boolean test)
        node.left.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        // Stack: [RuntimeScalar]

        mv.visitInsn(Opcodes.DUP);
        // Stack: [RuntimeScalar, RuntimeScalar]

        // Test boolean value
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeBase", getBoolean, "()Z", false);
        // Stack: [RuntimeScalar, boolean]

        // If true, jump to convert label
        mv.visitJumpInsn(compareOpcode, convertLabel);

        // LHS is false: evaluate RHS in LIST context
        mv.visitInsn(Opcodes.POP); // Remove LHS
        node.right.accept(emitterVisitor.with(RuntimeContextType.LIST));
        // Stack: [RuntimeList]
        mv.visitJumpInsn(Opcodes.GOTO, endLabel);

        // LHS is true: convert scalar to list
        mv.visitLabel(convertLabel);
        // Stack: [RuntimeScalar]
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar",
                "getList", "()Lorg/perlonjava/runtime/RuntimeList;", false);
        // Stack: [RuntimeList]

        mv.visitLabel(endLabel);
        // Stack: [RuntimeList] from both branches
        EmitOperator.handleVoidContext(emitterVisitor);
    }

    /**
     * Emits bytecode for the xor operator (low-precedence logical XOR).
     * XOR evaluates both operands (no short-circuit) and returns:
     * - left if left is true and right is false
     * - right if left is false and right is true  
     * - false otherwise
     *
     * Note: If the right operand is a control flow statement like 'next',
     * it will jump away and the xor operation will never complete.
     *
     * @param emitterVisitor The visitor used for code emission.
     * @param node           The binary operator node representing the xor operation.
     */
    static void emitXorOperator(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        MethodVisitor mv = emitterVisitor.ctx.mv;
        int operandContext = emitterVisitor.ctx.contextType == RuntimeContextType.RUNTIME
                ? RuntimeContextType.RUNTIME
                : RuntimeContextType.SCALAR;

        // Evaluate left operand
        node.left.accept(emitterVisitor.with(operandContext));
        // Stack: [left]

        // Convert to scalar if in RUNTIME context (xor requires RuntimeScalar)
        if (operandContext == RuntimeContextType.RUNTIME) {
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeBase",
                    "scalar", "()Lorg/perlonjava/runtime/RuntimeScalar;", false);
        }

        // Store left in a local variable to keep stack clean for control flow
        int leftVar = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
        mv.visitVarInsn(Opcodes.ASTORE, leftVar);
        // Stack: []

        // Evaluate right operand (this may jump away if it's 'next', 'last', 'redo', 'return', etc.)
        // If it jumps, the stack is now clean at the loop level
        node.right.accept(emitterVisitor.with(operandContext));
        // Stack: [right] (only if right didn't jump away)

        // Convert to scalar if in RUNTIME context (xor requires RuntimeScalar)
        if (operandContext == RuntimeContextType.RUNTIME) {
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeBase",
                    "scalar", "()Lorg/perlonjava/runtime/RuntimeScalar;", false);
        }

        // Load left back onto stack
        mv.visitVarInsn(Opcodes.ALOAD, leftVar);
        // Stack: [right, left]

        // Swap to get correct order for xor(left, right)
        mv.visitInsn(Opcodes.SWAP);
        // Stack: [left, right]

        // Call the xor operator
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/operators/Operator",
                "xor",
                "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;",
                false);
        // Stack: [result]

        EmitOperator.handleVoidContext(emitterVisitor);
    }

    /**
     * Simple implementation for SCALAR/VOID context (no context conversion needed)
     */
    private static void emitLogicalOperatorSimple(EmitterVisitor emitterVisitor, BinaryOperatorNode node, int compareOpcode, String getBoolean) {
        MethodVisitor mv = emitterVisitor.ctx.mv;
        Label endLabel = new Label();

        // check if the right operand contains a variable declaration
        OperatorNode declaration = FindDeclarationVisitor.findOperator(node.right, "my");
        if (declaration != null) {
            if (declaration.operand instanceof OperatorNode operatorNode) {
                declaration.accept(emitterVisitor.with(RuntimeContextType.VOID));
                declaration.operator = operatorNode.operator;
                declaration.operand = operatorNode.operand;
            }
        }

        // For RUNTIME context, preserve it; otherwise use SCALAR for boolean evaluation
        int operandContext = emitterVisitor.ctx.contextType == RuntimeContextType.RUNTIME
                ? RuntimeContextType.RUNTIME
                : RuntimeContextType.SCALAR;

        node.left.accept(emitterVisitor.with(operandContext));
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeBase", getBoolean, "()Z", false);
        mv.visitJumpInsn(compareOpcode, endLabel);
        mv.visitInsn(Opcodes.POP);
        node.right.accept(emitterVisitor.with(operandContext));
        mv.visitLabel(endLabel);
        EmitOperator.handleVoidContext(emitterVisitor);
    }

    /**
     * Emits bytecode for the ternary operator (condition ? trueExpr : falseExpr).
     *
     * @param emitterVisitor The visitor used for code emission.
     * @param node           The ternary operator node representing the operation.
     */
    public static void emitTernaryOperator(EmitterVisitor emitterVisitor, TernaryOperatorNode node) {
        emitterVisitor.ctx.logDebug("TERNARY_OP start");

        // Create labels for the else and end branches
        Label elseLabel = new Label();
        Label endLabel = new Label();

        // Visit the condition node in scalar context
        node.condition.accept(emitterVisitor.with(RuntimeContextType.SCALAR));

        // Convert the result to a boolean
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeBase", "getBoolean", "()Z", false);

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
