package org.perlonjava.backend.jvm;

import org.perlonjava.app.cli.CompilerOptions;

import org.objectweb.asm.Opcodes;
import org.perlonjava.frontend.analysis.EmitterVisitor;
import org.perlonjava.frontend.astnode.BinaryOperatorNode;
import org.perlonjava.runtime.operators.OperatorHandler;
import org.perlonjava.runtime.runtimetypes.PerlCompilerException;

public class EmitBinaryOperatorNode {

    public static void emitBinaryOperatorNode(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("visit(BinaryOperatorNode) %s in context %s"
                .formatted(node.operator, emitterVisitor.ctx.contextType));

        switch (node.operator) {
            // Logical operators with short-circuit evaluation
            case "||", "or" ->
                    EmitLogicalOperator.emitLogicalOperator(emitterVisitor, node, Opcodes.IFNE, "getBoolean");

            case "||=" -> EmitLogicalOperator.emitLogicalAssign(emitterVisitor, node, Opcodes.IFNE, "getBoolean");

            case "&&", "and" ->
                    EmitLogicalOperator.emitLogicalOperator(emitterVisitor, node, Opcodes.IFEQ, "getBoolean");

            case "&&=" -> EmitLogicalOperator.emitLogicalAssign(emitterVisitor, node, Opcodes.IFEQ, "getBoolean");

            case "//" ->
                    EmitLogicalOperator.emitLogicalOperator(emitterVisitor, node, Opcodes.IFNE, "getDefinedBoolean");

            case "//=" ->
                    EmitLogicalOperator.emitLogicalAssign(emitterVisitor, node, Opcodes.IFNE, "getDefinedBoolean");

            case "xor", "^^" -> EmitLogicalOperator.emitXorOperator(emitterVisitor, node);

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
            case "map", "sort", "grep", "all", "any" -> EmitOperator.handleMapOperator(emitterVisitor, node);

            // I/O operations
            case "eof", "printf", "print", "say" -> EmitOperator.handleSayOperator(emitterVisitor, node);

            case "close", "readline", "fileno", "getc", "tell" ->
                    EmitOperator.handleReadlineOperator(emitterVisitor, node);

            case "binmode", "seek", "sysseek" -> EmitOperator.handleBinmodeOperator(emitterVisitor, node);

            // String operations
            case "join", "sprintf" -> EmitOperator.handleSubstr(emitterVisitor, node);
            case "split" -> EmitOperator.handleSplit(emitterVisitor, node);

            case "x" -> EmitOperator.handleRepeat(emitterVisitor, node);

            // Regex operations
            case "!~" -> EmitRegex.handleNotBindRegex(emitterVisitor, node);
            case "=~" -> EmitRegex.handleBindRegex(emitterVisitor, node);

            // Compound assignment operators
            case "**=", "+=", "*=", "&=", "&.=", "binary&=", "<<=", "-=", "/=",
                 "|=", "|.=", "binary|=", ">>=", ".=", "%=", "^=", "^.=",
                 "binary^=", "x=", "^^=" -> EmitBinaryOperator.handleCompoundAssignment(emitterVisitor, node);

            // Range and flip-flop operators
            case "..." -> EmitLogicalOperator.emitFlipFlopOperator(emitterVisitor, node);

            case ".." -> EmitBinaryOperator.handleRangeOrFlipFlop(emitterVisitor, node);

            // Comparison operators (chained)
            case "<", ">", "<=", ">=", "lt", "gt", "le", "ge",
                 "==", "!=", "eq", "ne" -> EmitOperatorChained.emitChainedComparison(emitterVisitor, node);

            // Binary operators
            case "%", "&", "&.", "binary&", "*", "**", "+", "-", "/",
                 "<<", "<=>", ">>", "^", "^.", "binary^", "|", "|.", "binary|",
                 "bless", "cmp", "isa", "~~" -> {
                    // Check if uninitialized warnings are enabled at compile time
                    // Use warn variant for zero-overhead when warnings disabled
                    boolean warnUninit = emitterVisitor.ctx.symbolTable.isWarningCategoryEnabled("uninitialized");
                    OperatorHandler handler = warnUninit
                            ? OperatorHandler.getWarn(node.operator)
                            : OperatorHandler.get(node.operator);
                    EmitBinaryOperator.handleBinaryOperator(emitterVisitor, node, handler);
                }

            default -> throw new PerlCompilerException(node.tokenIndex,
                    "Not implemented operator: " + node.operator, emitterVisitor.ctx.errorUtil);
        }
    }
}