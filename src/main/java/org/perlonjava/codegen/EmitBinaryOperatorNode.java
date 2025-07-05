package org.perlonjava.codegen;

import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.BinaryOperatorNode;
import org.perlonjava.astvisitor.EmitterVisitor;
import org.perlonjava.operators.OperatorHandler;
import org.perlonjava.runtime.PerlCompilerException;

public class EmitBinaryOperatorNode {

    public static void emitBinaryOperatorNode(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        emitterVisitor.ctx.logDebug("visit(BinaryOperatorNode) %s in context %s"
                .formatted(node.operator, emitterVisitor.ctx.contextType));

        switch (node.operator) {
            // Logical operators with short-circuit evaluation
            case "||", "or" ->
                    EmitLogicalOperator.emitLogicalOperator(emitterVisitor, node, Opcodes.IFNE, "getBoolean");

            case "||=" ->
                    EmitLogicalOperator.emitLogicalAssign(emitterVisitor, node, Opcodes.IFNE, "getBoolean");

            case "&&", "and" ->
                    EmitLogicalOperator.emitLogicalOperator(emitterVisitor, node, Opcodes.IFEQ, "getBoolean");

            case "&&=" ->
                    EmitLogicalOperator.emitLogicalAssign(emitterVisitor, node, Opcodes.IFEQ, "getBoolean");

            case "//" ->
                    EmitLogicalOperator.emitLogicalOperator(emitterVisitor, node, Opcodes.IFNE, "getDefinedBoolean");

            case "//=" ->
                    EmitLogicalOperator.emitLogicalAssign(emitterVisitor, node, Opcodes.IFNE, "getDefinedBoolean");

            // Assignment operator
            case "=" -> EmitVariable.handleAssignOperator(emitterVisitor, node);

            // String concatenation
            case "." -> EmitOperator.handleConcatOperator(emitterVisitor, node);

            // Dereference operators
            case "->" -> Dereference.handleArrowOperator(emitterVisitor, node);
            case "[" -> Dereference.handleArrayElementOperator(emitterVisitor, node, "get");
            case "{" -> Dereference.handleHashElementOperator(emitterVisitor, node, "get");
            case "(" -> EmitSubroutine.handleApplyOperator(emitterVisitor, node);

            // Array manipulation
            case "push", "unshift" -> EmitOperator.handlePushOperator(emitterVisitor, node);

            // Higher-order functions
            case "map", "sort", "grep" -> EmitOperator.handleMapOperator(emitterVisitor, node);

            // I/O operations
            case "eof", "open", "printf", "print", "say" ->
                    EmitOperator.handleSayOperator(emitterVisitor, node);

            case "close", "readline", "fileno", "getc", "tell" ->
                    EmitOperator.handleReadlineOperator(emitterVisitor, node);

            case "truncate" ->
                    EmitOperator.handleTruncateOperator(emitterVisitor, node);

            case "binmode", "seek" ->
                    EmitOperator.handleBinmodeOperator(emitterVisitor, node);

            // String operations
            case "join", "split", "sprintf", "substr" ->
                    EmitOperator.handleSubstr(emitterVisitor, node);

            case "x" -> EmitOperator.handleRepeat(emitterVisitor, node);

            // Regex operations
            case "!~" -> EmitRegex.handleNotBindRegex(emitterVisitor, node);
            case "=~" -> EmitRegex.handleBindRegex(emitterVisitor, node);

            // Compound assignment operators
            case "**=", "+=", "*=", "&=", "&.=", "binary&=", "<<=", "-=", "/=",
                 "|=", "|.=", "binary|=", ">>=", ".=", "%=", "^=", "^.=",
                 "binary^=", "x=", "^^=" ->
                    EmitBinaryOperator.handleCompoundAssignment(emitterVisitor, node);

            // Range and flip-flop operators
            case "..." -> EmitLogicalOperator.emitFlipFlopOperator(emitterVisitor, node);

            case ".." -> EmitBinaryOperator.handleRangeOrFlipFlop(emitterVisitor, node);

            // Comparison operators (chained)
            case "<", ">", "<=", ">=", "lt", "gt", "le", "ge",
                 "==", "!=", "eq", "ne" ->
                    EmitOperatorChained.emitChainedComparison(emitterVisitor, node);

            // Binary operators
            case "%", "&", "&.", "*", "**", "+", "-", "/", "^^", "xor",
                 "<<", "<=>", ">>", "^", "^.", "|", "|.",
                 "bless", "cmp", "isa" ->
                    EmitBinaryOperator.handleBinaryOperator(emitterVisitor, node,
                            OperatorHandler.get(node.operator));

            default -> throw new PerlCompilerException(node.tokenIndex,
                    "Unexpected infix operator: " + node.operator, emitterVisitor.ctx.errorUtil);
        }
    }
}