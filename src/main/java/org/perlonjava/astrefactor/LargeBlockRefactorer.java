package org.perlonjava.astrefactor;

import org.perlonjava.astnode.*;
import org.perlonjava.astvisitor.ControlFlowDetectorVisitor;
import org.perlonjava.astvisitor.ControlFlowFinder;
import org.perlonjava.astvisitor.EmitterVisitor;
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

        // Skip if we're inside createMarkedBlock (prevents recursion)
        if (skipRefactoring.get()) {
            node.setAnnotation("refactorSkipReason", "Inside createMarkedBlock (recursion prevention)");
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
     * Process a block and refactor it if necessary to avoid method size limits.
     * This is the code-generation time entry point (legacy, kept for compatibility).
     *
     * @param emitterVisitor The emitter visitor context
     * @param node           The block to process
     * @return true if the block was refactored and emitted, false if no refactoring was needed
     */
    public static boolean processBlock(EmitterVisitor emitterVisitor, BlockNode node) {
        // CRITICAL: Skip if this block was already refactored to prevent infinite recursion
        if (node.getBooleanAnnotation("blockAlreadyRefactored")) {
            return false;
        }

        // Skip if block is already a subroutine or is a special block
        if (node.getBooleanAnnotation("blockIsSubroutine")) {
            return false;
        }

        // Determine if we need to refactor
        boolean needsRefactoring = shouldRefactorBlock(node, emitterVisitor, IS_REFACTORING_ENABLED);

        if (!needsRefactoring) {
            return false;
        }

        // Skip refactoring for special blocks (BEGIN, END, INIT, CHECK, UNITCHECK)
        // These blocks have special compilation semantics and cannot be refactored
        if (isSpecialContext(node)) {
            return false;
        }

        // Fallback: Try whole-block refactoring
        return tryWholeBlockRefactoring(emitterVisitor, node);  // Block was refactored and emitted

        // No refactoring was possible
    }

    /**
     * Determine if a block should be refactored based on size and context.
     */
    private static boolean shouldRefactorBlock(BlockNode node, EmitterVisitor emitterVisitor, boolean refactorEnabled) {
        // Check element count threshold (quick check before expensive bytecode estimation)
        if (node.elements.size() <= 50) {
            return false;
        }

        // Check if we're in a context that allows refactoring
        return refactorEnabled || !emitterVisitor.ctx.javaClassInfo.gotoLabelStack.isEmpty();
    }

    /**
     * Check if the block is in a special context where smart chunking should be avoided.
     */
    private static boolean isSpecialContext(BlockNode node) {
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
        // Quick check: skip small blocks to avoid expensive bytecode estimation
        // This is an optimization - small blocks are unlikely to exceed bytecode size limit
        if (node.elements.size() <= 50) {
            node.setAnnotation("refactorSkipReason", String.format("Element count %d <= 50", node.elements.size()));
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
            if (isCompleteBlock(element)) {
                // Complete blocks are already scoped - but check for labeled control flow
                // Labeled control flow might reference labels outside the block
                if (!currentChunk.isEmpty()) {
                    segments.add(new ArrayList<>(currentChunk));
                    currentChunk.clear();
                }
                segments.add(element);
            } else if (shouldBreakChunk(element)) {
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

        // Check ALL segments (both direct and chunks) for UNSAFE control flow
        // Use ControlFlowDetectorVisitor which considers loop depth
        // Unlabeled next/last/redo inside loops are safe, but labeled control flow is not
        for (Object segment : segments) {
            if (segment instanceof Node directNode) {
                controlFlowDetector.reset();
                directNode.accept(controlFlowDetector);
                if (controlFlowDetector.hasUnsafeControlFlow()) {
                    // Segment has unsafe control flow - skip refactoring
                    node.setAnnotation("refactorSkipReason", "Unsafe control flow in direct segment");
                    return;
                }
            } else if (segment instanceof List) {
                @SuppressWarnings("unchecked")
                List<Node> chunk = (List<Node>) segment;
                for (Node element : chunk) {
                    controlFlowDetector.reset();
                    element.accept(controlFlowDetector);
                    if (controlFlowDetector.hasUnsafeControlFlow()) {
                        // Chunk has unsafe control flow - skip refactoring
                        node.setAnnotation("refactorSkipReason", "Unsafe control flow in chunk");
                        return;
                    }
                }
            }
        }
        
        // Build nested structure if we have any chunks
        List<Node> processedElements = buildNestedStructure(
                segments,
                node.tokenIndex,
                MIN_CHUNK_SIZE,
                false, // returnTypeIsList = false: execute statements, don't return list
                skipRefactoring
        );

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
                node.setAnnotation("refactorSkipReason", String.format("Refactoring failed: size %d still > threshold %d", newEstimatedSize, LARGE_BYTECODE_SIZE));
                errorCantRefactorLargeBlock(node.tokenIndex, parser, newEstimatedSize);
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

        // Check if element contains ANY control flow (last/next/redo/goto)
        // We use a custom visitor that doesn't consider loop depth
        ControlFlowFinder finder = new ControlFlowFinder();
        element.accept(finder);
        return finder.foundControlFlow;
    }

    /**
     * Try to refactor the entire block as a subroutine.
     */
    private static boolean tryWholeBlockRefactoring(EmitterVisitor emitterVisitor, BlockNode node) {
        // Check for unsafe control flow using ControlFlowDetectorVisitor
        // This properly handles loop depth - unlabeled next/last/redo inside loops are safe
        controlFlowDetector.reset();
        node.accept(controlFlowDetector);
        if (controlFlowDetector.hasUnsafeControlFlow()) {
            return false;
        }

        // Create sub {...}->(@_) for whole block
        int tokenIndex = node.tokenIndex;

        // IMPORTANT: Mark the original block as already refactored to prevent recursion
        node.setAnnotation("blockAlreadyRefactored", true);

        // Create a wrapper block containing the original block
        BlockNode innerBlock = new BlockNode(List.of(node), tokenIndex);

        BinaryOperatorNode subr = createAnonSubCall(tokenIndex, innerBlock);

        // Emit the refactored block
        subr.accept(emitterVisitor);
        return true;
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

    /**
     * Check if a chunk would be wrapped in a closure based on its size.
     *
     * @param chunk The chunk to check
     * @return true if the chunk is large enough to be wrapped (>= MIN_CHUNK_SIZE)
     */
    private static boolean chunkWouldBeWrapped(List<Node> chunk) {
        return chunk.size() >= MIN_CHUNK_SIZE;
    }

    /**
     * Check if a chunk contains unsafe control flow.
     * This checks for any control flow statements (last/next/redo/goto) that would break
     * if the chunk is wrapped in a closure.
     *
     * @param chunk The chunk to check
     * @return true if unsafe control flow found
     */
    private static boolean chunkHasUnsafeControlFlow(List<Node> chunk) {
        controlFlowDetector.reset();
        for (Node element : chunk) {
            element.accept(controlFlowDetector);
            if (controlFlowDetector.hasUnsafeControlFlow()) {
                return true;
            }
        }
        return false;
    }

}
