package org.perlonjava.astrefactor;

import org.perlonjava.astnode.*;
import org.perlonjava.astvisitor.ControlFlowDetectorVisitor;
import org.perlonjava.astvisitor.ControlFlowFinder;
import org.perlonjava.astvisitor.LargeCodeRefactor;
import org.perlonjava.parser.Parser;

import java.util.ArrayList;
import java.util.List;

import static org.perlonjava.astrefactor.BlockRefactor.*;
import static org.perlonjava.astrefactor.LargeNodeRefactorer.IS_REFACTORING_ENABLED;

/**
 * Helper class for refactoring large blocks to avoid JVM's "Method too large" error.
 * <p>
 * This class encapsulates all logic for detecting and transforming large blocks,
 * including smart chunking strategies and control flow analysis.
 */
public class LargeBlockRefactorer {

    // Reusable visitor for control flow detection
    private static final ControlFlowDetectorVisitor controlFlowDetector = new ControlFlowDetectorVisitor();

    // Thread-local flag to prevent recursion when creating chunk blocks
    private static final ThreadLocal<Boolean> skipRefactoring = ThreadLocal.withInitial(() -> false);

    /**
     * Parse-time entry point: called from BlockNode constructor to refactor large blocks.
     * This applies smart chunking to split safe statement sequences into closures.
     * Only runs when JPERL_LARGECODE=refactor is set.
     *
     * @param node   The block to potentially refactor (modified in place)
     * @param parser The parser instance for access to error utilities (can be null if not available)
     */
    public static void maybeRefactorBlock(BlockNode node, Parser parser) {
        // Skip if refactoring is not enabled
        // This is critical - we only do bytecode size estimation when refactoring is enabled
        // to avoid parse-time overhead and potential issues with partially constructed AST
        if (!IS_REFACTORING_ENABLED) {
            return;
        }

        // Skip if already refactored (prevents infinite recursion)
        if (node.getBooleanAnnotation("blockAlreadyRefactored")) {
            node.setAnnotation("refactorSkipReason", "Already refactored");
            return;
        }

        // Skip special blocks (BEGIN, END, etc.)
        if (isSpecialContext(node)) {
            node.setAnnotation("refactorSkipReason", "Special block (BEGIN/END/etc)");
            return;
        }

        // Apply smart chunking
        trySmartChunking(node, parser);
    }

    /**
     * Second-pass AST processor: walks the entire AST after parsing and applies
     * the same refactoring logic that was previously done at code-gen time.
     * This allows blocks to be refactored based on the reduced size after first-pass refactoring.
     *
     * @param ast    The root AST node
     * @param parser The parser for error reporting
     */
    public static void applySecondPass(Node ast, Parser parser) {
        if (!IS_REFACTORING_ENABLED) {
            return;
        }

        LargeCodeRefactor processor = new LargeCodeRefactor(parser);
        ast.accept(processor);
    }

    /**
     * Try to refactor entire block at parse time (second pass).
     * Same logic as tryWholeBlockRefactoring but modifies AST instead of emitting.
     */
    public static void tryWholeBlockRefactoringAtParseTime(BlockNode node, Parser parser) {
        // Check for unsafe control flow
        controlFlowDetector.reset();
        node.accept(controlFlowDetector);
        if (controlFlowDetector.hasUnsafeControlFlow()) {
            return;
        }

        // Mark as refactored to prevent recursion
        node.setAnnotation("blockAlreadyRefactored", true);

        // Create wrapper: sub { original_block_contents }->(@_)
        List<Node> originalElements = new ArrayList<>(node.elements);

        BlockNode innerBlock = new BlockNode(originalElements, node.tokenIndex);
        innerBlock.setAnnotation("blockAlreadyRefactored", true);

        BinaryOperatorNode subCall = createAnonSubCall(node.tokenIndex, innerBlock);

        // Replace block contents with the subroutine call
        node.elements.clear();
        node.elements.add(subCall);
    }

    /**
     * Check if the block is in a special context where smart chunking should be avoided.
     */
    public static boolean isSpecialContext(BlockNode node) {
        return node.getBooleanAnnotation("blockIsSpecial") ||
                node.getBooleanAnnotation("blockIsBegin") ||
                node.getBooleanAnnotation("blockIsRequire") ||
                node.getBooleanAnnotation("blockIsInit");
    }

    /**
     * Try to apply smart chunking to reduce the number of top-level elements.
     * Creates nested closures for proper lexical scoping.
     *
     * @param node   The block to chunk
     * @param parser The parser instance for access to error utilities (can be null)
     */
    private static void trySmartChunking(BlockNode node, Parser parser) {
        // Minimal check: skip very small blocks to avoid estimation overhead
        if (node.elements.size() <= MIN_CHUNK_SIZE) {
            node.setAnnotation("refactorSkipReason", String.format("Element count %d <= %d (minimal threshold)", node.elements.size(), MIN_CHUNK_SIZE));
            return;
        }

        // Check bytecode size - skip if under threshold
        long estimatedSize = estimateTotalBytecodeSize(node.elements);
        node.setAnnotation("estimatedBytecodeSize", estimatedSize);
        if (estimatedSize <= LARGE_BYTECODE_SIZE) {
            node.setAnnotation("refactorSkipReason", String.format("Bytecode size %d <= threshold %d", estimatedSize, LARGE_BYTECODE_SIZE));
            return;
        }

        // Check if the block has any labels (stored in BlockNode.labels field)
        // Labels define goto/next/last targets and must remain at block level
        if (node.labels != null && !node.labels.isEmpty()) {
            // Block has labels - skip refactoring to preserve label scope
            node.setAnnotation("refactorSkipReason", "Block has labels");
            return;
        }

        List<Object> segments = new ArrayList<>();  // Either Node (direct) or List<Node> (chunk)
        List<Node> currentChunk = new ArrayList<>();

        for (Node element : node.elements) {
            if (shouldBreakChunk(element)) {
                // This element cannot be in a chunk (has unsafe control flow or is a label)
                if (!currentChunk.isEmpty()) {
                    segments.add(new ArrayList<>(currentChunk));
                    currentChunk.clear();
                }
                // Add the element directly
                segments.add(element);
            } else {
                // Safe element, add to current chunk
                currentChunk.add(element);
            }
        }

        // Process any remaining chunk
        if (!currentChunk.isEmpty()) {
            segments.add(new ArrayList<>(currentChunk));
        }

        // Normalize chunks: if a chunk contains unsafe control flow, split it so that unsafe
        // elements become direct segments and only safe elements remain in chunks.
        List<Object> normalizedSegments = new ArrayList<>();
        for (Object segment : segments) {
            if (segment instanceof Node directNode) {
                normalizedSegments.add(directNode);
                continue;
            }
            @SuppressWarnings("unchecked")
            List<Node> chunk = (List<Node>) segment;
            List<Node> safeSubChunk = new ArrayList<>();
            for (Node element : chunk) {
                controlFlowDetector.reset();
                element.accept(controlFlowDetector);
                if (controlFlowDetector.hasUnsafeControlFlow()) {
                    if (!safeSubChunk.isEmpty()) {
                        normalizedSegments.add(new ArrayList<>(safeSubChunk));
                        safeSubChunk.clear();
                    }
                    normalizedSegments.add(element);
                } else {
                    safeSubChunk.add(element);
                }
            }
            if (!safeSubChunk.isEmpty()) {
                normalizedSegments.add(new ArrayList<>(safeSubChunk));
            }
        }
        segments = normalizedSegments;

        // Build nested structure if we have any chunks
        List<Node> processedElements = buildNestedStructure(
                segments,
                node.tokenIndex,
                MIN_CHUNK_SIZE,
                false,
                skipRefactoring
        );

        if (processedElements.size() >= node.elements.size()) {
            long fallbackEstimated = estimateTotalBytecodeSize(processedElements);
            if (fallbackEstimated > LARGE_BYTECODE_SIZE) {
                processedElements = buildNestedStructure(
                        segments,
                        node.tokenIndex,
                        1,
                        false,
                        skipRefactoring
                );
            }
        }

        // Apply chunking if we reduced the element count
        if (processedElements.size() < node.elements.size()) {
            node.elements.clear();
            node.elements.addAll(processedElements);
            node.setAnnotation("blockAlreadyRefactored", true);

            // Verify refactoring was successful
            long newEstimatedSize = estimateTotalBytecodeSize(node.elements);
            node.setAnnotation("refactoredBytecodeSize", newEstimatedSize);
            long originalSize = (Long) node.getAnnotation("estimatedBytecodeSize");
            if (newEstimatedSize > LARGE_BYTECODE_SIZE) {
                List<Node> fallbackElements = buildNestedStructure(
                        segments,
                        node.tokenIndex,
                        1,
                        false,
                        skipRefactoring
                );
                long fallbackSize = estimateTotalBytecodeSize(fallbackElements);
                if (fallbackSize > LARGE_BYTECODE_SIZE) {
                    node.setAnnotation("refactorSkipReason", String.format("Refactoring failed: size %d still > threshold %d", newEstimatedSize, LARGE_BYTECODE_SIZE));
                    errorCantRefactorLargeBlock(node.tokenIndex, parser, fallbackSize);
                }
                node.elements.clear();
                node.elements.addAll(fallbackElements);
                node.setAnnotation("refactoredBytecodeSize", fallbackSize);
                node.setAnnotation("refactorSkipReason", String.format("Successfully refactored (fallback): %d -> %d bytes", originalSize, fallbackSize));
                return;
            }
            node.setAnnotation("refactorSkipReason", String.format("Successfully refactored: %d -> %d bytes", originalSize, newEstimatedSize));
            return;
        }

        // If refactoring didn't help and block is still too large, throw an error
        long finalEstimatedSize = estimateTotalBytecodeSize(node.elements);
        if (finalEstimatedSize > LARGE_BYTECODE_SIZE) {
            node.setAnnotation("refactorSkipReason", String.format("Refactoring didn't reduce element count, size %d > threshold %d", finalEstimatedSize, LARGE_BYTECODE_SIZE));
            errorCantRefactorLargeBlock(node.tokenIndex, parser, finalEstimatedSize);
        }
        node.setAnnotation("refactorSkipReason", String.format("Refactoring didn't reduce element count, but size %d <= threshold %d", finalEstimatedSize, LARGE_BYTECODE_SIZE));

    }


    /**
     * Determine if an element should break the current chunk.
     * Labels and ANY control flow statements break chunks - they must stay as direct elements.
     * This is more conservative than ControlFlowDetectorVisitor because we need to catch
     * ALL control flow, not just "unsafe" control flow (which considers loop depth).
     */
    private static boolean shouldBreakChunk(Node element) {
        // Labels break chunks - they're targets for goto/next/last
        if (element instanceof LabelNode) {
            return true;
        }

        // Break chunk only if the element contains UNSAFE control flow.
        // Regular loop control inside a loop body (unlabeled next/last/redo) is safe to wrap
        // as long as it stays within the same element subtree.
        controlFlowDetector.reset();
        element.accept(controlFlowDetector);
        return controlFlowDetector.hasUnsafeControlFlow();
    }

    /**
     * Check if a node is a complete block/loop with its own scope.
     */
    private static boolean isCompleteBlock(Node node) {
        return node instanceof BlockNode ||
                node instanceof For1Node ||
                node instanceof For3Node ||
                node instanceof IfNode ||
                node instanceof TryNode;
    }

}
