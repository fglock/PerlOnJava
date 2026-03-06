package org.perlonjava.backend.jvm;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.frontend.analysis.FindDeclarationVisitor;
import org.perlonjava.frontend.astnode.Node;

public class Local {

    static int localSetup(EmitterContext ctx, Node ast, MethodVisitor mv) {
        int dynamicIndex = ctx.symbolTable.allocateLocalVariable();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/runtimetypes/DynamicVariableManager",
                "getLocalLevel",
                "()I",
                false);
        mv.visitVarInsn(Opcodes.ISTORE, dynamicIndex);
        return dynamicIndex;
    }

    static void localTeardown(int dynamicIndex, MethodVisitor mv) {
        mv.visitVarInsn(Opcodes.ILOAD, dynamicIndex);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/runtimetypes/DynamicVariableManager",
                "popToLocalLevel",
                "(I)V",
                false);
    }

    static localRecord localSetup(EmitterContext ctx, Node ast, MethodVisitor mv, boolean blockLevel) {
        boolean containsLocalOperator = FindDeclarationVisitor.findOperator(ast, "local") != null;
        int dynamicIndex = -1;
        if (containsLocalOperator) {
            dynamicIndex = ctx.symbolTable.allocateLocalVariable();
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/runtimetypes/DynamicVariableManager",
                    "getLocalLevel",
                    "()I",
                    false);
            mv.visitVarInsn(Opcodes.ISTORE, dynamicIndex);
        }
        return new localRecord(containsLocalOperator, dynamicIndex);
    }

    static void localTeardown(localRecord localRecord, MethodVisitor mv) {
        if (localRecord.containsLocalOperator()) {
            mv.visitVarInsn(Opcodes.ILOAD, localRecord.dynamicIndex());
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/runtimetypes/DynamicVariableManager",
                    "popToLocalLevel",
                    "(I)V",
                    false);
        }
    }

    record localRecord(boolean containsLocalOperator, int dynamicIndex) {
    }
}
