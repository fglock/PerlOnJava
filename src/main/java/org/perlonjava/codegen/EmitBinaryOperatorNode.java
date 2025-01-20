package org.perlonjava.codegen;

import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.BinaryOperatorNode;
import org.perlonjava.astnode.NumberNode;
import org.perlonjava.operators.OperatorHandler;
import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeContextType;
import org.perlonjava.runtime.ScalarUtils;

import static org.perlonjava.codegen.EmitOperator.emitOperator;

public class EmitBinaryOperatorNode {
    static void emitBinaryOperatorNode(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        String operator = node.operator;
        emitterVisitor.ctx.logDebug("visit(BinaryOperatorNode) " + operator + " in context " + emitterVisitor.ctx.contextType);

        switch (operator) { // handle operators that support short-circuit or other special cases
            case "||":
            case "or":
                EmitLogicalOperator.emitLogicalOperator(emitterVisitor, node, Opcodes.IFNE, "getBoolean");
                return;
            case "||=":
                EmitLogicalOperator.emitLogicalAssign(emitterVisitor, node, Opcodes.IFNE, "getBoolean");
                return;
            case "&&":
            case "and":
                EmitLogicalOperator.emitLogicalOperator(emitterVisitor, node, Opcodes.IFEQ, "getBoolean");
                return;
            case "xor", "^^":
                EmitLogicalOperator.emitXorOperator(emitterVisitor, node);
                return;
            case "&&=":
                EmitLogicalOperator.emitLogicalAssign(emitterVisitor, node, Opcodes.IFEQ, "getBoolean");
                return;
            case "//":
                EmitLogicalOperator.emitLogicalOperator(emitterVisitor, node, Opcodes.IFNE, "getDefinedBoolean");
                return;
            case "//=":
                EmitLogicalOperator.emitLogicalAssign(emitterVisitor, node, Opcodes.IFNE, "getDefinedBoolean");
                return;
            case "=":
                EmitVariable.handleAssignOperator(emitterVisitor, node);
                return;
            case ".":
                EmitOperator.handleConcatOperator(emitterVisitor, node);
                return;
            case "->":
                Dereference.handleArrowOperator(emitterVisitor, node);
                return;
            case "[":
                Dereference.handleArrayElementOperator(emitterVisitor, node);
                return;
            case "{":
                Dereference.handleHashElementOperator(emitterVisitor, node, "get");
                return;
            case "(":
                EmitSubroutine.handleApplyOperator(emitterVisitor, node);
                return;
            case "push":
            case "unshift":
                EmitOperator.handlePushOperator(emitterVisitor, node);
                return;
            case "map":
            case "sort":
            case "grep":
                EmitOperator.handleMapOperator(emitterVisitor, node);
                return;
            case "eof", "open", "printf", "print", "say":
                EmitOperator.handleSayOperator(emitterVisitor, node);
                return;
            case "close", "readline", "fileno", "getc", "truncate":
                EmitOperator.handleReadlineOperator(emitterVisitor, node);
                return;
            case "join", "split", "sprintf", "substr":
                EmitOperator.handleSubstr(emitterVisitor, node);
                return;
            case "x":
                EmitOperator.handleRepeat(emitterVisitor, node);
                return;
            case "!~":
                EmitRegex.handleNotBindRegex(emitterVisitor, node);
                return;
            case "=~":
                EmitRegex.handleBindRegex(emitterVisitor, node);
                return;
            case "**=", "+=", "*=", "&=", "&.=", "binary&=", "<<=", "-=", "/=", "|=", "|.=",
                 "binary|=", ">>=", ".=", "%=", "^=", "^.=", "binary^=", "x=":
                handleCompoundAssignment(emitterVisitor, node);
                return;
            case "...":
                EmitLogicalOperator.emitFlipFlopOperator(emitterVisitor, node);
                return;
            case "..":
                if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR) {
                    EmitLogicalOperator.emitFlipFlopOperator(emitterVisitor, node);
                    return;
                }
                EmitOperator.handleRangeOperator(emitterVisitor, node);
                return;
            case "<", ">", "<=", ">=", "lt", "gt", "le", "ge",
                 "==", "!=", "eq", "ne":
                EmitOperatorChained.emitChainedComparison(emitterVisitor, node);
                return;
        }

        OperatorHandler operatorHandler = OperatorHandler.get(operator);
        if (operatorHandler != null) {
            handleBinaryOperator(emitterVisitor, node, operatorHandler);
            return;
        }

        throw new PerlCompilerException(node.tokenIndex, "Unexpected infix operator: " + operator, emitterVisitor.ctx.errorUtil);
    }

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
                        operatorHandler.getMethodType(),
                        operatorHandler.getClassName(),
                        operatorHandler.getMethodName(),
                        operatorHandler.getDescriptorWithIntParameter(),
                        false);
                if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
                    emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
                }
                return;
            }
        }

        node.left.accept(scalarVisitor); // left parameter
        node.right.accept(scalarVisitor); // right parameter
        // stack: [left, right]
        emitOperator(node.operator, emitterVisitor);
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
        emitOperator(baseOperator, scalarVisitor);
        // assign to the Lvalue
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", "set", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }
}
