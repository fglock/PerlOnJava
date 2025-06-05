package org.perlonjava.codegen;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.*;
import org.perlonjava.operators.OperatorHandler;
import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeContextType;

import static org.perlonjava.codegen.EmitOperator.emitOperator;

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
     * @param node The operator node to process
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

            // Built-in functions requiring special handling
            case "time", "times", "wantarray", "undef",
                 "gmtime", "localtime", "caller", "reset", "select",
                 "prototype", "require", "stat", "lstat" ->
                    handleUnaryBuiltin(emitterVisitor, node, operator);

            // Unary operators
            case "unaryMinus" -> handleUnaryDefaultCase(node, "unaryMinus", emitterVisitor);
            case "~" -> handleUnaryDefaultCase(node, "bitwiseNot", emitterVisitor);
            case "binary~" -> handleUnaryDefaultCase(node, "bitwiseNotBinary", emitterVisitor);
            case "~." -> handleUnaryDefaultCase(node, "bitwiseNotDot", emitterVisitor);
            case "!", "not" -> handleUnaryDefaultCase(node, "not", emitterVisitor);
            case "int" -> handleUnaryDefaultCase(node, "integer", emitterVisitor);

            // Auto-increment/decrement operators
            case "++" -> handleUnaryDefaultCase(node, "preAutoIncrement", emitterVisitor);
            case "--" -> handleUnaryDefaultCase(node, "preAutoDecrement", emitterVisitor);
            case "++postfix" -> handleUnaryDefaultCase(node, "postAutoIncrement", emitterVisitor);
            case "--postfix" -> handleUnaryDefaultCase(node, "postAutoDecrement", emitterVisitor);

            // Standard unary functions
            case "abs", "chdir", "chr", "closedir", "cos",
                 "defined", "doFile", "exit", "exp", "fc",
                 "hex", "lc", "lcfirst", "length", "log",
                 "oct", "ord", "pos", "quotemeta", "rand", "ref",
                 "rewinddir", "rmdir", "sin", "sleep", "sqrt",
                 "srand", "study", "telldir", "uc", "ucfirst" ->
                    handleUnaryDefaultCase(node, operator, emitterVisitor);

            // Miscellaneous operators
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
            case "\\" -> handleCreateReference(emitterVisitor, node);
            case "$#" -> handleArrayUnaryBuiltin(emitterVisitor,
                    new OperatorNode("$#", new OperatorNode("@", node.operand, node.tokenIndex), node.tokenIndex),
                    "indexLastElem");

            // Error handling
            case "die", "warn" -> EmitOperator.handleDieBuiltin(emitterVisitor, node);

            // Array operations
            case "reverse", "unlink" -> EmitOperator.handleReverseBuiltin(emitterVisitor, node);
            case "splice" -> EmitOperator.handleSpliceBuiltin(emitterVisitor, node);
            case "pop", "shift" -> handleArrayUnaryBuiltin(emitterVisitor, node, operator);

            // Regular expression operations
            case "matchRegex" -> EmitRegex.handleMatchRegex(emitterVisitor, node);
            case "quoteRegex" -> EmitRegex.handleQuoteRegex(emitterVisitor, node);
            case "replaceRegex" -> EmitRegex.handleReplaceRegex(emitterVisitor, node);
            case "tr", "y" -> EmitRegex.handleTransliterate(emitterVisitor, node);
            case "qx" -> EmitRegex.handleSystemCommand(emitterVisitor, node);

            default -> {
                // Handle file test operators (-f, -d, etc.)
                if (operator.length() == 2 && operator.startsWith("-")) {
                    EmitOperatorFileTest.handleFileTestBuiltin(emitterVisitor, node);
                    return;
                }
                throw new PerlCompilerException(node.tokenIndex,
                        "Not implemented: operator: " + operator,
                        emitterVisitor.ctx.errorUtil);
            }
        }
    }

    /**
     * Handles special unary builtin operators that require custom processing.
     *
     * @param emitterVisitor The visitor walking the AST
     * @param node The operator node
     * @param operator The operator string
     */
    static void handleUnaryBuiltin(EmitterVisitor emitterVisitor, OperatorNode node, String operator) {
        MethodVisitor mv = emitterVisitor.ctx.mv;

        switch (operator) {
            case "time", "times" -> {
                emitOperator(operator, emitterVisitor);
            }
            case "wantarray" -> {
                mv.visitVarInsn(Opcodes.ILOAD,
                        emitterVisitor.ctx.symbolTable.getVariableIndex("wantarray"));
                emitOperator("wantarray", emitterVisitor);
            }
            case "undef" -> {
                if (node.operand == null) {
                    emitOperator(operator, emitterVisitor);
                    return;
                }
                node.operand.accept(emitterVisitor.with(RuntimeContextType.RUNTIME));
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/RuntimeList",
                        "undefine",
                        "()Lorg/perlonjava/runtime/RuntimeList;",
                        false);
                if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
                    mv.visitInsn(Opcodes.POP);
                }
            }
            case "gmtime", "localtime", "caller", "reset", "select" -> {
                node.operand.accept(emitterVisitor.with(RuntimeContextType.LIST));
                emitterVisitor.pushCallContext();
                emitOperator(operator, emitterVisitor);
            }
            case "prototype" -> {
                node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                emitterVisitor.ctx.mv.visitLdcInsn(
                        emitterVisitor.ctx.symbolTable.getCurrentPackage());
                emitOperator(operator, emitterVisitor);
            }
            case "require" -> {
                node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                emitterVisitor.ctx.mv.visitLdcInsn(node.getBooleanAnnotation("module_true"));
                emitOperator(operator, emitterVisitor);
            }
            case "stat", "lstat" -> {
                if (node.operand instanceof IdentifierNode identNode &&
                        identNode.name.equals("_")) {
                    emitterVisitor.ctx.mv.visitMethodInsn(
                            Opcodes.INVOKESTATIC,
                            "org/perlonjava/operators/Stat",
                            operator + "LastHandle",
                            "()Lorg/perlonjava/runtime/RuntimeList;",
                            false);
                    if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
                        mv.visitInsn(Opcodes.POP);
                    }
                } else {
                    handleUnaryDefaultCase(node, operator, emitterVisitor);
                }
            }
            default -> handleUnaryDefaultCase(node, operator, emitterVisitor);
        }
    }

    /**
     * Handles standard unary operators with default processing logic.
     *
     * @param node The operator node
     * @param operator The operator string
     * @param emitterVisitor The visitor walking the AST
     */
    private static void handleUnaryDefaultCase(OperatorNode node, String operator,
                                               EmitterVisitor emitterVisitor) {
        MethodVisitor mv = emitterVisitor.ctx.mv;
        node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        OperatorHandler operatorHandler = OperatorHandler.get(operator);
        if (operatorHandler != null) {
            emitOperator(operator, emitterVisitor);
        } else {
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/RuntimeScalar",
                    operator,
                    "()Lorg/perlonjava/runtime/RuntimeScalar;",
                    false);
        }
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            mv.visitInsn(Opcodes.POP);
        }
    }

    /**
     * Handles array-specific unary builtin operators.
     *
     * @param emitterVisitor The visitor walking the AST
     * @param node The operator node
     * @param operator The operator string
     */
    static void handleArrayUnaryBuiltin(EmitterVisitor emitterVisitor, OperatorNode node,
                                        String operator) {
        Node operand = node.operand;
        emitterVisitor.ctx.logDebug("handleArrayUnaryBuiltin " + operand);
        if (operand instanceof ListNode listNode) {
            operand = listNode.elements.getFirst();
        }
        operand.accept(emitterVisitor.with(RuntimeContextType.LIST));
        emitOperator(operator, emitterVisitor);
    }

    /**
     * Handles creation of references (backslash operator).
     *
     * @param emitterVisitor The visitor walking the AST
     * @param node The operator node
     */
    static void handleCreateReference(EmitterVisitor emitterVisitor, OperatorNode node) {
        MethodVisitor mv = emitterVisitor.ctx.mv;
        if (node.operand instanceof ListNode) {
            node.operand.accept(emitterVisitor.with(RuntimeContextType.LIST));
            emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/RuntimeList",
                    "createListReference",
                    "()Lorg/perlonjava/runtime/RuntimeList;",
                    false);
            if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
                mv.visitInsn(Opcodes.POP);
            }
        } else {
            if (node.operand instanceof OperatorNode operatorNode &&
                    operatorNode.operator.equals("&")) {
                emitterVisitor.ctx.logDebug("Handle \\& " + operatorNode.operand);
                if (operatorNode.operand instanceof OperatorNode ||
                        operatorNode.operand instanceof BlockNode) {
                    operatorNode.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                    emitterVisitor.ctx.mv.visitLdcInsn(
                            emitterVisitor.ctx.symbolTable.getCurrentPackage());
                    emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                            "org/perlonjava/runtime/RuntimeCode",
                            "createCodeReference",
                            "(Lorg/perlonjava/runtime/RuntimeScalar;Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeScalar;",
                            false);
                } else {
                    node.operand.accept(emitterVisitor.with(RuntimeContextType.LIST));
                }
            } else {
                node.operand.accept(emitterVisitor.with(RuntimeContextType.LIST));
                emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                        "org/perlonjava/runtime/RuntimeDataProvider",
                        "createReference",
                        "()Lorg/perlonjava/runtime/RuntimeScalar;",
                        true);
            }
            if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
                mv.visitInsn(Opcodes.POP);
            }
        }
    }
}
