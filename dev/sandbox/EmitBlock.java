package org.perlonjava.codegen;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.perlonjava.astnode.*;
import org.perlonjava.astvisitor.BytecodeSizeEstimator;
import org.perlonjava.astvisitor.ControlFlowDetectorVisitor;
import org.perlonjava.astvisitor.EmitterVisitor;
import org.perlonjava.runtime.GlobalVariable;
import org.perlonjava.runtime.RuntimeContextType;

import java.util.ArrayList;
import java.util.List;

public class EmitBlock {
    // Blocks with too many statements are emitted as a separate subroutine
    // in order to avoid "Method too large" error test: in t/re/pat.t
    final static int LARGE_BLOCK = 16;
    final static int LARGE_BYTECODE = 30000;
    
    // Reusable visitor for control flow detection
    private static final ControlFlowDetectorVisitor controlFlowDetector = new ControlFlowDetectorVisitor();

    /**
     * Emits bytecode for a block of statements.
     *
     * @param emitterVisitor The visitor used for code emission.
     * @param node           The block node representing the block of statements.
     */
    public static void emitBlock(EmitterVisitor emitterVisitor, BlockNode node) {
        MethodVisitor mv = emitterVisitor.ctx.mv;

        int bytecodeSize = BytecodeSizeEstimator.estimateSize(node);
        if (bytecodeSize > LARGE_BYTECODE) {
            System.out.println("bytecodesize " + bytecodeSize);
            List<Node> firstPart = new ArrayList<Node>();
            BlockNode firstBlock = new BlockNode(firstPart, node.tokenIndex);

            int totalSize = 0;
            while (totalSize < LARGE_BYTECODE) {
                Node element = node.elements.getFirst();
                int elementSize = BytecodeSizeEstimator.estimateSize(element);
                totalSize = elementSize + BytecodeSizeEstimator.estimateSize(firstBlock);
                System.out.println("elementSize " + elementSize + " " + totalSize);
                if (totalSize < LARGE_BYTECODE) {
                    node.elements.removeFirst();
                    if (element instanceof LabelNode) {
                        // refactor `SKIP: {BLOCK}`
                        var statement = node.elements.removeFirst();
                        element = refactorBlockToSub(
                                new BlockNode(
                                        List.of(element, statement),
                                        node.tokenIndex
                                )
                        );
                        System.out.println(element);
                    }
                    firstPart.add(element);
                }
            }

            System.out.println("first part " + BytecodeSizeEstimator.estimateSize(firstBlock));
            System.out.println("second part " + BytecodeSizeEstimator.estimateSize(node));

            // Use visitor pattern to check for unsafe control flow
            controlFlowDetector.reset();
            node.accept(controlFlowDetector);
            boolean hasUnsafeControlFlow = controlFlowDetector.hasUnsafeControlFlow();

            System.out.println("second part is unsafe " + hasUnsafeControlFlow);

            firstBlock.elements.add(node);
            node = firstBlock;

//            if (hasUnsafeControlFlow) {
//                firstBlock.elements.add(node);
//            } else {
//                BinaryOperatorNode subr = refactorBlockToSub(node);
//                firstBlock.elements.add(subr);
//                emitBlock(emitterVisitor, firstBlock);
//                return;
//            }
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
