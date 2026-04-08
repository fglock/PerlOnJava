package org.perlonjava.backend.bytecode;


import org.perlonjava.backend.jvm.EmitterContext;
import org.perlonjava.backend.jvm.EmitterMethodCreator;
import org.perlonjava.frontend.analysis.ConstantFoldingVisitor;
import org.perlonjava.frontend.analysis.FindDeclarationVisitor;
import org.perlonjava.frontend.analysis.RegexUsageDetector;
import org.perlonjava.frontend.analysis.Visitor;
import org.perlonjava.frontend.astnode.*;
import org.perlonjava.frontend.semantic.ScopedSymbolTable;
import org.perlonjava.frontend.semantic.SymbolTable;
import org.perlonjava.runtime.debugger.DebugState;
import org.perlonjava.runtime.perlmodule.Attributes;
import org.perlonjava.runtime.perlmodule.Strict;
import org.perlonjava.runtime.runtimetypes.*;
import org.perlonjava.runtime.WarningBitsRegistry;

import java.util.*;

/**
 * BytecodeCompiler traverses the AST and generates interpreter bytecode.
 * <p>
 * This is analogous to EmitterVisitor but generates custom bytecode
 * for the interpreter instead of JVM bytecode via ASM.
 * <p>
 * Key responsibilities:
 * - Visit AST nodes and emit interpreter opcodes
 * - Allocate registers for variables and temporaries
 * - Build constant pool and string pool
 * - Generate 3-address code (rd = rs1 op rs2)
 */
public class BytecodeCompiler implements Visitor {
    // Pre-allocate with reasonable initial capacity to reduce resizing
    // Typical small eval/subroutine needs 20-50 bytecodes, 5-10 constants, 3-8 strings
    final List<Integer> bytecode = new ArrayList<>(64);
    // Scoped symbol table for variable tracking, package/class tracking, and eval STRING support.
    // Replaces the old variableScopes Stack + allDeclaredVariables flat map.
    // Using ScopedSymbolTable gives us proper scope-aware variable lookups and
    // getAllVisibleVariables()/getVisibleVariableRegistry() for per-eval-site snapshots.
    final ScopedSymbolTable symbolTable = new ScopedSymbolTable();
    // Goto label support: maps label names to their PC addresses for intra-function goto.
    // pendingGotos tracks forward references (goto before label) needing patch-up.
    final Map<String, Integer> gotoLabelPcs = new HashMap<>();
    final List<Object[]> pendingGotos = new ArrayList<>();  // [patchPc(Integer), labelName(String)]
    // Error reporting
    final ErrorMessageUtil errorUtil;
    // Per-site variable registries: each eval STRING or DEBUG opcode emission snapshots
    // the currently visible variables so at runtime the correct registers are captured.
    // Used by both eval STRING (for variable capture) and debugger (for lexical access).
    final List<Map<String, Integer>> evalSiteRegistries = new ArrayList<>();
    final List<int[]> evalSitePragmaFlags = new ArrayList<>();
    // Cache for DEBUG site registry deduplication - reuse index if scope unchanged
    private Map<String, Integer> lastDebugRegistry = null;
    private int lastDebugRegistryIndex = -1;
    // Variables captured by inner closures (named or anonymous subs compiled within this scope).
    // Assignments to these variables must use SET_SCALAR alone (in-place modification)
    // instead of LOAD_UNDEF + SET_SCALAR, to preserve the RuntimeScalar identity that
    // closures share.
    final Set<String> closureCapturedVarNames = new HashSet<>();
    // Source information
    final String sourceName;
    final int sourceLine;
    private final List<Object> constants = new ArrayList<>(16);
    private final List<String> stringPool = new ArrayList<>(16);
    private final Map<String, Integer> stringPoolIndex = new HashMap<>(16);  // O(1) lookup
    private final Map<Object, Integer> constantPoolIndex = new HashMap<>(16);  // O(1) lookup
    // Scope index stack for proper ScopedSymbolTable.exitScope() calls
    private final Stack<Integer> scopeIndices = new Stack<>();
    // Stack to save/restore register state when entering/exiting scopes
    private final Stack<Integer> savedNextRegister = new Stack<>();
    private final Stack<Integer> savedBaseRegister = new Stack<>();
    // Loop label stack for last/next/redo control flow
    // Each entry tracks loop boundaries and optional label
    private final Stack<LoopInfo> loopStack = new Stack<>();
    // Token index tracking for error reporting
    private final TreeMap<Integer, Integer> pcToTokenIndex = new TreeMap<>();
    int currentTokenIndex = -1;  // Track current token for error reporting
    // Callsite ID counter for /o modifier support (unique across all compilations)
    private static int nextCallsiteId = 1;
    // Track last result register for expression chaining
    int lastResultReg = -1;
    // Target output register for ALIAS elimination (same save/restore pattern as currentCallContext).
    // Callers set this before accept(); callees consume it via allocateOutputRegister().
    // Compound visitors save/restore around non-final children.
    int targetOutputReg = -1;

    // Track current calling context for subroutine calls
    int currentCallContext = RuntimeContextType.LIST; // Default to LIST
    Map<String, Integer> capturedVarIndices;  // Name → register index
    // BEGIN support for named subroutine closures
    int currentSubroutineBeginId = 0;     // BEGIN ID for current named subroutine (0 = not in named sub)
    Set<String> currentSubroutineClosureVars = new HashSet<>();  // Variables captured from outer scope
    // EmitterContext for strict checks and other compile-time options
    private EmitterContext emitterContext;
    // Register allocation
    private int nextRegister = 3;  // 0=this, 1=@_, 2=wantarray
    private int baseRegisterForStatement = 3;  // Reset point after each statement
    private int maxRegisterEverUsed = 2;  // Track highest register ever allocated
    // True when this compiler was constructed for eval STRING (has parentRegistry)
    private boolean isEvalString;
    // True when compiling inside a defer block (control flow out of defer is prohibited)
    private boolean isInDeferBlock;
    // True when compiling inside a map/grep block (explicit return must use RETURN_NONLOCAL)
    boolean isInMapGrepBlock;
    // Nesting depth inside eval blocks (goto &sub from eval is prohibited)
    // 0 = not in eval block, >0 = inside eval block(s)
    int evalBlockDepth;
    // Counter tracking nesting depth inside finally blocks (control flow out of finally is prohibited)
    private int finallyBlockDepth;
    // Tracks whether any LOCAL_* or PUSH_LOCAL_VARIABLE opcodes are emitted (for DynamicVariableManager optimization)
    private boolean usesLocalization;
    // Closure support
    private RuntimeBase[] capturedVars;           // Captured variable values
    private String[] capturedVarNames;            // Parallel array of names

    public BytecodeCompiler(String sourceName, int sourceLine, ErrorMessageUtil errorUtil) {
        this.sourceName = sourceName;
        this.sourceLine = sourceLine;
        this.errorUtil = errorUtil;

        // Initialize symbolTable with the 3 reserved registers
        symbolTable.addVariableWithIndex("this", 0, "reserved");
        symbolTable.addVariableWithIndex("@_", 1, "reserved");
        symbolTable.addVariableWithIndex("wantarray", 2, "reserved");
    }

    // Legacy constructor for backward compatibility
    public BytecodeCompiler(String sourceName, int sourceLine) {
        this(sourceName, sourceLine, null);
    }

    /**
     * Constructor for eval STRING with parent scope variable registry.
     * Initializes symbolTable with variables from parent scope.
     *
     * @param sourceName     Source name for error messages
     * @param sourceLine     Source line for error messages
     * @param errorUtil      Error message utility
     * @param parentRegistry Variable registry from parent scope (for eval STRING)
     */
    public BytecodeCompiler(String sourceName, int sourceLine, ErrorMessageUtil errorUtil,
                            Map<String, Integer> parentRegistry) {
        this.sourceName = sourceName;
        this.sourceLine = sourceLine;
        this.errorUtil = errorUtil;

        // Initialize symbolTable with the 3 reserved registers
        symbolTable.addVariableWithIndex("this", 0, "reserved");
        symbolTable.addVariableWithIndex("@_", 1, "reserved");
        symbolTable.addVariableWithIndex("wantarray", 2, "reserved");

        this.isEvalString = true;

        if (parentRegistry != null) {
            // Add parent scope variables to symbolTable (for eval STRING variable capture)
            for (Map.Entry<String, Integer> entry : parentRegistry.entrySet()) {
                String varName = entry.getKey();
                int regIndex = entry.getValue();
                if (regIndex >= 3) {
                    symbolTable.addVariableWithIndex(varName, regIndex, "my");
                }
            }

            // Mark parent scope variables as captured so assignments use SET_SCALAR
            capturedVarIndices = new HashMap<>();
            for (Map.Entry<String, Integer> entry : parentRegistry.entrySet()) {
                String varName = entry.getKey();
                int regIndex = entry.getValue();
                if (regIndex >= 3) {
                    capturedVarIndices.put(varName, regIndex);
                }
            }

            // Adjust nextRegister to account for captured variables
            int maxRegister = 2;
            for (Integer regIndex : parentRegistry.values()) {
                if (regIndex > maxRegister) {
                    maxRegister = regIndex;
                }
            }
            this.nextRegister = maxRegister + 1;
            this.maxRegisterEverUsed = maxRegister;
            this.baseRegisterForStatement = this.nextRegister;
        }
    }

    /**
     * Helper: Check if a variable is a built-in special length-one variable.
     * Single-character non-letter variables like $_, $0, $!, $; are always allowed under strict.
     * Mirrors logic from EmitVariable.java.
     */
    private static boolean isBuiltinSpecialLengthOneVar(String sigil, String name) {
        if (!"$".equals(sigil) || name == null || name.length() != 1) {
            return false;
        }
        char c = name.charAt(0);
        // In Perl, many single-character non-identifier variables (punctuation/digits)
        // are built-in special vars and are exempt from strict 'vars'.
        return !Character.isLetter(c);
    }

    /**
     * Helper: Check if a variable is a built-in special scalar variable.
     * Variables like ${^GLOBAL_PHASE}, $ARGV, $ENV are always allowed under strict.
     * Mirrors logic from EmitVariable.java.
     */
    private static boolean isBuiltinSpecialScalarVar(String sigil, String name) {
        if (!"$".equals(sigil) || name == null || name.isEmpty()) {
            return false;
        }
        // ${^FOO} variables are encoded as a leading ASCII control character.
        // (e.g. ${^GLOBAL_PHASE} -> "\aLOBAL_PHASE"). These are built-in and strict-safe.
        if (name.charAt(0) < 32) {
            return true;
        }
        return name.equals("ARGV")
                || name.equals("ARGVOUT")
                || name.equals("ENV")
                || name.equals("INC")
                || name.equals("SIG")
                || name.equals("STDIN")
                || name.equals("STDOUT")
                || name.equals("STDERR");
    }

    /**
     * Helper: Check if a variable is a built-in special container variable.
     * Variables like %ENV, @ARGV, @INC are always allowed under strict.
     * Mirrors logic from EmitVariable.java.
     */
    private static boolean isBuiltinSpecialContainerVar(String sigil, String name) {
        if (name == null) {
            return false;
        }
        if ("%".equals(sigil)) {
            return name.equals("SIG")
                    || name.equals("ENV")
                    || name.equals("INC")
                    || name.equals("+")
                    || name.equals("-")
                    || name.equals("_");
        }
        if ("@".equals(sigil)) {
            return name.equals("ARGV")
                    || name.equals("INC")
                    || name.equals("_")
                    || name.equals("F");
        }
        return false;
    }

    boolean hasVariable(String name) {
        return symbolTable.getVariableIndex(name) != -1;
    }

    boolean isOurVariable(String name) {
        SymbolTable.SymbolEntry entry = symbolTable.getSymbolEntry(name);
        return entry != null && "our".equals(entry.decl());
    }

    boolean isReservedVariable(String name) {
        SymbolTable.SymbolEntry entry = symbolTable.getSymbolEntry(name);
        return entry != null && "reserved".equals(entry.decl());
    }

    int getVariableRegister(String name) {
        return symbolTable.getVariableIndex(name);
    }

    int addVariable(String name, String declType) {
        int reg = allocateRegister();
        symbolTable.addVariableWithIndex(name, reg, declType);
        return reg;
    }

    void registerVariable(String name, int reg) {
        symbolTable.addVariableWithIndex(name, reg, "my");
    }

    private void enterScope() {
        int scopeIdx = symbolTable.enterScope();
        scopeIndices.push(scopeIdx);
        savedNextRegister.push(nextRegister);
        savedBaseRegister.push(baseRegisterForStatement);
        baseRegisterForStatement = nextRegister;
        if (emitterContext != null && emitterContext.symbolTable != null) {
            ScopedSymbolTable st = emitterContext.symbolTable;
            st.strictOptionsStack.push(st.strictOptionsStack.peek());
            st.featureFlagsStack.push(st.featureFlagsStack.peek());
            st.warningFlagsStack.push((java.util.BitSet) st.warningFlagsStack.peek().clone());
            st.warningFatalStack.push((java.util.BitSet) st.warningFatalStack.peek().clone());
            st.warningDisabledStack.push((java.util.BitSet) st.warningDisabledStack.peek().clone());
        }
    }

    private void exitScope() {
        exitScope(false);
    }

    /**
     * Exit the current scope, emitting cleanup opcodes.
     *
     * @param flush If true, emit MORTAL_PUSH_MARK before and MORTAL_POP_FLUSH after
     *              cleanup to trigger DESTROY for blessed objects whose refCount drops
     *              to 0. Only entries added by the cleanup are flushed (scoped flush).
     *              Must be false for subroutine body scopes where the return value
     *              is on the stack.
     */
    private void exitScope(boolean flush) {
        if (!scopeIndices.isEmpty()) {
            int scopeIdx = scopeIndices.pop();

            // Push mark BEFORE cleanup so popAndFlush only processes entries
            // added by scopeExitCleanup (not older entries from outer scopes)
            if (flush) {
                emit(Opcodes.MORTAL_PUSH_MARK);
            }

            // Emit SCOPE_EXIT_CLEANUP for each my-scalar register in the exiting scope.
            // This calls RuntimeScalar.scopeExitCleanup() which handles:
            // 1. IO fd recycling for anonymous filehandle globs
            // 2. refCount decrement for blessed references with DESTROY
            java.util.List<Integer> scalarIndices = symbolTable.getMyScalarIndicesInScope(scopeIdx);
            for (int reg : scalarIndices) {
                emit(Opcodes.SCOPE_EXIT_CLEANUP);
                emitReg(reg);
            }

            // Walk hash/array variables for nested blessed references.
            java.util.List<Integer> hashIndices = symbolTable.getMyHashIndicesInScope(scopeIdx);
            for (int reg : hashIndices) {
                emit(Opcodes.SCOPE_EXIT_CLEANUP_HASH);
                emitReg(reg);
            }
            java.util.List<Integer> arrayIndices = symbolTable.getMyArrayIndicesInScope(scopeIdx);
            for (int reg : arrayIndices) {
                emit(Opcodes.SCOPE_EXIT_CLEANUP_ARRAY);
                emitReg(reg);
            }

            // Pop mark and flush only entries added since the mark
            if (flush) {
                emit(Opcodes.MORTAL_POP_FLUSH);
            }

            symbolTable.exitScope(scopeIdx);
            if (!savedNextRegister.isEmpty()) {
                nextRegister = savedNextRegister.pop();
            }
            if (!savedBaseRegister.isEmpty()) {
                baseRegisterForStatement = savedBaseRegister.pop();
            }
            if (emitterContext != null && emitterContext.symbolTable != null) {
                ScopedSymbolTable st = emitterContext.symbolTable;
                st.strictOptionsStack.pop();
                st.featureFlagsStack.pop();
                st.warningFlagsStack.pop();
                st.warningFatalStack.pop();
                st.warningDisabledStack.pop();
            }
        }
    }

    /**
     * Helper: Get current package name for global variable resolution.
     * Uses symbolTable for proper package/class tracking.
     */
    String getCurrentPackage() {
        return symbolTable.getCurrentPackage();
    }

    /**
     * Set the compile-time package for name normalization.
     * Called by eval STRING handlers to sync the package from the call site,
     * so bare names like *named compile to FOO3::named instead of main::named.
     */
    public void setCompilePackage(String packageName) {
        symbolTable.setCurrentPackage(packageName, false);
    }

    private String[] getVariableNames() {
        return symbolTable.getVariableNames();
    }

    /**
     * Helper: Check if a non-ASCII length-1 scalar is allowed under 'no utf8'.
     * Under 'no utf8', Latin-1 characters (0x80-0xFF) in single-char variable names
     * are treated as special and exempt from strict vars checking.
     * Mirrors logic from EmitVariable.java lines 82-88.
     *
     * @param sigil The variable sigil
     * @param name  The bare variable name (without sigil)
     * @return true if this is a non-ASCII length-1 scalar allowed under 'no utf8'
     */
    private boolean isNonAsciiLengthOneScalarAllowedUnderNoUtf8(String sigil, String name) {
        if (!"$".equals(sigil) || name == null || name.length() != 1) {
            return false;
        }
        char c = name.charAt(0);
        // Allow if character is in Latin-1 extended range (128-255) and 'use utf8' is NOT enabled
        // Unicode characters above 255 (like Greek α = 945) should NOT be exempt
        return c > 127 && c <= 255
                && getEffectiveSymbolTable().isStrictOptionEnabled(Strict.HINT_UTF8) == false;
    }

    /**
     * Returns the effective symbol table for pragma checks.
     * Prefers emitterContext.symbolTable (when available), falls back to this.symbolTable.
     * This ensures sub-compilers (which have null emitterContext) still enforce pragmas.
     */
    private ScopedSymbolTable getEffectiveSymbolTable() {
        if (emitterContext != null && emitterContext.symbolTable != null) {
            return emitterContext.symbolTable;
        }
        return symbolTable;
    }

    /**
     * Copies pragma flags (strict, warnings, features) from this compiler's effective
     * symbol table into a sub-compiler's symbol table. Called before compiling subroutine
     * bodies so that BEGIN { $^H = ... } changes propagate into anonymous/named subs.
     */
    private void inheritPragmaFlags(BytecodeCompiler subCompiler) {
        ScopedSymbolTable parentST = getEffectiveSymbolTable();
        subCompiler.symbolTable.strictOptionsStack.pop();
        subCompiler.symbolTable.strictOptionsStack.push(parentST.strictOptionsStack.peek());
        subCompiler.symbolTable.featureFlagsStack.pop();
        subCompiler.symbolTable.featureFlagsStack.push(parentST.featureFlagsStack.peek());
        subCompiler.symbolTable.warningFlagsStack.pop();
        subCompiler.symbolTable.warningFlagsStack.push((java.util.BitSet) parentST.warningFlagsStack.peek().clone());
        subCompiler.symbolTable.warningFatalStack.pop();
        subCompiler.symbolTable.warningFatalStack.push((java.util.BitSet) parentST.warningFatalStack.peek().clone());
        subCompiler.symbolTable.warningDisabledStack.pop();
        subCompiler.symbolTable.warningDisabledStack.push((java.util.BitSet) parentST.warningDisabledStack.peek().clone());
    }

    /**
     * Returns true if strict refs is currently enabled in the symbol table.
     */
    boolean isStrictRefsEnabled() {
        return getEffectiveSymbolTable().isStrictOptionEnabled(Strict.HINT_STRICT_REFS);
    }

    /**
     * Helper: Check if strict vars should block access to this global variable.
     * Returns true if the variable should be BLOCKED (not allowed).
     * Mirrors the createIfNotExists logic from EmitVariable.java lines 362-371.
     *
     * @param varName The variable name with sigil (e.g., "$A", "@array")
     * @return true if access should be blocked under strict vars
     */

    boolean isBytesEnabled() {
        return getEffectiveSymbolTable().isStrictOptionEnabled(Strict.HINT_BYTES);
    }

    boolean isIntegerEnabled() {
        return getEffectiveSymbolTable().isStrictOptionEnabled(Strict.HINT_INTEGER);
    }

    boolean isNoOverloadingEnabled() {
        return getEffectiveSymbolTable().isStrictOptionEnabled(Strict.HINT_NO_AMAGIC);
    }

    boolean shouldBlockGlobalUnderStrictVars(String varName) {
        boolean strictEnabled = getEffectiveSymbolTable().isStrictOptionEnabled(Strict.HINT_STRICT_VARS);
        if (!strictEnabled) {
            return false;  // Strict vars not enabled, allow access
        }

        // Extract sigil and bare name
        String sigil = varName.substring(0, 1);
        String bareVarName = varName.substring(1);

        // Allow qualified names (contain ::)
        if (bareVarName.contains("::")) {
            return false;
        }

        // Allow regex capture variables ($1, $2, etc.) but not leading-zero variants ($01, $02)
        if (ScalarUtils.isInteger(bareVarName) && !bareVarName.startsWith("0")) {
            return false;
        }

        // Allow special sort variables $a and $b
        if (sigil.equals("$") && (bareVarName.equals("a") || bareVarName.equals("b"))) {
            return false;
        }

        // Allow built-in special variables
        if (isBuiltinSpecialLengthOneVar(sigil, bareVarName)) {
            return false;
        }
        if (isBuiltinSpecialScalarVar(sigil, bareVarName)) {
            return false;
        }
        if (isBuiltinSpecialContainerVar(sigil, bareVarName)) {
            return false;
        }

        // Allow non-ASCII length-1 scalars under 'no utf8'
        // e.g., $ª, $µ, $º under 'no utf8' are treated as special variables
        if (isNonAsciiLengthOneScalarAllowedUnderNoUtf8(sigil, bareVarName)) {
            return false;
        }

        // Allow variables that already exist in the global registry
        // (e.g., created by `use vars` at parse time)
        // This mirrors the allowIfAlreadyExists logic in EmitVariable.java
        String normalizedName = NameNormalizer.normalizeVariableName(bareVarName, getCurrentPackage());
        boolean allowIfAlreadyExists = sigil.equals("$") && GlobalVariable.existsGlobalVariable(normalizedName);
        if (sigil.equals("@") && GlobalVariable.existsGlobalArray(normalizedName)) {
            allowIfAlreadyExists = true;
        }
        if (sigil.equals("%") && !normalizedName.endsWith("::") && GlobalVariable.existsGlobalHash(normalizedName)) {
            allowIfAlreadyExists = true;
        }

        // Single-letter scalars ($A-$Z) bypass strict only if explicitly declared
        // (via use vars or Exporter import), not if merely auto-vivified under 'no strict'.
        boolean isSpecialSortVar = sigil.equals("$")
                && (bareVarName.equals("a") || bareVarName.equals("b"));
        if (sigil.equals("$")
                && bareVarName != null
                && bareVarName.length() == 1
                && Character.isLetter(bareVarName.charAt(0))
                && !bareVarName.contains("::")
                && !isSpecialSortVar) {
            if (!GlobalVariable.isDeclaredGlobalVariable(normalizedName)) {
                allowIfAlreadyExists = false;
            }
        }

        return !allowIfAlreadyExists;

        // BLOCK: Unqualified variable under strict vars
    }

    /**
     * Throw a compiler exception with proper error formatting.
     * Uses PerlCompilerException which formats with line numbers and code context.
     *
     * @param message    The error message
     * @param tokenIndex The token index where the error occurred
     */
    void throwCompilerException(String message, int tokenIndex) {
        if (errorUtil != null && tokenIndex >= 0) {
            throw new PerlCompilerException(tokenIndex, message, errorUtil);
        } else {
            // Fallback to simple error (no context available)
            throw new RuntimeException(message);
        }
    }

    /**
     * Throw a compiler exception using the current token index.
     *
     * @param message The error message
     */
    void throwCompilerException(String message) {
        throwCompilerException(message, currentTokenIndex);
    }

    /**
     * Check if we're inside a defer or finally block and throw an error if so.
     * Control flow statements (return, goto, last, next, redo) are prohibited in these blocks.
     *
     * @param tokenIndex The token index for error reporting
     * @param operator   The control flow operator (e.g., "return", "goto", "last")
     */
    void checkNotInDeferBlock(int tokenIndex, String operator) {
        if (isInDeferBlock) {
            throwCompilerException("Can't \"" + operator + "\" out of a \"defer\" block", tokenIndex);
        }
        if (finallyBlockDepth > 0) {
            throwCompilerException("Can't \"" + operator + "\" out of a \"finally\" block", tokenIndex);
        }
    }

    /**
     * Returns the type of eval scope the compiler is currently inside, or null if not in eval.
     * Used by goto &sub to emit "Can't goto subroutine from an eval-*" errors.
     * Does NOT count eval scopes that are outside a sub definition boundary
     * (e.g., sub { goto &foo } inside eval is fine).
     */
    String getEvalScopeType() {
        if (isEvalString) return "eval-string";
        if (evalBlockDepth > 0) return "eval-block";
        return null;
    }

    /**
     * Compile an AST node to InterpretedCode.
     *
     * @param node The AST node to compile
     * @return InterpretedCode ready for execution
     */
    public InterpretedCode compile(Node node) {
        return compile(node, null);
    }

    /**
     * Compile an AST node to InterpretedCode with optional closure support.
     *
     * @param node The AST node to compile
     * @param ctx  EmitterContext for closure detection (may be null)
     * @return InterpretedCode ready for execution
     */
    public InterpretedCode compile(Node node, EmitterContext ctx) {
        // Store context for strict checks and other compile-time options
        this.emitterContext = ctx;

        // Detect closure variables if context is provided
        if (ctx != null) {
            detectClosureVariables(node, ctx);
            // Use the calling context from EmitterContext for top-level expressions
            // This is crucial for eval STRING to propagate context correctly
            currentCallContext = ctx.contextType;
            // Inherit package from the JVM compiler context so that eval STRING
            // compiles unqualified names in the caller's package, not "main".
            // This is compile-time package propagation — it sets the symbol table's
            // current package so the parser/emitter qualify names correctly.
            // Note: the *runtime* package (InterpreterState.currentPackage) is a
            // separate concern used only by caller(); it must be scoped via
            // DynamicVariableManager around eval execution to prevent leaking.
            // See InterpreterState.currentPackage javadoc for details.
            if (ctx.symbolTable != null) {
                symbolTable.setCurrentPackage(ctx.symbolTable.getCurrentPackage(),
                        ctx.symbolTable.currentPackageIsClass());
                symbolTable.strictOptionsStack.pop();
                symbolTable.strictOptionsStack.push(ctx.symbolTable.strictOptionsStack.peek());
                symbolTable.featureFlagsStack.pop();
                symbolTable.featureFlagsStack.push(ctx.symbolTable.featureFlagsStack.peek());
                symbolTable.warningFlagsStack.pop();
                symbolTable.warningFlagsStack.push((java.util.BitSet) ctx.symbolTable.warningFlagsStack.peek().clone());
                symbolTable.warningFatalStack.pop();
                symbolTable.warningFatalStack.push((java.util.BitSet) ctx.symbolTable.warningFatalStack.peek().clone());
                symbolTable.warningDisabledStack.pop();
                symbolTable.warningDisabledStack.push((java.util.BitSet) ctx.symbolTable.warningDisabledStack.peek().clone());
            }
            // Propagate eval context flags from JVM backend for goto &sub checks
            if (ctx.javaClassInfo != null) {
                if (ctx.javaClassInfo.isInEvalBlock) {
                    evalBlockDepth++;
                }
                if (ctx.javaClassInfo.isInEvalString) {
                    isEvalString = true;
                }
                if (ctx.javaClassInfo.isInDeferBlock) {
                    isInDeferBlock = true;
                }
            }
        }

        // If we have captured variables, allocate registers for them
        // BUT: If we were constructed with a parentRegistry (for eval STRING),
        // nextRegister is already correctly set by the constructor.
        // Only reset it if we have runtime capturedVars but no parentRegistry.
        if (capturedVars != null && capturedVars.length > 0 && capturedVarIndices == null) {
            // Registers 0-2 are reserved (this, @_, wantarray)
            // Registers 3+ are captured variables
            nextRegister = 3 + capturedVars.length;
        }

        // For non-eval-STRING compilations (special blocks, top-level scripts),
        // override VOID→LIST so the block tracks its result. eval STRING must
        // preserve the caller's context so wantarray() works correctly inside.
        if (!isEvalString && currentCallContext == RuntimeContextType.VOID) {
            currentCallContext = RuntimeContextType.LIST;
        }

        int returnTargetReg = allocateRegister();
        targetOutputReg = returnTargetReg;

        node.accept(this);

        targetOutputReg = -1;

        int returnReg;
        if (lastResultReg >= 0) {
            returnReg = lastResultReg;
        } else {
            returnReg = returnTargetReg;
            emit(Opcodes.LOAD_UNDEF);
            emitReg(returnReg);
        }

        emit(Opcodes.RETURN);
        emitReg(returnReg);

        // Build variable registry for eval STRING support.
        // At this point all inner scopes have exited, so getVisibleVariableRegistry()
        // returns only the outermost scope variables. Per-eval-site registries
        // (stored in evalSiteRegistries) provide the correct scope-aware mappings.
        Map<String, Integer> variableRegistry = symbolTable.getVisibleVariableRegistry();

        // Extract strict/feature/warning flags for eval STRING inheritance
        int strictOptions = 0;
        int featureFlags = 0;
        BitSet warningFlags = new BitSet();
        String warningBitsString = null;
        if (emitterContext != null && emitterContext.symbolTable != null) {
            strictOptions = emitterContext.symbolTable.strictOptionsStack.peek();
            featureFlags = emitterContext.symbolTable.featureFlagsStack.peek();
            warningFlags = (BitSet) emitterContext.symbolTable.warningFlagsStack.peek().clone();
            warningBitsString = emitterContext.symbolTable.getWarningBitsString();
        }

        // Populate debug source lines if in debug mode
        if (DebugState.debugMode && errorUtil != null && sourceName != null) {
            String[] lines = errorUtil.extractSourceLines();
            if (lines.length > 0) {
                DebugState.storeSourceLines(sourceName, lines);
            }
        }

        // Build InterpretedCode
        InterpretedCode code = new InterpretedCode(
                toShortArray(),
                constants.toArray(),
                stringPool.toArray(new String[0]),
                maxRegisterEverUsed + 1,
                capturedVars,
                sourceName,
                sourceLine,
                pcToTokenIndex,
                variableRegistry,
                errorUtil,
                strictOptions,
                featureFlags,
                warningFlags,
                symbolTable.getCurrentPackage(),
                evalSiteRegistries.isEmpty() ? null : evalSiteRegistries,
                evalSitePragmaFlags.isEmpty() ? null : evalSitePragmaFlags,
                warningBitsString
        );
        // Set optimization flag - if no LOCAL_* or PUSH_LOCAL_VARIABLE opcodes were emitted,
        // the interpreter can skip DynamicVariableManager.getLocalLevel/popToLocalLevel
        code.usesLocalization = this.usesLocalization;
        // Store goto label map for dynamic goto support (goto $variable)
        if (!this.gotoLabelPcs.isEmpty()) {
            code.gotoLabelPcs = new HashMap<>(this.gotoLabelPcs);
        }
        return code;
    }

    /**
     * Detect closure variables: variables referenced but not declared locally.
     * Populates capturedVars, capturedVarNames, and capturedVarIndices.
     *
     * @param ast AST to scan for variable references
     * @param ctx EmitterContext containing symbol table and eval context
     */
    private void detectClosureVariables(Node ast, EmitterContext ctx) {
        if (capturedVarIndices != null) {
            return;
        }

        if (ctx == null || ctx.symbolTable == null) {
            return;
        }

        // Use getAllVisibleVariables() (TreeMap sorted by register index) with the same
        // filtering as SubroutineParser to ensure capturedVars ordering matches exactly.
        Map<Integer, org.perlonjava.frontend.semantic.SymbolTable.SymbolEntry> outerVars =
                ctx.symbolTable.getAllVisibleVariables();
        int skipVariables = org.perlonjava.backend.jvm.EmitterMethodCreator.skipVariables;

        List<String> outerVarNames = new ArrayList<>();
        List<RuntimeBase> outerValues = new ArrayList<>();
        List<String> outerVarDecls = new ArrayList<>();
        capturedVarIndices = new HashMap<>();

        int reg = 3;
        for (Map.Entry<Integer, org.perlonjava.frontend.semantic.SymbolTable.SymbolEntry> e :
                outerVars.entrySet()) {
            if (e.getKey() < skipVariables) continue;
            org.perlonjava.frontend.semantic.SymbolTable.SymbolEntry entry = e.getValue();
            String name = entry.name();
            if (name.equals("@_")) continue;
            if (entry.decl().isEmpty()) continue;
            if (entry.decl().equals("field")) continue;
            if (name.startsWith("&")) continue;
            capturedVarIndices.put(name, reg);
            outerVarNames.add(name);
            outerVarDecls.add(entry.decl());
            outerValues.add(getVariableValueFromContext(name, ctx));
            reg++;
        }

        for (int i = 0; i < outerVarNames.size(); i++) {
            symbolTable.addVariableWithIndex(outerVarNames.get(i), 3 + i, outerVarDecls.get(i));
        }

        // Reserve registers for captured variables so local my declarations don't collide
        nextRegister = reg;
        maxRegisterEverUsed = Math.max(maxRegisterEverUsed, nextRegister - 1);
        baseRegisterForStatement = nextRegister;

        // Also scan AST for referenced variables and add them to capturedVarIndices.
        // This prevents aggressive register recycling (getHighestVariableRegister uses this).
        Set<String> referencedVars = collectReferencedVariables(ast);
        Set<String> localVars = getLocalVariableNames(ctx);
        referencedVars.removeAll(localVars);
        referencedVars.removeIf(name ->
                name.equals("$_") || name.equals("$@") || name.equals("$!")
        );
        for (String varName : referencedVars) {
            if (!capturedVarIndices.containsKey(varName)) {
                capturedVarIndices.put(varName, reg++);
            }
        }

        if (outerVarNames.isEmpty() && capturedVarIndices.isEmpty()) {
            capturedVarIndices = null;
            return;
        }

        capturedVarNames = outerVarNames.toArray(new String[0]);
        capturedVars = outerValues.toArray(new RuntimeBase[0]);
    }

    // =========================================================================
    // CLOSURE DETECTION
    // =========================================================================

    /**
     * Collect all variable references in AST.
     *
     * @param ast AST node to scan
     * @return Set of variable names (with sigils)
     */
    private Set<String> collectReferencedVariables(Node ast) {
        Set<String> refs = new HashSet<>();
        ast.accept(new VariableCollectorVisitor(refs));
        return refs;
    }

    /**
     * Get local variable names from current scope (not parent scopes).
     *
     * @param ctx EmitterContext containing symbol table
     * @return Set of local variable names
     */
    private Set<String> getLocalVariableNames(EmitterContext ctx) {
        Set<String> locals = new HashSet<>();
        // Collect variables from all scopes
        String[] varNames = getVariableNames();
        for (String name : varNames) {
            // Skip the 3 reserved registers (this, @_, wantarray)
            if (!name.equals("this") && !name.equals("@_") && !name.equals("wantarray")) {
                locals.add(name);
            }
        }
        return locals;
    }

    /**
     * Get variable value from eval runtime context for closure capture.
     *
     * @param varName Variable name (with sigil)
     * @param ctx     EmitterContext containing eval tag
     * @return RuntimeBase value to capture
     */
    private RuntimeBase getVariableValueFromContext(String varName, EmitterContext ctx) {
        // For eval STRING, runtime values are available via evalRuntimeContext ThreadLocal
        RuntimeCode.EvalRuntimeContext evalCtx = RuntimeCode.getEvalRuntimeContext();
        if (evalCtx != null && evalCtx.runtimeValues() != null) {
            // Find variable in captured environment
            String[] capturedEnv = evalCtx.capturedEnv();
            Object[] runtimeValues = evalCtx.runtimeValues();

            for (int i = 0; i < capturedEnv.length; i++) {
                if (capturedEnv[i].equals(varName)) {
                    Object value = runtimeValues[i];
                    if (value instanceof RuntimeBase) {
                        return (RuntimeBase) value;
                    }
                }
            }
        }

        // If we can't find a runtime value, return a placeholder
        // This is OK - closures are typically created at runtime via eval
        return new RuntimeScalar();
    }

    /**
     * Compiles a block node, creating a new lexical scope.
     *
     * <p>Special case: the parser wraps implicit-{@code $_} foreach loops as
     * {@code BlockNode([local $_, For1Node(needsArrayOfAlias=true)])}.
     * In that pattern the {@code local $_} child is skipped here because
     * {@link #visit(For1Node)} emits {@code LOCAL_SCALAR_SAVE_LEVEL} itself,
     * which atomically saves the pre-push dynamic level and calls {@code makeLocal}.
     * This allows {@code POP_LOCAL_LEVEL} after the loop to restore {@code $_}
     * correctly regardless of nesting depth.
     */
    @Override
    public void visit(BlockNode node) {
        // Blocks create a new lexical scope
        // But if the block needs to return a value (not VOID context),
        // allocate a result register BEFORE entering the scope so it's valid after
        int outerResultReg = -1;
        if (currentCallContext != RuntimeContextType.VOID) {
            outerResultReg = allocateOutputRegister();
        }

        // Detect the BlockNode([local $_, For1Node(needsArrayOfAlias)]) pattern produced
        // by the parser for implicit-$_ foreach loops. For1Node emits LOCAL_SCALAR_SAVE_LEVEL
        // itself, so the 'local $_' child must be skipped here to avoid double-emission.
        // Using a local variable (not a field) makes this safe against nesting and exceptions.
        boolean skipFirstChild = node.elements.size() == 2
                && node.elements.get(1) instanceof For1Node for1
                && for1.needsArrayOfAlias
                && node.elements.get(0) instanceof OperatorNode localOp
                && localOp.operator.equals("local");

        // Save DynamicVariableManager level before the block body when the block contains
        // `local` operators, defer statements, or a scoped package declaration, so locals 
        // and deferred blocks are restored/executed on block exit.
        // This matches the JVM compiler's Local.localSetup/localTeardown pattern.
        int localLevelReg = -1;
        boolean needsLocalRestore = false;
        if (!node.getBooleanAnnotation("blockIsSubroutine")) {
            // Check for scoped package: package Foo { ... }
            boolean hasScopedPackage = !node.elements.isEmpty()
                    && node.elements.get(0) instanceof OperatorNode firstOp
                    && (firstOp.operator.equals("package") || firstOp.operator.equals("class"))
                    && Boolean.TRUE.equals(firstOp.getAnnotation("isScoped"));
            // Check for non-scoped package inside block: { package Foo; ... }
            // The runtime package (used by caller()) must be saved/restored on block exit.
            boolean hasNonScopedPackage = false;
            if (!hasScopedPackage) {
                for (Node elem : node.elements) {
                    if (elem instanceof OperatorNode opNode
                            && (opNode.operator.equals("package") || opNode.operator.equals("class"))
                            && !Boolean.TRUE.equals(opNode.getAnnotation("isScoped"))) {
                        hasNonScopedPackage = true;
                        break;
                    }
                }
            }
            // Check for both local operators and defer statements - both need scope cleanup
            boolean hasLocalOrDefer = FindDeclarationVisitor.containsLocalOrDefer(node);
            if (hasScopedPackage || hasNonScopedPackage || hasLocalOrDefer) {
                needsLocalRestore = true;
                localLevelReg = allocateRegister();
                emit(Opcodes.GET_LOCAL_LEVEL);
                emitReg(localLevelReg);
                // For non-scoped package declarations (e.g., { package Foo; ... }),
                // save the current package to DVM so POP_LOCAL_LEVEL restores it on block exit.
                // PUSH_PACKAGE saves the current package value and sets the new one;
                // since we pass the current package name, it's effectively just a save.
                if (hasNonScopedPackage) {
                    String currentPkg = symbolTable.getCurrentPackage();
                    int pkgIdx = addToStringPool(currentPkg);
                    emit(Opcodes.PUSH_PACKAGE);
                    emit(pkgIdx);
                }
            }
        }

        enterScope();

        int regexSaveReg = -1;
        if (!node.getBooleanAnnotation("blockIsSubroutine")
                && RegexUsageDetector.containsRegexOperation(node)) {
            regexSaveReg = allocateRegister();
            emit(Opcodes.SAVE_REGEX_STATE);
            emitReg(regexSaveReg);
        }

        // Visit each statement in the block
        int numStatements = node.elements.size();

        int lastMeaningfulIndex = -1;
        for (int i = numStatements - 1; i >= 0; i--) {
            Node elem = node.elements.get(i);
            if (elem == null) continue;
            if (elem instanceof ListNode ln && ln.elements.isEmpty()) continue;
            if (elem instanceof AbstractNode an && (an.getBooleanAnnotation("compileTimeOnly") || an.getBooleanAnnotation("noReturnValue"))) continue;
            lastMeaningfulIndex = i;
            break;
        }
        if (lastMeaningfulIndex == -1) lastMeaningfulIndex = numStatements - 1;

        int savedLastResultReg = -1;
        for (int i = 0; i < numStatements; i++) {
            // Skip the 'local $_' child when For1Node handles it via LOCAL_SCALAR_SAVE_LEVEL
            if (i == 0 && skipFirstChild) continue;
            Node stmt = node.elements.get(i);
            // Visit CompilerFlagNode to set strict/warning/feature flags, even if marked compileTimeOnly.
            // This is needed because pragmas like 'use strict' create CompilerFlagNode with compileTimeOnly=true,
            // but we still need to process it to set the flags before compiling subsequent statements.
            if (stmt instanceof CompilerFlagNode cfn) {
                cfn.accept(this);
                continue;
            }
            if (stmt instanceof AbstractNode an && an.getBooleanAnnotation("compileTimeOnly")) continue;

            // Track line number for this statement (like codegen's setDebugInfoLineNumber)
            int stmtTokenIndex = -1;
            if (stmt != null) {
                stmtTokenIndex = stmt.getIndex();
                int pc = bytecode.size();
                pcToTokenIndex.put(pc, stmtTokenIndex);
            }

            // Emit DEBUG opcode for debugger support (only when -d flag is active)
            // Skip debug opcodes for internal/infrastructure nodes (marked with skipDebug)
            if (DebugState.debugMode && stmtTokenIndex >= 0) {
                boolean skipDebug = (stmt instanceof AbstractNode an && an.getBooleanAnnotation("skipDebug"));
                if (!skipDebug) {
                    // Use getLineNumberAccurate() because subroutine bodies may be compiled
                    // lazily after the main script, making cached line numbers unreliable
                    int lineNumber = errorUtil.getLineNumberAccurate(stmtTokenIndex);
                    int fileIdx = addToStringPool(sourceName);
                    // Capture variable registry for debugger expression evaluation
                    // Deduplicate: reuse previous registry if scope unchanged (common case)
                    Map<String, Integer> currentRegistry = symbolTable.getVisibleVariableRegistry();
                    int siteIndex;
                    if (lastDebugRegistry != null && lastDebugRegistry.equals(currentRegistry)) {
                        // Scope unchanged - reuse existing registry
                        siteIndex = lastDebugRegistryIndex;
                    } else {
                        // New scope - add new registry
                        siteIndex = evalSiteRegistries.size();
                        evalSiteRegistries.add(currentRegistry);
                        lastDebugRegistry = currentRegistry;
                        lastDebugRegistryIndex = siteIndex;
                    }
                    emit(Opcodes.DEBUG);
                    emit(fileIdx);
                    emit(lineNumber);
                    emit(siteIndex);
                }
            }

            boolean isLastStatement = (i == lastMeaningfulIndex);
            int stmtTarget = (isLastStatement && outerResultReg >= 0) ? outerResultReg : -1;
            int stmtContext;
            if (!isLastStatement && !(stmt instanceof BinaryOperatorNode && ((BinaryOperatorNode) stmt).operator.equals("="))) {
                stmtContext = RuntimeContextType.VOID;
            } else {
                stmtContext = currentCallContext;
            }

            compileNode(stmt, stmtTarget, stmtContext);

            // Save lastResultReg after the last meaningful statement so that subsequent
            // non-meaningful statements (e.g., package declarations with noReturnValue)
            // don't overwrite it with -1. In Perl 5, `package Foo;` and `sub name { ... }`
            // are transparent for block return values.
            if (isLastStatement) {
                savedLastResultReg = lastResultReg;
            }

            // Recycle temporary registers after each statement
            // enterScope() protects registers allocated before entering a scope
            recycleTemporaryRegisters();

        }

        // Use the saved result reg from the last meaningful statement if subsequent
        // statements (like package declarations) reset lastResultReg to -1
        if (savedLastResultReg >= 0 && lastResultReg < 0) {
            lastResultReg = savedLastResultReg;
        }

        // Save the last statement's result to the outer register BEFORE exiting scope
        if (outerResultReg >= 0 && lastResultReg >= 0) {
            emitAliasWithTarget(outerResultReg, lastResultReg);
        } else if (outerResultReg >= 0 && lastResultReg < 0) {
            // Last statement didn't produce a result (e.g., for loop), initialize to undef
            emit(Opcodes.LOAD_UNDEF);
            emitReg(outerResultReg);
        }

        if (regexSaveReg >= 0) {
            emit(Opcodes.RESTORE_REGEX_STATE);
            emitReg(regexSaveReg);
        }

        // Exit scope restores register state.
        // Flush mortal list for non-subroutine blocks so DESTROY fires promptly
        // at scope exit. Subroutine body blocks must NOT flush — the implicit
        // return value may still be in a register and flushing could destroy it.
        exitScope(!node.getBooleanAnnotation("blockIsSubroutine"));

        if (needsLocalRestore) {
            emit(Opcodes.POP_LOCAL_LEVEL);
            emitReg(localLevelReg);
        }

        // Set lastResultReg to the outer register (or -1 if VOID context)
        lastResultReg = outerResultReg;
    }

    // =========================================================================
    // VISITOR METHODS
    // =========================================================================

    @Override
    public void visit(NumberNode node) {
        int rd = allocateOutputRegister();

        // Remove underscores which Perl allows as digit separators (e.g., 10_000_000)
        String value = node.value.replace("_", "");

        // Normalize Perl-style Inf/NaN to Java-parseable format
        // (constant folding may produce "Inf"/"-Inf" via RuntimeScalar.toString())
        if (value.equals("Inf")) value = "Infinity";
        else if (value.equals("-Inf")) value = "-Infinity";

        try {
            // Use ScalarUtils.isInteger() for consistent number parsing with compiler
            boolean isInteger = ScalarUtils.isInteger(value);

            // For 32-bit Perl emulation, check if this is a large integer
            // that needs to be stored as a string to preserve precision
            boolean isLargeInteger = !isInteger && value.matches("^-?\\d+$");

            if (isInteger) {
                // Regular integer - use LOAD_INT to create mutable scalar
                // Note: We don't use RuntimeScalarCache here because ALIAS just copies references,
                // and we need mutable scalars for variables (++, --, etc.)
                int intValue = Integer.parseInt(value);
                emit(Opcodes.LOAD_INT);
                emitReg(rd);
                emitInt(intValue);
            } else if (isLargeInteger) {
                // Large integer - store as double to match Perl 5 IV-to-NV promotion
                RuntimeScalar doubleScalar = new RuntimeScalar(Double.parseDouble(value));
                int constIdx = addToConstantPool(doubleScalar);
                emit(Opcodes.LOAD_CONST);
                emitReg(rd);
                emit(constIdx);
            } else {
                // Floating-point number - create RuntimeScalar with double value
                RuntimeScalar doubleScalar = new RuntimeScalar(Double.parseDouble(value));
                int constIdx = addToConstantPool(doubleScalar);
                emit(Opcodes.LOAD_CONST);
                emitReg(rd);
                emit(constIdx);
            }

        } catch (NumberFormatException e) {
            throw new RuntimeException("Invalid number: " + node.value, e);
        }

        lastResultReg = rd;
    }

    void handleArrayKeyValueSlice(BinaryOperatorNode node, OperatorNode leftOp) {
        int arrayReg;

        // Index/value slice: %array[indices] returns alternating index/value pairs.
        // Postfix ->%[...] is parsed into this form by the parser.

        if (leftOp.operand instanceof IdentifierNode) {
            String varName = "@" + ((IdentifierNode) leftOp.operand).name;
            if (hasVariable(varName)) {
                arrayReg = getVariableRegister(varName);
            } else {
                arrayReg = allocateRegister();
                String globalArrayName = NameNormalizer.normalizeVariableName(((IdentifierNode) leftOp.operand).name, getCurrentPackage());
                int nameIdx = addToStringPool(globalArrayName);
                emit(Opcodes.LOAD_GLOBAL_ARRAY);
                emitReg(arrayReg);
                emit(nameIdx);
            }
        } else {
            // %$arrayref[...] / %{expr}[...] / $aref->%[...] / ['a'..'z']->%[...]
            leftOp.operand.accept(this);
            int scalarRefReg = lastResultReg;
            arrayReg = allocateRegister();
            if (isStrictRefsEnabled()) {
                emitWithToken(Opcodes.DEREF_ARRAY, node.getIndex());
                emitReg(arrayReg);
                emitReg(scalarRefReg);
            } else {
                int pkgIdx = addToStringPool(getCurrentPackage());
                emitWithToken(Opcodes.DEREF_ARRAY_NONSTRICT, node.getIndex());
                emitReg(arrayReg);
                emitReg(scalarRefReg);
                emit(pkgIdx);
            }
        }

        if (!(node.right instanceof ArrayLiteralNode indicesNode)) {
            throwCompilerException("Index/value slice requires ArrayLiteralNode");
            return;
        }
        if (indicesNode.elements.isEmpty()) {
            throwCompilerException("Index/value slice requires at least one index");
            return;
        }

        // Compile indices into a list
        List<Integer> pairRegs = new ArrayList<>();
        for (Node indexElement : indicesNode.elements) {
            // Compile index in SCALAR context to ensure RuntimeScalar
            compileNode(indexElement, -1, RuntimeContextType.SCALAR);
            int indexReg = lastResultReg;

            int valueReg = allocateRegister();
            emit(Opcodes.ARRAY_GET);
            emitReg(valueReg);
            emitReg(arrayReg);
            emitReg(indexReg);

            pairRegs.add(indexReg);
            pairRegs.add(valueReg);
        }

        int listReg = allocateRegister();
        emit(Opcodes.CREATE_LIST);
        emitReg(listReg);
        emit(pairRegs.size());
        for (int r : pairRegs) {
            emitReg(r);
        }

        if (currentCallContext == RuntimeContextType.SCALAR) {
            int rd = allocateOutputRegister();
            emit(Opcodes.LIST_TO_SCALAR);
            emitReg(rd);
            emitReg(listReg);
            lastResultReg = rd;
        } else {
            lastResultReg = listReg;
        }
    }

    @Override
    public void visit(StringNode node) {
        int rd = allocateOutputRegister();
        int strIndex = addToStringPool(node.value);

        short opcode;
        if (node.isVString) {
            opcode = Opcodes.LOAD_VSTRING;
        } else if (emitterContext != null && emitterContext.symbolTable != null
                && !emitterContext.symbolTable.isStrictOptionEnabled(Strict.HINT_UTF8)
                && !emitterContext.compilerOptions.isUnicodeSource) {
            // Under `no utf8` - use BYTE_STRING, unless it contains wide characters (> 255)
            // Wide characters (like \x{100}) force the string to be UTF-8 even without `use utf8`
            boolean hasWideChars = false;
            for (int i = 0; i < node.value.length(); i++) {
                if (node.value.charAt(i) > 255) {
                    hasWideChars = true;
                    break;
                }
            }
            opcode = hasWideChars ? Opcodes.LOAD_STRING : Opcodes.LOAD_BYTE_STRING;
        } else {
            opcode = Opcodes.LOAD_STRING;
        }
        emit(opcode);
        emitReg(rd);
        emit(strIndex);

        lastResultReg = rd;
    }

    @Override
    public void visit(IdentifierNode node) {
        // Variable reference
        String varName = node.name;

        // Check if this is a captured variable (with sigil)
        // Try common sigils: $, @, %
        String[] sigils = {"$", "@", "%"};
        for (String sigil : sigils) {
            String varNameWithSigil = sigil + varName;
            if (capturedVarIndices != null && capturedVarIndices.containsKey(varNameWithSigil)) {
                // Captured variable - use its pre-allocated register
                lastResultReg = capturedVarIndices.get(varNameWithSigil);
                return;
            }
        }

        // Check if it's a lexical variable (may have sigil or not)
        if (hasVariable(varName)) {
            // Lexical variable - already has a register
            lastResultReg = getVariableRegister(varName);
        } else {
            // Try with sigils
            boolean found = false;
            for (String sigil : sigils) {
                String varNameWithSigil = sigil + varName;
                if (hasVariable(varNameWithSigil)) {
                    lastResultReg = getVariableRegister(varNameWithSigil);
                    found = true;
                    break;
                }
            }

            if (!found) {
                // Not a lexical variable - could be a global or a bareword
                // Check for strict subs violation (bareword without sigil)
                if (!varName.startsWith("$") && !varName.startsWith("@") && !varName.startsWith("%")) {
                    // Barewords ending with :: are package name constants, always allowed
                    // e.g., Tie::RefHash:: is equivalent to "Tie::RefHash"
                    if (varName.endsWith("::")) {
                        String packageName = varName.substring(0, varName.length() - 2);
                        int rd = allocateOutputRegister();
                        emit(Opcodes.LOAD_STRING);
                        emitReg(rd);
                        int strIdx = addToStringPool(packageName);
                        emit(strIdx);
                        lastResultReg = rd;
                        return;
                    }
                    // This is a bareword (no sigil)
                    if (emitterContext != null && emitterContext.symbolTable != null &&
                            emitterContext.symbolTable.isStrictOptionEnabled(Strict.HINT_STRICT_SUBS)) {
                        throwCompilerException("Bareword \"" + varName + "\" not allowed while \"strict subs\" in use");
                    }
                    // Not strict - treat bareword as string literal
                    int rd = allocateOutputRegister();
                    emit(Opcodes.LOAD_STRING);
                    emitReg(rd);
                    int strIdx = addToStringPool(varName);
                    emit(strIdx);
                    lastResultReg = rd;
                    return;
                }

                // Global variable
                // Check strict vars before accessing
                if (shouldBlockGlobalUnderStrictVars(varName)) {
                    throwCompilerException("Global symbol \"" + varName + "\" requires explicit package name");
                }

                // Strip sigil and normalize name (e.g., "$x" → "main::x")
                String bareVarName = varName.substring(1);  // Remove sigil
                String normalizedName = NameNormalizer.normalizeVariableName(bareVarName, getCurrentPackage());
                // Use allocateRegister() instead of allocateOutputRegister() because
                // LOAD_GLOBAL_SCALAR for special variables like $1 returns a proxy object.
                // The ALIAS operation is needed to copy the value before RESTORE_REGEX_STATE.
                int rd = allocateRegister();
                int nameIdx = addToStringPool(normalizedName);

                emit(Opcodes.LOAD_GLOBAL_SCALAR);
                emitReg(rd);
                emit(nameIdx);

                lastResultReg = rd;
            }
        }
    }

    /**
     * Handle array element access: $array[index]
     * Example: $ARGV[0] or $array[$i]
     */
    void handleArrayElementAccess(BinaryOperatorNode node, OperatorNode leftOp) {
        // Get the array variable name
        if (!(leftOp.operand instanceof IdentifierNode)) {
            throwCompilerException("Array element access requires identifier");
            return;
        }
        String varName = ((IdentifierNode) leftOp.operand).name;
        String arrayVarName = "@" + varName;

        // Get the array register - check closure, lexical, then global
        int arrayReg;
        if (currentSubroutineBeginId != 0 && currentSubroutineClosureVars != null
                && currentSubroutineClosureVars.contains(arrayVarName)) {
            arrayReg = allocateRegister();
            int nameIdx = addToStringPool(arrayVarName);
            emitWithToken(Opcodes.RETRIEVE_BEGIN_ARRAY, node.getIndex());
            emitReg(arrayReg);
            emit(nameIdx);
            emit(currentSubroutineBeginId);
        } else if (hasVariable(arrayVarName)) {
            arrayReg = getVariableRegister(arrayVarName);
        } else {
            arrayReg = allocateRegister();
            String globalArrayName = NameNormalizer.normalizeVariableName(
                    varName,
                    getCurrentPackage()
            );
            int nameIdx = addToStringPool(globalArrayName);
            emit(Opcodes.LOAD_GLOBAL_ARRAY);
            emitReg(arrayReg);
            emit(nameIdx);
        }

        // Compile the index expression (right side)
        if (!(node.right instanceof ArrayLiteralNode indexNode)) {
            throwCompilerException("Array subscript requires ArrayLiteralNode");
            return;
        }
        if (indexNode.elements.isEmpty()) {
            throwCompilerException("Array subscript requires at least one index");
            return;
        }

        // Handle single element access: $array[0]
        if (indexNode.elements.size() == 1) {
            Node indexExpr = indexNode.elements.get(0);
            // Compile index in SCALAR context to ensure RuntimeScalar
            compileNode(indexExpr, -1, RuntimeContextType.SCALAR);
            int indexReg = lastResultReg;

            // Emit ARRAY_GET opcode
            int rd = allocateOutputRegister();
            emit(Opcodes.ARRAY_GET);
            emitReg(rd);
            emitReg(arrayReg);
            emitReg(indexReg);

            lastResultReg = rd;
        } else {
            throwCompilerException("Multi-element array access not yet implemented");
        }
    }

    /**
     * Handle symbolic array element access: ${"name"}[index] or ${$ref}[index]
     * In Perl, ${EXPR}[index] evaluates EXPR and uses it for array element access.
     * This is different from ($scalarDeref)[index] - it allows symbolic references.
     * Example: ${"test"}[0] accesses element 0 of @test when no strict refs
     */
    void handleSymbolicArrayElementAccess(BinaryOperatorNode node, BlockNode blockNode) {
        // Compile the block expression to get the name/reference
        // The block contains the expression that yields the array name or reference
        if (blockNode.elements.isEmpty()) {
            throwCompilerException("Empty block in symbolic array access");
            return;
        }

        // Compile the block's content in scalar context
        Node blockContent = blockNode.elements.get(blockNode.elements.size() - 1);
        compileNode(blockContent, -1, RuntimeContextType.SCALAR);
        int baseReg = lastResultReg;

        // Compile the index expression
        if (!(node.right instanceof ArrayLiteralNode indexNode)) {
            throwCompilerException("Array subscript requires ArrayLiteralNode");
            return;
        }
        if (indexNode.elements.isEmpty()) {
            throwCompilerException("Array subscript requires at least one index");
            return;
        }

        // Handle single element access
        if (indexNode.elements.size() == 1) {
            Node indexExpr = indexNode.elements.get(0);
            // Use the arrayDerefGet helper which handles both strict and non-strict modes
            lastResultReg = emitArrayDerefGet(baseReg, indexExpr, node.getIndex());
        } else {
            throwCompilerException("Multi-element symbolic array access not yet implemented");
        }
    }

    /**
     * Handle hash slice operations: @hash{keys} or @$hashref{keys}
     * Must be called before automatic operand compilation to avoid compiling @ operator
     */

    /**
     * Handle array slice: @array[indices]
     * Example: @array[0,2,4] or @array[1..5]
     */
    void handleArraySlice(BinaryOperatorNode node, OperatorNode leftOp) {
        int arrayReg;

        // Get the array - either direct @array or dereferenced @$arrayref
        if (leftOp.operand instanceof IdentifierNode) {
            // Direct array slice: @array[indices]
            String varName = ((IdentifierNode) leftOp.operand).name;
            String arrayVarName = "@" + varName;

            if (currentSubroutineBeginId != 0 && currentSubroutineClosureVars != null
                    && currentSubroutineClosureVars.contains(arrayVarName)) {
                arrayReg = allocateRegister();
                int nameIdx = addToStringPool(arrayVarName);
                emitWithToken(Opcodes.RETRIEVE_BEGIN_ARRAY, node.getIndex());
                emitReg(arrayReg);
                emit(nameIdx);
                emit(currentSubroutineBeginId);
            } else if (hasVariable(arrayVarName)) {
                arrayReg = getVariableRegister(arrayVarName);
            } else {
                arrayReg = allocateRegister();
                String globalArrayName = NameNormalizer.normalizeVariableName(
                        varName,
                        getCurrentPackage()
                );
                int nameIdx = addToStringPool(globalArrayName);
                emit(Opcodes.LOAD_GLOBAL_ARRAY);
                emitReg(arrayReg);
                emit(nameIdx);
            }
        } else if (leftOp.operand instanceof OperatorNode derefOp) {
            // Array dereference slice: @$arrayref[indices]
            if (derefOp.operator.equals("$")) {
                // Compile the scalar reference
                derefOp.accept(this);
                int refReg = lastResultReg;

                // Dereference to get the array
                arrayReg = allocateRegister();
                if (isStrictRefsEnabled()) {
                    emitWithToken(Opcodes.DEREF_ARRAY, node.getIndex());
                    emitReg(arrayReg);
                    emitReg(refReg);
                } else {
                    int pkgIdx = addToStringPool(getCurrentPackage());
                    emitWithToken(Opcodes.DEREF_ARRAY_NONSTRICT, node.getIndex());
                    emitReg(arrayReg);
                    emitReg(refReg);
                    emit(pkgIdx);
                }
            } else {
                throwCompilerException("Array slice requires array or array reference");
                return;
            }
        } else if (leftOp.operand instanceof BlockNode blockNode) {
            // Array dereference slice with block: @{$arrayref}[indices]
            // Compile the block to get the array reference
            int savedContext = currentCallContext;
            currentCallContext = RuntimeContextType.SCALAR;
            blockNode.accept(this);
            currentCallContext = savedContext;
            int refReg = lastResultReg;

            // Dereference to get the array
            arrayReg = allocateRegister();
            if (isStrictRefsEnabled()) {
                emitWithToken(Opcodes.DEREF_ARRAY, node.getIndex());
                emitReg(arrayReg);
                emitReg(refReg);
            } else {
                int pkgIdx = addToStringPool(getCurrentPackage());
                emitWithToken(Opcodes.DEREF_ARRAY_NONSTRICT, node.getIndex());
                emitReg(arrayReg);
                emitReg(refReg);
                emit(pkgIdx);
            }
        } else {
            throwCompilerException("Array slice requires identifier or reference");
            return;
        }

        // Compile the indices (right side) - this is an ArrayLiteralNode
        if (!(node.right instanceof ArrayLiteralNode indicesNode)) {
            throwCompilerException("Array slice requires ArrayLiteralNode");
            return;
        }

        // Compile the indices into a list
        // The ArrayLiteralNode might contain a range operator (..) or multiple elements
        List<Integer> indexRegs = new ArrayList<>();
        for (Node indexExpr : indicesNode.elements) {
            // Each element could be a simple value or a range expression
            indexExpr.accept(this);
            indexRegs.add(lastResultReg);
        }

        // Create a RuntimeList from index registers
        int indicesListReg = allocateRegister();
        emit(Opcodes.CREATE_LIST);
        emitReg(indicesListReg);
        emit(indexRegs.size());
        for (int indexReg : indexRegs) {
            emitReg(indexReg);
        }

        // Emit ARRAY_SLICE opcode
        int rd = allocateOutputRegister();
        emit(Opcodes.ARRAY_SLICE);
        emitReg(rd);
        emitReg(arrayReg);
        emitReg(indicesListReg);

        lastResultReg = rd;
    }

    /**
     * Handle hash element access: $hash{key}
     * Example: $hash{foo} or $hash{$key}
     */
    void handleHashElementAccess(BinaryOperatorNode node, OperatorNode leftOp) {
        // Get the hash variable name
        if (!(leftOp.operand instanceof IdentifierNode)) {
            throwCompilerException("Hash element access requires identifier");
            return;
        }
        String varName = ((IdentifierNode) leftOp.operand).name;
        String hashVarName = "%" + varName;

        // Get the hash register - check closure, lexical, then global
        int hashReg;
        if (currentSubroutineBeginId != 0 && currentSubroutineClosureVars != null
                && currentSubroutineClosureVars.contains(hashVarName)) {
            hashReg = allocateRegister();
            int nameIdx = addToStringPool(hashVarName);
            emitWithToken(Opcodes.RETRIEVE_BEGIN_HASH, node.getIndex());
            emitReg(hashReg);
            emit(nameIdx);
            emit(currentSubroutineBeginId);
        } else if (hasVariable(hashVarName)) {
            hashReg = getVariableRegister(hashVarName);
        } else {
            hashReg = allocateRegister();
            String globalHashName = NameNormalizer.normalizeVariableName(
                    varName,
                    getCurrentPackage()
            );
            int nameIdx = addToStringPool(globalHashName);
            emit(Opcodes.LOAD_GLOBAL_HASH);
            emitReg(hashReg);
            emit(nameIdx);
        }

        // Compile the key expression (right side)
        if (!(node.right instanceof HashLiteralNode keyNode)) {
            throwCompilerException("Hash subscript requires HashLiteralNode");
            return;
        }
        if (keyNode.elements.isEmpty()) {
            throwCompilerException("Hash subscript requires at least one key");
            return;
        }

        // Handle single element access: $hash{key}
        if (keyNode.elements.size() == 1) {
            Node keyExpr = keyNode.elements.get(0);

            // Check if it's a bareword (IdentifierNode) - autoquote it
            if (keyExpr instanceof IdentifierNode) {
                String keyString = ((IdentifierNode) keyExpr).name;
                int keyReg = allocateRegister();
                int keyIdx = addToStringPool(keyString);
                emit(Opcodes.LOAD_STRING);
                emitReg(keyReg);
                emit(keyIdx);

                // Emit HASH_GET opcode
                int rd = allocateOutputRegister();
                emit(Opcodes.HASH_GET);
                emitReg(rd);
                emitReg(hashReg);
                emitReg(keyReg);

                lastResultReg = rd;
            } else {
                // Expression key - compile it in SCALAR context to ensure we get RuntimeScalar
                compileNode(keyExpr, -1, RuntimeContextType.SCALAR);
                int keyReg = lastResultReg;

                // Emit HASH_GET opcode
                int rd = allocateOutputRegister();
                emit(Opcodes.HASH_GET);
                emitReg(rd);
                emitReg(hashReg);
                emitReg(keyReg);

                lastResultReg = rd;
            }
        } else {
            throwCompilerException("Multi-element hash access not yet implemented");
        }
    }

    void handleHashSlice(BinaryOperatorNode node, OperatorNode leftOp) {
        int hashReg;

        // Hash slice: @hash{'key1', 'key2'} returns array of values
        // Hashref slice: @$hashref{'key1', 'key2'} dereferences then slices

        if (leftOp.operand instanceof IdentifierNode) {
            // Direct hash slice: @hash{keys}
            String varName = ((IdentifierNode) leftOp.operand).name;
            String hashVarName = "%" + varName;

            if (currentSubroutineBeginId != 0 && currentSubroutineClosureVars != null
                    && currentSubroutineClosureVars.contains(hashVarName)) {
                hashReg = allocateRegister();
                int nameIdx = addToStringPool(hashVarName);
                emitWithToken(Opcodes.RETRIEVE_BEGIN_HASH, node.getIndex());
                emitReg(hashReg);
                emit(nameIdx);
                emit(currentSubroutineBeginId);
            } else if (hasVariable(hashVarName)) {
                hashReg = getVariableRegister(hashVarName);
            } else {
                hashReg = allocateRegister();
                String globalHashName = NameNormalizer.normalizeVariableName(
                        varName,
                        getCurrentPackage()
                );
                int nameIdx = addToStringPool(globalHashName);
                emit(Opcodes.LOAD_GLOBAL_HASH);
                emitReg(hashReg);
                emit(nameIdx);
            }
        } else if (leftOp.operand instanceof OperatorNode) {
            // Hashref slice: @$hashref{keys}
            // Compile the reference and dereference it
            leftOp.operand.accept(this);
            int scalarRefReg = lastResultReg;

            // Dereference to get the hash
            hashReg = allocateRegister();
            if (isStrictRefsEnabled()) {
                emitWithToken(Opcodes.DEREF_HASH, node.getIndex());
                emitReg(hashReg);
                emitReg(scalarRefReg);
            } else {
                int pkgIdx = addToStringPool(getCurrentPackage());
                emitWithToken(Opcodes.DEREF_HASH_NONSTRICT, node.getIndex());
                emitReg(hashReg);
                emitReg(scalarRefReg);
                emit(pkgIdx);
            }
        } else if (leftOp.operand instanceof BlockNode blockNode) {
            // Hash dereference slice with block: @{$hashref}{keys}
            // Compile the block to get the hash reference
            int savedContext = currentCallContext;
            currentCallContext = RuntimeContextType.SCALAR;
            blockNode.accept(this);
            currentCallContext = savedContext;
            int refReg = lastResultReg;

            // Dereference to get the hash
            hashReg = allocateRegister();
            if (isStrictRefsEnabled()) {
                emitWithToken(Opcodes.DEREF_HASH, node.getIndex());
                emitReg(hashReg);
                emitReg(refReg);
            } else {
                int pkgIdx = addToStringPool(getCurrentPackage());
                emitWithToken(Opcodes.DEREF_HASH_NONSTRICT, node.getIndex());
                emitReg(hashReg);
                emitReg(refReg);
                emit(pkgIdx);
            }
        } else {
            throwCompilerException("Hash slice requires hash variable or reference");
            return;
        }

        // Get the keys from HashLiteralNode
        if (!(node.right instanceof HashLiteralNode)) {
            throwCompilerException("Hash slice requires HashLiteralNode");
        }
        HashLiteralNode keysNode = (HashLiteralNode) node.right;
        if (keysNode.elements.isEmpty()) {
            throwCompilerException("Hash slice requires at least one key");
        }

        // Compile all keys into a list
        List<Integer> keyRegs = new ArrayList<>();
        for (Node keyElement : keysNode.elements) {
            if (keyElement instanceof IdentifierNode) {
                // Bareword key - autoquote
                String keyString = ((IdentifierNode) keyElement).name;
                int keyReg = allocateRegister();
                int keyIdx = addToStringPool(keyString);
                emit(Opcodes.LOAD_STRING);
                emitReg(keyReg);
                emit(keyIdx);
                keyRegs.add(keyReg);
            } else {
                // Expression key - use default context to allow arrays to expand
                keyElement.accept(this);
                keyRegs.add(lastResultReg);
            }
        }

        // Create a RuntimeList from key registers
        int keysListReg = allocateRegister();
        emit(Opcodes.CREATE_LIST);
        emitReg(keysListReg);
        emit(keyRegs.size());
        for (int keyReg : keyRegs) {
            emitReg(keyReg);
        }

        // Emit direct opcode HASH_SLICE
        int rdSlice = allocateRegister();
        emit(Opcodes.HASH_SLICE);
        emitReg(rdSlice);
        emitReg(hashReg);
        emitReg(keysListReg);

        lastResultReg = rdSlice;
    }

    void handleHashKeyValueSlice(BinaryOperatorNode node, OperatorNode leftOp) {
        int hashReg;

        // Key/value slice: %hash{keys} returns alternating key/value pairs.
        // Postfix ->%{...} is parsed into this form by the parser.

        if (leftOp.operand instanceof IdentifierNode) {
            // Direct key/value slice: %hash{keys}
            String varName = ((IdentifierNode) leftOp.operand).name;
            String hashVarName = "%" + varName;

            if (currentSubroutineBeginId != 0 && currentSubroutineClosureVars != null
                    && currentSubroutineClosureVars.contains(hashVarName)) {
                hashReg = allocateRegister();
                int nameIdx = addToStringPool(hashVarName);
                emitWithToken(Opcodes.RETRIEVE_BEGIN_HASH, node.getIndex());
                emitReg(hashReg);
                emit(nameIdx);
                emit(currentSubroutineBeginId);
            } else if (hasVariable(hashVarName)) {
                hashReg = getVariableRegister(hashVarName);
            } else {
                hashReg = allocateRegister();
                String globalHashName = NameNormalizer.normalizeVariableName(varName, getCurrentPackage());
                int nameIdx = addToStringPool(globalHashName);
                emit(Opcodes.LOAD_GLOBAL_HASH);
                emitReg(hashReg);
                emit(nameIdx);
            }
        } else {
            // %$hashref{keys} / %{expr}{keys} / $hashref->%{keys}
            // Compile operand to a scalar and then dereference as hash.
            leftOp.operand.accept(this);
            int scalarRefReg = lastResultReg;
            hashReg = allocateRegister();
            if (isStrictRefsEnabled()) {
                emitWithToken(Opcodes.DEREF_HASH, node.getIndex());
                emitReg(hashReg);
                emitReg(scalarRefReg);
            } else {
                int pkgIdx = addToStringPool(getCurrentPackage());
                emitWithToken(Opcodes.DEREF_HASH_NONSTRICT, node.getIndex());
                emitReg(hashReg);
                emitReg(scalarRefReg);
                emit(pkgIdx);
            }
        }

        if (!(node.right instanceof HashLiteralNode keysNode)) {
            throwCompilerException("Key/value slice requires HashLiteralNode");
            return;
        }

        if (keysNode.elements.isEmpty()) {
            throwCompilerException("Key/value slice requires at least one key");
            return;
        }

        List<Integer> keyRegs = new ArrayList<>();
        for (Node keyElement : keysNode.elements) {
            if (keyElement instanceof IdentifierNode) {
                String keyString = ((IdentifierNode) keyElement).name;
                int keyReg = allocateRegister();
                int keyIdx = addToStringPool(keyString);
                emit(Opcodes.LOAD_STRING);
                emitReg(keyReg);
                emit(keyIdx);
                keyRegs.add(keyReg);
            } else {
                // Expression key - use default context to allow arrays to expand
                keyElement.accept(this);
                keyRegs.add(lastResultReg);
            }
        }

        int keysListReg = allocateRegister();
        emit(Opcodes.CREATE_LIST);
        emitReg(keysListReg);
        emit(keyRegs.size());
        for (int keyReg : keyRegs) {
            emitReg(keyReg);
        }

        int rd = allocateOutputRegister();
        emit(Opcodes.HASH_KEYVALUE_SLICE);
        emitReg(rd);
        emitReg(hashReg);
        emitReg(keysListReg);
        if (currentCallContext == RuntimeContextType.SCALAR) {
            int scalarReg = allocateRegister();
            emit(Opcodes.LIST_TO_SCALAR);
            emitReg(scalarReg);
            emitReg(rd);
            lastResultReg = scalarReg;
        } else {
            lastResultReg = rd;
        }
    }

    /**
     * Handle compound assignment operators: +=, -=, *=, /=, %=
     * Example: $sum += $elem means $sum = $sum + $elem
     */
    void handleCompoundAssignment(BinaryOperatorNode node) {
        String op = node.operator;

        // Short-circuit operators (//=, ||=, &&=) must NOT evaluate the RHS eagerly.
        // The RHS should only be evaluated if the short-circuit condition is not met.
        if (op.equals("//=") || op.equals("||=") || op.equals("&&=")) {
            handleShortCircuitAssignment(node);
            return;
        }

        // Compile the right operand first (the value to add/subtract/etc.)
        compileNode(node.right, -1, RuntimeContextType.SCALAR);
        int valueReg = lastResultReg;

        // Get the left operand register (the variable or expression being assigned to)
        int targetReg = compileLhsForCompoundAssignment(node);

        // Emit the appropriate compound assignment opcode
        switch (op) {
            case "+=" -> emit(Opcodes.ADD_ASSIGN);
            case "-=" -> emit(Opcodes.SUBTRACT_ASSIGN);
            case "*=" -> emit(Opcodes.MULTIPLY_ASSIGN);
            case "/=" -> emit(isIntegerEnabled() ? Opcodes.INTEGER_DIV_ASSIGN : Opcodes.DIVIDE_ASSIGN);
            case "%=" -> emit(isIntegerEnabled() ? Opcodes.INTEGER_MOD_ASSIGN : Opcodes.MODULUS_ASSIGN);
            case ".=" -> emit(Opcodes.STRING_CONCAT_ASSIGN);
            case "&=", "binary&=" -> emit(Opcodes.BITWISE_AND_ASSIGN);  // Numeric bitwise AND
            case "|=", "binary|=" -> emit(Opcodes.BITWISE_OR_ASSIGN);   // Numeric bitwise OR
            case "^=", "binary^=" -> emit(Opcodes.BITWISE_XOR_ASSIGN);  // Numeric bitwise XOR
            case "&.=" -> emit(Opcodes.STRING_BITWISE_AND_ASSIGN);      // String bitwise AND
            case "|.=" -> emit(Opcodes.STRING_BITWISE_OR_ASSIGN);       // String bitwise OR
            case "^.=" -> emit(Opcodes.STRING_BITWISE_XOR_ASSIGN);      // String bitwise XOR
            case "x=" -> emit(Opcodes.REPEAT_ASSIGN);                   // String repetition
            case "**=" -> emit(Opcodes.POW_ASSIGN);                     // Exponentiation
            case "<<=" -> emit(isIntegerEnabled() ? Opcodes.INTEGER_LEFT_SHIFT_ASSIGN : Opcodes.LEFT_SHIFT_ASSIGN);
            case ">>=" -> emit(isIntegerEnabled() ? Opcodes.INTEGER_RIGHT_SHIFT_ASSIGN : Opcodes.RIGHT_SHIFT_ASSIGN);
            default -> {
                throwCompilerException("Unknown compound assignment operator: " + op);
                return;
            }
        }

        emitReg(targetReg);
        emitReg(valueReg);

        // The result is stored in targetReg
        lastResultReg = targetReg;
    }

    /**
     * Compile the left-hand side of a compound assignment and return its register.
     */
    private int compileLhsForCompoundAssignment(BinaryOperatorNode node) {
        int targetReg;

        // Check if left side is a simple variable reference
        if (node.left instanceof OperatorNode leftOp) {
            if (leftOp.operator.equals("$") && leftOp.operand instanceof IdentifierNode) {
                // Simple scalar variable: $x += 5
                String varName = "$" + ((IdentifierNode) leftOp.operand).name;

                if (hasVariable(varName)) {
                    // Lexical variable - use its register directly
                    targetReg = getVariableRegister(varName);
                } else {
                    // Global variable - need to load it first
                    targetReg = allocateRegister();
                    // Strip sigil before normalizing (varName is "$x", need "x" for normalize)
                    String normalizedName = NameNormalizer.normalizeVariableName(
                            varName.substring(1), getCurrentPackage());
                    int nameIdx = addToStringPool(normalizedName);
                    emit(Opcodes.LOAD_GLOBAL_SCALAR);
                    emitReg(targetReg);
                    emit(nameIdx);
                }
            } else {
                // Other operator (not simple variable) - compile as expression in SCALAR context
                compileNode(node.left, -1, RuntimeContextType.SCALAR);
                targetReg = lastResultReg;
            }
        } else {
            // Not an OperatorNode (could be BinaryOperatorNode like ($x &= $y))
            // Compile the left side as an expression in SCALAR context
            compileNode(node.left, -1, RuntimeContextType.SCALAR);
            targetReg = lastResultReg;
        }

        return targetReg;
    }

    /**
     * Handle short-circuit compound assignment operators (//=, ||=, &&=).
     * These must NOT evaluate the RHS if the short-circuit condition is met:
     * - //= : skip RHS if LHS is defined
     * - ||= : skip RHS if LHS is truthy
     * - &&= : skip RHS if LHS is falsy
     */
    private void handleShortCircuitAssignment(BinaryOperatorNode node) {
        String op = node.operator;

        // Step 1: Compile LHS first to get the target register
        int targetReg = compileLhsForCompoundAssignment(node);

        // Step 1.5: Vivify the LHS proxy so hash/array entries exist before the condition check.
        // This matches Perl 5's behavior where $h{key} ||= expr creates the hash entry
        // (with undef value) during lvalue resolution, before evaluating the boolean condition.
        // Without this, `scalar keys %$h` in the RHS would see 0 keys instead of 1.
        emit(Opcodes.VIVIFY_LVALUE);
        emitReg(targetReg);

        // Step 2: Emit conditional jump to skip RHS evaluation
        int jumpPos;
        if (op.equals("//=")) {
            // For //=, check if LHS is defined using DEFINED opcode
            int condReg = allocateRegister();
            emit(Opcodes.DEFINED);
            emitReg(condReg);
            emitReg(targetReg);
            jumpPos = bytecode.size();
            emit(Opcodes.GOTO_IF_TRUE);
            emitReg(condReg);
            emitInt(0); // placeholder for end target
        } else if (op.equals("||=")) {
            // For ||=, skip RHS if LHS is truthy
            jumpPos = bytecode.size();
            emit(Opcodes.GOTO_IF_TRUE);
            emitReg(targetReg);
            emitInt(0); // placeholder for end target
        } else {
            // For &&=, skip RHS if LHS is falsy
            jumpPos = bytecode.size();
            emit(Opcodes.GOTO_IF_FALSE);
            emitReg(targetReg);
            emitInt(0); // placeholder for end target
        }

        // Step 3: Compile RHS (only executed if short-circuit didn't trigger)
        compileNode(node.right, -1, RuntimeContextType.SCALAR);
        int valueReg = lastResultReg;

        // Step 4: Assign RHS to LHS
        emit(Opcodes.SET_SCALAR);
        emitReg(targetReg);
        emitReg(valueReg);

        // Step 5: Patch the forward jump to skip past the RHS evaluation
        int endPc = bytecode.size();
        patchIntOffset(jumpPos + 2, endPc);

        lastResultReg = targetReg;
    }

    // =========================================================================
    // SUPEROPERATOR HELPERS
    // These methods centralize the logic for emitting superoperators vs
    // regular instruction sequences. Called from both BytecodeCompiler and
    // CompileBinaryOperator to handle all code paths consistently.
    // =========================================================================

    /**
     * Emit hash dereference + element access, using HASH_DEREF_FETCH superoperator when possible.
     * Handles: $hashref->{key} or expr{key} patterns
     *
     * @param baseReg   Register containing the hashref
     * @param keyExpr   The key expression node
     * @param tokenIndex Token index for error reporting
     * @return The result register containing the hash element
     */
    int emitHashDerefGet(int baseReg, Node keyExpr, int tokenIndex) {
        // Try to use superoperator for constant string keys
        String constantKey = getConstantStringKey(keyExpr);
        if (constantKey != null) {
            int keyIdx = addToStringPool(constantKey);
            int rd = allocateOutputRegister();
            if (isStrictRefsEnabled()) {
                // SUPEROPERATOR: Constant key + strict refs - use HASH_DEREF_FETCH
                emit(Opcodes.HASH_DEREF_FETCH);
                emitReg(rd);
                emitReg(baseReg);
                emit(keyIdx);
            } else {
                // SUPEROPERATOR: Constant key + non-strict - use HASH_DEREF_FETCH_NONSTRICT
                int pkgIdx = addToStringPool(getCurrentPackage());
                emit(Opcodes.HASH_DEREF_FETCH_NONSTRICT);
                emitReg(rd);
                emitReg(baseReg);
                emit(keyIdx);
                emit(pkgIdx);
            }
            return rd;
        }

        // General case: compile key, dereference, then get
        int keyReg;
        if (keyExpr instanceof IdentifierNode) {
            // Bareword key - autoquote it
            String keyString = ((IdentifierNode) keyExpr).name;
            keyReg = allocateRegister();
            int keyIdx = addToStringPool(keyString);
            emit(Opcodes.LOAD_STRING);
            emitReg(keyReg);
            emit(keyIdx);
        } else {
            compileNode(keyExpr, -1, RuntimeContextType.SCALAR);
            keyReg = lastResultReg;
        }

        int hashReg = allocateRegister();
        if (isStrictRefsEnabled()) {
            emitWithToken(Opcodes.DEREF_HASH, tokenIndex);
            emitReg(hashReg);
            emitReg(baseReg);
        } else {
            int pkgIdx = addToStringPool(getCurrentPackage());
            emitWithToken(Opcodes.DEREF_HASH_NONSTRICT, tokenIndex);
            emitReg(hashReg);
            emitReg(baseReg);
            emit(pkgIdx);
        }

        int rd = allocateOutputRegister();
        emit(Opcodes.HASH_GET);
        emitReg(rd);
        emitReg(hashReg);
        emitReg(keyReg);
        return rd;
    }

    /**
     * Emit array dereference + element access, using ARRAY_DEREF_FETCH superoperator when possible.
     * Handles: $arrayref->[n] or expr[n] patterns
     *
     * @param baseReg    Register containing the arrayref
     * @param indexExpr  The index expression node
     * @param tokenIndex Token index for error reporting
     * @return The result register containing the array element
     */
    int emitArrayDerefGet(int baseReg, Node indexExpr, int tokenIndex) {
        // Try to use superoperator for integer literal indices
        Integer constantIndex = getConstantIntegerIndex(indexExpr);
        if (constantIndex != null) {
            int rd = allocateOutputRegister();
            if (isStrictRefsEnabled()) {
                // SUPEROPERATOR: Integer literal + strict refs - use ARRAY_DEREF_FETCH
                emit(Opcodes.ARRAY_DEREF_FETCH);
                emitReg(rd);
                emitReg(baseReg);
                emitInt(constantIndex);
            } else {
                // SUPEROPERATOR: Integer literal + non-strict - use ARRAY_DEREF_FETCH_NONSTRICT
                int pkgIdx = addToStringPool(getCurrentPackage());
                emit(Opcodes.ARRAY_DEREF_FETCH_NONSTRICT);
                emitReg(rd);
                emitReg(baseReg);
                emitInt(constantIndex);
                emit(pkgIdx);
            }
            return rd;
        }

        // General case: compile index, dereference, then get
        compileNode(indexExpr, -1, RuntimeContextType.SCALAR);
        int indexReg = lastResultReg;

        int arrayReg = allocateRegister();
        if (isStrictRefsEnabled()) {
            emitWithToken(Opcodes.DEREF_ARRAY, tokenIndex);
            emitReg(arrayReg);
            emitReg(baseReg);
        } else {
            int pkgIdx = addToStringPool(getCurrentPackage());
            emitWithToken(Opcodes.DEREF_ARRAY_NONSTRICT, tokenIndex);
            emitReg(arrayReg);
            emitReg(baseReg);
            emit(pkgIdx);
        }

        int rd = allocateOutputRegister();
        emit(Opcodes.ARRAY_GET);
        emitReg(rd);
        emitReg(arrayReg);
        emitReg(indexReg);
        return rd;
    }

    /**
     * Extract a constant string key from a key expression node.
     * Returns the string value for IdentifierNode (bareword) or StringNode,
     * null otherwise.
     */
    private String getConstantStringKey(Node keyExpr) {
        if (keyExpr instanceof IdentifierNode idNode) {
            return idNode.name;
        } else if (keyExpr instanceof StringNode strNode) {
            return strNode.value;
        }
        return null;
    }

    /**
     * Extract a constant integer index from an index expression node.
     * Returns the integer value for NumberNode with valid integer,
     * null otherwise.
     */
    private Integer getConstantIntegerIndex(Node indexExpr) {
        if (indexExpr instanceof NumberNode numNode) {
            String value = numNode.value.replace("_", "");
            try {
                if (ScalarUtils.isInteger(value)) {
                    return Integer.parseInt(value);
                }
            } catch (NumberFormatException e) {
                // Not a valid integer
            }
        }
        return null;
    }

    /**
     * Handle general array access: expr[index]
     * Example: $matrix[1][0] where $matrix[1] returns an arrayref
     * Uses superoperators when the index is an integer literal.
     * NOTE: RuntimeList cases like (caller)[0] are handled by ListNode transformation
     * in CompileBinaryOperator.java before reaching this method.
     */
    void handleGeneralArrayAccess(BinaryOperatorNode node) {
        // Compile the left side (the expression that should yield an array or arrayref)
        // Use SCALAR context since we expect an arrayref (ListNode cases are transformed earlier)
        compileNode(node.left, -1, RuntimeContextType.SCALAR);
        int baseReg = lastResultReg;

        // Compile the index expression (right side)
        if (!(node.right instanceof ArrayLiteralNode indexNode)) {
            throwCompilerException("Array subscript requires ArrayLiteralNode");
            return;
        }
        if (indexNode.elements.isEmpty()) {
            throwCompilerException("Array subscript requires at least one index");
            return;
        }

        // Handle single element access
        if (indexNode.elements.size() == 1) {
            Node indexExpr = indexNode.elements.get(0);
            // Use superoperator helper - handles both constant and variable indices
            lastResultReg = emitArrayDerefGet(baseReg, indexExpr, node.getIndex());
        } else {
            throwCompilerException("Multi-element array access not yet implemented");
        }
    }

    /**
     * Handle general hash access: expr{key}
     * Example: $hash{outer}{inner} where $hash{outer} returns a hashref
     * Uses superoperators when the key is a constant string.
     */
    void handleGeneralHashAccess(BinaryOperatorNode node) {
        // Compile the left side (the expression that should yield a hash or hashref)
        node.left.accept(this);
        int baseReg = lastResultReg;

        // Compile the key expression (right side)
        if (!(node.right instanceof HashLiteralNode keyNode)) {
            throwCompilerException("Hash subscript requires HashLiteralNode");
            return;
        }
        if (keyNode.elements.isEmpty()) {
            throwCompilerException("Hash subscript requires at least one key");
            return;
        }

        // Handle single element access
        if (keyNode.elements.size() == 1) {
            Node keyExpr = keyNode.elements.get(0);

            // Check if this is a glob slot access: *X{key}
            // In this case, node.left is an OperatorNode with operator "*"
            boolean isGlobSlotAccess = (node.left instanceof OperatorNode) &&
                    ((OperatorNode) node.left).operator.equals("*");

            if (isGlobSlotAccess) {
                // Compile the key for glob slot access
                int keyReg;
                if (keyExpr instanceof IdentifierNode) {
                    String keyString = ((IdentifierNode) keyExpr).name;
                    keyReg = allocateRegister();
                    int keyIdx = addToStringPool(keyString);
                    emit(Opcodes.LOAD_STRING);
                    emitReg(keyReg);
                    emit(keyIdx);
                } else {
                    compileNode(keyExpr, -1, RuntimeContextType.SCALAR);
                    keyReg = lastResultReg;
                }

                // For glob slot access, call hashDerefGetNonStrict directly
                // This uses RuntimeGlob's override which accesses the slot without dereferencing
                int rd = allocateOutputRegister();
                emit(Opcodes.GLOB_SLOT_GET);
                emitReg(rd);
                emitReg(baseReg);
                emitReg(keyReg);

                lastResultReg = rd;
            } else {
                // Normal hash access: use superoperator helper
                // The helper handles both RuntimeHash and RuntimeScalar with hashref
                lastResultReg = emitHashDerefGet(baseReg, keyExpr, node.getIndex());
            }
        } else {
            throwCompilerException("Multi-element hash access not yet implemented");
        }
    }

    /**
     * Handle push/unshift operators: push @array, values or unshift @array, values
     * Example: push @arr, 1, 2, 3
     */
    void handlePushUnshift(BinaryOperatorNode node) {
        boolean isPush = node.operator.equals("push");

        // Left operand is the array (@array or @$arrayref)
        // Right operand is the list of values to push/unshift

        // Get the array
        int arrayReg;
        if (node.left instanceof OperatorNode leftOp) {

            if (leftOp.operator.equals("@") && leftOp.operand instanceof IdentifierNode) {
                // Direct array: @array
                String varName = ((IdentifierNode) leftOp.operand).name;
                String arrayVarName = "@" + varName;

                // Get array register - check lexical first, then global
                if (hasVariable(arrayVarName)) {
                    arrayReg = getVariableRegister(arrayVarName);
                } else {
                    // Global array - load it
                    arrayReg = allocateRegister();
                    String globalArrayName = NameNormalizer.normalizeVariableName(
                            varName,
                            getCurrentPackage()
                    );
                    int nameIdx = addToStringPool(globalArrayName);
                    emit(Opcodes.LOAD_GLOBAL_ARRAY);
                    emitReg(arrayReg);
                    emit(nameIdx);
                }
            } else if (leftOp.operator.equals("@") && !(leftOp.operand instanceof IdentifierNode)) {
                // Array dereference: @$arrayref or @{expr}
                // Evaluate the operand expression to get the reference, then deref
                compileNode(leftOp.operand, -1, RuntimeContextType.SCALAR);
                int refReg = lastResultReg;

                // Dereference to get the array
                arrayReg = allocateRegister();
                if (isStrictRefsEnabled()) {
                    emitWithToken(Opcodes.DEREF_ARRAY, node.getIndex());
                    emitReg(arrayReg);
                    emitReg(refReg);
                } else {
                    int pkgIdx = addToStringPool(getCurrentPackage());
                    emitWithToken(Opcodes.DEREF_ARRAY_NONSTRICT, node.getIndex());
                    emitReg(arrayReg);
                    emitReg(refReg);
                    emit(pkgIdx);
                }
            } else {
                throwCompilerException("push/unshift requires array or array reference");
                return;
            }
        } else {
            throwCompilerException("push/unshift requires array operand");
            return;
        }

        // Compile the values to push/unshift (right operand) in list context
        compileNode(node.right, -1, RuntimeContextType.LIST);
        int valuesReg = lastResultReg;

        // Emit ARRAY_PUSH or ARRAY_UNSHIFT opcode
        if (isPush) {
            emit(Opcodes.ARRAY_PUSH);
        } else {
            emit(Opcodes.ARRAY_UNSHIFT);
        }
        emitReg(arrayReg);
        emitReg(valuesReg);

        // push/unshift return the new array size in scalar context
        int rd = allocateOutputRegister();
        emit(Opcodes.ARRAY_SIZE);
        emitReg(rd);
        emitReg(arrayReg);
        lastResultReg = rd;
    }

    @Override
    public void visit(BinaryOperatorNode node) {
        CompileBinaryOperator.visitBinaryOperator(this, node);
    }

    /**
     * Compile variable declaration operators (my, our, local, state).
     * Extracted to reduce visit(OperatorNode) bytecode size.
     */
    void compileVariableDeclaration(OperatorNode node, String op) {
        if (op.equals("my") || op.equals("state")) {
            // my $x / my @x / my %x / state $x / state @x / state %x - variable declaration
            // The operand will be OperatorNode("$"/"@"/"%", IdentifierNode("x"))
            if (node.operand instanceof OperatorNode sigilOp) {
                String sigil = sigilOp.operator;

                if (sigilOp.operand instanceof IdentifierNode) {
                    String varName = sigil + ((IdentifierNode) sigilOp.operand).name;

                    // Check if this is a declared reference (my \$x or state \$x)
                    boolean isDeclaredReference = node.annotations != null &&
                            Boolean.TRUE.equals(node.annotations.get("isDeclaredReference"));

                    Integer beginId = RuntimeCode.evalBeginIds.get(sigilOp);
                    if (beginId != null) {
                        // BEGIN-captured variable: use RETRIEVE_BEGIN_* (destructive removal from global storage)
                        int persistId = beginId;
                        int reg = allocateRegister();
                        int nameIdx = addToStringPool(varName);

                        switch (sigil) {
                            case "$" -> {
                                emitWithToken(Opcodes.RETRIEVE_BEGIN_SCALAR, node.getIndex());
                                emitReg(reg);
                                emit(nameIdx);
                                emit(persistId);
                                registerVariable(varName, reg);
                            }
                            case "@" -> {
                                emitWithToken(Opcodes.RETRIEVE_BEGIN_ARRAY, node.getIndex());
                                emitReg(reg);
                                emit(nameIdx);
                                emit(persistId);
                                registerVariable(varName, reg);
                            }
                            case "%" -> {
                                emitWithToken(Opcodes.RETRIEVE_BEGIN_HASH, node.getIndex());
                                emitReg(reg);
                                emit(nameIdx);
                                emit(persistId);
                                registerVariable(varName, reg);
                            }
                            default -> throwCompilerException("Unsupported variable type: " + sigil);
                        }

                        // If this is a declared reference, create a reference to it
                        if (isDeclaredReference && currentCallContext != RuntimeContextType.VOID) {
                            int refReg = allocateRegister();
                            emit(Opcodes.CREATE_REF);
                            emitReg(refReg);
                            emitReg(reg);
                            lastResultReg = refReg;
                        } else {
                            lastResultReg = reg;
                        }
                        return;
                    }
                    if (op.equals("state")) {
                        // State variable without initializer: use STATE_INIT_* (non-destructive)
                        // This preserves the variable across subroutine calls
                        int persistId = sigilOp.id;
                        int reg = allocateRegister();
                        int nameIdx = addToStringPool(varName);

                        // Allocate a register for the undef/empty initial value
                        int undefReg = allocateRegister();

                        switch (sigil) {
                            case "$" -> {
                                emit(Opcodes.LOAD_UNDEF);
                                emitReg(undefReg);
                                emitWithToken(Opcodes.STATE_INIT_SCALAR, node.getIndex());
                                emitReg(reg);
                                emitReg(undefReg);
                                emit(nameIdx);
                                emit(persistId);
                                registerVariable(varName, reg);
                            }
                            case "@" -> {
                                emit(Opcodes.NEW_ARRAY);
                                emitReg(undefReg);
                                emitWithToken(Opcodes.STATE_INIT_ARRAY, node.getIndex());
                                emitReg(reg);
                                emitReg(undefReg);
                                emit(nameIdx);
                                emit(persistId);
                                registerVariable(varName, reg);
                            }
                            case "%" -> {
                                emit(Opcodes.NEW_HASH);
                                emitReg(undefReg);
                                emitWithToken(Opcodes.STATE_INIT_HASH, node.getIndex());
                                emitReg(reg);
                                emitReg(undefReg);
                                emit(nameIdx);
                                emit(persistId);
                                registerVariable(varName, reg);
                            }
                            default -> throwCompilerException("Unsupported variable type: " + sigil);
                        }

                        // Runtime attribute dispatch for state variables with attributes
                        emitVarAttrsIfNeeded(node, reg, sigil);

                        // If this is a declared reference, create a reference to it
                        if (isDeclaredReference && currentCallContext != RuntimeContextType.VOID) {
                            int refReg = allocateRegister();
                            emit(Opcodes.CREATE_REF);
                            emitReg(refReg);
                            emitReg(reg);
                            lastResultReg = refReg;
                        } else {
                            lastResultReg = reg;
                        }
                        return;
                    }

                    // Regular lexical variable (not captured, not state)
                    int reg = addVariable(varName, "my");

                    // Normal initialization: load undef/empty array/empty hash
                    switch (sigil) {
                        case "$" -> {
                            emit(Opcodes.LOAD_UNDEF);
                            emitReg(reg);
                        }
                        case "@" -> {
                            emit(Opcodes.NEW_ARRAY);
                            emitReg(reg);
                        }
                        case "%" -> {
                            emit(Opcodes.NEW_HASH);
                            emitReg(reg);
                        }
                        default -> throwCompilerException("Unsupported variable type: " + sigil);
                    }

                    // Runtime attribute dispatch for my variables with attributes
                    emitVarAttrsIfNeeded(node, reg, sigil);

                    // If this is a declared reference, create a reference to it
                    if (isDeclaredReference && currentCallContext != RuntimeContextType.VOID) {
                        int refReg = allocateRegister();
                        emit(Opcodes.CREATE_REF);
                        emitReg(refReg);
                        emitReg(reg);
                        lastResultReg = refReg;
                    } else {
                        lastResultReg = reg;
                    }
                    return;
                }

                // Handle my \\$x - reference to a declared reference (double backslash)
                if (sigil.equals("\\")) {
                    boolean isDeclaredReference = node.annotations != null &&
                            Boolean.TRUE.equals(node.annotations.get("isDeclaredReference"));

                    if (isDeclaredReference) {
                        // my \\$x means: declare a reference variable (my \$x) and then take a reference to that.
                        // Compile the inner declared-ref my in current context, then wrap with CREATE_REF.
                        OperatorNode innerMy = new OperatorNode(op, sigilOp.operand, node.getIndex());
                        innerMy.setAnnotation("isDeclaredReference", true);
                        innerMy.accept(this);
                        int innerReg = lastResultReg;

                        if (currentCallContext != RuntimeContextType.VOID && innerReg != -1) {
                            int refReg = allocateRegister();
                            emit(Opcodes.CREATE_REF);
                            emitReg(refReg);
                            emitReg(innerReg);
                            lastResultReg = refReg;
                        }
                        return;
                    }
                }
            } else if (node.operand instanceof ListNode listNode) {
                // my ($x, $y, @rest) - list of variable declarations
                List<Integer> varRegs = new ArrayList<>();
                List<Boolean> wrapWithRef = new ArrayList<>();

                // Check if this is a declared reference (my \($x, $y))
                boolean isDeclaredReference = node.annotations != null &&
                        Boolean.TRUE.equals(node.annotations.get("isDeclaredReference"));

                // Track if we found any backslash operators inside the list (my (\($x, $y)))
                // Note: backslash inside the list should only affect that element, not the whole list.
                boolean foundBackslashInList = false;

                for (Node element : listNode.elements) {
                    if (element instanceof OperatorNode sigilOp) {
                        String sigil = sigilOp.operator;

                        // Handle backslash operator (reference constructor): my (\$x) or my (\($x, $y))
                        if (sigil.equals("\\")) {
                            // Parser may represent element-level double-backslash (\\$i) as a single backslash
                            // node annotated with declaredReferenceOriginalSigil="\\".
                            Object elementOriginalSigilObj = sigilOp.annotations != null
                                    ? sigilOp.annotations.get("declaredReferenceOriginalSigil")
                                    : null;
                            if ("\\".equals(elementOriginalSigilObj) && sigilOp.getBooleanAnnotation("isDeclaredReference")
                                    && sigilOp.operand instanceof OperatorNode varNode
                                    && "$@%".contains(varNode.operator)
                                    && varNode.operand instanceof IdentifierNode idNode) {
                                String baseName = idNode.name;

                                // Declare the scalar reference variable $name
                                String scalarName = "$" + baseName;
                                int scalarReg = addVariable(scalarName, op);
                                emit(Opcodes.LOAD_UNDEF);
                                emitReg(scalarReg);

                                // Allocate/initialize the underlying storage and create a reference to it
                                int declaredReg;
                                if ("$".equals(varNode.operator)) {
                                    declaredReg = allocateRegister();
                                    emit(Opcodes.LOAD_UNDEF);
                                    emitReg(declaredReg);
                                } else {
                                    String declaredVarName = varNode.operator + baseName;
                                    declaredReg = addVariable(declaredVarName, op);
                                    if ("@".equals(varNode.operator)) {
                                        emit(Opcodes.NEW_ARRAY);
                                        emitReg(declaredReg);
                                    } else {
                                        emit(Opcodes.NEW_HASH);
                                        emitReg(declaredReg);
                                    }
                                }

                                int refReg = allocateRegister();
                                emit(Opcodes.CREATE_REF);
                                emitReg(refReg);
                                emitReg(declaredReg);
                                emit(Opcodes.SET_SCALAR);
                                emitReg(scalarReg);
                                emitReg(refReg);

                                // Return a reference to $name (ref-to-ref semantics)
                                int scalarRefReg = allocateRegister();
                                emit(Opcodes.CREATE_REF);
                                emitReg(scalarRefReg);
                                emitReg(scalarReg);
                                varRegs.add(scalarRefReg);
                                wrapWithRef.add(false);
                                continue;
                            }

                            // Check if it's a double backslash first: my (\\$x)
                            if (sigilOp.operand instanceof OperatorNode innerBackslash &&
                                    innerBackslash.operator.equals("\\")) {
                                // Double backslash: my (\\$x)
                                // This creates a reference to a reference
                                // We handle this completely here and add the final result to varRegs
                                // Don't set foundBackslashInList since we're creating references ourselves

                                // Recursively compile the inner part
                                // Create a temporary my node for the inner backslash
                                OperatorNode innerMy = new OperatorNode("my", innerBackslash, node.getIndex());
                                if (node.annotations != null && Boolean.TRUE.equals(node.annotations.get("isDeclaredReference"))) {
                                    innerMy.setAnnotation("isDeclaredReference", true);
                                }
                                innerMy.accept(this);

                                // The inner result is in lastResultReg, now create a reference to it
                                if (currentCallContext != RuntimeContextType.VOID && lastResultReg != -1) {
                                    int refReg = allocateRegister();
                                    emit(Opcodes.CREATE_REF);
                                    emitReg(refReg);
                                    emitReg(lastResultReg);
                                    varRegs.add(refReg);
                                    wrapWithRef.add(false);
                                }
                                continue;
                            }

                            // Single backslash - this element should become a reference
                            foundBackslashInList = true;

                            // Check if it's a nested list: my (\($d, $e))
                            if (sigilOp.operand instanceof ListNode nestedList) {
                                // Process each element in the nested list as a declared reference
                                for (Node nestedElement : nestedList.elements) {
                                    if (nestedElement instanceof OperatorNode nestedVarNode &&
                                            "$@%".contains(nestedVarNode.operator)) {
                                        // Get the variable name
                                        if (nestedVarNode.operand instanceof IdentifierNode idNode) {
                                            // Initialize based on original sigil.
                                            // Declared refs declare the underlying variable and return a reference to it.
                                            // We keep the underlying variable with its original sigil ($/@/%) and later
                                            // create references when building the return list.
                                            String originalSigil = nestedVarNode.operator;
                                            String declaredVarName = originalSigil + idNode.name;

                                            // Declare the underlying variable (not $-renamed)
                                            int declaredReg = addVariable(declaredVarName, op);

                                            switch (originalSigil) {
                                                case "$" -> {
                                                    emit(Opcodes.LOAD_UNDEF);
                                                    emitReg(declaredReg);
                                                }
                                                case "@" -> {
                                                    emit(Opcodes.NEW_ARRAY);
                                                    emitReg(declaredReg);
                                                }
                                                case "%" -> {
                                                    emit(Opcodes.NEW_HASH);
                                                    emitReg(declaredReg);
                                                }
                                            }

                                            int refReg = allocateRegister();
                                            emit(Opcodes.CREATE_REF);
                                            emitReg(refReg);
                                            emitReg(declaredReg);
                                            varRegs.add(refReg);
                                            wrapWithRef.add(false);
                                        }
                                    }
                                }
                            } else if (sigilOp.operand instanceof OperatorNode varNode &&
                                    "$@%".contains(varNode.operator)) {
                                // Single variable: my (\$x) or state (\$x) or my (\@x) or my (\%x)
                                if (varNode.operand instanceof IdentifierNode idNode) {
                                    String originalSigil = varNode.operator;
                                    String declaredVarName = originalSigil + idNode.name;

                                    // Declare the underlying variable (not $-renamed)
                                    int reg = addVariable(declaredVarName, op);

                                    switch (originalSigil) {
                                        case "$" -> {
                                            emit(Opcodes.LOAD_UNDEF);
                                            emitReg(reg);
                                        }
                                        case "@" -> {
                                            emit(Opcodes.NEW_ARRAY);
                                            emitReg(reg);
                                        }
                                        case "%" -> {
                                            emit(Opcodes.NEW_HASH);
                                            emitReg(reg);
                                        }
                                    }

                                    int refReg = allocateRegister();
                                    emit(Opcodes.CREATE_REF);
                                    emitReg(refReg);
                                    emitReg(reg);
                                    varRegs.add(refReg);
                                    wrapWithRef.add(false);
                                }
                            }
                            continue;
                        }

                        if (sigilOp.operand instanceof IdentifierNode) {
                            String varName = sigil + ((IdentifierNode) sigilOp.operand).name;

                            // Parser may rewrite list elements like \$f/\@f/\%f into a scalar $f (dropping the backslash node).
                            // Preserve semantics here by initializing $f to a reference to the underlying storage and
                            // returning the reference value from the declaration expression.
                            boolean elementIsDeclaredReference = sigilOp.getBooleanAnnotation("isDeclaredReference");
                            Object originalSigilObj = sigilOp.annotations != null ? sigilOp.annotations.get("declaredReferenceOriginalSigil") : null;
                            if (elementIsDeclaredReference && "$".equals(sigil) && ("$".equals(originalSigilObj) || "@".equals(originalSigilObj) || "%".equals(originalSigilObj))) {
                                String originalSigil = (String) originalSigilObj;
                                String baseName = ((IdentifierNode) sigilOp.operand).name;
                                String declaredVarName = originalSigil + baseName;

                                // Declare the scalar variable $name
                                int scalarReg = addVariable(varName, op);
                                emit(Opcodes.LOAD_UNDEF);
                                emitReg(scalarReg);

                                // Declare and initialize the underlying storage
                                int declaredReg;
                                if ("$".equals(originalSigil)) {
                                    // For declared scalar refs: allocate a fresh scalar lvalue and store a reference to it in $name
                                    declaredReg = allocateRegister();
                                    emit(Opcodes.LOAD_UNDEF);
                                    emitReg(declaredReg);
                                } else {
                                    declaredReg = addVariable(declaredVarName, op);
                                    if ("@".equals(originalSigil)) {
                                        emit(Opcodes.NEW_ARRAY);
                                        emitReg(declaredReg);
                                    } else {
                                        emit(Opcodes.NEW_HASH);
                                        emitReg(declaredReg);
                                    }
                                }

                                // Set $name to a reference to the underlying variable
                                int refReg = allocateRegister();
                                emit(Opcodes.CREATE_REF);
                                emitReg(refReg);
                                emitReg(declaredReg);
                                emit(Opcodes.SET_SCALAR);
                                emitReg(scalarReg);
                                emitReg(refReg);

                                // Element-level declared refs:
                                // - \$f expects a REF value (\$f)
                                // - \@f/\%f expect the referent ARRAY/HASH ref value
                                if ("$".equals(originalSigil)) {
                                    int scalarRefReg = allocateRegister();
                                    emit(Opcodes.CREATE_REF);
                                    emitReg(scalarRefReg);
                                    emitReg(scalarReg);
                                    varRegs.add(scalarRefReg);
                                } else {
                                    varRegs.add(refReg);
                                }
                                wrapWithRef.add(false);
                                continue;
                            }

                            Integer beginId2 = RuntimeCode.evalBeginIds.get(sigilOp);
                            if (beginId2 != null || op.equals("state")) {
                                int persistId = beginId2 != null ? beginId2 : sigilOp.id;
                                int reg = allocateRegister();
                                int nameIdx = addToStringPool(varName);

                                switch (sigil) {
                                    case "$" -> {
                                        emitWithToken(Opcodes.RETRIEVE_BEGIN_SCALAR, node.getIndex());
                                        emitReg(reg);
                                        emit(nameIdx);
                                        emit(persistId);
                                        registerVariable(varName, reg);
                                    }
                                    case "@" -> {
                                        emitWithToken(Opcodes.RETRIEVE_BEGIN_ARRAY, node.getIndex());
                                        emitReg(reg);
                                        emit(nameIdx);
                                        emit(persistId);
                                        registerVariable(varName, reg);
                                    }
                                    case "%" -> {
                                        emitWithToken(Opcodes.RETRIEVE_BEGIN_HASH, node.getIndex());
                                        emitReg(reg);
                                        emit(nameIdx);
                                        emit(persistId);
                                        registerVariable(varName, reg);
                                    }
                                    default ->
                                            throwCompilerException("Unsupported variable type in list declaration: " + sigil);
                                }

                                varRegs.add(reg);
                                wrapWithRef.add(isDeclaredReference);
                            } else {
                                // Regular lexical variable (not captured, not state)
                                int reg = addVariable(varName, op);

                                // Initialize the variable
                                switch (sigil) {
                                    case "$" -> {
                                        emit(Opcodes.LOAD_UNDEF);
                                        emitReg(reg);
                                    }
                                    case "@" -> {
                                        emit(Opcodes.NEW_ARRAY);
                                        emitReg(reg);
                                    }
                                    case "%" -> {
                                        emit(Opcodes.NEW_HASH);
                                        emitReg(reg);
                                    }
                                    default ->
                                            throwCompilerException("Unsupported variable type in list declaration: " + sigil);
                                }

                                // Runtime attribute dispatch for list variable declarations.
                                // Attributes are stored on the parent my/state node, propagate to each element.
                                emitVarAttrsIfNeeded(node, reg, sigil);

                                varRegs.add(reg);
                                wrapWithRef.add(isDeclaredReference);
                            }
                        } else if (sigilOp.operand instanceof OperatorNode) {
                            // Handle declared references with backslash: my (\$x)
                            // The backslash operator wraps the variable
                            // For now, skip these as they require special handling
                            continue;
                        } else {
                            throwCompilerException("my list declaration requires identifier: " + sigilOp.operand.getClass().getSimpleName());
                        }
                    } else if (element instanceof ListNode) {
                        // Nested list: my (($x, $y))
                        // Skip nested lists for now
                        continue;
                    } else {
                        throwCompilerException("my list declaration requires scalar/array/hash: " + element.getClass().getSimpleName());
                    }
                }

                // Return a list of the declared variables (or their references if isDeclaredReference).
                int resultReg = allocateRegister();

                if (currentCallContext != RuntimeContextType.VOID) {
                    // Build the return list, optionally wrapping only selected elements with CREATE_REF.
                    List<Integer> outRegs = new ArrayList<>();
                    for (int i = 0; i < varRegs.size(); i++) {
                        int vReg = varRegs.get(i);
                        if (isDeclaredReference && i < wrapWithRef.size() && Boolean.TRUE.equals(wrapWithRef.get(i))) {
                            int refReg = allocateRegister();
                            emit(Opcodes.CREATE_REF);
                            emitReg(refReg);
                            emitReg(vReg);
                            outRegs.add(refReg);
                        } else {
                            outRegs.add(vReg);
                        }
                    }

                    if (currentCallContext == RuntimeContextType.SCALAR) {
                        // In scalar context, declaration lists return the last element.
                        if (outRegs.isEmpty()) {
                            lastResultReg = -1;
                            return;
                        }
                        lastResultReg = outRegs.get(outRegs.size() - 1);
                        return;
                    }

                    emit(Opcodes.CREATE_LIST);
                    emitReg(resultReg);
                    emit(outRegs.size());
                    for (int outReg : outRegs) {
                        emitReg(outReg);
                    }
                } else {
                    emit(Opcodes.CREATE_LIST);
                    emitReg(resultReg);
                    emit(0);
                }

                lastResultReg = resultReg;
                return;
            }
            throwCompilerException("Unsupported my operand: " + node.operand.getClass().getSimpleName());
        } else if (op.equals("our")) {
            // our $x / our @x / our %x - package variable declaration
            // The operand will be OperatorNode("$"/"@"/"%", IdentifierNode("x"))
            if (node.operand instanceof OperatorNode sigilOp) {
                String sigil = sigilOp.operator;

                if (sigilOp.operand instanceof IdentifierNode) {
                    String varName = sigil + ((IdentifierNode) sigilOp.operand).name;

                    // Parser may rewrite element-level declared refs like \$h/\@h/\%h into a scalar $h
                    // with annotations (isDeclaredReference + declaredReferenceOriginalSigil).
                    // Handle that here for single-variable our declarations.
                    if ("$".equals(sigil) && sigilOp.getBooleanAnnotation("isDeclaredReference") && sigilOp.annotations != null
                            && sigilOp.annotations.get("declaredReferenceOriginalSigil") instanceof String originalSigil
                            && ("$".equals(originalSigil) || "@".equals(originalSigil) || "%".equals(originalSigil))) {

                        String baseName = ((IdentifierNode) sigilOp.operand).name;

                        // Declare/load the package scalar $baseName
                        String scalarVarName = "$" + baseName;
                        int scalarReg = hasVariable(scalarVarName) ? getVariableRegister(scalarVarName) : addVariable(scalarVarName, "our");
                        String globalScalarName = NameNormalizer.normalizeVariableName(baseName, getCurrentPackage());
                        int scalarNameIdx = addToStringPool(globalScalarName);
                        emit(Opcodes.LOAD_GLOBAL_SCALAR);
                        emitReg(scalarReg);
                        emit(scalarNameIdx);

                        int declaredReg;
                        if ("$".equals(originalSigil)) {
                            // Hidden scalar storage for declared-ref scalars
                            declaredReg = allocateRegister();
                            emit(Opcodes.LOAD_UNDEF);
                            emitReg(declaredReg);
                        } else {
                            // Underlying package @/%, declared reference points at the container
                            String declaredVarName = originalSigil + baseName;
                            declaredReg = hasVariable(declaredVarName) ? getVariableRegister(declaredVarName) : addVariable(declaredVarName, "our");
                            String globalName = NameNormalizer.normalizeVariableName(baseName, getCurrentPackage());
                            int nameIdx = addToStringPool(globalName);
                            if ("@".equals(originalSigil)) {
                                emit(Opcodes.LOAD_GLOBAL_ARRAY);
                            } else {
                                emit(Opcodes.LOAD_GLOBAL_HASH);
                            }
                            emitReg(declaredReg);
                            emit(nameIdx);
                        }

                        // Set $baseName to reference to underlying storage
                        int refReg = allocateRegister();
                        emit(Opcodes.CREATE_REF);
                        emitReg(refReg);
                        emitReg(declaredReg);
                        emit(Opcodes.SET_SCALAR);
                        emitReg(scalarReg);
                        emitReg(refReg);

                        if (currentCallContext != RuntimeContextType.VOID) {
                            if ("$".equals(originalSigil)) {
                                // Return \$h (a REF) for scalar declared-ref elements
                                int scalarRefReg = allocateRegister();
                                emit(Opcodes.CREATE_REF);
                                emitReg(scalarRefReg);
                                emitReg(scalarReg);
                                lastResultReg = scalarRefReg;
                            } else {
                                // Return the referent arrayref/hashref value
                                lastResultReg = refReg;
                            }
                        } else {
                            lastResultReg = -1;
                        }
                        return;
                    }

                    // Check if this is a declared reference (our \$x)
                    boolean isDeclaredReference = node.annotations != null &&
                            Boolean.TRUE.equals(node.annotations.get("isDeclaredReference"));

                    // Check if already declared in current scope
                    if (hasVariable(varName)) {
                        int reg = getVariableRegister(varName);

                        // For 'our' declarations, we must ALWAYS load from the global table
                        // even if the variable name already exists in scope.  This handles
                        // the interpreter-fallback case where detectClosureVariables()
                        // pre-populates registers from outer-scope 'my' variables, but
                        // the wrapper's 'our $VAR' needs to rebind the register to the
                        // actual package global (e.g. BEGIN block capture variables).
                        if (op.equals("our")) {
                            String globalVarName = NameNormalizer.normalizeVariableName(
                                    ((IdentifierNode) sigilOp.operand).name,
                                    getCurrentPackage()
                            );
                            int nameIdx = addToStringPool(globalVarName);

                            switch (sigil) {
                                case "$" -> {
                                    emit(Opcodes.LOAD_GLOBAL_SCALAR);
                                    emitReg(reg);
                                    emit(nameIdx);
                                }
                                case "@" -> {
                                    emit(Opcodes.LOAD_GLOBAL_ARRAY);
                                    emitReg(reg);
                                    emit(nameIdx);
                                }
                                case "%" -> {
                                    emit(Opcodes.LOAD_GLOBAL_HASH);
                                    emitReg(reg);
                                    emit(nameIdx);
                                }
                            }
                        }

                        // If this is a declared reference, create a reference to it
                        if (isDeclaredReference && currentCallContext != RuntimeContextType.VOID) {
                            int refReg = allocateRegister();
                            emit(Opcodes.CREATE_REF);
                            emitReg(refReg);
                            emitReg(reg);
                            lastResultReg = refReg;
                        } else {
                            lastResultReg = reg;
                        }
                        return;
                    }

                    // Allocate register and add to symbol table
                    int reg = addVariable(varName, "our");

                    // Load from global variable using normalized name
                    String globalVarName = NameNormalizer.normalizeVariableName(
                            ((IdentifierNode) sigilOp.operand).name,
                            getCurrentPackage()
                    );
                    int nameIdx = addToStringPool(globalVarName);

                    switch (sigil) {
                        case "$" -> {
                            emit(Opcodes.LOAD_GLOBAL_SCALAR);
                            emitReg(reg);
                            emit(nameIdx);
                        }
                        case "@" -> {
                            emit(Opcodes.LOAD_GLOBAL_ARRAY);
                            emitReg(reg);
                            emit(nameIdx);
                        }
                        case "%" -> {
                            emit(Opcodes.LOAD_GLOBAL_HASH);
                            emitReg(reg);
                            emit(nameIdx);
                        }
                        default -> throwCompilerException("Unsupported variable type: " + sigil);
                    }

                    // If this is a declared reference, create a reference to it
                    if (isDeclaredReference && currentCallContext != RuntimeContextType.VOID) {
                        int refReg = allocateRegister();
                        emit(Opcodes.CREATE_REF);
                        emitReg(refReg);
                        emitReg(reg);
                        lastResultReg = refReg;
                    } else {
                        lastResultReg = reg;
                    }
                    return;
                }

                // Handle our \\$x - reference to a declared reference (double backslash)
                if (sigil.equals("\\")) {
                    boolean isDeclaredReference = node.annotations != null &&
                            Boolean.TRUE.equals(node.annotations.get("isDeclaredReference"));

                    if (isDeclaredReference) {
                        // This is our \\$x which means: create a declared reference and then take a reference to it
                        // The operand is \$x, so we recursively compile it
                        sigilOp.accept(this);
                        // The result is now in lastResultReg
                        return;
                    }
                }
            } else if (node.operand instanceof ListNode listNode) {
                // our ($x, $y) - list of package variable declarations
                List<Integer> varRegs = new ArrayList<>();

                // Check if this is a declared reference (our \($x, $y))
                boolean isDeclaredReference = node.annotations != null &&
                        Boolean.TRUE.equals(node.annotations.get("isDeclaredReference"));

                // Track if we found any backslash operators inside the list
                // Note: backslash inside the list should only affect that element, not the whole list.
                boolean foundBackslashInList = false;

                for (Node element : listNode.elements) {
                    if (element instanceof OperatorNode sigilOp) {
                        String sigil = sigilOp.operator;

                        // Parser may rewrite element-level declared refs like \$h/\@h/\%h into a scalar $h
                        // with annotations (isDeclaredReference + declaredReferenceOriginalSigil).
                        if ("$".equals(sigil) && sigilOp.operand instanceof IdentifierNode idNode
                                && sigilOp.getBooleanAnnotation("isDeclaredReference")
                                && sigilOp.annotations != null
                                && sigilOp.annotations.get("declaredReferenceOriginalSigil") instanceof String originalSigil
                                && ("$".equals(originalSigil) || "@".equals(originalSigil) || "%".equals(originalSigil))) {

                            String baseName = idNode.name;

                            // Declare/load the package scalar $baseName
                            String scalarVarName = "$" + baseName;
                            int scalarReg = hasVariable(scalarVarName) ? getVariableRegister(scalarVarName) : addVariable(scalarVarName, "our");
                            String globalScalarName = NameNormalizer.normalizeVariableName(baseName, getCurrentPackage());
                            int scalarNameIdx = addToStringPool(globalScalarName);
                            emit(Opcodes.LOAD_GLOBAL_SCALAR);
                            emitReg(scalarReg);
                            emit(scalarNameIdx);

                            int declaredReg;
                            if ("$".equals(originalSigil)) {
                                declaredReg = allocateRegister();
                                emit(Opcodes.LOAD_UNDEF);
                                emitReg(declaredReg);
                            } else {
                                String declaredVarName = originalSigil + baseName;
                                declaredReg = hasVariable(declaredVarName) ? getVariableRegister(declaredVarName) : addVariable(declaredVarName, "our");
                                String globalName = NameNormalizer.normalizeVariableName(baseName, getCurrentPackage());
                                int nameIdx = addToStringPool(globalName);
                                if ("@".equals(originalSigil)) {
                                    emit(Opcodes.LOAD_GLOBAL_ARRAY);
                                } else {
                                    emit(Opcodes.LOAD_GLOBAL_HASH);
                                }
                                emitReg(declaredReg);
                                emit(nameIdx);
                            }

                            int refReg = allocateRegister();
                            emit(Opcodes.CREATE_REF);
                            emitReg(refReg);
                            emitReg(declaredReg);
                            emit(Opcodes.SET_SCALAR);
                            emitReg(scalarReg);
                            emitReg(refReg);

                            if ("$".equals(originalSigil)) {
                                int scalarRefReg = allocateRegister();
                                emit(Opcodes.CREATE_REF);
                                emitReg(scalarRefReg);
                                emitReg(scalarReg);
                                varRegs.add(scalarRefReg);
                            } else {
                                varRegs.add(refReg);
                            }
                            continue;
                        }

                        // Handle backslash operator (reference constructor): our (\$x) or our (\($x, $y))
                        if (sigil.equals("\\")) {
                            // Check if it's a double backslash first: our (\\$x)
                            if (sigilOp.operand instanceof OperatorNode innerBackslash &&
                                    innerBackslash.operator.equals("\\")) {
                                // Double backslash: our (\\$x)
                                // Recursively compile the inner part
                                OperatorNode innerOur = new OperatorNode("our", innerBackslash, node.getIndex());
                                if (node.annotations != null && Boolean.TRUE.equals(node.annotations.get("isDeclaredReference"))) {
                                    innerOur.setAnnotation("isDeclaredReference", true);
                                }
                                innerOur.accept(this);

                                // Create a reference to the result
                                if (currentCallContext != RuntimeContextType.VOID && lastResultReg != -1) {
                                    int refReg = allocateRegister();
                                    emit(Opcodes.CREATE_REF);
                                    emitReg(refReg);
                                    emitReg(lastResultReg);
                                    varRegs.add(refReg);
                                }
                                continue;
                            }

                            // Single backslash - this element should become a reference
                            foundBackslashInList = true;

                            // Check if it's a nested list: our (\($d, $e))
                            if (sigilOp.operand instanceof ListNode nestedList) {
                                // Process each element in the nested list as a declared reference
                                for (Node nestedElement : nestedList.elements) {
                                    if (nestedElement instanceof OperatorNode nestedVarNode &&
                                            "$@%".contains(nestedVarNode.operator)) {
                                        if (nestedVarNode.operand instanceof IdentifierNode idNode) {
                                            String originalSigil = nestedVarNode.operator;
                                            String varName = originalSigil + idNode.name;

                                            // Declare and load the package variable
                                            int reg = addVariable(varName, "our");
                                            String globalVarName = NameNormalizer.normalizeVariableName(
                                                    idNode.name,
                                                    getCurrentPackage()
                                            );
                                            int nameIdx = addToStringPool(globalVarName);

                                            // Load based on original sigil
                                            switch (originalSigil) {
                                                case "$" -> {
                                                    emit(Opcodes.LOAD_GLOBAL_SCALAR);
                                                    emitReg(reg);
                                                    emit(nameIdx);
                                                }
                                                case "@" -> {
                                                    emit(Opcodes.LOAD_GLOBAL_ARRAY);
                                                    emitReg(reg);
                                                    emit(nameIdx);
                                                }
                                                case "%" -> {
                                                    emit(Opcodes.LOAD_GLOBAL_HASH);
                                                    emitReg(reg);
                                                    emit(nameIdx);
                                                }
                                            }

                                            int refReg = allocateRegister();
                                            emit(Opcodes.CREATE_REF);
                                            emitReg(refReg);
                                            emitReg(reg);
                                            varRegs.add(refReg);
                                        }
                                    }
                                }
                            } else if (sigilOp.operand instanceof OperatorNode varNode &&
                                    "$@%".contains(varNode.operator)) {
                                // Single variable: our (\$x) or our (\@x) or our (\%x)
                                if (varNode.operand instanceof IdentifierNode idNode) {
                                    String originalSigil = varNode.operator;
                                    String varName = originalSigil + idNode.name;

                                    // Declare and load the package variable
                                    int reg = addVariable(varName, "our");
                                    String globalVarName = NameNormalizer.normalizeVariableName(
                                            idNode.name,
                                            getCurrentPackage()
                                    );
                                    int nameIdx = addToStringPool(globalVarName);

                                    // Load based on original sigil
                                    switch (originalSigil) {
                                        case "$" -> {
                                            emit(Opcodes.LOAD_GLOBAL_SCALAR);
                                            emitReg(reg);
                                            emit(nameIdx);
                                        }
                                        case "@" -> {
                                            emit(Opcodes.LOAD_GLOBAL_ARRAY);
                                            emitReg(reg);
                                            emit(nameIdx);
                                        }
                                        case "%" -> {
                                            emit(Opcodes.LOAD_GLOBAL_HASH);
                                            emitReg(reg);
                                            emit(nameIdx);
                                        }
                                    }

                                    int refReg = allocateRegister();
                                    emit(Opcodes.CREATE_REF);
                                    emitReg(refReg);
                                    emitReg(reg);
                                    varRegs.add(refReg);
                                }
                            }
                            continue;
                        }

                        if (sigilOp.operand instanceof IdentifierNode) {
                            String varName = sigil + ((IdentifierNode) sigilOp.operand).name;

                            int reg;
                            // Check if already declared in current scope
                            if (hasVariable(varName)) {
                                // Already declared, just use existing register
                                reg = getVariableRegister(varName);

                                // For 'our' declarations, we must ALWAYS load from the global table
                                // even if the variable name already exists in scope.  This handles
                                // the case where eval STRING captures outer 'my' variables into
                                // registers, but the eval's 'our $var' needs to rebind the register
                                // to the actual package global.
                                // (Matches the single-variable our handling above.)
                                String globalVarName = NameNormalizer.normalizeVariableName(
                                        ((IdentifierNode) sigilOp.operand).name,
                                        getCurrentPackage()
                                );
                                int nameIdx = addToStringPool(globalVarName);
                                switch (sigil) {
                                    case "$" -> {
                                        emit(Opcodes.LOAD_GLOBAL_SCALAR);
                                        emitReg(reg);
                                        emit(nameIdx);
                                    }
                                    case "@" -> {
                                        emit(Opcodes.LOAD_GLOBAL_ARRAY);
                                        emitReg(reg);
                                        emit(nameIdx);
                                    }
                                    case "%" -> {
                                        emit(Opcodes.LOAD_GLOBAL_HASH);
                                        emitReg(reg);
                                        emit(nameIdx);
                                    }
                                }
                            } else {
                                // Allocate register and add to symbol table
                                reg = addVariable(varName, "our");

                                // Load from global variable using normalized name
                                String globalVarName = NameNormalizer.normalizeVariableName(
                                        ((IdentifierNode) sigilOp.operand).name,
                                        getCurrentPackage()
                                );
                                int nameIdx = addToStringPool(globalVarName);

                                switch (sigil) {
                                    case "$" -> {
                                        emit(Opcodes.LOAD_GLOBAL_SCALAR);
                                        emitReg(reg);
                                        emit(nameIdx);
                                    }
                                    case "@" -> {
                                        emit(Opcodes.LOAD_GLOBAL_ARRAY);
                                        emitReg(reg);
                                        emit(nameIdx);
                                    }
                                    case "%" -> {
                                        emit(Opcodes.LOAD_GLOBAL_HASH);
                                        emitReg(reg);
                                        emit(nameIdx);
                                    }
                                    default ->
                                            throwCompilerException("Unsupported variable type in list declaration: " + sigil);
                                }
                            }

                            varRegs.add(reg);
                        } else {
                            throwCompilerException("our list declaration requires identifier: " + sigilOp.operand.getClass().getSimpleName());
                        }
                    } else {
                        throwCompilerException("our list declaration requires scalar/array/hash: " + element.getClass().getSimpleName());
                    }
                }

                // Return a list of the declared variables (or their references if isDeclaredReference)
                int resultReg = allocateRegister();

                if (currentCallContext != RuntimeContextType.VOID) {
                    // Create references to all variables first
                    List<Integer> refRegs = new ArrayList<>();
                    for (int varReg : varRegs) {
                        if (isDeclaredReference) {
                            int refReg = allocateRegister();
                            emit(Opcodes.CREATE_REF);
                            emitReg(refReg);
                            emitReg(varReg);
                            refRegs.add(refReg);
                        } else {
                            refRegs.add(varReg);
                        }
                    }

                    if (currentCallContext == RuntimeContextType.SCALAR) {
                        lastResultReg = refRegs.isEmpty() ? -1 : refRegs.get(refRegs.size() - 1);
                        return;
                    }

                    // Create a list of the references
                    emit(Opcodes.CREATE_LIST);
                    emitReg(resultReg);
                    emit(refRegs.size());
                    for (int refReg : refRegs) {
                        emitReg(refReg);
                    }
                } else {
                    // Regular list of variables
                    emit(Opcodes.CREATE_LIST);
                    emitReg(resultReg);
                    emit(varRegs.size());
                    for (int varReg : varRegs) {
                        emitReg(varReg);
                    }
                }

                lastResultReg = resultReg;
                return;
            }
            throwCompilerException("Unsupported our operand: " + node.operand.getClass().getSimpleName());
        } else if (op.equals("local")) {
            // local $x - temporarily localize a global variable
            // The operand will be OperatorNode("$", IdentifierNode("x"))
            if (node.operand instanceof OperatorNode sigilOp) {
                String sigil = sigilOp.operator;

                if (sigil.equals("$") && sigilOp.operand instanceof IdentifierNode) {
                    String varName = "$" + ((IdentifierNode) sigilOp.operand).name;

                    if (hasVariable(varName) && !isOurVariable(varName) && !isReservedVariable(varName)) {
                        throwCompilerException("Can't localize lexical variable " + varName);
                        return;
                    }

                    boolean isDeclaredReference = node.annotations != null &&
                            Boolean.TRUE.equals(node.annotations.get("isDeclaredReference"));

                    String globalVarName = NameNormalizer.normalizeVariableName(((IdentifierNode) sigilOp.operand).name, getCurrentPackage());
                    int nameIdx = addToStringPool(globalVarName);

                    int rd = allocateOutputRegister();
                    emitWithToken(Opcodes.LOCAL_SCALAR, node.getIndex());
                    emitReg(rd);
                    emit(nameIdx);

                    // If this is a declared reference, create a reference to it
                    if (isDeclaredReference && currentCallContext != RuntimeContextType.VOID) {
                        int refReg = allocateRegister();
                        emit(Opcodes.CREATE_REF);
                        emitReg(refReg);
                        emitReg(rd);
                        lastResultReg = refReg;
                    } else {
                        lastResultReg = rd;
                    }
                    return;
                }

                // local @x / local %x - localize global array/hash
                if ((sigil.equals("@") || sigil.equals("%")) && sigilOp.operand instanceof IdentifierNode idNode) {
                    String varName = sigil + idNode.name;

                    if (hasVariable(varName) && !isOurVariable(varName) && !isReservedVariable(varName)) {
                        throwCompilerException("Can't localize lexical variable " + varName);
                        return;
                    }

                    boolean isDeclaredReference = node.annotations != null &&
                            Boolean.TRUE.equals(node.annotations.get("isDeclaredReference"));

                    // For reserved variables like @_, use register-based localization
                    // because register 1 holds @_ which is different from @main::_
                    if (isReservedVariable(varName)) {
                        int regIdx = getVariableRegister(varName);

                        // Emit PUSH_LOCAL_VARIABLE to save register state
                        emit(Opcodes.PUSH_LOCAL_VARIABLE);
                        emitReg(regIdx);

                        // The result is the register itself (for assignment)
                        if (isDeclaredReference && currentCallContext != RuntimeContextType.VOID) {
                            int refReg1 = allocateRegister();
                            emit(Opcodes.CREATE_REF);
                            emitReg(refReg1);
                            emitReg(regIdx);

                            int refReg2 = allocateRegister();
                            emit(Opcodes.CREATE_REF);
                            emitReg(refReg2);
                            emitReg(refReg1);
                            lastResultReg = refReg2;
                        } else {
                            lastResultReg = regIdx;
                        }
                        return;
                    }

                    String globalVarName = NameNormalizer.normalizeVariableName(idNode.name, getCurrentPackage());
                    int nameIdx = addToStringPool(globalVarName);

                    int rd = allocateOutputRegister();
                    if (sigil.equals("@")) {
                        emitWithToken(Opcodes.LOCAL_ARRAY, node.getIndex());
                    } else {
                        emitWithToken(Opcodes.LOCAL_HASH, node.getIndex());
                    }
                    emitReg(rd);
                    emit(nameIdx);

                    if (isDeclaredReference && currentCallContext != RuntimeContextType.VOID) {
                        int refReg1 = allocateRegister();
                        emit(Opcodes.CREATE_REF);
                        emitReg(refReg1);
                        emitReg(rd);

                        int refReg2 = allocateRegister();
                        emit(Opcodes.CREATE_REF);
                        emitReg(refReg2);
                        emitReg(refReg1);
                        lastResultReg = refReg2;
                    } else {
                        lastResultReg = rd;
                    }
                    return;
                }

                // Handle local \$x or local \\$x - backslash operator
                if (sigil.equals("\\")) {
                    boolean isDeclaredReference = node.annotations != null &&
                            Boolean.TRUE.equals(node.annotations.get("isDeclaredReference"));

                    if (isDeclaredReference) {
                        // Check if operand is a variable operator ($, @, %)
                        if (sigilOp.operand instanceof OperatorNode innerOp &&
                                "$@%".contains(innerOp.operator) &&
                                innerOp.operand instanceof IdentifierNode idNode) {

                            String originalSigil = innerOp.operator;
                            String varName = originalSigil + idNode.name;

                            if (hasVariable(varName) && !isOurVariable(varName) && !isReservedVariable(varName)) {
                                throwCompilerException("Can't localize lexical variable " + varName);
                                return;
                            }

                            String globalVarName = NameNormalizer.normalizeVariableName(idNode.name, getCurrentPackage());
                            int nameIdx = addToStringPool(globalVarName);

                            int rd = allocateOutputRegister();
                            if (originalSigil.equals("$")) {
                                emitWithToken(Opcodes.LOCAL_SCALAR, node.getIndex());
                                emitReg(rd);
                                emit(nameIdx);
                            } else if (originalSigil.equals("@")) {
                                emitWithToken(Opcodes.LOCAL_ARRAY, node.getIndex());
                                emitReg(rd);
                                emit(nameIdx);
                            } else {
                                emitWithToken(Opcodes.LOCAL_HASH, node.getIndex());
                                emitReg(rd);
                                emit(nameIdx);
                            }

                            // Create first reference
                            int refReg1 = allocateRegister();
                            emit(Opcodes.CREATE_REF);
                            emitReg(refReg1);
                            emitReg(rd);

                            // Check if the backslash operator itself has isDeclaredReference annotation
                            // which indicates this is \\$x (double backslash)
                            boolean backslashIsDeclaredRef = sigilOp.annotations != null &&
                                    Boolean.TRUE.equals(sigilOp.annotations.get("isDeclaredReference"));

                            if (backslashIsDeclaredRef) {
                                // Double backslash: create second reference
                                int refReg2 = allocateRegister();
                                emit(Opcodes.CREATE_REF);
                                emitReg(refReg2);
                                emitReg(refReg1);
                                lastResultReg = refReg2;
                            } else {
                                // Single backslash: just one reference
                                lastResultReg = refReg1;
                            }
                            return;
                        }
                    }
                }

                if (sigil.equals("our") && sigilOp.operand instanceof OperatorNode innerSigilOp
                        && innerSigilOp.operand instanceof IdentifierNode idNode) {
                    String innerSigil = innerSigilOp.operator;
                    String varName = innerSigil + idNode.name;
                    String globalVarName = NameNormalizer.normalizeVariableName(idNode.name, getCurrentPackage());
                    int nameIdx = addToStringPool(globalVarName);

                    int ourReg = hasVariable(varName) ? getVariableRegister(varName) : addVariable(varName, "our");
                    int rd = allocateOutputRegister();
                    switch (innerSigil) {
                        case "$" -> {
                            emit(Opcodes.LOAD_GLOBAL_SCALAR);
                            emitReg(ourReg);
                            emit(nameIdx);
                            emitWithToken(Opcodes.LOCAL_SCALAR, node.getIndex());
                            emitReg(rd);
                            emit(nameIdx);
                        }
                        case "@" -> {
                            emit(Opcodes.LOAD_GLOBAL_ARRAY);
                            emitReg(ourReg);
                            emit(nameIdx);
                            emitWithToken(Opcodes.LOCAL_ARRAY, node.getIndex());
                            emitReg(rd);
                            emit(nameIdx);
                        }
                        case "%" -> {
                            emit(Opcodes.LOAD_GLOBAL_HASH);
                            emitReg(ourReg);
                            emit(nameIdx);
                            emitWithToken(Opcodes.LOCAL_HASH, node.getIndex());
                            emitReg(rd);
                            emit(nameIdx);
                        }
                        default -> throwCompilerException("Unsupported variable type in local our: " + innerSigil);
                    }
                    lastResultReg = rd;
                    return;
                }

                // Handle: local $#array (without assignment)
                if (sigil.equals("$#")) {
                    int arrayReg = CompileAssignment.resolveArrayForDollarHash(this, sigilOp);
                    // Save the array state so it's restored on scope exit
                    emit(Opcodes.PUSH_LOCAL_VARIABLE);
                    emitReg(arrayReg);
                    // Return the current array size as the result
                    int resultReg = allocateOutputRegister();
                    emit(Opcodes.ARRAY_SIZE);
                    emitReg(resultReg);
                    emitReg(arrayReg);
                    lastResultReg = resultReg;
                    return;
                }
            } else if (node.operand instanceof ListNode listNode) {
                // local ($x, $y) - list of localized global variables
                List<Integer> varRegs = new ArrayList<>();

                // Check if this is a declared reference
                boolean isDeclaredReference = node.annotations != null &&
                        Boolean.TRUE.equals(node.annotations.get("isDeclaredReference"));

                // Track if we found any backslash operators inside the list
                boolean foundBackslashInList = false;

                for (Node element : listNode.elements) {
                    if (element instanceof OperatorNode sigilOp) {
                        String sigil = sigilOp.operator;

                        // Handle backslash operator: local (\$x) or local (\($x, $y))
                        if (sigil.equals("\\")) {
                            // Check if backslash itself has isDeclaredReference (indicates \\$ in source)
                            boolean backslashIsDeclaredRef = sigilOp.annotations != null &&
                                    Boolean.TRUE.equals(sigilOp.annotations.get("isDeclaredReference"));

                            if (backslashIsDeclaredRef) {
                                // Double backslash: local (\\$x)
                                // Localize, create ref, create ref to ref
                                if (sigilOp.operand instanceof OperatorNode varNode &&
                                        "$@%".contains(varNode.operator) &&
                                        varNode.operand instanceof IdentifierNode idNode) {

                                    String varName = varNode.operator + idNode.name;

                                    if (hasVariable(varName) && !isOurVariable(varName) && !isReservedVariable(varName)) {
                                        throwCompilerException("Can't localize lexical variable " + varName);
                                    }

                                    String globalVarName = NameNormalizer.normalizeVariableName(idNode.name, getCurrentPackage());
                                    int nameIdx = addToStringPool(globalVarName);

                                    int rd = allocateOutputRegister();
                                    if (varNode.operator.equals("$")) {
                                        emitWithToken(Opcodes.LOCAL_SCALAR, node.getIndex());
                                        emitReg(rd);
                                        emit(nameIdx);
                                    } else if (varNode.operator.equals("@")) {
                                        emitWithToken(Opcodes.LOCAL_ARRAY, node.getIndex());
                                        emitReg(rd);
                                        emit(nameIdx);
                                    } else {
                                        emitWithToken(Opcodes.LOCAL_HASH, node.getIndex());
                                        emitReg(rd);
                                        emit(nameIdx);
                                    }

                                    // Create first reference
                                    int refReg1 = allocateRegister();
                                    emit(Opcodes.CREATE_REF);
                                    emitReg(refReg1);
                                    emitReg(rd);

                                    // Create second reference
                                    int refReg2 = allocateRegister();
                                    emit(Opcodes.CREATE_REF);
                                    emitReg(refReg2);
                                    emitReg(refReg1);

                                    varRegs.add(refReg2);
                                }
                                continue;
                            }

                            // Single backslash
                            foundBackslashInList = true;

                            // Check if it's a nested list
                            if (sigilOp.operand instanceof ListNode nestedList) {
                                for (Node nestedElement : nestedList.elements) {
                                    if (nestedElement instanceof OperatorNode nestedVarNode &&
                                            "$@%".contains(nestedVarNode.operator) &&
                                            nestedVarNode.operand instanceof IdentifierNode idNode) {
                                        String varName = nestedVarNode.operator + idNode.name;

                                        if (hasVariable(varName) && !isOurVariable(varName) && !isReservedVariable(varName)) {
                                            throwCompilerException("Can't localize lexical variable " + varName);
                                        }

                                        String globalVarName = NameNormalizer.normalizeVariableName(idNode.name, getCurrentPackage());
                                        int nameIdx = addToStringPool(globalVarName);

                                        int rd = allocateOutputRegister();
                                        if (nestedVarNode.operator.equals("$")) {
                                            emitWithToken(Opcodes.LOCAL_SCALAR, node.getIndex());
                                            emitReg(rd);
                                            emit(nameIdx);
                                        } else if (nestedVarNode.operator.equals("@")) {
                                            emitWithToken(Opcodes.LOCAL_ARRAY, node.getIndex());
                                            emitReg(rd);
                                            emit(nameIdx);
                                        } else {
                                            emitWithToken(Opcodes.LOCAL_HASH, node.getIndex());
                                            emitReg(rd);
                                            emit(nameIdx);
                                        }

                                        varRegs.add(rd);
                                    }
                                }
                            } else if (sigilOp.operand instanceof OperatorNode varNode &&
                                    "$@%".contains(varNode.operator) &&
                                    varNode.operand instanceof IdentifierNode idNode) {
                                // Single variable: local (\$x), local (\@x), local (\%x)
                                String originalSigil = varNode.operator;
                                String varName = originalSigil + idNode.name;

                                if (hasVariable(varName) && !isOurVariable(varName) && !isReservedVariable(varName)) {
                                    throwCompilerException("Can't localize lexical variable " + varName);
                                }

                                String globalVarName = NameNormalizer.normalizeVariableName(idNode.name, getCurrentPackage());
                                int nameIdx = addToStringPool(globalVarName);

                                int rd = allocateOutputRegister();
                                if (originalSigil.equals("$")) {
                                    emitWithToken(Opcodes.LOCAL_SCALAR, node.getIndex());
                                    emitReg(rd);
                                    emit(nameIdx);
                                } else if (originalSigil.equals("@")) {
                                    emitWithToken(Opcodes.LOCAL_ARRAY, node.getIndex());
                                    emitReg(rd);
                                    emit(nameIdx);
                                } else {
                                    emitWithToken(Opcodes.LOCAL_HASH, node.getIndex());
                                    emitReg(rd);
                                    emit(nameIdx);
                                }

                                varRegs.add(rd);
                            }
                            continue;
                        }

                        if (sigilOp.operand instanceof IdentifierNode idNode) {
                            if (sigil.equals("*")) {
                                String globalName = NameNormalizer.normalizeVariableName(idNode.name, getCurrentPackage());
                                int nameIdx = addToStringPool(globalName);
                                int rd = allocateOutputRegister();
                                emit(Opcodes.LOCAL_GLOB);
                                emitReg(rd);
                                emit(nameIdx);
                                varRegs.add(rd);
                            } else {
                                String varName = sigil + idNode.name;
                                if (hasVariable(varName) && !isOurVariable(varName) && !isReservedVariable(varName)) {
                                    throwCompilerException("Can't localize lexical variable " + varName);
                                }
                                String globalVarName = NameNormalizer.normalizeVariableName(idNode.name, getCurrentPackage());
                                int nameIdx = addToStringPool(globalVarName);
                                int rd = allocateOutputRegister();
                                if (sigil.equals("$")) {
                                    emitWithToken(Opcodes.LOCAL_SCALAR, node.getIndex());
                                } else if (sigil.equals("@")) {
                                    emitWithToken(Opcodes.LOCAL_ARRAY, node.getIndex());
                                } else if (sigil.equals("%")) {
                                    emitWithToken(Opcodes.LOCAL_HASH, node.getIndex());
                                }
                                emitReg(rd);
                                emit(nameIdx);
                                varRegs.add(rd);
                            }
                        }
                    }
                }

                // Return a list of the localized variables (or their references)
                int resultReg = allocateRegister();

                if ((isDeclaredReference || foundBackslashInList) && currentCallContext != RuntimeContextType.VOID) {
                    // Create references to all variables first
                    List<Integer> refRegs = new ArrayList<>();
                    for (int varReg : varRegs) {
                        int refReg = allocateRegister();
                        emit(Opcodes.CREATE_REF);
                        emitReg(refReg);
                        emitReg(varReg);
                        refRegs.add(refReg);
                    }

                    // Create a list of the references
                    emit(Opcodes.CREATE_LIST);
                    emitReg(resultReg);
                    emit(refRegs.size());
                    for (int refReg : refRegs) {
                        emitReg(refReg);
                    }
                } else {
                    // Regular list of variables
                    emit(Opcodes.CREATE_LIST);
                    emitReg(resultReg);
                    emit(varRegs.size());
                    for (int varReg : varRegs) {
                        emitReg(varReg);
                    }
                }

                lastResultReg = resultReg;
                return;
            }
            // local *GLOB - localize a typeglob
            if (node.operand instanceof OperatorNode sigilOp2
                    && sigilOp2.operator.equals("*")
                    && sigilOp2.operand instanceof IdentifierNode idNode2) {
                String globalName = NameNormalizer.normalizeVariableName(idNode2.name, getCurrentPackage());
                int nameIdx = addToStringPool(globalName);
                int rd = allocateOutputRegister();
                emit(Opcodes.LOCAL_GLOB);
                emitReg(rd);
                emit(nameIdx);
                lastResultReg = rd;
                return;
            }
            // local *{expr} - localize a typeglob with dynamic name
            if (node.operand instanceof OperatorNode sigilOp3
                    && sigilOp3.operator.equals("*")
                    && sigilOp3.operand instanceof BlockNode blockNode) {
                // Compile the expression inside the block to get the name
                if (blockNode.elements.size() == 1) {
                    compileNode(blockNode.elements.getFirst(), -1, RuntimeContextType.SCALAR);
                } else {
                    compileNode(blockNode, -1, RuntimeContextType.SCALAR);
                }
                int nameReg = lastResultReg;
                int rd = allocateOutputRegister();
                emit(Opcodes.LOCAL_GLOB_DYNAMIC);
                emitReg(rd);
                emitReg(nameReg);
                lastResultReg = rd;
                return;
            }
            // local @{expr} / local %{expr} - localize a dynamic array/hash by name
            // Implemented by localizing the typeglob (covers the array/hash slot)
            if (node.operand instanceof OperatorNode sigilOp4
                    && (sigilOp4.operator.equals("@") || sigilOp4.operator.equals("%"))
                    && sigilOp4.operand instanceof BlockNode blockNode2) {
                if (blockNode2.elements.size() == 1) {
                    compileNode(blockNode2.elements.getFirst(), -1, RuntimeContextType.SCALAR);
                } else {
                    compileNode(blockNode2, -1, RuntimeContextType.SCALAR);
                }
                int nameReg = lastResultReg;
                int rd = allocateOutputRegister();
                emit(Opcodes.LOCAL_GLOB_DYNAMIC);
                emitReg(rd);
                emitReg(nameReg);
                lastResultReg = rd;
                return;
            }
            // General fallback for any lvalue expression (matches JVM backend behavior)
            // Handles: local $hash{key}, local $array[index], local $obj->method->{key}, etc.
            if (node.operand instanceof BinaryOperatorNode binOp) {
                compileNode(binOp, -1, RuntimeContextType.SCALAR);
                int elemReg = lastResultReg;
                emit(Opcodes.PUSH_LOCAL_VARIABLE);
                emitReg(elemReg);
                lastResultReg = elemReg;
                return;
            }
            throwCompilerException("Unsupported local operand: " + node.operand.getClass().getSimpleName());
        }
        throwCompilerException("Unsupported variable declaration operator: " + op);
    }

    void compileVariableReference(OperatorNode node, String op) {
        if (op.equals("$")) {
            // Scalar variable dereference: $x
            if (node.operand instanceof IdentifierNode) {
                String varName = "$" + ((IdentifierNode) node.operand).name;

                // Check if this is a closure variable captured from outer scope via PersistentVariable
                if (currentSubroutineBeginId != 0 && currentSubroutineClosureVars.contains(varName)) {
                    // This is a closure variable - use RETRIEVE_BEGIN_SCALAR
                    int rd = allocateOutputRegister();
                    int nameIdx = addToStringPool(varName);

                    emitWithToken(Opcodes.RETRIEVE_BEGIN_SCALAR, node.getIndex());
                    emitReg(rd);
                    emit(nameIdx);
                    emit(currentSubroutineBeginId);

                    lastResultReg = rd;
                } else if (hasVariable(varName) && !isOurVariable(varName)) {
                    // Lexical variable (my/state) - use existing register
                    lastResultReg = getVariableRegister(varName);
                } else if (hasVariable(varName) && isOurVariable(varName)) {
                    // 'our' variable - must load from global table to see local() changes
                    // This ensures 'local $Pkg::Var' modifications are visible inside subroutines
                    String globalVarName = varName.substring(1); // Remove $ sigil
                    globalVarName = NameNormalizer.normalizeVariableName(
                            globalVarName,
                            getCurrentPackage()
                    );
                    int rd = allocateRegister();
                    int nameIdx = addToStringPool(globalVarName);
                    emit(Opcodes.LOAD_GLOBAL_SCALAR);
                    emitReg(rd);
                    emit(nameIdx);
                    lastResultReg = rd;
                } else {
                    // Global variable - check strict vars then load
                    if (shouldBlockGlobalUnderStrictVars(varName)) {
                        throwCompilerException("Global symbol \"" + varName + "\" requires explicit package name");
                    }

                    // Use NameNormalizer to properly handle special variables (like $&)
                    // which must always be in the "main" package
                    String globalVarName = varName.substring(1); // Remove $ sigil first
                    globalVarName = NameNormalizer.normalizeVariableName(
                            globalVarName,
                            getCurrentPackage()
                    );

                    // Use allocateRegister() instead of allocateOutputRegister() because
                    // LOAD_GLOBAL_SCALAR for special variables like $1 returns a proxy object.
                    // The ALIAS operation is needed to copy the value before RESTORE_REGEX_STATE.
                    int rd = allocateRegister();
                    int nameIdx = addToStringPool(globalVarName);

                    emit(Opcodes.LOAD_GLOBAL_SCALAR);
                    emitReg(rd);
                    emit(nameIdx);

                    lastResultReg = rd;
                }
            } else if (node.operand instanceof BlockNode block) {
                // Symbolic reference via block: ${label:expr} or ${expr}
                // Execute the block to get a variable name string, then load that variable

                // Check strict refs at compile time — mirrors JVM path in EmitVariable.java
                compileNode(block, -1, RuntimeContextType.SCALAR);
                int blockResultReg = lastResultReg;
                int rd = allocateOutputRegister();
                if (isStrictRefsEnabled()) {
                    // strict refs: scalarDeref() — throws for non-refs
                    emitWithToken(Opcodes.DEREF_SCALAR_STRICT, node.getIndex());
                    emitReg(rd);
                    emitReg(blockResultReg);
                } else {
                    // no strict refs: scalarDerefNonStrict(pkg) — allows symrefs
                    int pkgIdx = addToStringPool(getCurrentPackage());
                    emitWithToken(Opcodes.DEREF_SCALAR_NONSTRICT, node.getIndex());
                    emitReg(rd);
                    emitReg(blockResultReg);
                    emit(pkgIdx);
                }
                lastResultReg = rd;
            } else if (node.operand instanceof OperatorNode) {
                // Operator dereference: $$x, $${expr}, etc.
                // Compile the operand expression (e.g., $x returns a reference)
                node.operand.accept(this);
                int refReg = lastResultReg;

                // Dereference the result
                // Use strict/non-strict variants to match JVM behavior and strict refs semantics.
                int rd = allocateOutputRegister();
                if (isStrictRefsEnabled()) {
                    emitWithToken(Opcodes.DEREF_SCALAR_STRICT, node.getIndex());
                    emitReg(rd);
                    emitReg(refReg);
                } else {
                    int pkgIdx = addToStringPool(getCurrentPackage());
                    emitWithToken(Opcodes.DEREF_SCALAR_NONSTRICT, node.getIndex());
                    emitReg(rd);
                    emitReg(refReg);
                    emit(pkgIdx);
                }
                lastResultReg = rd;
            } else if (node.operand instanceof StringNode strNode) {
                // Symbolic ref: ${'name'} — load global scalar by string name
                String globalName = NameNormalizer.normalizeVariableName(strNode.value, getCurrentPackage());
                int nameIdx = addToStringPool(globalName);
                int rd = allocateRegister();
                emit(Opcodes.LOAD_GLOBAL_SCALAR);
                emitReg(rd);
                emit(nameIdx);
                lastResultReg = rd;
            } else {
                throwCompilerException("Unsupported $ operand: " + node.operand.getClass().getSimpleName());
            }
        } else if (op.equals("@")) {
            // Array variable dereference: @x or @_ or @$arrayref
            if (node.operand instanceof IdentifierNode) {
                String varName = "@" + ((IdentifierNode) node.operand).name;

                // Special case: @_ is register 1
                if (varName.equals("@_")) {
                    int arrayReg = 1; // @_ is always in register 1

                    // Check if we're in scalar context - if so, return array size
                    if (currentCallContext == RuntimeContextType.SCALAR) {
                        int rd = allocateOutputRegister();
                        emit(Opcodes.ARRAY_SIZE);
                        emitReg(rd);
                        emitReg(arrayReg);
                        lastResultReg = rd;
                    } else {
                        lastResultReg = arrayReg;
                    }
                    return;
                }

                // Check if this is a closure variable captured from outer scope via PersistentVariable
                int arrayReg;
                if (currentSubroutineBeginId != 0 && currentSubroutineClosureVars.contains(varName)) {
                    // This is a closure variable - use RETRIEVE_BEGIN_ARRAY
                    arrayReg = allocateRegister();
                    int nameIdx = addToStringPool(varName);

                    emitWithToken(Opcodes.RETRIEVE_BEGIN_ARRAY, node.getIndex());
                    emitReg(arrayReg);
                    emit(nameIdx);
                    emit(currentSubroutineBeginId);
                } else if (hasVariable(varName) && !isOurVariable(varName)) {
                    // Lexical array (my/state) - use existing register
                    arrayReg = getVariableRegister(varName);
                } else if (hasVariable(varName) && isOurVariable(varName)) {
                    // 'our' array - must load from global table to see local() changes
                    arrayReg = allocateRegister();
                    String globalArrayName = NameNormalizer.normalizeVariableName(((IdentifierNode) node.operand).name, getCurrentPackage());
                    int nameIdx = addToStringPool(globalArrayName);
                    emit(Opcodes.LOAD_GLOBAL_ARRAY);
                    emitReg(arrayReg);
                    emit(nameIdx);
                } else {
                    // Global array - load it
                    arrayReg = allocateRegister();
                    String globalArrayName = NameNormalizer.normalizeVariableName(((IdentifierNode) node.operand).name, getCurrentPackage());
                    int nameIdx = addToStringPool(globalArrayName);

                    emit(Opcodes.LOAD_GLOBAL_ARRAY);
                    emitReg(arrayReg);
                    emit(nameIdx);
                }

                // Check if we're in scalar context - if so, return array size
                if (currentCallContext == RuntimeContextType.SCALAR) {
                    int rd = allocateOutputRegister();
                    emit(Opcodes.ARRAY_SIZE);
                    emitReg(rd);
                    emitReg(arrayReg);
                    lastResultReg = rd;
                } else if (currentCallContext == RuntimeContextType.RUNTIME) {
                    // In RUNTIME context (e.g., return @a), check wantarray at runtime
                    // and convert to scalar (count) if scalar context
                    int rd = allocateOutputRegister();
                    emit(Opcodes.SCALAR_IF_WANTARRAY);
                    emitReg(rd);
                    emitReg(arrayReg);
                    emitReg(2); // wantarray is in register 2
                    lastResultReg = rd;
                } else {
                    lastResultReg = arrayReg;
                }
            } else if (node.operand instanceof OperatorNode operandOp) {
                // Dereference: @$arrayref or @{$hashref}

                // Compile the reference
                operandOp.accept(this);
                int refReg = lastResultReg;

                // Dereference to get the array
                // The reference should contain a RuntimeArray
                // For @$scalar, we need to dereference it
                int rd = allocateOutputRegister();
                if (isStrictRefsEnabled()) {
                    emitWithToken(Opcodes.DEREF_ARRAY, node.getIndex());
                    emitReg(rd);
                    emitReg(refReg);
                } else {
                    int pkgIdx = addToStringPool(getCurrentPackage());
                    emitWithToken(Opcodes.DEREF_ARRAY_NONSTRICT, node.getIndex());
                    emitReg(rd);
                    emitReg(refReg);
                    emit(pkgIdx);
                }

                lastResultReg = rd;
                // Note: We don't check scalar context here because dereferencing
                // should return the array itself. The slice or other operation
                // will handle scalar context conversion if needed.
            } else if (node.operand instanceof BlockNode blockNode) {
                // @{ block } - evaluate block and dereference the result
                // The block should return an arrayref
                compileNode(blockNode, -1, RuntimeContextType.SCALAR);
                int refReg = lastResultReg;

                // Dereference to get the array
                int rd = allocateOutputRegister();
                if (isStrictRefsEnabled()) {
                    emitWithToken(Opcodes.DEREF_ARRAY, node.getIndex());
                    emitReg(rd);
                    emitReg(refReg);
                } else {
                    int pkgIdx = addToStringPool(getCurrentPackage());
                    emitWithToken(Opcodes.DEREF_ARRAY_NONSTRICT, node.getIndex());
                    emitReg(rd);
                    emitReg(refReg);
                    emit(pkgIdx);
                }

                lastResultReg = rd;
            } else if (node.operand instanceof StringNode strNode) {
                // Symbolic ref: @{'name'} or 'name'->@* — load global array by string name
                String globalName = NameNormalizer.normalizeVariableName(strNode.value, getCurrentPackage());
                int nameIdx = addToStringPool(globalName);
                int rd = allocateOutputRegister();
                emit(Opcodes.LOAD_GLOBAL_ARRAY);
                emitReg(rd);
                emit(nameIdx);
                lastResultReg = rd;
            } else {
                throwCompilerException("Unsupported @ operand: " + node.operand.getClass().getSimpleName());
            }
        } else if (op.equals("%")) {
            // Hash variable dereference: %x
            if (node.operand instanceof IdentifierNode) {
                String varName = "%" + ((IdentifierNode) node.operand).name;

                int hashReg;
                if (currentSubroutineBeginId != 0 && currentSubroutineClosureVars != null
                        && currentSubroutineClosureVars.contains(varName)) {
                    hashReg = allocateRegister();
                    int nameIdx = addToStringPool(varName);
                    emitWithToken(Opcodes.RETRIEVE_BEGIN_HASH, node.getIndex());
                    emitReg(hashReg);
                    emit(nameIdx);
                    emit(currentSubroutineBeginId);
                } else if (hasVariable(varName) && !isOurVariable(varName)) {
                    // Lexical hash (my/state) - use existing register
                    hashReg = getVariableRegister(varName);
                } else if (hasVariable(varName) && isOurVariable(varName)) {
                    // 'our' hash - must load from global table to see local() changes
                    hashReg = allocateRegister();
                    String globalHashName = NameNormalizer.normalizeVariableName(((IdentifierNode) node.operand).name, getCurrentPackage());
                    int nameIdx = addToStringPool(globalHashName);
                    emit(Opcodes.LOAD_GLOBAL_HASH);
                    emitReg(hashReg);
                    emit(nameIdx);
                } else {
                    hashReg = allocateRegister();
                    String globalHashName = NameNormalizer.normalizeVariableName(((IdentifierNode) node.operand).name, getCurrentPackage());
                    int nameIdx = addToStringPool(globalHashName);

                    emit(Opcodes.LOAD_GLOBAL_HASH);
                    emitReg(hashReg);
                    emit(nameIdx);
                }

                // Check if we're in scalar context - if so, return hash as scalar (bucket info)
                if (currentCallContext == RuntimeContextType.SCALAR) {
                    int rd = allocateOutputRegister();
                    emit(Opcodes.ARRAY_SIZE);  // Works for hashes too - calls .scalar()
                    emitReg(rd);
                    emitReg(hashReg);
                    lastResultReg = rd;
                } else {
                    lastResultReg = hashReg;
                }
            } else if (node.operand instanceof OperatorNode refOp) {
                // %$ref or %{expr} — dereference scalar to hash
                refOp.accept(this);
                int scalarReg = lastResultReg;
                int hashReg = allocateRegister();
                if (isStrictRefsEnabled()) {
                    emitWithToken(Opcodes.DEREF_HASH, node.getIndex());
                    emitReg(hashReg);
                    emitReg(scalarReg);
                } else {
                    int pkgIdx = addToStringPool(getCurrentPackage());
                    emitWithToken(Opcodes.DEREF_HASH_NONSTRICT, node.getIndex());
                    emitReg(hashReg);
                    emitReg(scalarReg);
                    emit(pkgIdx);
                }
                if (currentCallContext == RuntimeContextType.SCALAR) {
                    int rd = allocateOutputRegister();
                    emit(Opcodes.ARRAY_SIZE);
                    emitReg(rd);
                    emitReg(hashReg);
                    lastResultReg = rd;
                } else {
                    lastResultReg = hashReg;
                }
            } else if (node.operand instanceof BlockNode blockNode) {
                // %{ block } — evaluate block and dereference to hash
                compileNode(blockNode, -1, RuntimeContextType.SCALAR);
                int scalarReg = lastResultReg;
                int hashReg = allocateRegister();
                if (isStrictRefsEnabled()) {
                    emitWithToken(Opcodes.DEREF_HASH, node.getIndex());
                    emitReg(hashReg);
                    emitReg(scalarReg);
                } else {
                    int pkgIdx = addToStringPool(getCurrentPackage());
                    emitWithToken(Opcodes.DEREF_HASH_NONSTRICT, node.getIndex());
                    emitReg(hashReg);
                    emitReg(scalarReg);
                    emit(pkgIdx);
                }
                lastResultReg = hashReg;
            } else if (node.operand instanceof StringNode strNode) {
                // Symbolic ref: %{'name'} or 'name'->%* — load global hash by string name
                String globalName = NameNormalizer.normalizeVariableName(strNode.value, getCurrentPackage());
                int nameIdx = addToStringPool(globalName);
                int hashReg = allocateRegister();
                emit(Opcodes.LOAD_GLOBAL_HASH);
                emitReg(hashReg);
                emit(nameIdx);
                lastResultReg = hashReg;
            } else {
                throwCompilerException("Unsupported % operand: " + node.operand.getClass().getSimpleName());
            }
        } else if (op.equals("*")) {
            // Glob variable dereference: *x
            if (node.operand instanceof IdentifierNode idNode) {
                // Use NameNormalizer to properly handle special handles (STDOUT, STDERR, STDIN, etc.)
                // which must always be in the "main" package, regardless of current package.
                String varName = NameNormalizer.normalizeVariableName(idNode.name, getCurrentPackage());

                // Allocate register for glob
                int rd = allocateOutputRegister();
                int nameIdx = addToStringPool(varName);

                // Emit direct opcode LOAD_GLOB
                emitWithToken(Opcodes.LOAD_GLOB, node.getIndex());
                emitReg(rd);
                emit(nameIdx);

                lastResultReg = rd;
            } else if (node.operand instanceof StringNode strNode) {
                // Symbolic ref: *{'name'} or 'name'->** — load global glob by string name
                // Use NameNormalizer to properly handle special handles (STDOUT, STDERR, etc.)
                String varName = NameNormalizer.normalizeVariableName(strNode.value, getCurrentPackage());
                int rd = allocateOutputRegister();
                int nameIdx = addToStringPool(varName);
                emitWithToken(Opcodes.LOAD_GLOB, node.getIndex());
                emitReg(rd);
                emit(nameIdx);
                lastResultReg = rd;
            } else if (node.operand instanceof OperatorNode || node.operand instanceof BlockNode) {
                compileNode(node.operand, -1, RuntimeContextType.SCALAR);
                int refReg = lastResultReg;
                int rd = allocateOutputRegister();
                int pkgIdx = addToStringPool(getCurrentPackage());
                if (isStrictRefsEnabled()) {
                    emitWithToken(Opcodes.DEREF_GLOB, node.getIndex());
                } else {
                    emitWithToken(Opcodes.DEREF_GLOB_NONSTRICT, node.getIndex());
                }
                emitReg(rd);
                emitReg(refReg);
                emit(pkgIdx);
                lastResultReg = rd;
            } else {
                throwCompilerException("Unsupported * operand: " + node.operand.getClass().getSimpleName());
            }
        } else if (op.equals("&")) {
            // Code reference: &subname or &{expr}
            // Gets a reference to a named subroutine
            if (node.operand instanceof IdentifierNode idNode) {
                String subName = idNode.name;

                // Use NameNormalizer to properly handle package prefixes
                // This will add the current package if no package is specified
                subName = NameNormalizer.normalizeVariableName(subName, getCurrentPackage());

                // Cache the RuntimeScalar code reference at compile time.
                // This matches Perl's behavior where the CV (code value) is cached
                // in the compiled bytecode, surviving stash entry deletion.
                RuntimeScalar codeRef = GlobalVariable.getGlobalCodeRef(subName);

                // Allocate register and load from constant pool
                int rd = allocateOutputRegister();
                int constIdx = addToConstantPool(codeRef);

                emit(Opcodes.LOAD_CONST);
                emitReg(rd);
                emit(constIdx);

                lastResultReg = rd;
            } else if (node.operand instanceof StringNode strNode) {
                // Symbolic ref: &{'name'} — look up global code reference by string name
                String globalName = NameNormalizer.normalizeVariableName(strNode.value, getCurrentPackage());
                RuntimeScalar codeRef = GlobalVariable.getGlobalCodeRef(globalName);
                int rd = allocateOutputRegister();
                int constIdx = addToConstantPool(codeRef);
                emit(Opcodes.LOAD_CONST);
                emitReg(rd);
                emit(constIdx);
                lastResultReg = rd;
            } else if (node.operand instanceof BlockNode || node.operand instanceof OperatorNode) {
                // Dynamic code reference: &{$name} or &$name
                // Compile the expression to get the name/value, then dereference as code
                compileNode(node.operand, -1, RuntimeContextType.SCALAR);
                int valueReg = lastResultReg;

                // Use CODE_DEREF_NONSTRICT to look up the code reference
                int rd = allocateOutputRegister();
                int pkgIdx = addToStringPool(getCurrentPackage());
                emit(Opcodes.CODE_DEREF_NONSTRICT);
                emitReg(rd);
                emitReg(valueReg);
                emit(pkgIdx);

                lastResultReg = rd;
            } else {
                throwCompilerException("Unsupported & operand: " + node.operand.getClass().getSimpleName());
            }
        } else if (op.equals("\\")) {
            // Reference operator: \$x, \@x, \%x, \*x, etc.
            if (node.operand != null) {
                // Special case: \&name — CODE is already a reference type.
                // Emit LOAD_GLOBAL_CODE directly without CREATE_REF, matching JVM compiler.
                // Also set isSymbolicReference so defined(\&stub) returns true, matching
                // the JVM backend's createCodeReference behavior.
                if (node.operand instanceof OperatorNode operandOp
                        && operandOp.operator.equals("&")) {
                    if (operandOp.operand instanceof IdentifierNode idNode) {
                        // \&name — regular package sub. Set isSymbolicReference before
                        // loading, so defined(\&Name) returns true
                        String subName = NameNormalizer.normalizeVariableName(
                                idNode.name, getCurrentPackage());
                        RuntimeScalar codeRef = GlobalVariable.getGlobalCodeRef(subName);
                        if (codeRef.type == RuntimeScalarType.CODE
                                && codeRef.value instanceof RuntimeCode rc) {
                            rc.isSymbolicReference = true;
                        }
                    }
                    // For both \&name and \&$var (lexical subs), the & operator
                    // already produces a CODE value — no CREATE_REF wrapping needed.
                    // This matches the JVM backend's createCodeReference behavior.
                    node.operand.accept(this);
                    // lastResultReg already holds the CODE scalar — no wrapping needed
                    return;
                }

                // Compile operand in LIST context to get the actual value
                // Example: \@array should get a reference to the array itself,
                // not its size (which would happen in SCALAR context)
                compileNode(node.operand, -1, RuntimeContextType.LIST);
                int valueReg = lastResultReg;

                // Allocate register for reference
                int rd = allocateOutputRegister();

                // Emit CREATE_REF
                emit(Opcodes.CREATE_REF);
                emitReg(rd);
                emitReg(valueReg);

                lastResultReg = rd;
            } else {
                throwCompilerException("Reference operator requires operand");
            }
        }
    }

    @Override
    public void visit(OperatorNode node) {
        CompileOperator.visitOperator(this, node);
    }

    int allocateRegister() {
        int reg = nextRegister++;
        if (reg > 65535) {
            throwCompilerException("Too many registers: exceeded 65535 register limit. " +
                    "Consider breaking this code into smaller subroutines.");
        }
        // Track the highest register ever used for array sizing
        if (reg > maxRegisterEverUsed) {
            maxRegisterEverUsed = reg;
        }
        return reg;
    }

    /**
     * Allocate a unique callsite ID for /o modifier support.
     * Each callsite with /o gets a unique ID so the pattern is compiled only once per callsite.
     */
    int allocateCallsiteId() {
        return nextCallsiteId++;
    }

    int allocateOutputRegister() {
        if (targetOutputReg >= 0) {
            int reg = targetOutputReg;
            targetOutputReg = -1;
            return reg;
        }
        return allocateRegister();
    }

    void emitAliasWithTarget(int destReg, int srcReg) {
        if (destReg >= 0 && srcReg >= 0 && destReg != srcReg) {
            emit(Opcodes.ALIAS);
            emitReg(destReg);
            emitReg(srcReg);
        }
    }

    void compileNode(Node node, int targetReg, int callContext) {
        if (node == null) {
            // Skip null nodes (e.g., from format declarations that produce empty statements)
            lastResultReg = -1;
            return;
        }
        int savedTarget = targetOutputReg;
        int savedContext = currentCallContext;
        targetOutputReg = targetReg;
        currentCallContext = callContext;
        node.accept(this);
        targetOutputReg = savedTarget;
        currentCallContext = savedContext;
    }

    // =========================================================================
    // HELPER METHODS
    // =========================================================================

    TreeMap<Integer, String> collectVisiblePerlVariables() {
        TreeMap<Integer, String> closureVarsByReg = new TreeMap<>();
        Map<Integer, org.perlonjava.frontend.semantic.SymbolTable.SymbolEntry> visible = symbolTable.getAllVisibleVariables();
        for (Map.Entry<Integer, org.perlonjava.frontend.semantic.SymbolTable.SymbolEntry> e : visible.entrySet()) {
            int reg = e.getKey();
            String varName = e.getValue().name();
            if (reg < 3 || varName == null || varName.isEmpty()) continue;
            char sigil = varName.charAt(0);
            if (sigil == '$' || sigil == '@' || sigil == '%') {
                closureVarsByReg.put(reg, varName);
            }
        }
        return closureVarsByReg;
    }

    /**
     * Get the highest register index currently used by variables (not temporaries).
     * This is used to determine the reset point for register recycling.
     */
    private int getHighestVariableRegister() {
        int maxReg = 2;
        Map<Integer, org.perlonjava.frontend.semantic.SymbolTable.SymbolEntry> visible = symbolTable.getAllVisibleVariables();
        for (Integer reg : visible.keySet()) {
            if (reg > maxReg) {
                maxReg = reg;
            }
        }

        // Also check captured variables
        if (capturedVarIndices != null) {
            for (Integer reg : capturedVarIndices.values()) {
                if (reg > maxReg) {
                    maxReg = reg;
                }
            }
        }

        return maxReg;
    }

    /**
     * Reset temporary registers after a statement completes.
     * This allows register reuse for the next statement, preventing register exhaustion
     * in large scripts.
     * <p>
     * IMPORTANT: Only resets registers that are truly temporary. Preserves:
     * - Reserved registers (0-2)
     * - All variables in all scopes
     * - Captured variables (closures)
     * - Registers protected by enterScope() (baseRegisterForStatement)
     * <p>
     * NOTE: This assumes that by the end of a statement, all intermediate values
     * have either been stored in variables or used/discarded. Any value that needs
     * to persist must be in a variable, not just a temporary register.
     */
    private void recycleTemporaryRegisters() {
        // Find highest variable register and reset nextRegister to one past it
        int highestVarReg = getHighestVariableRegister() + 1;

        // CRITICAL: Never lower baseRegisterForStatement below what enterScope() set.
        // This protects registers like foreach iterators that must survive across loop iterations.
        // Use Math.max to preserve the higher value set by enterScope()
        baseRegisterForStatement = Math.max(baseRegisterForStatement, highestVarReg);
        nextRegister = baseRegisterForStatement;

        // DO NOT reset lastResultReg - BlockNode needs it to return the last statement's result
    }

    int addToStringPool(String str) {
        // Use HashMap for O(1) lookup instead of O(n) ArrayList.indexOf()
        Integer cached = stringPoolIndex.get(str);
        if (cached != null) {
            return cached;
        }
        int index = stringPool.size();
        stringPool.add(str);
        stringPoolIndex.put(str, index);
        return index;
    }

    /**
     * Emit DISPATCH_VAR_ATTRS opcode if the node has variable attributes.
     * Called after a my/state variable is initialized in its register.
     */
    @SuppressWarnings("unchecked")
    void emitVarAttrsIfNeeded(OperatorNode node, int varReg, String sigil) {
        if (node.annotations == null || !node.annotations.containsKey("attributes")) return;

        List<String> attrs = (List<String>) node.annotations.get("attributes");
        String packageName = (String) node.annotations.get("attributePackage");
        if (packageName == null) packageName = getCurrentPackage();

        String fileName = sourceName;
        int lineNum = sourceLine;

        // Store metadata in constant pool as Object[]
        Object[] data = new Object[]{
                packageName, sigil, attrs.toArray(new String[0]), fileName, lineNum
        };
        int constIdx = addToConstantPool(data);

        emit(Opcodes.DISPATCH_VAR_ATTRS);
        emitReg(varReg);
        emit(constIdx);
    }

    private int addToConstantPool(Object obj) {
        // Use HashMap for O(1) lookup instead of O(n) ArrayList.indexOf()
        Integer cached = constantPoolIndex.get(obj);
        if (cached != null) {
            return cached;
        }
        int index = constants.size();
        constants.add(obj);
        constantPoolIndex.put(obj, index);
        return index;
    }

    void emit(short opcode) {
        // Track if any localization opcodes are emitted (including defer blocks which use DVM)
        if (opcode == Opcodes.LOCAL_SCALAR || opcode == Opcodes.LOCAL_ARRAY ||
                opcode == Opcodes.LOCAL_HASH || opcode == Opcodes.LOCAL_GLOB ||
                opcode == Opcodes.PUSH_LOCAL_VARIABLE || opcode == Opcodes.LOCAL_SCALAR_SAVE_LEVEL ||
                opcode == Opcodes.PUSH_DEFER || opcode == Opcodes.SAVE_REGEX_STATE) {
            usesLocalization = true;
        }
        bytecode.add((int) opcode);
    }

    /**
     * Emit opcode and track tokenIndex for error reporting.
     * Use this for opcodes that may throw exceptions (DIE, method calls, etc.)
     */
    void emitWithToken(short opcode, int tokenIndex) {
        // Track if any localization opcodes are emitted (including defer blocks which use DVM)
        if (opcode == Opcodes.LOCAL_SCALAR || opcode == Opcodes.LOCAL_ARRAY ||
                opcode == Opcodes.LOCAL_HASH || opcode == Opcodes.LOCAL_GLOB ||
                opcode == Opcodes.PUSH_LOCAL_VARIABLE || opcode == Opcodes.LOCAL_SCALAR_SAVE_LEVEL ||
                opcode == Opcodes.PUSH_DEFER || opcode == Opcodes.SAVE_REGEX_STATE) {
            usesLocalization = true;
        }
        int pc = bytecode.size();
        pcToTokenIndex.put(pc, tokenIndex);
        bytecode.add((int) opcode);
    }

    void emit(int value) {
        bytecode.add(value);
    }

    void emitInt(int value) {
        bytecode.add(value);  // Full int in one slot
    }

    /**
     * Emit a short value (register index, small immediate, etc.).
     */
    private void emitShort(int value) {
        bytecode.add(value);
    }

    /**
     * Emit a register index as a short value.
     * Registers are now 16-bit (0-65535) instead of 8-bit (0-255).
     */
    void emitReg(int register) {
        bytecode.add(register);
    }

    /**
     * Patch a 2-short (4-byte) int offset at the specified position.
     * Used for forward jumps where the target is unknown at emit time.
     */
    void patchIntOffset(int position, int target) {
        // Store absolute target address in one slot
        bytecode.set(position, target);
    }

    /**
     * Helper for forward jumps - emit placeholder and return position for later patching.
     */
    private void emitJumpPlaceholder() {
        emitInt(0);  // 1 int slot placeholder
    }

    private void patchJump(int placeholderPos, int target) {
        patchIntOffset(placeholderPos, target);
    }

    /**
     * Patch a short offset at the specified position.
     * Used for forward jumps where the target is unknown at emit time.
     */
    private void patchShortOffset(int position, int target) {
        bytecode.set(position, target);
    }

    /**
     * Convert List<Integer> bytecode to int[] array.
     */
    private int[] toShortArray() {
        int[] result = new int[bytecode.size()];
        for (int i = 0; i < bytecode.size(); i++) {
            result[i] = bytecode.get(i);
        }
        return result;
    }

    @Override
    public void visit(ArrayLiteralNode node) {
        // Array literal: [expr1, expr2, ...]
        // In Perl, [..] creates an ARRAY REFERENCE (RuntimeScalar containing RuntimeArray)
        // Perl semantics: array literal elements are always evaluated in LIST context
        // Implementation:
        //   1. Create a list with all elements (in LIST context)
        //   2. Convert list to array reference using CREATE_ARRAY (which now returns reference)

        // Save current context and use LIST context for elements
        int savedContext = currentCallContext;
        currentCallContext = RuntimeContextType.LIST;

        // Fast path: empty array
        if (node.elements.isEmpty()) {
            // Create empty RuntimeList
            int listReg = allocateRegister();
            emit(Opcodes.CREATE_LIST);
            emitReg(listReg);
            emit(0); // count = 0

            // Convert to RuntimeArray reference (CREATE_ARRAY now returns reference!)
            int refReg = allocateRegister();
            emit(Opcodes.CREATE_ARRAY);
            emitReg(refReg);
            emitReg(listReg);

            lastResultReg = refReg;
            currentCallContext = savedContext;
            return;
        }

        // General case: evaluate all elements in LIST context
        int[] elementRegs = new int[node.elements.size()];
        for (int i = 0; i < node.elements.size(); i++) {
            compileNode(node.elements.get(i), -1, RuntimeContextType.LIST);
            elementRegs[i] = lastResultReg;
        }

        // Restore context
        currentCallContext = savedContext;

        // Create RuntimeList with all elements
        int listReg = allocateRegister();
        emit(Opcodes.CREATE_LIST);
        emitReg(listReg);
        emit(node.elements.size()); // count

        // Emit register numbers for each element
        for (int elemReg : elementRegs) {
            emitReg(elemReg);
        }

        // Convert list to array reference (CREATE_ARRAY now returns reference!)
        int refReg = allocateRegister();
        emit(Opcodes.CREATE_ARRAY);
        emitReg(refReg);
        emitReg(listReg);

        lastResultReg = refReg;
    }

    // =========================================================================
    // UNIMPLEMENTED VISITOR METHODS (TODO)
    // =========================================================================

    @Override
    public void visit(HashLiteralNode node) {
        // Hash literal: {key1 => value1, key2 => value2, ...}
        // Can also contain array expansion: {a => 1, @x}
        // In Perl, {...} creates a HASH REFERENCE (RuntimeScalar containing RuntimeHash)
        //
        // Implementation:
        //   1. Create a RuntimeList with all elements (including arrays)
        //   2. Call CREATE_HASH which flattens arrays and creates hash reference

        // Fast path: empty hash
        if (node.elements.isEmpty()) {
            int listReg = allocateRegister();
            emit(Opcodes.CREATE_LIST);
            emitReg(listReg);
            emit(0); // count = 0

            // Create hash reference (CREATE_HASH now returns reference!)
            int refReg = allocateRegister();
            emit(Opcodes.CREATE_HASH);
            emitReg(refReg);
            emitReg(listReg);

            lastResultReg = refReg;
            return;
        }

        // General case: evaluate all elements in LIST context
        // Arrays like @list must be compiled in LIST context to get their elements,
        // not SCALAR context which would return the count
        int[] elementRegs = new int[node.elements.size()];
        for (int i = 0; i < node.elements.size(); i++) {
            compileNode(node.elements.get(i), -1, RuntimeContextType.LIST);
            elementRegs[i] = lastResultReg;
        }

        // Create RuntimeList with all elements
        // Arrays will be included as-is; RuntimeHash.createHash() will flatten them
        int listReg = allocateRegister();
        emit(Opcodes.CREATE_LIST);
        emitReg(listReg);
        emit(node.elements.size()); // count

        // Emit register numbers for each element
        for (int elemReg : elementRegs) {
            emitReg(elemReg);
        }

        // Create hash reference (CREATE_HASH now returns reference!)
        int refReg = allocateRegister();
        emit(Opcodes.CREATE_HASH);
        emitReg(refReg);
        emitReg(listReg);

        lastResultReg = refReg;
    }

    @Override
    public void visit(SubroutineNode node) {
        if (node.useTryCatch) {
            // This is an eval block: eval { ... }
            visitEvalBlock(node);
        } else if (node.name == null || node.name.equals("<anon>")) {
            // Anonymous subroutine: sub { ... }
            visitAnonymousSubroutine(node);
        } else {
            // Named subroutine: sub foo { ... }
            // NOTE: Named subs are compiled by the parser, not here.
            // They use the JVM compiler which has full closure support.
            // This is fine because compiled and interpreted code share the same RuntimeCode API.
            visitNamedSubroutine(node);
        }
    }

    /**
     * Visit a named subroutine: sub foo { ... }
     * <p>
     * NOTE: In practice, named subroutines are compiled by the parser using the JVM compiler,
     * not the interpreter. This method exists for completeness but may not be called for
     * typical named sub definitions. The parser creates compiled RuntimeCode objects that
     * interoperate seamlessly with interpreted code via the shared RuntimeCode.apply() API.
     */
    private void visitNamedSubroutine(SubroutineNode node) {
        // Step 1: Collect closure variables.
        //
        // IMPORTANT: For named subs defined in eval STRING, the body may contain nested
        // eval STRING that references outer lexicals that are NOT referenced directly
        // in the AST (because they're inside a string). Perl still expects those outer
        // lexicals to be visible from inside the sub.
        //
        // Therefore capture all visible Perl variables (scalars/arrays/hashes) from the
        // current scope, not just variables referenced directly in the sub AST.
        TreeMap<Integer, String> closureVarsByReg = collectVisiblePerlVariables();

        List<String> closureVarNames = new ArrayList<>(closureVarsByReg.values());
        List<Integer> closureVarIndices = new ArrayList<>(closureVarsByReg.keySet());

        closureCapturedVarNames.addAll(closureVarNames);

        int beginId = 0;
        if (!closureVarIndices.isEmpty()) {
            beginId = EmitterMethodCreator.classCounter++;

            // Store each closure variable in PersistentVariable globals
            for (int i = 0; i < closureVarNames.size(); i++) {
                String varName = closureVarNames.get(i);
                int varReg = closureVarIndices.get(i);

                // Get the variable type from the sigil
                String sigil = varName.substring(0, 1);
                String bareVarName = varName.substring(1);
                String beginVarName = PersistentVariable.beginPackage(beginId) + "::" + bareVarName;

                // Store the variable value in PersistentVariable global
                int nameIdx = addToStringPool(beginVarName);
                switch (sigil) {
                    case "$" -> {
                        emit(Opcodes.STORE_GLOBAL_SCALAR);
                        emit(nameIdx);
                        emitReg(varReg);
                    }
                    case "@" -> {
                        emit(Opcodes.STORE_GLOBAL_ARRAY);
                        emit(nameIdx);
                        emitReg(varReg);
                    }
                    case "%" -> {
                        emit(Opcodes.STORE_GLOBAL_HASH);
                        emit(nameIdx);
                        emitReg(varReg);
                    }
                }
            }
        }

        // Step 3: Compile the subroutine body with a packed registry for captured variables.
        // The closure created at runtime packs capturedVars sequentially into registers 3..,
        // so the compiled sub body must use the same packed register layout.
        Map<String, Integer> packedRegistry = new HashMap<>();
        packedRegistry.put("this", 0);
        packedRegistry.put("@_", 1);
        packedRegistry.put("wantarray", 2);
        for (int i = 0; i < closureVarNames.size(); i++) {
            packedRegistry.put(closureVarNames.get(i), 3 + i);
        }

        BytecodeCompiler subCompiler = new BytecodeCompiler(
                this.sourceName,
                node.getIndex(),
                this.errorUtil,
                packedRegistry
        );
        // The parentRegistry constructor sets isEvalString=true (for eval STRING closures),
        // but named subs are NOT eval strings - clear the flag.
        subCompiler.isEvalString = false;
        subCompiler.symbolTable.setCurrentPackage(getCurrentPackage(),
                symbolTable.currentPackageIsClass());

        // Inherit pragma flags so BEGIN { $^H = ... } changes propagate into sub body
        inheritPragmaFlags(subCompiler);

        // Set the BEGIN ID in the sub-compiler so it knows to use RETRIEVE_BEGIN opcodes
        subCompiler.currentSubroutineBeginId = beginId;
        subCompiler.currentSubroutineClosureVars = new HashSet<>(closureVarNames);

        // Subroutine bodies should use RUNTIME context so the calling context
        // (VOID/SCALAR/LIST) propagates correctly at runtime via register 2 (wantarray).
        subCompiler.currentCallContext = RuntimeContextType.RUNTIME;

        // Step 4: Compile the subroutine body
        // Sub-compiler will use RETRIEVE_BEGIN opcodes for closure variables
        InterpretedCode subCode = subCompiler.compile(node.block);

        if (RuntimeCode.DISASSEMBLE) {
            System.out.println(Disassemble.disassemble(subCode));
        }

        // Step 5: Emit bytecode to create closure or simple code ref
        int codeReg = allocateRegister();

        if (closureVarIndices.isEmpty()) {
            RuntimeScalar codeScalar = new RuntimeScalar(subCode);
            subCode.__SUB__ = codeScalar;  // Set __SUB__ for self-reference
            int constIdx = addToConstantPool(codeScalar);
            emit(Opcodes.LOAD_CONST);
            emitReg(codeReg);
            emit(constIdx);
        } else {
            // Create a closure capturing the current register values.
            int templateIdx = addToConstantPool(subCode);
            emit(Opcodes.CREATE_CLOSURE);
            emitReg(codeReg);
            emit(templateIdx);
            emit(closureVarIndices.size());
            for (int regIdx : closureVarIndices) {
                emit(regIdx);
            }
        }

        // Step 6: Store in global namespace
        String fullName = node.name;
        if (!fullName.contains("::")) {
            fullName = getCurrentPackage() + "::" + fullName;  // Use getCurrentPackage() for proper package tracking
        }

        int nameIdx = addToStringPool(fullName);
        emit(Opcodes.STORE_GLOBAL_CODE);
        emit(nameIdx);
        emitReg(codeReg);

        // Step 7: Register subroutine location for %DB::sub (only in debug mode)
        if (DebugState.debugMode && errorUtil != null) {
            int startLine = errorUtil.getLineNumber(node.getIndex());
            // Use start line as end line for now (accurate end would require tracking block end)
            DebugState.registerSubroutine(fullName, sourceName, startLine, startLine);
        }

        lastResultReg = -1;
    }

    /**
     * Visit an anonymous subroutine: sub { ... }
     * <p>
     * Compiles the subroutine body to bytecode with closure support.
     * Anonymous subs capture lexical variables from the enclosing scope.
     */
    private void visitAnonymousSubroutine(SubroutineNode node) {
        // Step 1: Collect closure variables.
        //
        // IMPORTANT: Anonymous subs can contain nested eval STRING that references outer
        // lexicals only inside strings (so they won't appear as IdentifierNodes in the AST).
        // Perl still expects those lexicals to be visible to eval STRING at runtime.
        // Capture all visible Perl variables (scalars/arrays/hashes) from the current scope.
        TreeMap<Integer, String> closureVarsByReg = collectVisiblePerlVariables();

        List<String> closureVarNames = new ArrayList<>(closureVarsByReg.values());
        List<Integer> closureVarIndices = new ArrayList<>(closureVarsByReg.keySet());

        closureCapturedVarNames.addAll(closureVarNames);

        // Step 3: Create a new BytecodeCompiler for the subroutine body
        // Build a variable registry from current scope to pass to sub-compiler
        // This allows nested closures to see grandparent scope variables
        Map<String, Integer> parentRegistry = new HashMap<>();
        parentRegistry.put("this", 0);
        parentRegistry.put("@_", 1);
        parentRegistry.put("wantarray", 2);

        // Add captured variables with adjusted indices (starting at 3)
        for (int i = 0; i < closureVarNames.size(); i++) {
            parentRegistry.put(closureVarNames.get(i), 3 + i);
        }

        BytecodeCompiler subCompiler = new BytecodeCompiler(
                this.sourceName,
                node.getIndex(),
                this.errorUtil,
                parentRegistry  // Pass parent variable registry for nested closure support
        );
        // The parentRegistry constructor sets isEvalString=true (for eval STRING closures),
        // but anonymous subs are NOT eval strings - clear the flag.
        subCompiler.isEvalString = false;
        subCompiler.symbolTable.setCurrentPackage(getCurrentPackage(),
                symbolTable.currentPackageIsClass());

        // Inherit pragma flags so BEGIN { $^H = ... } changes propagate into sub body
        inheritPragmaFlags(subCompiler);
        
        // Check if this subroutine is a defer block
        Boolean isDeferBlock = (Boolean) node.getAnnotation("isDeferBlock");
        if (isDeferBlock != null && isDeferBlock) {
            subCompiler.isInDeferBlock = true;
        }

        // Check if this subroutine is a map/grep block - explicit return must use RETURN_NONLOCAL
        Boolean isMapGrepBlock = (Boolean) node.getAnnotation("isMapGrepBlock");
        if (isMapGrepBlock != null && isMapGrepBlock) {
            subCompiler.isInMapGrepBlock = true;
        }

        // Subroutine bodies should use RUNTIME context so the calling context
        // (VOID/SCALAR/LIST) propagates correctly at runtime via register 2 (wantarray).
        // Without this, the default LIST context is baked into all opcodes,
        // causing incorrect behavior when the sub is called in VOID or SCALAR context.
        subCompiler.currentCallContext = RuntimeContextType.RUNTIME;

        // Step 4: Compile the subroutine body
        // Sub-compiler will use parentRegistry to resolve captured variables
        InterpretedCode subCode = subCompiler.compile(node.block);
        subCode.prototype = node.prototype;
        subCode.attributes = node.attributes;
        subCode.packageName = getCurrentPackage();

        // Copy the isMapGrepBlock flag to the runtime code object so that
        // ListOperators.map/grep and RuntimeCode.apply() can detect non-local returns
        if (subCompiler.isInMapGrepBlock) {
            subCode.isMapGrepBlock = true;
        }

        if (RuntimeCode.DISASSEMBLE) {
            System.out.println(Disassemble.disassemble(subCode));
        }

        // Step 5: Create closure or simple code ref
        int codeReg = allocateRegister();

        if (closureVarIndices.isEmpty()) {
            // No closures - just wrap the InterpretedCode
            RuntimeScalar codeScalar = new RuntimeScalar(subCode);
            subCode.__SUB__ = codeScalar;  // Set __SUB__ for self-reference
            // Dispatch MODIFY_CODE_ATTRIBUTES for anonymous subs with non-builtin attributes
            if (subCode.attributes != null && !subCode.attributes.isEmpty()
                    && subCode.packageName != null) {
                Attributes.runtimeDispatchModifyCodeAttributes(subCode.packageName, codeScalar);
            }
            int constIdx = addToConstantPool(codeScalar);
            emit(Opcodes.LOAD_CONST);
            emitReg(codeReg);
            emit(constIdx);
        } else {
            // Has closures - emit CREATE_CLOSURE
            int templateIdx = addToConstantPool(subCode);
            emit(Opcodes.CREATE_CLOSURE);
            emitReg(codeReg);
            emit(templateIdx);
            emit(closureVarIndices.size());
            for (int regIdx : closureVarIndices) {
                emit(regIdx);
            }
        }

        lastResultReg = codeReg;
    }

    /**
     * Visit an anonymous subroutine: sub { ... }
     *
     * Compiles the subroutine body to bytecode and creates an InterpretedCode instance.
     * Handles closure variable capture if needed.
     *
     * The result is an InterpretedCode wrapped in RuntimeScalar, stored in lastResultReg.
     */

    /**
     * Visit an eval block: eval { ... }
     * <p>
     * Generates:
     * EVAL_TRY catch_offset    # Set up exception handler, clear $@
     * ... block bytecode ...
     * EVAL_END                 # Clear $@ on success
     * GOTO end
     * LABEL catch:
     * EVAL_CATCH rd            # Set $@, store undef in rd
     * LABEL end:
     * <p>
     * The result is stored in lastResultReg.
     */
    private void visitEvalBlock(SubroutineNode node) {
        int resultReg = allocateRegister();

        // Emit EVAL_TRY with placeholder for catch target (absolute address)
        emitWithToken(Opcodes.EVAL_TRY, node.getIndex());
        int catchTargetPos = bytecode.size();
        emitInt(0); // Placeholder for absolute catch address (4 bytes)

        // Track eval block nesting for "goto &sub from eval" detection
        evalBlockDepth++;

        // Compile the eval block body
        compileNode(node.block, resultReg, currentCallContext);

        evalBlockDepth--;

        if (lastResultReg >= 0) {
            emitAliasWithTarget(resultReg, lastResultReg);
        }

        // Emit EVAL_END (clears $@)
        emit(Opcodes.EVAL_END);

        // Jump over catch block to end
        emit(Opcodes.GOTO);
        int gotoEndPos = bytecode.size();
        emitInt(0); // Placeholder for absolute end address (4 bytes)

        // CATCH block starts here
        int catchPc = bytecode.size();

        // Patch EVAL_TRY with absolute catch target (4 bytes)
        patchIntOffset(catchTargetPos, catchPc);

        // Emit EVAL_CATCH (sets $@, stores undef)
        emit(Opcodes.EVAL_CATCH);
        emitReg(resultReg);

        // END label (after catch)
        int endPc = bytecode.size();

        // Patch GOTO with absolute end target (4 bytes)
        patchIntOffset(gotoEndPos, endPc);

        lastResultReg = resultReg;
    }

    /**
     * Compiles a foreach-style loop ({@code for my $var (@list) { body }}).
     *
     * <p>Uses a <em>do-while</em> bytecode layout to eliminate the back-edge
     * {@code GOTO} that would otherwise execute on every iteration:
     * <pre>
     *   GOTO loopCheck          // one-time entry jump
     * body:
     *   &lt;body&gt;
     * loopCheck:               // next/continue target
     *   FOREACH_NEXT_OR_EXIT -&gt; body   // jump back if has next; fall through if exhausted
     * exit:
     * </pre>
     *
     * <p>For global loop variables (e.g. implicit {@code $_}, {@code needsArrayOfAlias=true}):
     * <ul>
     *   <li>{@code LOCAL_SCALAR_SAVE_LEVEL} atomically saves {@code getLocalLevel()} into
     *       {@code levelReg} <em>before</em> calling {@code makeLocal}, so the pre-push
     *       level is available for restore.</li>
     *   <li>{@code FOREACH_GLOBAL_NEXT_OR_EXIT} combines hasNext + next +
     *       {@code aliasGlobalVariable} + conditional jump per iteration.</li>
     *   <li>{@code POP_LOCAL_LEVEL(levelReg)} after loop exit restores {@code $_} to
     *       the pre-{@code makeLocal} state, correct for any nesting depth.</li>
     * </ul>
     */
    @Override
    public void visit(For1Node node) {
        // For1Node: foreach-style loop
        // for my $var (@list) { body }
        //
        // For global loop variables (needsArrayOfAlias=true, e.g. implicit $_):
        //   The parser wraps this as BlockNode([local $_, For1Node]).
        //   visit(BlockNode) detects this pattern and skips the 'local $_' child directly,
        //   so For1Node emits LOCAL_SCALAR_SAVE_LEVEL here (saves pre-push level atomically),
        //   uses FOREACH_GLOBAL_NEXT_OR_EXIT per iteration (hasNext+next+alias),
        //   and POP_LOCAL_LEVEL after the loop (restores $_ correctly for nested loops).

        // Determine if this is a global loop variable (e.g. $_).
        String globalLoopVarName = null;
        if (node.needsArrayOfAlias && node.variable instanceof OperatorNode varOp
                && varOp.operator.equals("$") && varOp.operand instanceof IdentifierNode idNode) {
            globalLoopVarName = NameNormalizer.normalizeVariableName(idNode.name, getCurrentPackage());
        }

        // Step 1: Evaluate list in list context
        compileNode(node.list, -1, RuntimeContextType.LIST);
        int listReg = lastResultReg;

        int foreachRegexSaveReg = -1;
        if (RegexUsageDetector.containsRegexOperation(node)) {
            foreachRegexSaveReg = allocateRegister();
            emit(Opcodes.SAVE_REGEX_STATE);
            emitReg(foreachRegexSaveReg);
        }

        // Step 2: Create iterator from the list
        int iterReg = allocateRegister();
        emit(Opcodes.ITERATOR_CREATE);
        emitReg(iterReg);
        emitReg(listReg);

        // Step 3: Allocate loop variable register BEFORE entering scope
        // For pre-existing lexical variables (e.g., `my $k; foreach $k (...)`),
        // reuse the existing register so FOREACH_NEXT_OR_EXIT writes to the same
        // slot the loop body reads from.
        int varReg = -1;
        if (globalLoopVarName == null && node.variable instanceof OperatorNode varOp
                && varOp.operator.equals("$") && varOp.operand instanceof IdentifierNode idNode) {
            String varName = "$" + idNode.name;
            varReg = getVariableRegister(varName);
        }
        if (varReg == -1) {
            varReg = allocateRegister();
        }

        // Step 3b: For global loop variable: emit LOCAL_SCALAR_SAVE_LEVEL.
        // This atomically saves getLocalLevel() into levelReg (pre-push), then calls makeLocal.
        // POP_LOCAL_LEVEL(levelReg) after the loop correctly restores $_ for any nesting depth.
        int levelReg = -1;
        if (globalLoopVarName != null) {
            levelReg = allocateRegister();
            int nameIdx = addToStringPool(globalLoopVarName);
            emit(Opcodes.LOCAL_SCALAR_SAVE_LEVEL);
            emitReg(varReg);      // rd: receives makeLocal result (the new localized container)
            emitReg(levelReg);    // levelReg: receives pre-push dynamic level
            emit(nameIdx);
        }

        // Step 4: Enter new scope for loop variable
        enterScope();

        // Step 5: If we have a named lexical loop variable, add it to the scope now
        if (node.variable != null && node.variable instanceof OperatorNode varOp2) {
            if (varOp2.operator.equals("my") && varOp2.operand instanceof OperatorNode sigilOp) {
                if (sigilOp.operator.equals("$") && sigilOp.operand instanceof IdentifierNode) {
                    String varName = "$" + ((IdentifierNode) sigilOp.operand).name;
                    registerVariable(varName, varReg);
                }
            }
        }

        // Step 6: Emit initial GOTO to the loop check (do-while structure).
        // This avoids a back-edge GOTO on every iteration: the superinstruction at
        // the bottom jumps backward to the body start if the iterator has more elements.
        // Layout: GOTO check | body | check: FOREACH_NEXT_OR_EXIT → body | exit
        emit(Opcodes.GOTO);
        int entryJumpPc = bytecode.size();
        emitInt(0);   // placeholder: will be patched to loopCheckPc

        // Step 7: Body start (redo jumps here)
        int bodyStartPc = bytecode.size();
        LoopInfo loopInfo = new LoopInfo(node.labelName, bodyStartPc, true);
        loopStack.push(loopInfo);

        // Step 8: Execute body
        if (node.body != null) {
            node.body.accept(this);
        }

        // Step 9: Loop check (next/continue jumps here) - the superinstruction
        int loopCheckPc = bytecode.size();
        loopInfo.continuePc = loopCheckPc;
        patchJump(entryJumpPc, loopCheckPc);   // patch the entry GOTO

        // Step 10: Emit the loop superinstruction at the bottom (do-while check).
        // If iterator has next: load element (and alias for global vars), jump back to body.
        // If exhausted: fall through to exit.
        int loopEndJumpPc;
        if (globalLoopVarName != null) {
            // FOREACH_GLOBAL_NEXT_OR_EXIT: hasNext + next + aliasGlobalVariable + conditional jump
            int nameIdx = addToStringPool(globalLoopVarName);
            emit(Opcodes.FOREACH_GLOBAL_NEXT_OR_EXIT);
            emitReg(varReg);
            emitReg(iterReg);
            emit(nameIdx);
            loopEndJumpPc = bytecode.size();
            emitInt(bodyStartPc);   // jump backward to body start if has next
        } else {
            // FOREACH_NEXT_OR_EXIT: hasNext + next + conditional jump (lexical or temp var)
            emit(Opcodes.FOREACH_NEXT_OR_EXIT);
            emitReg(varReg);
            emitReg(iterReg);
            loopEndJumpPc = bytecode.size();
            emitInt(bodyStartPc);   // jump backward to body start if has next
        }

        // Step 11: Loop exit - fall-through after the superinstruction
        int loopEndPc = bytecode.size();

        // Step 11b: Restore global loop variable after loop exits.
        // POP_LOCAL_LEVEL(levelReg) pops to the pre-makeLocal level, undoing both
        // the makeLocal push and all aliasGlobalVariable replacements. Correct for
        // any nesting depth because levelReg holds the exact pre-push level.
        if (levelReg >= 0) {
            emit(Opcodes.POP_LOCAL_LEVEL);
            emitReg(levelReg);
        }

        // Step 12: Patch all last/next/redo jumps
        for (int pc : loopInfo.breakPcs) {
            patchJump(pc, loopEndPc);
        }
        for (int pc : loopInfo.nextPcs) {
            patchJump(pc, loopCheckPc);
        }
        for (int pc : loopInfo.redoPcs) {
            patchJump(pc, bodyStartPc);
        }

        // Step 13: Pop loop info and exit scope
        loopStack.pop();
        exitScope(true);  // safe to flush — foreach loop, not subroutine body

        if (foreachRegexSaveReg >= 0) {
            emit(Opcodes.RESTORE_REGEX_STATE);
            emitReg(foreachRegexSaveReg);
        }

        lastResultReg = -1;  // For loop returns empty
    }

    @Override
    public void visit(For3Node node) {
        // For3Node: C-style for loop, while loop, do-while loop, or bare block
        // for (init; condition; increment) { body }
        // while (condition) { body }
        // do { body } while (condition);
        // { body }  (bare block - isSimpleBlock=true)

        // Handle bare blocks (simple blocks) differently - they execute once, not loop
        if (node.isSimpleBlock) {
            // Simple bare block: { statements; }
            // Allocate a result register before entering scope when in non-VOID context,
            // so the value is accessible after the scope exits (same pattern as BlockNode).
            int outerResultReg = -1;
            if (currentCallContext != RuntimeContextType.VOID) {
                outerResultReg = allocateOutputRegister();
            }

            int labelIdx = -1;
            int exitPcPlaceholder = -1;
            if (node.labelName != null) {
                labelIdx = addToStringPool(node.labelName);
                emit(Opcodes.PUSH_LABELED_BLOCK);
                emit(labelIdx);
                exitPcPlaceholder = bytecode.size();
                emitInt(0);
            }

            // Save local variable level so that `last` exits restore `local` variables.
            // The body's BlockNode has its own GET_LOCAL_LEVEL/POP_LOCAL_LEVEL, but `last`
            // bypasses the body's POP_LOCAL_LEVEL. This outer pair catches that case.
            // On normal exit, the body already restored locals, so this is a no-op.
            int blockLocalLevelReg = -1;
            if (FindDeclarationVisitor.containsLocalOrDefer(node)) {
                blockLocalLevelReg = allocateRegister();
                emit(Opcodes.GET_LOCAL_LEVEL);
                emitReg(blockLocalLevelReg);
            }

            // Push loop info so that redo/next/last inside bare blocks work
            // (Perl 5 allows redo/next/last in bare blocks)
            // Unlabeled bare blocks are targets for unlabeled redo/next/last;
            // labeled blocks (like SKIP: { }) are only targeted by matching label.
            int bodyStartPc = bytecode.size();
            boolean isUnlabeledTarget = (node.labelName == null);
            LoopInfo loopInfo = new LoopInfo(
                    isUnlabeledTarget ? null : node.labelName,
                    bodyStartPc, true);
            loopStack.push(loopInfo);

            enterScope();
            try {
                if (node.body != null) {
                    compileNode(node.body, outerResultReg, currentCallContext);
                }
                if (outerResultReg >= 0 && lastResultReg >= 0) {
                    emitAliasWithTarget(outerResultReg, lastResultReg);
                }
            } finally {
                // Exit scope to clean up lexical variables
                exitScope(true);  // safe to flush — foreach body, not subroutine
            }

            // next jumps here (continue point = end of body, before exit)
            loopInfo.continuePc = bytecode.size();

            // Pop loop info and patch jump targets
            loopStack.pop();
            // Patch redo PCs to jump to body start
            for (int pc2 : loopInfo.redoPcs) {
                patchJump(pc2, bodyStartPc);
            }
            // Patch next PCs to jump to continue point
            for (int pc2 : loopInfo.nextPcs) {
                patchJump(pc2, loopInfo.continuePc);
            }
            // Patch last (break) PCs - will be patched to end label below
            // (we need the end PC first)

            if (node.labelName != null) {
                emit(Opcodes.POP_LABELED_BLOCK);
                int exitPc = bytecode.size();
                patchJump(exitPcPlaceholder, exitPc);
            }

            // Patch last (break) PCs to jump to local cleanup (or past the block if no locals).
            // POP_LOCAL_LEVEL must be at endPc so `last` runs it.
            // On normal exit this is a no-op since the body's POP_LOCAL_LEVEL already ran.
            int endPc = bytecode.size();
            if (blockLocalLevelReg >= 0) {
                emit(Opcodes.POP_LOCAL_LEVEL);
                emitReg(blockLocalLevelReg);
            }
            for (int pc2 : loopInfo.breakPcs) {
                patchJump(pc2, endPc);
            }

            lastResultReg = outerResultReg;
            return;
        }

        int loopRegexSaveReg = -1;
        if (node.useNewScope && RegexUsageDetector.containsRegexOperation(node)) {
            loopRegexSaveReg = allocateRegister();
            emit(Opcodes.SAVE_REGEX_STATE);
            emitReg(loopRegexSaveReg);
        }

        // Step 1: Execute initialization (for C-style loops only)
        if (node.initialization != null) {
            node.initialization.accept(this);
        }

        // Save local variable level so that `last` exits restore `local` variables.
        // The body's BlockNode has its own GET_LOCAL_LEVEL/POP_LOCAL_LEVEL, but `last`
        // bypasses the body's POP_LOCAL_LEVEL. This outer pair catches that case.
        // On normal exit, the body already restored locals, so POP_LOCAL_LEVEL is a no-op.
        int for3LocalLevelReg = -1;
        if (FindDeclarationVisitor.containsLocalOrDefer(node)) {
            for3LocalLevelReg = allocateRegister();
            emit(Opcodes.GET_LOCAL_LEVEL);
            emitReg(for3LocalLevelReg);
        }

        // Step 2: Push loop info onto stack for last/next/redo
        int loopStartPc = bytecode.size();
        // do-while is NOT a true loop (can't use last/next/redo); while/for are true loops
        LoopInfo loopInfo = new LoopInfo(node.labelName, loopStartPc, !node.isDoWhile);
        loopStack.push(loopInfo);

        int loopEndJumpPc = -1;

        if (node.isDoWhile) {
            // do-while loop: body executes at least once, condition checked at end
            // Step 3: Execute body (redo jumps here)
            if (node.body != null) {
                node.body.accept(this);
            }

            // Step 4: Continue point (next jumps here)
            loopInfo.continuePc = bytecode.size();

            // Step 5: Execute continue block if present
            if (node.continueBlock != null) {
                node.continueBlock.accept(this);
            }

            // Step 6: Execute increment (for C-style for loops)
            if (node.increment != null) {
                node.increment.accept(this);
            }

            // Step 7: Check condition
            // Check if condition is a compile-time constant (e.g., "do {} until TRUE_CONST")
            String currentPackage = symbolTable.getCurrentPackage();
            Boolean constantCondition = ConstantFoldingVisitor.getConstantConditionValue(node.condition, currentPackage);

            if (constantCondition != null) {
                if (constantCondition) {
                    // Condition is constant true — infinite loop, jump back unconditionally
                    emit(Opcodes.GOTO);
                    emitInt(loopStartPc);
                }
                // else: condition is constant false — don't jump back, body runs exactly once
            } else {
                int condReg = allocateRegister();
                if (node.condition != null) {
                    // Evaluate condition in SCALAR context (need boolean result)
                    compileNode(node.condition, -1, RuntimeContextType.SCALAR);
                    condReg = lastResultReg;
                } else {
                    // No condition means infinite loop - load true
                    emit(Opcodes.LOAD_INT);
                    emitReg(condReg);
                    emitInt(1);
                }

                // Step 8: If condition is true, jump back to start
                emit(Opcodes.GOTO_IF_TRUE);
                emitReg(condReg);
                emitInt(loopStartPc);
            }

        } else {
            // while/for loop: condition checked before body
            // Step 3: Check condition (redo jumps here)
            int condReg = allocateRegister();
            if (node.condition != null) {
                // Evaluate condition in SCALAR context (need boolean result)
                compileNode(node.condition, -1, RuntimeContextType.SCALAR);
                condReg = lastResultReg;
            } else {
                // No condition means infinite loop - load true
                emit(Opcodes.LOAD_INT);
                emitReg(condReg);
                emitInt(1);
            }

            // Step 4: If condition is false, jump to end
            emit(Opcodes.GOTO_IF_FALSE);
            emitReg(condReg);
            loopEndJumpPc = bytecode.size();
            emitInt(0);  // Placeholder for jump target (will be patched)

            // Step 5: Execute body
            if (node.body != null) {
                node.body.accept(this);
            }

            // Step 6: Continue point (next jumps here)
            loopInfo.continuePc = bytecode.size();

            // Step 7: Execute continue block if present
            if (node.continueBlock != null) {
                node.continueBlock.accept(this);
            }

            // Step 8: Execute increment (for C-style for loops)
            if (node.increment != null) {
                node.increment.accept(this);
            }

            // Step 9: Jump back to loop start
            emit(Opcodes.GOTO);
            emitInt(loopStartPc);
        }

        // Step 10: Loop end - restore local variables and patch jumps.
        // POP_LOCAL_LEVEL must be at loopEndPc so `last` runs it.
        // On normal exit (condition false) this is a no-op since the body already cleaned up.
        int loopEndPc = bytecode.size();
        if (for3LocalLevelReg >= 0) {
            emit(Opcodes.POP_LOCAL_LEVEL);
            emitReg(for3LocalLevelReg);
        }
        if (loopEndJumpPc != -1) {
            patchJump(loopEndJumpPc, loopEndPc);
        }

        // Step 11: Patch all last/next/redo jumps
        for (int pc : loopInfo.breakPcs) {
            patchJump(pc, loopEndPc);
        }
        for (int pc : loopInfo.nextPcs) {
            patchJump(pc, loopInfo.continuePc);
        }
        for (int pc : loopInfo.redoPcs) {
            patchJump(pc, loopStartPc);
        }

        // Step 12: Pop loop info
        loopStack.pop();

        if (loopRegexSaveReg >= 0) {
            emit(Opcodes.RESTORE_REGEX_STATE);
            emitReg(loopRegexSaveReg);
        }

        lastResultReg = -1;  // For loop returns empty
    }

    @Override
    public void visit(IfNode node) {
        // Try to evaluate the condition at compile time for dead code elimination
        String currentPackage = symbolTable.getCurrentPackage();
        Boolean constantValue = ConstantFoldingVisitor.getConstantConditionValue(node.condition, currentPackage);

        // For "unless", invert the condition
        if (constantValue != null && "unless".equals(node.operator)) {
            constantValue = !constantValue;
        }

        // If we have a constant condition, we can eliminate dead code
        if (constantValue != null) {
            if (constantValue) {
                // Condition is constant true - compile only the then branch
                if (node.thenBranch != null) {
                    node.thenBranch.accept(this);
                }
            } else {
                // Condition is constant false - compile only the else branch
                if (node.elseBranch != null) {
                    node.elseBranch.accept(this);
                } else {
                    lastResultReg = -1;
                }
            }
            return;
        }

        // Non-constant condition - compile normal if/else bytecode
        compileNode(node.condition, -1, RuntimeContextType.SCALAR);
        int condReg = lastResultReg;

        // Mark position for forward jump to else/end
        int ifFalsePos = bytecode.size();
        // Invert condition for 'unless'
        if ("unless".equals(node.operator)) {
            emit(Opcodes.GOTO_IF_TRUE);
        } else {
            emit(Opcodes.GOTO_IF_FALSE);
        }
        emitReg(condReg);
        emitInt(0); // Placeholder for else/end target

        // Compile then block
        if (node.thenBranch != null) {
            node.thenBranch.accept(this);
        }
        int thenResultReg = lastResultReg;

        if (node.elseBranch != null) {
            // Need to jump over else block after then block executes
            int gotoEndPos = bytecode.size();
            emit(Opcodes.GOTO);
            emitInt(0); // Placeholder for end target

            // Patch if-false jump to here (start of else block)
            int elseStart = bytecode.size();
            patchIntOffset(ifFalsePos + 2, elseStart);

            // Compile else block
            compileNode(node.elseBranch, thenResultReg, currentCallContext);
            int elseResultReg = lastResultReg;

            if (thenResultReg >= 0 && elseResultReg >= 0) {
                emitAliasWithTarget(thenResultReg, elseResultReg);
            }

            // Patch goto-end jump to here
            int endPos = bytecode.size();
            patchIntOffset(gotoEndPos + 1, endPos);

            lastResultReg = thenResultReg >= 0 ? thenResultReg : elseResultReg;
        } else {
            // No else block - patch if-false jump to here (after then block)
            int endPos = bytecode.size();
            patchIntOffset(ifFalsePos + 2, endPos);

            lastResultReg = thenResultReg;
        }
    }

    @Override
    public void visit(TernaryOperatorNode node) {
        // condition ? true_expr : false_expr
        // Implementation:
        //   eval condition
        //   if (!condition) goto false_label
        //   rd = true_expr
        //   goto end_label
        //   false_label:
        //   rd = false_expr
        //   end_label:

        int rd = allocateOutputRegister();

        compileNode(node.condition, -1, RuntimeContextType.SCALAR);
        int condReg = lastResultReg;

        int ifFalsePos = bytecode.size();
        emit(Opcodes.GOTO_IF_FALSE);
        emitReg(condReg);
        emitInt(0);

        compileNode(node.trueExpr, rd, currentCallContext);
        emitAliasWithTarget(rd, lastResultReg);

        int gotoEndPos = bytecode.size();
        emit(Opcodes.GOTO);
        emitInt(0);

        int falseStart = bytecode.size();
        patchIntOffset(ifFalsePos + 2, falseStart);

        compileNode(node.falseExpr, rd, currentCallContext);
        emitAliasWithTarget(rd, lastResultReg);

        // Patch goto-end jump to here
        int endPos = bytecode.size();
        patchIntOffset(gotoEndPos + 1, endPos);

        lastResultReg = rd;
    }

    @Override
    public void visit(TryNode node) {
        throw new UnsupportedOperationException("Try/catch not yet implemented");
    }

    @Override
    public void visit(DeferNode node) {
        // Compile the defer block as a closure
        // Create a SubroutineNode to wrap the block
        SubroutineNode closureNode = new SubroutineNode(
                null, null, null, node.block, false, node.tokenIndex);
        // Mark the closure as a defer block so control flow checks can be performed
        closureNode.setAnnotation("isDeferBlock", true);
        closureNode.accept(this);
        int codeReg = lastResultReg;

        // Create DeferBlock and push onto dynamic variable stack
        // PUSH_DEFER takes code_reg and args_reg (@_ is always register 1)
        // This ensures the defer block sees the same @_ as the enclosing scope
        emit(Opcodes.PUSH_DEFER);
        emitReg(codeReg);
        emitReg(1);  // @_ is always in register 1

        lastResultReg = -1; // defer doesn't produce a value
    }

    @Override
    public void visit(LabelNode node) {
        int pc = bytecode.size();
        gotoLabelPcs.put(node.label, pc);
        for (Object[] pending : pendingGotos) {
            if (node.label.equals(pending[1])) {
                patchIntOffset((Integer) pending[0], pc);
            }
        }
        pendingGotos.removeIf(p -> node.label.equals(p[1]));
        lastResultReg = -1;
    }

    @Override
    public void visit(CompilerFlagNode node) {
        if (emitterContext != null && emitterContext.symbolTable != null) {
            ScopedSymbolTable st = emitterContext.symbolTable;
            st.warningFlagsStack.pop();
            st.warningFlagsStack.push((java.util.BitSet) node.getWarningFlags().clone());
            st.warningFatalStack.pop();
            st.warningFatalStack.push((java.util.BitSet) node.getWarningFatalFlags().clone());
            st.warningDisabledStack.pop();
            st.warningDisabledStack.push((java.util.BitSet) node.getWarningDisabledFlags().clone());
            st.featureFlagsStack.pop();
            st.featureFlagsStack.push(node.getFeatureFlags());
            st.strictOptionsStack.pop();
            st.strictOptionsStack.push(node.getStrictOptions());
        }
        symbolTable.featureFlagsStack.pop();
        symbolTable.featureFlagsStack.push(node.getFeatureFlags());
        symbolTable.strictOptionsStack.pop();
        symbolTable.strictOptionsStack.push(node.getStrictOptions());
        symbolTable.warningFlagsStack.pop();
        symbolTable.warningFlagsStack.push((java.util.BitSet) node.getWarningFlags().clone());
        symbolTable.warningFatalStack.pop();
        symbolTable.warningFatalStack.push((java.util.BitSet) node.getWarningFatalFlags().clone());
        symbolTable.warningDisabledStack.pop();
        symbolTable.warningDisabledStack.push((java.util.BitSet) node.getWarningDisabledFlags().clone());

        // Update per-call-site $^H and %^H for caller()[8] and caller()[10]
        WarningBitsRegistry.setCallSiteHints(node.getStrictOptions());
        WarningBitsRegistry.snapshotCurrentHintHash();

        lastResultReg = -1;
    }

    @Override
    public void visit(FormatNode node) {
        // Format declarations are handled at the JVM compilation stage.
        // When the interpreter backend processes the AST, formats are already
        // registered, so this is a no-op.
        lastResultReg = -1;
    }

    @Override
    public void visit(ListNode node) {
        // Handle ListNode - represents (expr1, expr2, ...) in Perl

        // Fast path: empty list
        if (node.elements.isEmpty()) {
            // In SCALAR context, return undef; in LIST context, return empty list
            if (currentCallContext == RuntimeContextType.SCALAR) {
                int rd = allocateOutputRegister();
                emit(Opcodes.LOAD_UNDEF);
                emitReg(rd);
                lastResultReg = rd;
            } else {
                int listReg = allocateRegister();
                emit(Opcodes.CREATE_LIST);
                emitReg(listReg);
                emit(0); // count = 0
                lastResultReg = listReg;
            }
            return;
        }

        // In SCALAR context, evaluate all elements except last for side effects
        // and return only the last element's value (like compiled backend does)
        if (currentCallContext == RuntimeContextType.SCALAR) {
            // Evaluate all elements except the last in SCALAR context for side effects
            for (int i = 0; i < node.elements.size() - 1; i++) {
                node.elements.get(i).accept(this);
                // Result is discarded (side effects only)
            }
            // Evaluate and keep the last element
            node.elements.get(node.elements.size() - 1).accept(this);
            // lastResultReg already contains the last element's value
            return;
        }

        // Fast path: single element in LIST context
        // In list context, returns a RuntimeList with one element
        // List elements should be evaluated in LIST context
        if (node.elements.size() == 1) {
            compileNode(node.elements.get(0), -1, RuntimeContextType.LIST);
            int elemReg = lastResultReg;

            int listReg = allocateRegister();
            emit(Opcodes.CREATE_LIST);
            emitReg(listReg);
            emit(1); // count = 1
            emitReg(elemReg);
            lastResultReg = listReg;
            return;
        }

        // General case: multiple elements in LIST context
        // Evaluate each element into a register
        int[] elementRegs = new int[node.elements.size()];
        for (int i = 0; i < node.elements.size(); i++) {
            compileNode(node.elements.get(i), -1, RuntimeContextType.LIST);
            elementRegs[i] = lastResultReg;
        }

        // Create RuntimeList with all elements
        int listReg = allocateRegister();
        emit(Opcodes.CREATE_LIST);
        emitReg(listReg);
        emit(node.elements.size()); // count

        // Emit register numbers for each element
        for (int elemReg : elementRegs) {
            emitReg(elemReg);
        }

        lastResultReg = listReg;
    }

    /**
     * Get the symbol table for package/strict/feature tracking.
     * Used by refactored compiler classes.
     */
    ScopedSymbolTable getSymbolTable() {
        return symbolTable;
    }

    // =================================================================
    // PACKAGE-LEVEL ACCESSORS FOR REFACTORED CLASSES
    // =================================================================

    /**
     * Get the source name for error messages.
     * Used by refactored compiler classes.
     */
    String getSourceName() {
        return sourceName;
    }

    /**
     * Get the source line for error messages.
     * Used by refactored compiler classes.
     */
    int getSourceLine() {
        return sourceLine;
    }

    /**
     * Get the bytecode list for position tracking.
     * Used by refactored compiler classes for jump patching.
     */
    List<Integer> getBytecode() {
        return bytecode;
    }

    Map<String, Integer> getCapturedVarIndices() {
        return capturedVarIndices;
    }

    /**
     * Handle loop control operators: last, next, redo
     * Extracted for use by CompileOperator.
     */
    void handleLoopControlOperator(OperatorNode node, String op) {
        // Extract label if present
        String labelStr = null;
        boolean isDynamicLabel = false;
        int dynamicLabelReg = -1;
        if (node.operand instanceof ListNode labelNode && !labelNode.elements.isEmpty()) {
            Node arg = labelNode.elements.getFirst();
            if (arg instanceof IdentifierNode) {
                labelStr = ((IdentifierNode) arg).name;
            } else {
                // Dynamic label: last EXPR, next EXPR, redo EXPR
                // Evaluate expression at runtime to get label string
                isDynamicLabel = true;
                compileNode(arg, -1, RuntimeContextType.SCALAR);
                dynamicLabelReg = lastResultReg;
            }
        }

        // Dynamic label always uses non-local control flow
        if (isDynamicLabel) {
            short createDynOp = op.equals("last") ? Opcodes.CREATE_LAST_DYNAMIC
                    : op.equals("next") ? Opcodes.CREATE_NEXT_DYNAMIC
                    : Opcodes.CREATE_REDO_DYNAMIC;
            int rd = allocateOutputRegister();
            emit(createDynOp);
            emitReg(rd);
            emitReg(dynamicLabelReg);
            emit(Opcodes.RETURN);
            emitReg(rd);
            return;
        }

        // Find the target loop
        LoopInfo targetLoop = null;
        if (labelStr == null) {
            // Unlabeled: find innermost true loop (skip do-while/bare blocks)
            for (int i = loopStack.size() - 1; i >= 0; i--) {
                LoopInfo loop = loopStack.get(i);
                if (loop.isTrueLoop) {
                    targetLoop = loop;
                    break;
                }
            }
        } else {
            // Labeled: search for matching label
            for (int i = loopStack.size() - 1; i >= 0; i--) {
                LoopInfo loop = loopStack.get(i);
                if (labelStr.equals(loop.label)) {
                    targetLoop = loop;
                    break;
                }
            }
        }

        if (targetLoop == null) {
            // No matching loop found - non-local control flow
            // Emit CREATE_LAST/NEXT/REDO + RETURN to propagate via RuntimeControlFlowList
            short createOp = op.equals("last") ? Opcodes.CREATE_LAST
                    : op.equals("next") ? Opcodes.CREATE_NEXT
                    : Opcodes.CREATE_REDO;
            int rd = allocateOutputRegister();
            emit(createOp);
            emitReg(rd);
            int labelIdx = labelStr != null ? addToStringPool(labelStr) : 255;
            emitReg(labelIdx);
            emit(Opcodes.RETURN);
            emitReg(rd);
            return;
        }

        // Check if this is a pseudo-loop (do-while/bare block) which doesn't support last/next/redo
        if (!targetLoop.isTrueLoop) {
            throwCompilerException("Can't \"" + op + "\" outside a loop block", node.getIndex());
        }

        // Emit the opcode and record the PC to be patched later
        short opcode = op.equals("last") ? Opcodes.LAST
                : op.equals("next") ? Opcodes.NEXT
                : Opcodes.REDO;

        emitWithToken(opcode, node.getIndex());

        // Record the PC to be patched (it's the PC of the jump offset operand)
        int patchPc = bytecode.size();
        emitInt(0); // Placeholder offset to be patched later

        // Add to the appropriate list in the target loop
        if (op.equals("last")) {
            targetLoop.breakPcs.add(patchPc);
        } else if (op.equals("next")) {
            targetLoop.nextPcs.add(patchPc);
        } else { // redo
            targetLoop.redoPcs.add(patchPc);
        }
    }

    // Helper class to track loop boundaries
    private static class LoopInfo {
        final String label;           // Loop label (null if unlabeled)
        final int startPc;           // PC for redo (start of loop body)
        final List<Integer> breakPcs; // PCs to patch for last (break)
        final List<Integer> nextPcs;  // PCs to patch for next
        final List<Integer> redoPcs;  // PCs to patch for redo
        final boolean isTrueLoop;    // True for for/while/foreach; false for do-while/bare blocks
        int continuePc;              // PC for next (continue block or increment)

        LoopInfo(String label, int startPc, boolean isTrueLoop) {
            this.label = label;
            this.startPc = startPc;
            this.isTrueLoop = isTrueLoop;
            this.continuePc = -1;  // Will be set later
            this.breakPcs = new ArrayList<>();
            this.nextPcs = new ArrayList<>();
            this.redoPcs = new ArrayList<>();
        }
    }
}
