package org.perlonjava.backend.jvm.astrefactor;

import org.perlonjava.frontend.analysis.BytecodeSizeEstimator;
import org.perlonjava.frontend.analysis.ControlFlowDetectorVisitor;
import org.perlonjava.frontend.analysis.EmitterVisitor;
import org.perlonjava.frontend.analysis.RegexUsageDetector;
import org.perlonjava.frontend.astnode.BinaryOperatorNode;
import org.perlonjava.frontend.astnode.BlockNode;
import org.perlonjava.frontend.astnode.LabelNode;
import org.perlonjava.frontend.astnode.Node;
import org.perlonjava.frontend.astnode.For3Node;
import org.perlonjava.frontend.astnode.IfNode;
import org.perlonjava.frontend.astnode.ListNode;
import org.perlonjava.frontend.astnode.OperatorNode;
import org.perlonjava.frontend.astnode.SubroutineNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.perlonjava.backend.jvm.astrefactor.BlockRefactor.*;

/**
 * Helper class for refactoring large blocks to avoid JVM's "Method too large" error.
 * <p>
 * When a block's estimated bytecode size exceeds {@link BlockRefactor#LARGE_BYTECODE_SIZE},
 * the entire block is wrapped in an anonymous sub call: {@code sub { <block> }->(@_)}.
 * This pushes the block's code into a separate JVM method with its own 64KB budget.
 * <p>
 * If wrapping is insufficient (the block is still too large for a single method),
 * the caller ({@link org.perlonjava.backend.jvm.EmitterMethodCreator}) catches the
 * resulting {@code MethodTooLargeException} and falls back to the interpreter backend.
 */
public class LargeBlockRefactorer {

    private static long estimateTotalBytecodeSizeCapped(List<Node> nodes, long capInclusive) {
        long total = 0;
        for (Node node : nodes) {
            if (node == null) {
                continue;
            }
            total += BytecodeSizeEstimator.estimateSnippetSize(node);
            if (total > capInclusive) {
                return capInclusive + 1;
            }
        }
        return total;
    }

    private static void collectInternalLabels(Node node, Set<String> labels) {
        if (node == null) return;
        if (node instanceof LabelNode labelNode) {
            labels.add(labelNode.label);
            return;
        }
        if (node instanceof BlockNode block) {
            labels.addAll(block.labels);
            for (Node child : block.elements) {
                collectInternalLabels(child, labels);
            }
            return;
        }
        // Bare source blocks are represented as simple For3Nodes. Labels in
        // those blocks remain internal to the extracted wrapper, too.
        if (node instanceof org.perlonjava.frontend.astnode.For3Node loop) {
            if (loop.labelName != null) {
                labels.add(loop.labelName);
            }
            collectInternalLabels(loop.initialization, labels);
            collectInternalLabels(loop.condition, labels);
            collectInternalLabels(loop.increment, labels);
            collectInternalLabels(loop.body, labels);
            collectInternalLabels(loop.continueBlock, labels);
        }
    }

    /**
     * Split only independent bare source blocks out of a large enclosing block.
     * A bare block is represented by a simple scoped {@link For3Node}; its
     * declarations are already lexically local, so placing it in an anonymous
     * subroutine cannot make those lexicals visible to neighbouring statements.
     *
     * <p>Blocks with labels or non-local control flow stay in the enclosing
     * method. In particular, this avoids changing the visibility of a label
     * that may be targeted by a goto outside the extracted block.
     */
    private static BlockNode partitionIndependentBareBlocks(BlockNode node) {
        List<Node> elements = new ArrayList<>(node.elements.size());
        boolean partitioned = false;

        for (Node element : node.elements) {
            if (element instanceof For3Node loop
                    && loop.isSimpleBlock
                    && loop.useNewScope
                    && loop.labelName == null
                    && !containsSubroutine(loop)
                    && !containsDynamicLocalization(loop)) {
                Set<String> labels = new HashSet<>();
                collectInternalLabels(loop, labels);
                ControlFlowDetectorVisitor controlFlowDetector = new ControlFlowDetectorVisitor();
                controlFlowDetector.scan(loop);

                if (labels.isEmpty() && !controlFlowDetector.hasUnsafeControlFlow()) {
                    BlockNode extractedBlock = new BlockNode(List.of(loop), loop.tokenIndex);
                    extractedBlock.setAnnotation("blockAlreadyRefactored", true);
                    elements.add(createAnonSubCall(loop.tokenIndex, extractedBlock));
                    partitioned = true;
                    continue;
                }
            }
            elements.add(element);
        }

        if (!partitioned) {
            return node;
        }

        BlockNode partitionedBlock = new BlockNode(elements, node.tokenIndex);
        partitionedBlock.labels.addAll(node.labels);
        partitionedBlock.setAnnotation("blockAlreadyRefactored", true);
        return partitionedBlock;
    }

    /**
     * An extracted scope returns before its enclosing block does. A closure
     * created in that scope may capture one of its lexicals, so its lifetime
     * cannot be changed by wrapping the scope in an extra anonymous subroutine.
     */
    private static boolean containsSubroutine(Node node) {
        if (node == null) return false;
        if (node instanceof SubroutineNode) return true;
        if (node instanceof BlockNode block) {
            return block.elements.stream().anyMatch(LargeBlockRefactorer::containsSubroutine);
        }
        if (node instanceof BinaryOperatorNode binary) {
            return containsSubroutine(binary.left) || containsSubroutine(binary.right);
        }
        if (node instanceof OperatorNode operator) {
            return containsSubroutine(operator.operand);
        }
        if (node instanceof ListNode list) {
            return list.elements.stream().anyMatch(LargeBlockRefactorer::containsSubroutine);
        }
        if (node instanceof For3Node loop) {
            return containsSubroutine(loop.initialization)
                    || containsSubroutine(loop.condition)
                    || containsSubroutine(loop.increment)
                    || containsSubroutine(loop.body)
                    || containsSubroutine(loop.continueBlock);
        }
        if (node instanceof IfNode conditional) {
            return containsSubroutine(conditional.condition)
                    || containsSubroutine(conditional.thenBranch)
                    || containsSubroutine(conditional.elseBranch);
        }
        return false;
    }

    /**
     * `local` is dynamically scoped to the currently executing subroutine.
     * Extracting a bare block that contains it into another anonymous subroutine
     * restores the localized values when that call returns, which is earlier
     * than the original enclosing block. Keep these blocks in their wrapper.
     */
    private static boolean containsDynamicLocalization(Node node) {
        if (node == null) return false;
        if (node instanceof OperatorNode operator) {
            return operator.operator.equals("local") || containsDynamicLocalization(operator.operand);
        }
        if (node instanceof BinaryOperatorNode binary) {
            return containsDynamicLocalization(binary.left) || containsDynamicLocalization(binary.right);
        }
        if (node instanceof BlockNode block) {
            return block.elements.stream().anyMatch(LargeBlockRefactorer::containsDynamicLocalization);
        }
        if (node instanceof ListNode list) {
            return list.elements.stream().anyMatch(LargeBlockRefactorer::containsDynamicLocalization);
        }
        if (node instanceof For3Node loop) {
            return containsDynamicLocalization(loop.initialization)
                    || containsDynamicLocalization(loop.condition)
                    || containsDynamicLocalization(loop.increment)
                    || containsDynamicLocalization(loop.body)
                    || containsDynamicLocalization(loop.continueBlock);
        }
        if (node instanceof IfNode conditional) {
            return containsDynamicLocalization(conditional.condition)
                    || containsDynamicLocalization(conditional.thenBranch)
                    || containsDynamicLocalization(conditional.elseBranch);
        }
        return false;
    }

    /**
     * Process a block and refactor it if necessary to avoid method size limits.
     * Called from {@link org.perlonjava.backend.jvm.EmitBlock#emitBlock} during bytecode emission.
     *
     * @param emitterVisitor The emitter visitor context
     * @param node           The block to process
     * @return true if the block was refactored and emitted, false if no refactoring was needed
     */
    public static boolean processBlock(EmitterVisitor emitterVisitor, BlockNode node) {
        // Skip if this block was already refactored to prevent infinite recursion
        if (node.getBooleanAnnotation("blockAlreadyRefactored")) {
            return false;
        }

        // A method/subroutine establishes dynamic match, eval, and caller
        // state.  Moving its body into another anonymous subroutine changes
        // those observable boundaries; only source blocks may be extracted.
        if (node.getBooleanAnnotation("blockIsSubroutine")) {
            return false;
        }

        // Determine if we need to refactor
        if (!shouldRefactorBlock(node)) {
            return false;
        }

        // Skip refactoring for special blocks (BEGIN, END, INIT, CHECK, UNITCHECK)
        // These blocks have special compilation semantics and cannot be refactored
        if (isSpecialContext(node)) {
            return false;
        }

        // Try whole-block refactoring
        return tryWholeBlockRefactoring(emitterVisitor, node);
    }

    /**
     * Determine if a block should be refactored based on size criteria.
     */
    private static boolean shouldRefactorBlock(BlockNode node) {
        if (node.elements.size() <= MIN_CHUNK_SIZE) {
            return false;
        }
        // An anonymous-subroutine wrapper owns a separate regex match state.
        // Splitting a source block that observes $&, $1, or related variables
        // changes their lifetime even when the block has no explicit control
        // flow. Keep those blocks in their original method; the previous
        // 40 KB threshold already compiled the upstream regex corpus safely.
        if (RegexUsageDetector.containsRegexOperation(node) || containsDynamicEval(node)) {
            return false;
        }
        long estimatedSize = estimateTotalBytecodeSizeCapped(
                node.elements, (long) LARGE_BYTECODE_SIZE * 2);
        return estimatedSize > LARGE_BYTECODE_SIZE;
    }

    /**
     * Check if the block is in a special context where refactoring should be avoided.
     */
    private static boolean isSpecialContext(BlockNode node) {
        return node.getBooleanAnnotation("blockIsSpecial") ||
                node.getBooleanAnnotation("blockIsBegin") ||
                node.getBooleanAnnotation("blockIsRequire") ||
                node.getBooleanAnnotation("blockIsInit");
    }

    /** Runtime eval inherits the caller's dynamic match state, so it cannot
     * be moved behind an anonymous-subroutine boundary. */
    private static boolean containsDynamicEval(Node node) {
        if (node == null) return false;
        if (node instanceof OperatorNode operator) {
            return operator.operator.equals("eval") || operator.operator.equals("evalbytes")
                    || containsDynamicEval(operator.operand);
        }
        if (node instanceof BinaryOperatorNode binary) {
            return containsDynamicEval(binary.left) || containsDynamicEval(binary.right);
        }
        if (node instanceof BlockNode block) return block.elements.stream().anyMatch(LargeBlockRefactorer::containsDynamicEval);
        if (node instanceof ListNode list) return list.elements.stream().anyMatch(LargeBlockRefactorer::containsDynamicEval);
        if (node instanceof For3Node loop) return containsDynamicEval(loop.initialization) || containsDynamicEval(loop.condition)
                || containsDynamicEval(loop.increment) || containsDynamicEval(loop.body) || containsDynamicEval(loop.continueBlock);
        if (node instanceof IfNode conditional) return containsDynamicEval(conditional.condition)
                || containsDynamicEval(conditional.thenBranch) || containsDynamicEval(conditional.elseBranch);
        return false;
    }

    /**
     * Try to refactor the entire block as a subroutine: {@code sub { <block> }->(@_)}.
     */
    private static boolean tryWholeBlockRefactoring(EmitterVisitor emitterVisitor, BlockNode node) {
        // Check for unsafe control flow using ControlFlowDetectorVisitor
        // This properly handles loop depth - unlabeled next/last/redo inside loops are safe
        ControlFlowDetectorVisitor controlFlowDetector = new ControlFlowDetectorVisitor();
        Set<String> internalLabels = new HashSet<>();
        collectInternalLabels(node, internalLabels);
        controlFlowDetector.setAllowedGotoLabels(internalLabels);
        controlFlowDetector.setAllowStaticGoto(node.getBooleanAnnotation("blockIsSubroutine"));
        controlFlowDetector.scan(node);
        if (controlFlowDetector.hasUnsafeControlFlow()) {
            return false;
        }

        // Create sub {...}->(@_) for whole block
        int tokenIndex = node.tokenIndex;

        // Mark the original block as already refactored to prevent recursion
        node.setAnnotation("blockAlreadyRefactored", true);

        // Partition independent bare blocks before wrapping the enclosing
        // block. Whole-block wrapping alone only relocates an oversized method;
        // these child calls give the JVM several independently sized methods.
        BlockNode innerBlock = partitionIndependentBareBlocks(node);
        if (innerBlock == node) {
            innerBlock = new BlockNode(List.of(node), tokenIndex);
        }

        BinaryOperatorNode subr = createAnonSubCall(tokenIndex, innerBlock);

        // Emit the refactored block
        subr.accept(emitterVisitor);
        return true;
    }
}
