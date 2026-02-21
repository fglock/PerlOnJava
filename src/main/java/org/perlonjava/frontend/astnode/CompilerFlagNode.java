package org.perlonjava.frontend.astnode;

import org.perlonjava.frontend.analysis.Visitor;

/**
 * The CompilerFlagNode class represents a node in the AST for handling
 * compiler flags such as warnings, features, and strict options.
 */
public class CompilerFlagNode extends AbstractNode {
    private final java.util.BitSet warningFlags;
    private final int featureFlags;
    private final int strictOptions;

    /**
     * Constructs a new CompilerFlagNode with the specified flag states.
     *
     * @param warningFlags  the bitmask representing the state of warning flags
     * @param featureFlags  the bitmask representing the state of feature flags
     * @param strictOptions the bitmask representing the state of strict options
     * @param tokenIndex    the index of the token in the source code
     */
    public CompilerFlagNode(java.util.BitSet warningFlags, int featureFlags, int strictOptions, int tokenIndex) {
        this.warningFlags = (java.util.BitSet) warningFlags.clone();
        this.featureFlags = featureFlags;
        this.strictOptions = strictOptions;
        this.tokenIndex = tokenIndex;
    }

    /**
     * Returns the bitmask representing the state of warning flags.
     *
     * @return the warning flags bitmask
     */
    public java.util.BitSet getWarningFlags() {
        return warningFlags;
    }

    /**
     * Returns the bitmask representing the state of feature flags.
     *
     * @return the feature flags bitmask
     */
    public int getFeatureFlags() {
        return featureFlags;
    }

    /**
     * Returns the bitmask representing the state of strict options.
     *
     * @return the strict options bitmask
     */
    public int getStrictOptions() {
        return strictOptions;
    }

    /**
     * @param visitor the visitor that will perform the operation on the node
     */
    @Override
    public void accept(Visitor visitor) {
        visitor.visit(this);
    }
}
