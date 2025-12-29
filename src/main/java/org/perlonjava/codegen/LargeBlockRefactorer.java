package org.perlonjava.codegen;

import org.perlonjava.astnode.*;
import org.perlonjava.astrefactor.BlockRefactor;
import org.perlonjava.astvisitor.BytecodeSizeEstimator;
import org.perlonjava.astvisitor.ControlFlowDetectorVisitor;
import org.perlonjava.astvisitor.EmitterVisitor;
import org.perlonjava.parser.Parser;
import org.perlonjava.runtime.PerlCompilerException;

import java.util.ArrayList;
import java.util.List;

/**
 * Helper class for refactoring large blocks to avoid JVM's "Method too large" error.
 * <p>
 * This class encapsulates all logic for detecting and transforming large blocks,
 * including smart chunking strategies and control flow analysis.
 */
public class LargeBlockRefactorer {

    // Configuration thresholds
    private static final int LARGE_BLOCK_ELEMENT_COUNT = 50;  // Minimum elements before considering refactoring
    private static final int LARGE_BYTECODE_SIZE = 40000;
    private static final int MIN_CHUNK_SIZE = 4;  // Minimum statements to extract as a chunk

    // Reusable visitor for control flow detection
    private static final ControlFlowDetectorVisitor controlFlowDetector = new ControlFlowDetectorVisitor();

    // Thread-local flag to prevent recursion when creating chunk blocks
    private static final ThreadLocal<Boolean> skipRefactoring = ThreadLocal.withInitial(() -> false);

    /**
     * Check if refactoring is enabled via environment variable.
     */
    private static boolean isRefactoringEnabled() {
        String largeCodeMode = System.getenv("JPERL_LARGECODE");
        return "refactor".equals(largeCodeMode);
    }

    /**
     * Parse-time entry point: called from BlockNode constructor to refactor large blocks.
     * This applies smart chunking to split safe statement sequences into closures.
     *
     * @param node   The block to potentially refactor (modified in place)
     * @param parser The parser instance for access to error utilities (can be null if not available)
     */
    public static void maybeRefactorBlock(BlockNode node, Parser parser) {
        // Skip if refactoring is not enabled
        if (!isRefactoringEnabled()) {
            return;
        }

        // Skip if we're inside createMarkedBlock (prevents recursion)
        if (skipRefactoring.get()) {
            return;
        }

        // Skip if already refactored (prevents infinite recursion)
        if (node.getBooleanAnnotation("blockAlreadyRefactored")) {
            return;
        }

        // Skip small blocks
        if (node.elements.size() <= LARGE_BLOCK_ELEMENT_COUNT) {
            return;
        }

        // Skip special blocks (BEGIN, END, etc.)
        if (isSpecialContext(node)) {
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

        // Check if refactoring is enabled via environment variable
        String largeCodeMode = System.getenv("JPERL_LARGECODE");
        boolean refactorEnabled = "refactor".equals(largeCodeMode);

        // Skip if block is already a subroutine or is a special block
        if (node.getBooleanAnnotation("blockIsSubroutine")) {
            return false;
        }

        // Determine if we need to refactor
        boolean needsRefactoring = shouldRefactorBlock(node, emitterVisitor, refactorEnabled);

        if (!needsRefactoring) {
            return false;
        }

        // Skip refactoring for special blocks (BEGIN, END, INIT, CHECK, UNITCHECK)
        // These blocks have special compilation semantics and cannot be refactored
        if (isSpecialContext(node)) {
            return false;
        }

        // TEMPORARILY DISABLED: Smart chunking has timing issues with special blocks (BEGIN/require)
        // Causes NPE in SpecialBlockParser when functions aren't defined yet during compilation
        // if (trySmartChunking(node)) {
        //     // Block was successfully chunked, continue with normal emission
        //     return false;
        // }

        // Fallback: Try whole-block refactoring
        return tryWholeBlockRefactoring(emitterVisitor, node);  // Block was refactored and emitted

        // No refactoring was possible
    }

    /**
     * Determine if a block should be refactored based on size and context.
     */
    private static boolean shouldRefactorBlock(BlockNode node, EmitterVisitor emitterVisitor, boolean refactorEnabled) {
        // Check element count threshold
        if (node.elements.size() <= LARGE_BLOCK_ELEMENT_COUNT) {
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
     * @return true if chunking was successful, false otherwise
     */
    private static boolean trySmartChunking(BlockNode node, Parser parser) {
        // First check if the block contains any control flow that would be unsafe to refactor
        // This catches cases where we're inside a loop body but don't know it yet (loop node not created)
        controlFlowDetector.reset();
        for (Node element : node.elements) {
            element.accept(controlFlowDetector);
            if (controlFlowDetector.hasUnsafeControlFlow()) {
                // Block contains last/next/redo/goto that would break if we wrap in closures
                // Skip refactoring - we cannot safely refactor blocks with control flow
                return false;
            }
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
            } else if (isCompleteBlock(element)) {
                // Complete blocks are already scoped
                if (!currentChunk.isEmpty()) {
                    segments.add(new ArrayList<>(currentChunk));
                    currentChunk.clear();
                }
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

        // Build nested structure if we have any chunks
        List<Node> processedElements = buildNestedStructure(segments, node.tokenIndex);

        // Apply chunking if we reduced the element count
        if (processedElements.size() < node.elements.size()) {
            node.elements.clear();
            node.elements.addAll(processedElements);
            node.setAnnotation("blockAlreadyRefactored", true);
            return true;
        }

        // If refactoring didn't help and block is still too large, throw an error
        if (node.elements.size() > LARGE_BLOCK_ELEMENT_COUNT) {
            long estimatedSize = estimateTotalBytecodeSize(node.elements);
            if (estimatedSize > LARGE_BYTECODE_SIZE) {
                String message = "Block is too large (" + node.elements.size() + " elements, estimated " + estimatedSize + " bytes) " +
                    "and refactoring failed to reduce it below " + LARGE_BYTECODE_SIZE + " bytes. " +
                    "The block contains control flow statements that prevent safe refactoring. " +
                    "Consider breaking the code into smaller subroutines manually.";
                if (parser != null) {
                    throw new PerlCompilerException(node.tokenIndex, message, parser.ctx.errorUtil);
                } else {
                    throw new PerlCompilerException(message);
                }
            }
        }

        return false;
    }

    /**
     * Determine if an element should break the current chunk.
     */
    private static boolean shouldBreakChunk(Node element) {
        // Labels break chunks - they're targets for goto/next/last
        if (element instanceof LabelNode) {
            return true;
        }

        // Control flow statements that jump outside the block break chunks
        controlFlowDetector.reset();
        element.accept(controlFlowDetector);
        if (controlFlowDetector.hasUnsafeControlFlow()) {
            return true;
        }

        // Variable declarations are OK - closures capture lexical variables from outer scope
        return false;
    }

    /**
     * Build nested closure structure from segments.
     * Structure: direct1, direct2, sub{ chunk1, sub{ chunk2, chunk3 }->(@_) }->(@_)
     * Closures are always placed at tail position to preserve variable scoping.
     *
     * @param segments   List of segments (either Node for direct elements or List<Node> for chunks)
     * @param tokenIndex token index for new nodes
     * @return List of processed elements with nested structure
     */
    @SuppressWarnings("unchecked")
    private static List<Node> buildNestedStructure(List<Object> segments, int tokenIndex) {
        if (segments.isEmpty()) {
            return new ArrayList<>();
        }

        List<Node> result = new ArrayList<>();
        
        // Process segments forward, accumulating direct elements and building nested closures at the end
        for (int i = 0; i < segments.size(); i++) {
            Object segment = segments.get(i);

            if (segment instanceof Node directNode) {
                // Direct elements (labels, variable declarations, control flow) stay at block level
                result.add(directNode);
            } else if (segment instanceof List) {
                List<Node> chunk = (List<Node>) segment;
                if (chunk.size() >= MIN_CHUNK_SIZE) {
                    // Create closure for this chunk at tail position
                    // Collect remaining chunks to nest inside this closure
                    List<Node> blockElements = new ArrayList<>(chunk);
                    
                    // Build nested closures for remaining chunks
                    for (int j = i + 1; j < segments.size(); j++) {
                        Object nextSegment = segments.get(j);
                        if (nextSegment instanceof Node) {
                            blockElements.add((Node) nextSegment);
                        } else if (nextSegment instanceof List) {
                            List<Node> nextChunk = (List<Node>) nextSegment;
                            if (nextChunk.size() >= MIN_CHUNK_SIZE) {
                                // Create nested closure for next chunk
                                List<Node> nestedElements = new ArrayList<>(nextChunk);
                                // Add all remaining segments to the nested closure
                                for (int k = j + 1; k < segments.size(); k++) {
                                    Object remainingSegment = segments.get(k);
                                    if (remainingSegment instanceof Node) {
                                        nestedElements.add((Node) remainingSegment);
                                    } else {
                                        nestedElements.addAll((List<Node>) remainingSegment);
                                    }
                                }
                                BlockNode nestedBlock = createMarkedBlock(nestedElements, tokenIndex);
                                Node nestedClosure = BlockRefactor.createAnonSubCall(tokenIndex, nestedBlock);
                                blockElements.add(nestedClosure);
                                j = segments.size(); // Break outer loop
                                break;
                            } else {
                                blockElements.addAll(nextChunk);
                            }
                        }
                    }
                    
                    BlockNode block = createMarkedBlock(blockElements, tokenIndex);
                    Node closure = BlockRefactor.createAnonSubCall(tokenIndex, block);
                    result.add(closure);
                    break; // All remaining segments are now inside the closure
                } else {
                    // Chunk too small - add elements directly
                    result.addAll(chunk);
                }
            }
        }

        return result;
    }

    /**
     * Create a BlockNode that is pre-marked as already refactored.
     * This prevents infinite recursion since BlockNode constructor calls maybeRefactorBlock.
     */
    private static BlockNode createMarkedBlock(List<Node> elements, int tokenIndex) {
        // We need to create the block without triggering maybeRefactorBlock
        // Set a thread-local flag to skip refactoring during this construction
        skipRefactoring.set(true);
        try {
            BlockNode block = new BlockNode(elements, tokenIndex);
            block.setAnnotation("blockAlreadyRefactored", true);
            return block;
        } finally {
            skipRefactoring.set(false);
        }
    }

    /**
     * Try to refactor the entire block as a subroutine.
     */
    private static boolean tryWholeBlockRefactoring(EmitterVisitor emitterVisitor, BlockNode node) {
        // Check for unsafe control flow
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

        BinaryOperatorNode subr = BlockRefactor.createAnonSubCall(tokenIndex, innerBlock);

        // Emit the refactored block
        subr.accept(emitterVisitor);
        return true;
    }

    /**
     * Check if a node contains variable declarations (my, our, local).
     */
    private static boolean hasVariableDeclaration(Node node) {
        // Pattern 1: Direct declaration without assignment
        if (node instanceof OperatorNode op) {
            return "my".equals(op.operator) || "our".equals(op.operator) || "local".equals(op.operator);
        }

        // Pattern 2: Declaration with assignment
        if (node instanceof BinaryOperatorNode bin) {
            if ("=".equals(bin.operator) && bin.left instanceof OperatorNode left) {
                return "my".equals(left.operator) || "our".equals(left.operator) || "local".equals(left.operator);
            }
        }

        return false;
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
     * Estimates the total bytecode size for a list of nodes.
     * Uses sampling for efficiency on large lists.
     *
     * @param nodes the list of nodes to estimate
     * @return estimated total bytecode size in bytes
     */
    private static long estimateTotalBytecodeSize(List<Node> nodes) {
        if (nodes.isEmpty()) {
            return 0;
        }

        // For small lists, calculate exact size
        if (nodes.size() <= 10) {
            long total = 0;
            for (Node node : nodes) {
                total += BytecodeSizeEstimator.estimateSnippetSize(node);
            }
            return total;
        }

        // For large lists, use sampling
        int sampleSize = Math.min(10, nodes.size());
        long totalSampleSize = 0;
        for (int i = 0; i < sampleSize; i++) {
            int index = (int) (((long) i * (nodes.size() - 1)) / (sampleSize - 1));
            totalSampleSize += BytecodeSizeEstimator.estimateSnippetSize(nodes.get(index));
        }
        return (totalSampleSize * nodes.size()) / sampleSize;
    }
}
