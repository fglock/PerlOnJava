package org.perlonjava.codegen;

import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.LabelNode;
import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeContextType;

/**
 * EmitLabel handles the bytecode generation for Perl label statements.
 * Labels are used as targets for goto statements and provide control flow functionality.
 */
public class EmitLabel {
    /**
     * Generates bytecode for a label declaration in Perl code.
     *
     * @param ctx  The current emitter context containing compilation state
     * @param node The AST node representing the label
     * @throws PerlCompilerException if the label is not found in the current scope
     */
    static void emitLabel(EmitterContext ctx, LabelNode node) {
        // Search for the label definition in the current compilation scope
        GotoLabels targetLabel = ctx.javaClassInfo.findGotoLabelsByName(node.label);

        // Validate label existence
        if (targetLabel == null) {
            throw new PerlCompilerException(node.tokenIndex,
                    "Can't find label " + node.label, ctx.errorUtil);
        }

        // Generate the actual label in the bytecode
        ctx.mv.visitLabel(targetLabel.gotoLabel);

        // Handle non-void context cases
        // When a label is the last statement in a block and the block's result is used,
        // we need to ensure a value is pushed onto the stack
        if (ctx.contextType != RuntimeContextType.VOID) {
            // Create a new empty RuntimeList as the result value
            ctx.mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/RuntimeList");
            ctx.mv.visitInsn(Opcodes.DUP);  // Duplicate reference for constructor
            // Initialize the empty RuntimeList
            ctx.mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
                    "org/perlonjava/runtime/RuntimeList",
                    "<init>",
                    "()V",
                    false);
        }
    }
}
