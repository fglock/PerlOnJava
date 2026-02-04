package org.perlonjava.astrefactor;

import org.perlonjava.astnode.*;
import org.perlonjava.astvisitor.BytecodeSizeEstimator;
import org.perlonjava.parser.Parser;

import java.util.ArrayList;
import java.util.List;

import static org.perlonjava.astrefactor.BlockRefactor.*;

/**
 * Helper class for refactoring large AST node lists (arrays, hashes, lists) to avoid JVM's "Method too large" error.
 * <p>
 * This class provides on-demand refactoring of large literals when compilation fails with MethodTooLargeException.
 */
public class LargeNodeRefactorer {

    /**
     * Maximum elements per chunk.
     */
    private static final int MAX_CHUNK_SIZE = 200;

    /**
     * Thread-local flag to prevent recursion when creating nested blocks.
     */
    private static final ThreadLocal<Boolean> skipRefactoring = ThreadLocal.withInitial(() -> false);

    /**
     * Called from AST node constructors - returns elements unchanged.
     * <p>
     * Proactive refactoring is disabled. Use {@link #forceRefactorElements} for on-demand refactoring.
     *
     * @param elements   the original elements list
     * @param tokenIndex the token index for error reporting
     * @param parser     the parser instance (unused)
     * @return the original list unchanged
     */
    public static List<Node> maybeRefactorElements(List<Node> elements, int tokenIndex, Parser parser) {
        // No proactive refactoring - return unchanged
        return elements;
    }

    /**
     * Force refactoring of a large element list (for on-demand use when MethodTooLargeException occurs).
     *
     * @param elements   the elements list to refactor
     * @param tokenIndex the token index for creating new nodes
     * @return refactored list with elements chunked into closures
     */
    public static List<Node> forceRefactorElements(List<Node> elements, int tokenIndex) {
        if (elements == null || elements.isEmpty()) {
            return elements;
        }

        // Check if elements contain any top-level labels
        for (Node element : elements) {
            if (element instanceof LabelNode) {
                // Contains a label - skip refactoring to preserve label scope
                return elements;
            }
        }

        List<Node> chunks = splitIntoDynamicChunks(elements);

        // Check if any chunk that will be wrapped contains unsafe control flow
        if (hasUnsafeControlFlowInChunks(chunks, MIN_CHUNK_SIZE)) {
            // Chunks contain control flow that would break if wrapped
            return elements;
        }

        // Create nested closures for proper lexical scoping
        List<Node> result = createNestedListClosures(chunks, tokenIndex);
        return result;
    }

    /**
     * Traverse a BlockNode and refactor any large literals (arrays, hashes, lists) found within.
     * Performs deep traversal of all nodes.
     *
     * @param block The block to traverse and refactor
     */
    public static void refactorLiteralsInBlock(BlockNode block) {
        if (block == null || block.elements == null) {
            return;
        }

        // Traverse all elements in the block
        for (int i = 0; i < block.elements.size(); i++) {
            Node node = block.elements.get(i);
            refactorLiteralsInNode(node);
        }
    }

    /**
     * Recursively traverse a node and refactor any large literals found.
     *
     * @param node The node to traverse
     */
    private static void refactorLiteralsInNode(Node node) {
        if (node == null) {
            return;
        }

        // Refactor this node if it's a large literal
        if (node instanceof ArrayLiteralNode arrayNode && shouldRefactor(arrayNode.elements)) {
            arrayNode.elements = forceRefactorElements(arrayNode.elements, arrayNode.getIndex());
        } else if (node instanceof HashLiteralNode hashNode && shouldRefactor(hashNode.elements)) {
            hashNode.elements = forceRefactorElements(hashNode.elements, hashNode.getIndex());
        } else if (node instanceof ListNode listNode && listNode.handle == null && shouldRefactor(listNode.elements)) {
            listNode.elements = forceRefactorElements(listNode.elements, listNode.getIndex());
        }

        // Recursively traverse child nodes
        if (node instanceof BlockNode nestedBlock) {
            refactorLiteralsInBlock(nestedBlock);
        } else if (node instanceof ArrayLiteralNode arrayNode) {
            for (Node element : arrayNode.elements) {
                refactorLiteralsInNode(element);
            }
        } else if (node instanceof HashLiteralNode hashNode) {
            for (Node element : hashNode.elements) {
                refactorLiteralsInNode(element);
            }
        } else if (node instanceof ListNode listNode) {
            for (Node element : listNode.elements) {
                refactorLiteralsInNode(element);
            }
            if (listNode.handle != null) {
                refactorLiteralsInNode(listNode.handle);
            }
        } else if (node instanceof OperatorNode opNode) {
            refactorLiteralsInNode(opNode.left);
            refactorLiteralsInNode(opNode.right);
        } else if (node instanceof BinaryOperatorNode binOp) {
            refactorLiteralsInNode(binOp.left);
            refactorLiteralsInNode(binOp.right);
        } else if (node instanceof UnaryOperatorNode unOp) {
            refactorLiteralsInNode(unOp.operand);
        } else if (node instanceof TernaryOperatorNode ternary) {
            refactorLiteralsInNode(ternary.condition);
            refactorLiteralsInNode(ternary.trueExpr);
            refactorLiteralsInNode(ternary.falseExpr);
        } else if (node instanceof For1Node forNode) {
            refactorLiteralsInNode(forNode.init);
            refactorLiteralsInNode(forNode.condition);
            refactorLiteralsInNode(forNode.increment);
            refactorLiteralsInNode(forNode.body);
        } else if (node instanceof ForNode forNode) {
            refactorLiteralsInNode(forNode.variable);
            refactorLiteralsInNode(forNode.list);
            refactorLiteralsInNode(forNode.body);
        } else if (node instanceof IfNode ifNode) {
            refactorLiteralsInNode(ifNode.condition);
            refactorLiteralsInNode(ifNode.thenBranch);
            refactorLiteralsInNode(ifNode.elseBranch);
        }
    }

    /**
     * Creates nested closures for LIST chunks to ensure proper lexical scoping.
     */
    private static List<Node> createNestedListClosures(List<Node> chunks, int tokenIndex) {
        if (chunks.isEmpty()) {
            return new ArrayList<>();
        }

        // If only one chunk and it's small, just return its elements
        if (chunks.size() == 1 && chunks.get(0) instanceof ListNode listChunk &&
                listChunk.elements.size() < MIN_CHUNK_SIZE) {
            return listChunk.elements;
        }

        // Convert chunks (ListNode objects) to segments (List<Node> objects)
        List<Object> segments = new ArrayList<>();
        for (Node chunk : chunks) {
            if (chunk instanceof ListNode listChunk) {
                segments.add(listChunk.elements);
            } else {
                segments.add(List.of(chunk));
            }
        }

        return buildNestedStructure(
                segments,
                tokenIndex,
                MIN_CHUNK_SIZE,
                true, // returnTypeIsList = true: wrap in ListNode to return list
                skipRefactoring
        );
    }

    /**
     * Determines if a list of elements should be refactored based on size criteria.
     */
    private static boolean shouldRefactor(List<Node> elements) {
        int n = elements.size();
        if (n == 0) {
            return false;
        }
        if (n == 1) {
            long size = BytecodeSizeEstimator.estimateSnippetSize(elements.get(0));
            return size > LARGE_BYTECODE_SIZE;
        }

        // Estimate all elements for accurate size calculation
        long totalSize = 0;
        for (Node element : elements) {
            totalSize += BytecodeSizeEstimator.estimateSnippetSize(element);
        }

        return totalSize > LARGE_BYTECODE_SIZE;
    }

    /**
     * Splits a list of elements into dynamic chunks based on estimated bytecode sizes.
     */
    private static List<Node> splitIntoDynamicChunks(List<Node> elements) {
        List<Node> chunks = new ArrayList<>();
        List<Node> currentChunk = new ArrayList<>();
        long currentChunkSize = 0;

        for (Node element : elements) {
            long elementSize = BytecodeSizeEstimator.estimateSnippetSize(element);

            // Check if adding this element would exceed the size limit or max chunk size
            if (!currentChunk.isEmpty() &&
                    (currentChunkSize + elementSize > LARGE_BYTECODE_SIZE ||
                            currentChunk.size() >= MAX_CHUNK_SIZE)) {
                // Finalize current chunk
                chunks.add(new ListNode(new ArrayList<>(currentChunk), currentChunk.get(0).getIndex()));
                currentChunk.clear();
                currentChunkSize = 0;
            }

            // Add element to current chunk
            currentChunk.add(element);
            currentChunkSize += elementSize;
        }

        // Add the last chunk if it has elements
        if (!currentChunk.isEmpty()) {
            chunks.add(new ListNode(new ArrayList<>(currentChunk), currentChunk.get(0).getIndex()));
        }

        return chunks;
    }
}
