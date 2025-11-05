package org.perlonjava.codegen;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.*;
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

        // Try to refactor large blocks using the helper class
        if (LargeBlockRefactorer.processBlock(emitterVisitor, node)) {
            // Block was refactored and emitted by the helper
            return;
        }

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

        // Special case: detect pattern of `local $_` followed by `For1Node` with needsArrayOfAlias
        // In this case, we need to evaluate the For1Node's list before emitting the local operator
        if (list.size() >= 2 && 
            list.get(0) instanceof OperatorNode localOp && localOp.operator.equals("local") &&
            list.get(1) instanceof For1Node forNode && forNode.needsArrayOfAlias) {
            
            // Pre-evaluate the For1Node's list to array of aliases before localizing $_
            int tempArrayIndex = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
            forNode.list.accept(emitterVisitor.with(RuntimeContextType.LIST));
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeBase", "getArrayOfAlias", "()Lorg/perlonjava/runtime/RuntimeArray;", false);
            mv.visitVarInsn(Opcodes.ASTORE, tempArrayIndex);
            
            // Mark the For1Node to use the pre-evaluated array
            forNode.preEvaluatedArrayIndex = tempArrayIndex;
        }

        // Check if block has any actual runtime code (not just labels)
        boolean hasRuntimeCode = false;
        for (Node element : list) {
            if (element != null && !(element instanceof LabelNode)) {
                hasRuntimeCode = true;
                break;
            }
        }

        // Wrap block body in try-catch if there are goto labels (for non-local jumps)
        // But only if this block has actual runtime code and is not just a loop body placeholder
        // (bare blocks parsed as For3Node have isLoop=true and should not get try-catch here)
        if (!node.labels.isEmpty() && hasRuntimeCode && !node.isLoop) {
            Label tryStart = new Label();
            Label tryEnd = new Label();
            Label catchGoto = new Label();
            Label afterCatch = new Label();
            
            // Register exception handler for GotoException
            mv.visitTryCatchBlock(tryStart, tryEnd, catchGoto, "org/perlonjava/runtime/GotoException");
            
            // Try block start
            mv.visitLabel(tryStart);
            
            // Emit all statements in the block
        for (int i = 0; i < list.size(); i++) {
            Node element = list.get(i);
            
                if (element == null) {
                    emitterVisitor.ctx.logDebug("Skipping null element in block at index " + i);
                    continue;
                }

                ByteCodeSourceMapper.setDebugInfoLineNumber(emitterVisitor.ctx, element.getIndex());

                if (i == list.size() - 1) {
                    emitterVisitor.ctx.logDebug("Last element: " + element);
                    element.accept(emitterVisitor);
                } else {
                    emitterVisitor.ctx.logDebug("Element: " + element);
                    element.accept(voidVisitor);
                }
            }
            
            mv.visitLabel(tryEnd);
            mv.visitJumpInsn(Opcodes.GOTO, afterCatch);
            
            // Catch GotoException
            mv.visitLabel(catchGoto);
            // Stack: [exception]
            
            // Check if exception matches any of our labels
            for (int i = 0; i < node.labels.size(); i++) {
                String label = node.labels.get(i);
                
                mv.visitInsn(Opcodes.DUP);  // [exception, exception]
                mv.visitLdcInsn(label);     // [exception, exception, label]
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, 
                    "org/perlonjava/runtime/PerlControlFlowException", 
                    "matchesLabel", 
                    "(Ljava/lang/String;)Z", 
                    false);
                // Stack now: [exception, boolean]
                
                Label noMatch = new Label();
                mv.visitJumpInsn(Opcodes.IFEQ, noMatch);  // if false, check next label
                
                // Match! Pop the exception and continue execution
                mv.visitInsn(Opcodes.POP);  // Pop the exception
                
                // If the block has a non-void context, we need to push a value
                // since the goto bypassed the normal block execution
                if (emitterVisitor.ctx.contextType != RuntimeContextType.VOID) {
                    EmitOperator.emitUndef(mv);
                }
                
                mv.visitJumpInsn(Opcodes.GOTO, afterCatch);
                
                mv.visitLabel(noMatch);
                // Stack: [exception] - ready for next iteration or re-throw
            }
            
            // No match found, exception is still on stack, re-throw for outer frames
            mv.visitInsn(Opcodes.ATHROW);
            
            mv.visitLabel(afterCatch);
        } else {
            // No goto labels, emit statements normally
            for (int i = 0; i < list.size(); i++) {
                Node element = list.get(i);
                
                // Skip null elements
            if (element == null) {
                emitterVisitor.ctx.logDebug("Skipping null element in block at index " + i);
                continue;
            }

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

    private static BinaryOperatorNode refactorBlockToSub(BlockNode node) {
        // Create sub {...}->(@_)
        int index = node.tokenIndex;
        ListNode args = new ListNode(index);
        args.elements.add(new OperatorNode("@", new IdentifierNode("_", index), index));
        BinaryOperatorNode subr = new BinaryOperatorNode(
                "->",
                new SubroutineNode(
                        null, null, null,
                        new BlockNode(List.of(node), index),
                        false,
                        index
                ),
                args,
                index
        );
        return subr;
    }
}
