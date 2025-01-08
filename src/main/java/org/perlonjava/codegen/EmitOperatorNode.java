package org.perlonjava.codegen;

import org.perlonjava.astnode.OperatorNode;
import org.perlonjava.runtime.PerlCompilerException;

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
                EmitOperator.handleNextOperator(emitterVisitor.ctx, node);
                break;
            case "return":
                EmitOperator.handleReturnOperator(emitterVisitor, node);
                break;
            case "eval", "evalbytes":
                EmitEval.handleEvalOperator(emitterVisitor, node);
                break;
            case "unaryMinus":
                emitterVisitor.handleUnaryBuiltin(node, "unaryMinus");
                break;
            case "+":
                EmitOperator.handleUnaryPlusOperator(emitterVisitor, node);
                break;
            case "~":
                emitterVisitor.handleUnaryBuiltin(node, "bitwiseNot");
                break;
            case "binary~":
                emitterVisitor.handleUnaryBuiltin(node, "bitwiseNotBinary");
                break;
            case "~.":
                emitterVisitor.handleUnaryBuiltin(node, "bitwiseNotDot");
                break;
            case "!":
            case "not":
                emitterVisitor.handleUnaryBuiltin(node, "not");
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
                emitterVisitor.handleUnaryBuiltin(node, operator);
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
                emitterVisitor.handleUnaryBuiltin(node, "integer");
                break;
            case "++":
                emitterVisitor.handleUnaryBuiltin(node, "preAutoIncrement");
                break;
            case "--":
                emitterVisitor.handleUnaryBuiltin(node, "preAutoDecrement");
                break;
            case "++postfix":
                emitterVisitor.handleUnaryBuiltin(node, "postAutoIncrement");
                break;
            case "--postfix":
                emitterVisitor.handleUnaryBuiltin(node, "postAutoDecrement");
                break;
            case "\\":
                emitterVisitor.handleCreateReference(node);
                break;
            case "$#":
                node = new OperatorNode("$#", new OperatorNode("@", node.operand, node.tokenIndex), node.tokenIndex);
                emitterVisitor.handleArrayUnaryBuiltin(node, "indexLastElem");
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
                emitterVisitor.handleArrayUnaryBuiltin(node, operator);
                break;
            case "matchRegex", "quoteRegex", "replaceRegex", "tr", "y", "qx":
                EmitRegex.handleRegex(emitterVisitor, node);
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
}
