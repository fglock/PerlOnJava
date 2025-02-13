package org.perlonjava.codegen;

import org.perlonjava.astnode.LabelNode;
import org.perlonjava.runtime.PerlCompilerException;

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

        EmitterContext.fixupContext(ctx);
    }

}
