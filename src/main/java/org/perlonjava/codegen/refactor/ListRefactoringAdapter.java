package org.perlonjava.codegen.refactor;

import org.perlonjava.astnode.*;
import org.perlonjava.parser.Parser;

import java.util.ArrayList;
import java.util.List;

import static org.perlonjava.parser.ParserNodeUtils.variableAst;

/**
 * Adapter class for refactoring array/hash/list literals using the unified refactoring framework.
 * <p>
 * This class provides a bridge between the old {@code LargeNodeRefactorer} API and the
 * new unified {@link NodeListRefactorer}. It handles the specific wrapping strategies
 * needed for different literal types.
 * <p>
 * <b>Wrapping Strategies:</b>
 * <ul>
 *   <li>Arrays: {@code [@{sub{[chunk1]}->(@_)}, @{sub{[chunk2]}->(@_)}, ...]}</li>
 *   <li>Hashes: {@code {%{sub{{chunk1}}->(@_)}, %{sub{{chunk2}}->(@_)}, ...}}</li>
 *   <li>Lists: {@code sub{ chunk1, sub{ chunk2 }->(@_) }->(@_)} - nested for proper closure scope</li>
 * </ul>
 *
 * @see NodeListRefactorer
 */
public class ListRefactoringAdapter {

    /**
     * Node type enum for determining wrapping strategy.
     */
    public enum NodeType {
        ARRAY,
        HASH,
        LIST
    }

    /**
     * Refactor a list of elements for an array/hash/list literal.
     * <p>
     * This method is called from AST node constructors to automatically refactor
     * large literals during parsing.
     *
     * @param elements   the original elements list
     * @param tokenIndex token index for creating new nodes
     * @param nodeType   the type of node being constructed
     * @param parser     parser instance for error reporting (can be null)
     * @return the original or refactored list
     */
    public static List<Node> maybeRefactorElements(List<Node> elements, int tokenIndex, 
                                                   NodeType nodeType, Parser parser) {
        if (!RefactoringContext.isRefactoringEnabled()) {
            return elements;
        }

        // Create context for list refactoring
        RefactoringContext context = new RefactoringContext(
                200,   // minElementCount - higher for literals
                30000, // maxBytecodeSize
                50,    // minChunkSize - larger for literals
                200,   // maxChunkSize
                tokenIndex,
                parser,
                false
        );

        // For lists, use nested closure strategy
        if (nodeType == NodeType.LIST) {
            return refactorListElements(elements, context);
        }

        // For arrays and hashes, use flat chunk strategy
        return refactorArrayHashElements(elements, context, nodeType);
    }

    /**
     * Refactor list elements using nested closures.
     *
     * @param elements the elements to refactor
     * @param context  the refactoring context
     * @return the refactored list
     */
    private static List<Node> refactorListElements(List<Node> elements, RefactoringContext context) {
        // Check if refactoring is needed
        if (elements.size() < context.minElementCount) {
            return elements;
        }

        long estimatedSize = estimateTotalBytecodeSize(elements);
        if (estimatedSize <= context.maxBytecodeSize) {
            return elements;
        }

        // Split into chunks
        int chunkSize = calculateChunkSize(elements, context);
        List<Node> chunks = splitIntoChunks(elements, chunkSize, context.tokenIndex);

        // Create nested closures
        return createNestedListClosures(chunks, context);
    }

    /**
     * Refactor array/hash elements using flat chunk strategy.
     *
     * @param elements the elements to refactor
     * @param context  the refactoring context
     * @param nodeType the node type (ARRAY or HASH)
     * @return the refactored list
     */
    private static List<Node> refactorArrayHashElements(List<Node> elements, 
                                                        RefactoringContext context, 
                                                        NodeType nodeType) {
        // Check if refactoring is needed
        if (elements.size() < context.minElementCount) {
            return elements;
        }

        long estimatedSize = estimateTotalBytecodeSize(elements);
        if (estimatedSize <= context.maxBytecodeSize) {
            return elements;
        }

        // Split into chunks
        int chunkSize = calculateChunkSize(elements, context);
        if (nodeType == NodeType.HASH && chunkSize % 2 != 0) {
            chunkSize++; // Ensure even number for key-value pairs
        }

        List<Node> chunks = splitIntoChunks(elements, chunkSize, context.tokenIndex);

        // Wrap chunks
        List<Node> refactoredElements = new ArrayList<>();
        for (Node chunk : chunks) {
            if (chunk instanceof ListNode listChunk && listChunk.elements.size() < context.minChunkSize) {
                refactoredElements.addAll(listChunk.elements);
            } else {
                List<Node> chunkElements = chunk instanceof ListNode ? 
                        ((ListNode) chunk).elements : List.of(chunk);
                Node wrappedChunk = createChunkWrapper(chunkElements, context.tokenIndex, nodeType);
                refactoredElements.add(wrappedChunk);
            }
        }

        return refactoredElements;
    }

    /**
     * Create nested closures for list chunks.
     */
    private static List<Node> createNestedListClosures(List<Node> chunks, RefactoringContext context) {
        if (chunks.isEmpty()) {
            return new ArrayList<>();
        }

        if (chunks.size() == 1 && chunks.get(0) instanceof ListNode listChunk &&
                listChunk.elements.size() < context.minChunkSize) {
            return listChunk.elements;
        }

        Node result = null;

        for (int i = chunks.size() - 1; i >= 0; i--) {
            Node chunk = chunks.get(i);
            List<Node> chunkElements = chunk instanceof ListNode ? 
                    ((ListNode) chunk).elements : List.of(chunk);

            if (chunkElements.size() < context.minChunkSize && result == null) {
                result = new ListNode(new ArrayList<>(chunkElements), context.tokenIndex);
            } else {
                List<Node> blockElements = new ArrayList<>(chunkElements);
                if (result != null) {
                    blockElements.add(result);
                }

                ListNode listNode = new ListNode(blockElements, context.tokenIndex);
                listNode.setAnnotation("chunkAlreadyRefactored", true);
                BlockNode block = new BlockNode(List.of(listNode), context.tokenIndex);
                block.setAnnotation("blockAlreadyRefactored", true);
                SubroutineNode sub = new SubroutineNode(null, null, null, block, false, context.tokenIndex);
                result = new BinaryOperatorNode("->", sub, new ListNode(
                        new ArrayList<>(List.of(variableAst("@", "_", context.tokenIndex))), 
                        context.tokenIndex), context.tokenIndex);
            }
        }

        return List.of(result);
    }

    /**
     * Create a wrapper for array/hash chunks.
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
            default:
                throw new IllegalStateException("LIST nodes should use createNestedListClosures");
        }

        BlockNode block = new BlockNode(List.of(innerLiteral), tokenIndex);
        block.setAnnotation("blockAlreadyRefactored", true);
        SubroutineNode sub = new SubroutineNode(null, null, null, block, false, tokenIndex);
        BinaryOperatorNode call = new BinaryOperatorNode("->", sub, new ListNode(
                new ArrayList<>(List.of(variableAst("@", "_", tokenIndex))), tokenIndex), tokenIndex);
        return new OperatorNode(derefOp, call, tokenIndex);
    }

    /**
     * Calculate optimal chunk size based on element sizes.
     */
    private static int calculateChunkSize(List<Node> elements, RefactoringContext context) {
        if (elements.isEmpty()) {
            return context.maxChunkSize;
        }

        int sampleSize = Math.min(10, elements.size());
        int totalSampleSize = 0;
        for (int i = 0; i < sampleSize; i++) {
            totalSampleSize += org.perlonjava.astvisitor.BytecodeSizeEstimator.estimateSnippetSize(elements.get(i));
        }
        int avgElementSize = totalSampleSize / sampleSize;

        int targetChunkBytes = 20000;
        int calculatedSize = avgElementSize > 0 ? targetChunkBytes / avgElementSize : context.maxChunkSize;

        return Math.max(context.minChunkSize, Math.min(context.maxChunkSize, calculatedSize));
    }

    /**
     * Split elements into chunks.
     */
    private static List<Node> splitIntoChunks(List<Node> elements, int chunkSize, int tokenIndex) {
        List<Node> chunks = new ArrayList<>();

        for (int i = 0; i < elements.size(); i += chunkSize) {
            int end = Math.min(i + chunkSize, elements.size());
            List<Node> chunkElements = new ArrayList<>(elements.subList(i, end));
            chunks.add(new ListNode(chunkElements, elements.get(i).getIndex()));
        }

        return chunks;
    }

    /**
     * Estimate total bytecode size.
     */
    private static long estimateTotalBytecodeSize(List<Node> nodes) {
        if (nodes.isEmpty()) {
            return 0;
        }

        if (nodes.size() <= 10) {
            long total = 0;
            for (Node node : nodes) {
                total += org.perlonjava.astvisitor.BytecodeSizeEstimator.estimateSnippetSize(node);
            }
            return total;
        }

        int sampleSize = Math.min(10, nodes.size());
        long totalSampleSize = 0;
        for (int i = 0; i < sampleSize; i++) {
            int index = (int) (((long) i * (nodes.size() - 1)) / (sampleSize - 1));
            totalSampleSize += org.perlonjava.astvisitor.BytecodeSizeEstimator.estimateSnippetSize(nodes.get(index));
        }
        return (totalSampleSize * nodes.size()) / sampleSize;
    }
}
