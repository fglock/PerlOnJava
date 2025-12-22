package org.perlonjava.codegen;

import org.perlonjava.astnode.*;
import org.perlonjava.astvisitor.BytecodeSizeEstimator;
import org.perlonjava.parser.Parser;
import org.perlonjava.runtime.PerlCompilerException;

import java.util.ArrayList;
import java.util.List;

import static org.perlonjava.parser.ParserNodeUtils.variableAst;

/**
 * Generic helper class for refactoring large AST node lists to avoid JVM's "Method too large" error.
 * <p>
 * <b>Problem:</b> The JVM has a hard limit of 65535 bytes per method. Large Perl literals
 * (arrays, hashes, lists with thousands of elements) can exceed this limit when compiled.
 * <p>
 * <b>Solution:</b> This class splits large element lists into chunks, each wrapped in an
 * anonymous subroutine. The chunks are then dereferenced and merged back together.
 * <p>
 * <b>Supported Node Types:</b>
 * <ul>
 *   <li>{@link ArrayLiteralNode} - e.g., [1, 2, 3, ... thousands of elements]</li>
 *   <li>{@link HashLiteralNode} - e.g., {a =&gt; 1, b =&gt; 2, ... thousands of pairs}</li>
 *   <li>{@link ListNode} - e.g., (1, 2, 3, ... thousands of elements)</li>
 *   <li>{@link org.perlonjava.astnode.BlockNode} - handled by {@link LargeBlockRefactorer} for control flow safety</li>
 * </ul>
 * <p>
 * <b>Chunk Wrapping Strategy:</b>
 * <ul>
 *   <li>Arrays: {@code [@{sub{[chunk1]}->(@_)}, @{sub{[chunk2]}->(@_)}, ...]}</li>
 *   <li>Hashes: {@code {%{sub{{chunk1}}->(@_)}, %{sub{{chunk2}}->(@_)}, ...}}</li>
 *   <li>Lists: {@code sub{ chunk1, sub{ chunk2 }->(@_) }->(@_)} - nested for proper closure scope</li>
 * </ul>
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
     * Minimum number of elements before considering refactoring.
     * Lists smaller than this are never refactored.
     * Set conservatively low since bytecode estimation is unreliable.
     */
    private static final int LARGE_ELEMENT_COUNT = 200;

    /**
     * Target maximum bytecode size per chunk (in bytes).
     * When total estimated bytecode exceeds this, refactoring is triggered.
     */
    private static final int LARGE_BYTECODE_SIZE = 30000;

    /**
     * Minimum elements per chunk. Chunks smaller than this are inlined directly
     * rather than wrapped in a subroutine. This also breaks the recursion.
     */
    private static final int MIN_CHUNK_SIZE = 50;

    /**
     * Maximum elements per chunk. Limits chunk size even if bytecode estimates
     * suggest larger chunks would fit.
     */
    private static final int MAX_CHUNK_SIZE = 200;

    /**
     * Enum identifying the type of AST node being refactored.
     * Used to determine the appropriate chunk wrapping and dereference strategy.
     */
    public enum NodeType {
        /**
         * Array literal: [...] - chunks wrapped with @{sub{[...]}->()}
         */
        ARRAY,
        /**
         * Hash literal: {...} - chunks wrapped with %{sub{{...}}->()}, chunk size forced even
         */
        HASH,
        /**
         * List: (...) - chunks wrapped with sub{(...)}->()
         */
        LIST
    }

    /**
     * Check if refactoring is enabled via environment variable.
     * When JPERL_LARGECODE=refactor, large literals will be automatically split.
     */
    private static boolean isRefactoringEnabled() {
        String largeCodeMode = System.getenv("JPERL_LARGECODE");
        return "refactor".equals(largeCodeMode);
    }

    /**
     * Main entry point: called from AST node constructors to potentially refactor large element lists.
     * <p>
     * This method checks if refactoring is needed based on:
     * <ol>
     *   <li>Environment variable JPERL_LARGECODE=refactor must be set</li>
     *   <li>Element count must exceed LARGE_ELEMENT_COUNT (500)</li>
     *   <li>Estimated bytecode size must exceed LARGE_BYTECODE_SIZE (30000 bytes)</li>
     * </ol>
     * <p>
     * If refactoring is needed, elements are split into chunks and wrapped in anonymous
     * subroutines with appropriate dereference operators.
     *
     * @param elements   the original elements list from the AST node constructor
     * @param tokenIndex the token index for creating new AST nodes (for error reporting)
     * @param nodeType   the type of node being constructed (affects wrapping strategy)
     * @param parser     the parser instance for access to error utilities (can be null if not available)
     * @return the original list if no refactoring needed, or a new list with chunked elements
     */
    public static List<Node> maybeRefactorElements(List<Node> elements, int tokenIndex, NodeType nodeType, Parser parser) {
        if (!isRefactoringEnabled() || !shouldRefactor(elements)) {
            return elements;
        }

        int chunkSize = calculateChunkSize(elements);
        if (nodeType == NodeType.HASH && chunkSize % 2 != 0) {
            chunkSize++; // Ensure even number for key-value pairs
        }

        List<Node> chunks = splitIntoChunks(elements, chunkSize);
        
        // For LIST nodes, create nested closures for proper lexical scoping
        if (nodeType == NodeType.LIST) {
            List<Node> result = createNestedListClosures(chunks, tokenIndex);
            // Check if refactoring was successful by estimating bytecode size
            long estimatedSize = estimateTotalBytecodeSize(result);
            if (estimatedSize > LARGE_BYTECODE_SIZE) {
                String message = "List is too large (" + elements.size() + " elements, estimated " + estimatedSize + " bytes) " +
                    "and refactoring failed to reduce it below " + LARGE_BYTECODE_SIZE + " bytes. " +
                    "Consider breaking the list into smaller parts or using a different data structure.";
                if (parser != null) {
                    throw new PerlCompilerException(tokenIndex, message, parser.ctx.errorUtil);
                } else {
                    throw new PerlCompilerException(message);
                }
            }
            return result;
        }
        
        // For ARRAY and HASH, use flat structure (they don't need nested scoping)
        List<Node> refactoredElements = new ArrayList<>();
        for (Node chunk : chunks) {
            if (chunk instanceof ListNode listChunk && listChunk.elements.size() < MIN_CHUNK_SIZE) {
                // Small chunk - add elements directly
                refactoredElements.addAll(listChunk.elements);
            } else {
                // Wrap chunk in anonymous sub, then dereference appropriately
                List<Node> chunkElements = chunk instanceof ListNode ? ((ListNode) chunk).elements : List.of(chunk);
                Node wrappedChunk = createChunkWrapper(chunkElements, tokenIndex, nodeType);
                refactoredElements.add(wrappedChunk);
            }
        }

        // Check if refactoring was successful for arrays and hashes by estimating bytecode size
        long estimatedSize = estimateTotalBytecodeSize(refactoredElements);
        if (estimatedSize > LARGE_BYTECODE_SIZE) {
            String typeName = nodeType == NodeType.ARRAY ? "Array" : "Hash";
            String message = typeName + " is too large (" + elements.size() + " elements, estimated " + estimatedSize + " bytes) " +
                "and refactoring failed to reduce it below " + LARGE_BYTECODE_SIZE + " bytes. " +
                "Consider breaking the " + typeName.toLowerCase() + " into smaller parts or using a different approach.";
            if (parser != null) {
                throw new PerlCompilerException(tokenIndex, message, parser.ctx.errorUtil);
            } else {
                throw new PerlCompilerException(message);
            }
        }

        return refactoredElements;
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
        
        // Build nested structure from innermost to outermost
        Node result = null;
        
        for (int i = chunks.size() - 1; i >= 0; i--) {
            Node chunk = chunks.get(i);
            List<Node> chunkElements = chunk instanceof ListNode ? ((ListNode) chunk).elements : List.of(chunk);
            
            if (chunkElements.size() < MIN_CHUNK_SIZE && result == null) {
                // Last chunk is small and there's no nested closure yet - skip wrapping
                result = new ListNode(new ArrayList<>(chunkElements), tokenIndex);
            } else {
                // Create the block content: either just the chunk elements, or chunk elements + nested closure call
                List<Node> blockElements = new ArrayList<>(chunkElements);
                if (result != null) {
                    blockElements.add(result);
                }
                
                ListNode listNode = new ListNode(blockElements, tokenIndex);
                listNode.setAnnotation("chunkAlreadyRefactored", true);
                BlockNode block = new BlockNode(List.of(listNode), tokenIndex);
                block.setAnnotation("blockAlreadyRefactored", true);
                SubroutineNode sub = new SubroutineNode(null, null, null, block, false, tokenIndex);
                result = new BinaryOperatorNode("->", sub, new ListNode(
                        new ArrayList<>(List.of(variableAst("@", "_", tokenIndex))), tokenIndex), tokenIndex);
            }
        }
        
        return List.of(result);
    }

    /**
     * Creates a wrapper AST node for a chunk of elements.
     * <p>
     * The wrapper structure depends on the node type:
     * <ul>
     *   <li>ARRAY: {@code @{ sub { [elements] }->() }} - array ref created in sub, then dereferenced</li>
     *   <li>HASH: {@code %{ sub { {elements} }->() }} - hash ref created in sub, then dereferenced</li>
     *   <li>LIST: Not used - see {@link #createNestedListClosures(List, int)}</li>
     * </ul>
     *
     * @param chunkElements the elements to wrap in this chunk
     * @param tokenIndex    token index for new nodes
     * @param nodeType      determines wrapping strategy
     * @return an AST node representing the wrapped chunk
     */
    private static Node createChunkWrapper(List<Node> chunkElements, int tokenIndex, NodeType nodeType) {
        Node innerLiteral;
        String derefOp;

        switch (nodeType) {
            case ARRAY:
                ArrayLiteralNode arr = new ArrayLiteralNode(chunkElements, tokenIndex);
                arr.setAnnotation("chunkAlreadyRefactored", true);
                innerLiteral = arr;
                derefOp = "@";
                break;
            case HASH:
                HashLiteralNode hash = new HashLiteralNode(chunkElements, tokenIndex);
                hash.setAnnotation("chunkAlreadyRefactored", true);
                innerLiteral = hash;
                derefOp = "%";
                break;
            case LIST:
            default:
                // LIST case should not reach here - handled by createNestedListClosures
                throw new IllegalStateException("LIST nodes should be handled by createNestedListClosures");
        }

        // For array/hash: @{ sub { [...] }->() } or %{ sub { {...} }->() }
        BlockNode block = new BlockNode(List.of(innerLiteral), tokenIndex);
        block.setAnnotation("blockAlreadyRefactored", true);  // Prevent LargeBlockRefactorer from processing
        SubroutineNode sub = new SubroutineNode(null, null, null, block, false, tokenIndex);
        BinaryOperatorNode call = new BinaryOperatorNode("->", sub, new ListNode(
                new ArrayList<>(List.of(variableAst("@", "_", tokenIndex))), tokenIndex), tokenIndex);
        return new OperatorNode(derefOp, call, tokenIndex);
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
        // Quick check: element count threshold
        if (elements.size() < LARGE_ELEMENT_COUNT) {
            return false;
        }

        // Use sampling to estimate bytecode size - avoid O(n) traversal
        int n = elements.size();
        int sampleSize = Math.min(10, n);
        long totalSampleSize = 0;
        for (int i = 0; i < sampleSize; i++) {
            int index = (int) (((long) i * (n - 1)) / (sampleSize - 1));
            totalSampleSize += BytecodeSizeEstimator.estimateSnippetSize(elements.get(index));
        }
        long estimatedTotalSize = (totalSampleSize * n) / sampleSize;
        return estimatedTotalSize > LARGE_BYTECODE_SIZE;
    }

    /**
     * Calculates the optimal chunk size based on sampled element sizes.
     * <p>
     * Samples the first 10 elements to estimate average bytecode size per element,
     * then calculates how many elements would fit in ~20KB of bytecode.
     * Result is clamped between MIN_CHUNK_SIZE and MAX_CHUNK_SIZE.
     *
     * @param elements the list of elements to chunk
     * @return optimal number of elements per chunk
     */
    private static int calculateChunkSize(List<Node> elements) {
        if (elements.isEmpty()) {
            return MAX_CHUNK_SIZE;
        }

        // Sample a few elements to estimate average size
        int sampleSize = Math.min(10, elements.size());
        int totalSampleSize = 0;
        for (int i = 0; i < sampleSize; i++) {
            totalSampleSize += BytecodeSizeEstimator.estimateSnippetSize(elements.get(i));
        }
        int avgElementSize = totalSampleSize / sampleSize;

        // Calculate chunk size to stay under bytecode limit
        // Target ~20KB per chunk to leave room for overhead
        int targetChunkBytes = 20000;
        int calculatedSize = avgElementSize > 0 ? targetChunkBytes / avgElementSize : MAX_CHUNK_SIZE;

        // Clamp to reasonable bounds
        return Math.max(MIN_CHUNK_SIZE, Math.min(MAX_CHUNK_SIZE, calculatedSize));
    }

    /**
     * Splits a list of elements into chunks of the specified size.
     * <p>
     * Each chunk is wrapped in a ListNode for uniform handling.
     * The last chunk may be smaller than chunkSize.
     *
     * @param elements  the list to split
     * @param chunkSize number of elements per chunk
     * @return list of ListNode chunks
     */
    private static List<Node> splitIntoChunks(List<Node> elements, int chunkSize) {
        List<Node> chunks = new ArrayList<>();

        for (int i = 0; i < elements.size(); i += chunkSize) {
            int end = Math.min(i + chunkSize, elements.size());
            List<Node> chunkElements = new ArrayList<>(elements.subList(i, end));
            chunks.add(new ListNode(chunkElements, elements.get(i).getIndex()));
        }

        return chunks;
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
