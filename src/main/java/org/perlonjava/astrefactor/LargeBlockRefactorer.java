package org.perlonjava.astrefactor;

import org.perlonjava.astnode.*;
import org.perlonjava.astvisitor.ControlFlowDetectorVisitor;
import org.perlonjava.astvisitor.ControlFlowFinder;
import org.perlonjava.astvisitor.EmitterVisitor;
import org.perlonjava.parser.Parser;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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

    private static final ThreadLocal<ControlFlowFinder> controlFlowFinderTl = ThreadLocal.withInitial(ControlFlowFinder::new);

    private static final ThreadLocal<Deque<BlockNode>> pendingRefactorBlocks = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<Boolean> processingPendingRefactors = ThreadLocal.withInitial(() -> false);

    public static void enqueueForRefactor(BlockNode node) {
        if (!IS_REFACTORING_ENABLED || node == null) {
            return;
        }
        if (node.getBooleanAnnotation("queuedForRefactor")) {
            return;
        }
        node.setAnnotation("queuedForRefactor", true);
        pendingRefactorBlocks.get().addLast(node);
    }

    private static void processPendingRefactors() {
        if (processingPendingRefactors.get()) {
            return;
        }
        processingPendingRefactors.set(true);
        Deque<BlockNode> queue = pendingRefactorBlocks.get();
        try {
            while (!queue.isEmpty()) {
                BlockNode block = queue.removeFirst();
                maybeRefactorBlock(block, null);
            }
        } finally {
            queue.clear();
            processingPendingRefactors.set(false);
        }
    }

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
            if (node.annotations != null) {
                node.setAnnotation("refactorSkipReason", "Inside createMarkedBlock (recursion prevention)");
            }
            return;
        }

        // Skip if already refactored (prevents infinite recursion)
        if (node.getBooleanAnnotation("blockAlreadyRefactored")) {
            if (parser != null || node.annotations != null) {
                node.setAnnotation("refactorSkipReason", "Already refactored");
            }
            return;
        }

        // Skip special blocks (BEGIN, END, etc.)
        if (isSpecialContext(node)) {
            if (parser != null || node.annotations != null) {
                node.setAnnotation("refactorSkipReason", "Special block (BEGIN/END/etc)");
            }
            return;
        }

        // Apply smart chunking
        trySmartChunking(node, parser);

        // Refactor any blocks created during this pass (iteratively, not recursively).
        processPendingRefactors();
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
     *
     * @param node   The block to chunk
     * @param parser The parser instance for access to error utilities (can be null)
     */
    private static void trySmartChunking(BlockNode node, Parser parser) {
        // Minimal check: skip very small blocks to avoid estimation overhead
        if (node.elements.size() <= MIN_CHUNK_SIZE) {
            if (parser != null || node.annotations != null) {
                node.setAnnotation("refactorSkipReason", "Element count " + node.elements.size() + " <= " + MIN_CHUNK_SIZE + " (minimal threshold)");
            }
            return;
        }
        
        // Check bytecode size - skip if under threshold
        long estimatedSize = estimateTotalBytecodeSize(node.elements);
        long estimatedHalf = estimatedSize / 2;
        long estimatedSizeWithSafetyMargin = estimatedSize > Long.MAX_VALUE - estimatedHalf ? Long.MAX_VALUE : estimatedSize + estimatedHalf;
        if (parser != null || node.annotations != null) {
            node.setAnnotation("estimatedBytecodeSize", estimatedSize);
            node.setAnnotation("estimatedBytecodeSizeWithSafetyMargin", estimatedSizeWithSafetyMargin);
        }
        boolean forceRefactorByElementCount = node.elements.size() >= LARGE_ELEMENT_COUNT;
        if (!forceRefactorByElementCount && estimatedSizeWithSafetyMargin <= LARGE_BYTECODE_SIZE) {
            if (parser != null || node.annotations != null) {
                node.setAnnotation("refactorSkipReason", "Bytecode size " + estimatedSize + " <= threshold " + LARGE_BYTECODE_SIZE);
            }
            return;
        }

        int effectiveMinChunkSize = MIN_CHUNK_SIZE;
        // Guard against creating an excessive number of nested closures on extremely large blocks.
        // Too many closures can blow up memory long before bytecode size is reduced.
        final int maxNestedClosures = 512;

        int maxNestedClosuresEffective = (int) Math.min(
                maxNestedClosures,
                Math.max(1L, (estimatedSizeWithSafetyMargin + LARGE_BYTECODE_SIZE - 1) / LARGE_BYTECODE_SIZE)
        );

        int closuresCreated = 0;
        if (node.elements.size() > (long) effectiveMinChunkSize * maxNestedClosuresEffective) {
            effectiveMinChunkSize = Math.max(MIN_CHUNK_SIZE, (node.elements.size() + maxNestedClosuresEffective - 1) / maxNestedClosuresEffective);
            if (parser != null || node.annotations != null) {
                node.setAnnotation("refactorEffectiveMinChunkSize", effectiveMinChunkSize);
            }
        }

        // Streaming construction from the end to avoid building large intermediate segment lists.
        // We only materialize block bodies for chunks that will actually be wrapped.
        List<Node> suffixReversed = new ArrayList<>();
        Node tailClosure = null;
        boolean createdAnyClosure = false;

        int safeRunEndExclusive = node.elements.size();
        int safeRunLen = 0;
        boolean safeRunActive = false;

        for (int i = node.elements.size() - 1; i >= 0; i--) {
            Node element = node.elements.get(i);
            boolean safeForChunk = !shouldBreakChunk(element);

            if (safeForChunk) {
                safeRunActive = true;
                safeRunLen++;
                continue;
            }

            if (safeRunActive) {
                int safeRunStart = safeRunEndExclusive - safeRunLen;
                while (safeRunLen >= effectiveMinChunkSize) {
                    int remainingBudget = maxNestedClosuresEffective - closuresCreated;
                    if (remainingBudget <= 0) {
                        break;
                    }

                    int chunkLen = Math.max(effectiveMinChunkSize, (safeRunLen + remainingBudget - 1) / remainingBudget);
                    if (chunkLen > safeRunLen) {
                        chunkLen = safeRunLen;
                    }
                    int chunkStart = safeRunEndExclusive - chunkLen;

                    List<Node> blockElements = new ArrayList<>(chunkLen + suffixReversed.size() + (tailClosure != null ? 1 : 0));
                    for (int j = chunkStart; j < safeRunEndExclusive; j++) {
                        blockElements.add(node.elements.get(j));
                    }
                    for (int k = suffixReversed.size() - 1; k >= 0; k--) {
                        blockElements.add(suffixReversed.get(k));
                    }
                    if (tailClosure != null) {
                        blockElements.add(tailClosure);
                    }

                    BlockNode block = createBlockNode(blockElements, node.tokenIndex, skipRefactoring);
                    tailClosure = createAnonSubCall(node.tokenIndex, block);
                    suffixReversed.clear();
                    createdAnyClosure = true;
                    closuresCreated++;

                    safeRunEndExclusive = chunkStart;
                    safeRunLen -= chunkLen;
                }

                safeRunStart = safeRunEndExclusive - safeRunLen;
                for (int j = safeRunEndExclusive - 1; j >= safeRunStart; j--) {
                    suffixReversed.add(node.elements.get(j));
                }

                safeRunActive = false;
                safeRunLen = 0;
            }

            suffixReversed.add(element);
            safeRunEndExclusive = i;
        }

        if (safeRunActive) {
            int safeRunStart = safeRunEndExclusive - safeRunLen;
            while (safeRunLen >= effectiveMinChunkSize) {
                int remainingBudget = maxNestedClosuresEffective - closuresCreated;
                if (remainingBudget <= 0) {
                    break;
                }

                int chunkLen = Math.max(effectiveMinChunkSize, (safeRunLen + remainingBudget - 1) / remainingBudget);
                if (chunkLen > safeRunLen) {
                    chunkLen = safeRunLen;
                }
                int chunkStart = safeRunEndExclusive - chunkLen;

                List<Node> blockElements = new ArrayList<>(chunkLen + suffixReversed.size() + (tailClosure != null ? 1 : 0));
                for (int j = chunkStart; j < safeRunEndExclusive; j++) {
                    blockElements.add(node.elements.get(j));
                }
                for (int k = suffixReversed.size() - 1; k >= 0; k--) {
                    blockElements.add(suffixReversed.get(k));
                }
                if (tailClosure != null) {
                    blockElements.add(tailClosure);
                }

                BlockNode block = createBlockNode(blockElements, node.tokenIndex, skipRefactoring);
                tailClosure = createAnonSubCall(node.tokenIndex, block);
                suffixReversed.clear();
                createdAnyClosure = true;
                closuresCreated++;

                safeRunEndExclusive = chunkStart;
                safeRunLen -= chunkLen;
            }

            safeRunStart = safeRunEndExclusive - safeRunLen;
            for (int j = safeRunEndExclusive - 1; j >= safeRunStart; j--) {
                suffixReversed.add(node.elements.get(j));
            }
        }

        if (!createdAnyClosure) {
            if (parser != null || node.annotations != null) {
                node.setAnnotation("refactorSkipReason", "No chunk >= effective min chunk size " + effectiveMinChunkSize);
            }
            return;
        }

        List<Node> processedElements = new ArrayList<>(suffixReversed.size() + 1);
        for (int k = suffixReversed.size() - 1; k >= 0; k--) {
            processedElements.add(suffixReversed.get(k));
        }
        processedElements.add(tailClosure);

        // Apply chunking if we reduced the element count
        if (processedElements.size() < node.elements.size()) {
            node.elements = processedElements;
            // Mark the block as refactored
            node.setAnnotation("blockAlreadyRefactored", true);

            // Verify the refactored block is smaller
            long newEstimatedSize = estimateTotalBytecodeSize(node.elements);
            long newEstimatedHalf = newEstimatedSize / 2;
            long newEstimatedSizeWithSafetyMargin = newEstimatedSize > Long.MAX_VALUE - newEstimatedHalf ? Long.MAX_VALUE : newEstimatedSize + newEstimatedHalf;
            if (parser != null || node.annotations != null) {
                node.setAnnotation("refactoredBytecodeSize", newEstimatedSize);
            }
            long originalSize = estimatedSize;
            if (newEstimatedSizeWithSafetyMargin > LARGE_BYTECODE_SIZE) {
                if (parser != null || node.annotations != null) {
                    node.setAnnotation("refactorSkipReason", "Refactoring failed: size " + newEstimatedSize + " still > threshold " + LARGE_BYTECODE_SIZE);
                }
                errorCantRefactorLargeBlock(node.tokenIndex, parser, newEstimatedSize);
            }
            if (parser != null || node.annotations != null) {
                node.setAnnotation("refactorSkipReason", "Successfully refactored: " + originalSize + " -> " + newEstimatedSize + " bytes");
            }
        }

        // If refactoring didn't help and block is still too large, throw an error
        long finalEstimatedSize = estimateTotalBytecodeSize(node.elements);
        long finalEstimatedHalf = finalEstimatedSize / 2;
        long finalEstimatedSizeWithSafetyMargin = finalEstimatedSize > Long.MAX_VALUE - finalEstimatedHalf ? Long.MAX_VALUE : finalEstimatedSize + finalEstimatedHalf;
        if (finalEstimatedSizeWithSafetyMargin > LARGE_BYTECODE_SIZE) {
            if (parser != null || node.annotations != null) {
                node.setAnnotation("refactorSkipReason", "Refactoring didn't reduce element count, size " + finalEstimatedSize + " > threshold " + LARGE_BYTECODE_SIZE);
            }
            errorCantRefactorLargeBlock(node.tokenIndex, parser, finalEstimatedSize);
        }
        if (parser != null || node.annotations != null) {
            node.setAnnotation("refactorSkipReason", "Refactoring didn't reduce element count, but size " + finalEstimatedSize + " <= threshold " + LARGE_BYTECODE_SIZE);
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
        ControlFlowFinder finder = controlFlowFinderTl.get();
        finder.scan(element);
        return finder.foundControlFlow;
    }

    /**
     * Try to refactor the entire block as a subroutine.
     */
    private static boolean tryWholeBlockRefactoring(EmitterVisitor emitterVisitor, BlockNode node) {
        // Check for unsafe control flow using ControlFlowDetectorVisitor
        // This properly handles loop depth - unlabeled next/last/redo inside loops are safe
        controlFlowDetector.reset();
        controlFlowDetector.scan(node);
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

    private static BlockNode createBlockNode(List<Node> elements, int tokenIndex, ThreadLocal<Boolean> skipRefactoring) {
        BlockNode block;
        skipRefactoring.set(true);
        try {
            block = new BlockNode(elements, tokenIndex);
        } finally {
            skipRefactoring.set(false);
        }
        enqueueForRefactor(block);
        return block;
    }

}
