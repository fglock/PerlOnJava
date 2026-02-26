package org.perlonjava.backend.jvm;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.frontend.astnode.Node;
import org.perlonjava.frontend.astnode.BinaryOperatorNode;
import org.perlonjava.frontend.astnode.OperatorNode;
import org.perlonjava.frontend.astnode.TernaryOperatorNode;
import org.perlonjava.frontend.analysis.EmitterVisitor;
import org.perlonjava.frontend.analysis.FindDeclarationVisitor;
import org.perlonjava.runtime.operators.ScalarFlipFlopOperator;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;

import static org.perlonjava.runtime.operators.ScalarFlipFlopOperator.flipFlops;

/**
 * The EmitLogicalOperator class is responsible for handling logical operators
 * and generating the corresponding bytecode using ASM.
 */
public class EmitLogicalOperator {

    private static final boolean EVAL_TRACE =
            System.getenv("JPERL_EVAL_TRACE") != null;

    private static void evalTrace(String msg) {
        if (EVAL_TRACE) {
            System.err.println("[eval-trace] " + msg);
        }
    }

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
        int flipFlopIdSlot = ctx.symbolTable.allocateLocalVariable();
        mv.visitLdcInsn(flipFlopId);
        mv.visitVarInsn(Opcodes.ISTORE, flipFlopIdSlot);

        int leftSlot = ctx.symbolTable.allocateLocalVariable();
        emitFlipFlopOperand(emitterVisitor, node.left);
        mv.visitVarInsn(Opcodes.ASTORE, leftSlot);

        int rightSlot = ctx.symbolTable.allocateLocalVariable();
        emitFlipFlopOperand(emitterVisitor, node.right);
        mv.visitVarInsn(Opcodes.ASTORE, rightSlot);

        mv.visitVarInsn(Opcodes.ILOAD, flipFlopIdSlot);
        mv.visitVarInsn(Opcodes.ALOAD, leftSlot);
        mv.visitVarInsn(Opcodes.ALOAD, rightSlot);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/operators/ScalarFlipFlopOperator", "evaluate", "(ILorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);

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
    private static void emitFlipFlopOperand(EmitterVisitor emitterVisitor, Node operandNode) {
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

        // Evaluate the left side once and spill it to keep the operand stack clean.
        // This is critical when the right side may perform non-local control flow (return/last/next/redo)
        // and jump away during evaluation.
        node.left.accept(emitterVisitor.with(RuntimeContextType.SCALAR)); // target - left parameter

        int leftSlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
        boolean pooledLeft = leftSlot >= 0;
        if (!pooledLeft) {
            leftSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
        }
        mv.visitVarInsn(Opcodes.ASTORE, leftSlot);

        // Reload left for boolean test
        mv.visitVarInsn(Opcodes.ALOAD, leftSlot);
        mv.visitInsn(Opcodes.DUP);

        // Convert the result to a boolean
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeBase", getBoolean, "()Z", false);
        // Stack is [left, boolean]

        // If the boolean value is true, jump to endLabel (we keep the left operand)
        mv.visitJumpInsn(compareOpcode, endLabel);

        mv.visitInsn(Opcodes.POP);

        // Left was false: evaluate right operand in scalar context.
        // Stack is clean here, so any non-local control flow jump doesn't leave stray values behind.
        node.right.accept(emitterVisitor.with(RuntimeContextType.SCALAR));

        // Load left back for assignment
        mv.visitVarInsn(Opcodes.ALOAD, leftSlot);
        // Stack is [right, left]

        mv.visitInsn(Opcodes.SWAP);   // Stack becomes [left, right]

        // Assign right to left
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeScalar", "set", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
        // Stack is [right]

        // At this point, the stack either has the left (if it was true) or the right (if left was false)
        mv.visitLabel(endLabel);

        if (pooledLeft) {
            emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
        }

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

        String savedOperator = null;
        Node savedOperand = null;
        boolean rewritten = false;
        try {
            if (declaration != null && declaration.operand instanceof OperatorNode operatorNode) {
                savedOperator = declaration.operator;
                savedOperand = declaration.operand;

                // emit bytecode for the declaration
                declaration.accept(emitterVisitor.with(RuntimeContextType.VOID));
                // replace the declaration with its operand (temporarily)
                declaration.operator = operatorNode.operator;
                declaration.operand = operatorNode.operand;
                rewritten = true;
            }

            // Evaluate LHS in scalar context (for boolean test)
            node.left.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
            // Stack: [RuntimeScalar]

            mv.visitInsn(Opcodes.DUP);
            // Stack: [RuntimeScalar, RuntimeScalar]

            // Test boolean value
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeBase", getBoolean, "()Z", false);
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
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeScalar",
                    "getList", "()Lorg/perlonjava/runtime/runtimetypes/RuntimeList;", false);
            // Stack: [RuntimeList]

            mv.visitLabel(endLabel);
            // Stack: [RuntimeList] from both branches
            EmitOperator.handleVoidContext(emitterVisitor);
        } finally {
            if (rewritten) {
                declaration.operator = savedOperator;
                declaration.operand = savedOperand;
            }
        }
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

        // xor always needs RuntimeScalar operands, so evaluate in SCALAR context
        // Evaluate left operand
        node.left.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        // Stack: [left]

        // Store left in a local variable to keep stack clean for control flow
        int leftVar = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
        mv.visitVarInsn(Opcodes.ASTORE, leftVar);
        // Stack: []

        // Evaluate right operand (this may jump away if it's 'next', 'last', 'redo', 'return', etc.)
        // If it jumps, the stack is now clean at the loop level
        node.right.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        // Stack: [right] (only if right didn't jump away)

        // Load left back onto stack
        mv.visitVarInsn(Opcodes.ALOAD, leftVar);
        // Stack: [right, left]

        // Swap to get correct order for xor(left, right)
        mv.visitInsn(Opcodes.SWAP);
        // Stack: [left, right]

        // Call the xor operator
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/operators/Operator",
                "xor",
                "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
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

        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            evalTrace("EmitLogicalOperatorSimple VOID op=" + node.operator + " emit LHS in SCALAR; RHS in SCALAR");
            node.left.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeBase", getBoolean, "()Z", false);
            mv.visitJumpInsn(compareOpcode, endLabel);

            node.right.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
            mv.visitInsn(Opcodes.POP);

            mv.visitLabel(endLabel);
            return;
        }

        // If the right operand contains a variable declaration (e.g. `... && my $x = ...`),
        // emit the declaration first and then temporarily rewrite that AST node so the logical
        // operator sees just the declared variable/expression.
        //
        // IMPORTANT: this rewrite must be temporary. The compiler may re-run code generation
        // (e.g. diagnostic second pass after an ASM frame compute crash), and mutating the AST
        // permanently can change semantics and even introduce spurious errors.
        OperatorNode declaration = FindDeclarationVisitor.findOperator(node.right, "my");
        String savedOperator = null;
        Node savedOperand = null;
        boolean rewritten = false;
        JavaClassInfo.SpillRef resultRef = null;
        try {
            if (declaration != null && declaration.operand instanceof OperatorNode operatorNode) {
                savedOperator = declaration.operator;
                savedOperand = declaration.operand;

                declaration.accept(emitterVisitor.with(RuntimeContextType.VOID));
                declaration.operator = operatorNode.operator;
                declaration.operand = operatorNode.operand;
                rewritten = true;
            }

            // Logical operators always evaluate their operands in scalar context for truthiness.
            // Even when the enclosing context is RUNTIME, evaluating operands in RUNTIME can
            // propagate VOID into constructs like `eval STRING`, breaking `eval "1" or die`.
            int operandContext = RuntimeContextType.SCALAR;

            resultRef = emitterVisitor.ctx.javaClassInfo.acquireSpillRefOrAllocate(emitterVisitor.ctx.symbolTable);

            // Evaluate LHS and store it.
            node.left.accept(emitterVisitor.with(operandContext));
            emitterVisitor.ctx.javaClassInfo.storeSpillRef(mv, resultRef);

            // Boolean test on the stored LHS.
            emitterVisitor.ctx.javaClassInfo.loadSpillRef(mv, resultRef);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeBase", getBoolean, "()Z", false);
            mv.visitJumpInsn(compareOpcode, endLabel);

            // LHS didn't short-circuit: evaluate RHS, overwrite result.
            node.right.accept(emitterVisitor.with(operandContext));
            emitterVisitor.ctx.javaClassInfo.storeSpillRef(mv, resultRef);

            // Return whichever side won the short-circuit.
            mv.visitLabel(endLabel);
            emitterVisitor.ctx.javaClassInfo.loadSpillRef(mv, resultRef);
            EmitOperator.handleVoidContext(emitterVisitor);
        } finally {
            if (resultRef != null) {
                emitterVisitor.ctx.javaClassInfo.releaseSpillRef(resultRef);
            }
            if (rewritten) {
                declaration.operator = savedOperator;
                declaration.operand = savedOperand;
            }
        }
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

        MethodVisitor mv = emitterVisitor.ctx.mv;
        int contextType = emitterVisitor.ctx.contextType;

        // Visit the condition node in scalar context
        node.condition.accept(emitterVisitor.with(RuntimeContextType.SCALAR));

        // Convert the result to a boolean
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeBase", "getBoolean", "()Z", false);

        // Jump to the else label if the condition is false
        mv.visitJumpInsn(Opcodes.IFEQ, elseLabel);

        // Visit the then branch
        if (contextType == RuntimeContextType.VOID) {
            node.trueExpr.accept(emitterVisitor);
            mv.visitJumpInsn(Opcodes.GOTO, endLabel);

            // Visit the else label
            mv.visitLabel(elseLabel);
            node.falseExpr.accept(emitterVisitor);

            // Visit the end label
            mv.visitLabel(endLabel);

            emitterVisitor.ctx.logDebug("TERNARY_OP end");
            return;
        }

        int resultSlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
        boolean usedSpillSlot = resultSlot != -1;
        if (!usedSpillSlot) {
            resultSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
        }

        node.trueExpr.accept(emitterVisitor);
        mv.visitVarInsn(Opcodes.ASTORE, resultSlot);

        // Jump to the end label after executing the then branch
        mv.visitJumpInsn(Opcodes.GOTO, endLabel);

        // Visit the else label
        mv.visitLabel(elseLabel);

        // Visit the else branch
        node.falseExpr.accept(emitterVisitor);
        mv.visitVarInsn(Opcodes.ASTORE, resultSlot);

        // Visit the end label
        mv.visitLabel(endLabel);

        mv.visitVarInsn(Opcodes.ALOAD, resultSlot);
        if (usedSpillSlot) {
            emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
        }

        emitterVisitor.ctx.logDebug("TERNARY_OP end");
    }
}
