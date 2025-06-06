package org.perlonjava.codegen;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.perlonjava.astnode.BlockNode;
import org.perlonjava.astnode.Node;
import org.perlonjava.astvisitor.EmitterVisitor;
import org.perlonjava.runtime.RuntimeContextType;

import java.util.List;

public class EmitBlock {
    /**
     * Emits bytecode for a block of statements.
     *
     * @param emitterVisitor The visitor used for code emission.
     * @param node           The block node representing the block of statements.
     */
    public static void emitBlock(EmitterVisitor emitterVisitor, BlockNode node) {
        MethodVisitor mv = emitterVisitor.ctx.mv;

        emitterVisitor.ctx.logDebug("generateCodeBlock start context:" + emitterVisitor.ctx.contextType);
        int scopeIndex = emitterVisitor.ctx.symbolTable.enterScope();
        EmitterVisitor voidVisitor =
                emitterVisitor.with(RuntimeContextType.VOID); // statements in the middle of the block have context VOID
        List<Node> list = node.elements;

        // Create labels for the block as a loop, like `L1: {...}`
        Label redoLabel = new Label();
        Label nextLabel = new Label();

        // Create labels used inside the block, like `{ L1: ... }`
        for (int i = 0; i < node.labels.size(); i++) {
            emitterVisitor.ctx.javaClassInfo.pushGotoLabels(node.labels.get(i), new Label());
        }

        // Setup 'local' environment if needed
        Local.localRecord localRecord = Local.localSetup(emitterVisitor.ctx, node, mv);

        // Add redo label
        mv.visitLabel(redoLabel);

        // Restore 'local' environment if 'redo' was called
        Local.localTeardown(localRecord, mv);

        if (node.isLoop) {
            emitterVisitor.ctx.javaClassInfo.pushLoopLabels(
                    node.labelName,
                    nextLabel,
                    redoLabel,
                    nextLabel,
                    emitterVisitor.ctx.contextType);
        }

        for (int i = 0; i < list.size(); i++) {
            Node element = list.get(i);

            ByteCodeSourceMapper.setDebugInfoLineNumber(emitterVisitor.ctx, element.getIndex());

            // Emit the statement with current context
            if (i == list.size() - 1) {
                // Special case for the last element
                emitterVisitor.ctx.logDebug("Last element: " + element);
                element.accept(emitterVisitor);
            } else {
                // General case for all other elements
                emitterVisitor.ctx.logDebug("Element: " + element);
                element.accept(voidVisitor);
            }
        }

        if (node.isLoop) {
            emitterVisitor.ctx.javaClassInfo.popLoopLabels();
        }

        // Pop labels used inside the block
        for (int i = 0; i < node.labels.size(); i++) {
            emitterVisitor.ctx.javaClassInfo.popGotoLabels();
        }

        // Add 'next', 'last' label
        mv.visitLabel(nextLabel);

        Local.localTeardown(localRecord, mv);

        emitterVisitor.ctx.symbolTable.exitScope(scopeIndex);
        emitterVisitor.ctx.logDebug("generateCodeBlock end");
    }
}
