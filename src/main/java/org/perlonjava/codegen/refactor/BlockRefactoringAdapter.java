package org.perlonjava.codegen.refactor;

import org.perlonjava.astnode.BlockNode;
import org.perlonjava.parser.Parser;

/**
 * Adapter class for refactoring BlockNode elements using the unified refactoring framework.
 * <p>
 * This class provides a bridge between the old {@code LargeBlockRefactorer} API and the
 * new unified {@link NodeListRefactorer}. It maintains backward compatibility while
 * leveraging the improved refactoring strategies.
 * <p>
 * <b>Usage:</b>
 * <pre>
 * BlockRefactoringAdapter.maybeRefactorBlock(blockNode, parser);
 * </pre>
 *
 * @see NodeListRefactorer
 * @see BlockNode
 */
public class BlockRefactoringAdapter {

    /**
     * Thread-local flag to prevent recursion when creating chunk blocks.
     * This is shared with NodeListRefactorer to coordinate recursion prevention.
     */
    private static final ThreadLocal<Boolean> skipRefactoring = ThreadLocal.withInitial(() -> false);

    /**
     * Set the skip refactoring flag (used by NodeListRefactorer).
     */
    static void setSkipRefactoring(boolean value) {
        skipRefactoring.set(value);
    }

    /**
     * Parse-time entry point: refactor a block's elements if needed.
     * <p>
     * This method is called from BlockNode constructors to automatically refactor
     * large blocks during parsing. It applies smart chunking and recursive strategies
     * to reduce block size while preserving semantics.
     *
     * @param node   the block to potentially refactor (modified in place)
     * @param parser the parser instance for error reporting (can be null)
     */
    public static void maybeRefactorBlock(BlockNode node, Parser parser) {
        if (!RefactoringContext.isRefactoringEnabled()) {
            return;
        }

        // Skip if we're inside createMarkedBlock (prevents recursion)
        if (skipRefactoring.get()) {
            return;
        }

        // Skip if already refactored
        if (node.getBooleanAnnotation("blockAlreadyRefactored")) {
            return;
        }

        // Skip special blocks (BEGIN, END, etc.)
        if (isSpecialContext(node)) {
            return;
        }

        // Create context for block refactoring
        RefactoringContext context = new RefactoringContext(
                50,    // minElementCount
                40000, // maxBytecodeSize
                2,     // minChunkSize
                200,   // maxChunkSize
                node.tokenIndex,
                parser,
                node.isLoop
        );

        // Refactor the block's elements
        RefactoringResult result = NodeListRefactorer.refactor(node.elements, context);

        // Mark as refactored if successful
        if (result.success && result.modified) {
            node.setAnnotation("blockAlreadyRefactored", true);
        }

        // Throw exception if still too large
        if (!result.success) {
            NodeListRefactorer.throwIfTooLarge(result, context);
        }
    }

    /**
     * Check if the block is in a special context where refactoring should be avoided.
     * <p>
     * Special blocks include BEGIN, END, INIT, CHECK, UNITCHECK, and require blocks.
     * These have special compilation semantics and cannot be safely refactored.
     *
     * @param node the block to check
     * @return true if the block is in a special context
     */
    private static boolean isSpecialContext(BlockNode node) {
        return node.getBooleanAnnotation("blockIsSpecial") ||
                node.getBooleanAnnotation("blockIsBegin") ||
                node.getBooleanAnnotation("blockIsRequire") ||
                node.getBooleanAnnotation("blockIsInit") ||
                node.getBooleanAnnotation("blockIsSubroutine");
    }
}
