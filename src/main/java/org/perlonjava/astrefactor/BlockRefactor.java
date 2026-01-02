package org.perlonjava.astrefactor;

import org.perlonjava.astnode.*;
import org.perlonjava.astvisitor.BytecodeSizeEstimator;
import org.perlonjava.astvisitor.ControlFlowDetectorVisitor;
import org.perlonjava.parser.Parser;
import org.perlonjava.runtime.PerlCompilerException;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.perlonjava.parser.ParserNodeUtils.variableAst;

public class BlockRefactor {
    // Shared configuration thresholds for both LargeBlockRefactorer and LargeNodeRefactorer
    public static final int LARGE_ELEMENT_COUNT = 200;     // Deprecated: kept for MIN_CHUNK_SIZE compatibility
    public static final int LARGE_BYTECODE_SIZE = 40000;   // Maximum bytecode size before refactoring
    public static final int MIN_CHUNK_SIZE = 4;            // Minimum statements to extract as a chunk

    // Reusable visitor for control flow detection
    private static final ControlFlowDetectorVisitor controlFlowDetector = new ControlFlowDetectorVisitor();
    /**
     * Creates an anonymous subroutine call node that invokes a subroutine with the @_ array as arguments.
     *
     * @param tokenIndex  the token index for AST node positioning
     * @param nestedBlock the block node representing the subroutine body
     * @return a BinaryOperatorNode representing the anonymous subroutine call
     */
    public static BinaryOperatorNode createAnonSubCall(int tokenIndex, BlockNode nestedBlock) {
        return new BinaryOperatorNode(
                "->",
                new SubroutineNode(null, null, null, nestedBlock, false, tokenIndex),
                new ListNode(new ArrayList<>(List.of(variableAst("@", "_", tokenIndex))), tokenIndex),
                tokenIndex
        );
    }

    /**
     * Builds nested closure structure from segments.
     * Structure: direct1, direct2, sub{ chunk1, sub{ chunk2, chunk3 }->(@_) }->(@_)
     * Closures are always placed at tail position to preserve variable scoping.
     *
     * @param segments         List of segments (either Node for direct elements or List<Node> for chunks)
     * @param tokenIndex       token index for new nodes
     * @param minChunkSize     minimum size for a chunk to be wrapped in a closure
     * @param returnTypeIsList if true, wrap elements in ListNode to return list; if false, execute statements
     * @param skipRefactoring  thread-local flag to prevent recursion during BlockNode construction
     * @return List of processed elements with nested structure
     */
    @SuppressWarnings("unchecked")
    public static List<Node> buildNestedStructure(
            List<Object> segments,
            int tokenIndex,
            int minChunkSize,
            boolean returnTypeIsList,
            ThreadLocal<Boolean> skipRefactoring) {
        if (segments.isEmpty()) {
            return new ArrayList<>();
        }

        List<Node> result = new ArrayList<>();

        // NESTED CLOSURES IN TAIL POSITION: Build from end to beginning
        // Example: { A, B, sub { C, D, sub { E, F }->(@_) }->(@_) }
        // Direct elements stay at top level, then ONE closure with nested closures inside
        
        // Separate direct elements from chunks, maintaining order
        List<Integer> chunkIndices = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            Object segment = segments.get(i);
            if (segment instanceof List) {
                List<Node> chunk = (List<Node>) segment;
                if (chunk.size() >= minChunkSize) {
                    chunkIndices.add(i);
                }
            }
        }
        
        // If no chunks to wrap, just return all segments as-is
        if (chunkIndices.isEmpty()) {
            for (Object segment : segments) {
                if (segment instanceof Node) {
                    result.add((Node) segment);
                } else {
                    result.addAll((List<Node>) segment);
                }
            }
            return result;
        }
        
        // Find where the first chunk starts
        int firstChunkIndex = chunkIndices.get(0);
        
        // Add all direct elements and small chunks before first chunk
        for (int i = 0; i < firstChunkIndex; i++) {
            Object segment = segments.get(i);
            if (segment instanceof Node) {
                result.add((Node) segment);
            } else {
                result.addAll((List<Node>) segment);
            }
        }
        
        // Build nested closures from the LAST chunk backwards
        // Collect all trailing elements (small chunks and direct nodes) that come after the last large chunk
        List<Node> trailingElements = new ArrayList<>();
        int lastLargeChunkIndex = -1;
        
        // Find the last large chunk
        for (int i = segments.size() - 1; i >= firstChunkIndex; i--) {
            Object segment = segments.get(i);
            if (segment instanceof List) {
                List<Node> chunk = (List<Node>) segment;
                if (chunk.size() >= minChunkSize) {
                    lastLargeChunkIndex = i;
                    break;
                }
            }
        }
        
        // Collect trailing elements after the last large chunk
        if (lastLargeChunkIndex >= 0) {
            for (int i = lastLargeChunkIndex + 1; i < segments.size(); i++) {
                Object segment = segments.get(i);
                if (segment instanceof Node) {
                    trailingElements.add((Node) segment);
                } else if (segment instanceof List) {
                    trailingElements.addAll((List<Node>) segment);
                }
            }
        }
        
        // Build nested closures from the last large chunk backwards
        Node tailClosure = null;
        
        // Start with trailing elements if any
        if (!trailingElements.isEmpty()) {
            List<Node> wrapped = returnTypeIsList ? wrapInListNode(trailingElements, tokenIndex) : trailingElements;
            BlockNode block = createBlockNode(wrapped, tokenIndex, skipRefactoring);
            block.setAnnotation("blockAlreadyRefactored", true);
            tailClosure = createAnonSubCall(tokenIndex, block);
        }
        
        // Process chunks backwards from last large chunk
        int endIndex = lastLargeChunkIndex >= 0 ? lastLargeChunkIndex : segments.size() - 1;
        for (int i = endIndex; i >= firstChunkIndex; i--) {
            Object segment = segments.get(i);
            
            if (segment instanceof List) {
                List<Node> chunk = (List<Node>) segment;
                if (chunk.size() >= minChunkSize) {
                    // This is a large chunk - wrap it with the tail closure
                    List<Node> blockElements = new ArrayList<>(chunk);
                    if (tailClosure != null) {
                        blockElements.add(tailClosure);
                    }
                    List<Node> wrapped = returnTypeIsList ? wrapInListNode(blockElements, tokenIndex) : blockElements;
                    BlockNode block = createBlockNode(wrapped, tokenIndex, skipRefactoring);
                    block.setAnnotation("blockAlreadyRefactored", true);
                    tailClosure = createAnonSubCall(tokenIndex, block);
                } else {
                    // Small chunk before first large chunk - add to current closure
                    if (tailClosure != null) {
                        List<Node> combined = new ArrayList<>(chunk);
                        combined.add(tailClosure);
                        List<Node> wrapped = returnTypeIsList ? wrapInListNode(combined, tokenIndex) : combined;
                        BlockNode block = createBlockNode(wrapped, tokenIndex, skipRefactoring);
                        block.setAnnotation("blockAlreadyRefactored", true);
                        tailClosure = createAnonSubCall(tokenIndex, block);
                    }
                }
            } else if (segment instanceof Node) {
                // Direct node before first large chunk - add to current closure
                if (tailClosure != null) {
                    List<Node> combined = new ArrayList<>();
                    combined.add((Node) segment);
                    combined.add(tailClosure);
                    List<Node> wrapped = returnTypeIsList ? wrapInListNode(combined, tokenIndex) : combined;
                    BlockNode block = createBlockNode(wrapped, tokenIndex, skipRefactoring);
                    block.setAnnotation("blockAlreadyRefactored", true);
                    tailClosure = createAnonSubCall(tokenIndex, block);
                }
            }
        }
        
        // Add the final nested closure structure
        if (tailClosure != null) {
            result.add(tailClosure);
        }

        return result;
    }

    /**
     * Wraps elements in a ListNode to ensure the closure returns a list of elements.
     */
    private static List<Node> wrapInListNode(List<Node> elements, int tokenIndex) {
        ListNode listNode = new ListNode(elements, tokenIndex);
        listNode.setAnnotation("chunkAlreadyRefactored", true);
        return List.of(listNode);
    }

    /**
     * Creates a BlockNode using thread-local flag to prevent recursion.
     */
    private static BlockNode createBlockNode(List<Node> elements, int tokenIndex, ThreadLocal<Boolean> skipRefactoring) {
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
     * Estimates the total bytecode size for a list of nodes.
     * Uses sampling for efficiency on large lists.
     *
     * @param nodes the list of nodes to estimate
     * @return estimated total bytecode size in bytes
     */
    public static long estimateTotalBytecodeSize(List<Node> nodes) {
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

    /**
     * Check if any chunk that will be wrapped in a closure contains unsafe control flow.
     * Only checks chunks that are large enough to be wrapped (>= minChunkSize).
     *
     * @param chunks List of chunks (either ListNode or List<Node>)
     * @param minChunkSize Minimum size for a chunk to be wrapped
     * @return true if unsafe control flow found in chunks that will be wrapped
     */
    public static boolean hasUnsafeControlFlowInChunks(List<?> chunks, int minChunkSize) {
        for (Object chunk : chunks) {
            List<Node> elements = null;
            if (chunk instanceof ListNode listChunk) {
                elements = listChunk.elements;
            } else if (chunk instanceof List) {
                @SuppressWarnings("unchecked")
                List<Node> nodeList = (List<Node>) chunk;
                elements = nodeList;
            }

            if (elements != null && elements.size() >= minChunkSize) {
                controlFlowDetector.reset();
                for (Node element : elements) {
                    element.accept(controlFlowDetector);
                    if (controlFlowDetector.hasUnsafeControlFlow()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Throw an error when a large block/node cannot be refactored.
     */
    public static void errorCantRefactorLargeBlock(int tokenIndex, Parser parser, long estimatedSize) {
        String message = "Code is too large (estimated " + estimatedSize + " bytes) " +
                "and refactoring failed to reduce it below " + LARGE_BYTECODE_SIZE + " bytes. " +
                "The block may contain control flow statements that prevent safe refactoring. " +
                "Consider breaking the code into smaller subroutines manually.";
        if (parser != null) {
            throw new PerlCompilerException(tokenIndex, message, parser.ctx.errorUtil);
        } else {
            throw new PerlCompilerException(message);
        }
    }
}
