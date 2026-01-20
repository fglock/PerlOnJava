package org.perlonjava.astrefactor;

import org.perlonjava.astnode.*;
import org.perlonjava.astvisitor.BytecodeSizeEstimator;
import org.perlonjava.parser.Parser;

import java.util.ArrayList;
import java.util.List;

import static org.perlonjava.astrefactor.BlockRefactor.createAnonSubCall;

import static org.perlonjava.astrefactor.BlockRefactor.*;

/**
 * Generic helper class for refactoring large AST node lists to avoid JVM's "Method too large" error.
 * <p>
 * <b>Problem:</b> The JVM has a hard limit of 65535 bytes per method. Large Perl literals
 * (arrays, hashes, lists with thousands of elements) can exceed this limit when compiled.
 * <p>
 * <b>Solution:</b> This class splits large element lists into chunks, each wrapped in an
 * anonymous subroutine. The chunks are then dereferenced and merged back together.
 * <p>
 * <b>Integration:</b> Called automatically from AST node constructors when enabled.
 * This ensures the AST is always the right size from creation time.
 * <p>
 * <b>Activation:</b> Set environment variable {@code JPERL_LARGECODE=refactor} to enable.
 * <p>
 * <b>Recursion Safety:</b> The circular dependency (constructor calls refactorer which
 * creates new nodes) breaks naturally when chunks become small enough (below MIN_CHUNK_SIZE).
 *
 * @see BytecodeSizeEstimator#estimateSnippetSize(Node)
 * @see LargeBlockRefactorer
 */
public class LargeNodeRefactorer {

    /**
     * Check if refactoring is enabled via environment variable.
     * When JPERL_LARGECODE=refactor, large literals will be automatically split.
     */
    static final boolean IS_REFACTORING_ENABLED = "refactor".equals(System.getenv("JPERL_LARGECODE"));

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
    
    /**
     * Maximum elements per chunk. Limits chunk size even if bytecode estimates
     * suggest larger chunks would fit.
     */
    private static final int MAX_CHUNK_SIZE = parseEnvInt("JPERL_MAX_NODE_CHUNK_SIZE", 2000);

    /**
     * Thread-local flag to prevent recursion when creating nested blocks.
     */
    private static final ThreadLocal<Boolean> skipRefactoring = ThreadLocal.withInitial(() -> false);

    /**
     * Main entry point: called from AST node constructors to potentially refactor large element lists.
     * <p>
     * This method checks if refactoring is needed based on:
     * <ol>
     *   <li>Environment variable JPERL_LARGECODE=refactor must be set</li>
     *   <li>Estimated bytecode size must exceed LARGE_BYTECODE_SIZE (40000 bytes)</li>
     * </ol>
     * <p>
     * If refactoring is needed, elements are split into chunks and wrapped in anonymous
     * subroutines with appropriate dereference operators.
     *
     * @param elements   the original elements list from the AST node constructor
     * @param tokenIndex the token index for creating new AST nodes (for error reporting)
     * @param parser     the parser instance for access to error utilities (can be null if not available)
     * @return the original list if no refactoring needed, or a new list with chunked elements
     */
    public static List<Node> maybeRefactorElements(List<Node> elements, int tokenIndex, Parser parser) {
        if (!IS_REFACTORING_ENABLED || !shouldRefactor(elements)) {
            return elements;
        }

        // Check if elements contain any top-level labels
        // Labels in lists/arrays/hashes would break if wrapped in closures
        for (Node element : elements) {
            if (element instanceof LabelNode) {
                // Contains a label - skip refactoring to preserve label scope
                return elements;
            }
        }

        List<Node> chunks = splitIntoDynamicChunks(elements);

        // Check if any chunk that will be wrapped contains unsafe control flow
        // Control flow in lists/arrays/hashes can break if wrapped in closures
        if (hasUnsafeControlFlowInChunks(chunks, MIN_CHUNK_SIZE)) {
            // Chunks contain control flow that would break if wrapped
            // Skip refactoring - cannot safely refactor this list
            return elements;
        }

        // If there are no lexical declarations within the element list, we can avoid
        // deeply nesting closures (which can cause StackOverflowError at runtime for
        // huge generated tables like ExifTool) by emitting a flat sequence of chunk calls.
        // If there ARE lexical declarations (my/state), we keep the nested structure
        // to preserve Perl's lexical visibility across the rest of the list.
        boolean needsLexicalScopePreservation = containsLexicalDeclarations(elements);
        List<Node> result = needsLexicalScopePreservation
                ? createNestedListClosures(chunks, tokenIndex)
                : createFlatListChunkCalls(chunks, tokenIndex);
        // Check if refactoring was successful by estimating bytecode size
        long estimatedSize = BlockRefactor.estimateTotalBytecodeSizeExact(result);
        if (estimatedSize > LARGE_BYTECODE_SIZE) {
            errorCantRefactorLargeBlock(tokenIndex, parser, estimatedSize);
        }
        return result;
    }

    private static boolean containsLexicalDeclarations(List<Node> elements) {
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
        }
        return false;
    }

    /**
     * Builds a flat sequence of chunk calls: sub{ (chunk1...) }->(@_), sub{ (chunk2...) }->(@_), ...
     *
     * This preserves evaluation order without creating a deep call stack.
     */
    private static List<Node> createFlatListChunkCalls(List<Node> chunks, int tokenIndex) {
        List<Node> result = new ArrayList<>();
        for (Node chunk : chunks) {
            if (!(chunk instanceof ListNode listChunk)) {
                result.add(chunk);
                continue;
            }

            if (listChunk.elements.size() < MIN_CHUNK_SIZE) {
                result.addAll(listChunk.elements);
                continue;
            }

            // Wrap chunk elements in a ListNode so the closure returns a list, not just
            // a sequence of statements.
            ListNode listNode = new ListNode(tokenIndex);
            listNode.elements = listChunk.elements;
            BlockNode block = new BlockNode(List.of(listNode), tokenIndex);
            result.add(createAnonSubCall(tokenIndex, block));
        }
        return result;
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
