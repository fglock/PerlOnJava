package org.perlonjava.backend.jvm;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.frontend.astnode.Node;
import org.perlonjava.frontend.analysis.FindDeclarationVisitor;

/**
 * The Local class provides methods to handle the setup and teardown of local variables
 * in the context of dynamic variable management within a method.
 */
public class Local {

    /**
     * Sets up a local variable to manage dynamic variable stack levels if a 'local' operator
     * is present in the AST node.
     *
     * @param ctx The emitter context containing the symbol table.
     * @param ast The abstract syntax tree node to be checked for a 'local' operator.
     * @param mv  The method visitor used to generate bytecode instructions.
     * @return A localRecord containing information about the presence of a 'local' operator
     * and the index of the dynamic variable stack.
     */
    static localRecord localSetup(EmitterContext ctx, Node ast, MethodVisitor mv) {
        boolean containsLocalOperator = FindDeclarationVisitor.findOperator(ast, "local") != null;
        boolean containsRegex = FindDeclarationVisitor.findOperator(ast, "matchRegex") != null
                || FindDeclarationVisitor.findOperator(ast, "replaceRegex") != null;
        boolean needsDynamicSave = containsLocalOperator || containsRegex;
        int dynamicIndex = -1;
        if (needsDynamicSave) {
            dynamicIndex = ctx.symbolTable.allocateLocalVariable();
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/runtimetypes/DynamicVariableManager",
                    "getLocalLevel",
                    "()I",
                    false);
            mv.visitVarInsn(Opcodes.ISTORE, dynamicIndex);
        }
        if (containsRegex) {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/runtimetypes/RuntimeRegexState",
                    "pushLocal",
                    "()V",
                    false);
        }
        return new localRecord(needsDynamicSave, containsRegex, dynamicIndex);
    }

    static void localTeardown(localRecord localRecord, MethodVisitor mv) {
        if (localRecord.needsDynamicSave()) {
            mv.visitVarInsn(Opcodes.ILOAD, localRecord.dynamicIndex());
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/runtimetypes/DynamicVariableManager",
                    "popToLocalLevel",
                    "(I)V",
                    false);
        }
    }

    record localRecord(boolean needsDynamicSave, boolean containsRegex, int dynamicIndex) {
    }
}
