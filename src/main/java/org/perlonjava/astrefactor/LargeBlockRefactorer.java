package org.perlonjava.astrefactor;

import org.perlonjava.astnode.*;
import org.perlonjava.astvisitor.ControlFlowDetectorVisitor;
import org.perlonjava.astvisitor.ControlFlowFinder;
import org.perlonjava.astvisitor.EmitterVisitor;
import org.perlonjava.astvisitor.BytecodeSizeEstimator;
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

    private static final ThreadLocal<Boolean> forceRefactoringForCodegen = ThreadLocal.withInitial(() -> false);

    private static final ThreadLocal<ControlFlowFinder> controlFlowFinderTl = ThreadLocal.withInitial(ControlFlowFinder::new);

    private static final int FORCE_REFACTOR_ELEMENT_COUNT = 50000;
    private static final int TARGET_CHUNK_BYTECODE_SIZE = LARGE_BYTECODE_SIZE / 2;

    private static final int MAX_REFACTOR_ATTEMPTS = 3;

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

    private static int findChunkStartByEstimatedSize(List<Node> elements,
                                                    int safeRunStart,
                                                    int safeRunEndExclusive,
                                                    long suffixEstimatedSize,
                                                    int minChunkSize) {
        int chunkStart = safeRunEndExclusive;
        long chunkEstimatedSize = 0;
        while (chunkStart > safeRunStart) {
            Node candidate = elements.get(chunkStart - 1);
            long candidateSize = candidate == null ? 0 : BytecodeSizeEstimator.estimateSnippetSize(candidate);
            int candidateChunkLen = safeRunEndExclusive - (chunkStart - 1);
            if (candidateChunkLen < minChunkSize) {
                chunkStart--;
                chunkEstimatedSize += candidateSize;
                continue;
            }
            if (chunkEstimatedSize + candidateSize + suffixEstimatedSize <= TARGET_CHUNK_BYTECODE_SIZE) {
                chunkStart--;
                chunkEstimatedSize += candidateSize;
                continue;
            }
            break;
        }

        if (safeRunEndExclusive - chunkStart < minChunkSize) {
            chunkStart = Math.max(safeRunStart, safeRunEndExclusive - minChunkSize);
        }
        return chunkStart;
    }

    private static final ThreadLocal<Deque<BlockNode>> pendingRefactorBlocks = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<Boolean> processingPendingRefactors = ThreadLocal.withInitial(() -> false);

    public static void enqueueForRefactor(BlockNode node) {
        if ((!IS_REFACTORING_ENABLED && !forceRefactoringForCodegen.get()) || node == null) {
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

    public static void forceRefactorForCodegen(BlockNode node) {
        if (!IS_REFACTORING_ENABLED || node == null) {
            return;
        }
        Object attemptsObj = node.getAnnotation("refactorAttempts");
        int attempts = attemptsObj instanceof Integer ? (Integer) attemptsObj : 0;
        if (attempts >= MAX_REFACTOR_ATTEMPTS) {
            return;
        }
        node.setAnnotation("refactorAttempts", attempts + 1);

        // The estimator can under-estimate; if we reached codegen overflow, we must allow another pass.
        node.setAnnotation("blockAlreadyRefactored", false);
        node.setAnnotation("forceRefactorForCodegen", true);

        // More aggressive than parse-time: allow deeper nesting to ensure we get under the JVM limit.
        trySmartChunking(node, null, 256);
        processPendingRefactors();
    }

    public static void forceRefactorForCodegenEvenIfDisabled(BlockNode node) {
        if (node == null) {
            return;
        }

        Object attemptsObj = node.getAnnotation("refactorAttempts");
        int attempts = attemptsObj instanceof Integer ? (Integer) attemptsObj : 0;
        if (attempts >= MAX_REFACTOR_ATTEMPTS) {
            return;
        }
        node.setAnnotation("refactorAttempts", attempts + 1);

        // The estimator can under-estimate; if we reached codegen overflow, we must allow another pass.
        node.setAnnotation("blockAlreadyRefactored", false);
        node.setAnnotation("forceRefactorForCodegen", true);

        forceRefactoringForCodegen.set(true);
        try {
            trySmartChunking(node, null, 256);
            processPendingRefactors();
        } finally {
            forceRefactoringForCodegen.set(false);
            node.setAnnotation("forceRefactorForCodegen", false);
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

        // Skip if already successfully refactored (prevents infinite recursion)
        if (node.getBooleanAnnotation("blockAlreadyRefactored")) {
            if (parser != null || node.annotations != null) {
                node.setAnnotation("refactorSkipReason", "Already refactored");
            }
            return;
        }

        Object attemptsObj = node.getAnnotation("refactorAttempts");
        int attempts = attemptsObj instanceof Integer ? (Integer) attemptsObj : 0;
        if (attempts >= MAX_REFACTOR_ATTEMPTS) {
            if (parser != null || node.annotations != null) {
                node.setAnnotation("refactorSkipReason", "Refactor attempt limit reached: " + attempts);
            }
            return;
        }
        node.setAnnotation("refactorAttempts", attempts + 1);

        // Skip special blocks (BEGIN, END, etc.)
        if (isSpecialContext(node)) {
            if (parser != null || node.annotations != null) {
                node.setAnnotation("refactorSkipReason", "Special block (BEGIN/END/etc)");
            }
            return;
        }

        // Apply smart chunking
        trySmartChunking(node, parser, 64);

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

        // Codegen-time refactoring must be conservative.
        // Wrapping statements in extracted closures changes control-flow semantics by losing the
        // enclosing loop label stack. Only refactor when explicitly enabled and the block is
        // actually large enough to risk hitting JVM method-size limits.
        //
        // If you need to re-enable/extend codegen-time refactoring in the future, do NOT wrap
        // arbitrary statements into closures inside a loop body. In particular, moving
        // `next/last/redo/goto` into an extracted closure will make codegen see an empty
        // loop-label stack and compile these as non-local control flow.
        //
        // Safe options (in order of preference):
        // - Keep any loop-control statements in the original method (never move them into a closure).
        // - If you must extract, add an explicit mechanism to propagate and handle loop control across
        //   the extracted boundary (e.g. via RuntimeControlFlowRegistry + loop boundary checks, or by
        //   implementing/turning on loop handlers/call-site checks).
        if (!refactorEnabled) {
            return false;
        }

        long estimatedSize = estimateTotalBytecodeSizeCapped(node.elements, LARGE_BYTECODE_SIZE);
        return estimatedSize > LARGE_BYTECODE_SIZE;
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
    private static void trySmartChunking(BlockNode node, Parser parser, int maxNestedClosures) {
        // Minimal check: skip very small blocks to avoid estimation overhead
        if (node.elements.size() <= MIN_CHUNK_SIZE) {
            if (parser != null || node.annotations != null) {
                node.setAnnotation("refactorSkipReason", "Element count " + node.elements.size() + " <= " + MIN_CHUNK_SIZE + " (minimal threshold)");
            }
            return;
        }

        // Check bytecode size - skip if under threshold.
        // IMPORTANT: use a larger cap here so we can compute a meaningful maxNestedClosuresEffective.
        long estimatedSize = estimateTotalBytecodeSizeCapped(node.elements, (long) LARGE_BYTECODE_SIZE * maxNestedClosures);
        long estimatedHalf = estimatedSize / 2;
        long estimatedSizeWithSafetyMargin = estimatedSize > Long.MAX_VALUE - estimatedHalf ? Long.MAX_VALUE : estimatedSize + estimatedHalf;
        if (parser != null || node.annotations != null) {
            node.setAnnotation("estimatedBytecodeSize", estimatedSize);
            node.setAnnotation("estimatedBytecodeSizeWithSafetyMargin", estimatedSizeWithSafetyMargin);
        }
        boolean forceRefactorByElementCount = node.elements.size() >= FORCE_REFACTOR_ELEMENT_COUNT;
        boolean forceRefactorForCodegen = node.getBooleanAnnotation("forceRefactorForCodegen");
        if (!forceRefactorForCodegen && !forceRefactorByElementCount && estimatedSizeWithSafetyMargin <= LARGE_BYTECODE_SIZE) {
            if (parser != null || node.annotations != null) {
                node.setAnnotation("refactorSkipReason", "Bytecode size " + estimatedSize + " <= threshold " + LARGE_BYTECODE_SIZE);
            }
            return;
        }

        int effectiveMinChunkSize = MIN_CHUNK_SIZE;

        int maxNestedClosuresEffective = (int) Math.min(
                maxNestedClosures,
                Math.max(1L, (estimatedSizeWithSafetyMargin + TARGET_CHUNK_BYTECODE_SIZE - 1) / TARGET_CHUNK_BYTECODE_SIZE)
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
        long suffixEstimatedSize = 0;

        int safeRunEndExclusive = node.elements.size();
        int safeRunLen = 0;
        boolean safeRunActive = false;

        boolean hasLabelElement = false;
        for (Node el : node.elements) {
            if (el instanceof LabelNode) {
                hasLabelElement = true;
                break;
            }
        }
        ControlFlowFinder blockFinder = controlFlowFinderTl.get();
        blockFinder.scan(node);
        boolean hasAnyControlFlowInBlock = blockFinder.foundControlFlow;

        // IMPORTANT: Smart chunking wraps segments in extracted closures.
        // Loop control operators (next/last/redo/goto) must remain in the original scope
        // so codegen can resolve loop labels and emit local jumps.
        // Even when chunk boundaries try to "break" on control flow, the suffix stitching
        // can still place the control-flow node inside a generated closure.
        //
        // Be conservative: if the block contains ANY control flow, do not chunk it.
        boolean forceRefactorForCodegenForControlFlow = node.getBooleanAnnotation("forceRefactorForCodegen");
        if (hasAnyControlFlowInBlock && !forceRefactorForCodegenForControlFlow) {
            if (parser != null || node.annotations != null) {
                node.setAnnotation("refactorSkipReason", "Smart chunking skipped: block contains control flow");
            }
            return;
        }
        boolean treatAllElementsAsSafe = !hasLabelElement && !hasAnyControlFlowInBlock;

        if (treatAllElementsAsSafe) {
            safeRunActive = true;
            safeRunLen = node.elements.size();
            safeRunEndExclusive = node.elements.size();
        } else {

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

                        int chunkStart = findChunkStartByEstimatedSize(
                                node.elements,
                                safeRunStart,
                                safeRunEndExclusive,
                                suffixEstimatedSize,
                                effectiveMinChunkSize
                        );
                        int chunkLen = safeRunEndExclusive - chunkStart;

                        if (chunkLen <= 0) {
                            break;
                        }

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
                        suffixEstimatedSize = BytecodeSizeEstimator.estimateSnippetSize(tailClosure);
                        suffixReversed.clear();
                        createdAnyClosure = true;
                        closuresCreated++;

                        safeRunEndExclusive = chunkStart;
                        safeRunLen -= chunkLen;
                    }

                    safeRunStart = safeRunEndExclusive - safeRunLen;
                    for (int j = safeRunEndExclusive - 1; j >= safeRunStart; j--) {
                        suffixReversed.add(node.elements.get(j));
                        suffixEstimatedSize += BytecodeSizeEstimator.estimateSnippetSize(node.elements.get(j));
                    }

                    safeRunActive = false;
                    safeRunLen = 0;
                }

                suffixReversed.add(element);
                suffixEstimatedSize += BytecodeSizeEstimator.estimateSnippetSize(element);
                safeRunEndExclusive = i;
            }
        }

        if (safeRunActive) {
            int safeRunStart = safeRunEndExclusive - safeRunLen;
            while (safeRunLen >= effectiveMinChunkSize) {
                int remainingBudget = maxNestedClosuresEffective - closuresCreated;
                if (remainingBudget <= 0) {
                    break;
                }

                int chunkStart = findChunkStartByEstimatedSize(
                        node.elements,
                        safeRunStart,
                        safeRunEndExclusive,
                        suffixEstimatedSize,
                        effectiveMinChunkSize
                );
                int chunkLen = safeRunEndExclusive - chunkStart;

                if (chunkLen <= 0) {
                    break;
                }

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
                suffixEstimatedSize = BytecodeSizeEstimator.estimateSnippetSize(tailClosure);
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

        boolean didReduceElementCount = processedElements.size() < node.elements.size();
        long originalSize = estimatedSize;

        // Apply chunking if we reduced the element count
        if (didReduceElementCount) {
            node.elements = processedElements;
        }

        // Single verification pass after applying (or not applying) chunking.
        long finalEstimatedSize = didReduceElementCount
                ? estimateTotalBytecodeSizeCapped(node.elements, (long) LARGE_BYTECODE_SIZE * maxNestedClosures)
                : estimatedSize;
        long finalEstimatedHalf = finalEstimatedSize / 2;
        long finalEstimatedSizeWithSafetyMargin = finalEstimatedSize > Long.MAX_VALUE - finalEstimatedHalf ? Long.MAX_VALUE : finalEstimatedSize + finalEstimatedHalf;
        if (parser != null || node.annotations != null) {
            if (didReduceElementCount) {
                node.setAnnotation("refactoredBytecodeSize", finalEstimatedSize);
            }
        }

        if (finalEstimatedSizeWithSafetyMargin > LARGE_BYTECODE_SIZE) {
            if (parser != null || node.annotations != null) {
                if (didReduceElementCount) {
                    node.setAnnotation("refactorSkipReason", "Refactoring failed: size " + finalEstimatedSize + " still > threshold " + LARGE_BYTECODE_SIZE);
                } else {
                    node.setAnnotation("refactorSkipReason", "Refactoring didn't reduce element count, size " + finalEstimatedSize + " > threshold " + LARGE_BYTECODE_SIZE);
                }
            }
            return;
        }

        if (parser != null || node.annotations != null) {
            if (didReduceElementCount) {
                node.setAnnotation("refactorSkipReason", "Successfully refactored: " + originalSize + " -> " + finalEstimatedSize + " bytes");
            } else {
                node.setAnnotation("refactorSkipReason", "Refactoring didn't reduce element count, but size " + finalEstimatedSize + " <= threshold " + LARGE_BYTECODE_SIZE);
            }
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
        // IMPORTANT: wrapping a block in an extracted closure/subroutine loses the enclosing
        // loop label stack. Even control flow that is "safe" in-place (e.g. unlabeled `next`
        // inside a real loop) becomes unsafe when moved into a closure because codegen will
        // not find loop labels and will emit non-local tagged returns.
        //
        // Therefore, be conservative here: if the block contains ANY control flow statement,
        // do not refactor the whole block into a subroutine.
        ControlFlowFinder finder = controlFlowFinderTl.get();
        finder.scan(node);
        if (finder.foundControlFlow) {
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
        // Same reasoning as tryWholeBlockRefactoring(): moving a chunk into a closure loses
        // loop context, so ANY control flow statement makes the chunk unsafe.
        ControlFlowFinder finder = controlFlowFinderTl.get();
        for (Node element : chunk) {
            finder.scan(element);
            if (finder.foundControlFlow) {
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
        return block;
    }

}
