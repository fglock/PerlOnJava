package org.perlonjava.backend.jvm;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.frontend.analysis.FindDeclarationVisitor;
import org.perlonjava.frontend.astnode.Node;

public class Local {

    static int saveLocalLevel(EmitterContext ctx, MethodVisitor mv) {
        int dynamicIndex = ctx.symbolTable.allocateLocalVariable();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/runtimetypes/DynamicVariableManager",
                "getLocalLevel",
                "()I",
                false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "java/lang/Integer",
                "valueOf",
                "(I)Ljava/lang/Integer;",
                false);
        mv.visitVarInsn(Opcodes.ASTORE, dynamicIndex);
        return dynamicIndex;
    }

    static int localSetup(EmitterContext ctx, Node ast, MethodVisitor mv) {
        return saveLocalLevel(ctx, mv);
    }

    static void localTeardown(int dynamicIndex, MethodVisitor mv) {
        emitPopToLocalLevel(mv, dynamicIndex, "teardownFrameToLocalLevel");
    }

    static void emitPopToLocalLevel(MethodVisitor mv, int dynamicIndex) {
        emitPopToLocalLevel(mv, dynamicIndex, "popToLocalLevel");
    }

    private static void emitPopToLocalLevel(MethodVisitor mv, int dynamicIndex, String methodName) {
        mv.visitVarInsn(Opcodes.ALOAD, dynamicIndex);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Integer");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/runtimetypes/DynamicVariableManager",
                methodName,
                "(I)V",
                false);
    }

    static localRecord localSetup(EmitterContext ctx, Node ast, MethodVisitor mv, boolean blockLevel) {
        // Check for both local operators and defer statements - both need scope cleanup
        boolean needsCleanup = FindDeclarationVisitor.containsLocalOrDefer(ast);
        int dynamicIndex = -1;
        if (needsCleanup) {
            dynamicIndex = saveLocalLevel(ctx, mv);
        }
        return new localRecord(needsCleanup, dynamicIndex);
    }

    static void localTeardown(localRecord localRecord, MethodVisitor mv) {
        if (localRecord.needsCleanup()) {
            emitPopToLocalLevel(mv, localRecord.dynamicIndex());
        }
    }

    record localRecord(boolean needsCleanup, int dynamicIndex) {
    }
}
