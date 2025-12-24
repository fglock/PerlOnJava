package org.perlonjava.codegen.refactor;

import org.perlonjava.astnode.*;
import org.perlonjava.astvisitor.BytecodeSizeEstimator;
import org.perlonjava.runtime.PerlCompilerException;

import java.util.ArrayList;
import java.util.List;

import static org.perlonjava.parser.ParserNodeUtils.variableAst;

/**
 * Unified refactorer for large node lists to avoid JVM method size limits.
 * <p>
 * This class provides a unified interface for refactoring any {@code List<Node>},
 * whether it's a block's statement list, an array literal, a hash literal, or
 * a list expression. The refactoring strategy adapts based on the node type and
 * control flow constraints.
 * <p>
 * <b>Key Features:</b>
 * <ul>
 *   <li>Unified interface: works with any {@code List<Node>}</li>
 *   <li>Control flow validation: fails safely when refactoring would break semantics</li>
 *   <li>Recursive fallback: attempts to refactor inner blocks when top-level fails</li>
 *   <li>Tail-position closures: preserves lexical scoping</li>
 * </ul>
 * <p>
 * <b>Refactoring Strategies:</b>
 * <ol>
 *   <li>Smart chunking: splits safe sequences into closures</li>
 *   <li>Tail extraction: extracts declaration-free tail sequences</li>
 *   <li>Complete structure extraction: extracts whole if/loop/try blocks</li>
 *   <li>Recursive inner refactoring: refactors inner blocks when outer fails</li>
 * </ol>
 * <p>
 * <b>Control Flow Safety:</b>
 * Control flow statements (next/last/redo/goto) cannot be wrapped in closures
 * because closures create a new scope. The refactorer validates control flow
 * and either:
 * <ul>
 *   <li>Skips refactoring if control flow would break</li>
 *   <li>Recursively refactors inner blocks that don't have control flow</li>
 *   <li>Extracts complete structures that preserve control flow context</li>
 * </ul>
 *
 * @see ControlFlowValidator
 * @see RecursiveBlockRefactorer
 */
public class NodeListRefactorer {

    /**
     * Refactor a list of nodes if needed.
     * <p>
     * This is the main entry point for refactoring. It checks if refactoring is
     * needed and applies appropriate strategies based on the context.
     *
     * @param nodes   the list of nodes to refactor
     * @param context the refactoring context
     * @return the refactoring result
     */
    public static RefactoringResult refactor(List<Node> nodes, RefactoringContext context) {
        if (!RefactoringContext.isRefactoringEnabled()) {
            return RefactoringResult.noChange(nodes, nodes.size(), estimateTotalBytecodeSize(nodes));
        }

        int originalCount = nodes.size();
        long originalSize = estimateTotalBytecodeSize(nodes);

        // Check if refactoring is needed
        if (!shouldRefactor(nodes, context)) {
            return RefactoringResult.noChange(nodes, originalCount, originalSize);
        }

        List<String> attemptedStrategies = new ArrayList<>();

        // Check for unsafe control flow
        if (ControlFlowValidator.hasUnsafeControlFlow(nodes)) {
            attemptedStrategies.add("control_flow_check");
            
            // Try recursive inner block refactoring
            if (tryRecursiveRefactoring(nodes, context)) {
                attemptedStrategies.add("recursive_inner_blocks");
                long finalSize = estimateTotalBytecodeSize(nodes);
                
                // Check if we achieved sufficient reduction
                if (finalSize <= context.maxBytecodeSize || finalSize < originalSize * 0.8) {
                    return RefactoringResult.success(nodes, originalCount, nodes.size(),
                            originalSize, finalSize, attemptedStrategies);
                }
            }
            
            // Control flow prevents refactoring and recursive didn't help enough
            String reason = buildFailureMessage(nodes, originalCount, originalSize, 
                    context.maxBytecodeSize, attemptedStrategies);
            return RefactoringResult.failure(nodes, originalCount, originalSize, reason, attemptedStrategies);
        }

        // Try smart chunking
        attemptedStrategies.add("smart_chunking");
        if (trySmartChunking(nodes, context)) {
            long finalSize = estimateTotalBytecodeSize(nodes);
            return RefactoringResult.success(nodes, originalCount, nodes.size(),
                    originalSize, finalSize, attemptedStrategies);
        }

        // Chunking didn't help
        return RefactoringResult.noChange(nodes, originalCount, originalSize);
    }

    /**
     * Determine if a list should be refactored based on size criteria.
     *
     * @param nodes   the list to check
     * @param context the refactoring context
     * @return true if refactoring is needed
     */
    private static boolean shouldRefactor(List<Node> nodes, RefactoringContext context) {
        if (nodes.size() < context.minElementCount) {
            return false;
        }

        long estimatedSize = estimateTotalBytecodeSize(nodes);
        return estimatedSize > context.maxBytecodeSize;
    }

    /**
     * Try smart chunking: split safe sequences into closures.
     * <p>
     * This strategy identifies sequences of nodes that can be safely wrapped
     * in closures (no control flow, no labels) and creates nested closures
     * at tail position.
     *
     * @param nodes   the list to chunk (modified in place)
     * @param context the refactoring context
     * @return true if chunking was applied
     */
    private static boolean trySmartChunking(List<Node> nodes, RefactoringContext context) {
        List<Object> segments = new ArrayList<>();  // Either Node or List<Node>
        List<Node> currentChunk = new ArrayList<>();

        for (Node element : nodes) {
            if (shouldBreakChunk(element)) {
                // Element breaks chunk (control flow or label)
                if (!currentChunk.isEmpty()) {
                    segments.add(new ArrayList<>(currentChunk));
                    currentChunk.clear();
                }
                segments.add(element);
            } else if (ControlFlowValidator.isCompleteBlock(element)) {
                // Complete blocks are already scoped
                if (!currentChunk.isEmpty()) {
                    segments.add(new ArrayList<>(currentChunk));
                    currentChunk.clear();
                }
                segments.add(element);
            } else {
                // Safe element, add to chunk
                currentChunk.add(element);
            }
        }

        // Add remaining chunk
        if (!currentChunk.isEmpty()) {
            segments.add(new ArrayList<>(currentChunk));
        }

        // Build nested structure
        List<Node> processedElements = buildNestedStructure(segments, context);

        // Apply if we reduced the count
        if (processedElements.size() < nodes.size()) {
            nodes.clear();
            nodes.addAll(processedElements);
            return true;
        }

        return false;
    }

    /**
     * Try recursive refactoring of inner blocks.
     * <p>
     * When top-level refactoring fails due to control flow, this strategy
     * recursively refactors inner blocks (within if/loop/try statements)
     * that don't contain control flow.
     *
     * @param nodes   the list containing nodes to recursively refactor
     * @param context the refactoring context
     * @return true if any inner block was refactored
     */
    private static boolean tryRecursiveRefactoring(List<Node> nodes, RefactoringContext context) {
        boolean improved = false;
        
        for (Node node : nodes) {
            improved |= RecursiveBlockRefactorer.refactorInnerBlocks(node, context);
        }
        
        return improved;
    }

    /**
     * Determine if an element should break the current chunk.
     *
     * @param element the element to check
     * @return true if this element should break chunking
     */
    private static boolean shouldBreakChunk(Node element) {
        // Labels break chunks
        if (ControlFlowValidator.isLabel(element)) {
            return true;
        }

        // Control flow breaks chunks
        if (ControlFlowValidator.hasUnsafeControlFlow(element)) {
            return true;
        }

        return false;
    }

    /**
     * Build nested closure structure from segments.
     * <p>
     * Structure: direct1, direct2, sub{ chunk1, sub{ chunk2, chunk3 }->(@_) }->(@_)
     * Closures are placed at tail position to preserve variable scoping.
     *
     * @param segments list of segments (Node or List<Node>)
     * @param context  the refactoring context
     * @return list of processed elements with nested structure
     */
    @SuppressWarnings("unchecked")
    private static List<Node> buildNestedStructure(List<Object> segments, RefactoringContext context) {
        if (segments.isEmpty()) {
            return new ArrayList<>();
        }

        List<Node> result = new ArrayList<>();

        for (int i = 0; i < segments.size(); i++) {
            Object segment = segments.get(i);

            if (segment instanceof Node directNode) {
                result.add(directNode);
            } else if (segment instanceof List) {
                List<Node> chunk = (List<Node>) segment;
                if (chunk.size() >= context.minChunkSize) {
                    // Create closure for this chunk at tail position
                    List<Node> blockElements = new ArrayList<>(chunk);

                    // Build nested closures for remaining chunks
                    for (int j = i + 1; j < segments.size(); j++) {
                        Object nextSegment = segments.get(j);
                        if (nextSegment instanceof Node) {
                            blockElements.add((Node) nextSegment);
                        } else if (nextSegment instanceof List) {
                            List<Node> nextChunk = (List<Node>) nextSegment;
                            if (nextChunk.size() >= context.minChunkSize) {
                                // Create nested closure
                                List<Node> nestedElements = new ArrayList<>(nextChunk);
                                for (int k = j + 1; k < segments.size(); k++) {
                                    Object remaining = segments.get(k);
                                    if (remaining instanceof Node) {
                                        nestedElements.add((Node) remaining);
                                    } else {
                                        nestedElements.addAll((List<Node>) remaining);
                                    }
                                }
                                BlockNode nestedBlock = createMarkedBlock(nestedElements, context.tokenIndex);
                                Node nestedClosure = createClosure(nestedBlock, context.tokenIndex);
                                blockElements.add(nestedClosure);
                                j = segments.size();
                                break;
                            } else {
                                blockElements.addAll(nextChunk);
                            }
                        }
                    }

                    BlockNode block = createMarkedBlock(blockElements, context.tokenIndex);
                    Node closure = createClosure(block, context.tokenIndex);
                    result.add(closure);
                    break;
                } else {
                    result.addAll(chunk);
                }
            }
        }

        return result;
    }

    /**
     * Create a closure: sub { block }->(@_)
     *
     * @param block      the block to wrap
     * @param tokenIndex token index for new nodes
     * @return the closure node
     */
    private static Node createClosure(BlockNode block, int tokenIndex) {
        return new BinaryOperatorNode(
                "->",
                new SubroutineNode(null, null, null, block, false, tokenIndex),
                new ListNode(new ArrayList<>(List.of(variableAst("@", "_", tokenIndex))), tokenIndex),
                tokenIndex
        );
    }

    /**
     * Create a BlockNode that won't be refactored during this pass.
     * Uses skipRefactoring flag to prevent infinite recursion.
     *
     * @param elements   the elements for the block
     * @param tokenIndex token index for new nodes
     * @return the marked block
     */
    private static BlockNode createMarkedBlock(List<Node> elements, int tokenIndex) {
        BlockRefactoringAdapter.setSkipRefactoring(true);
        try {
            BlockNode block = new BlockNode(elements, tokenIndex);

            // Extract labels from LabelNode elements
            for (Node element : elements) {
                if (element instanceof LabelNode labelNode) {
                    block.labels.add(labelNode.label);
                }
            }

            block.setAnnotation("blockAlreadyRefactored", true);
            return block;
        } finally {
            BlockRefactoringAdapter.setSkipRefactoring(false);
        }
    }

    /**
     * Estimate total bytecode size for a list of nodes.
     *
     * @param nodes the list to estimate
     * @return estimated bytecode size in bytes
     */
    private static long estimateTotalBytecodeSize(List<Node> nodes) {
        if (nodes.isEmpty()) {
            return 0;
        }

        if (nodes.size() <= 10) {
            long total = 0;
            for (Node node : nodes) {
                total += BytecodeSizeEstimator.estimateSnippetSize(node);
            }
            return total;
        }

        // Use sampling for large lists
        int sampleSize = Math.min(10, nodes.size());
        long totalSampleSize = 0;
        for (int i = 0; i < sampleSize; i++) {
            int index = (int) (((long) i * (nodes.size() - 1)) / (sampleSize - 1));
            totalSampleSize += BytecodeSizeEstimator.estimateSnippetSize(nodes.get(index));
        }
        return (totalSampleSize * nodes.size()) / sampleSize;
    }

    /**
     * Build an informative error message when refactoring fails.
     *
     * @param nodes              the node list that failed to refactor
     * @param originalCount      original element count
     * @param originalSize       original bytecode size
     * @param maxBytecodeSize    maximum allowed bytecode size
     * @param attemptedStrategies list of strategies that were attempted
     * @return error message
     */
    private static String buildFailureMessage(List<Node> nodes, int originalCount, long originalSize,
                                              long maxBytecodeSize, List<String> attemptedStrategies) {
        StringBuilder message = new StringBuilder();
        message.append("Node list is too large (")
                .append(originalCount)
                .append(" elements, estimated ")
                .append(originalSize)
                .append(" bytes) and cannot be automatically refactored.\n");

        message.append("Attempted strategies: ").append(attemptedStrategies).append("\n");

        // Find obstacles
        List<String> labels = new ArrayList<>();
        List<String> controlFlowStatements = new ArrayList<>();

        for (int i = 0; i < nodes.size(); i++) {
            Node element = nodes.get(i);
            if (element instanceof LabelNode labelNode) {
                labels.add(labelNode.label);
            }

            if (ControlFlowValidator.hasUnsafeControlFlow(element)) {
                controlFlowStatements.add("element at index " + i);
            }
        }

        if (!labels.isEmpty() || !controlFlowStatements.isEmpty()) {
            message.append("Obstacles: [");
            if (!labels.isEmpty()) {
                message.append("labels: ").append(String.join(", ", labels));
            }
            if (!labels.isEmpty() && !controlFlowStatements.isEmpty()) {
                message.append("; ");
            }
            if (!controlFlowStatements.isEmpty()) {
                message.append("control flow: ").append(String.join(", ", controlFlowStatements));
            }
            message.append("]\n");
        }

        message.append("Please manually split into smaller subroutines.");
        return message.toString();
    }

    /**
     * Throw an exception if refactoring failed and the list is still too large.
     *
     * @param result  the refactoring result
     * @param context the refactoring context
     * @throws PerlCompilerException if the list is too large
     */
    public static void throwIfTooLarge(RefactoringResult result, RefactoringContext context) {
        if (!result.success && result.finalBytecodeSize > context.maxBytecodeSize) {
            if (context.parser != null) {
                throw new PerlCompilerException(context.tokenIndex, result.failureReason, 
                        context.parser.ctx.errorUtil);
            } else {
                throw new PerlCompilerException(result.failureReason);
            }
        }
    }
}
