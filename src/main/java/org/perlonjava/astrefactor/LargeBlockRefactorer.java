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

    private static final ThreadLocal<ControlFlowFinder> controlFlowFinderTl = ThreadLocal.withInitial(ControlFlowFinder::new);

    private static final int FORCE_REFACTOR_ELEMENT_COUNT = 50000;

    // Target size for extracted chunks.
    // Larger values reduce the number of generated anon-sub chunks (and therefore classes),
    // which is important for very large modules under JPERL_LARGECODE=refactor (e.g. ExifTool).
    // Keep this meaningfully below LARGE_BYTECODE_SIZE to allow headroom for estimator error and
    // for the wrapper call overhead.
    private static final int TARGET_CHUNK_BYTECODE_SIZE;

    static {
        int defaultTarget = Math.max(8_000, (int) (LARGE_BYTECODE_SIZE * 0.80));
        int v = parseEnvInt("JPERL_TARGET_CHUNK_BYTECODE_SIZE", defaultTarget);
        TARGET_CHUNK_BYTECODE_SIZE = Math.max(4_000, Math.min(LARGE_BYTECODE_SIZE - 1_000, v));
    }

    private static final int MAX_REFACTOR_ATTEMPTS = 3;

    // Cap on how many statements we pack into a single extracted block chunk.
    // This is separate from LargeNodeRefactorer.MAX_CHUNK_SIZE (which applies to list literals).
    private static final int MAX_BLOCK_CHUNK_SIZE = Math.max(16, Math.min(50_000,
            parseEnvInt("JPERL_MAX_BLOCK_CHUNK_SIZE", 2000)));

    private static final boolean PARSE_REFACTOR_AGGRESSIVE = System.getenv("JPERL_PARSE_REFACTOR_AGGRESSIVE") != null;

    // Parse-time max nesting budget for LargeBlockRefactorer.
    // Cache this value to avoid repeated System.getenv() calls while constructing many BlockNodes.
    private static final int PARSE_REFACTOR_MAX_NESTED;

    static {
        int defaultBudget = PARSE_REFACTOR_AGGRESSIVE ? 1024 : 512;
        int parseBudget = parseEnvInt("JPERL_PARSE_REFACTOR_MAX_NESTED", defaultBudget);
        PARSE_REFACTOR_MAX_NESTED = Math.max(1, Math.min(2048, parseBudget));
    }

    private static int parseEnvInt(String name, int defaultValue) {
        String raw = System.getenv(name);
        if (raw == null || raw.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static boolean containsScopeDeclarations(List<Node> elements) {
        if (elements == null || elements.isEmpty()) {
            return false;
        }
        for (Node element : elements) {
            if (element == null) {
                continue;
            }
            if (org.perlonjava.astvisitor.FindDeclarationVisitor.findOperator(element, "my") != null) {
                return true;
            }
            if (org.perlonjava.astvisitor.FindDeclarationVisitor.findOperator(element, "state") != null) {
                return true;
            }
            if (org.perlonjava.astvisitor.FindDeclarationVisitor.findOperator(element, "local") != null) {
                return true;
            }
        }
        return false;
    }

    private static boolean chunkBlockLikelyTooLarge(BlockNode block) {
        if (block == null) {
            return false;
        }
        // Use a capped estimate to avoid spending too much time on huge blocks.
        long estimated = estimateTotalBytecodeSizeCapped(block.elements, LARGE_BYTECODE_SIZE);
        return estimated > LARGE_BYTECODE_SIZE;
    }

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
                                                    int minChunkSize,
                                                    int targetChunkBytecodeSize) {
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
            if (chunkEstimatedSize + candidateSize + suffixEstimatedSize <= targetChunkBytecodeSize) {
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

        // Force refactor regardless of estimated bytecode size.
        // This entry point is used after we already hit MethodTooLargeException, and the estimator
        // can under-estimate (especially for complex nodes). If we keep trusting the estimate here,
        // we may incorrectly skip refactoring and repeatedly fail codegen (e.g. anon* chunk bodies).
        node.setAnnotation("forceRefactorForCodegen", true);

        // The estimator can under-estimate; if we reached codegen overflow, we must allow another pass.
        node.setAnnotation("blockAlreadyRefactored", false);

        // More aggressive than parse-time: allow deeper nesting to ensure we get under the JVM limit.
        // This may be tuned down to avoid excessive nesting depth (which can cause StackOverflowError
        // during compilation due to deeply nested anonymous sub calls).
        int maxNested = parseEnvInt("JPERL_FORCE_REFACTOR_MAX_NESTED", 1024);
        maxNested = Math.max(1, Math.min(2048, maxNested));
        trySmartChunking(node, null, maxNested);
        processPendingRefactors();
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
        // Default parse-time budget is conservative. If this budget is too low, codegen can still
        // hit MethodTooLargeException and require an emit-time retry pass which re-emits the same AST.
        // Increasing this budget at parse time can avoid the need for a second emission pass.
        trySmartChunking(node, parser, PARSE_REFACTOR_MAX_NESTED);

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
    private static void trySmartChunking(BlockNode node, Parser parser, int maxNestedClosures) {
        // Minimal check: skip very small blocks to avoid estimation overhead
        if (node.elements.size() <= MIN_CHUNK_SIZE) {
            if (parser != null || node.annotations != null) {
                node.setAnnotation("refactorSkipReason", "Element count " + node.elements.size() + " <= " + MIN_CHUNK_SIZE + " (minimal threshold)");
            }
            return;
        }

        boolean forceRefactor = node.getBooleanAnnotation("forceRefactorForCodegen");

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
        if (!forceRefactor && !forceRefactorByElementCount && estimatedSizeWithSafetyMargin <= LARGE_BYTECODE_SIZE) {
            if (parser != null || node.annotations != null) {
                node.setAnnotation("refactorSkipReason", "Bytecode size " + estimatedSize + " <= threshold " + LARGE_BYTECODE_SIZE);
            }
            return;
        }

        int effectiveMinChunkSize = MIN_CHUNK_SIZE;

        // If the block contains scope-affecting declarations (my/state/local), we must preserve
        // lexical visibility across chunk boundaries. In practice this means we often need fewer,
        // larger chunks to avoid exhausting the nesting budget (which can otherwise force a giant
        // suffix into a single closure and OOM).
        boolean needsScopePreservation = containsScopeDeclarations(node.elements);
        int targetChunkBytecodeSizeEffective = needsScopePreservation
                ? Math.max(TARGET_CHUNK_BYTECODE_SIZE, (int) (LARGE_BYTECODE_SIZE * 0.92))
                : TARGET_CHUNK_BYTECODE_SIZE;

        int maxNestedClosuresEffective = (int) Math.min(
                maxNestedClosures,
                Math.max(1L, (estimatedSizeWithSafetyMargin + targetChunkBytecodeSizeEffective - 1) / targetChunkBytecodeSizeEffective)
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
        boolean treatAllElementsAsSafe = !hasLabelElement && !hasAnyControlFlowInBlock;

        if (!needsScopePreservation) {
            // If there are no scope-affecting declarations (my/state/local), we can split safe
            // runs into independent chunk closures without nesting. This avoids the nested
            // tail strategy which can allocate very large intermediate lists when the suffix
            // is repeatedly copied into chunk bodies.
            List<Node> rewritten = new ArrayList<>(node.elements.size());
            int i = 0;
            int closuresEmitted = 0;
            while (i < node.elements.size()) {
                Node element = node.elements.get(i);
                if (element == null || shouldBreakChunk(element)) {
                    rewritten.add(element);
                    i++;
                    continue;
                }

                int runStart = i;
                while (i < node.elements.size()) {
                    Node el = node.elements.get(i);
                    if (el == null || shouldBreakChunk(el)) {
                        break;
                    }
                    i++;
                }
                int runEndExclusive = i;
                int runLen = runEndExclusive - runStart;

                if (runLen < effectiveMinChunkSize) {
                    for (int j = runStart; j < runEndExclusive; j++) {
                        rewritten.add(node.elements.get(j));
                    }
                    continue;
                }

                long runningSize = 0;
                int chunkStart = runStart;
                while (chunkStart < runEndExclusive) {
                    if (closuresEmitted >= maxNestedClosuresEffective) {
                        for (int j = chunkStart; j < runEndExclusive; j++) {
                            rewritten.add(node.elements.get(j));
                        }
                        break;
                    }

                    int chunkEndExclusive = chunkStart;
                    int chunkLen = 0;
                    runningSize = 0;
                    while (chunkEndExclusive < runEndExclusive) {
                        Node el = node.elements.get(chunkEndExclusive);
                        long elSize = el == null ? 0 : BytecodeSizeEstimator.estimateSnippetSize(el);
                        if (chunkLen >= effectiveMinChunkSize && runningSize + elSize > targetChunkBytecodeSizeEffective) {
                            break;
                        }
                        if (chunkLen >= MAX_BLOCK_CHUNK_SIZE) {
                            break;
                        }
                        runningSize += elSize;
                        chunkEndExclusive++;
                        chunkLen++;
                    }

                    if (chunkEndExclusive <= chunkStart) {
                        rewritten.add(node.elements.get(chunkStart));
                        chunkStart++;
                        continue;
                    }

                    if (chunkLen < effectiveMinChunkSize) {
                        for (int j = chunkStart; j < chunkEndExclusive; j++) {
                            rewritten.add(node.elements.get(j));
                        }
                        chunkStart = chunkEndExclusive;
                        continue;
                    }

                    List<Node> blockElements = new ArrayList<>(chunkLen);
                    for (int j = chunkStart; j < chunkEndExclusive; j++) {
                        blockElements.add(node.elements.get(j));
                    }
                    BlockNode block = createBlockNode(blockElements, node.tokenIndex, skipRefactoring);
                    if (chunkBlockLikelyTooLarge(block)) {
                        enqueueForRefactor(block);
                    }
                    rewritten.add(createAnonSubCall(node.tokenIndex, block));
                    closuresEmitted++;
                    chunkStart = chunkEndExclusive;
                }
            }

            boolean didChange = rewritten.size() < node.elements.size();
            if (didChange) {
                long rewrittenSize = estimateTotalBytecodeSizeCapped(rewritten, (long) LARGE_BYTECODE_SIZE * maxNestedClosures);
                long half = rewrittenSize / 2;
                long rewrittenWithMargin = rewrittenSize > Long.MAX_VALUE - half ? Long.MAX_VALUE : rewrittenSize + half;
                if (rewrittenWithMargin <= LARGE_BYTECODE_SIZE) {
                    node.elements = rewritten;
                    node.setAnnotation("blockAlreadyRefactored", true);
                    if (parser != null || node.annotations != null) {
                        node.setAnnotation("refactorSkipReason", "Successfully refactored (flat no-scope): closures=" + closuresEmitted);
                        node.setAnnotation("refactoredBytecodeSize", rewrittenSize);
                    }
                    return;
                }
            }
        }

        if (treatAllElementsAsSafe) {
            // All elements are safe to chunk: avoid suffix duplication.
            // The generic tail-chunking algorithm copies the growing suffix into each closure body,
            // which can allocate huge intermediate lists and OOM on very large safe blocks.
            //
            // Build a chunk-only nested chain:
            //   sub { chunk1; sub { chunk2; sub { chunk3; ... }->(@_) }->(@_) }->(@_)
            // Each chunk appears exactly once, and lexical declarations are preserved because
            // later chunks execute inside the same closure scope.
            List<Node> originalElements = node.elements;
            int endExclusive = originalElements.size();
            Node tail = null;
            long suffixSize = 0;
            int safeClosuresCreated = 0;

            while (endExclusive > 0 && safeClosuresCreated < maxNestedClosuresEffective) {
                int chunkStart = findChunkStartByEstimatedSize(
                        originalElements,
                        0,
                        endExclusive,
                        suffixSize,
                        effectiveMinChunkSize,
                        targetChunkBytecodeSizeEffective
                );
                int chunkLen = endExclusive - chunkStart;
                if (chunkLen <= 0) {
                    break;
                }

                // Stop if the remaining prefix is too small to justify another closure.
                // We'll keep it as direct elements before the final tail closure.
                if (chunkLen < effectiveMinChunkSize && tail != null) {
                    break;
                }

                List<Node> blockElements = new ArrayList<>(chunkLen + (tail != null ? 1 : 0));
                for (int i = chunkStart; i < endExclusive; i++) {
                    blockElements.add(originalElements.get(i));
                }
                if (tail != null) {
                    blockElements.add(tail);
                }

                BlockNode block = createBlockNode(blockElements, node.tokenIndex, skipRefactoring);
                if (chunkBlockLikelyTooLarge(block)) {
                    enqueueForRefactor(block);
                }
                tail = createAnonSubCall(node.tokenIndex, block);
                suffixSize = BytecodeSizeEstimator.estimateSnippetSize(tail);
                safeClosuresCreated++;
                endExclusive = chunkStart;
            }

            if (tail == null) {
                if (parser != null || node.annotations != null) {
                    node.setAnnotation("refactorSkipReason", "No chunk >= effective min chunk size " + effectiveMinChunkSize);
                }
                return;
            }

            List<Node> processedElements = new ArrayList<>(endExclusive + 1);
            for (int i = 0; i < endExclusive; i++) {
                processedElements.add(originalElements.get(i));
            }
            processedElements.add(tail);

            long finalEstimatedSize = estimateTotalBytecodeSizeCapped(processedElements, (long) LARGE_BYTECODE_SIZE * maxNestedClosures);
            long finalEstimatedHalf = finalEstimatedSize / 2;
            long finalEstimatedSizeWithSafetyMargin = finalEstimatedSize > Long.MAX_VALUE - finalEstimatedHalf
                    ? Long.MAX_VALUE
                    : finalEstimatedSize + finalEstimatedHalf;

            if (finalEstimatedSizeWithSafetyMargin > LARGE_BYTECODE_SIZE) {
                if (parser != null || node.annotations != null) {
                    node.setAnnotation("refactorSkipReason", "Refactoring failed: size " + finalEstimatedSize + " still > threshold " + LARGE_BYTECODE_SIZE);
                }
                return;
            }

            node.elements = processedElements;
            node.setAnnotation("blockAlreadyRefactored", true);
            if (parser != null || node.annotations != null) {
                node.setAnnotation("refactorSkipReason", "Successfully refactored (safe chunk-only chain): closures=" + safeClosuresCreated);
                node.setAnnotation("refactoredBytecodeSize", finalEstimatedSize);
            }
            return;
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
                                effectiveMinChunkSize,
                                targetChunkBytecodeSizeEffective
                        );
                        int chunkLen = safeRunEndExclusive - chunkStart;

                        if (chunkLen <= 0) {
                            break;
                        }

                        // Avoid repeatedly copying the growing suffix into every new chunk closure.
                        // Instead, wrap the current suffix into a single tail closure once.
                        if (!suffixReversed.isEmpty()) {
                            List<Node> tailElements = new ArrayList<>(suffixReversed.size() + (tailClosure != null ? 1 : 0));
                            for (int k = suffixReversed.size() - 1; k >= 0; k--) {
                                tailElements.add(suffixReversed.get(k));
                            }
                            if (tailClosure != null) {
                                tailElements.add(tailClosure);
                            }
                            BlockNode tailBlock = createBlockNode(tailElements, node.tokenIndex, skipRefactoring);
                            if (chunkBlockLikelyTooLarge(tailBlock)) {
                                enqueueForRefactor(tailBlock);
                            }
                            tailClosure = createAnonSubCall(node.tokenIndex, tailBlock);
                            suffixEstimatedSize = BytecodeSizeEstimator.estimateSnippetSize(tailClosure);
                            suffixReversed.clear();
                        }

                        List<Node> blockElements = new ArrayList<>(chunkLen + (tailClosure != null ? 1 : 0));
                        for (int j = chunkStart; j < safeRunEndExclusive; j++) {
                            blockElements.add(node.elements.get(j));
                        }
                        if (tailClosure != null) {
                            blockElements.add(tailClosure);
                        }

                        BlockNode block = createBlockNode(blockElements, node.tokenIndex, skipRefactoring);
                        // The BlockNode constructor skips refactoring when invoked from createBlockNode
                        // (skipRefactoring=true). Only enqueue chunk blocks that are still likely too large,
                        // otherwise we can create an excessive number of refactor tasks and blow memory.
                        if (chunkBlockLikelyTooLarge(block)) {
                            enqueueForRefactor(block);
                        }
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
                        effectiveMinChunkSize,
                        targetChunkBytecodeSizeEffective
                );
                int chunkLen = safeRunEndExclusive - chunkStart;

                if (chunkLen <= 0) {
                    break;
                }

                // Avoid repeatedly copying the growing suffix into every new chunk closure.
                // Instead, wrap the current suffix into a single tail closure once.
                if (!suffixReversed.isEmpty()) {
                    List<Node> tailElements = new ArrayList<>(suffixReversed.size() + (tailClosure != null ? 1 : 0));
                    for (int k = suffixReversed.size() - 1; k >= 0; k--) {
                        tailElements.add(suffixReversed.get(k));
                    }
                    if (tailClosure != null) {
                        tailElements.add(tailClosure);
                    }
                    BlockNode tailBlock = createBlockNode(tailElements, node.tokenIndex, skipRefactoring);
                    if (chunkBlockLikelyTooLarge(tailBlock)) {
                        enqueueForRefactor(tailBlock);
                    }
                    tailClosure = createAnonSubCall(node.tokenIndex, tailBlock);
                    suffixEstimatedSize = BytecodeSizeEstimator.estimateSnippetSize(tailClosure);
                    suffixReversed.clear();
                }

                List<Node> blockElements = new ArrayList<>(chunkLen + (tailClosure != null ? 1 : 0));
                for (int j = chunkStart; j < safeRunEndExclusive; j++) {
                    blockElements.add(node.elements.get(j));
                }
                if (tailClosure != null) {
                    blockElements.add(tailClosure);
                }

                BlockNode block = createBlockNode(blockElements, node.tokenIndex, skipRefactoring);
                // See comment above: only enqueue chunk blocks that still look too large.
                if (chunkBlockLikelyTooLarge(block)) {
                    enqueueForRefactor(block);
                }
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
        return block;
    }

}
