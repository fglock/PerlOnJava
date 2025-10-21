package org.perlonjava.codegen;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.Node;
import org.perlonjava.astvisitor.FindDeclarationVisitor;
import org.perlonjava.astvisitor.RegexDetectorVisitor;

/**
 * The Local class provides methods to handle the setup and teardown of local variables
 * in the context of dynamic variable management within a method.
 */
public class Local {

    /**
     * Sets up a local variable to manage dynamic variable stack levels if a 'local' operator
     * or regex operations are present in the AST node.
     *
     * @param ctx The emitter context containing the symbol table.
     * @param ast The abstract syntax tree node to be checked for a 'local' operator or regex operations.
     * @param mv  The method visitor used to generate bytecode instructions.
     * @return A localRecord containing information about the presence of operations requiring state management
     * and the index of the dynamic variable stack.
     */
    static localRecord localSetup(EmitterContext ctx, Node ast, MethodVisitor mv) {
        // Check if the code contains a 'local' operator
        boolean containsLocalOperator = FindDeclarationVisitor.findOperator(ast, "local") != null;
        // Check if the code contains regex operations that need state preservation
        boolean containsRegex = RegexDetectorVisitor.containsRegex(ast);
        boolean needsDynamicState = containsLocalOperator || containsRegex;
        
        int dynamicIndex = -1;
        if (needsDynamicState) {
            // Allocate a local variable to store the dynamic variable stack index
            dynamicIndex = ctx.symbolTable.allocateLocalVariable();
            // Get the current level of the dynamic variable stack and store it
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/DynamicVariableManager",
                    "getLocalLevel",
                    "()I",
                    false);
            mv.visitVarInsn(Opcodes.ISTORE, dynamicIndex);
            
            // If there are regex operations, push a RegexState marker onto the stack
            // This will save regex state when entering the block
            if (containsRegex) {
                // Create new RegexState marker
                mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/RegexState");
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
                        "org/perlonjava/runtime/RegexState",
                        "<init>",
                        "()V",
                        false);
                // Push onto DynamicVariableManager (calls dynamicSaveState)
                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/DynamicVariableManager",
                        "pushLocalVariable",
                        "(Lorg/perlonjava/runtime/DynamicState;)Lorg/perlonjava/runtime/DynamicState;",
                        false);
                mv.visitInsn(Opcodes.POP); // Discard return value
            }
        }
        return new localRecord(needsDynamicState, dynamicIndex);
    }

    /**
     * Tears down the local variable setup by restoring the dynamic variable stack
     * to its previous level if a 'local' operator was present.
     *
     * @param localRecord The record containing information about the 'local' operator
     *                    and the dynamic variable stack index.
     * @param mv          The method visitor used to generate bytecode instructions.
     */
    static void localTeardown(localRecord localRecord, MethodVisitor mv) {
        // Add `local` teardown logic
        if (localRecord.needsDynamicState()) {
            // Restore the dynamic variable stack to the recorded level
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
     * @param needsDynamicState Indicates if dynamic state management is needed (local or regex ops).
     * @param dynamicIndex          The index of the dynamic variable stack.
     */
    record localRecord(boolean needsDynamicState, int dynamicIndex) {
    }
}
