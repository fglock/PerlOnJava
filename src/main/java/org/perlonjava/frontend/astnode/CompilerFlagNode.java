package org.perlonjava.frontend.astnode;

import org.perlonjava.frontend.analysis.Visitor;

/**
 * The CompilerFlagNode class represents a node in the AST for handling
 * compiler flags such as warnings, features, and strict options.
 * Also handles runtime warning scope propagation for "no warnings 'category'".
 */
public class CompilerFlagNode extends AbstractNode {
    private final java.util.BitSet warningFlags;
    private final java.util.BitSet warningFatalFlags;
    private final java.util.BitSet warningDisabledFlags;
    private final int featureFlags;
    private final int strictOptions;
    private final int warningScopeId;  // Runtime scope ID for "no warnings" propagation
    private final int hintHashSnapshotId;  // Snapshot ID for compile-time %^H (0 = empty)

    /**
     * Constructs a new CompilerFlagNode with the specified flag states.
     *
     * @param warningFlags  the bitmask representing the state of warning flags
     * @param featureFlags  the bitmask representing the state of feature flags
     * @param strictOptions the bitmask representing the state of strict options
     * @param tokenIndex    the index of the token in the source code
     */
    public CompilerFlagNode(java.util.BitSet warningFlags, int featureFlags, int strictOptions, int tokenIndex) {
        this(warningFlags, null, null, featureFlags, strictOptions, 0, 0, tokenIndex);
    }

    /**
     * Constructs a new CompilerFlagNode with the specified flag states and warning scope ID.
     *
     * @param warningFlags    the bitmask representing the state of warning flags
     * @param featureFlags    the bitmask representing the state of feature flags
     * @param strictOptions   the bitmask representing the state of strict options
     * @param warningScopeId  the runtime warning scope ID (0 if not applicable)
     * @param tokenIndex      the index of the token in the source code
     */
    public CompilerFlagNode(java.util.BitSet warningFlags, int featureFlags, int strictOptions, int warningScopeId, int tokenIndex) {
        this(warningFlags, null, null, featureFlags, strictOptions, warningScopeId, 0, tokenIndex);
    }

    /**
     * Constructs a new CompilerFlagNode with all flag states including fatal and disabled warnings.
     *
     * @param warningFlags          the bitmask representing the state of warning flags
     * @param warningFatalFlags     the bitmask representing FATAL warning flags (may be null)
     * @param warningDisabledFlags  the bitmask representing disabled warning flags (may be null)
     * @param featureFlags          the bitmask representing the state of feature flags
     * @param strictOptions         the bitmask representing the state of strict options
     * @param warningScopeId        the runtime warning scope ID (0 if not applicable)
     * @param hintHashSnapshotId    the snapshot ID for compile-time %^H (0 = empty)
     * @param tokenIndex            the index of the token in the source code
     */
    public CompilerFlagNode(java.util.BitSet warningFlags, java.util.BitSet warningFatalFlags, 
                           java.util.BitSet warningDisabledFlags, int featureFlags, int strictOptions, 
                           int warningScopeId, int hintHashSnapshotId, int tokenIndex) {
        this.warningFlags = (java.util.BitSet) warningFlags.clone();
        this.warningFatalFlags = warningFatalFlags != null ? (java.util.BitSet) warningFatalFlags.clone() : new java.util.BitSet();
        this.warningDisabledFlags = warningDisabledFlags != null ? (java.util.BitSet) warningDisabledFlags.clone() : new java.util.BitSet();
        this.featureFlags = featureFlags;
        this.strictOptions = strictOptions;
        this.warningScopeId = warningScopeId;
        this.hintHashSnapshotId = hintHashSnapshotId;
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
     * Returns the bitmask representing FATAL warning flags.
     *
     * @return the FATAL warning flags bitmask
     */
    public java.util.BitSet getWarningFatalFlags() {
        return warningFatalFlags;
    }

    /**
     * Returns the bitmask representing disabled warning flags.
     *
     * @return the disabled warning flags bitmask
     */
    public java.util.BitSet getWarningDisabledFlags() {
        return warningDisabledFlags;
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
     * Returns the runtime warning scope ID for "no warnings" propagation.
     * Used to emit "local ${^WARNING_SCOPE} = scopeId" for warnif() checking.
     *
     * @return the warning scope ID, or 0 if not applicable
     */
    public int getWarningScopeId() {
        return warningScopeId;
    }

    /**
     * Returns the snapshot ID for compile-time %^H.
     * Used to emit HintHashRegistry.setCallSiteHintHashId() for caller()[10] support.
     *
     * @return the hint hash snapshot ID, or 0 if %^H was empty
     */
    public int getHintHashSnapshotId() {
        return hintHashSnapshotId;
    }

    /**
     * @param visitor the visitor that will perform the operation on the node
     */
    @Override
    public void accept(Visitor visitor) {
        visitor.visit(this);
    }
}
