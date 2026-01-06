package org.perlonjava.codegen;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.*;
import org.perlonjava.astrefactor.LargeBlockRefactorer;import org.perlonjava.astvisitor.EmitterVisitor;
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

        for (int i = 0; i < list.size(); i++) {
            Node element = list.get(i);
            
            // Skip null elements - these occur when parseStatement returns null to signal
            // "not a statement, continue parsing" (e.g., AUTOLOAD without {}, try without feature enabled)
            // ParseBlock.parseBlock() adds these null results to the statements list
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
            
            // Check for non-local control flow after each statement in labeled blocks
            // Only for simple blocks to avoid ASM VerifyError
            if (node.isLoop && node.labelName != null && i < list.size() - 1 && list.size() <= 3) {
                // Check if block contains loop constructs (they handle their own control flow)
                boolean hasLoopConstruct = false;
                for (Node elem : list) {
                    if (elem instanceof For1Node || elem instanceof For3Node) {
                        hasLoopConstruct = true;
                        break;
                    }
                }
                
                if (!hasLoopConstruct) {
                    Label continueBlock = new Label();
                    
                    // if (!RuntimeControlFlowRegistry.hasMarker()) continue
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                            "org/perlonjava/runtime/RuntimeControlFlowRegistry",
                            "hasMarker",
                            "()Z",
                            false);
                    mv.visitJumpInsn(Opcodes.IFEQ, continueBlock);
                    
                    // Has marker: check if it matches this loop
                    mv.visitLdcInsn(node.labelName);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                            "org/perlonjava/runtime/RuntimeControlFlowRegistry",
                            "checkLoopAndGetAction",
                            "(Ljava/lang/String;)I",
                            false);
                    
                    // If action != 0, jump to nextLabel (exit block)
                    mv.visitJumpInsn(Opcodes.IFNE, nextLabel);
                    
                    mv.visitLabel(continueBlock);
                }
            }
            
            // NOTE: Registry checks are DISABLED in EmitBlock because:
            // 1. They cause ASM frame computation errors in nested/refactored code
            // 2. Bare labeled blocks (like TODO:) don't need non-local control flow
            // 3. Real loops (for/while/foreach) have their own registry checks in
            //    EmitForeach.java and EmitStatement.java that work correctly
            //
            // This means non-local control flow (next LABEL from closures) works for
            // actual loop constructs but NOT for bare labeled blocks, which is correct
            // Perl behavior anyway.
        }

        if (node.isLoop) {
            emitterVisitor.ctx.javaClassInfo.popLoopLabels();
            
            // Clear any stale markers when exiting a labeled block
            // This prevents markers from affecting subsequent labeled blocks
            if (node.labelName != null) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/RuntimeControlFlowRegistry",
                        "clear",
                        "()V",
                        false);
            }
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

    /**
     * Emit bytecode to check RuntimeControlFlowRegistry and handle any registered control flow.
     * This is called after loop body execution to catch non-local control flow markers.
     * 
     * @param mv The MethodVisitor
     * @param loopLabels The current loop's labels
     * @param redoLabel The redo target
     * @param nextLabel The next/continue target  
     * @param lastLabel The last/exit target
     */
    private static void emitRegistryCheck(MethodVisitor mv, LoopLabels loopLabels, 
                                         Label redoLabel, Label nextLabel, Label lastLabel) {
        // Check RuntimeControlFlowRegistry for non-local control flow
        // Use simple IF comparisons instead of TABLESWITCH to avoid ASM frame computation issues
        
        String labelName = loopLabels.labelName;
        if (labelName != null) {
            mv.visitLdcInsn(labelName);
        } else {
            mv.visitInsn(Opcodes.ACONST_NULL);
        }
        
        // Call: int action = RuntimeControlFlowRegistry.checkLoopAndGetAction(String labelName)
        // Returns: 0=none, 1=last, 2=next, 3=redo
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/RuntimeControlFlowRegistry",
                "checkLoopAndGetAction",
                "(Ljava/lang/String;)I",
                false);
        
        // Store action in a local variable to avoid stack issues
        // Then use simple IF comparisons instead of TABLESWITCH
        Label continueLabel = new Label();
        Label checkNext = new Label();
        Label checkRedo = new Label();
        
        // Check if action == 1 (LAST)
        mv.visitInsn(Opcodes.DUP);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitJumpInsn(Opcodes.IF_ICMPNE, checkNext);
        mv.visitInsn(Opcodes.POP); // Pop the duplicate
        mv.visitJumpInsn(Opcodes.GOTO, lastLabel);
        
        // Check if action == 2 (NEXT)
        mv.visitLabel(checkNext);
        mv.visitInsn(Opcodes.DUP);
        mv.visitInsn(Opcodes.ICONST_2);
        mv.visitJumpInsn(Opcodes.IF_ICMPNE, checkRedo);
        mv.visitInsn(Opcodes.POP); // Pop the duplicate
        mv.visitJumpInsn(Opcodes.GOTO, nextLabel);
        
        // Check if action == 3 (REDO)
        mv.visitLabel(checkRedo);
        mv.visitInsn(Opcodes.DUP);
        mv.visitInsn(Opcodes.ICONST_3);
        mv.visitJumpInsn(Opcodes.IF_ICMPNE, continueLabel);
        mv.visitInsn(Opcodes.POP); // Pop the duplicate
        mv.visitJumpInsn(Opcodes.GOTO, redoLabel);
        
        // Default: action == 0 (none) or other - continue normally
        mv.visitLabel(continueLabel);
        mv.visitInsn(Opcodes.POP); // Pop the action value
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
