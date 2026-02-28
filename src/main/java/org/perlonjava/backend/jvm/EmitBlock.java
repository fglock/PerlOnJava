package org.perlonjava.backend.jvm;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.backend.jvm.astrefactor.LargeBlockRefactorer;
import org.perlonjava.frontend.analysis.EmitterVisitor;
import org.perlonjava.frontend.analysis.RegexUsageDetector;
import org.perlonjava.frontend.astnode.*;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;

import java.util.List;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.ArrayList;

public class EmitBlock {

    private static void collectStateDeclSigilNodes(Node node, Set<OperatorNode> out) {
        if (node == null) {
            return;
        }
        if (node instanceof OperatorNode op) {
            if ("state".equals(op.operator) && op.operand instanceof OperatorNode sigilNode) {
                if (sigilNode.operand instanceof IdentifierNode && "$@%".contains(sigilNode.operator)) {
                    out.add(sigilNode);
                }
            }
            collectStateDeclSigilNodes(op.operand, out);
            return;
        }
        if (node instanceof BinaryOperatorNode bin) {
            collectStateDeclSigilNodes(bin.left, out);
            collectStateDeclSigilNodes(bin.right, out);
            return;
        }
        if (node instanceof ListNode list) {
            for (Node child : list.elements) {
                collectStateDeclSigilNodes(child, out);
            }
            return;
        }
        if (node instanceof BlockNode block) {
            for (Node child : block.elements) {
                collectStateDeclSigilNodes(child, out);
            }
        }
        if (node instanceof For1Node for1) {
            collectStateDeclSigilNodes(for1.variable, out);
            collectStateDeclSigilNodes(for1.list, out);
            collectStateDeclSigilNodes(for1.body, out);
            collectStateDeclSigilNodes(for1.continueBlock, out);
            return;
        }
        if (node instanceof For3Node for3) {
            collectStateDeclSigilNodes(for3.initialization, out);
            collectStateDeclSigilNodes(for3.condition, out);
            collectStateDeclSigilNodes(for3.increment, out);
            collectStateDeclSigilNodes(for3.body, out);
            collectStateDeclSigilNodes(for3.continueBlock, out);
            return;
        }
        if (node instanceof IfNode ifNode) {
            collectStateDeclSigilNodes(ifNode.condition, out);
            collectStateDeclSigilNodes(ifNode.thenBranch, out);
            collectStateDeclSigilNodes(ifNode.elseBranch, out);
            return;
        }
        if (node instanceof TryNode tryNode) {
            collectStateDeclSigilNodes(tryNode.tryBlock, out);
            collectStateDeclSigilNodes(tryNode.catchBlock, out);
            collectStateDeclSigilNodes(tryNode.finallyBlock, out);
        }
    }

    private static void collectStatementLabelNames(List<Node> elements, List<String> out) {
        for (Node element : elements) {
            if (element instanceof LabelNode labelNode) {
                out.add(labelNode.label);
            }
        }
    }

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

        // Hoist `state` declarations to the beginning of the block scope so that JVM local slots
        // are initialized even if a `goto` skips the original declaration statement.
        // This prevents NPEs when later code evaluates e.g. `defined $state_var`.
        Set<OperatorNode> stateDeclSigilNodes = new LinkedHashSet<>();
        for (Node element : list) {
            collectStateDeclSigilNodes(element, stateDeclSigilNodes);
        }
        for (OperatorNode sigilNode : stateDeclSigilNodes) {
            new OperatorNode("state", sigilNode, sigilNode.tokenIndex)
                    .accept(voidVisitor);
        }

        int lastNonNullIndex = -1;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i) != null) {
                lastNonNullIndex = i;
                break;
            }
        }

        // Create labels for the block as a loop, like `L1: {...}`
        Label redoLabel = new Label();
        Label nextLabel = new Label();

        // Pre-register statement labels (e.g. `NEXT:`) in this block so `goto NEXT` can resolve
        // even when the goto appears before the label (forward goto).
        //
        // We intentionally only register labels at this block's top level. Nested blocks get
        // their own EmitBlock invocation and maintain proper scoping/shadowing via the stack.
        List<String> statementLabelNames = new ArrayList<>();
        collectStatementLabelNames(list, statementLabelNames);
        for (String labelName : statementLabelNames) {
            emitterVisitor.ctx.javaClassInfo.pushGotoLabels(labelName, new Label());
        }

        // Create labels used inside the block, like `{ L1: ... }`
        for (int i = 0; i < node.labels.size(); i++) {
            emitterVisitor.ctx.javaClassInfo.pushGotoLabels(node.labels.get(i), new Label());
        }

        // Setup 'local' environment if needed
        Local.localRecord localRecord = Local.localSetup(emitterVisitor.ctx, node, mv);

        // Perl 5 block-level regex state scoping: save $1, $&, etc. on entry, restore on exit.
        // Skip if blockIsSubroutine: EmitterMethodCreator already emits subroutine-level
        // save/restore (regexStateSlot), so block-level would be redundant.
        int regexStateLocal = -1;
        if (!node.getBooleanAnnotation("blockIsSubroutine")
                && RegexUsageDetector.containsRegexOperation(node)) {
            regexStateLocal = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
            mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/runtimetypes/RegexState");
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
                    "org/perlonjava/runtime/runtimetypes/RegexState", "<init>", "()V", false);
            mv.visitVarInsn(Opcodes.ASTORE, regexStateLocal);
        }

        // Add redo label
        mv.visitLabel(redoLabel);

        // Restore 'local' environment if 'redo' was called
        Local.localTeardown(localRecord, mv);

        if (node.isLoop) {
            // A labeled/bare block used as a loop target (e.g. SKIP: { ... }) is a
            // pseudo-loop: it supports labeled next/last/redo (e.g. next SKIP), but
            // an unlabeled next/last/redo must target the nearest enclosing true loop.
            //
            // However, a *bare* block with loop control (e.g. `{ ...; redo }` or
            // `{ ... } continue { ... }`) is itself a valid target for *unlabeled*
            // last/next/redo, matching Perl semantics.
            boolean isBareBlock = node.labelName == null;
            emitterVisitor.ctx.javaClassInfo.pushLoopLabels(
                    node.labelName,
                    nextLabel,
                    redoLabel,
                    nextLabel,
                    emitterVisitor.ctx.contextType,
                    isBareBlock,
                    isBareBlock);
        }

        // Special case: detect pattern of `local $_` followed by `For1Node` with needsArrayOfAlias
        // In this case, we need to evaluate the For1Node's list before emitting the local operator
        For1Node preEvalForNode = null;
        int savedPreEvaluatedArrayIndex = -1;

        if (list.size() >= 2 && 
            list.get(0) instanceof OperatorNode localOp && localOp.operator.equals("local") &&
            list.get(1) instanceof For1Node forNode && forNode.needsArrayOfAlias) {
            
            // Pre-evaluate the For1Node's list to array of aliases before localizing $_
            int tempArrayIndex = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
            forNode.list.accept(emitterVisitor.with(RuntimeContextType.LIST));
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeBase", "getArrayOfAlias", "()Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;", false);
            mv.visitVarInsn(Opcodes.ASTORE, tempArrayIndex);
            
            // Mark the For1Node to use the pre-evaluated array
            preEvalForNode = forNode;
            savedPreEvaluatedArrayIndex = forNode.preEvaluatedArrayIndex;
            forNode.preEvaluatedArrayIndex = tempArrayIndex;
        }

        try {
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
                if (i == lastNonNullIndex) {
                    // Special case for the last element
                    emitterVisitor.ctx.logDebug("Last element: " + element);
                    element.accept(emitterVisitor);
                } else {
                    // General case for all other elements
                    emitterVisitor.ctx.logDebug("Element: " + element);
                    element.accept(voidVisitor);
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
        } finally {
            if (preEvalForNode != null) {
                preEvalForNode.preEvaluatedArrayIndex = savedPreEvaluatedArrayIndex;
            }
        }

        if (node.isLoop) {
            emitterVisitor.ctx.javaClassInfo.popLoopLabels();
        }

        // Pop labels used inside the block
        for (int i = 0; i < node.labels.size(); i++) {
            emitterVisitor.ctx.javaClassInfo.popGotoLabels();
        }

        // Pop statement labels registered for this block
        for (int i = 0; i < statementLabelNames.size(); i++) {
            emitterVisitor.ctx.javaClassInfo.popGotoLabels();
        }

        // Add 'next', 'last' label
        mv.visitLabel(nextLabel);

        Local.localTeardown(localRecord, mv);

        // Restore block-level regex state (counterpart to the save above)
        if (regexStateLocal >= 0) {
            mv.visitVarInsn(Opcodes.ALOAD, regexStateLocal);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/runtimetypes/RegexState", "restore", "()V", false);
        }

        emitterVisitor.ctx.symbolTable.exitScope(scopeIndex);
        emitterVisitor.ctx.logDebug("generateCodeBlock end");
    }

}
