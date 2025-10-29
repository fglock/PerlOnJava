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
    static void handleBinaryOperator(EmitterVisitor emitterVisitor, BinaryOperatorNode node, OperatorHandler operatorHandler) {
        EmitterVisitor scalarVisitor =
                emitterVisitor.with(RuntimeContextType.SCALAR); // execute operands in scalar context
        emitterVisitor.ctx.logDebug("handleBinaryOperator: " + node.toString());

        // Optimization
        if ((node.operator.equals("+")
                || node.operator.equals("-")
                || node.operator.equals("=="))
                && node.right instanceof NumberNode right) {
            String value = right.value;
            boolean isInteger = ScalarUtils.isInteger(value);
            if (isInteger) {
                node.left.accept(scalarVisitor); // target - left parameter
                int intValue = Integer.parseInt(value);
                emitterVisitor.ctx.mv.visitLdcInsn(intValue);
                emitterVisitor.ctx.mv.visitMethodInsn(
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
                // Use integer modulus when "use integer" is in effect
                node.left.accept(scalarVisitor); // left parameter
                right.accept(scalarVisitor); // right parameter
                emitterVisitor.ctx.mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "org/perlonjava/operators/MathOperators",
                        "integerModulus",
                        "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;",
                        false);
                EmitOperator.handleVoidContext(emitterVisitor);
                return;
            } else if (node.operator.equals("/")) {
                // Use integer division when "use integer" is in effect
                node.left.accept(scalarVisitor); // left parameter
                right.accept(scalarVisitor); // right parameter
                emitterVisitor.ctx.mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "org/perlonjava/operators/MathOperators",
                        "integerDivide",
                        "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;",
                        false);
                EmitOperator.handleVoidContext(emitterVisitor);
                return;
            } else if (node.operator.equals("<<")) {
                // Use integer left shift when "use integer" is in effect
                node.left.accept(scalarVisitor); // left parameter
                right.accept(scalarVisitor); // right parameter
                emitterVisitor.ctx.mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "org/perlonjava/operators/BitwiseOperators",
                        "integerShiftLeft",
                        "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;",
                        false);
                EmitOperator.handleVoidContext(emitterVisitor);
                return;
            } else if (node.operator.equals(">>")) {
                // Use integer right shift when "use integer" is in effect
                node.left.accept(scalarVisitor); // left parameter
                right.accept(scalarVisitor); // right parameter
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

        node.left.accept(scalarVisitor); // left parameter
        right.accept(scalarVisitor); // right parameter
        // stack: [left, right]
        emitOperator(node, emitterVisitor);
    }

    static void handleCompoundAssignment(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        // compound assignment operators like `+=`
        EmitterVisitor scalarVisitor =
                emitterVisitor.with(RuntimeContextType.SCALAR); // execute operands in scalar context
        node.left.accept(scalarVisitor); // target - left parameter
        emitterVisitor.ctx.mv.visitInsn(Opcodes.DUP);
        node.right.accept(scalarVisitor); // right parameter
        // stack: [left, left, right]
        // perform the operation
        String baseOperator = node.operator.substring(0, node.operator.length() - 1);
        // Create a BinaryOperatorNode for the base operation
        BinaryOperatorNode baseOpNode = new BinaryOperatorNode(
                baseOperator,
                node.left,
                node.right,
                node.tokenIndex
        );
        EmitOperator.emitOperator(baseOpNode, scalarVisitor);
        // assign to the Lvalue
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", "set", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
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
