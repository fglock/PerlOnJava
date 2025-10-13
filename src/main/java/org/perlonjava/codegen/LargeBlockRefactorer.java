package org.perlonjava.codegen;

import org.perlonjava.astnode.*;
import org.perlonjava.astvisitor.ControlFlowDetectorVisitor;
import org.perlonjava.astvisitor.EmitterVisitor;

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
    private static final int LARGE_BLOCK_ELEMENT_COUNT = 8;  // Lowered from 16 for more aggressive refactoring
    private static final int LARGE_BYTECODE_SIZE = 30000;
    private static final int MIN_CHUNK_SIZE = 4;  // Minimum statements to extract as a chunk

    // Reusable visitor for control flow detection
    private static final ControlFlowDetectorVisitor controlFlowDetector = new ControlFlowDetectorVisitor();

    /**
     * Process a block and refactor it if necessary to avoid method size limits.
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
        
        // Skip main script body - it can contain labeled blocks with last/next/redo
        // that would break if refactored into a subroutine
        if (node.getBooleanAnnotation("blockIsMainScript")) {
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

        // Only refactor if explicitly enabled via JPERL_LARGECODE=refactor
        // Automatic refactoring is disabled because:
        // 1. It can break labeled blocks with last/next/redo (control flow detector has bugs)
        // 2. The original logic "refactor if goto labels exist" was backwards
        return refactorEnabled;
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
     *
     * @param node The block to chunk
     * @return true if chunking was successful, false otherwise
     */
    private static boolean trySmartChunking(BlockNode node) {
        List<Node> processedElements = new ArrayList<>();
        List<Node> currentChunk = new ArrayList<>();

        for (Node element : node.elements) {
            if (shouldBreakChunk(element)) {
                // This element cannot be in a chunk
                processChunk(currentChunk, processedElements, node.tokenIndex);
                currentChunk.clear();

                // Add the unsafe element directly
                processedElements.add(element);
            } else if (isCompleteBlock(element)) {
                // Complete blocks are already scoped
                processChunk(currentChunk, processedElements, node.tokenIndex);
                currentChunk.clear();
                processedElements.add(element);
            } else {
                // Safe element, add to current chunk
                currentChunk.add(element);
            }
        }

        // Process any remaining chunk
        processChunk(currentChunk, processedElements, node.tokenIndex);

        // Apply chunking if we reduced the element count
        if (processedElements.size() < node.elements.size()) {
            node.elements.clear();
            node.elements.addAll(processedElements);
            return true;
        }

        return false;
    }

    /**
     * Determine if an element should break the current chunk.
     */
    private static boolean shouldBreakChunk(Node element) {
        // Labels break chunks
        if (element instanceof LabelNode) {
            return true;
        }

        // Control flow statements break chunks
        controlFlowDetector.reset();
        element.accept(controlFlowDetector);
        if (controlFlowDetector.hasUnsafeControlFlow()) {
            return true;
        }

        // Top-level variable declarations break chunks (unless in a block)
        return !isCompleteBlock(element) && hasVariableDeclaration(element);
    }

    /**
     * Process accumulated chunk statements.
     */
    private static void processChunk(List<Node> chunk, List<Node> processedElements, int tokenIndex) {
        if (chunk.isEmpty()) {
            return;
        }

        if (chunk.size() >= MIN_CHUNK_SIZE) {
            // Create a closure for this chunk: sub { ... }->()
            BlockNode chunkBlock = new BlockNode(new ArrayList<>(chunk), tokenIndex);
            BinaryOperatorNode closure = new BinaryOperatorNode(
                    "->",
                    new SubroutineNode(null, null, null, chunkBlock, false, tokenIndex),
                    new ListNode(tokenIndex),  // Empty args - closures capture outer scope
                    tokenIndex
            );
            processedElements.add(closure);
        } else {
            // Chunk too small, add elements directly
            processedElements.addAll(chunk);
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
        int index = node.tokenIndex;
        ListNode args = new ListNode(index);
        args.elements.add(new OperatorNode("@", new IdentifierNode("_", index), index));

        // IMPORTANT: Mark the original block as already refactored to prevent recursion
        node.setAnnotation("blockAlreadyRefactored", true);

        // Create a wrapper block containing the original block
        BlockNode innerBlock = new BlockNode(List.of(node), index);

        BinaryOperatorNode subr = new BinaryOperatorNode(
                "->",
                new SubroutineNode(
                        null, null, null,
                        innerBlock,
                        false,
                        index
                ),
                args,
                index
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
}
