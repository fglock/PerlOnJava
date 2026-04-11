package org.perlonjava.backend.bytecode;

import org.perlonjava.runtime.WarningBitsRegistry;
import org.perlonjava.runtime.runtimetypes.*;

import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Interpreted bytecode that extends RuntimeCode.
 * <p>
 * This class represents Perl code that is interpreted rather than compiled to JVM bytecode.
 * It is COMPLETELY INDISTINGUISHABLE from compiled RuntimeCode to the rest of the system:
 * - Can be stored in global variables ($::func)
 * - Can be passed as code references
 * - Can capture variables (closures work both directions)
 * - Can be used in method dispatch, overload, @ISA, etc.
 * <p>
 * The ONLY difference is the execution engine:
 * - Compiled RuntimeCode uses MethodHandle to invoke JVM bytecode
 * - InterpretedCode overrides apply() to dispatch to BytecodeInterpreter
 */
public class InterpretedCode extends RuntimeCode implements PerlSubroutine {
    // Bytecode and metadata
    public final int[] bytecode;           // Instruction stream (opcodes + operands as ints)
    public final Object[] constants;       // Constant pool (RuntimeBase objects)
    public final String[] stringPool;      // String constants (variable names, etc.)
    public final int maxRegisters;         // Number of registers needed
    public final RuntimeBase[] capturedVars; // Closure support (captured from outer scope)
    public final Map<String, Integer> variableRegistry; // Variable name → register index (for eval STRING)
    public final List<Map<String, Integer>> evalSiteRegistries; // Per-eval-site variable registries
    public final List<int[]> evalSitePragmaFlags; // Per-eval-site [strictOptions, featureFlags]

    // Optimization flags (set by compiler after construction)
    // If false, we can skip DynamicVariableManager.getLocalLevel/popToLocalLevel calls
    public boolean usesLocalization = true;

    // Goto label map (set by compiler after construction for dynamic goto support)
    // Maps label name → bytecode PC offset
    public Map<String, Integer> gotoLabelPcs;

    // Pre-created InterpreterFrame to avoid allocation on every call
    // Created lazily on first use (after packageName/subName are set)
    public volatile InterpreterState.InterpreterFrame cachedFrame;

    // Cached register array for non-recursive calls (avoids allocation)
    // Thread-safe via ThreadLocal for multi-threaded execution
    private final ThreadLocal<RuntimeBase[]> cachedRegisters = new ThreadLocal<>();
    // Flag to track if cached registers are currently in use (for recursion detection)
    private final ThreadLocal<Boolean> registersInUse = ThreadLocal.withInitial(() -> false);

    /**
     * Get a register array for execution. Returns cached array if not in use (common case),
     * otherwise allocates a new one (recursive call).
     */
    public RuntimeBase[] getRegisters() {
        // Disable register pooling for now - it causes subtle bugs with stale values
        // TODO: Investigate why Arrays.fill(regs, null) doesn't fully fix the issue
        return new RuntimeBase[maxRegisters];
    }

    /**
     * Release the register array after execution completes.
     */
    public void releaseRegisters() {
        registersInUse.set(false);
    }

    // Lexical pragma state (for eval STRING to inherit)
    public final int strictOptions;        // Strict flags at compile time
    public final int featureFlags;         // Feature flags at compile time
    public final BitSet warningFlags;      // Warning flags at compile time
    public final String warningBitsString; // Perl 5 compatible warning bits string (for caller()[9])
    public final String compilePackage;    // Package at compile time (for eval STRING name resolution)

    // Debug information (optional)
    public final String sourceName;        // Source file name (for stack traces)
    public final int sourceLine;           // Source line number
    public final TreeMap<Integer, Integer> pcToTokenIndex;  // Map bytecode PC to tokenIndex for error reporting (TreeMap for floorEntry lookup)
    public final ErrorMessageUtil errorUtil; // For converting token index to line numbers

    /**
     * Constructor for InterpretedCode.
     *
     * @param bytecode         The bytecode instructions
     * @param constants        Constant pool (RuntimeBase objects)
     * @param stringPool       String constants (variable names, etc.)
     * @param maxRegisters     Number of registers needed for execution
     * @param capturedVars     Captured variables for closure support (may be null)
     * @param sourceName       Source file name for debugging
     * @param sourceLine       Source line number for debugging
     * @param pcToTokenIndex   Map from bytecode PC to AST tokenIndex for error reporting
     * @param variableRegistry Variable name → register index mapping (for eval STRING)
     * @param errorUtil        Error message utility for line number lookup
     * @param strictOptions    Strict flags at compile time (for eval STRING inheritance)
     * @param featureFlags     Feature flags at compile time (for eval STRING inheritance)
     * @param warningFlags     Warning flags at compile time (for eval STRING inheritance)
     */
    public InterpretedCode(int[] bytecode, Object[] constants, String[] stringPool,
                           int maxRegisters, RuntimeBase[] capturedVars,
                           String sourceName, int sourceLine,
                           TreeMap<Integer, Integer> pcToTokenIndex,
                           Map<String, Integer> variableRegistry,
                           ErrorMessageUtil errorUtil,
                           int strictOptions, int featureFlags, BitSet warningFlags) {
        this(bytecode, constants, stringPool, maxRegisters, capturedVars,
                sourceName, sourceLine, pcToTokenIndex, variableRegistry, errorUtil,
                strictOptions, featureFlags, warningFlags, "main", null, null, null);
    }

    public InterpretedCode(int[] bytecode, Object[] constants, String[] stringPool,
                           int maxRegisters, RuntimeBase[] capturedVars,
                           String sourceName, int sourceLine,
                           TreeMap<Integer, Integer> pcToTokenIndex,
                           Map<String, Integer> variableRegistry,
                           ErrorMessageUtil errorUtil,
                           int strictOptions, int featureFlags, BitSet warningFlags,
                           String compilePackage) {
        this(bytecode, constants, stringPool, maxRegisters, capturedVars,
                sourceName, sourceLine, pcToTokenIndex, variableRegistry, errorUtil,
                strictOptions, featureFlags, warningFlags, compilePackage, null, null, null);
    }

    public InterpretedCode(int[] bytecode, Object[] constants, String[] stringPool,
                           int maxRegisters, RuntimeBase[] capturedVars,
                           String sourceName, int sourceLine,
                           TreeMap<Integer, Integer> pcToTokenIndex,
                           Map<String, Integer> variableRegistry,
                           ErrorMessageUtil errorUtil,
                           int strictOptions, int featureFlags, BitSet warningFlags,
                           String compilePackage,
                           List<Map<String, Integer>> evalSiteRegistries,
                           List<int[]> evalSitePragmaFlags,
                           String warningBitsString) {
        super(null, new java.util.ArrayList<>());
        this.bytecode = bytecode;
        this.constants = constants;
        this.stringPool = stringPool;
        this.maxRegisters = maxRegisters;
        this.capturedVars = capturedVars;
        this.sourceName = sourceName;
        this.sourceLine = sourceLine;
        this.pcToTokenIndex = pcToTokenIndex;
        this.variableRegistry = variableRegistry;
        this.evalSiteRegistries = evalSiteRegistries;
        this.evalSitePragmaFlags = evalSitePragmaFlags;
        this.errorUtil = errorUtil;
        this.strictOptions = strictOptions;
        this.featureFlags = featureFlags;
        this.warningFlags = warningFlags;
        this.warningBitsString = warningBitsString;
        this.compilePackage = compilePackage;
        if (this.packageName == null && compilePackage != null) {
            this.packageName = compilePackage;
        }
        // Register with WarningBitsRegistry for caller()[9] support
        if (warningBitsString != null) {
            String registryKey = "interpreter:" + System.identityHashCode(this);
            WarningBitsRegistry.register(registryKey, warningBitsString);
        }
    }

    // Legacy constructor for backward compatibility
    public InterpretedCode(int[] bytecode, Object[] constants, String[] stringPool,
                           int maxRegisters, RuntimeBase[] capturedVars,
                           String sourceName, int sourceLine,
                           java.util.Map<Integer, Integer> pcToTokenIndex) {
        this(bytecode, constants, stringPool, maxRegisters, capturedVars,
                sourceName, sourceLine,
                pcToTokenIndex instanceof TreeMap ? (TreeMap<Integer, Integer>) pcToTokenIndex : new TreeMap<>(pcToTokenIndex),
                null, null, 0, 0, new BitSet());
    }

    // Legacy constructor with variableRegistry but no errorUtil
    public InterpretedCode(int[] bytecode, Object[] constants, String[] stringPool,
                           int maxRegisters, RuntimeBase[] capturedVars,
                           String sourceName, int sourceLine,
                           java.util.Map<Integer, Integer> pcToTokenIndex,
                           Map<String, Integer> variableRegistry) {
        this(bytecode, constants, stringPool, maxRegisters, capturedVars,
                sourceName, sourceLine,
                pcToTokenIndex instanceof TreeMap ? (TreeMap<Integer, Integer>) pcToTokenIndex : new TreeMap<>(pcToTokenIndex),
                variableRegistry, null, 0, 0, new BitSet());
    }

    /**
     * Read a 32-bit integer from bytecode (stored as 1 int slot).
     * With int[] storage a full int fits in a single slot.
     */
    static int readInt(int[] bytecode, int pc) {
        return bytecode[pc];
    }

    /**
     * Get or create the cached InterpreterFrame for this code.
     * Uses double-checked locking for thread safety with minimal overhead.
     *
     * @param packageName    The package name (usually from this.packageName)
     * @param subroutineName The subroutine name (usually from this.subName)
     * @return The cached frame if names match, or a new frame if they don't
     */
    public InterpreterState.InterpreterFrame getOrCreateFrame(String packageName, String subroutineName) {
        InterpreterState.InterpreterFrame frame = cachedFrame;
        if (frame != null && frame.packageName().equals(packageName) && 
            java.util.Objects.equals(frame.subroutineName(), subroutineName)) {
            return frame;
        }
        // Create new frame (either first time, or names don't match)
        frame = new InterpreterState.InterpreterFrame(this, packageName, subroutineName);
        // Cache it if this is the "normal" case (using code's own names)
        String defaultPkg = this.packageName != null ? this.packageName : "main";
        String defaultSub = this.subName != null ? this.subName : "(eval)";
        if (packageName.equals(defaultPkg) && java.util.Objects.equals(subroutineName, defaultSub)) {
            cachedFrame = frame;
        }
        return frame;
    }

    /**
     * Override RuntimeCode.apply() to dispatch to interpreter.
     *
     * <p>This is the ONLY method that differs from compiled RuntimeCode.
     * The API signature is IDENTICAL, ensuring perfect compatibility.
     *
     * <p>Regex state save/restore is handled inside {@code BytecodeInterpreter.execute()}
     * (via {@code savedRegexState}/finally), not here.
     *
     * @param args        The arguments array (@_)
     * @param callContext The calling context (VOID/SCALAR/LIST)
     * @return RuntimeList containing the result (may be RuntimeControlFlowList)
     */
    @Override
    public RuntimeList apply(RuntimeArray args, int callContext) {
        // Return cached constant value if this sub has been const-folded
        if (constantValue != null) {
            return new RuntimeList(constantValue);
        }
        // Push args for getCallerArgs() support (used by List::Util::any/all/etc.)
        // This matches what RuntimeCode.apply() does for JVM-compiled subs
        RuntimeCode.pushArgs(args);
        // Push warning bits for FATAL warnings support
        // This allows runtime code to check current warning context
        if (warningBitsString != null) {
            WarningBitsRegistry.pushCurrent(warningBitsString);
        }
        try {
            return BytecodeInterpreter.execute(this, args, callContext);
        } finally {
            if (warningBitsString != null) {
                WarningBitsRegistry.popCurrent();
            }
            RuntimeCode.popArgs();
        }
    }

    @Override
    public RuntimeList apply(String subroutineName, RuntimeArray args, int callContext) {
        // Return cached constant value if this sub has been const-folded
        if (constantValue != null) {
            return new RuntimeList(constantValue);
        }
        // Push args for getCallerArgs() support (used by List::Util::any/all/etc.)
        RuntimeCode.pushArgs(args);
        // Push warning bits for FATAL warnings support
        if (warningBitsString != null) {
            WarningBitsRegistry.pushCurrent(warningBitsString);
        }
        try {
            return BytecodeInterpreter.execute(this, args, callContext, subroutineName);
        } finally {
            if (warningBitsString != null) {
                WarningBitsRegistry.popCurrent();
            }
            RuntimeCode.popArgs();
        }
    }

    /**
     * Override RuntimeCode.defined() to return true for InterpretedCode.
     * InterpretedCode doesn't use methodHandle, so the parent defined() check fails.
     * But InterpretedCode instances are always "defined" - they contain executable bytecode.
     */
    @Override
    public boolean defined() {
        return true;
    }

    /**
     * Create an InterpretedCode with captured variables (for closures).
     *
     * @param capturedVars The variables to capture from outer scope
     * @return A new InterpretedCode with captured variables
     */
    public InterpretedCode withCapturedVars(RuntimeBase[] capturedVars) {
        InterpretedCode copy = new InterpretedCode(
                this.bytecode,
                this.constants,
                this.stringPool,
                this.maxRegisters,
                capturedVars,
                this.sourceName,
                this.sourceLine,
                this.pcToTokenIndex,
                this.variableRegistry,
                this.errorUtil,
                this.strictOptions,
                this.featureFlags,
                this.warningFlags,
                this.compilePackage,
                this.evalSiteRegistries,
                this.evalSitePragmaFlags,
                this.warningBitsString
        );
        copy.prototype = this.prototype;
        copy.attributes = this.attributes != null ? new java.util.ArrayList<>(this.attributes) : null;
        copy.subName = this.subName;
        copy.packageName = this.packageName;
        // Preserve compiler-set fields that are not passed through the constructor
        copy.gotoLabelPcs = this.gotoLabelPcs;
        copy.usesLocalization = this.usesLocalization;
        return copy;
    }

    /**
     * Register this InterpretedCode as a global named subroutine.
     * This allows compiled code to call interpreted closures seamlessly.
     *
     * @param name Subroutine name (e.g., "main::closure_123")
     * @return RuntimeScalar CODE reference to this InterpretedCode
     */
    public RuntimeScalar registerAsNamedSub(String name) {
        // Extract package and sub name
        int lastColonIndex = name.lastIndexOf("::");
        if (lastColonIndex > 0) {
            this.packageName = name.substring(0, lastColonIndex);
            this.subName = name.substring(lastColonIndex + 2);
        } else {
            this.packageName = "main";
            this.subName = name;
        }

        // Register in global code refs (creates or gets existing RuntimeScalar)
        // Then set its value to this InterpretedCode
        RuntimeScalar codeRef = GlobalVariable.getGlobalCodeRef(name);
        codeRef.set(new RuntimeScalar(this));

        return codeRef;
    }

    /**
     * Get a human-readable representation for debugging.
     */
    @Override
    public String toString() {
        return "InterpretedCode{" +
                "sourceName='" + sourceName + '\'' +
                ", sourceLine=" + sourceLine +
                ", bytecode.length=" + bytecode.length +
                ", maxRegisters=" + maxRegisters +
                ", hasCapturedVars=" + (capturedVars != null && capturedVars.length > 0) +
                '}';
    }

    /**
     * Builder class for constructing InterpretedCode instances.
     */
    public static class Builder {
        private int[] bytecode;
        private Object[] constants = new Object[0];
        private String[] stringPool = new String[0];
        private int maxRegisters = 10;
        private RuntimeBase[] capturedVars = null;
        private String sourceName = "<eval>";
        private int sourceLine = 1;

        public Builder bytecode(int[] bytecode) {
            this.bytecode = bytecode;
            return this;
        }

        public Builder constants(Object[] constants) {
            this.constants = constants;
            return this;
        }

        public Builder stringPool(String[] stringPool) {
            this.stringPool = stringPool;
            return this;
        }

        public Builder maxRegisters(int maxRegisters) {
            this.maxRegisters = maxRegisters;
            return this;
        }

        public Builder capturedVars(RuntimeBase[] capturedVars) {
            this.capturedVars = capturedVars;
            return this;
        }

        public Builder sourceName(String sourceName) {
            this.sourceName = sourceName;
            return this;
        }

        public Builder sourceLine(int sourceLine) {
            this.sourceLine = sourceLine;
            return this;
        }

        public InterpretedCode build() {
            if (bytecode == null) {
                throw new IllegalStateException("Bytecode is required");
            }
            return new InterpretedCode(bytecode, constants, stringPool, maxRegisters,
                    capturedVars, sourceName, sourceLine, null, null);
        }
    }
}
