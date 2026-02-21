package org.perlonjava.backend.jvm.astrefactor;

import org.perlonjava.frontend.astnode.LabelNode;
import org.perlonjava.frontend.astnode.ListNode;
import org.perlonjava.frontend.astnode.Node;
import org.perlonjava.frontend.analysis.BytecodeSizeEstimator;

import java.util.ArrayList;
import java.util.List;

import static org.perlonjava.backend.jvm.astrefactor.BlockRefactor.*;

/**
 * Helper class for refactoring large AST node lists to avoid JVM's "Method too large" error.
 * <p>
 * <b>Problem:</b> The JVM has a hard limit of 65535 bytes per method. Large Perl literals
 * (arrays, hashes, lists with thousands of elements) can exceed this limit when compiled.
 * <p>
 * <b>Solution:</b> This class provides on-demand refactoring that splits large element lists
 * into chunks, each wrapped in an anonymous subroutine. The chunks are then dereferenced
 * and merged back together when compilation errors occur.
 * <p>
 * <b>Integration:</b> Used by {@link LargeBlockRefactorer} for automatic on-demand refactoring
 * when "Method too large" errors are detected during bytecode generation.
 * <p>
 * <b>Recursion Safety:</b> The circular dependency (constructor calls refactorer which
 * creates new nodes) breaks naturally when chunks become small enough (below MIN_CHUNK_SIZE).
 *
 * @see BytecodeSizeEstimator#estimateSnippetSize(Node)
 * @see LargeBlockRefactorer
 */
public class LargeNodeRefactorer {
    /**
     * Maximum elements per chunk. Limits chunk size even if bytecode estimates
     * suggest larger chunks would fit.
     */
    private static final int MAX_CHUNK_SIZE = 200;

    /**
     * Thread-local flag to prevent recursion when creating nested blocks.
     */
    private static final ThreadLocal<Boolean> skipRefactoring = ThreadLocal.withInitial(() -> false);

    /**
     * Refactors a large element list (for on-demand use when MethodTooLargeException occurs).
     * <p>
     * This method always attempts refactoring and is used by DepthFirstLiteralRefactorVisitor
     * when "Method too large" errors are detected during bytecode generation.
     *
     * @param elements   the elements list to refactor
     * @param tokenIndex the token index for creating new nodes
     * @return refactored list with elements chunked into closures, or original list if refactoring not possible
     */
    public static List<Node> forceRefactorElements(List<Node> elements, int tokenIndex) {
        if (elements == null || elements.isEmpty() || !shouldRefactor(elements)) {
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

        // Note: Control flow checks removed since master supports non-local gotos in subroutines
        // Wrapping code in `sub { next }` is now safe

        // Create nested closures for proper lexical scoping
        return createNestedListClosures(chunks, tokenIndex);
    }

    /**
     * Creates nested closures for LIST chunks to ensure proper lexical scoping.
     * Structure: sub{ chunk1, sub{ chunk2, sub{ chunk3 }->(@_) }->(@_) }->(@_)
     *
     * @param chunks     the list of chunks to nest
     * @param tokenIndex token index for new nodes
     * @return a single Node representing the nested closure structure, or a ListNode if only one small chunk
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

        // Use unified method with ListNode wrapper
        // The wrapping is necessary because the closure needs to return a list of elements,
        // not just execute them sequentially.
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
     * <p>
     * Uses {@link BytecodeSizeEstimator#estimateSnippetSize(Node)} to estimate
     * the bytecode that would be generated for each element.
     *
     * @param elements the list of AST nodes to evaluate
     * @return true if the list exceeds size thresholds and should be refactored
     */
    private static boolean shouldRefactor(List<Node> elements) {
        // Estimate bytecode size by visiting all elements (no sampling)
        // Sampling was causing inaccurate estimates for mixed element types
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
     * <p>
     * Each chunk is created by accumulating elements until the estimated bytecode size
     * reaches LARGE_BYTECODE_SIZE. This ensures chunks stay under the size limit while
     * maximizing elements per chunk.
     * <p>
     * Respects MIN_CHUNK_SIZE and MAX_CHUNK_SIZE constraints.
     *
     * @param elements the list to split
     * @return list of ListNode chunks
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
