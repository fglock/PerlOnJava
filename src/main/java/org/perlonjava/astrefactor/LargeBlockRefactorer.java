package org.perlonjava.astrefactor;

import org.perlonjava.astnode.*;
import org.perlonjava.astvisitor.BytecodeSizeEstimator;
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

        // Skip if block is already a subroutine - subroutines are compiled into separate classes
        // and should have been refactored at parse time. We cannot wrap them in another closure
        // at code-generation time because they're already being compiled as a separate method.
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
     * Determine if a block should be refactored based on size criteria.
     * Uses minimal element count check to avoid overhead on trivial blocks.
     *
     * @param node             The block to check
     * @param emitterVisitor   The emitter visitor for context
     * @param refactorEnabled  Whether refactoring is enabled
     * @return true if the block should be refactored
     */
    private static boolean shouldRefactorBlock(BlockNode node, EmitterVisitor emitterVisitor, boolean refactorEnabled) {
        // Minimal check: skip very small blocks to avoid estimation overhead
        if (node.elements.size() <= MIN_CHUNK_SIZE) {
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
     * AGGRESSIVE MODE: Chunks based on bytecode size even with control flow present.
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
        // Use real bytecode size calculation (not sampling) to avoid underestimation
        long estimatedSize = 0;
        for (Node element : node.elements) {
            estimatedSize += BytecodeSizeEstimator.estimateSnippetSize(element);
        }
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

        // AGGRESSIVE CHUNKING: Split based on bytecode size, not control flow
        // TAIL-POSITION OPTIMIZATION: Control flow at the end can be excluded from closures
        // Example: { A, B, C, next } becomes { A, sub { B, C }->(@_), next }
        List<Object> segments = new ArrayList<>();  // Either Node (direct) or List<Node> (chunk)
        List<Node> currentChunk = new ArrayList<>();
        long currentChunkSize = 0;
        
        // Target chunk size: aim for chunks around 20KB to leave room for closure overhead
        // Each closure adds ~200 bytes overhead, and we want final methods under 40KB
        final long TARGET_CHUNK_SIZE = 20000;

        for (int i = 0; i < node.elements.size(); i++) {
            Node element = node.elements.get(i);
            long elementSize = BytecodeSizeEstimator.estimateSnippetSize(element);
            
            // Check if this is a tail-position control flow element
            // Tail position means: it's one of the last few elements and followed only by control flow
            boolean isTailControlFlow = false;
            if (shouldBreakChunk(element)) {
                // Check if all remaining elements are also control flow or labels
                boolean allRemainingAreControlFlow = true;
                for (int j = i + 1; j < node.elements.size(); j++) {
                    if (!shouldBreakChunk(node.elements.get(j)) && !isCompleteBlock(node.elements.get(j))) {
                        allRemainingAreControlFlow = false;
                        break;
                    }
                }
                isTailControlFlow = allRemainingAreControlFlow;
            }
            
            // Check if we should finalize the current chunk
            boolean shouldFinalizeChunk = false;
            
            if (isCompleteBlock(element)) {
                // Complete blocks are already scoped - finalize current chunk and add directly
                shouldFinalizeChunk = true;
            } else if (shouldBreakChunk(element) && !isTailControlFlow) {
                // Non-tail control flow - finalize current chunk and add directly
                shouldFinalizeChunk = true;
            } else if (isTailControlFlow && !currentChunk.isEmpty()) {
                // Tail control flow - finalize chunk but keep control flow outside
                shouldFinalizeChunk = true;
            } else if (!currentChunk.isEmpty() && currentChunkSize + elementSize > TARGET_CHUNK_SIZE) {
                // Chunk would exceed target size - finalize it
                shouldFinalizeChunk = true;
            }
            
            if (shouldFinalizeChunk && !currentChunk.isEmpty()) {
                segments.add(new ArrayList<>(currentChunk));
                currentChunk.clear();
                currentChunkSize = 0;
            }
            
            // Add element to appropriate location
            if (isCompleteBlock(element) || (shouldBreakChunk(element) && !isTailControlFlow)) {
                // Add directly as a segment (not in a chunk)
                segments.add(element);
            } else if (isTailControlFlow) {
                // Tail control flow - add directly (outside any closure)
                segments.add(element);
            } else {
                // Safe element - add to current chunk
                currentChunk.add(element);
                currentChunkSize += elementSize;
            }
        }

        // Process any remaining chunk (should be rare with tail optimization)
        if (!currentChunk.isEmpty()) {
            segments.add(new ArrayList<>(currentChunk));
        }

        // AGGRESSIVE MODE: Don't check for unsafe control flow in chunks
        // We accept that some chunks may have control flow, but we wrap them anyway
        // The key insight: control flow within a closure is safe as long as it doesn't
        // reference labels outside the closure. We've already filtered out labels above.
        
        // Build nested structure if we have any chunks
        List<Node> processedElements = buildNestedStructure(
                segments,
                node.tokenIndex,
                MIN_CHUNK_SIZE,
                false, // returnTypeIsList = false: execute statements, don't return list
                skipRefactoring
        );

        // Apply chunking - we should always have created segments
        node.elements.clear();
        node.elements.addAll(processedElements);
        node.setAnnotation("blockAlreadyRefactored", true);
        
        // Verify refactoring was successful
        // Use real bytecode size calculation (not sampling) to avoid underestimation
        long newEstimatedSize = 0;
        for (Node element : node.elements) {
            newEstimatedSize += BytecodeSizeEstimator.estimateSnippetSize(element);
        }
        node.setAnnotation("refactoredBytecodeSize", newEstimatedSize);
        long originalSize = (Long) node.getAnnotation("estimatedBytecodeSize");
        
        // Check if refactoring succeeded in reducing size
        if (newEstimatedSize > LARGE_BYTECODE_SIZE) {
            // Still over 40KB threshold - let JVM handle validation
            node.setAnnotation("refactorSkipReason", String.format("Refactoring reduced size from %d to %d bytes, still > threshold %d (JVM will validate)", originalSize, newEstimatedSize, LARGE_BYTECODE_SIZE));
        } else {
            node.setAnnotation("refactorSkipReason", String.format("Successfully refactored: %d -> %d bytes", originalSize, newEstimatedSize));
        }

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
