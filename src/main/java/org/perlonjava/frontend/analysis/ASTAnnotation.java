package org.perlonjava.frontend.analysis;

import org.perlonjava.frontend.astnode.Node;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.util.List;
import java.util.Set;

/**
 * Stores semantic annotations computed by the shared AST transformer.
 * These annotations are computed once and used by both the JVM backend
 * and the bytecode interpreter to ensure parity.
 *
 * <p>The transformer populates these fields during AST analysis passes.
 * Backends read these fields instead of computing semantics independently.</p>
 */
public class ASTAnnotation {

    // Context resolution (Phase 4: ContextResolver)
    /** The context this node executes in: SCALAR, LIST, VOID, or RUNTIME */
    public int context = RuntimeContextType.RUNTIME;

    /** Context to pass to subroutine calls (for wantarray) */
    public int callContext = RuntimeContextType.RUNTIME;

    // Lvalue resolution (Phase 5: LvalueResolver)
    /** True if this node must return a mutable reference (lvalue) */
    public boolean isLvalue;

    /** True if this node may create container elements via autovivification */
    public boolean needsAutovivification;

    // Operator/function argument contexts (Phase 4)
    /** Per-argument context requirements for operators/functions */
    public ArgumentContexts argContexts;

    // Variable resolution (Phase 2: VariableResolver)
    /** Links variable use to its declaration */
    public VariableBinding binding;

    /** True if this variable is used by an inner closure */
    public boolean isCaptured;

    /** Nesting level for closure captures (0 = same scope) */
    public int closureDepth;

    /** For subroutines: list of captured variables from outer scopes */
    public List<VariableBinding> capturedVariables;

    // Block analysis (Phase 3.5: BlockAnalyzer)
    /** True if block contains 'local' declarations (needs save/restore) */
    public boolean containsLocal;

    /** Details of each 'local' declaration in this block */
    public List<LocalDeclaration> localDeclarations;

    /** True if block uses regex operations (needs RegexState save/restore) */
    public boolean containsRegex;

    // Pragma tracking (Phase 1: PragmaResolver)
    /** Pragma state (strict, warnings, features) at this node */
    public PragmaState pragmas;

    // Label resolution (Phase 3: LabelCollector)
    /** Target information for goto/next/last/redo */
    public LabelInfo labelTarget;

    // Constant folding (Phase 6: ConstantFolder)
    /** True if this node can be evaluated at compile time */
    public boolean isConstant;

    /** The folded constant value, if isConstant is true */
    public RuntimeScalar constantValue;

    // Control flow analysis (optional, for optimization)
    /** Cached: contains next/last/redo/goto */
    public Boolean hasAnyControlFlow;

    /** Cached: control flow escapes this block */
    public Boolean hasUnsafeControlFlow;

    /**
     * Per-argument context requirements for operators and functions.
     * For example, push(@array, LIST) requires SCALAR context for the
     * first argument and LIST context for remaining arguments.
     */
    public static class ArgumentContexts {
        /** Context for each argument position (RuntimeContextType values) */
        public int[] contexts;

        /** If true, last context applies to all remaining arguments */
        public boolean lastArgTakesRemainder;

        public ArgumentContexts(int[] contexts, boolean lastArgTakesRemainder) {
            this.contexts = contexts;
            this.lastArgTakesRemainder = lastArgTakesRemainder;
        }

        public ArgumentContexts(int[] contexts) {
            this(contexts, false);
        }
    }

    /**
     * Links a variable use to its declaration.
     */
    public static class VariableBinding {
        /** Variable name with sigil (e.g., "$x", "@arr", "%hash") */
        public String name;

        /** Unique ID for this declaration (within compilation unit) */
        public int declarationId;

        /** The AST node where this variable was declared */
        public Node declarationNode;

        /** Scope type: LEXICAL (my/state), PACKAGE (our), or DYNAMIC (local) */
        public ScopeType scopeType;

        /** True if this is a 'state' variable */
        public boolean isState;

        public enum ScopeType {
            LEXICAL,   // my, state
            PACKAGE,   // our
            DYNAMIC    // local
        }
    }

    /**
     * Tracks pragma state (strict, warnings, features) at a point in the AST.
     */
    public static class PragmaState {
        public boolean strictVars;
        public boolean strictRefs;
        public boolean strictSubs;
        public Set<String> enabledWarnings;
        public Set<String> disabledWarnings;
        public Set<String> enabledFeatures;  // say, fc, signatures, etc.

        public PragmaState copy() {
            PragmaState copy = new PragmaState();
            copy.strictVars = this.strictVars;
            copy.strictRefs = this.strictRefs;
            copy.strictSubs = this.strictSubs;
            if (this.enabledWarnings != null) {
                copy.enabledWarnings = new java.util.HashSet<>(this.enabledWarnings);
            }
            if (this.disabledWarnings != null) {
                copy.disabledWarnings = new java.util.HashSet<>(this.disabledWarnings);
            }
            if (this.enabledFeatures != null) {
                copy.enabledFeatures = new java.util.HashSet<>(this.enabledFeatures);
            }
            return copy;
        }
    }

    /**
     * Information about a 'local' declaration for save/restore.
     */
    public static class LocalDeclaration {
        /** Variable name with sigil (e.g., "$foo", "@bar", "%baz") */
        public String variableName;

        /** Links to the package/global variable */
        public VariableBinding binding;

        /** The 'local' operator node */
        public Node declarationNode;

        /** True if value must be restored at scope end */
        public boolean needsRestore;
    }

    /**
     * Information about a label target for control flow.
     */
    public static class LabelInfo {
        /** Label name (null for implicit innermost loop) */
        public String labelName;

        /** The target loop or block node */
        public Node targetNode;

        /** True if target is a loop (for next/last/redo) */
        public boolean isLoopLabel;

        public LabelInfo(String labelName, Node targetNode) {
            this.labelName = labelName;
            this.targetNode = targetNode;
        }

        public LabelInfo(String labelName, Node targetNode, boolean isLoopLabel) {
            this.labelName = labelName;
            this.targetNode = targetNode;
            this.isLoopLabel = isLoopLabel;
        }
    }
}
