package org.perlonjava.backend.jvm;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.frontend.astnode.Node;
import org.perlonjava.frontend.analysis.FindDeclarationVisitor;
import org.perlonjava.frontend.analysis.RegexUsageDetector;

public class Local {

    static localRecord localSetup(EmitterContext ctx, Node ast, MethodVisitor mv) {
        boolean needsDVM = FindDeclarationVisitor.findOperator(ast, "local") != null
                || RegexUsageDetector.containsRegexOperation(ast);
        int dynamicIndex = -1;
        if (needsDVM) {
            dynamicIndex = ctx.symbolTable.allocateLocalVariable();
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/runtimetypes/DynamicVariableManager",
                    "getLocalLevel",
                    "()I",
                    false);
            mv.visitVarInsn(Opcodes.ISTORE, dynamicIndex);
        }
        return new localRecord(needsDVM, dynamicIndex);
    }

    static void localTeardown(localRecord localRecord, MethodVisitor mv) {
        if (localRecord.needsDVM()) {
            mv.visitVarInsn(Opcodes.ILOAD, localRecord.dynamicIndex());
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/runtimetypes/DynamicVariableManager",
                    "popToLocalLevel",
                    "(I)V",
                    false);
        }
    }

    record localRecord(boolean needsDVM, int dynamicIndex) {
    }
}
