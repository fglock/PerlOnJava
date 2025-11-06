package org.perlonjava.codegen;

import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.BinaryOperatorNode;
import org.perlonjava.astnode.IdentifierNode;
import org.perlonjava.astnode.NumberNode;
import org.perlonjava.astnode.StringNode;
import org.perlonjava.astvisitor.EmitterVisitor;
import org.perlonjava.operators.OperatorHandler;
import org.perlonjava.perlmodule.Strict;
import org.perlonjava.runtime.RuntimeContextType;
import org.perlonjava.runtime.ScalarUtils;

import static org.perlonjava.codegen.EmitOperator.emitOperator;

public class EmitBinaryOperator {
    /**
     * Helper method to evaluate binary operands with proper stack management.
     * Evaluates left operand, stores in local variable, evaluates right operand,
     * then loads both operands in correct order for the operation.
     * This prevents stackmap frame issues when right operand contains loops.
     * 
     * @param emitterVisitor The emitter visitor
     * @param node The binary operator node
     * @param leftContext Context for left operand evaluation
     * @param rightContext Context for right operand evaluation
     * @return Array of [leftValue location on stack index, rightValue location on stack index]
     */
    private static void evaluateOperandsWithLocalVar(
            EmitterVisitor emitterVisitor, 
            BinaryOperatorNode node,
            int leftContext,
            int rightContext) {
        var mv = emitterVisitor.ctx.mv;
        
        // Evaluate left operand and store in local variable
        node.left.accept(emitterVisitor.with(leftContext));
        int leftVar = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
        mv.visitVarInsn(Opcodes.ASTORE, leftVar);
        
        // Evaluate right operand with clean stack
        node.right.accept(emitterVisitor.with(rightContext));
        
        // Load left operand back
        mv.visitVarInsn(Opcodes.ALOAD, leftVar);
        
        // Swap so left is first parameter (stack: right, left -> left, right)
        mv.visitInsn(Opcodes.SWAP);
        // Stack: [left, right]
    }
    
    static void handleBinaryOperator(EmitterVisitor emitterVisitor, BinaryOperatorNode node, OperatorHandler operatorHandler) {
        EmitterVisitor scalarVisitor =
                emitterVisitor.with(RuntimeContextType.SCALAR); // execute operands in scalar context
        emitterVisitor.ctx.logDebug("handleBinaryOperator: " + node.toString());

        // Optimization for integer literals - still use local variable for safety
        if ((node.operator.equals("+")
                || node.operator.equals("-")
                || node.operator.equals("=="))
                && node.right instanceof NumberNode right) {
            String value = right.value;
            boolean isInteger = ScalarUtils.isInteger(value);
            if (isInteger) {
                // Even for optimized integer case, use local variable approach
                var mv = emitterVisitor.ctx.mv;
                node.left.accept(scalarVisitor);
                int leftVar = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
                mv.visitVarInsn(Opcodes.ASTORE, leftVar);
                
                mv.visitVarInsn(Opcodes.ALOAD, leftVar);
                int intValue = Integer.parseInt(value);
                mv.visitLdcInsn(intValue);
                mv.visitMethodInsn(
                        operatorHandler.methodType(),
                        operatorHandler.className(),
                        operatorHandler.methodName(),
                        operatorHandler.getDescriptorWithIntParameter(),
                        false);
                EmitOperator.handleVoidContext(emitterVisitor);
                return;
            }
        }

        var right = node.right;

        // Special case for `isa` - left side can be bareword
        if (node.operator.equals("isa") && right instanceof IdentifierNode identifierNode) {
            right = new StringNode(identifierNode.name, node.tokenIndex);
        }

        // Special case for modulus, division, and shift operators under "use integer"
        if (emitterVisitor.ctx.symbolTable.isStrictOptionEnabled(Strict.HINT_INTEGER)) {
            if (node.operator.equals("%")) {
                // Use integer modulus when "use integer" is in effect - with safe stack management
                evaluateOperandsWithLocalVar(emitterVisitor, node, RuntimeContextType.SCALAR, RuntimeContextType.SCALAR);
                emitterVisitor.ctx.mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "org/perlonjava/operators/MathOperators",
                        "integerModulus",
                        "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;",
                        false);
                EmitOperator.handleVoidContext(emitterVisitor);
                return;
            } else if (node.operator.equals("/")) {
                // Use integer division when "use integer" is in effect - with safe stack management
                evaluateOperandsWithLocalVar(emitterVisitor, node, RuntimeContextType.SCALAR, RuntimeContextType.SCALAR);
                emitterVisitor.ctx.mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "org/perlonjava/operators/MathOperators",
                        "integerDivide",
                        "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;",
                        false);
                EmitOperator.handleVoidContext(emitterVisitor);
                return;
            } else if (node.operator.equals("<<")) {
                // Use integer left shift when "use integer" is in effect - with safe stack management
                evaluateOperandsWithLocalVar(emitterVisitor, node, RuntimeContextType.SCALAR, RuntimeContextType.SCALAR);
                emitterVisitor.ctx.mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "org/perlonjava/operators/BitwiseOperators",
                        "integerShiftLeft",
                        "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;",
                        false);
                EmitOperator.handleVoidContext(emitterVisitor);
                return;
            } else if (node.operator.equals(">>")) {
                // Use integer right shift when "use integer" is in effect - with safe stack management
                evaluateOperandsWithLocalVar(emitterVisitor, node, RuntimeContextType.SCALAR, RuntimeContextType.SCALAR);
                emitterVisitor.ctx.mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "org/perlonjava/operators/BitwiseOperators",
                        "integerShiftRight",
                        "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;",
                        false);
                EmitOperator.handleVoidContext(emitterVisitor);
                return;
            }
        }

        // CRITICAL FIX: Store left operand in local variable to prevent stackmap frame issues
        // If we leave left operand on stack while evaluating right operand, and right operand
        // contains a loop with control flow (e.g., `for ... last`), the loop's GOTO will
        // create inconsistent stack states at the loop start label.
        // Solution: Evaluate left, store in local variable, evaluate right, load left, call operator.
        
        var mv = emitterVisitor.ctx.mv;
        
        // Evaluate left operand and store in local variable
        node.left.accept(scalarVisitor);
        int leftVar = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
        mv.visitVarInsn(Opcodes.ASTORE, leftVar);
        
        // Evaluate right operand (stack is now clean - no left value on stack)
        right.accept(scalarVisitor);
        
        // Load left operand from local variable  
        mv.visitVarInsn(Opcodes.ALOAD, leftVar);
        
        // Swap so left is first parameter (stack: right, left -> left, right)
        mv.visitInsn(Opcodes.SWAP);
        
        // stack: [left, right]
        emitOperator(node, emitterVisitor);
    }

    static void handleCompoundAssignment(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        // compound assignment operators like `+=`
        // CRITICAL FIX: Use local variable for safe stack management
        EmitterVisitor scalarVisitor =
                emitterVisitor.with(RuntimeContextType.SCALAR);
        var mv = emitterVisitor.ctx.mv;
        
        // Evaluate left (lvalue) and store in local variable
        node.left.accept(scalarVisitor);
        int lvalueVar = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
        mv.visitVarInsn(Opcodes.ASTORE, lvalueVar);
        
        // Load lvalue for the operation
        mv.visitVarInsn(Opcodes.ALOAD, lvalueVar);
        int leftValueVar = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
        mv.visitVarInsn(Opcodes.ASTORE, leftValueVar);
        
        // Evaluate right operand with clean stack
        node.right.accept(scalarVisitor);
        
        // Load left value and swap for correct order
        mv.visitVarInsn(Opcodes.ALOAD, leftValueVar);
        mv.visitInsn(Opcodes.SWAP);
        
        // stack: [left_value, right]
        // perform the operation
        String baseOperator = node.operator.substring(0, node.operator.length() - 1);
        BinaryOperatorNode baseOpNode = new BinaryOperatorNode(
                baseOperator,
                node.left,
                node.right,
                node.tokenIndex
        );
        EmitOperator.emitOperator(baseOpNode, scalarVisitor);
        
        // Load lvalue and assign result
        int resultVar = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
        mv.visitVarInsn(Opcodes.ASTORE, resultVar);
        mv.visitVarInsn(Opcodes.ALOAD, lvalueVar);
        mv.visitVarInsn(Opcodes.ALOAD, resultVar);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", "set", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        EmitOperator.handleVoidContext(emitterVisitor);
    }

    static void handleRangeOrFlipFlop(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR) {
            EmitLogicalOperator.emitFlipFlopOperator(emitterVisitor, node);
        } else {
            EmitOperator.handleRangeOperator(emitterVisitor, node);
        }
    }
}
