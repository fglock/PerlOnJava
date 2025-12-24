package org.perlonjava.codegen.refactor;

import org.perlonjava.astnode.Node;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of a refactoring operation.
 * <p>
 * Contains the refactored node list and metadata about the refactoring process.
 */
public class RefactoringResult {

    /**
     * The refactored list of nodes.
     */
    public final List<Node> nodes;

    /**
     * Whether refactoring was successful.
     */
    public final boolean success;

    /**
     * Whether any changes were made.
     */
    public final boolean modified;

    /**
     * Original element count before refactoring.
     */
    public final int originalCount;

    /**
     * Final element count after refactoring.
     */
    public final int finalCount;

    /**
     * Estimated bytecode size before refactoring.
     */
    public final long originalBytecodeSize;

    /**
     * Estimated bytecode size after refactoring.
     */
    public final long finalBytecodeSize;

    /**
     * Reason for failure (if not successful).
     */
    public final String failureReason;

    /**
     * Strategies that were attempted.
     */
    public final List<String> attemptedStrategies;

    private RefactoringResult(List<Node> nodes, boolean success, boolean modified,
                              int originalCount, int finalCount,
                              long originalBytecodeSize, long finalBytecodeSize,
                              String failureReason, List<String> attemptedStrategies) {
        this.nodes = nodes;
        this.success = success;
        this.modified = modified;
        this.originalCount = originalCount;
        this.finalCount = finalCount;
        this.originalBytecodeSize = originalBytecodeSize;
        this.finalBytecodeSize = finalBytecodeSize;
        this.failureReason = failureReason;
        this.attemptedStrategies = attemptedStrategies;
    }

    /**
     * Create a successful refactoring result.
     */
    public static RefactoringResult success(List<Node> nodes, int originalCount, int finalCount,
                                            long originalBytecodeSize, long finalBytecodeSize,
                                            List<String> attemptedStrategies) {
        boolean modified = finalCount != originalCount || finalBytecodeSize != originalBytecodeSize;
        return new RefactoringResult(nodes, true, modified, originalCount, finalCount,
                originalBytecodeSize, finalBytecodeSize, null, attemptedStrategies);
    }

    /**
     * Create a no-change result (refactoring not needed).
     */
    public static RefactoringResult noChange(List<Node> nodes, int count, long bytecodeSize) {
        return new RefactoringResult(nodes, true, false, count, count,
                bytecodeSize, bytecodeSize, null, new ArrayList<>());
    }

    /**
     * Create a failure result.
     */
    public static RefactoringResult failure(List<Node> nodes, int originalCount,
                                            long originalBytecodeSize, String reason,
                                            List<String> attemptedStrategies) {
        return new RefactoringResult(nodes, false, false, originalCount, originalCount,
                originalBytecodeSize, originalBytecodeSize, reason, attemptedStrategies);
    }

    /**
     * Get a summary of the refactoring result.
     */
    public String getSummary() {
        if (!success) {
            return "Refactoring failed: " + failureReason;
        }
        if (!modified) {
            return "No refactoring needed";
        }
        return String.format("Refactored: %d -> %d elements, %d -> %d bytes",
                originalCount, finalCount, originalBytecodeSize, finalBytecodeSize);
    }
}
