package org.perlonjava.astrefactor;

import org.perlonjava.astnode.*;
import org.perlonjava.parser.Parser;

import java.util.List;

/**
 * Legacy class for refactoring large AST node lists.
 * <p>
 * Proactive refactoring has been replaced with automatic on-demand refactoring
 * in {@link LargeBlockRefactorer}.
 * <p>
 * This class is kept for API compatibility but performs no operations.
 *
 * @see LargeBlockRefactorer
 */
public class LargeNodeRefactorer {

    /**
     * Legacy method that returns elements unchanged.
     * <p>
     * Large code is now handled automatically via on-demand refactoring when
     * compilation errors occur. See {@link LargeBlockRefactorer#forceRefactorForCodegen(BlockNode)}.
     *
     * @param elements   the original elements list from the AST node constructor
     * @param tokenIndex the token index for creating new AST nodes (for error reporting)
     * @param parser     the parser instance (unused, kept for API compatibility)
     * @return the original list unchanged
     */
    public static List<Node> maybeRefactorElements(List<Node> elements, int tokenIndex, Parser parser) {
        // Proactive refactoring is disabled - on-demand refactoring handles large code automatically
        return elements;
    }
}
