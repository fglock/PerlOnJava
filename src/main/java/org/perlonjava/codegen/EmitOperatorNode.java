package org.perlonjava.codegen;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.*;
import org.perlonjava.operators.OperatorHandler;
import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeContextType;

import static org.perlonjava.codegen.EmitOperator.emitOperator;

public class EmitOperatorNode {
    static void emitOperatorNode(EmitterVisitor emitterVisitor, OperatorNode node) {
        String operator = node.operator;
        emitterVisitor.ctx.logDebug("visit(OperatorNode) " + operator + " in context " + emitterVisitor.ctx.contextType);

        switch (operator) {
            case "__SUB__":
                EmitSubroutine.handleSelfCallOperator(emitterVisitor, node);
                break;
            case "package":
                EmitOperator.handlePackageOperator(emitterVisitor, node);
                break;
            case "$", "@", "%", "*", "&":
                EmitVariable.handleVariableOperator(emitterVisitor, node);
                break;
            case "keys":
            case "values":
                EmitOperator.handleKeysOperator(emitterVisitor, node);
                break;
            case "our":
            case "state":
            case "my":
                EmitVariable.handleMyOperator(emitterVisitor, node);
                break;
            case "next":
            case "redo":
            case "last":
                EmitControlFlow.handleNextOperator(emitterVisitor.ctx, node);
                break;
            case "return":
                EmitControlFlow.handleReturnOperator(emitterVisitor, node);
                break;
            case "goto":
                EmitControlFlow.handleGotoLabel(emitterVisitor, node);
                break;
            case "eval", "evalbytes":
                EmitEval.handleEvalOperator(emitterVisitor, node);
                break;
            case "unaryMinus":
                handleUnaryBuiltin(emitterVisitor, node, "unaryMinus");
                break;
            case "+":
                EmitOperator.handleUnaryPlusOperator(emitterVisitor, node);
                break;
            case "~":
                handleUnaryBuiltin(emitterVisitor, node, "bitwiseNot");
                break;
            case "binary~":
                handleUnaryBuiltin(emitterVisitor, node, "bitwiseNotBinary");
                break;
            case "~.":
                handleUnaryBuiltin(emitterVisitor, node, "bitwiseNotDot");
                break;
            case "!":
            case "not":
                handleUnaryBuiltin(emitterVisitor, node, "not");
                break;
            case "<>":
                EmitOperator.handleDiamondBuiltin(emitterVisitor, node);
                break;
            case "abs", "caller", "chdir", "chr", "closedir", "cos", "defined", "doFile", "exit",
                 "exp", "fc", "gmtime", "hex", "lc", "lcfirst", "length", "localtime", "log",
                 "lstat", "oct", "ord", "pos", "prototype", "quotemeta", "rand", "ref",
                 "require", "reset", "rewinddir", "rmdir", "select", "sin", "sleep", "sqrt",
                 "srand", "stat", "study", "telldir", "time", "times", "uc", "ucfirst", "undef",
                 "wantarray":
                handleUnaryBuiltin(emitterVisitor, node, operator);
                break;
            case "chop":
            case "chomp":
                EmitOperator.handleChompBuiltin(emitterVisitor, node);
                break;
            case "readdir":
                EmitOperator.handleReaddirOperator(emitterVisitor, node);
                break;
            case "glob":
                EmitOperator.handleGlobBuiltin(emitterVisitor, node);
                break;
            case "rindex":
            case "index":
                EmitOperator.handleIndexBuiltin(emitterVisitor, node);
                break;
            case "pack", "unpack", "mkdir", "opendir", "seekdir", "crypt", "vec", "each":
                EmitOperator.handleVecBuiltin(emitterVisitor, node);
                break;
            case "atan2":
                EmitOperator.handleAtan2(emitterVisitor, node);
                break;
            case "scalar":
                EmitOperator.handleScalar(emitterVisitor, node);
                break;
            case "delete":
            case "exists":
                EmitOperator.handleDeleteExists(emitterVisitor, node);
                break;
            case "local":
                EmitOperator.handleLocal(emitterVisitor, node);
                break;
            case "int":
                handleUnaryBuiltin(emitterVisitor, node, "integer");
                break;
            case "++":
                handleUnaryBuiltin(emitterVisitor, node, "preAutoIncrement");
                break;
            case "--":
                handleUnaryBuiltin(emitterVisitor, node, "preAutoDecrement");
                break;
            case "++postfix":
                handleUnaryBuiltin(emitterVisitor, node, "postAutoIncrement");
                break;
            case "--postfix":
                handleUnaryBuiltin(emitterVisitor, node, "postAutoDecrement");
                break;
            case "\\":
                handleCreateReference(emitterVisitor, node);
                break;
            case "$#":
                node = new OperatorNode("$#", new OperatorNode("@", node.operand, node.tokenIndex), node.tokenIndex);
                handleArrayUnaryBuiltin(emitterVisitor, node, "indexLastElem");
                break;
            case "die":
            case "warn":
                EmitOperator.handleDieBuiltin(emitterVisitor, node);
                break;
            case "reverse":
            case "unlink":
                EmitOperator.handleReverseBuiltin(emitterVisitor, node);
                break;
            case "splice":
                EmitOperator.handleSpliceBuiltin(emitterVisitor, node);
                break;
            case "pop", "shift":
                handleArrayUnaryBuiltin(emitterVisitor, node, operator);
                break;
            case "matchRegex":
                EmitRegex.handleMatchRegex(emitterVisitor, node);
                break;
            case "quoteRegex":
                EmitRegex.handleQuoteRegex(emitterVisitor, node);
                break;
            case "replaceRegex":
                EmitRegex.handleReplaceRegex(emitterVisitor, node);
                break;
            case "tr", "y":
                EmitRegex.handleTransliterate(emitterVisitor, node);
                break;
            case "qx":
                EmitRegex.handleSystemCommand(emitterVisitor, node);
                break;
            default:
                if (operator.length() == 2 && operator.startsWith("-")) {
                    // -d -e -f
                    EmitOperatorFileTest.handleFileTestBuiltin(emitterVisitor, node);
                    return;
                }
                throw new PerlCompilerException(node.tokenIndex, "Not implemented: operator: " + operator, emitterVisitor.ctx.errorUtil);
        }
    }

    /**
     * Emits a call to a unary built-in method on the RuntimeScalar class.
     *
     * @param emitterVisitor
     * @param operator       The name of the built-in method to call.
     */
    static void handleUnaryBuiltin(EmitterVisitor emitterVisitor, OperatorNode node, String operator) {
        MethodVisitor mv = emitterVisitor.ctx.mv;

        if (node.operand == null) {
            // Unary operator with no arguments, or with optional arguments called without arguments
            // example: undef()  wantarray()  time()  times()
            if (operator.equals("wantarray")) {
                // Retrieve wantarray value from JVM local vars
                mv.visitVarInsn(Opcodes.ILOAD, emitterVisitor.ctx.symbolTable.getVariableIndex("wantarray"));
            }
            emitOperator(operator, emitterVisitor);
            return;
        }

        switch (operator) {
            case "undef":
                operator = "undefine";
                node.operand.accept(emitterVisitor.with(RuntimeContextType.RUNTIME));
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeList", operator, "()Lorg/perlonjava/runtime/RuntimeList;", false);
                if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
                    mv.visitInsn(Opcodes.POP);
                }
                return;

            case "gmtime":
            case "localtime":
            case "caller":
            case "reset":
            case "select":
                node.operand.accept(emitterVisitor.with(RuntimeContextType.LIST));
                emitterVisitor.pushCallContext();
                emitOperator(operator, emitterVisitor);
                return;

            case "prototype":
                node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                emitterVisitor.ctx.mv.visitLdcInsn(emitterVisitor.ctx.symbolTable.getCurrentPackage());
                emitOperator(operator, emitterVisitor);
                return;

            case "require":
                node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                emitterVisitor.ctx.mv.visitLdcInsn(node.getBooleanAnnotation("module_true"));
                emitOperator(operator, emitterVisitor);
                return;

            case "stat":
            case "lstat":
                if (node.operand instanceof IdentifierNode && ((IdentifierNode) node.operand).name.equals("_")) {
                    emitterVisitor.ctx.mv.visitMethodInsn(
                            Opcodes.INVOKESTATIC,
                            "org/perlonjava/operators/Stat",
                            operator + "LastHandle",
                            "()Lorg/perlonjava/runtime/RuntimeList;", false);
                    if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
                        mv.visitInsn(Opcodes.POP);
                    }
                } else {
                    handleUnaryDefaultCase(node, operator, emitterVisitor, mv);
                }
                return;

            default:
                handleUnaryDefaultCase(node, operator, emitterVisitor, mv);
        }
    }

    private static void handleUnaryDefaultCase(OperatorNode node, String operator, EmitterVisitor emitterVisitor,
                                               MethodVisitor mv) {
        node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        OperatorHandler operatorHandler = OperatorHandler.get(operator);
        if (operatorHandler != null) {
            emitOperator(operator, emitterVisitor);
        } else {
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar",
                    operator, "()Lorg/perlonjava/runtime/RuntimeScalar;", false);
        }
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            mv.visitInsn(Opcodes.POP);
        }
    }

    static void handleArrayUnaryBuiltin(EmitterVisitor emitterVisitor, OperatorNode node, String operator) {
        // Handle:  $#array  $#$array_ref  shift @array  pop @array
        Node operand = node.operand;
        emitterVisitor.ctx.logDebug("handleArrayUnaryBuiltin " + operand);
        if (operand instanceof ListNode) {
            operand = ((ListNode) operand).elements.getFirst();
        }
        operand.accept(emitterVisitor.with(RuntimeContextType.LIST));
        emitOperator(operator, emitterVisitor);
    }

    static void handleCreateReference(EmitterVisitor emitterVisitor, OperatorNode node) {
        MethodVisitor mv = emitterVisitor.ctx.mv;
        if (node.operand instanceof ListNode) {
            // operand is a list:  `\(1,2,3)`
            node.operand.accept(emitterVisitor.with(RuntimeContextType.LIST));
            emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/RuntimeList",
                    "createListReference",
                    "()Lorg/perlonjava/runtime/RuntimeList;", false);
            if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
                mv.visitInsn(Opcodes.POP);
            }
        } else {
            if (node.operand instanceof OperatorNode operatorNode && operatorNode.operator.equals("&")) {
                emitterVisitor.ctx.logDebug("Handle \\& " + operatorNode.operand);
                if (operatorNode.operand instanceof OperatorNode || operatorNode.operand instanceof BlockNode) {
                    // \&$a or \&{$a}
                    operatorNode.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                    emitterVisitor.ctx.mv.visitLdcInsn(emitterVisitor.ctx.symbolTable.getCurrentPackage());
                    emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                            "org/perlonjava/runtime/RuntimeCode",
                            "createCodeReference",
                            "(Lorg/perlonjava/runtime/RuntimeScalar;Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeScalar;",
                            false);
                } else {
                    // assume \&var, which is already a reference
                    node.operand.accept(emitterVisitor.with(RuntimeContextType.LIST));
                }
            } else {
                node.operand.accept(emitterVisitor.with(RuntimeContextType.LIST));
                emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "createReference", "()Lorg/perlonjava/runtime/RuntimeScalar;", true);
            }
            if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
                mv.visitInsn(Opcodes.POP);
            }
        }
    }
}
