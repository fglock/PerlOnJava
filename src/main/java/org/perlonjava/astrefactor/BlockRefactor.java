package org.perlonjava.astrefactor;

import org.perlonjava.astnode.*;
import org.perlonjava.astvisitor.BytecodeSizeEstimator;
import org.perlonjava.astvisitor.ControlFlowDetectorVisitor;
import org.perlonjava.parser.Parser;
import org.perlonjava.runtime.PerlCompilerException;

import java.util.ArrayList;
import java.util.List;

import static org.perlonjava.parser.ParserNodeUtils.variableAst;

public class BlockRefactor {
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

        List<Node> tail = new ArrayList<>();

        for (int i = segments.size() - 1; i >= 0; i--) {
            Object segment = segments.get(i);

            if (segment instanceof Node directNode) {
                tail.add(0, directNode);
                continue;
            }

            List<Node> chunk = (List<Node>) segment;
            if (chunk.size() >= minChunkSize) {
                List<Node> blockElements = new ArrayList<>(chunk);
                blockElements.addAll(tail);
                List<Node> wrapped = returnTypeIsList ? wrapInListNode(blockElements, tokenIndex) : blockElements;
                BlockNode block = createBlockNode(wrapped, tokenIndex, skipRefactoring);
                Node closure = createAnonSubCall(tokenIndex, block);
                tail = new ArrayList<>();
                tail.add(closure);
            } else {
                tail.addAll(0, chunk);
            }
        }

        return tail;
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
        BlockNode block = new BlockNode(elements, tokenIndex);
        List<String> labels = new ArrayList<>();
        for (Node element : elements) {
            if (element instanceof LabelNode labelNode) {
                labels.add(labelNode.label);
            }
        }
        block.labels = labels;
        return block;
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
     * @param chunks       List of chunks (either ListNode or List<Node>)
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
