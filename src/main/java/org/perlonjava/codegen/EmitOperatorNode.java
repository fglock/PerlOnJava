package org.perlonjava.codegen;

import org.perlonjava.astnode.*;
import org.perlonjava.runtime.PerlCompilerException;

/**
 * Handles the bytecode emission for Perl operator nodes during compilation.
 * This class contains methods to process different types of Perl operators and
 * generate appropriate JVM bytecode.
 */
public class EmitOperatorNode {

    /**
     * Main entry point for emitting operator node bytecode.
     * Processes various Perl operators and delegates to specific handler methods.
     *
     * @param emitterVisitor The visitor that walks the AST
     * @param node           The operator node to process
     */
    static void emitOperatorNode(EmitterVisitor emitterVisitor, OperatorNode node) {
        String operator = node.operator;
        emitterVisitor.ctx.logDebug("visit(OperatorNode) " + operator + " in context " + emitterVisitor.ctx.contextType);

        switch (operator) {
            // Subroutine related
            case "__SUB__" -> EmitSubroutine.handleSelfCallOperator(emitterVisitor, node);
            case "package" -> EmitOperator.handlePackageOperator(emitterVisitor, node);

            // Variable access operators
            case "$", "@", "%", "*", "&" -> EmitVariable.handleVariableOperator(emitterVisitor, node);

            // Hash operations
            case "keys", "values" -> EmitOperator.handleKeysOperator(emitterVisitor, node);

            // Variable declarations
            case "our", "state", "my" -> EmitVariable.handleMyOperator(emitterVisitor, node);

            // Control flow
            case "next", "redo", "last" -> EmitControlFlow.handleNextOperator(emitterVisitor.ctx, node);
            case "return" -> EmitControlFlow.handleReturnOperator(emitterVisitor, node);
            case "goto" -> EmitControlFlow.handleGotoLabel(emitterVisitor, node);

            // Eval operations
            case "eval", "evalbytes" -> EmitEval.handleEvalOperator(emitterVisitor, node);

            // Unary operators
            case "unaryMinus" -> EmitOperator.handleUnaryDefaultCase(node, "unaryMinus", emitterVisitor);
            case "~" -> EmitOperator.handleUnaryDefaultCase(node, "bitwiseNot", emitterVisitor);
            case "binary~" -> EmitOperator.handleUnaryDefaultCase(node, "bitwiseNotBinary", emitterVisitor);
            case "~." -> EmitOperator.handleUnaryDefaultCase(node, "bitwiseNotDot", emitterVisitor);
            case "!", "not" -> EmitOperator.handleUnaryDefaultCase(node, "not", emitterVisitor);
            case "int" -> EmitOperator.handleUnaryDefaultCase(node, "integer", emitterVisitor);

            // Auto-increment/decrement operators
            case "++" -> EmitOperator.handleUnaryDefaultCase(node, "preAutoIncrement", emitterVisitor);
            case "--" -> EmitOperator.handleUnaryDefaultCase(node, "preAutoDecrement", emitterVisitor);
            case "++postfix" -> EmitOperator.handleUnaryDefaultCase(node, "postAutoIncrement", emitterVisitor);
            case "--postfix" -> EmitOperator.handleUnaryDefaultCase(node, "postAutoDecrement", emitterVisitor);

            // Standard unary functions
            case "abs", "chdir", "chr", "closedir", "cos",
                 "defined", "doFile", "exit", "exp", "fc",
                 "hex", "lc", "lcfirst", "length", "log",
                 "oct", "ord", "pos", "quotemeta", "rand", "ref",
                 "rewinddir", "rmdir", "sin", "sleep", "sqrt",
                 "srand", "study", "telldir", "uc", "ucfirst" -> EmitOperator.handleUnaryDefaultCase(node, operator, emitterVisitor);

            // Miscellaneous operators
            case "time", "times" -> EmitOperator.handleTimeOperator(emitterVisitor, operator);
            case "wantarray" -> EmitOperator.handleWantArrayOperator(emitterVisitor);
            case "undef" -> EmitOperator.handleUndefOperator(emitterVisitor, node, operator);
            case "gmtime", "localtime", "caller", "reset", "select" ->
                    EmitOperator.handleTimeRelatedOperator(emitterVisitor, node, operator);
            case "prototype" -> EmitOperator.handlePrototypeOperator(emitterVisitor, node);
            case "require" -> EmitOperator.handleRequireOperator(emitterVisitor, node);
            case "stat", "lstat" -> EmitOperator.handleStatOperator(emitterVisitor, node, operator);
            case "+" -> EmitOperator.handleUnaryPlusOperator(emitterVisitor, node);
            case "<>" -> EmitOperator.handleDiamondBuiltin(emitterVisitor, node);
            case "chop", "chomp" -> EmitOperator.handleChompBuiltin(emitterVisitor, node);
            case "readdir" -> EmitOperator.handleReaddirOperator(emitterVisitor, node);
            case "glob" -> EmitOperator.handleGlobBuiltin(emitterVisitor, node);
            case "rindex", "index" -> EmitOperator.handleIndexBuiltin(emitterVisitor, node);
            case "pack", "unpack", "mkdir", "opendir", "seekdir", "crypt", "vec", "each" ->
                    EmitOperator.handleVecBuiltin(emitterVisitor, node);
            case "atan2" -> EmitOperator.handleAtan2(emitterVisitor, node);
            case "scalar" -> EmitOperator.handleScalar(emitterVisitor, node);
            case "delete", "exists" -> EmitOperator.handleDeleteExists(emitterVisitor, node);
            case "local" -> EmitOperator.handleLocal(emitterVisitor, node);
            case "\\" -> EmitOperator.handleCreateReference(emitterVisitor, node);
            case "$#" -> EmitOperator.handleArrayUnaryBuiltin(emitterVisitor,
                    new OperatorNode("$#", new OperatorNode("@", node.operand, node.tokenIndex), node.tokenIndex),
                    "indexLastElem");

            // Error handling
            case "die", "warn" -> EmitOperator.handleDieBuiltin(emitterVisitor, node);

            // Array operations
            case "reverse", "unlink" -> EmitOperator.handleReverseBuiltin(emitterVisitor, node);
            case "splice" -> EmitOperator.handleSpliceBuiltin(emitterVisitor, node);
            case "pop", "shift" -> EmitOperator.handleArrayUnaryBuiltin(emitterVisitor, node, operator);

            // Regular expression operations
            case "matchRegex" -> EmitRegex.handleMatchRegex(emitterVisitor, node);
            case "quoteRegex" -> EmitRegex.handleQuoteRegex(emitterVisitor, node);
            case "replaceRegex" -> EmitRegex.handleReplaceRegex(emitterVisitor, node);
            case "tr", "y" -> EmitRegex.handleTransliterate(emitterVisitor, node);
            case "qx" -> EmitRegex.handleSystemCommand(emitterVisitor, node);

            // File test operators
            case "-r", "-w", "-x", "-o",
                 "-R", "-W", "-X", "-O",
                 "-e", "-z", "-s",
                 "-f", "-d", "-l", "-p", "-S", "-b", "-c", "-t",
                 "-u", "-g", "-k",
                 "-T", "-B",
                 "-M", "-A", "-C" -> EmitOperatorFileTest.handleFileTestBuiltin(emitterVisitor, node);

            default -> throw new PerlCompilerException(node.tokenIndex,
                        "Not implemented: operator: " + operator,
                        emitterVisitor.ctx.errorUtil);
        }
    }
}
