package org.perlonjava.codegen.refactor;

import org.perlonjava.parser.Parser;

/**
 * Context information for refactoring operations.
 * <p>
 * Provides configuration and utilities needed during refactoring,
 * including size thresholds, token indices, and parser access.
 */
public class RefactoringContext {

    /**
     * Minimum number of elements before considering refactoring.
     */
    public final int minElementCount;

    /**
     * Target maximum bytecode size (in bytes).
     */
    public final int maxBytecodeSize;

    /**
     * Minimum elements per chunk.
     */
    public final int minChunkSize;

    /**
     * Maximum elements per chunk.
     */
    public final int maxChunkSize;

    /**
     * Token index for creating new AST nodes.
     */
    public final int tokenIndex;

    /**
     * Parser instance for error reporting (may be null).
     */
    public final Parser parser;

    /**
     * Whether this is a loop body context.
     */
    public final boolean isLoopContext;

    /**
     * Create a refactoring context with default values.
     *
     * @param tokenIndex token index for new nodes
     * @param parser     parser instance (may be null)
     */
    public RefactoringContext(int tokenIndex, Parser parser) {
        this(50, 40000, 2, 200, tokenIndex, parser, false);
    }

    /**
     * Create a refactoring context with custom values.
     *
     * @param minElementCount minimum elements before refactoring
     * @param maxBytecodeSize maximum bytecode size threshold
     * @param minChunkSize    minimum chunk size
     * @param maxChunkSize    maximum chunk size
     * @param tokenIndex      token index for new nodes
     * @param parser          parser instance (may be null)
     * @param isLoopContext   whether this is a loop body
     */
    public RefactoringContext(int minElementCount, int maxBytecodeSize, int minChunkSize,
                              int maxChunkSize, int tokenIndex, Parser parser, boolean isLoopContext) {
        this.minElementCount = minElementCount;
        this.maxBytecodeSize = maxBytecodeSize;
        this.minChunkSize = minChunkSize;
        this.maxChunkSize = maxChunkSize;
        this.tokenIndex = tokenIndex;
        this.parser = parser;
        this.isLoopContext = isLoopContext;
    }

    /**
     * Create a new context with loop flag set.
     *
     * @param isLoop whether this is a loop context
     * @return new context with updated loop flag
     */
    public RefactoringContext withLoopContext(boolean isLoop) {
        return new RefactoringContext(minElementCount, maxBytecodeSize, minChunkSize,
                maxChunkSize, tokenIndex, parser, isLoop);
    }

    /**
     * Check if refactoring is enabled via environment variable.
     *
     * @return true if JPERL_LARGECODE=refactor is set
     */
    public static boolean isRefactoringEnabled() {
        String largeCodeMode = System.getenv("JPERL_LARGECODE");
        return "refactor".equals(largeCodeMode);
    }
}
