package org.perlonjava.codegen;

import org.perlonjava.astnode.*;
import org.perlonjava.astvisitor.BytecodeSizeEstimator;
import org.perlonjava.astvisitor.ControlFlowDetectorVisitor;
import org.perlonjava.astvisitor.EmitterVisitor;
import org.perlonjava.parser.Parser;
import org.perlonjava.runtime.PerlCompilerException;

import java.util.ArrayList;
import java.util.List;

import static org.perlonjava.parser.ParserNodeUtils.variableAst;

/**
 * Helper class for refactoring large blocks to avoid JVM's "Method too large" error.
 * <p>
 * This class encapsulates all logic for detecting and transforming large blocks,
 * including smart chunking strategies and control flow analysis.
 * <p>
 * <b>DEPRECATED:</b> This class has been replaced by the unified refactoring package
 * at {@link org.perlonjava.codegen.refactor}. Use {@link org.perlonjava.codegen.refactor.BlockRefactoringAdapter}
 * instead for new code.
 * <p>
 * <b>Migration:</b>
 * <pre>
 * // Old:
 * LargeBlockRefactorer.maybeRefactorBlock(blockNode, parser);
 * 
 * // New:
 * BlockRefactoringAdapter.maybeRefactorBlock(blockNode, parser);
 * </pre>
 *
 * @deprecated Use {@link org.perlonjava.codegen.refactor.BlockRefactoringAdapter} instead
 * @see org.perlonjava.codegen.refactor.BlockRefactoringAdapter
 * @see org.perlonjava.codegen.refactor.NodeListRefactorer
 */
@Deprecated
public class LargeBlockRefactorer {

    // Configuration thresholds
    private static final int LARGE_BLOCK_ELEMENT_COUNT = 50;  // Minimum elements before considering refactoring
    private static final int LARGE_BYTECODE_SIZE = 40000;
    private static final int MIN_CHUNK_SIZE = 2;  // Minimum statements to extract as a chunk (reduced to 2 for more aggressive refactoring)

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
        System.err.println("DEBUG: trySmartChunking - block size: " + node.elements.size());
        
        // First check if the block contains any control flow that would be unsafe to refactor
        // This catches cases where we're inside a loop body but don't know it yet (loop node not created)
        controlFlowDetector.reset();
        for (Node element : node.elements) {
            element.accept(controlFlowDetector);
            if (controlFlowDetector.hasUnsafeControlFlow()) {
                // Block contains last/next/redo/goto - cannot safely refactor
                // Control flow statements lose their loop context when wrapped in closures
                System.err.println("DEBUG: Detected unsafe control flow, skipping refactoring");
                return false;
            }
        }
        
        System.err.println("DEBUG: No control flow detected, proceeding with normal chunking");

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
     * Try fallback refactoring strategies when the block contains control flow.
     * This applies a series of increasingly aggressive strategies to reduce block size.
     *
     * @param node   The block to refactor
     * @param parser The parser instance for error reporting
     * @return true if any reduction was achieved
     */
    private static boolean tryFallbackRefactoring(BlockNode node, Parser parser) {
        boolean improved = false;
        int originalSize = node.elements.size();
        long originalBytecodeSize = estimateTotalBytecodeSize(node.elements);

        System.err.println("DEBUG: tryFallbackRefactoring - original size: " + originalSize + ", bytecode: " + originalBytecodeSize);

        // Strategy 0: FIRST - recursively refactor inner blocks
        boolean innerImproved = recursivelyRefactorInnerBlocks(node);
        improved |= innerImproved;
        System.err.println("DEBUG: After recursive inner blocks - size: " + node.elements.size() + ", improved: " + innerImproved);

        // Now try top-level strategies - iterate multiple times if needed
        for (int iteration = 0; iteration < 10; iteration++) {
            boolean iterationImproved = tryFallbackOnBlock(node);
            improved |= iterationImproved;
            System.err.println("DEBUG: After iteration " + iteration + " - size: " + node.elements.size() + ", improved: " + iterationImproved);
            if (!iterationImproved) {
                break; // No more improvements possible
            }
        }

        // Check if we achieved sufficient reduction
        if (improved) {
            node.setAnnotation("blockAlreadyRefactored", true);
            System.err.println("DEBUG: Fallback succeeded - final size: " + node.elements.size());
            return true;
        }

        // If refactoring didn't help and block is still too large, throw an error
        if (node.elements.size() > LARGE_BLOCK_ELEMENT_COUNT) {
            long estimatedSize = estimateTotalBytecodeSize(node.elements);
            if (estimatedSize > LARGE_BYTECODE_SIZE) {
                String message = buildFallbackErrorMessage(node, originalSize, originalBytecodeSize);
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
     * Apply fallback strategies to a single block.
     *
     * @param node The block to refactor
     * @return true if any reduction was achieved
     */
    private static boolean tryFallbackOnBlock(BlockNode node) {
        // Don't refactor loop bodies - they contain valid control flow
        if (node.isLoop) {
            System.err.println("DEBUG: Skipping loop body refactoring");
            return false;
        }

        boolean improved = false;

        // Strategy 1: Extract tail position sequences (stops at control flow)
        boolean s1 = extractTailPosition(node);
        improved |= s1;
        System.err.println("DEBUG: Strategy 1 (tail position) - improved: " + s1 + ", size now: " + node.elements.size());

        // Strategy 2: Extract complete structures at tail (preserves control flow context)
        boolean s2 = extractTailCompleteStructures(node);
        improved |= s2;
        System.err.println("DEBUG: Strategy 2 (complete structures) - improved: " + s2 + ", size now: " + node.elements.size());

        // Strategy 3: Transform unlabeled blocks with trailing control flow (keeps control flow outside closure)
        boolean s3 = transformUnlabeledBlocksWithTrailingControlFlow(node);
        improved |= s3;
        System.err.println("DEBUG: Strategy 3 (unlabeled blocks) - improved: " + s3 + ", size now: " + node.elements.size());

        // Strategy 4: Aggressive chunking - split large sequences even with control flow
        // DISABLED: This strategy can break control flow context
        // boolean s4 = aggressiveChunking(node);
        // improved |= s4;
        // System.err.println("DEBUG: Strategy 4 (aggressive chunking) - improved: " + s4 + ", size now: " + node.elements.size());

        return improved;
    }

    /**
     * Recursively refactor inner blocks within control structures.
     * Works depth-first (innermost blocks first).
     *
     * @param node The node to scan for inner blocks
     * @return true if any inner block was refactored
     */
    private static boolean recursivelyRefactorInnerBlocks(Node node) {
        boolean improved = false;

        if (node instanceof IfNode ifNode) {
            // Recursively process branches first
            if (ifNode.thenBranch != null) {
                improved |= recursivelyRefactorInnerBlocks(ifNode.thenBranch);
            }
            if (ifNode.elseBranch != null) {
                improved |= recursivelyRefactorInnerBlocks(ifNode.elseBranch);
            }
            // Then try to refactor the branches themselves if they're blocks
            if (ifNode.thenBranch instanceof BlockNode thenBlock) {
                improved |= tryFallbackOnBlock(thenBlock);
            }
            if (ifNode.elseBranch instanceof BlockNode elseBlock) {
                improved |= tryFallbackOnBlock(elseBlock);
            }
        } else if (node instanceof For1Node loop) {
            // Don't refactor loop bodies or anything inside them
            // They contain valid control flow (next/last/redo)
            if (loop.body != null) {
                improved |= recursivelyRefactorInnerBlocks(loop.body);
                // Don't call tryFallbackOnBlock on loop bodies
            }
        } else if (node instanceof For3Node loop) {
            // Don't refactor loop bodies or anything inside them
            // They contain valid control flow (next/last/redo)
            if (loop.body != null) {
                improved |= recursivelyRefactorInnerBlocks(loop.body);
                // Don't call tryFallbackOnBlock on loop bodies
            }
        } else if (node instanceof TryNode tryNode) {
            if (tryNode.tryBlock != null) {
                improved |= recursivelyRefactorInnerBlocks(tryNode.tryBlock);
                if (tryNode.tryBlock instanceof BlockNode tryBlock) {
                    improved |= tryFallbackOnBlock(tryBlock);
                }
            }
            if (tryNode.catchBlock != null) {
                improved |= recursivelyRefactorInnerBlocks(tryNode.catchBlock);
                if (tryNode.catchBlock instanceof BlockNode catchBlock) {
                    improved |= tryFallbackOnBlock(catchBlock);
                }
            }
        } else if (node instanceof BlockNode block) {
            // Recursively process all elements
            for (Node element : block.elements) {
                improved |= recursivelyRefactorInnerBlocks(element);
            }
        }

        return improved;
    }

    /**
     * Extract tail-position sequences that don't have variable declarations.
     * Works backwards from the end until hitting a declaration, label, or control flow.
     *
     * @param node The block to process
     * @return true if extraction was performed
     */
    private static boolean extractTailPosition(BlockNode node) {
        if (node.elements.size() < MIN_CHUNK_SIZE * 2) {
            return false;
        }

        // Skip if already refactored
        if (node.getBooleanAnnotation("blockAlreadyRefactored")) {
            return false;
        }

        // Work backwards to find where we can start extracting
        int extractStartIndex = node.elements.size();
        for (int i = node.elements.size() - 1; i >= 0; i--) {
            Node element = node.elements.get(i);

            // Stop if we hit a declaration
            if (hasVariableDeclaration(element)) {
                break;
            }

            // Stop if we hit a label - but also stop before the label
            // Labels must stay at block scope with their labeled statement
            if (element instanceof LabelNode) {
                break;
            }
            // If the next element (towards the start) is a label, stop here to keep label+statement together
            if (i > 0 && node.elements.get(i - 1) instanceof LabelNode) {
                break;
            }

            // Stop if we hit control flow
            controlFlowDetector.reset();
            element.accept(controlFlowDetector);
            if (controlFlowDetector.hasUnsafeControlFlow()) {
                break;
            }

            extractStartIndex = i;
        }

        // Check if we have enough elements to extract
        int tailSize = node.elements.size() - extractStartIndex;
        if (tailSize < MIN_CHUNK_SIZE) {
            return false;
        }

        // Extract the tail
        List<Node> tailElements = new ArrayList<>(node.elements.subList(extractStartIndex, node.elements.size()));
        node.elements.subList(extractStartIndex, node.elements.size()).clear();

        // Create closure for tail
        BlockNode tailBlock = createMarkedBlock(tailElements, node.tokenIndex);
        Node closure = new BinaryOperatorNode(
                "->",
                new SubroutineNode(null, null, null, tailBlock, false, node.tokenIndex),
                new ListNode(new ArrayList<>(List.of(variableAst("@", "_", node.tokenIndex))), node.tokenIndex),
                node.tokenIndex
        );
        node.elements.add(closure);

        return true;
    }

    /**
     * Extract complete control structures (if/else, loops, try, labeled blocks) at tail position.
     *
     * @param node The block to process
     * @return true if extraction was performed
     */
    private static boolean extractTailCompleteStructures(BlockNode node) {
        if (node.elements.size() < 2) {
            return false;
        }

        // Check if the last element is a complete structure
        Node lastElement = node.elements.get(node.elements.size() - 1);
        if (!isCompleteBlock(lastElement) && !(lastElement instanceof LabelNode)) {
            return false;
        }

        // Find how many tail elements we can extract as complete structures
        int extractStartIndex = node.elements.size();
        for (int i = node.elements.size() - 1; i >= 0; i--) {
            Node element = node.elements.get(i);

            // Can extract complete blocks and labeled blocks
            if (isCompleteBlock(element) || element instanceof LabelNode) {
                extractStartIndex = i;
            } else {
                break;
            }
        }

        // Check if we have enough to extract
        int tailSize = node.elements.size() - extractStartIndex;
        if (tailSize < 1 || extractStartIndex == 0) {
            return false;
        }

        // Extract the tail structures
        List<Node> tailElements = new ArrayList<>(node.elements.subList(extractStartIndex, node.elements.size()));
        node.elements.subList(extractStartIndex, node.elements.size()).clear();

        // Create closure for tail
        BlockNode tailBlock = createMarkedBlock(tailElements, node.tokenIndex);
        Node closure = new BinaryOperatorNode(
                "->",
                new SubroutineNode(null, null, null, tailBlock, false, node.tokenIndex),
                new ListNode(new ArrayList<>(List.of(variableAst("@", "_", node.tokenIndex))), node.tokenIndex),
                node.tokenIndex
        );
        node.elements.add(closure);

        return true;
    }

    /**
     * Transform unlabeled blocks that end with control flow statements.
     * Splits the block body from the trailing control flow.
     *
     * @param node The block to process
     * @return true if any transformation was performed
     */
    private static boolean transformUnlabeledBlocksWithTrailingControlFlow(BlockNode node) {
        boolean improved = false;

        for (int i = 0; i < node.elements.size(); i++) {
            Node element = node.elements.get(i);

            // Only process BlockNode elements (not labeled blocks)
            if (!(element instanceof BlockNode innerBlock)) {
                continue;
            }

            // Skip if this is a labeled block (check previous element)
            if (i > 0 && node.elements.get(i - 1) instanceof LabelNode) {
                continue;
            }

            // Check if block ends with control flow
            if (innerBlock.elements.isEmpty()) {
                continue;
            }

            Node lastElement = innerBlock.elements.get(innerBlock.elements.size() - 1);
            controlFlowDetector.reset();
            lastElement.accept(controlFlowDetector);

            if (!controlFlowDetector.hasUnsafeControlFlow()) {
                continue;
            }

            // Check if there are enough elements before the control flow to extract
            if (innerBlock.elements.size() < MIN_CHUNK_SIZE + 1) {
                continue;
            }

            // Split: extract all but the last element into a closure
            List<Node> bodyElements = new ArrayList<>(innerBlock.elements.subList(0, innerBlock.elements.size() - 1));
            Node controlFlowElement = innerBlock.elements.get(innerBlock.elements.size() - 1);

            // Create closure for body
            BlockNode bodyBlock = createMarkedBlock(bodyElements, innerBlock.tokenIndex);
            Node closure = new BinaryOperatorNode(
                    "->",
                    new SubroutineNode(null, null, null, bodyBlock, false, innerBlock.tokenIndex),
                    new ListNode(new ArrayList<>(List.of(variableAst("@", "_", innerBlock.tokenIndex))), innerBlock.tokenIndex),
                    innerBlock.tokenIndex
            );

            // Replace inner block contents with closure + control flow
            innerBlock.elements.clear();
            innerBlock.elements.add(closure);
            innerBlock.elements.add(controlFlowElement);

            improved = true;
        }

        return improved;
    }

    /**
     * Aggressive chunking strategy: split the block into smaller chunks,
     * but avoid wrapping control flow statements (last/next/redo/goto) in closures.
     *
     * @param node The block to process
     * @return true if any chunking was performed
     */
    private static boolean aggressiveChunking(BlockNode node) {
        if (node.elements.size() < MIN_CHUNK_SIZE * 2) {
            return false;
        }

        // Skip if already refactored
        if (node.getBooleanAnnotation("blockAlreadyRefactored")) {
            return false;
        }

        // Split the block into chunks of reasonable size
        List<Node> newElements = new ArrayList<>();
        List<Node> currentChunk = new ArrayList<>();
        int chunkSizeLimit = 20; // Target chunk size

        for (int i = 0; i < node.elements.size(); i++) {
            Node element = node.elements.get(i);

            // Check if this element contains control flow
            controlFlowDetector.reset();
            element.accept(controlFlowDetector);
            boolean hasControlFlow = controlFlowDetector.hasUnsafeControlFlow();

            // If element has control flow, finalize current chunk and add element directly
            if (hasControlFlow) {
                if (!currentChunk.isEmpty() && currentChunk.size() >= MIN_CHUNK_SIZE) {
                    BlockNode chunkBlock = createMarkedBlock(new ArrayList<>(currentChunk), node.tokenIndex);
                    Node closure = new BinaryOperatorNode(
                            "->",
                            new SubroutineNode(null, null, null, chunkBlock, false, node.tokenIndex),
                            new ListNode(new ArrayList<>(List.of(variableAst("@", "_", node.tokenIndex))), node.tokenIndex),
                            node.tokenIndex
                    );
                    newElements.add(closure);
                } else if (!currentChunk.isEmpty()) {
                    newElements.addAll(currentChunk);
                }
                currentChunk.clear();
                newElements.add(element);
                continue;
            }

            // Labels can be included in chunks along with their labeled statement
            // The label and the next statement form an atomic unit
            if (element instanceof LabelNode) {
                currentChunk.add(element);
                // Also add the labeled statement to the same chunk
                if (i + 1 < node.elements.size()) {
                    i++;
                    currentChunk.add(node.elements.get(i));
                }
                continue;
            }

            currentChunk.add(element);

            // Check if we should finalize this chunk
            boolean shouldFinalize = false;

            // Finalize if chunk is large enough
            if (currentChunk.size() >= chunkSizeLimit) {
                shouldFinalize = true;
            }

            // Finalize at the end
            if (i == node.elements.size() - 1) {
                shouldFinalize = true;
            }

            if (shouldFinalize && currentChunk.size() >= MIN_CHUNK_SIZE) {
                // Create a closure for this chunk
                BlockNode chunkBlock = createMarkedBlock(new ArrayList<>(currentChunk), node.tokenIndex);
                Node closure = new BinaryOperatorNode(
                        "->",
                        new SubroutineNode(null, null, null, chunkBlock, false, node.tokenIndex),
                        new ListNode(new ArrayList<>(List.of(variableAst("@", "_", node.tokenIndex))), node.tokenIndex),
                        node.tokenIndex
                );
                newElements.add(closure);
                currentChunk.clear();
            } else if (shouldFinalize && !currentChunk.isEmpty()) {
                // Chunk too small, add directly
                newElements.addAll(currentChunk);
                currentChunk.clear();
            }
        }

        // Add any remaining elements
        if (!currentChunk.isEmpty()) {
            newElements.addAll(currentChunk);
        }

        // Apply if we reduced the count
        if (newElements.size() < node.elements.size()) {
            node.elements.clear();
            node.elements.addAll(newElements);
            return true;
        }

        return false;
    }

    /**
     * Build an informative error message when fallback refactoring fails.
     */
    private static String buildFallbackErrorMessage(BlockNode node, int originalSize, long originalBytecodeSize) {
        StringBuilder message = new StringBuilder();
        message.append("Block is too large (")
                .append(node.elements.size())
                .append(" elements, estimated ")
                .append(estimateTotalBytecodeSize(node.elements))
                .append(" bytes) and cannot be automatically refactored.\n");

        message.append("Attempted strategies: [recursive inner blocks, tail extraction, complete structures, trailing control flow]\n");

        // Find obstacles
        List<String> labels = new ArrayList<>();
        List<String> controlFlowStatements = new ArrayList<>();

        for (int i = 0; i < node.elements.size(); i++) {
            Node element = node.elements.get(i);
            if (element instanceof LabelNode labelNode) {
                labels.add(labelNode.label);
            }

            controlFlowDetector.reset();
            element.accept(controlFlowDetector);
            if (controlFlowDetector.hasUnsafeControlFlow()) {
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
                                Node nestedClosure = new BinaryOperatorNode(
                                        "->",
                                        new SubroutineNode(null, null, null, nestedBlock, false, tokenIndex),
                                        new ListNode(new ArrayList<>(List.of(variableAst("@", "_", tokenIndex))), tokenIndex),
                                        tokenIndex
                                );
                                blockElements.add(nestedClosure);
                                j = segments.size(); // Break outer loop
                                break;
                            } else {
                                blockElements.addAll(nextChunk);
                            }
                        }
                    }
                    
                    BlockNode block = createMarkedBlock(blockElements, tokenIndex);
                    Node closure = new BinaryOperatorNode(
                            "->",
                            new SubroutineNode(null, null, null, block, false, tokenIndex),
                            new ListNode(new ArrayList<>(List.of(variableAst("@", "_", tokenIndex))), tokenIndex),
                            tokenIndex
                    );
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
     * Create a BlockNode that will NOT be refactored during this pass.
     * Uses skipRefactoring flag to prevent infinite recursion.
     * Properly handles labels by extracting LabelNode elements and adding them to the block's labels list.
     */
    private static BlockNode createMarkedBlock(List<Node> elements, int tokenIndex) {
        // Temporarily disable automatic refactoring during construction to prevent infinite recursion
        skipRefactoring.set(true);
        try {
            BlockNode block = new BlockNode(elements, tokenIndex);
            
            // Extract labels from LabelNode elements and add to block's labels list
            for (Node element : elements) {
                if (element instanceof LabelNode labelNode) {
                    block.labels.add(labelNode.label);
                }
            }
            
            // Mark as already refactored to prevent future refactoring attempts
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

        BinaryOperatorNode subr = new BinaryOperatorNode(
                "->",
                new SubroutineNode(
                        null, null, null,
                        innerBlock,
                        false,
                        tokenIndex
                ),
                new ListNode(
                        new ArrayList<>(List.of(variableAst("@", "_", tokenIndex))), tokenIndex),
                tokenIndex
        );

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
