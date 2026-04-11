package org.perlonjava.backend.jvm.astrefactor;

import org.perlonjava.frontend.analysis.BytecodeSizeEstimator;
import org.perlonjava.frontend.analysis.ControlFlowDetectorVisitor;
import org.perlonjava.frontend.analysis.EmitterVisitor;
import org.perlonjava.frontend.astnode.BinaryOperatorNode;
import org.perlonjava.frontend.astnode.BlockNode;
import org.perlonjava.frontend.astnode.Node;

import java.util.List;

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

        // Skip if block is already a subroutine or is a special block
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

    /**
     * Try to refactor the entire block as a subroutine: {@code sub { <block> }->(@_)}.
     */
    private static boolean tryWholeBlockRefactoring(EmitterVisitor emitterVisitor, BlockNode node) {
        // Check for unsafe control flow using ControlFlowDetectorVisitor
        // This properly handles loop depth - unlabeled next/last/redo inside loops are safe
        // Create a new instance per call to avoid thread-safety issues with shared mutable state
        ControlFlowDetectorVisitor detector = new ControlFlowDetectorVisitor();
        detector.scan(node);
        if (detector.hasUnsafeControlFlow()) {
            return false;
        }

        // Create sub {...}->(@_) for whole block
        int tokenIndex = node.tokenIndex;

        // Mark the original block as already refactored to prevent recursion
        node.setAnnotation("blockAlreadyRefactored", true);

        // Create a wrapper block containing the original block
        BlockNode innerBlock = new BlockNode(List.of(node), tokenIndex);

        BinaryOperatorNode subr = createAnonSubCall(tokenIndex, innerBlock);

        // Emit the refactored block
        subr.accept(emitterVisitor);
        return true;
    }
}
