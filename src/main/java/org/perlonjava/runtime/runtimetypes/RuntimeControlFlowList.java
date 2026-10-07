package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.runtime.perlmodule.Warnings;

/**
 * A specialized RuntimeList that carries control flow information.
 * This is returned by control flow statements (last/next/redo/goto/goto &NAME)
 * to signal non-local control flow across subroutine boundaries.
 */
public class RuntimeControlFlowList extends RuntimeList {
    /** Internal control-block label for a when clause's switch-only continue target. */
    public static final String SWITCH_CONTINUE_BLOCK_LABEL = "\u0001switch-continue-target";
    // Debug flag - set to true to enable detailed tracing
    private static final boolean DEBUG_TAILCALL = false;

    /**
     * The control flow marker with type and label/codeRef information
     */
    public final ControlFlowMarker marker;

    /**
     * The return value for RETURN type (non-local return from map/grep block).
     * Null for all other control flow types.
     */
    public final RuntimeBase returnValue;
    /** True when this loop-control marker escaped a class ADJUST block. */
    private boolean classAdjustOrigin;
    /**
     * A localized false {@code $^W} suppresses the warning that Perl would
     * otherwise issue when this marker crosses a subroutine boundary.  Capture
     * it while the marker is created: the {@code local} scope is unwound before
     * the caller dispatches the marker.
     */
    private final boolean suppressEscapingLoopControlWarning;

    /**
     * Constructor for control flow (last/next/redo/goto).
     *
     * @param type       The control flow type
     * @param label      The label to jump to (null for unlabeled)
     * @param fileName   Source file name (for error messages)
     * @param lineNumber Line number (for error messages)
     */
    public RuntimeControlFlowList(ControlFlowType type, String label, String fileName, int lineNumber) {
        this(type, label, fileName, lineNumber, null);
    }

    /** Constructor for label control flow which carries its eval origin. */
    public RuntimeControlFlowList(ControlFlowType type, String label, String fileName, int lineNumber,
                                  String evalScope) {
        this(type, label, fileName, lineNumber, evalScope, null);
    }

    /** Constructor retaining a switch-only source spelling after NEXT/LAST normalization. */
    public RuntimeControlFlowList(ControlFlowType type, String label, String fileName, int lineNumber,
                                  String evalScope, String switchControlOperator) {
        super();
        this.marker = new ControlFlowMarker(type, label, fileName, lineNumber, evalScope, switchControlOperator);
        this.returnValue = null;
        this.suppressEscapingLoopControlWarning = Warnings.isWarnFlagLocalized()
                && !Warnings.isWarnFlagSet();
        if (DEBUG_TAILCALL) {
            System.err.println("[DEBUG-0a] RuntimeControlFlowList constructor (type,label): type=" + type +
                    ", label=" + label + " @ " + fileName + ":" + lineNumber);
        }
    }

    /**
     * Constructor for tail call (goto &NAME).
     *
     * @param codeRef    The code reference to call
     * @param args       The arguments to pass
     * @param fileName   Source file name (for error messages)
     * @param lineNumber Line number (for error messages)
     */
    public RuntimeControlFlowList(RuntimeScalar codeRef, RuntimeArray args, String fileName, int lineNumber) {
        this(codeRef, args, fileName, lineNumber, null);
    }

    /**
     * Constructor for tail call (goto &NAME) with eval scope check.
     * Perl 5 checks for undefined subroutine FIRST, then checks eval context.
     *
     * @param codeRef    The code reference to call
     * @param args       The arguments to pass
     * @param fileName   Source file name (for error messages)
     * @param lineNumber Line number (for error messages)
     * @param evalScope  The eval scope type ("eval-block", "eval-string", or null if not in eval)
     */
    public RuntimeControlFlowList(RuntimeScalar codeRef, RuntimeArray args, String fileName, int lineNumber, String evalScope) {
        this(codeRef, args, fileName, lineNumber, evalScope, null);
    }

    public RuntimeControlFlowList(RuntimeScalar codeRef, RuntimeArray args, String fileName, int lineNumber,
                                  String evalScope, String namedTarget) {
        super();
        if (RuntimeCode.isInSortComparator()) {
            throw new PerlCompilerException(RuntimeCode.isCurrentSortComparatorBlock()
                    ? "Can't goto subroutine outside a subroutine"
                    : "Can't goto subroutine from a sort sub");
        }
        this.marker = new ControlFlowMarker(retainTailCallCodeRef(codeRef), args, fileName, lineNumber,
                namedTarget, evalScope);
        this.returnValue = null;
        this.suppressEscapingLoopControlWarning = false;
        if (DEBUG_TAILCALL) {
            System.err.println("[DEBUG-0b] RuntimeControlFlowList constructor (codeRef,args): codeRef=" + codeRef +
                    ", args.size=" + (args != null ? args.size() : "null") +
                    " @ " + fileName + ":" + lineNumber +
                    " marker.type=" + marker.type);
        }
    }

    private static RuntimeScalar retainTailCallCodeRef(RuntimeScalar codeRef) {
        RuntimeScalar retained = new RuntimeScalar(codeRef);
        RuntimeScalar.incrementRefCountForContainerStore(retained);
        return retained;
    }

    /**
     * Constructor for non-local return from map/grep block.
     * The return value is carried so it can be used as the enclosing subroutine's return value.
     *
     * @param returnValue The value being returned
     * @param fileName    Source file name (for error messages)
     * @param lineNumber  Line number (for error messages)
     */
    public RuntimeControlFlowList(RuntimeBase returnValue, String fileName, int lineNumber) {
        super();
        this.marker = new ControlFlowMarker(ControlFlowType.RETURN, null, fileName, lineNumber);
        this.returnValue = returnValue;
        this.suppressEscapingLoopControlWarning = false;
    }

    /**
     * Get the return value (for RETURN type only).
     *
     * @return The return value, or null if not a RETURN type
     */
    public RuntimeBase getReturnValue() {
        return returnValue;
    }

    public void markClassAdjustOrigin() {
        classAdjustOrigin = true;
    }

    public boolean hasClassAdjustOrigin() {
        return classAdjustOrigin;
    }

    public boolean suppressEscapingLoopControlWarning() {
        return suppressEscapingLoopControlWarning;
    }

    /** Switch controls cannot escape an eval or subroutine as ordinary loop controls can. */
    public boolean isSwitchControl() {
        return marker.switchControlOperator != null;
    }

    /** True when a CORE::continue marker must resume after its current when clause. */
    public boolean isSwitchContinue() {
        return "continue".equals(marker.switchControlOperator);
    }

    /**
     * Create a RuntimeControlFlowList from a registry action code.
     * Used by emitControlFlowCheck to convert registry action to marked list.
     *
     * @param action The action code (1=LAST, 2=NEXT, 3=REDO)
     * @param label  The loop label (or null)
     * @return A marked RuntimeControlFlowList
     */
    public static RuntimeControlFlowList createFromAction(int action, String label) {
        ControlFlowType type;
        switch (action) {
            case 1:
                type = ControlFlowType.LAST;
                break;
            case 2:
                type = ControlFlowType.NEXT;
                break;
            case 3:
                type = ControlFlowType.REDO;
                break;
            default:
                throw new IllegalArgumentException("Invalid action code: " + action);
        }
        return new RuntimeControlFlowList(type, label, "(registry)", 0);
    }

    /**
     * Get the control flow type.
     *
     * @return The control flow type
     */
    public ControlFlowType getControlFlowType() {
        if (DEBUG_TAILCALL) {
            System.err.println("[DEBUG-2] getControlFlowType() called, returning: " + marker.type);
        }
        return marker.type;
    }

    /**
     * Get the control flow label.
     *
     * @return The label, or null if unlabeled
     */
    public String getControlFlowLabel() {
        return marker.label;
    }

    /**
     * Check if this control flow matches the given loop label.
     * Perl semantics:
     * - If control flow is unlabeled (null), it matches any loop
     * - If control flow is labeled and loop is unlabeled (null), no match
     * - If both are labeled, they must match exactly
     *
     * @param loopLabel The loop label to check against (null for unlabeled loop)
     * @return true if this control flow targets the given loop
     */
    public boolean matchesLabel(String loopLabel) {
        String controlFlowLabel = marker.label;

        // Unlabeled control flow (null) matches any loop
        if (controlFlowLabel == null) {
            return true;
        }

        // Labeled control flow - check if it matches the loop label
        return controlFlowLabel.equals(loopLabel);
    }

    /**
     * Get the tail call code reference.
     *
     * @return The code reference, or null if not a tail call
     */
    public RuntimeScalar getTailCallCodeRef() {
        if (DEBUG_TAILCALL) {
            System.err.println("[DEBUG-3] getTailCallCodeRef() called, returning: " + marker.codeRef);
        }
        return marker.codeRef;
    }

    /**
     * Get the tail call arguments.
     *
     * @return The arguments, or null if not a tail call
     */
    public RuntimeArray getTailCallArgs() {
        if (DEBUG_TAILCALL) {
            System.err.println("[DEBUG-4] getTailCallArgs() called, returning: " +
                    (marker.args != null ? marker.args.size() + " args" : "null"));
        }
        return marker.args;
    }

    /**
     * Debug method - print this control flow list's details.
     * Can be called from generated bytecode or Java code.
     */
    public RuntimeControlFlowList debugTrace(String context) {
        System.err.println("[TRACE] " + context + ": " + this);
        if (marker != null) {
            marker.debugPrint(context);
        }
        return this;  // Return self for chaining
    }
}
