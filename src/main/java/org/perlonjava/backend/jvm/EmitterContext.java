package org.perlonjava.backend.jvm;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.frontend.semantic.ScopedSymbolTable;
import org.perlonjava.runtime.runtimetypes.ErrorMessageUtil;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.perlonjava.frontend.semantic.SymbolTable;

/**
 * The EmitterContext class holds the context information required for parsing and emitting bytecode.
 * This includes details about the file, class, symbol table, method visitor, context type,
 * and context for error messages and debug messages.
 */
public class EmitterContext {

    /**
     * Cache for contexts with different ContextTypes
     */
    private Map<Integer, EmitterContext> contextCache;

    /**
     * Per-site hot-spot cache for evalStringHelper: avoids fastKey construction
     * on repeated evals of the same string object in the same package.
     * Stored on the per-thread EmitterContext so no synchronization is needed.
     */
    public String lastEvalStr;
    public String lastEvalPackage;
    public Class<?> lastEvalClass;

    /**
     * CompilerOptions is a configuration class that holds various settings and flags
     * used by the compiler during the compilation process.
     */
    public CompilerOptions compilerOptions;

    /**
     * The information on the Java class being generated.
     */
    public JavaClassInfo javaClassInfo;
    /**
     * The symbol table used for scoping symbols within the context.
     */
    public ScopedSymbolTable symbolTable;

    /**
     * Source-level lexical subroutine declarations seen while parsing this
     * compilation unit.  Child parsers for deferred syntax (notably format
     * argument lines) share this context after their normal symbol-table
     * scope has unwound.
     */
    public final Set<String> lexicalSubroutineDeclarations = new HashSet<>();

    /**
     * The ClassWriter instance used to visit the method instructions.
     */
    public ClassWriter cw;
    /**
     * The MethodVisitor instance used to visit the method instructions.
     */
    public MethodVisitor mv;
    /**
     * The type of the current context, defined by the RuntimeContextType enum - VOID, SCALAR, etc
     */
    public int contextType;
    /**
     * Indicates whether the current context is for a boxed object (true) or a native object (false).
     */
    public boolean isBoxed;
    /**
     * Formats error messages with source code context
     */
    public ErrorMessageUtil errorUtil;

    /**
     * List of UNITCHECK blocks to execute after compilation phase
     */
    public RuntimeArray unitcheckBlocks;

    /**
     * Active after \Q in double quoted string
     */
    public boolean quoteMetaEnabled;

    /**
     * Captured environment array for eval - stores the exact variable names array
     * from compile-time so runtime constructor generation matches
     */
    public String[] capturedEnv;

    /** Maps parser pad indices to this generated method's JVM local indices. */
    public Map<Integer, Integer> sourceLexicalSlotMap;

    /**
     * Flag indicating if this is an evalbytes context - prevents Unicode source detection
     */
    public boolean isEvalbytes;

    /**
     * Whether the method currently being emitted is an lvalue subroutine.
     * This is method-local state; keeping it on JavaClassInfo leaks the flag
     * to later methods emitted for the same generated class.
     */
    public boolean isLvalueSubroutine;

    // -------------------------------------------------------------------------
    // Per-eval-site cache for repeated eval STRING invocations.
    // capturedEnv and symbolTable are both fixed at compile time, so these maps
    // are stable for the lifetime of this EmitterContext and can be reused
    // across every runtime call to the same eval{} site.
    // Only accessed from the eval-compilation thread (PerlRuntime is ThreadLocal).
    // -------------------------------------------------------------------------

    /** adjustedRegistry map, built once from capturedEnv indices. */
    public Map<String, Integer> evalCachedRegistry;
    /** adjustedDecls map (varName → decl kind), built once from capturedEnv + symbolTable. */
    public Map<String, String> evalCachedDecls;
    /** adjustedOurPackages map, built once from capturedEnv + symbolTable. */
    public Map<String, String> evalCachedOurPackages;
    /** Pre-built SymbolTable used as BytecodeCompiler's initial variable table template. */
    public SymbolTable evalCachedCompilerSymbolTable;
    /** capturedVarIndices for BytecodeCompiler — read-only after first eval, shared directly. */
    public Map<String, Integer> evalCachedCapturedVarIndices;
    /** capturedOurVariableNames for BytecodeCompiler — read-only after first eval, shared directly. */
    public Set<String> evalCachedCapturedOurVarNames;
    /** nextRegister value to use for BytecodeCompiler on the cached path. */
    public int evalCachedNextRegister;
    /** variableRegistry for InterpretedCode — built once from compilerSymbolTableTemplate. */
    public Map<String, Integer> evalCachedVariableRegistry;
    /** ourVariableRegistry for InterpretedCode — built once from compilerSymbolTableTemplate. */
    public Map<String, String> evalCachedOurVariableRegistry;
    /** Pooled ScopedSymbolTable for the cached eval path; reused across 2M+ warm-path compilations.
     *  After each compilation symbolTable is at depth 1 with all caches cleared by exitScope(),
     *  so it can be handed back to the next BytecodeCompiler without any explicit reset. */
    public ScopedSymbolTable evalCachedSymbolTable;
    /** Pooled ScopedSymbolTable for the parse phase of cached eval STRING; at depth 1 between
     *  calls because parser.parse() enters exactly one scope and exits it before returning.
     *  Null means not yet cached or invalidated by a compile error. */
    public ScopedSymbolTable evalCachedParseSymbolTable;

    // ---- Pooled BytecodeCompiler collections (round 21) ----
    // Each list/deque is empty after a successful compile(); we clear() and reuse
    // the backing arrays instead of allocating new ones on every warm-path eval.
    /** Pooled bytecode instruction list; cleared and reused per warm-path compile. */
    public java.util.List<Integer> evalCachedBytecodeList;
    /** Pooled constants list (runtime objects referenced from bytecode). */
    public java.util.List<Object>  evalCachedConstants;
    /** Pooled string-pool list (interned string literals). */
    public java.util.List<String>  evalCachedStringPool;
    /** Pooled scope-entry-point stack (balanced after each compile). */
    public java.util.Deque<Integer> evalCachedScopeIndices;
    /** Pooled saved-register stack for scope entry (balanced after each compile). */
    public java.util.Deque<Integer> evalCachedSavedNextRegister;
    /** Pooled saved-base-register stack for scope entry (balanced after each compile). */
    public java.util.Deque<Integer> evalCachedSavedBaseRegister;
    /** Pooled loop-info stack (balanced after each compile). */
    public java.util.List<Object>  evalCachedLoopStack;

    /**
     * Constructs a new EmitterContext with the specified parameters.
     *
     * @param javaClassInfo   the name of the Java class being generated
     * @param symbolTable     the symbol table used for scoping symbols within the context
     * @param mv              the MethodVisitor instance used to visit the method instructions
     * @param cw              the ClassWriter instance used to visit the method instructions
     * @param contextType     the type of the context, defined by the RuntimeContextType int value
     * @param isBoxed         indicates whether the context is for a boxed object (true) or a native object (false)
     * @param errorUtil       formats error messages with source code context
     * @param compilerOptions compiler flags, file name and source code
     * @param unitcheckBlocks list of UNITCHECK blocks to execute after compilation phase
     */
    public EmitterContext(
            JavaClassInfo javaClassInfo,
            ScopedSymbolTable symbolTable,
            MethodVisitor mv,
            ClassWriter cw,
            int contextType,
            boolean isBoxed,
            ErrorMessageUtil errorUtil,
            CompilerOptions compilerOptions,
            RuntimeArray unitcheckBlocks) {
        this.javaClassInfo = javaClassInfo;
        this.symbolTable = symbolTable;
        this.mv = mv;
        this.cw = cw;
        this.contextType = contextType;
        this.isBoxed = isBoxed;
        this.errorUtil = errorUtil;
        this.compilerOptions = compilerOptions;
        this.unitcheckBlocks = unitcheckBlocks;
    }

    static void fixupContext(EmitterContext ctx) {
        // Handle non-void context cases
        // When a label is the last statement in a block and the block's result is used,
        // we need to ensure a value is pushed onto the stack
        if (ctx.contextType != RuntimeContextType.VOID) {
            // Create a new empty RuntimeList as the result value
            ctx.mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/runtimetypes/RuntimeList");
            ctx.mv.visitInsn(Opcodes.DUP);  // Duplicate reference for constructor
            // Initialize the empty RuntimeList
            ctx.mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
                    "org/perlonjava/runtime/runtimetypes/RuntimeList",
                    "<init>",
                    "()V",
                    false);
        }
    }

    /**
     * Creates a new EmitterContext with the specified context type.
     * The other properties are copied from the current context.
     * <p>
     * This is used for example when the context changes from VOID to SCALAR
     *
     * @param contextType the new context type
     * @return a new EmitterContext with the updated context type
     */
    public EmitterContext with(int contextType) {
        if (contextCache != null) {
            EmitterContext cached = contextCache.get(contextType);
            if (cached != null) return cached;
        } else {
            contextCache = new HashMap<>();
        }
        EmitterContext newContext = new EmitterContext(
                this.javaClassInfo, this.symbolTable,
                this.mv, this.cw, contextType, this.isBoxed, this.errorUtil, this.compilerOptions,
                this.unitcheckBlocks);
        newContext.isLvalueSubroutine = this.isLvalueSubroutine;
        newContext.sourceLexicalSlotMap = this.sourceLexicalSlotMap;
        contextCache.put(contextType, newContext);
        return newContext;
    }

    public void logDebug(String message) {
        if (this.compilerOptions.debugEnabled) { // Use ctx.debugEnabled
            System.out.println(message);
        }
    }

    public void clearContextCache() {
        if (contextCache != null) contextCache.clear();
    }

    @Override
    public String toString() {
        return "EmitterContext{\n" +
                "    JavaClassInfo='" + javaClassInfo + "',\n" +
                "    symbolTable=" + symbolTable + ",\n" +
                "    contextType=" + contextType + ",\n" +
                "    isBoxed=" + isBoxed + ",\n" +
                "    errorUtil=" + (errorUtil != null ? errorUtil.toString() : "null") + ",\n" +
                "    compilerOptions=" + (compilerOptions != null ? compilerOptions.toString() : "null") + "\n" +
                "}";
    }
}
