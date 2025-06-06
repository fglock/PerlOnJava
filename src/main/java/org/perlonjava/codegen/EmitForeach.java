package org.perlonjava.codegen;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.*;
import org.perlonjava.astvisitor.EmitterVisitor;
import org.perlonjava.perlmodule.Warnings;
import org.perlonjava.runtime.RuntimeContextType;

public class EmitForeach {
    public static void emitFor1(EmitterVisitor emitterVisitor, For1Node node) {
        emitterVisitor.ctx.logDebug("FOR1 start");

        int scopeIndex = -1;
        if (node.useNewScope) {
            scopeIndex = emitterVisitor.ctx.symbolTable.enterScope();
        }

        MethodVisitor mv = emitterVisitor.ctx.mv;
        Label loopStart = new Label();
        Label loopEnd = new Label();
        Label continueLabel = new Label();

        // Check if the variable is global
        boolean loopVariableIsGlobal = false;
        if (node.variable instanceof OperatorNode opNode && opNode.operator.equals("$")) {
            if (opNode.operand instanceof IdentifierNode idNode) {
                int varIndex = emitterVisitor.ctx.symbolTable.getVariableIndex(opNode.operator + idNode.name);
                if (varIndex == -1) {
                    loopVariableIsGlobal = true;
                }
            }
        }

        if (loopVariableIsGlobal) {
            // TODO - special case if loopVariableIsGlobal
            // FIXME - Workaround: Handle $_ by declaring it as 'our'
            if (node.variable instanceof OperatorNode opNode &&
                    opNode.operator.equals("$") &&
                    opNode.operand instanceof IdentifierNode idNode &&
                    !idNode.name.contains("::")) {
                node.variable = new OperatorNode("our", node.variable, node.variable.getIndex());
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
        }

        // Obtain the iterator for the list
        node.list.accept(emitterVisitor.with(RuntimeContextType.LIST));
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "iterator", "()Ljava/util/Iterator;", true);

        Local.localRecord localRecord = Local.localSetup(emitterVisitor.ctx, node, mv);

        mv.visitLabel(loopStart);

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
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/RuntimeScalar", "undef", "()Lorg/perlonjava/runtime/RuntimeScalar;", false);
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

            if (node.variable instanceof OperatorNode operatorNode) {
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

        emitterVisitor.ctx.javaClassInfo.decrementStackLevel(1);
        mv.visitInsn(Opcodes.POP);

        if (emitterVisitor.ctx.contextType != RuntimeContextType.VOID) {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/RuntimeScalar", "undef", "()Lorg/perlonjava/runtime/RuntimeScalar;", false);
        }

        if (node.useNewScope) {
            emitterVisitor.ctx.symbolTable.exitScope(scopeIndex);
        }

        emitterVisitor.ctx.logDebug("FOR1 end");
    }
}
