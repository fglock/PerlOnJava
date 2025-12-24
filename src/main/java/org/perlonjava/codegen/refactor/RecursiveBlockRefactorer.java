package org.perlonjava.codegen.refactor;

import org.perlonjava.astnode.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursively refactors inner blocks within control structures.
 * <p>
 * When top-level refactoring fails due to control flow statements, this class
 * attempts to refactor inner blocks that don't contain control flow. This is
 * particularly useful for large if/else branches, try/catch blocks, and nested
 * structures.
 * <p>
 * <b>Strategy:</b>
 * <ol>
 *   <li>Work depth-first (innermost blocks first)</li>
 *   <li>For each inner block, check if it contains control flow</li>
 *   <li>If no control flow, apply normal chunking</li>
 *   <li>Skip loop bodies (they contain valid control flow)</li>
 * </ol>
 * <p>
 * <b>Supported Structures:</b>
 * <ul>
 *   <li>{@link IfNode} - refactor then/else branches</li>
 *   <li>{@link For1Node}/{@link For3Node} - recursively scan loop bodies (but don't refactor them)</li>
 *   <li>{@link TryNode} - refactor try/catch blocks</li>
 *   <li>{@link BlockNode} - recursively scan nested blocks</li>
 * </ul>
 *
 * @see NodeListRefactorer
 * @see ControlFlowValidator
 */
public class RecursiveBlockRefactorer {

    /**
     * Recursively refactor inner blocks within a node.
     * <p>
     * This method traverses the AST depth-first, looking for inner blocks
     * that can be safely refactored (no control flow, not loop bodies).
     *
     * @param node    the node to scan for inner blocks
     * @param context the refactoring context
     * @return true if any inner block was refactored
     */
    public static boolean refactorInnerBlocks(Node node, RefactoringContext context) {
        if (node == null) {
            return false;
        }

        boolean improved = false;

        if (node instanceof IfNode ifNode) {
            improved |= refactorIfNode(ifNode, context);
        } else if (node instanceof For1Node loop) {
            improved |= refactorFor1Node(loop, context);
        } else if (node instanceof For3Node loop) {
            improved |= refactorFor3Node(loop, context);
        } else if (node instanceof TryNode tryNode) {
            improved |= refactorTryNode(tryNode, context);
        } else if (node instanceof BlockNode block) {
            improved |= refactorBlockNode(block, context);
        }

        return improved;
    }

    /**
     * Refactor an if/else node's branches.
     *
     * @param ifNode  the if node to refactor
     * @param context the refactoring context
     * @return true if any branch was refactored
     */
    private static boolean refactorIfNode(IfNode ifNode, RefactoringContext context) {
        boolean improved = false;

        // Recursively process branches first (depth-first)
        if (ifNode.thenBranch != null) {
            improved |= refactorInnerBlocks(ifNode.thenBranch, context);
        }
        if (ifNode.elseBranch != null) {
            improved |= refactorInnerBlocks(ifNode.elseBranch, context);
        }

        // Try to refactor the branches themselves if they're blocks without control flow
        if (ifNode.thenBranch instanceof BlockNode thenBlock) {
            if (!ControlFlowValidator.hasUnsafeControlFlow(thenBlock.elements) &&
                !ControlFlowValidator.isLoopBody(thenBlock)) {
                RefactoringResult result = NodeListRefactorer.refactor(thenBlock.elements, context);
                improved |= result.modified;
            }
        }
        if (ifNode.elseBranch instanceof BlockNode elseBlock) {
            if (!ControlFlowValidator.hasUnsafeControlFlow(elseBlock.elements) &&
                !ControlFlowValidator.isLoopBody(elseBlock)) {
                RefactoringResult result = NodeListRefactorer.refactor(elseBlock.elements, context);
                improved |= result.modified;
            }
        }

        return improved;
    }

    /**
     * Refactor a for1 loop node.
     * <p>
     * Loop bodies contain valid control flow (next/last/redo) and should not
     * be refactored at the top level. However, we recursively scan for inner
     * blocks that might be safe to refactor.
     *
     * @param loop    the for1 loop node
     * @param context the refactoring context
     * @return true if any inner block was refactored
     */
    private static boolean refactorFor1Node(For1Node loop, RefactoringContext context) {
        boolean improved = false;

        // Recursively scan loop body for inner blocks, but don't refactor the body itself
        if (loop.body != null) {
            improved |= refactorInnerBlocks(loop.body, context.withLoopContext(true));
        }

        return improved;
    }

    /**
     * Refactor a for3 loop node.
     * <p>
     * Loop bodies contain valid control flow (next/last/redo) and should not
     * be refactored at the top level. However, we recursively scan for inner
     * blocks that might be safe to refactor.
     *
     * @param loop    the for3 loop node
     * @param context the refactoring context
     * @return true if any inner block was refactored
     */
    private static boolean refactorFor3Node(For3Node loop, RefactoringContext context) {
        boolean improved = false;

        // Recursively scan loop body for inner blocks, but don't refactor the body itself
        if (loop.body != null) {
            improved |= refactorInnerBlocks(loop.body, context.withLoopContext(true));
        }

        return improved;
    }

    /**
     * Refactor a try/catch node.
     *
     * @param tryNode the try node to refactor
     * @param context the refactoring context
     * @return true if any block was refactored
     */
    private static boolean refactorTryNode(TryNode tryNode, RefactoringContext context) {
        boolean improved = false;

        // Recursively process blocks first
        if (tryNode.tryBlock != null) {
            improved |= refactorInnerBlocks(tryNode.tryBlock, context);
        }
        if (tryNode.catchBlock != null) {
            improved |= refactorInnerBlocks(tryNode.catchBlock, context);
        }

        // Try to refactor the blocks themselves if they don't have control flow
        if (tryNode.tryBlock instanceof BlockNode tryBlock) {
            if (!ControlFlowValidator.hasUnsafeControlFlow(tryBlock.elements)) {
                RefactoringResult result = NodeListRefactorer.refactor(tryBlock.elements, context);
                improved |= result.modified;
            }
        }
        if (tryNode.catchBlock instanceof BlockNode catchBlock) {
            if (!ControlFlowValidator.hasUnsafeControlFlow(catchBlock.elements)) {
                RefactoringResult result = NodeListRefactorer.refactor(catchBlock.elements, context);
                improved |= result.modified;
            }
        }

        return improved;
    }

    /**
     * Refactor a block node by recursively processing its elements.
     *
     * @param block   the block node to refactor
     * @param context the refactoring context
     * @return true if any inner block was refactored
     */
    private static boolean refactorBlockNode(BlockNode block, RefactoringContext context) {
        boolean improved = false;

        // Don't refactor loop bodies
        if (ControlFlowValidator.isLoopBody(block) || context.isLoopContext) {
            // But still recursively scan for inner blocks
            for (Node element : block.elements) {
                improved |= refactorInnerBlocks(element, context);
            }
            return improved;
        }

        // Recursively process all elements
        for (Node element : block.elements) {
            improved |= refactorInnerBlocks(element, context);
        }

        return improved;
    }

    /**
     * Extract tail-position sequences that don't have variable declarations.
     * <p>
     * This is a fallback strategy that works backwards from the end of a block,
     * extracting sequences that are safe to wrap in closures.
     *
     * @param block   the block to process
     * @param context the refactoring context
     * @return true if extraction was performed
     */
    public static boolean extractTailPosition(BlockNode block, RefactoringContext context) {
        if (block.elements.size() < context.minChunkSize * 2) {
            return false;
        }

        // Skip if already refactored
        if (block.getBooleanAnnotation("blockAlreadyRefactored")) {
            return false;
        }

        // Work backwards to find where we can start extracting
        int extractStartIndex = block.elements.size();
        for (int i = block.elements.size() - 1; i >= 0; i--) {
            Node element = block.elements.get(i);

            // Stop if we hit a declaration
            if (ControlFlowValidator.hasVariableDeclaration(element)) {
                break;
            }

            // Stop if we hit a label
            if (ControlFlowValidator.isLabel(element)) {
                break;
            }
            if (i > 0 && ControlFlowValidator.isLabel(block.elements.get(i - 1))) {
                break;
            }

            // Stop if we hit control flow
            if (ControlFlowValidator.hasUnsafeControlFlow(element)) {
                break;
            }

            extractStartIndex = i;
        }

        // Check if we have enough elements to extract
        int tailSize = block.elements.size() - extractStartIndex;
        if (tailSize < context.minChunkSize) {
            return false;
        }

        // Extract the tail
        List<Node> tailElements = new ArrayList<>(block.elements.subList(extractStartIndex, block.elements.size()));
        block.elements.subList(extractStartIndex, block.elements.size()).clear();

        // Create closure for tail
        BlockNode tailBlock = createMarkedBlock(tailElements, context.tokenIndex);
        Node closure = createClosure(tailBlock, context.tokenIndex);
        block.elements.add(closure);

        return true;
    }

    /**
     * Create a closure: sub { block }->(@_)
     */
    private static Node createClosure(BlockNode block, int tokenIndex) {
        return new BinaryOperatorNode(
                "->",
                new SubroutineNode(null, null, null, block, false, tokenIndex),
                new ListNode(new ArrayList<>(List.of(
                        org.perlonjava.parser.ParserNodeUtils.variableAst("@", "_", tokenIndex))), tokenIndex),
                tokenIndex
        );
    }

    /**
     * Create a marked block that won't be refactored again.
     */
    private static BlockNode createMarkedBlock(List<Node> elements, int tokenIndex) {
        BlockNode block = new BlockNode(elements, tokenIndex);

        // Extract labels
        for (Node element : elements) {
            if (element instanceof LabelNode labelNode) {
                block.labels.add(labelNode.label);
            }
        }

        block.setAnnotation("blockAlreadyRefactored", true);
        return block;
    }
}
