package org.perlonjava.astrefactor;

import org.perlonjava.astnode.*;
import org.perlonjava.astvisitor.ControlFlowDetectorVisitor;
import org.perlonjava.astvisitor.ControlFlowFinder;
import org.perlonjava.astvisitor.EmitterVisitor;
import org.perlonjava.parser.Parser;

import java.util.ArrayList;
import java.util.List;

import static org.perlonjava.astrefactor.BlockRefactor.*;
import static org.perlonjava.astrefactor.LargeNodeRefactorer.IS_REFACTORING_ENABLED;

/**
 * Helper class for refactoring large blocks to avoid JVM's "Method too large" error.
 * <p>
 * This class encapsulates all logic for detecting and transforming large blocks,
 * including smart chunking strategies and control flow analysis.
 */
public class LargeBlockRefactorer {

    // Reusable visitor for control flow detection
    private static final ControlFlowDetectorVisitor controlFlowDetector = new ControlFlowDetectorVisitor();

    // Thread-local flag to prevent recursion when creating chunk blocks
    private static final ThreadLocal<Boolean> skipRefactoring = ThreadLocal.withInitial(() -> false);

    /**
     * Parse-time entry point: called from BlockNode constructor to refactor large blocks.
     * This applies smart chunking to split safe statement sequences into closures.
     * Only runs when JPERL_LARGECODE=refactor is set.
     *
     * @param node   The block to potentially refactor (modified in place)
     * @param parser The parser instance for access to error utilities (can be null if not available)
     */
    public static void maybeRefactorBlock(BlockNode node, Parser parser) {
        // Skip if refactoring is not enabled
        // This is critical - we only do bytecode size estimation when refactoring is enabled
        // to avoid parse-time overhead and potential issues with partially constructed AST
        if (!IS_REFACTORING_ENABLED) {
            return;
        }

        // Skip if we're inside createMarkedBlock (prevents recursion)
        if (skipRefactoring.get()) {
            node.setAnnotation("refactorSkipReason", "Inside createMarkedBlock (recursion prevention)");
            return;
        }

        // Skip if already refactored (prevents infinite recursion)
        if (node.getBooleanAnnotation("blockAlreadyRefactored")) {
            node.setAnnotation("refactorSkipReason", "Already refactored");
            return;
        }

        // Skip special blocks (BEGIN, END, etc.)
        if (isSpecialContext(node)) {
            node.setAnnotation("refactorSkipReason", "Special block (BEGIN/END/etc)");
            return;
        }

        // Apply smart chunking
        trySmartChunking(node, parser);
    }

    /**
     * Second-pass AST processor: walks the entire AST after parsing and applies
     * the same refactoring logic that was previously done at code-gen time.
     * This allows blocks to be refactored based on the reduced size after first-pass refactoring.
     *
     * @param ast    The root AST node
     * @param parser The parser for error reporting
     */
    public static void applySecondPass(Node ast, Parser parser) {
        if (!IS_REFACTORING_ENABLED) {
            return;
        }

        SecondPassProcessor processor = new SecondPassProcessor(parser);
        ast.accept(processor);
    }

    /**
     * AST processor that applies second-pass refactoring to all blocks.
     */
    private static class SecondPassProcessor implements org.perlonjava.astvisitor.Visitor {
        private final Parser parser;

        SecondPassProcessor(Parser parser) {
            this.parser = parser;
        }

        @Override
        public void visit(BlockNode node) {
            // Apply the same logic as processBlock, but at parse time
            // CRITICAL: Skip if this block was already refactored to prevent infinite recursion
            if (node.getBooleanAnnotation("blockAlreadyRefactored")) {
                // Continue to children
                for (Node element : node.elements) {
                    if (element != null) element.accept(this);
                }
                return;
            }

            // Skip if block is already a subroutine or is a special block
            if (node.getBooleanAnnotation("blockIsSubroutine")) {
                for (Node element : node.elements) {
                    if (element != null) element.accept(this);
                }
                return;
            }

            // Determine if we need to refactor (same logic as shouldRefactorBlock)
            boolean needsRefactoring = false;
            if (node.elements.size() > MIN_CHUNK_SIZE) {
                needsRefactoring = true; // Refactoring is enabled (we're in second pass)
            }

            if (needsRefactoring && !isSpecialContext(node)) {
                // Try whole-block refactoring (same as tryWholeBlockRefactoring but at parse time)
                tryWholeBlockRefactoringAtParseTime(node, parser);
            }

            // Continue walking into child blocks
            for (Node element : node.elements) {
                if (element != null) {
                    element.accept(this);
                }
            }
        }

        // Traverse the AST
        @Override public void visit(OperatorNode node) {
            if (node.operand != null) node.operand.accept(this);
        }
        @Override public void visit(BinaryOperatorNode node) {
            if (node.left != null) node.left.accept(this);
            if (node.right != null) node.right.accept(this);
        }
        @Override public void visit(TernaryOperatorNode node) {
            if (node.condition != null) node.condition.accept(this);
            if (node.trueExpr != null) node.trueExpr.accept(this);
            if (node.falseExpr != null) node.falseExpr.accept(this);
        }
        @Override public void visit(IfNode node) {
            if (node.condition != null) node.condition.accept(this);
            if (node.thenBranch != null) node.thenBranch.accept(this);
            if (node.elseBranch != null) node.elseBranch.accept(this);
        }
        @Override public void visit(For1Node node) {
            if (node.variable != null) node.variable.accept(this);
            if (node.list != null) node.list.accept(this);
            if (node.body != null) node.body.accept(this);
        }
        @Override public void visit(For3Node node) {
            if (node.initialization != null) node.initialization.accept(this);
            if (node.condition != null) node.condition.accept(this);
            if (node.increment != null) node.increment.accept(this);
            if (node.body != null) node.body.accept(this);
        }
        @Override public void visit(TryNode node) {
            if (node.tryBlock != null) node.tryBlock.accept(this);
            if (node.catchBlock != null) node.catchBlock.accept(this);
            if (node.finallyBlock != null) node.finallyBlock.accept(this);
        }
        @Override public void visit(ListNode node) {
            for (Node element : node.elements) {
                if (element != null) element.accept(this);
            }
        }
        @Override public void visit(SubroutineNode node) {
            if (node.block != null) node.block.accept(this);
        }
        @Override public void visit(HashLiteralNode node) {
            for (Node element : node.elements) {
                if (element != null) element.accept(this);
            }
        }
        @Override public void visit(ArrayLiteralNode node) {
            for (Node element : node.elements) {
                if (element != null) element.accept(this);
            }
        }
        
        // Leaf nodes
        @Override public void visit(IdentifierNode node) {}
        @Override public void visit(NumberNode node) {}
        @Override public void visit(StringNode node) {}
        @Override public void visit(LabelNode node) {}
        @Override public void visit(CompilerFlagNode node) {}
        @Override public void visit(FormatNode node) {}
        @Override public void visit(FormatLine node) {}
    }

    /**
     * Try to refactor entire block at parse time (second pass).
     * Same logic as tryWholeBlockRefactoring but modifies AST instead of emitting.
     */
    private static void tryWholeBlockRefactoringAtParseTime(BlockNode node, Parser parser) {
        // Check for unsafe control flow
        controlFlowDetector.reset();
        node.accept(controlFlowDetector);
        if (controlFlowDetector.hasUnsafeControlFlow()) {
            return;
        }

        // Mark as refactored to prevent recursion
        node.setAnnotation("blockAlreadyRefactored", true);

        // Create wrapper: sub { original_block_contents }->(@_)
        List<Node> originalElements = new ArrayList<>(node.elements);
        
        skipRefactoring.set(true);
        try {
            BlockNode innerBlock = new BlockNode(originalElements, node.tokenIndex);
            innerBlock.setAnnotation("blockAlreadyRefactored", true);
            
            BinaryOperatorNode subCall = createAnonSubCall(node.tokenIndex, innerBlock);
            
            // Replace block contents with the subroutine call
            node.elements.clear();
            node.elements.add(subCall);
        } finally {
            skipRefactoring.set(false);
        }
    }

    /**
     * Code-generation time entry point - no longer performs refactoring.
     * All refactoring now happens at parse time (first pass + second pass).
     *
     * @param emitterVisitor The emitter visitor context
     * @param node           The block to process
     * @return always false - no code-gen refactoring
     */
    public static boolean processBlock(EmitterVisitor emitterVisitor, BlockNode node) {
        // All refactoring now happens at parse time via:
        // 1. First pass: maybeRefactorBlock (during BlockNode construction)
        // 2. Second pass: applySecondPass (after full AST is built)
        return false;
    }

    /**
     * Determine if a block should be refactored based on size criteria.
     * Uses minimal element count check to avoid overhead on trivial blocks.
     *
     * @param node             The block to check
     * @param emitterVisitor   The emitter visitor for context
     * @param refactorEnabled  Whether refactoring is enabled
     * @return true if the block should be refactored
     */
    private static boolean shouldRefactorBlock(BlockNode node, EmitterVisitor emitterVisitor, boolean refactorEnabled) {
        // Minimal check: skip very small blocks to avoid estimation overhead
        if (node.elements.size() <= MIN_CHUNK_SIZE) {
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
     */
    private static void trySmartChunking(BlockNode node, Parser parser) {
        // Minimal check: skip very small blocks to avoid estimation overhead
        if (node.elements.size() <= MIN_CHUNK_SIZE) {
            node.setAnnotation("refactorSkipReason", String.format("Element count %d <= %d (minimal threshold)", node.elements.size(), MIN_CHUNK_SIZE));
            return;
        }
        
        // Check bytecode size - skip if under threshold
        long estimatedSize = estimateTotalBytecodeSize(node.elements);
        node.setAnnotation("estimatedBytecodeSize", estimatedSize);
        if (estimatedSize <= LARGE_BYTECODE_SIZE) {
            node.setAnnotation("refactorSkipReason", String.format("Bytecode size %d <= threshold %d", estimatedSize, LARGE_BYTECODE_SIZE));
            return;
        }
        
        // Check if the block has any labels (stored in BlockNode.labels field)
        // Labels define goto/next/last targets and must remain at block level
        if (node.labels != null && !node.labels.isEmpty()) {
            // Block has labels - skip refactoring to preserve label scope
            node.setAnnotation("refactorSkipReason", "Block has labels");
            return;
        }

        List<Object> segments = new ArrayList<>();  // Either Node (direct) or List<Node> (chunk)
        List<Node> currentChunk = new ArrayList<>();

        for (Node element : node.elements) {
            if (isCompleteBlock(element)) {
                // Complete blocks are already scoped - but check for labeled control flow
                // Labeled control flow might reference labels outside the block
                if (!currentChunk.isEmpty()) {
                    segments.add(new ArrayList<>(currentChunk));
                    currentChunk.clear();
                }
                segments.add(element);
            } else if (shouldBreakChunk(element)) {
                // This element cannot be in a chunk (has unsafe control flow or is a label)
                if (!currentChunk.isEmpty()) {
                    segments.add(new ArrayList<>(currentChunk));
                    currentChunk.clear();
                }
                // Add the element directly
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

        // Check ALL segments (both direct and chunks) for UNSAFE control flow
        // Use ControlFlowDetectorVisitor which considers loop depth
        // Unlabeled next/last/redo inside loops are safe, but labeled control flow is not
        for (Object segment : segments) {
            if (segment instanceof Node directNode) {
                controlFlowDetector.reset();
                directNode.accept(controlFlowDetector);
                if (controlFlowDetector.hasUnsafeControlFlow()) {
                    // Segment has unsafe control flow - skip refactoring
                    node.setAnnotation("refactorSkipReason", "Unsafe control flow in direct segment");
                    return;
                }
            } else if (segment instanceof List) {
                @SuppressWarnings("unchecked")
                List<Node> chunk = (List<Node>) segment;
                for (Node element : chunk) {
                    controlFlowDetector.reset();
                    element.accept(controlFlowDetector);
                    if (controlFlowDetector.hasUnsafeControlFlow()) {
                        // Chunk has unsafe control flow - skip refactoring
                        node.setAnnotation("refactorSkipReason", "Unsafe control flow in chunk");
                        return;
                    }
                }
            }
        }
        
        // Build nested structure if we have any chunks
        List<Node> processedElements = buildNestedStructure(
                segments,
                node.tokenIndex,
                MIN_CHUNK_SIZE,
                false, // returnTypeIsList = false: execute statements, don't return list
                skipRefactoring
        );

        // Apply chunking if we reduced the element count
        if (processedElements.size() < node.elements.size()) {
            node.elements.clear();
            node.elements.addAll(processedElements);
            node.setAnnotation("blockAlreadyRefactored", true);
            
            // Verify refactoring was successful
            long newEstimatedSize = estimateTotalBytecodeSize(node.elements);
            node.setAnnotation("refactoredBytecodeSize", newEstimatedSize);
            long originalSize = (Long) node.getAnnotation("estimatedBytecodeSize");
            if (newEstimatedSize > LARGE_BYTECODE_SIZE) {
                node.setAnnotation("refactorSkipReason", String.format("Refactoring failed: size %d still > threshold %d", newEstimatedSize, LARGE_BYTECODE_SIZE));
                errorCantRefactorLargeBlock(node.tokenIndex, parser, newEstimatedSize);
            }
            node.setAnnotation("refactorSkipReason", String.format("Successfully refactored: %d -> %d bytes", originalSize, newEstimatedSize));
            return;
        }

        // If refactoring didn't help and block is still too large, throw an error
        long finalEstimatedSize = estimateTotalBytecodeSize(node.elements);
        if (finalEstimatedSize > LARGE_BYTECODE_SIZE) {
            node.setAnnotation("refactorSkipReason", String.format("Refactoring didn't reduce element count, size %d > threshold %d", finalEstimatedSize, LARGE_BYTECODE_SIZE));
            errorCantRefactorLargeBlock(node.tokenIndex, parser, finalEstimatedSize);
        }
        node.setAnnotation("refactorSkipReason", String.format("Refactoring didn't reduce element count, but size %d <= threshold %d", finalEstimatedSize, LARGE_BYTECODE_SIZE));

    }


    /**
     * Determine if an element should break the current chunk.
     * Labels and ANY control flow statements break chunks - they must stay as direct elements.
     * This is more conservative than ControlFlowDetectorVisitor because we need to catch
     * ALL control flow, not just "unsafe" control flow (which considers loop depth).
     */
    private static boolean shouldBreakChunk(Node element) {
        // Labels break chunks - they're targets for goto/next/last
        if (element instanceof LabelNode) {
            return true;
        }

        // Check if element contains ANY control flow (last/next/redo/goto)
        // We use a custom visitor that doesn't consider loop depth
        ControlFlowFinder finder = new ControlFlowFinder();
        element.accept(finder);
        return finder.foundControlFlow;
    }

    /**
     * Try to refactor the entire block as a subroutine.
     */
    private static boolean tryWholeBlockRefactoring(EmitterVisitor emitterVisitor, BlockNode node) {
        // Check for unsafe control flow using ControlFlowDetectorVisitor
        // This properly handles loop depth - unlabeled next/last/redo inside loops are safe
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

        BinaryOperatorNode subr = createAnonSubCall(tokenIndex, innerBlock);

        // Emit the refactored block
        subr.accept(emitterVisitor);
        return true;
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
     * Check if a chunk would be wrapped in a closure based on its size.
     *
     * @param chunk The chunk to check
     * @return true if the chunk is large enough to be wrapped (>= MIN_CHUNK_SIZE)
     */
    private static boolean chunkWouldBeWrapped(List<Node> chunk) {
        return chunk.size() >= MIN_CHUNK_SIZE;
    }

    /**
     * Check if a chunk contains unsafe control flow.
     * This checks for any control flow statements (last/next/redo/goto) that would break
     * if the chunk is wrapped in a closure.
     *
     * @param chunk The chunk to check
     * @return true if unsafe control flow found
     */
    private static boolean chunkHasUnsafeControlFlow(List<Node> chunk) {
        controlFlowDetector.reset();
        for (Node element : chunk) {
            element.accept(controlFlowDetector);
            if (controlFlowDetector.hasUnsafeControlFlow()) {
                return true;
            }
        }
        return false;
    }

}
