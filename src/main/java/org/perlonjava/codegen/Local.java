package org.perlonjava.codegen;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.Node;
import org.perlonjava.astvisitor.FindDeclarationVisitor;

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
        // Check if the code contains a 'local' operator
        boolean containsLocalOperator = FindDeclarationVisitor.findOperator(ast, "local") != null;
        int dynamicIndex = -1;
        boolean pooledDynamicIndex = false;
        if (containsLocalOperator) {
            // Allocate a local variable to store the dynamic variable stack index
            dynamicIndex = ctx.javaClassInfo.acquireIntSpillSlot();
            pooledDynamicIndex = dynamicIndex >= 0;
            if (!pooledDynamicIndex) {
                dynamicIndex = ctx.symbolTable.allocateLocalVariable();
            }
            // Get the current level of the dynamic variable stack and store it
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/DynamicVariableManager",
                    "getLocalLevel",
                    "()I",
                    false);
            mv.visitVarInsn(Opcodes.ISTORE, dynamicIndex);
        }
        return new localRecord(containsLocalOperator, dynamicIndex, pooledDynamicIndex);
    }

    /**
     * Tears down the local variable setup by restoring the dynamic variable stack
     * to its previous level if a 'local' operator was present.
     *
     * @param localRecord The record containing information about the 'local' operator
     *                    and the dynamic variable stack index.
     * @param ctx          The emitter context containing the symbol table.
     * @param mv          The method visitor used to generate bytecode instructions.
     */
    static void localTeardown(localRecord localRecord, EmitterContext ctx, MethodVisitor mv) {
        // Add `local` teardown logic
        if (localRecord.containsLocalOperator()) {
            // Restore the dynamic variable stack to the recorded level
            mv.visitVarInsn(Opcodes.ILOAD, localRecord.dynamicIndex());
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/DynamicVariableManager",
                    "popToLocalLevel",
                    "(I)V",
                    false);
            if (localRecord.pooledDynamicIndex()) {
                ctx.javaClassInfo.releaseIntSpillSlot();
            }
        }
    }

    static void localRestore(localRecord localRecord, MethodVisitor mv) {
        // Restore only: used by redo paths. Do not release pooled slots here because the
        // surrounding scope is still active and will reuse the same localRecord.
        if (localRecord.containsLocalOperator()) {
            mv.visitVarInsn(Opcodes.ILOAD, localRecord.dynamicIndex());
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/DynamicVariableManager",
                    "popToLocalLevel",
                    "(I)V",
                    false);
        }
    }

    /**
     * A record to store information about the presence of a 'local' operator
     * and the index of the dynamic variable stack.
     *
     * @param containsLocalOperator Indicates if a 'local' operator is present.
     * @param dynamicIndex          The index of the dynamic variable stack.
     */
    record localRecord(boolean containsLocalOperator, int dynamicIndex, boolean pooledDynamicIndex) {
    }
}
