package org.perlonjava.codegen;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.*;
import org.perlonjava.astvisitor.EmitterVisitor;
import org.perlonjava.operators.Time;
import org.perlonjava.perlmodule.Warnings;
import org.perlonjava.runtime.RuntimeContextType;

public class EmitForeach {
    public static void emitFor1(EmitterVisitor emitterVisitor, For1Node node) {
        emitterVisitor.ctx.logDebug("FOR1 start");

        // Check if the loop variable is a complex lvalue expression like $$f
        // If so, emit as while loop with explicit assignment
        if (node.variable instanceof OperatorNode opNode &&
                opNode.operand instanceof OperatorNode nestedOpNode &&
                opNode.operator.equals("$") && nestedOpNode.operator.equals("$")) {

            emitterVisitor.ctx.logDebug("FOR1 emitting complex lvalue $$var as while loop");
            emitFor1AsWhileLoop(emitterVisitor, node);
            return;
        }

        MethodVisitor mv = emitterVisitor.ctx.mv;
        Label loopStart = new Label();
        Label loopEnd = new Label();
        Label continueLabel = new Label();

        int scopeIndex = emitterVisitor.ctx.symbolTable.enterScope();

        // Check if the variable is global
        boolean loopVariableIsGlobal = false;
        String globalVarName = null;
        if (node.variable instanceof OperatorNode opNode && opNode.operator.equals("$")) {
            if (opNode.operand instanceof IdentifierNode idNode) {
                String varName = opNode.operator + idNode.name;
                int varIndex = emitterVisitor.ctx.symbolTable.getVariableIndex(varName);
                if (varIndex == -1) {
                    loopVariableIsGlobal = true;
                    globalVarName = idNode.name;
                }
            }
        }

        // First declare the variables if it's a my/our operator
        if (node.variable instanceof OperatorNode opNode &&
                (opNode.operator.equals("my") || opNode.operator.equals("our"))) {
            boolean isWarningEnabled = Warnings.warningManager.isWarningEnabled("redefine");
            if (isWarningEnabled) {
                // turn off "masks earlier declaration" warning
                Warnings.warningManager.setWarningState("redefine", false);
            }
            // emit the variable declarations
            node.variable.accept(emitterVisitor.with(RuntimeContextType.VOID));
            // rewrite the variable node without the declaration
            node.variable = opNode.operand;

            if (isWarningEnabled) {
                // restore warnings
                Warnings.warningManager.setWarningState("redefine", true);
            }

            // Reset global variable check after rewriting
            loopVariableIsGlobal = false;
        }

        Local.localRecord localRecord = Local.localSetup(emitterVisitor.ctx, node, mv);

        // Obtain the iterator for the list
        node.list.accept(emitterVisitor.with(RuntimeContextType.LIST));
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeBase", "iterator", "()Ljava/util/Iterator;", false);

        mv.visitLabel(loopStart);

        // Check for pending signals (alarm) before each loop iteration
        mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "org/perlonjava/operators/Time",
                "checkPendingSignals",
                "()V",
                false
        );

        // Check if iterator has more elements
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
        mv.visitJumpInsn(Opcodes.IFEQ, loopEnd);

        // Handle multiple variables case
        if (node.variable instanceof ListNode varList) {
            for (int i = 0; i < varList.elements.size(); i++) {
                // Duplicate iterator
                mv.visitInsn(Opcodes.DUP);

                // Check if iterator has more elements
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
                Label hasValueLabel = new Label();
                Label endValueLabel = new Label();
                mv.visitJumpInsn(Opcodes.IFNE, hasValueLabel);

                // No more elements - assign undef
                mv.visitInsn(Opcodes.POP); // Pop the iterator copy
                EmitOperator.emitUndef(mv);
                mv.visitJumpInsn(Opcodes.GOTO, endValueLabel);

                // Has more elements - get next value
                mv.visitLabel(hasValueLabel);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
                mv.visitTypeInsn(Opcodes.CHECKCAST, "org/perlonjava/runtime/RuntimeScalar");

                mv.visitLabel(endValueLabel);

                // Assign to variable
                Node varNode = varList.elements.get(i);
                if (varNode instanceof OperatorNode operatorNode) {
                    String varName = operatorNode.operator + ((IdentifierNode) operatorNode.operand).name;
                    int varIndex = emitterVisitor.ctx.symbolTable.getVariableIndex(varName);
                    emitterVisitor.ctx.logDebug("FOR1 multi var name:" + varName + " index:" + varIndex);
                    mv.visitVarInsn(Opcodes.ASTORE, varIndex);
                }
            }
        } else {
            // Original single variable case
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
            mv.visitTypeInsn(Opcodes.CHECKCAST, "org/perlonjava/runtime/RuntimeScalar");

            if (loopVariableIsGlobal) {
                // Regular global variable assignment
                mv.visitLdcInsn(globalVarName);
                mv.visitInsn(Opcodes.SWAP); // Stack: globalVarName, iteratorValue
                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/GlobalVariable",
                        "aliasGlobalVariable",
                        "(Ljava/lang/String;Lorg/perlonjava/runtime/RuntimeScalar;)V",
                        false);
            } else if (node.variable instanceof OperatorNode operatorNode) {
                // Local variable case
                String varName = operatorNode.operator + ((IdentifierNode) operatorNode.operand).name;
                int varIndex = emitterVisitor.ctx.symbolTable.getVariableIndex(varName);
                emitterVisitor.ctx.logDebug("FOR1 single var name:" + varName + " index:" + varIndex);
                mv.visitVarInsn(Opcodes.ASTORE, varIndex);
            }
        }

        emitterVisitor.ctx.javaClassInfo.incrementStackLevel(1);

        Label redoLabel = new Label();
        mv.visitLabel(redoLabel);

        emitterVisitor.ctx.javaClassInfo.pushLoopLabels(
                node.labelName,
                continueLabel,
                redoLabel,
                loopEnd,
                RuntimeContextType.VOID);

        node.body.accept(emitterVisitor.with(RuntimeContextType.VOID));

        emitterVisitor.ctx.javaClassInfo.popLoopLabels();

        mv.visitLabel(continueLabel);

        if (node.continueBlock != null) {
            node.continueBlock.accept(emitterVisitor.with(RuntimeContextType.VOID));
        }

        mv.visitJumpInsn(Opcodes.GOTO, loopStart);

        mv.visitLabel(loopEnd);
        Local.localTeardown(localRecord, mv);

        emitterVisitor.ctx.symbolTable.exitScope(scopeIndex);

        emitterVisitor.ctx.javaClassInfo.decrementStackLevel(1);
        mv.visitInsn(Opcodes.POP);

        if (emitterVisitor.ctx.contextType != RuntimeContextType.VOID) {
            // Foreach loop returns empty string when it completes normally
            // This is different from an empty list in scalar context (which would be undef)
            if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR || emitterVisitor.ctx.contextType == RuntimeContextType.RUNTIME) {
                // Return empty string
                mv.visitFieldInsn(Opcodes.GETSTATIC, "org/perlonjava/runtime/RuntimeScalarCache",
                        "scalarEmptyString", "Lorg/perlonjava/runtime/RuntimeScalarReadOnly;");
            } else {
                // LIST context: Return empty list
                mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/RuntimeList");
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/perlonjava/runtime/RuntimeList", "<init>", "()V", false);
            }
        }

        emitterVisitor.ctx.logDebug("FOR1 end");
    }

    private static void emitFor1AsWhileLoop(EmitterVisitor emitterVisitor, For1Node node) {
        MethodVisitor mv = emitterVisitor.ctx.mv;
        Label loopStart = new Label();
        Label loopEnd = new Label();

        // Obtain the iterator for the list
        node.list.accept(emitterVisitor.with(RuntimeContextType.LIST));
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeBase", "iterator", "()Ljava/util/Iterator;", false);

        mv.visitLabel(loopStart);

        // Check for pending signals (alarm) before each loop iteration
        mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "org/perlonjava/operators/Time",
                "checkPendingSignals",
                "()V",
                false
        );

        // Check if iterator has more elements
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
        mv.visitJumpInsn(Opcodes.IFEQ, loopEnd);

        // Get next value
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "org/perlonjava/runtime/RuntimeScalar");

        // Assign to variable $$f
        node.variable.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        // Stack: iteratorValue, dereferenced_var
        mv.visitInsn(Opcodes.SWAP);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar",
                "set", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        mv.visitInsn(Opcodes.POP);

        emitterVisitor.ctx.javaClassInfo.incrementStackLevel(1);

        Label redoLabel = new Label();
        mv.visitLabel(redoLabel);

        emitterVisitor.ctx.javaClassInfo.pushLoopLabels(
                node.labelName,
                new Label(),
                redoLabel,
                loopEnd,
                RuntimeContextType.VOID);

        node.body.accept(emitterVisitor.with(RuntimeContextType.VOID));

        emitterVisitor.ctx.javaClassInfo.popLoopLabels();

        mv.visitJumpInsn(Opcodes.GOTO, loopStart);

        mv.visitLabel(loopEnd);

        emitterVisitor.ctx.javaClassInfo.decrementStackLevel(1);
        mv.visitInsn(Opcodes.POP);

        if (emitterVisitor.ctx.contextType != RuntimeContextType.VOID) {
            // Foreach loop returns empty string when it completes normally
            // This is different from an empty list in scalar context (which would be undef)
            if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR || emitterVisitor.ctx.contextType == RuntimeContextType.RUNTIME) {
                // Return empty string
                mv.visitFieldInsn(Opcodes.GETSTATIC, "org/perlonjava/runtime/RuntimeScalarCache",
                        "scalarEmptyString", "Lorg/perlonjava/runtime/RuntimeScalarReadOnly;");
            } else {
                // LIST context: Return empty list
                mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/RuntimeList");
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/perlonjava/runtime/RuntimeList", "<init>", "()V", false);
            }
        }
    }
}
