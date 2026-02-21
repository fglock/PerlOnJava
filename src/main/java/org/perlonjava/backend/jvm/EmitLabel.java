package org.perlonjava.backend.jvm;

import org.perlonjava.frontend.astnode.LabelNode;
import org.objectweb.asm.Label;
import org.perlonjava.runtime.runtimetypes.PerlCompilerException;

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
    public static void emitLabel(EmitterContext ctx, LabelNode node) {
        // Search for the label definition in the current compilation scope
        GotoLabels targetLabel = ctx.javaClassInfo.findGotoLabelsByName(node.label);

        // If the label is not pre-registered, treat it as a standalone label.
        // Perl tests frequently use labeled blocks (e.g. SKIP: { ... }) without any goto.
        // In that case we still need to emit a valid bytecode label as a join point.
        if (targetLabel == null) {
            ctx.mv.visitLabel(new Label());
        } else {
            // Generate the actual label in the bytecode
            ctx.mv.visitLabel(targetLabel.gotoLabel);
        }

        EmitterContext.fixupContext(ctx);
    }

}
