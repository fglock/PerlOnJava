package org.perlonjava.codegen;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.*;
import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeContextType;
import org.perlonjava.runtime.ScalarGlobOperator;

public class EmitOperator {
    static void handleReaddirOperator(EmitterVisitor emitterVisitor, OperatorNode node) {
        String operator = node.operator;
        node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        emitterVisitor.pushCallContext();
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/Operator",
                operator,
                "(Lorg/perlonjava/runtime/RuntimeScalar;I)Lorg/perlonjava/runtime/RuntimeDataProvider;", false);
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    static void handleMkdirOperator(EmitterVisitor emitterVisitor, OperatorNode node) {
        String operator = node.operator;
        node.operand.accept(emitterVisitor.with(RuntimeContextType.LIST));
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/Operator",
                operator,
                "(Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    static void handleEachOperator(EmitterVisitor emitterVisitor, OperatorNode node) {
        String operator = node.operator;
        node.operand.accept(emitterVisitor.with(RuntimeContextType.LIST));
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", operator, "()Lorg/perlonjava/runtime/RuntimeList;", true);
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    static void handleKeysOperator(EmitterVisitor emitterVisitor, OperatorNode node) {
        String operator = node.operator;
        node.operand.accept(emitterVisitor.with(RuntimeContextType.LIST));
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", operator, "()Lorg/perlonjava/runtime/RuntimeArray;", true);
        if (emitterVisitor.ctx.contextType == RuntimeContextType.LIST) {
        } else if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR) {
            emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "scalar", "()Lorg/perlonjava/runtime/RuntimeScalar;", true);
        } else if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    static void handleReadlineOperator(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        String operator = node.operator;

        // Emit the File Handle
        if (node.left instanceof OperatorNode) {
            // my $fh  $fh
            node.left.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        } else {
            emitterVisitor.emitFileHandle(node.left);
        }

        if (operator.equals("readline")) {
            emitterVisitor.pushCallContext();  // SCALAR or LIST context
            emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/Operator", operator, "(Lorg/perlonjava/runtime/RuntimeScalar;I)Lorg/perlonjava/runtime/RuntimeDataProvider;", false);
        } else {
            emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/Operator", operator, "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        }

        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    static void handleSayOperator(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        String operator = node.operator;
        // Emit the argument list
        node.right.accept(emitterVisitor.with(RuntimeContextType.LIST));

        // Emit the File Handle
        if (node.left instanceof OperatorNode) {
            // my $fh  $fh
            node.left.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        } else {
            emitterVisitor.emitFileHandle(node.left);
        }

        // Call the operator, return Scalar
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/Operator", operator, "(Lorg/perlonjava/runtime/RuntimeList;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    static void handleUnaryPlusOperator(EmitterVisitor emitterVisitor, OperatorNode node) {
        node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    static void handleIndexBuiltin(EmitterVisitor emitterVisitor, OperatorNode node) {
        MethodVisitor mv = emitterVisitor.ctx.mv;
        EmitterVisitor scalarVisitor = emitterVisitor.with(RuntimeContextType.SCALAR);
        if (node.operand instanceof ListNode) {
            ListNode operand = (ListNode) node.operand;
            if (!operand.elements.isEmpty()) {
                operand.elements.get(0).accept(scalarVisitor);
                operand.elements.get(1).accept(scalarVisitor);
                if (operand.elements.size() == 3) {
                    operand.elements.get(2).accept(scalarVisitor);
                } else {
                    new OperatorNode("undef", null, node.tokenIndex).accept(scalarVisitor);
                }
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/RuntimeScalar",
                        node.operator,
                        "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
                if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
                    mv.visitInsn(Opcodes.POP);
                }
                return;
            }
        }
        throw new PerlCompilerException(node.tokenIndex, "Not implemented: operator: " + node.operator, emitterVisitor.ctx.errorUtil);
    }

    static void handleAtan2(EmitterVisitor emitterVisitor, OperatorNode node) {
        MethodVisitor mv = emitterVisitor.ctx.mv;
        EmitterVisitor scalarVisitor = emitterVisitor.with(RuntimeContextType.SCALAR);
        if (node.operand instanceof ListNode) {
            ListNode operand = (ListNode) node.operand;
            if (operand.elements.size() == 2) {
                operand.elements.get(0).accept(scalarVisitor);
                operand.elements.get(1).accept(scalarVisitor);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/RuntimeScalar",
                        node.operator,
                        "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
                if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
                    mv.visitInsn(Opcodes.POP);
                }
                return;
            }
        }
        throw new PerlCompilerException(node.tokenIndex, "Not implemented: operator: " + node.operator, emitterVisitor.ctx.errorUtil);
    }

    static void handleDieBuiltin(EmitterVisitor emitterVisitor, OperatorNode node) {
        // Handle:  die LIST
        //   static RuntimeDataProvider die(RuntimeDataProvider value, int ctx)
        String operator = node.operator;
        emitterVisitor.ctx.logDebug("handleDieBuiltin " + node);
        node.operand.accept(emitterVisitor.with(RuntimeContextType.LIST));

        // push the formatted line number
        Node message = new StringNode(emitterVisitor.ctx.errorUtil.errorMessage(node.tokenIndex, ""), node.tokenIndex);
        message.accept(emitterVisitor.with(RuntimeContextType.SCALAR));

        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/Operator",
                operator,
                "(Lorg/perlonjava/runtime/RuntimeDataProvider;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeDataProvider;", false);
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    static void handleReverseBuiltin(EmitterVisitor emitterVisitor, OperatorNode node) {
        // Handle:  reverse LIST
        //   static RuntimeDataProvider reverse(RuntimeDataProvider value, int ctx)
        String operator = node.operator;
        emitterVisitor.ctx.logDebug("handleReverseBuiltin " + node);
        node.operand.accept(emitterVisitor.with(RuntimeContextType.LIST));
        emitterVisitor.pushCallContext();
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/Operator", operator, "(Lorg/perlonjava/runtime/RuntimeDataProvider;I)Lorg/perlonjava/runtime/RuntimeDataProvider;", false);
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    static void handleCryptBuiltin(EmitterVisitor emitterVisitor, OperatorNode node) {
        // Handle:  crypt PLAINTEXT,SALT
        emitterVisitor.ctx.logDebug("handleCryptBuiltin " + node);
        node.operand.accept(emitterVisitor.with(RuntimeContextType.LIST));
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/Crypt",
                node.operator,
                "(Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeScalar;",
                false);
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    static void handleUnpackBuiltin(EmitterVisitor emitterVisitor, OperatorNode node) {
        // Handle:  unpack TEMPLATE, EXPR
        emitterVisitor.ctx.logDebug("handleUnpackBuiltin " + node);
        node.operand.accept(emitterVisitor.with(RuntimeContextType.LIST));
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/Unpack",
                node.operator,
                "(Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeList;",
                false);
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    static void handlePackBuiltin(EmitterVisitor emitterVisitor, OperatorNode node) {
        // Handle:  pack TEMPLATE, LIST
        emitterVisitor.ctx.logDebug("handlePackBuiltin " + node);
        node.operand.accept(emitterVisitor.with(RuntimeContextType.LIST));
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/Pack",
                node.operator,
                "(Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeScalar;",
                false);
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    static void handleSpliceBuiltin(EmitterVisitor emitterVisitor, OperatorNode node) {
        // Handle:  splice @array, LIST
        String operator = node.operator;
        emitterVisitor.ctx.logDebug("handleSpliceBuiltin " + node);
        Node args = node.operand;
        Node operand = ((ListNode) args).elements.remove(0);
        operand.accept(emitterVisitor.with(RuntimeContextType.LIST));
        args.accept(emitterVisitor.with(RuntimeContextType.LIST));
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/Operator", operator, "(Lorg/perlonjava/runtime/RuntimeArray;Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeList;", false);
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    static void handlePushOperator(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        String operator = node.operator;
        node.left.accept(emitterVisitor.with(RuntimeContextType.LIST));
        node.right.accept(emitterVisitor.with(RuntimeContextType.LIST));
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeArray", operator, "(Lorg/perlonjava/runtime/RuntimeDataProvider;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    static void handleMapOperator(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        String operator = node.operator;
        node.right.accept(emitterVisitor.with(RuntimeContextType.LIST));  // list
        node.left.accept(emitterVisitor.with(RuntimeContextType.SCALAR)); // subroutine
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/Operator", operator, "(Lorg/perlonjava/runtime/RuntimeList;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeList;", false);
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    static void handleSplitOperator(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        String operator = node.operator;
        node.left.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        node.right.accept(emitterVisitor.with(RuntimeContextType.LIST));
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/Operator", operator, "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeList;", false);
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    static void handleJoinOperator(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        String operator = node.operator;
        node.left.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        node.right.accept(emitterVisitor.with(RuntimeContextType.LIST));
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/Operator", operator, "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeDataProvider;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    static void handleDiamondBuiltin(EmitterVisitor emitterVisitor, OperatorNode node) {
        MethodVisitor mv = emitterVisitor.ctx.mv;
        String argument = ((StringNode) ((ListNode) node.operand).elements.get(0)).value;
        emitterVisitor.ctx.logDebug("visit diamond " + argument);
        if (argument.equals("") || argument.equals("<>")) {
            // null filehandle:  <>  <<>>
            node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
            emitterVisitor.pushCallContext();
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/DiamondIO",
                    "readline",
                    "(Lorg/perlonjava/runtime/RuntimeScalar;I)Lorg/perlonjava/runtime/RuntimeDataProvider;", false);
            if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
                mv.visitInsn(Opcodes.POP);
            }
        } else {
            node.operator = "glob";
            handleGlobBuiltin(emitterVisitor, node);
        }
    }

    static void handleChompBuiltin(EmitterVisitor emitterVisitor, OperatorNode node) {
        MethodVisitor mv = emitterVisitor.ctx.mv;
        node.operand.accept(emitterVisitor.with(RuntimeContextType.LIST));
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "org/perlonjava/runtime/RuntimeDataProvider",
                node.operator,
                "()Lorg/perlonjava/runtime/RuntimeScalar;", true);
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            mv.visitInsn(Opcodes.POP);
        }
    }

    static void handleGlobBuiltin(EmitterVisitor emitterVisitor, OperatorNode node) {
        MethodVisitor mv = emitterVisitor.ctx.mv;

        // Generate unique IDs for this glob instance
        int globId = ScalarGlobOperator.currentId++;

        // public static RuntimeDataProvider evaluate(id, patternArg, ctx)
        mv.visitLdcInsn(globId);
        node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        emitterVisitor.pushCallContext();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/ScalarGlobOperator", "evaluate", "(ILorg/perlonjava/runtime/RuntimeScalar;I)Lorg/perlonjava/runtime/RuntimeDataProvider;", false);

        // If the context is VOID, we need to pop the result from the stack
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            mv.visitInsn(Opcodes.POP);
        }
    }

    static void handleVecBuiltin(EmitterVisitor emitterVisitor, OperatorNode node) {
        MethodVisitor mv = emitterVisitor.ctx.mv;
        node.operand.accept(emitterVisitor.with(RuntimeContextType.LIST));
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/Vec",
                node.operator,
                "(Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            mv.visitInsn(Opcodes.POP);
        }
    }

    static void handleRangeOperator(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        node.left.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        node.right.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        // static PerlRange generateList(int start, int end)
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/PerlRange",
                "createRange",
                "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/PerlRange;", false);
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    static void handleSubstr(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        String operator = node.operator;
        node.left.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        node.right.accept(emitterVisitor.with(RuntimeContextType.LIST));
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/Operator", operator, "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    static void handleRepeat(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        Node left = node.left;
        if (node.left instanceof ListNode) {
            node.left.accept(emitterVisitor.with(RuntimeContextType.LIST));
        } else {
            node.left.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        }
        node.right.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        emitterVisitor.pushCallContext();
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/Operator",
                "repeat",
                "(Lorg/perlonjava/runtime/RuntimeDataProvider;Lorg/perlonjava/runtime/RuntimeScalar;I)Lorg/perlonjava/runtime/RuntimeDataProvider;", false);
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    static void handleConcatOperator(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        EmitterVisitor scalarVisitor =
                emitterVisitor.with(RuntimeContextType.SCALAR); // execute operands in scalar context
        node.left.accept(scalarVisitor); // target - left parameter
        node.right.accept(scalarVisitor); // right parameter
        emitterVisitor.ctx.mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "org/perlonjava/runtime/RuntimeScalar",
                "stringConcat",
                "(Lorg/perlonjava/runtime/RuntimeDataProvider;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    static void handleScalar(EmitterVisitor emitterVisitor, OperatorNode node) {
        MethodVisitor mv = emitterVisitor.ctx.mv;
        node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "scalar", "()Lorg/perlonjava/runtime/RuntimeScalar;", true);
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            mv.visitInsn(Opcodes.POP);
        }
    }

    static void handleDeleteExists(EmitterVisitor emitterVisitor, OperatorNode node) {
        //   OperatorNode: delete
        //    ListNode:
        //      BinaryOperatorNode: {
        //        OperatorNode: $
        //          IdentifierNode: a
        //        HashLiteralNode:
        //          NumberNode: 10
        String operator = node.operator;
        if (node.operand instanceof ListNode) {
            ListNode operand = (ListNode) node.operand;
            if (operand.elements.size() == 1) {
                BinaryOperatorNode binop = (BinaryOperatorNode) operand.elements.get(0);
                if (binop.operator.equals("{")) {
                    emitterVisitor.handleHashElementOperator(binop, operator);
                    return;
                }
                if (binop.operator.equals("->")) {
                    if (binop.right instanceof HashLiteralNode) { // ->{x}
                        emitterVisitor.handleArrowHashDeref(binop, operator);
                        return;
                    }
                }
            }
        }
        throw new PerlCompilerException(node.tokenIndex, "Not implemented: operator: " + operator, emitterVisitor.ctx.errorUtil);
    }

    static void handlePackageOperator(EmitterVisitor emitterVisitor, OperatorNode node) {
        String name = ((IdentifierNode) node.operand).name;
        emitterVisitor.ctx.symbolTable.setCurrentPackage(name);
        DebugInfo.setDebugInfoFileName(emitterVisitor.ctx);
        if (emitterVisitor.ctx.contextType != RuntimeContextType.VOID) {
            // if context is not void, return an empty list
            ListNode listNode = new ListNode(node.tokenIndex);
            listNode.accept(emitterVisitor);
        }
    }

    static void handleNextOperator(EmitterContext ctx, OperatorNode node) {
        ctx.logDebug("visit(next)");

        String labelStr = null;
        ListNode labelNode = (ListNode) node.operand;
        if (labelNode.elements.isEmpty()) {
            // next
        } else {
            // next LABEL
            Node arg = labelNode.elements.get(0);
            if (arg instanceof IdentifierNode) {
                // L1
                labelStr = ((IdentifierNode) arg).name;
            } else {
                throw new RuntimeException("Not implemented: " + node);
            }
        }

        String operator = node.operator;
        LoopLabels loopLabels = ctx.javaClassInfo.findLoopLabelsByName(labelStr);
        ctx.logDebug("visit(next) operator: " + operator + " label: " + labelStr + " labels: " + loopLabels);
        if (loopLabels == null) {
            throw new PerlCompilerException(node.tokenIndex, "Can't \"" + operator + "\" outside a loop block", ctx.errorUtil);
        }

        ctx.logDebug("visit(next): asmStackLevel: " + ctx.javaClassInfo.asmStackLevel);

        int consumeStack = ctx.javaClassInfo.asmStackLevel;
        int targetStack = loopLabels.asmStackLevel;
        while (consumeStack-- > targetStack) {
            // consume the JVM stack
            ctx.mv.visitInsn(Opcodes.POP);
        }

        Label label = operator.equals("next") ? loopLabels.nextLabel
                : operator.equals("last") ? loopLabels.lastLabel
                : loopLabels.redoLabel;
        ctx.mv.visitJumpInsn(Opcodes.GOTO, label);
    }

    static void handleReturnOperator(EmitterVisitor emitterVisitor, OperatorNode node) {
        emitterVisitor.ctx.logDebug("visit(return) in context " + emitterVisitor.ctx.contextType);
        emitterVisitor.ctx.logDebug("visit(return) will visit " + node.operand + " in context " + emitterVisitor.ctx.with(RuntimeContextType.RUNTIME).contextType);

        int consumeStack = emitterVisitor.ctx.javaClassInfo.asmStackLevel;
        while (consumeStack-- > 0) {
            // consume the JVM stack
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }

        if (node.operand instanceof ListNode) {
            ListNode list = (ListNode) node.operand;
            if (list.elements.size() == 1) {
                // special case for a list with 1 element
                list.elements.get(0).accept(emitterVisitor.with(RuntimeContextType.RUNTIME));
                emitterVisitor.ctx.mv.visitJumpInsn(Opcodes.GOTO, emitterVisitor.ctx.javaClassInfo.returnLabel);
                return;
            }
        }

        node.operand.accept(emitterVisitor.with(RuntimeContextType.RUNTIME));
        emitterVisitor.ctx.mv.visitJumpInsn(Opcodes.GOTO, emitterVisitor.ctx.javaClassInfo.returnLabel);
        // TODO return (1,2), 3
    }
}
