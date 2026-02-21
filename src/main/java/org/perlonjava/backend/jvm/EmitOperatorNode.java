package org.perlonjava.backend.jvm;

import org.perlonjava.astnode.OperatorNode;
import org.perlonjava.frontend.analysis.EmitterVisitor;
import org.perlonjava.perlmodule.Strict;
import org.perlonjava.runtime.runtimetypes.PerlCompilerException;

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
    public static void emitOperatorNode(EmitterVisitor emitterVisitor, OperatorNode node) {
        emitterVisitor.ctx.logDebug("visit(OperatorNode) " + node.operator + " in context " + emitterVisitor.ctx.contextType);

        switch (node.operator) {
            // Subroutine related
            case "__SUB__" -> EmitSubroutine.handleSelfCallOperator(emitterVisitor, node);
            case "package" -> EmitOperator.handlePackageOperator(emitterVisitor, node);

            // Variable access operators
            case "$", "@", "%", "*", "&" -> EmitVariable.handleVariableOperator(emitterVisitor, node);

            // Operations that take a list of operands
            case "keys", "values", "pack", "mkdir", "opendir", "seekdir", "crypt", "vec", "read", "chmod" ->
                    EmitOperator.handleOpWithList(emitterVisitor, node);

            case "each" -> EmitOperator.handleEach(emitterVisitor, node);

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
            case "~" -> {
                // Use integer bitwise NOT when "use integer" is in effect
                if (emitterVisitor.ctx.symbolTable.isStrictOptionEnabled(Strict.HINT_INTEGER)) {
                    EmitOperator.handleUnaryDefaultCase(node, "integerBitwiseNot", emitterVisitor);
                } else {
                    // Use the operator key "~" for OperatorHandler lookup
                    EmitOperator.handleUnaryDefaultCase(node, "~", emitterVisitor);
                }
            }
            case "binary~" -> EmitOperator.handleUnaryDefaultCase(node, "binary~", emitterVisitor);
            case "~." -> EmitOperator.handleUnaryDefaultCase(node, "~.", emitterVisitor);
            case "!", "not" -> EmitOperator.handleUnaryDefaultCase(node, "not", emitterVisitor);
            case "int" -> EmitOperator.handleUnaryDefaultCase(node, "int", emitterVisitor);

            // Auto-increment/decrement operators
            case "++" -> EmitOperator.handleUnaryDefaultCase(node, "preAutoIncrement", emitterVisitor);
            case "--" -> EmitOperator.handleUnaryDefaultCase(node, "preAutoDecrement", emitterVisitor);
            case "++postfix" -> EmitOperator.handleUnaryDefaultCase(node, "postAutoIncrement", emitterVisitor);
            case "--postfix" -> EmitOperator.handleUnaryDefaultCase(node, "postAutoDecrement", emitterVisitor);

            // Special case for length under "use bytes"
            case "length" -> EmitOperator.handleLengthOperator(node, emitterVisitor);

            // Special case for chr under "use bytes"
            case "chr" -> EmitOperator.handleChrOperator(node, emitterVisitor);

            // Special case for ord under "use bytes"
            case "ord" -> EmitOperator.handleOrdOperator(node, emitterVisitor);

            // Standard unary functions
            case "fc" -> EmitOperator.handleFcOperator(node, emitterVisitor);
            case "lc" -> EmitOperator.handleLcOperator(node, emitterVisitor);
            case "uc" -> EmitOperator.handleUcOperator(node, emitterVisitor);
            case "lcfirst" -> EmitOperator.handleLcfirstOperator(node, emitterVisitor);
            case "ucfirst" -> EmitOperator.handleUcfirstOperator(node, emitterVisitor);
            case "abs", "chdir", "closedir", "cos", "exit", "exp",
                 "hex", "log",
                 "oct", "pos", "quotemeta", "rand", "ref",
                 "rewinddir", "rmdir", "sin", "sleep", "sqrt",
                 "srand", "study", "telldir" ->
                    EmitOperator.handleUnaryDefaultCase(node, node.operator, emitterVisitor);

            // Miscellaneous operators
            case "time", "wait" -> EmitOperator.handleTimeOperator(emitterVisitor, node);
            case "wantarray" -> EmitOperator.handleWantArrayOperator(emitterVisitor, node);
            case "undef" -> EmitOperator.handleUndefOperator(emitterVisitor, node);
            case "gmtime", "localtime", "caller", "reset", "select", "times" ->
                    EmitOperator.handleTimeRelatedOperator(emitterVisitor, node);
            case "prototype" -> EmitOperator.handlePrototypeOperator(emitterVisitor, node);
            case "require" -> EmitOperator.handleRequireOperator(emitterVisitor, node);
            case "doFile" -> EmitOperator.handleDoFileOperator(emitterVisitor, node);
            case "stat", "lstat" -> EmitOperator.handleStatOperator(emitterVisitor, node, node.operator);
            case "+" -> EmitOperator.handleUnaryPlusOperator(emitterVisitor, node);
            case "<>" -> EmitOperator.handleDiamondBuiltin(emitterVisitor, node);
            case "chop", "chomp" -> EmitOperator.handleChompBuiltin(emitterVisitor, node);
            case "readdir" -> EmitOperator.handleReaddirOperator(emitterVisitor, node);
            case "glob" -> EmitOperator.handleGlobBuiltin(emitterVisitor, node);
            case "rindex", "index" -> EmitOperator.handleIndexBuiltin(emitterVisitor, node);
            case "atan2" -> EmitOperator.handleAtan2(emitterVisitor, node);
            case "scalar" -> EmitOperator.handleScalar(emitterVisitor, node);
            case "delete", "exists" -> EmitOperatorDeleteExists.handleDeleteExists(emitterVisitor, node);
            case "defined" -> EmitOperatorDeleteExists.handleDefined(node, node.operator, emitterVisitor);
            case "local" -> EmitOperatorLocal.handleLocal(emitterVisitor, node);
            case "\\" -> EmitOperator.handleCreateReference(emitterVisitor, node);
            case "$#" -> EmitOperator.handleArrayUnaryBuiltin(emitterVisitor,
                    new OperatorNode("$#", new OperatorNode("@", node.operand, node.tokenIndex), node.tokenIndex),
                    "indexLastElem");
            case "system", "exec" -> EmitOperator.handleSystemBuiltin(emitterVisitor, node);

            // Error handling
            case "die", "warn" -> EmitOperator.handleDieBuiltin(emitterVisitor, node);

            // Array operations
            case "splice" -> EmitOperator.handleSpliceBuiltin(emitterVisitor, node);
            case "pop", "shift" -> EmitOperator.handleArrayUnaryBuiltin(emitterVisitor, node, node.operator);

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

            case "HEREDOC" -> handleMissingHeredoc(node);

            default -> EmitOperator.handleOperator(emitterVisitor, node);
        }
    }

    private static void handleMissingHeredoc(OperatorNode node) {
        throw new PerlCompilerException("HEREDOC marker " + node.getAnnotation("identifier") + " not found");
    }
}
