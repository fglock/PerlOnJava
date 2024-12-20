package org.perlonjava.codegen;

import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.BinaryOperatorNode;
import org.perlonjava.operators.OperatorHandler;
import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeContextType;

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
            case "xor":
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
                String newOp = operator.substring(0, operator.length() - 1);
                OperatorHandler operatorHandler = OperatorHandler.get(newOp);
                if (operatorHandler != null) {
                    emitterVisitor.handleCompoundAssignment(node, operatorHandler);
                    return;
                }
                break;
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
        }

        OperatorHandler operatorHandler = OperatorHandler.get(operator);
        if (operatorHandler != null) {
            emitterVisitor.handleBinaryOperator(node, operatorHandler);
            return;
        }

        throw new PerlCompilerException(node.tokenIndex, "Unexpected infix operator: " + operator, emitterVisitor.ctx.errorUtil);
    }
}
