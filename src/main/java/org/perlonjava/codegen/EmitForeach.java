package org.perlonjava.codegen;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.*;
import org.perlonjava.runtime.RuntimeContextType;

public class EmitForeach {
    static void emitFor1(EmitterVisitor emitterVisitor, For1Node node) {
        emitterVisitor.ctx.logDebug("FOR1 start");

        if (node.useNewScope) {
            emitterVisitor.ctx.symbolTable.enterScope();
        }

        MethodVisitor mv = emitterVisitor.ctx.mv;
        Label loopStart = new Label();
        Label loopEnd = new Label();
        Label continueLabel = new Label();

        // First declare the variables if it's a my operator
        if (node.variable instanceof OperatorNode opNode && opNode.operator.equals("my")) {
            node.variable.accept(emitterVisitor.with(RuntimeContextType.VOID));
            node.variable = ((OperatorNode) node.variable).operand;
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
                varList.elements.get(i).accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                mv.visitInsn(Opcodes.SWAP);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", "set", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
                mv.visitInsn(Opcodes.POP);
            }
        } else {
            // Original single variable case
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
            mv.visitTypeInsn(Opcodes.CHECKCAST, "org/perlonjava/runtime/RuntimeScalar");

            node.variable.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
            mv.visitInsn(Opcodes.SWAP);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", "set", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
            mv.visitInsn(Opcodes.POP);
        }

        emitterVisitor.ctx.javaClassInfo.incrementStackLevel(1);

        Label redoLabel = new Label();
        mv.visitLabel(redoLabel);

        Local.localTeardown(localRecord, mv);

        emitterVisitor.ctx.javaClassInfo.pushLoopLabels(
                node.labelName,
                continueLabel,
                redoLabel,
                loopEnd,
                RuntimeContextType.VOID);

        node.body.accept(emitterVisitor.with(RuntimeContextType.VOID));

        emitterVisitor.ctx.javaClassInfo.popLoopLabels();

        mv.visitLabel(continueLabel);
        Local.localTeardown(localRecord, mv);

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
            emitterVisitor.ctx.symbolTable.exitScope();
        }

        emitterVisitor.ctx.logDebug("FOR1 end");
    }
}
