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
            
            // Check RuntimeControlFlowRegistry after each statement (if in a LABELED loop)
            // Only check for loops with explicit labels (like SKIP:) to avoid overhead in regular loops
            if (node.isLoop && node.labelName != null) {
                LoopLabels loopLabels = emitterVisitor.ctx.javaClassInfo.findLoopLabelsByName(node.labelName);
                if (loopLabels != null) {
                    emitRegistryCheck(mv, loopLabels, redoLabel, nextLabel, nextLabel);
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
        // ULTRA-SIMPLE pattern to avoid ASM issues:
        // Call a single helper method that does ALL the checking and returns an action code
        
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
        
        // Use TABLESWITCH for clean bytecode
        mv.visitTableSwitchInsn(
                1,  // min (LAST)
                3,  // max (REDO)
                nextLabel,  // default (0=none or out of range)
                lastLabel,  // 1: LAST
                nextLabel,  // 2: NEXT  
                redoLabel   // 3: REDO
        );
        
        // No label needed - all paths are handled by switch
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
