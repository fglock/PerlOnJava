package org.perlonjava.backend.jvm;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.frontend.analysis.FindDeclarationVisitor;
import org.perlonjava.frontend.astnode.BlockNode;
import org.perlonjava.frontend.astnode.For3Node;
import org.perlonjava.frontend.astnode.Node;
import org.perlonjava.frontend.astnode.OperatorNode;

public class Local {

    static int saveLocalLevel(EmitterContext ctx, MethodVisitor mv) {
        int dynamicIndex = ctx.symbolTable.allocateLocalVariable();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/runtimetypes/DynamicVariableManager",
                "getLocalLevel",
                "()I",
                false);
        mv.visitVarInsn(Opcodes.ISTORE, dynamicIndex);
        return dynamicIndex;
    }

    static int localSetup(EmitterContext ctx, Node ast, MethodVisitor mv) {
        return saveLocalLevel(ctx, mv);
    }

    static void localTeardown(int dynamicIndex, MethodVisitor mv) {
        mv.visitVarInsn(Opcodes.ILOAD, dynamicIndex);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/runtimetypes/DynamicVariableManager",
                "teardownFrameToLocalLevel",
                "(I)V",
                false);
    }

    static localRecord localSetup(EmitterContext ctx, Node ast, MethodVisitor mv, boolean blockLevel) {
        // Check for local operators, defer statements, and package declarations - all
        // register dynamically scoped state that must be restored when this scope exits.
        boolean needsCleanup = FindDeclarationVisitor.containsLocalOrDefer(ast)
                || containsPackageDeclarationInScope(ast);
        int dynamicIndex = -1;
        if (needsCleanup) {
            dynamicIndex = saveLocalLevel(ctx, mv);
        }
        return new localRecord(needsCleanup, dynamicIndex);
    }

    private static boolean containsPackageDeclarationInScope(Node ast) {
        BlockNode block = ast instanceof BlockNode blockNode ? blockNode
                : ast instanceof For3Node for3 && for3.body instanceof BlockNode body ? body
                : null;
        if (block == null || block.getBooleanAnnotation("unitClassDeclaration")) {
            return false;
        }
        for (Node element : block.elements) {
            if (element instanceof OperatorNode operator
                    && (operator.operator.equals("package") || operator.operator.equals("class"))) {
                return true;
            }
        }
        return false;
    }

    static void localTeardown(localRecord localRecord, MethodVisitor mv) {
        if (localRecord.needsCleanup()) {
            mv.visitVarInsn(Opcodes.ILOAD, localRecord.dynamicIndex());
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/runtimetypes/DynamicVariableManager",
                    "popToLocalLevel",
                    "(I)V",
                    false);
        }
    }

    record localRecord(boolean needsCleanup, int dynamicIndex) {
    }
}
