package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.backend.bytecode.BytecodeCompiler;
import org.perlonjava.backend.bytecode.Disassemble;
import org.perlonjava.backend.bytecode.InterpretedCode;
import org.perlonjava.backend.bytecode.InterpreterState;
import org.perlonjava.backend.jvm.EmitterContext;
import org.perlonjava.backend.jvm.EmitterMethodCreator;
import org.perlonjava.backend.jvm.InterpreterFallbackException;
import org.perlonjava.backend.jvm.JavaClassInfo;
import org.perlonjava.frontend.analysis.ConstantFoldingVisitor;
import org.perlonjava.frontend.astnode.Node;
import org.perlonjava.frontend.astnode.OperatorNode;
import org.perlonjava.frontend.lexer.Lexer;
import org.perlonjava.frontend.lexer.LexerToken;
import org.perlonjava.frontend.parser.Parser;
import org.perlonjava.frontend.semantic.ScopedSymbolTable;
import org.perlonjava.frontend.semantic.SymbolTable;
import org.perlonjava.runtime.ForkOpenCompleteException;
import org.perlonjava.runtime.HintHashRegistry;
import org.perlonjava.runtime.WarningBitsRegistry;
import org.perlonjava.runtime.mro.InheritanceResolver;
import org.perlonjava.runtime.debugger.DebugHooks;
import org.perlonjava.runtime.debugger.DebugState;
import org.perlonjava.runtime.operators.ModuleOperators;
import org.perlonjava.runtime.operators.WarnDie;
import org.perlonjava.runtime.CoreSubroutineGenerator;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.function.Supplier;

import static org.perlonjava.frontend.parser.ParserTables.CORE_PROTOTYPES;
import static org.perlonjava.frontend.parser.SpecialBlockParser.getCurrentScope;
import static org.perlonjava.frontend.parser.SpecialBlockParser.setCurrentScope;
import static org.perlonjava.runtime.runtimetypes.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarUndef;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.*;
import static org.perlonjava.runtime.runtimetypes.SpecialBlock.runUnitcheckBlocks;

/**
 * The RuntimeCode class represents a compiled code object in the runtime environment.
 * It provides functionality to compile, store, and execute Perl subroutines and eval strings.
 */
public class RuntimeCode extends RuntimeBase implements RuntimeScalarReference {

    // Lookup object for performing method handle operations
    public static final MethodHandles.Lookup lookup = MethodHandles.lookup();

    public static final IdentityHashMap<OperatorNode, Integer> evalBeginIds = new IdentityHashMap<>();

    /**
     * Flag to control whether eval STRING should use the interpreter backend.
     * Enabled by default. eval STRING compiles to InterpretedCode instead of generating JVM bytecode.
     * This provides 46x faster compilation for workloads with many unique eval strings.
     * <p>
     * Set environment variable JPERL_EVAL_NO_INTERPRETER=1 to disable.
     */
    public static final boolean EVAL_USE_INTERPRETER =
            System.getenv("JPERL_EVAL_NO_INTERPRETER") == null;

    /**
     * Flag to control whether eval compilation errors should be printed to stderr.
     * By default, eval failures are silent (errors only stored in $@).
     * <p>
     * Set environment variable JPERL_EVAL_VERBOSE=1 to enable verbose error reporting.
     * This is useful for debugging eval compilation issues, especially when testing
     * the interpreter path.
     */
    public static final boolean EVAL_VERBOSE =
            System.getenv("JPERL_EVAL_VERBOSE") != null;

    public static final boolean EVAL_TRACE =
            System.getenv("JPERL_EVAL_TRACE") != null;
    /**
     * ThreadLocal storage for runtime values of captured variables during eval STRING compilation.
     * <p>
     * PROBLEM: In perl5, BEGIN blocks inside eval STRING can access outer lexical variables' runtime values:
     * my @imports = qw(a b);
     * eval q{ BEGIN { say @imports } };  # perl5 prints: a b
     * <p>
     * In PerlOnJava, BEGIN blocks execute during parsing (before the eval class is instantiated),
     * so they couldn't access runtime values - they would see empty variables.
     * <p>
     * SOLUTION: When evalStringHelper() is called, the runtime values are stored in this ThreadLocal.
     * During parsing, when SpecialBlockParser sets up BEGIN blocks, it can access these runtime values
     * and use them to initialize the special globals that lexical variables become in BEGIN blocks.
     * <p>
     * This ThreadLocal stores:
     * - Key: The evalTag identifying this eval compilation
     * - Value: EvalRuntimeContext containing:
     * - runtimeValues: Object[] of captured variable values
     * - capturedEnv: String[] of captured variable names (matching array indices)
     * <p>
     * Thread-safety: Each thread's eval compilation uses its own ThreadLocal storage, so parallel
     * eval compilations don't interfere with each other.
     */
    private static final ThreadLocal<EvalRuntimeContext> evalRuntimeContext = new ThreadLocal<>();
    // Cache for memoization of evalStringHelper results
    private static final int CLASS_CACHE_SIZE = 100;
    private static final Map<String, Class<?>> evalCache = new LinkedHashMap<String, Class<?>>(CLASS_CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Class<?>> eldest) {
            return size() > CLASS_CACHE_SIZE;
        }
    };
    // Cache for method handles with eviction policy
    private static final int METHOD_HANDLE_CACHE_SIZE = 100;
    private static final Map<Class<?>, MethodHandle> methodHandleCache = new LinkedHashMap<Class<?>, MethodHandle>(METHOD_HANDLE_CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Class<?>, MethodHandle> eldest) {
            return size() > METHOD_HANDLE_CACHE_SIZE;
        }
    };
    /**
     * Flag to enable disassembly of eval STRING bytecode.
     * When set, prints the interpreter bytecode for each eval STRING compilation.
     * <p>
     * Set environment variable JPERL_DISASSEMBLE=1 to enable, or use --disassemble CLI flag.
     * The --disassemble flag sets this via setDisassemble().
     */
    public static boolean DISASSEMBLE =
            System.getenv("JPERL_DISASSEMBLE") != null;
    public static boolean USE_INTERPRETER =
            System.getenv("JPERL_INTERPRETER") != null;
    public static final boolean FORCE_INTERPRETER =
            System.getenv("JPERL_INTERPRETER") != null;
    public static MethodType methodType = MethodType.methodType(RuntimeList.class, RuntimeArray.class, int.class);

    /**
     * Tracks the current eval nesting depth for $^S support.
     * 0 = not inside any eval, >0 = inside eval (eval STRING or eval BLOCK).
     * Incremented on eval entry, decremented on eval exit (success or failure).
     */
    public static int evalDepth = 0;

    /**
     * Thread-local stack of @_ arrays for each active subroutine call.
     * This allows nested code blocks (like those passed to List::Util::any/all/grep/map)
     * to access the outer subroutine's @_ via $_[0], $_[1], etc.
     * 
     * Push/pop is handled by RuntimeCode.apply() methods.
     * Access via getCurrentArgs() for Java-implemented functions that need caller's @_.
     */
    private static final ThreadLocal<Deque<RuntimeArray>> argsStack =
            ThreadLocal.withInitial(ArrayDeque::new);

    /**
     * Get the current subroutine's @_ array.
     * Used by Java-implemented functions (like List::Util::any) that need to pass
     * the caller's @_ to code blocks.
     *
     * @return The current @_ array, or null if not in a subroutine
     */
    public static RuntimeArray getCurrentArgs() {
        Deque<RuntimeArray> stack = argsStack.get();
        return stack.isEmpty() ? null : stack.peek();
    }

    /**
     * Get the caller's @_ array (one level up from current).
     * Used by Java-implemented functions (like List::Util::any) that need to pass
     * the outer Perl subroutine's @_ to code blocks.
     * 
     * When a Java method is called via RuntimeCode.apply(), its @_ is pushed onto the stack.
     * To get the @_ from the Perl subroutine that called this Java method, we need to look
     * one level deeper in the stack.
     *
     * @return The caller's @_ array, or null if not available
     */
    public static RuntimeArray getCallerArgs() {
        Deque<RuntimeArray> stack = argsStack.get();
        if (stack.size() < 2) {
            return null;
        }
        // Convert to array to access by index (skip top element)
        RuntimeArray[] arr = stack.toArray(new RuntimeArray[0]);
        return arr[1];
    }

    /**
     * Push @_ onto the args stack when entering a subroutine.
     * Public so BytecodeInterpreter can use it when calling InterpretedCode directly.
     */
    public static void pushArgs(RuntimeArray args) {
        argsStack.get().push(args);
    }

    /**
     * Pop @_ from the args stack when exiting a subroutine.
     * Public so BytecodeInterpreter can use it when calling InterpretedCode directly.
     */
    public static void popArgs() {
        Deque<RuntimeArray> stack = argsStack.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
    }

    /**
     * Inline method cache for fast method dispatch at monomorphic call sites.
     * 
     * In OO code, most call sites (e.g., `$obj->method()`) repeatedly call the same
     * method on objects of the same class. This is called a "monomorphic" call site.
     * Without caching, each call requires a full method lookup traversing the @ISA
     * hierarchy, which is expensive.
     * 
     * How it works:
     * 1. Each method call site in compiled code gets a unique callsiteId (allocated
     *    at compile time via allocateMethodCallsiteId()).
     * 2. The callsiteId maps to a cache slot via: cacheIndex = callsiteId & (SIZE - 1)
     * 3. Each cache slot stores: (blessId, methodHash, RuntimeCode)
     *    - blessId: identifies the class of the object (from $obj's bless)
     *    - methodHash: identifies which method is being called
     *    - RuntimeCode: the resolved method to invoke
     * 4. On cache hit (same blessId + methodHash), we skip method resolution and
     *    directly invoke the cached MethodHandle.
     * 5. On cache miss, we do full method resolution and update the cache.
     * 
     * Cache invalidation:
     * When @ISA changes or methods are redefined, InheritanceResolver.invalidateCache()
     * calls clearInlineMethodCache() to clear all cached entries.
     * 
     * This optimization provides ~50% speedup for method-heavy code like:
     *   while ($i < 10000) { $obj->method($arg); $i++ }
     */
    private static final int METHOD_CALL_CACHE_SIZE = 4096;
    private static final int[] inlineCacheBlessId = new int[METHOD_CALL_CACHE_SIZE];
    private static final int[] inlineCacheMethodHash = new int[METHOD_CALL_CACHE_SIZE];
    private static final RuntimeCode[] inlineCacheCode = new RuntimeCode[METHOD_CALL_CACHE_SIZE];
    private static int nextCallsiteId = 0;
    
    public static int allocateMethodCallsiteId() {
        return nextCallsiteId++ % METHOD_CALL_CACHE_SIZE;
    }
    
    /**
     * Clear the inline method cache. Should be called when method definitions change.
     */
    public static void clearInlineMethodCache() {
        java.util.Arrays.fill(inlineCacheBlessId, 0);
        java.util.Arrays.fill(inlineCacheMethodHash, 0);
        java.util.Arrays.fill(inlineCacheCode, null);
    }
    
    // Temporary storage for anonymous subroutines and eval string compiler context
    public static HashMap<String, Class<?>> anonSubs = new HashMap<>(); // temp storage for makeCodeObject()
    public static HashMap<String, Object> interpretedSubs = new HashMap<>(); // storage for interpreter fallback closures
    public static HashMap<String, EmitterContext> evalContext = new HashMap<>(); // storage for eval string compiler context
    // Runtime eval counter for generating unique filenames when $^P is set
    private static int runtimeEvalCounter = 1;
    // Method object representing the compiled subroutine (legacy - used by PerlModuleBase)
    public MethodHandle methodHandle;
    // Functional interface for direct subroutine invocation (preferred for generated classes)
    public PerlSubroutine subroutine;
    public boolean isStatic;
    public String autoloadVariableName = null;
    // Code object instance used during execution (legacy - used with methodHandle)
    public Object codeObject;
    // Prototype of the subroutine
    public String prototype;
    // Attributes associated with the subroutine
    public List<String> attributes = new ArrayList<>();
    // Method context information for next::method support
    public String packageName;
    public String subName;
    // Source package for imported forward declarations (used for AUTOLOAD resolution)
    public String sourcePackage = null;
    // Flag to indicate this is a symbolic reference created by \&{string} that should always be "defined"
    public boolean isSymbolicReference = false;
    // Flag to indicate this is a built-in operator
    public boolean isBuiltin = false;
    // Flag to indicate this was explicitly declared (sub foo; or sub foo { ... })
    // as opposed to auto-created by getGlobalCodeRef() for lookups.
    // In Perl 5, declared subs (even forward declarations) are visible via *{glob}{CODE}.
    public boolean isDeclared = false;
    // Flag to indicate this is a closure prototype (the template CV before cloning).
    // In Perl 5, MODIFY_CODE_ATTRIBUTES receives the closure prototype for closures.
    // Calling a closure prototype should die with "Closure prototype called".
    public boolean isClosurePrototype = false;
    // Flag to indicate this code is a map/grep block - non-local return should propagate through it
    public boolean isMapGrepBlock = false;
    // Flag to indicate this code is an eval BLOCK - non-local return should propagate through it
    public boolean isEvalBlock = false;
    // State variables
    public Map<String, Boolean> stateVariableInitialized = new HashMap<>();
    public Map<String, RuntimeScalar> stateVariable = new HashMap<>();
    public Map<String, RuntimeArray> stateArray = new HashMap<>();
    public Map<String, RuntimeHash> stateHash = new HashMap<>();
    public RuntimeList constantValue;
    // Field to hold the thread compiling this code
    public Supplier<Void> compilerSupplier;
    // Self-reference for __SUB__ (set after construction for InterpretedCode)
    public RuntimeScalar __SUB__;

    /**
     * Captured RuntimeScalar variables from the enclosing scope.
     * Set by {@link #makeCodeObject} for closures that capture lexical variables.
     * Used to properly track blessed object lifetimes across closure boundaries:
     * captured variables' blessed refs should not be destroyed at the inner scope
     * exit, but only when the closure itself is released.
     */
    public RuntimeScalar[] capturedScalars;

    /**
     * Cached constants referenced via backslash (e.g., \"yay") inside this subroutine.
     * When the CODE slot of a glob is replaced, weak references to these constants
     * are cleared to emulate Perl 5's "optree reaping" behavior.
     */
    public RuntimeBase[] padConstants;

    /**
     * Registry mapping generated class names to their pad constants.
     * Used to transfer pad constants from compile time to runtime for anonymous subs.
     */
    public static final java.util.concurrent.ConcurrentHashMap<String, RuntimeBase[]> padConstantsByClassName =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Clears weak references to this subroutine's pad constants.
     * Called when the CODE slot of a glob is replaced, emulating Perl 5's
     * behavior where replacing a sub frees its op-tree and clears weak refs
     * to compile-time constants.
     */
    public void clearPadConstantWeakRefs() {
        if (padConstants != null) {
            for (RuntimeBase constant : padConstants) {
                WeakRefRegistry.clearWeakRefsTo(constant);
            }
        }
    }

    /**
     * Release captured variable references. Called when this closure is being
     * discarded (scope exit, undef, or reassignment of the variable holding
     * this CODE ref). Decrements {@code captureCount} on each captured scalar,
     * and if it reaches zero, defers the blessed ref decrement via MortalList.
     * <p>
     * Handles cascading: if a captured scalar itself holds a CODE ref with
     * captures, those are released recursively.
     */
    public void releaseCaptures() {
        if (capturedScalars != null) {
            RuntimeScalar[] scalars = capturedScalars;
            capturedScalars = null;  // null out first to prevent re-entry
            for (RuntimeScalar s : scalars) {
                s.captureCount--;
                if (s.captureCount == 0) {
                    // If the captured scalar itself holds a CODE ref with captures,
                    // release those recursively (handles nested closures).
                    if (s.type == RuntimeScalarType.CODE && s.value instanceof RuntimeCode innerCode) {
                        innerCode.releaseCaptures();
                    }
                    // The captured variable's scope has exited but refCount was NOT
                    // decremented at that time (scopeExitCleanup returns early for
                    // captured variables to prevent premature clearing while the
                    // closure is alive). Now that the last closure is releasing this
                    // capture, decrement refCount to balance the original increment.
                    if (s.scopeExited) {
                        MortalList.deferDecrementIfTracked(s);
                    }
                }
            }
        }
    }

    /**
     * Constructs a RuntimeCode instance with the specified prototype and attributes.
     *
     * @param prototype  the prototype of the subroutine
     * @param attributes the attributes associated with the subroutine
     */
    public RuntimeCode(String prototype, List<String> attributes) {
        this.prototype = prototype;
        this.attributes = attributes;
    }

    public RuntimeCode(MethodHandle methodObject, Object codeObject, String prototype) {
        this.methodHandle = methodObject;
        this.codeObject = codeObject;
        this.prototype = prototype;
    }

    /**
     * Constructs a RuntimeCode instance with a PerlSubroutine functional interface.
     * This is the preferred constructor for generated Perl code.
     *
     * @param subroutine the functional interface implementation
     * @param prototype  the prototype of the subroutine
     */
    public RuntimeCode(PerlSubroutine subroutine, String prototype) {
        this.subroutine = subroutine;
        this.prototype = prototype;
    }

    private static void evalTrace(String msg) {
        if (EVAL_TRACE) {
            System.err.println("[eval-trace] " + msg);
        }
    }

    /**
     * Create a callable clone of this RuntimeCode for closure prototype support.
     * The original will be marked as a closure prototype (non-callable);
     * the clone is the actual closure that can be called.
     */
    public RuntimeCode cloneForClosure() {
        RuntimeCode clone;
        if (this.subroutine != null) {
            clone = new RuntimeCode(this.subroutine, this.prototype);
        } else {
            clone = new RuntimeCode(this.methodHandle, this.codeObject, this.prototype);
        }
        clone.attributes = this.attributes != null ? new java.util.ArrayList<>(this.attributes) : null;
        clone.packageName = this.packageName;
        clone.subName = this.subName;
        clone.isStatic = this.isStatic;
        clone.isDeclared = this.isDeclared;
        clone.constantValue = this.constantValue;
        clone.compilerSupplier = this.compilerSupplier;
        // isClosurePrototype stays false for the clone (it's callable)
        return clone;
    }

    /**
     * Called by CLI argument parser when --disassemble is set.
     */
    public static void setDisassemble(boolean value) {
        DISASSEMBLE = value;
    }

    public static void setUseInterpreter(boolean value) {
        USE_INTERPRETER = value;
    }

    /**
     * Check if AUTOLOAD exists for a given RuntimeCode's package.
     * Checks source package first (for imported subs), then current package.
     *
     * @param code The RuntimeCode to check AUTOLOAD for
     * @return true if AUTOLOAD exists and is defined
     */
    public static boolean hasAutoload(RuntimeCode code) {
        if (code.packageName == null) {
            return false;
        }
        // Check source package AUTOLOAD first (for imported subs)
        if (code.sourcePackage != null && !code.sourcePackage.equals(code.packageName)) {
            String sourceAutoloadString = code.sourcePackage + "::AUTOLOAD";
            RuntimeScalar sourceAutoload = GlobalVariable.getGlobalCodeRef(sourceAutoloadString);
            if (sourceAutoload.getDefinedBoolean()) {
                return true;
            }
        }
        // Then check current package AUTOLOAD
        String autoloadString = code.packageName + "::AUTOLOAD";
        RuntimeScalar autoload = GlobalVariable.getGlobalCodeRef(autoloadString);
        return autoload.getDefinedBoolean();
    }


    /**
     * Get the current eval runtime context for accessing variable runtime values during parsing.
     * This is called by SpecialBlockParser when setting up BEGIN blocks.
     *
     * @return The current eval runtime context, or null if not in eval STRING compilation
     */
    public static EvalRuntimeContext getEvalRuntimeContext() {
        return evalRuntimeContext.get();
    }

    /**
     * Save and clear the eval runtime context.
     * Used by require/do to prevent inner compilations from seeing the eval's captured variables.
     * The returned value should be passed to {@link #restoreEvalRuntimeContext} to restore it.
     *
     * @return The saved eval runtime context (may be null)
     */
    public static EvalRuntimeContext saveAndClearEvalRuntimeContext() {
        EvalRuntimeContext saved = evalRuntimeContext.get();
        if (saved != null) {
            evalRuntimeContext.remove();
        }
        return saved;
    }

    /**
     * Restore a previously saved eval runtime context.
     *
     * @param saved The context returned by {@link #saveAndClearEvalRuntimeContext}
     */
    public static void restoreEvalRuntimeContext(EvalRuntimeContext saved) {
        if (saved != null) {
            evalRuntimeContext.set(saved);
        }
    }

    /**
     * Gets the next eval sequence number and generates a filename.
     * Used by both baseline compiler and interpreter for consistent naming.
     *
     * @return Filename like "(eval 1)", "(eval 2)", etc.
     */
    public static synchronized String getNextEvalFilename() {
        return "(eval " + runtimeEvalCounter++ + ")";
    }

    // Add a method to clear caches when globals are reset
    public static void clearCaches() {
        evalCache.clear();
        methodHandleCache.clear();
        anonSubs.clear();
        interpretedSubs.clear();
        evalContext.clear();
        evalRuntimeContext.remove();
    }

    public static void copy(RuntimeCode code, RuntimeCode codeFrom) {
        code.prototype = codeFrom.prototype;
        code.attributes = codeFrom.attributes;
        code.methodHandle = codeFrom.methodHandle;
        code.subroutine = codeFrom.subroutine;
        code.isStatic = codeFrom.isStatic;
        code.codeObject = codeFrom.codeObject;
    }

    /**
     * Backwards-compatible overload for code compiled before runtimeValues parameter was added.
     * This allows pre-compiled Perl modules to continue working with the new signature.
     *
     * @param code    the RuntimeScalar containing the eval string
     * @param evalTag the tag used to retrieve the eval context
     * @return the compiled Class representing the anonymous subroutine
     * @throws Exception if an error occurs during compilation
     */
    public static Class<?> evalStringHelper(RuntimeScalar code, String evalTag) throws Exception {
        return evalStringHelper(code, evalTag, new Object[0]);
    }

    /**
     * Compiles the text of an eval string into a Class that represents an anonymous subroutine.
     * After the Class is returned to the caller, an instance of the Class will be populated
     * with closure variables, and then makeCodeObject() will be called to transform the Class
     * instance into a Perl CODE object.
     * <p>
     * IMPORTANT CHANGE: This method now accepts runtime values of captured variables.
     * <p>
     * WHY THIS IS NEEDED:
     * In perl5, BEGIN blocks inside eval STRING can access outer lexical variables' runtime values.
     * For example:
     * my @imports = qw(md5 md5_hex);
     * eval q{ use Digest::MD5 @imports };  # BEGIN block sees @imports = (md5 md5_hex)
     * <p>
     * Previously in PerlOnJava, BEGIN blocks would see empty variables because they execute
     * during parsing, before the eval class is instantiated with runtime values.
     * <p>
     * NOW: We pass runtime values to this method and store them in ThreadLocal storage.
     * SpecialBlockParser can then access these values when setting up BEGIN blocks,
     * allowing lexical variables to be initialized with their runtime values.
     *
     * @param code          the RuntimeScalar containing the eval string
     * @param evalTag       the tag used to retrieve the eval context
     * @param runtimeValues the runtime values of captured variables (Object[] matching capturedEnv order)
     * @return the compiled Class representing the anonymous subroutine
     * @throws Exception if an error occurs during compilation
     */
    public static Class<?> evalStringHelper(RuntimeScalar code, String evalTag, Object[] runtimeValues) throws Exception {

        // Retrieve the eval context that was saved at program compile-time
        EmitterContext ctx = RuntimeCode.evalContext.get(evalTag);

        // Handle missing eval context - this can happen when compiled code (e.g., INIT blocks
        // with eval) is executed after the runtime has been reset. In JUnit parallel tests,
        // PerlLanguageProvider.resetAll() clears evalContext between tests, but compiled
        // bytecode (which references specific evalTags) survives and may be re-executed.
        if (ctx == null) {
            throw new RuntimeException(
                    "Eval context not found for tag: " + evalTag +
                    ". This can happen when eval is called from code that was compiled " +
                    "in a previous session (e.g., INIT blocks in cached modules). " +
                    "The module may need to be reloaded. " +
                    "If this occurs in tests, ensure module caches are cleared along with eval contexts.");
        }

        // Save the current scope so we can restore it after eval compilation.
        // This is critical because eval may be called from code compiled with different
        // warning/feature flags than the caller, and we must not leak the eval's scope.
        ScopedSymbolTable savedCurrentScope = getCurrentScope();

        // Store runtime values in ThreadLocal so SpecialBlockParser can access them during parsing.
        // This enables BEGIN blocks to see outer lexical variables' runtime values.
        //
        // CRITICAL: The runtimeValues array matches capturedEnv order (both skip first 3 variables).
        // SpecialBlockParser will use getRuntimeValue() to look up values by variable name.
        //
        // Example: If @imports is at capturedEnv[5], its runtime value is at runtimeValues[5-3=2]
        //          (because both arrays skip 'this', '@_', and 'wantarray')
        EvalRuntimeContext runtimeCtx = new EvalRuntimeContext(
                runtimeValues,
                ctx.capturedEnv,  // Variable names in same order as runtimeValues
                evalTag
        );
        evalRuntimeContext.set(runtimeCtx);

        try {
            // Check if the eval string contains non-ASCII characters
            // If so, treat it as Unicode source to preserve Unicode characters during parsing
            // EXCEPT for evalbytes, which must treat everything as bytes
            String evalString = code.toString();
            boolean hasUnicode = false;
            if (!ctx.isEvalbytes && code.type != RuntimeScalarType.BYTE_STRING) {
                for (int i = 0; i < evalString.length(); i++) {
                    if (evalString.charAt(i) > 127) {
                        hasUnicode = true;
                        break;
                    }
                }
            }

            // Clone compiler options and set isUnicodeSource if needed
            // This only affects string parsing, not symbol table or method resolution
            // Always clone to avoid modifying the original and to set a unique filename
            CompilerOptions evalCompilerOptions = ctx.compilerOptions.clone();
            // The eval string can originate from either a Perl STRING or BYTE_STRING scalar.
            // For BYTE_STRING source we must treat the source as raw bytes (latin-1-ish) and
            // NOT re-encode characters to UTF-8 when simulating 'non-unicode source'.
            boolean isByteStringSource = !ctx.isEvalbytes && code.type == RuntimeScalarType.BYTE_STRING;
            if (hasUnicode) {
                evalCompilerOptions.isUnicodeSource = true;
            }
            if (ctx.isEvalbytes) {
                evalCompilerOptions.isEvalbytes = true;
            }
            if (isByteStringSource) {
                evalCompilerOptions.isByteStringSource = true;
            }

            // Check $^P to determine if we should use caching
            // Give each eval a unique filename for correct source location tracking.
            // Previously, all evals from the same source file shared the caller's filename,
            // which caused source location info collisions when multiple evals had the same
            // tokenIndex (since each eval's tokenization starts from 0).
            // When debugging is enabled ($^P is set), we skip the cache entirely.
            int debugFlags = GlobalVariable.getGlobalVariable(GlobalContext.encodeSpecialVar("P")).getInt();
            boolean isDebugging = debugFlags != 0;

            // Always generate a unique filename for each eval to prevent source location collisions
            String actualFileName = getNextEvalFilename();
            evalCompilerOptions.fileName = actualFileName;

            // Check if the result is already cached (include hasUnicode, isEvalbytes, byte-string-source, feature flags, and package in cache key)
            // Skip caching when $^P is set, so each eval gets a unique filename
            // Include package name in cache key to ensure source location info is correct per-package
            int featureFlags = ctx.symbolTable.featureFlagsStack.peek();
            String currentPackage = ctx.symbolTable.getCurrentPackage();
            String cacheKey = code.toString() + '\0' + evalTag + '\0' + hasUnicode + '\0' + ctx.isEvalbytes + '\0' + isByteStringSource + '\0' + featureFlags + '\0' + currentPackage;
            Class<?> cachedClass = null;
            if (!isDebugging) {
                synchronized (evalCache) {
                    if (evalCache.containsKey(cacheKey)) {
                        cachedClass = evalCache.get(cacheKey);
                    }
                }

                if (cachedClass != null) {
                    return cachedClass;
                }
            }

            // IMPORTANT: The eval call site (EmitEval) computes the constructor signature from
            // ctx.symbolTable (captured at compile-time). We must use that exact symbol table for
            // codegen, otherwise the generated <init>(...) descriptor may not match what the
            // call site is looking up via reflection.
            ScopedSymbolTable capturedSymbolTable = ctx.symbolTable;

            // %^H is the compile-time hints hash. eval STRING must not leak modifications to the
            // caller scope, so snapshot and restore it across compilation.
            RuntimeHash capturedHintHash = GlobalVariable.getGlobalHash(GlobalContext.encodeSpecialVar("H"));
            Map<String, RuntimeScalar> savedHintHash = new HashMap<>(capturedHintHash.elements);

            // Restore %^H from the call site's compile-time snapshot for eval STRING.
            // At runtime, %^H is normally empty. But eval STRING needs to inherit the
            // compile-time %^H that was active when the eval statement was compiled,
            // so that pragmas like 'use mypragma' are visible inside eval.
            java.util.Map<String, String> callSiteHints = HintHashRegistry.getCurrentCallSiteHintHash();
            if (callSiteHints != null) {
                for (java.util.Map.Entry<String, String> entry : callSiteHints.entrySet()) {
                    capturedHintHash.elements.put(entry.getKey(), new RuntimeScalar(entry.getValue()));
                }
            }

            // eval may include lexical pragmas (use strict/warnings/features). We need those flags
            // during codegen of the eval body, but they must NOT leak back into the caller scope.
            BitSet savedWarningFlags = (BitSet) capturedSymbolTable.warningFlagsStack.peek().clone();
            int savedFeatureFlags = capturedSymbolTable.featureFlagsStack.peek();
            int savedStrictOptions = capturedSymbolTable.strictOptionsStack.peek();

            // Parse using a mutable clone so lexical declarations inside the eval do not
            // change the captured environment / constructor signature.
            // IMPORTANT: The parseSymbolTable starts with the captured flags so that
            // the eval code is parsed with the correct feature/strict/warning context
            ScopedSymbolTable parseSymbolTable = capturedSymbolTable.snapShot();

            // CRITICAL: Pre-create aliases for captured variables BEFORE parsing
            // This allows BEGIN blocks in the eval string to access outer lexical variables.
            //
            // When the eval string is parsed, variable references in BEGIN blocks will be
            // resolved to these special package globals that we're aliasing now.
            //
            // Example: my @arr = qw(a b); eval q{ BEGIN { say @arr } };
            // We create: globalArrays["BEGIN_PKG_x::@arr"] = (the runtime @arr object)
            // Then when "say @arr" is parsed in the BEGIN, it resolves to BEGIN_PKG_x::@arr
            // which is aliased to the runtime array with values (a, b).
            List<String> evalAliasKeys = new ArrayList<>();
            Map<Integer, SymbolTable.SymbolEntry> capturedVars = capturedSymbolTable.getAllVisibleVariables();
            for (SymbolTable.SymbolEntry entry : capturedVars.values()) {
                if (!entry.name().equals("@_") && !entry.decl().isEmpty() && !entry.name().startsWith("&")) {
                    if (!entry.decl().equals("our")) {
                        // "my" or "state" variables get special BEGIN package globals
                        Object runtimeValue = runtimeCtx.getRuntimeValue(entry.name());
                        if (runtimeValue != null) {
                            // Get or create the special package ID.
                            // IMPORTANT: Do NOT mutate the AST node (ast.id) — the AST is
                            // shared with the JVM compiler and mutation would corrupt `my`
                            // variable reinitialization in loops.
                            OperatorNode ast = entry.ast();
                            if (ast != null) {
                                int beginId = evalBeginIds.computeIfAbsent(
                                        ast,
                                        k -> EmitterMethodCreator.classCounter++);
                                String packageName = PersistentVariable.beginPackage(beginId);
                                String varNameWithoutSigil = entry.name().substring(1);
                                String fullName = packageName + "::" + varNameWithoutSigil;

                                if (runtimeValue instanceof RuntimeArray) {
                                    GlobalVariable.globalArrays.put(fullName, (RuntimeArray) runtimeValue);
                                } else if (runtimeValue instanceof RuntimeHash) {
                                    GlobalVariable.globalHashes.put(fullName, (RuntimeHash) runtimeValue);
                                } else if (runtimeValue instanceof RuntimeScalar) {
                                    GlobalVariable.globalVariables.put(fullName, (RuntimeScalar) runtimeValue);
                                }
                                evalAliasKeys.add(entry.name().charAt(0) + fullName);
                            }
                        }
                    }
                }
            }

            EmitterContext evalCtx = new EmitterContext(
                    new JavaClassInfo(),  // internal java class name
                    parseSymbolTable, // symbolTable
                    null, // method visitor
                    null, // class writer
                    ctx.contextType, // call context
                    true, // is boxed
                    ctx.errorUtil, // error message utility
                    evalCompilerOptions, // possibly modified for Unicode source
                    ctx.unitcheckBlocks);
            // Mark as eval string so goto &sub can emit proper error
            evalCtx.javaClassInfo.isInEvalString = true;
            // evalCtx.logDebug("evalStringHelper EmitterContext: " + evalCtx);
            // evalCtx.logDebug("evalStringHelper Code: " + code);

            // Process the string source code to create the LexerToken list
            Lexer lexer = new Lexer(evalString);
            List<LexerToken> tokens = lexer.tokenize(); // Tokenize the Perl code
            Node ast = null;
            Class<?> generatedClass;
            try {
                // Create the AST
                // Create an instance of ErrorMessageUtil with the file name and token list
                evalCtx.errorUtil = new ErrorMessageUtil(evalCtx.compilerOptions.fileName, tokens);
                Parser parser = new Parser(evalCtx, tokens); // Parse the tokens
                ast = parser.parse(); // Generate the abstract syntax tree (AST)

                // ast = ConstantFoldingVisitor.foldConstants(ast);

                // Constant folding: inline user-defined constant subs and fold constant expressions.
                ast = ConstantFoldingVisitor.foldConstants(ast, evalCtx.symbolTable.getCurrentPackage());

                // Create a new instance of ErrorMessageUtil, resetting the line counter
                // Use evalCtx.compilerOptions.fileName (the eval's filename, e.g. "(eval 1)")
                // not ctx.compilerOptions.fileName (the outer file, e.g. "-e") so that
                // anonymous subs compiled inside eval STRING get the correct source filename
                // for #line directives and caller() reporting
                evalCtx.errorUtil = new ErrorMessageUtil(evalCtx.compilerOptions.fileName, tokens);
                ScopedSymbolTable postParseSymbolTable = evalCtx.symbolTable;
                evalCtx.symbolTable = capturedSymbolTable;
                evalCtx.symbolTable.copyFlagsFrom(postParseSymbolTable);
                setCurrentScope(evalCtx.symbolTable);

                // Use the captured environment array from compile-time to ensure
                // constructor signature matches what EmitEval generated bytecode for
                if (ctx.capturedEnv != null) {
                    evalCtx.capturedEnv = ctx.capturedEnv;
                }

                ast.setAnnotation("blockIsSubroutine", true);
                generatedClass = EmitterMethodCreator.createClassWithMethod(
                        evalCtx,
                        ast,
                        false  // use try-catch
                );
                runUnitcheckBlocks(ctx.unitcheckBlocks);
            } catch (Throwable e) {
                // Compilation error in eval-string

                // Set the global error variable "$@"
                RuntimeScalar err = GlobalVariable.getGlobalVariable("main::@");
                err.set(e.getMessage());

                // If EVAL_VERBOSE is set, print the error to stderr for debugging
                if (EVAL_VERBOSE) {
                    System.err.println("eval compilation error: " + e.getMessage());
                    if (e.getCause() != null) {
                        System.err.println("Caused by: " + e.getCause().getMessage());
                    }
                }

                // Check if $SIG{__DIE__} handler is defined
                RuntimeScalar sig = GlobalVariable.getGlobalHash("main::SIG").get("__DIE__");
                if (sig.getDefinedBoolean()) {
                    // Call the $SIG{__DIE__} handler (similar to what die() does)
                    RuntimeScalar sigHandler = new RuntimeScalar(sig);

                    // Undefine $SIG{__DIE__} before calling to avoid infinite recursion
                    int level = DynamicVariableManager.getLocalLevel();
                    DynamicVariableManager.pushLocalVariable(sig);

                    try {
                        RuntimeArray args = new RuntimeArray();
                        RuntimeArray.push(args, new RuntimeScalar(err));
                        apply(sigHandler, args, RuntimeContextType.SCALAR);
                    } catch (Throwable handlerException) {
                        // If the handler dies, use its payload as the new error
                        if (handlerException instanceof RuntimeException && handlerException.getCause() instanceof PerlDieException pde) {
                            RuntimeBase handlerPayload = pde.getPayload();
                            if (handlerPayload != null) {
                                err.set(handlerPayload.getFirst());
                            }
                        } else if (handlerException instanceof PerlDieException pde) {
                            RuntimeBase handlerPayload = pde.getPayload();
                            if (handlerPayload != null) {
                                err.set(handlerPayload.getFirst());
                            }
                        }
                        // If handler throws other exceptions, ignore them (keep original error in $@)
                    } finally {
                        // Restore $SIG{__DIE__}
                        DynamicVariableManager.popToLocalLevel(level);
                    }
                }

                // Return null to signal compilation failure (don't throw exception)
                // This prevents the exception from escaping to outer eval blocks
                return null;
            } finally {
                // Restore caller lexical flags (do not leak eval pragmas).
                capturedSymbolTable.warningFlagsStack.pop();
                capturedSymbolTable.warningFlagsStack.push((BitSet) savedWarningFlags.clone());

                capturedSymbolTable.featureFlagsStack.pop();
                capturedSymbolTable.featureFlagsStack.push(savedFeatureFlags);

                capturedSymbolTable.strictOptionsStack.pop();
                capturedSymbolTable.strictOptionsStack.push(savedStrictOptions);

                // Restore %^H (compile-time hints hash) to the caller snapshot.
                capturedHintHash.elements.clear();
                capturedHintHash.elements.putAll(savedHintHash);

                // Note: Scope restoration moved to outer finally block to handle cache hits

                // Clean up BEGIN aliases for captured variables after compilation.
                // These aliases were only needed during parsing (for BEGIN blocks to access
                // outer lexicals). Leaving them in GlobalVariable would cause corruption
                // if a recursive call re-enters the same function and its `my` declaration
                // calls retrieveBeginScalar, finding the stale alias instead of creating
                // a fresh variable.
                for (String key : evalAliasKeys) {
                    String fullName = key.substring(1);
                    switch (key.charAt(0)) {
                        case '$' -> GlobalVariable.globalVariables.remove(fullName);
                        case '@' -> GlobalVariable.globalArrays.remove(fullName);
                        case '%' -> GlobalVariable.globalHashes.remove(fullName);
                    }
                }

                // Store source lines in symbol table if $^P flags are set
                // Do this on both success and failure paths when flags require retention
                // Use the original evalString and actualFileName; AST may be null on failure
                storeSourceLines(evalString, actualFileName, ast, tokens);
            }

            // Cache the result (unless debugging is enabled)
            if (!isDebugging) {
                synchronized (evalCache) {
                    evalCache.put(cacheKey, generatedClass);
                }
            }

            return generatedClass;
        } finally {
            // Restore the original current scope, not the captured symbol table.
            // This prevents eval from leaking its compile-time scope to the caller.
            // This MUST be in the outer finally to handle both cache hits and compilation paths.
            setCurrentScope(savedCurrentScope);

            // Clean up ThreadLocal to prevent memory leaks
            // IMPORTANT: Always clean up ThreadLocal in finally block to ensure it's removed
            // even if compilation fails. Failure to do so could cause memory leaks in
            // long-running applications with thread pools.
            evalRuntimeContext.remove();
        }
    }

    /**
     * Stores source lines in the symbol table for debugger support when $^P flags are set.
     *
     * <p>This method is used by both the baseline compiler and the interpreter to save
     * eval source code for debugging when $^P flags require it.
     *
     * @param evalString The source code string to store
     * @param filename   The filename (e.g., "(eval 1)")
     * @param ast        The AST to check for subroutine definitions (may be null on compilation failure)
     * @param tokens     Lexer tokens for #line directive processing
     */
    public static void storeSourceLines(String evalString, String filename, Node ast, List<LexerToken> tokens) {
        // Check $^P for debugger flags
        int debugFlags = GlobalVariable.getGlobalVariable(GlobalContext.encodeSpecialVar("P")).getInt();
        // 0x02 (2): Line-by-line debugging (also saves source like 0x400)
        // 0x400 (1024): Save source code lines
        // 0x800 (2048): Include evals that generate no subroutines
        // 0x1000 (4096): Include source that did not compile
        boolean shouldSaveSource = (debugFlags & 0x02) != 0 || (debugFlags & 0x400) != 0;
        boolean saveWithoutSubs = (debugFlags & 0x800) != 0;

        if (shouldSaveSource) {
            // Note: We can't reliably detect subroutine definitions from the AST because
            // subroutines are processed at parse-time and removed from the AST.
            // Use a simple heuristic: check if the eval string contains "sub " followed by
            // an identifier or block.
            boolean definesSubs = evalString.matches("(?s).*\\bsub\\s+(?:\\w+|\\{).*");

            // Only save if either:
            // - The eval defines subroutines, OR
            // - The 0x800 flag is set (save evals without subs)
            if (!definesSubs && !saveWithoutSubs) {
                return;  // Skip this eval
            }
            // Store in the symbol table as @{"_<(eval N)"}
            String symbolKey = "_<" + filename;

            // Split the eval string into lines (without including trailing empty strings)
            String[] lines = evalString.split("\n");

            // Create the array with the format expected by the debugger:
            // [0] = undef, [1..n] = lines with \n, [n+1] = \n, [n+2] = ;
            String arrayKey = "main::" + symbolKey;
            RuntimeArray sourceArray = GlobalVariable.getGlobalArray(arrayKey);
            sourceArray.elements.clear();

            // Index 0: undef
            sourceArray.elements.add(RuntimeScalarCache.scalarUndef);

            // Indexes 1..n: each line with "\n" appended
            for (String line : lines) {
                sourceArray.elements.add(new RuntimeScalar(line + "\n"));
            }

            // Index n+1: "\n"
            sourceArray.elements.add(new RuntimeScalar("\n"));

            // Index n+2: ";"
            sourceArray.elements.add(new RuntimeScalar(";"));

            // Process #line directives to populate @{"_<filename"} arrays
            processLineDirectives(evalString, lines, tokens);
        }
    }

    /**
     * Process #line directives in the eval string to populate @{"_<filename"} arrays.
     * This implements the debugger behavior where #line N "file" causes subsequent
     * source lines to be stored in @{"_<file"} at index N.
     *
     * @param evalString The full eval source string
     * @param lines      The split lines of the eval string
     * @param tokens     Lexer tokens (may be null on compilation failure)
     */
    private static void processLineDirectives(String evalString, String[] lines, List<LexerToken> tokens) {
        String currentFilename = null;
        int currentLineOffset = 0; // 0-based index into lines array

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            // Simple #line directive parsing: #line N "filename"
            // Allow optional leading whitespace
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\s*#line\\s+(\\d+)\\s+\"([^\"]+)\"").matcher(line);
            if (m.find()) {
                int targetLine = Integer.parseInt(m.group(1)); // 1-based line number in target file
                currentFilename = m.group(2);
                currentLineOffset = i + 1; // Next line in eval corresponds to targetLine
                // Ensure the target array exists and is properly sized
                String targetKey = "main::_<" + currentFilename;
                RuntimeArray targetArray = GlobalVariable.getGlobalArray(targetKey);
                // Ensure array is large enough (sparse behavior)
                while (targetArray.elements.size() <= targetLine) {
                    targetArray.elements.add(RuntimeScalarCache.scalarUndef);
                }
                // Place the next line at the correct index
                if (i + 1 < lines.length) {
                    targetArray.elements.set(targetLine, new RuntimeScalar(lines[i + 1] + "\n"));
                }
            } else if (currentFilename != null && i >= currentLineOffset) {
                // Continue populating the current filename array
                int targetLine = (i - currentLineOffset) + 1; // Convert to 1-based
                String targetKey = "main::_<" + currentFilename;
                RuntimeArray targetArray = GlobalVariable.getGlobalArray(targetKey);
                // Ensure array is large enough (sparse behavior)
                while (targetArray.elements.size() <= targetLine) {
                    targetArray.elements.add(RuntimeScalarCache.scalarUndef);
                }
                targetArray.elements.set(targetLine, new RuntimeScalar(line + "\n"));
            }
        }
    }

    /**
     * Execute eval STRING using the interpreter backend for faster compilation.
     * This method parses the eval string and compiles it to InterpretedCode instead
     * of generating JVM bytecode, which is 46x faster for workloads with many unique eval strings.
     *
     * @param code          The RuntimeScalar containing the eval string
     * @param evalTag       The unique identifier for this eval site
     * @param runtimeValues The captured variable values from the outer scope
     * @param args          The @_ arguments to pass to the eval
     * @param callContext   The calling context (SCALAR/LIST/VOID)
     * @return The result of executing the eval as a RuntimeList
     * @throws Throwable if compilation or execution fails
     */
    public static RuntimeList evalStringWithInterpreter(
            RuntimeScalar code,
            String evalTag,
            Object[] runtimeValues,
            RuntimeArray args,
            int callContext) throws Throwable {

        evalTrace("evalStringWithInterpreter enter tag=" + evalTag + " ctx=" + callContext +
                " codeType=" + code.type + " codeLen=" + (code.toString() != null ? code.toString().length() : -1));

        // Retrieve the eval context that was saved at program compile-time
        EmitterContext ctx = RuntimeCode.evalContext.get(evalTag);

        // Handle missing eval context - this can happen when compiled code (e.g., INIT blocks
        // with eval) is executed after the runtime has been reset. In JUnit parallel tests,
        // PerlLanguageProvider.resetAll() clears evalContext between tests, but compiled
        // bytecode (which references specific evalTags) survives and may be re-executed.
        if (ctx == null) {
            throw new RuntimeException(
                    "Eval context not found for tag: " + evalTag +
                    ". This can happen when eval is called from code that was compiled " +
                    "in a previous session (e.g., INIT blocks in cached modules). " +
                    "The module may need to be reloaded. " +
                    "If this occurs in tests, ensure module caches are cleared along with eval contexts.");
        }

        // Save the current scope so we can restore it after eval execution.
        // This is critical because eval may be called from code compiled with different
        // warning/feature flags than the caller, and we must not leak the eval's scope.
        ScopedSymbolTable savedCurrentScope = getCurrentScope();

        // Store runtime values in ThreadLocal for BEGIN block support
        EvalRuntimeContext runtimeCtx = new EvalRuntimeContext(
                runtimeValues,
                ctx.capturedEnv,
                evalTag
        );
        evalRuntimeContext.set(runtimeCtx);

        InterpretedCode interpretedCode = null;
        RuntimeList result;

        // Declare these outside try block so they're accessible in finally block for debugger support
        Node ast = null;
        List<LexerToken> tokens = null;
        List<String> evalAliasKeys = new ArrayList<>();

        // Save dynamic variable level to restore after eval.
        // IMPORTANT: Scope InterpreterState.currentPackage around eval execution.
        // This is the interpreter-side equivalent of the same scoping done in
        // EvalStringHandler for the JVM bytecode path. Without this, SET_PACKAGE
        // opcodes inside the eval permanently mutate the caller's package state,
        // breaking caller() and subsequent eval compilations.
        // See InterpreterState.currentPackage javadoc for the full design rationale.
        int dynamicVarLevel = DynamicVariableManager.getLocalLevel();
        String savedPkg = InterpreterState.currentPackage.get().toString();
        DynamicVariableManager.pushLocalVariable(InterpreterState.currentPackage.get());
        InterpreterState.currentPackage.get().set(savedPkg);

        try {
            String evalString = code.toString();
            evalTrace("evalStringWithInterpreter parse start tag=" + evalTag + " ctx=" + callContext +
                    " fileName=" + ctx.compilerOptions.fileName);
            // Handle Unicode source detection (same logic as evalStringHelper)
            boolean hasUnicode = false;
            if (!ctx.isEvalbytes && code.type != RuntimeScalarType.BYTE_STRING) {
                for (int i = 0; i < evalString.length(); i++) {
                    if (evalString.charAt(i) > 127) {
                        hasUnicode = true;
                        break;
                    }
                }
            }

            // Clone compiler options and set isUnicodeSource if needed
            // Always clone to avoid modifying the original and to set a unique filename
            CompilerOptions evalCompilerOptions = ctx.compilerOptions.clone();
            boolean isByteStringSource = !ctx.isEvalbytes && code.type == RuntimeScalarType.BYTE_STRING;
            if (hasUnicode) {
                evalCompilerOptions.isUnicodeSource = true;
            }
            if (ctx.isEvalbytes) {
                evalCompilerOptions.isEvalbytes = true;
            }
            if (isByteStringSource) {
                evalCompilerOptions.isByteStringSource = true;
            }
            // Always generate a unique filename for each eval to prevent source location collisions
            evalCompilerOptions.fileName = getNextEvalFilename();

            // Setup for BEGIN block support - create aliases for captured variables.
            // IMPORTANT: Do NOT mutate AST nodes (e.g. operatorAst.id) here.
            // The AST is shared with the JVM compiler; mutating it would change how
            // `my` declarations are compiled (NEW vs RETRIEVE_BEGIN), causing variables
            // inside loops to stop being reinitialized between iterations.
            ScopedSymbolTable capturedSymbolTable = ctx.symbolTable;
            Map<Integer, SymbolTable.SymbolEntry> capturedVars = capturedSymbolTable.getAllVisibleVariables();
            for (SymbolTable.SymbolEntry entry : capturedVars.values()) {
                if (!entry.name().equals("@_") && !entry.decl().isEmpty() && !entry.name().startsWith("&")) {
                    if (!entry.decl().equals("our")) {
                        Object runtimeValue = runtimeCtx.getRuntimeValue(entry.name());
                        if (runtimeValue != null) {
                            OperatorNode operatorAst = entry.ast();
                            if (operatorAst != null) {
                                int beginId = evalBeginIds.computeIfAbsent(
                                        operatorAst,
                                        k -> EmitterMethodCreator.classCounter++);
                                String packageName = PersistentVariable.beginPackage(beginId);
                                String varNameWithoutSigil = entry.name().substring(1);
                                String fullName = packageName + "::" + varNameWithoutSigil;

                                if (runtimeValue instanceof RuntimeArray) {
                                    GlobalVariable.globalArrays.put(fullName, (RuntimeArray) runtimeValue);
                                } else if (runtimeValue instanceof RuntimeHash) {
                                    GlobalVariable.globalHashes.put(fullName, (RuntimeHash) runtimeValue);
                                } else if (runtimeValue instanceof RuntimeScalar) {
                                    GlobalVariable.globalVariables.put(fullName, (RuntimeScalar) runtimeValue);
                                }
                                evalAliasKeys.add(entry.name().charAt(0) + fullName);
                            }
                        }
                    }
                }
            }

            try {
                // Parse the eval string
                Lexer lexer = new Lexer(evalString);
                tokens = lexer.tokenize();

                // Create parser context
                ScopedSymbolTable parseSymbolTable = capturedSymbolTable.snapShot();
                EmitterContext evalCtx = new EmitterContext(
                        new JavaClassInfo(),
                        parseSymbolTable,
                        null,
                        null,
                        callContext,  // Use the runtime calling context, not the saved one!
                        true,
                        new ErrorMessageUtil(evalCompilerOptions.fileName, tokens),
                        evalCompilerOptions,
                        ctx.unitcheckBlocks);

                Parser parser = new Parser(evalCtx, tokens);
                ast = parser.parse();

                // Run UNITCHECK blocks
                runUnitcheckBlocks(evalCtx.unitcheckBlocks);

                // Build adjusted registry for captured variables
                // Map variable names to register indices (3+ for captured variables)
                Map<String, Integer> adjustedRegistry = new HashMap<>();
                adjustedRegistry.put("this", 0);
                adjustedRegistry.put("@_", 1);
                adjustedRegistry.put("wantarray", 2);

                // IMPORTANT: Captured variables are loaded into registers starting at 3
                // (see BytecodeInterpreter's `System.arraycopy(code.capturedVars, 0, registers, 3, ...)`).
                // Therefore the variable registry must map captured variable names to packed
                // register indices 3+(i-skipVariables), in the same order as runtimeValues[].
                // Do NOT pack indices: runtimeValues[] is sized for the full capturedEnv range
                // and may contain null gaps, so the corresponding registers must keep the same
                // slot numbering.
                if (ctx.capturedEnv != null) {
                    for (int i = 3; i < ctx.capturedEnv.length; i++) {
                        String varName = ctx.capturedEnv[i];
                        if (varName == null) {
                            continue;
                        }
                        adjustedRegistry.put(varName, i);
                    }
                }

                // Compile to InterpretedCode with variable registry.
                //
                // setCompilePackage() is safe here (unlike EvalStringHandler) because:
                //   - evalCtx.errorUtil uses evalCompilerOptions.fileName (the outer script name),
                //     not the eval string's tokens, so die/warn location baking is already
                //     relative to the outer script and is unaffected by the package change.
                //   - capturedSymbolTable.getCurrentPackage() gives the compile-time package
                //     of the eval call site (e.g. "FOO3"), so bare names like *named are
                //     correctly qualified to FOO3::named in the bytecode string pool.
                //   - Without this call, the BytecodeCompiler defaults to "main", causing
                //     eval q[*named{CODE}] to look up main::named instead of FOO3::named.
                BytecodeCompiler compiler = new BytecodeCompiler(
                        evalCompilerOptions.fileName,
                        1,
                        evalCtx.errorUtil,
                        adjustedRegistry);
                compiler.setCompilePackage(capturedSymbolTable.getCurrentPackage());
                interpretedCode = compiler.compile(ast, evalCtx);
                evalTrace("evalStringWithInterpreter compiled tag=" + evalTag +
                        " bytecodeLen=" + (interpretedCode != null ? interpretedCode.bytecode.length : -1) +
                        " src=" + (interpretedCode != null ? interpretedCode.sourceName : "null"));
                if (DISASSEMBLE) {
                    System.out.println(Disassemble.disassemble(interpretedCode));
                }

                // Set captured variables
                if (runtimeValues.length > 0) {
                    RuntimeBase[] capturedVars2 = new RuntimeBase[runtimeValues.length];
                    for (int i = 0; i < runtimeValues.length; i++) {
                        capturedVars2[i] = (RuntimeBase) runtimeValues[i];
                    }
                    interpretedCode = interpretedCode.withCapturedVars(capturedVars2);
                }

            } catch (Throwable e) {
                // Compilation error in eval-string
                // Set the global error variable "$@"
                RuntimeScalar err = GlobalVariable.getGlobalVariable("main::@");
                err.set(e.getMessage());

                // If EVAL_VERBOSE is set, print the error to stderr for debugging
                if (EVAL_VERBOSE) {
                    System.err.println("eval compilation error: " + e.getMessage());
                    String src = evalString;
                    if (src != null) {
                        int maxLen = 4000;
                        if (src.length() > maxLen) {
                            src = src.substring(0, maxLen) + "\n...";
                        }
                        System.err.println("eval source:\n" + src);
                    }
                    if (e.getCause() != null) {
                        System.err.println("Caused by: " + e.getCause().getMessage());
                    }
                }

                // Check if $SIG{__DIE__} handler is defined
                RuntimeScalar sig = GlobalVariable.getGlobalHash("main::SIG").get("__DIE__");
                if (sig.getDefinedBoolean()) {
                    // Call the $SIG{__DIE__} handler (similar to what die() does)
                    RuntimeScalar sigHandler = new RuntimeScalar(sig);

                    // Undefine $SIG{__DIE__} before calling to avoid infinite recursion
                    int level = DynamicVariableManager.getLocalLevel();
                    DynamicVariableManager.pushLocalVariable(sig);

                    try {
                        RuntimeArray handlerArgs = new RuntimeArray();
                        RuntimeArray.push(handlerArgs, new RuntimeScalar(err));
                        apply(sigHandler, handlerArgs, RuntimeContextType.SCALAR);
                    } catch (Throwable handlerException) {
                        // If the handler dies, use its payload as the new error
                        if (handlerException instanceof RuntimeException && handlerException.getCause() instanceof PerlDieException pde) {
                            RuntimeBase handlerPayload = pde.getPayload();
                            if (handlerPayload != null) {
                                err.set(handlerPayload.getFirst());
                            }
                        } else if (handlerException instanceof PerlDieException pde) {
                            RuntimeBase handlerPayload = pde.getPayload();
                            if (handlerPayload != null) {
                                err.set(handlerPayload.getFirst());
                            }
                        }
                        // If handler throws other exceptions, ignore them (keep original error in $@)
                    } finally {
                        // Restore $SIG{__DIE__}
                        DynamicVariableManager.popToLocalLevel(level);
                    }
                }

                // Return undef/empty list to signal compilation failure
                if (callContext == RuntimeContextType.LIST) {
                    return new RuntimeList();
                } else {
                    return new RuntimeList(new RuntimeScalar());
                }
            }

            // Clean up BEGIN aliases BEFORE execution. These aliases were only needed during
            // parsing/compilation (for BEGIN blocks to access outer lexicals). If left in
            // GlobalVariable during execution, a recursive call that re-enters the same
            // function would find the alias via retrieveBeginScalar, sharing the same
            // RuntimeScalar object instead of creating a fresh one.
            for (String key : evalAliasKeys) {
                String fullName = key.substring(1);
                switch (key.charAt(0)) {
                    case '$' -> GlobalVariable.globalVariables.remove(fullName);
                    case '@' -> GlobalVariable.globalArrays.remove(fullName);
                    case '%' -> GlobalVariable.globalHashes.remove(fullName);
                }
            }
            evalAliasKeys.clear();

            // Execute the interpreted code
            // Track eval depth for $^S support
            evalDepth++;
            try {
                result = interpretedCode.apply(args, callContext);

                evalTrace("evalStringWithInterpreter exec ok tag=" + evalTag + " ctx=" + callContext +
                        " resultClass=" + (result != null ? result.getClass().getSimpleName() : "null") +
                        " resultScalar=" + (result != null ? result.scalar().toString() : "null") +
                        " resultBool=" + (result != null && result.scalar() != null && result.scalar().getBoolean()));

                // Clear $@ on successful execution
                RuntimeScalar err = GlobalVariable.getGlobalVariable("main::@");
                err.set("");

                return result;

            } catch (PerlDieException e) {
                evalTrace("evalStringWithInterpreter exec die tag=" + evalTag + " ctx=" + callContext +
                        " payload=" + (e.getPayload() != null ? e.getPayload().getFirst().toString() : "null"));
                // Runtime error - set $@ and return undef/empty list
                RuntimeScalar err = GlobalVariable.getGlobalVariable("main::@");
                RuntimeBase payload = e.getPayload();
                if (payload != null) {
                    err.set(payload.getFirst());
                } else {
                    err.set("Died");
                }

                if (EVAL_VERBOSE) {
                    System.err.println("eval runtime error: " + err);
                    String src = evalString;
                    if (src != null) {
                        int maxLen = 4000;
                        if (src.length() > maxLen) {
                            src = src.substring(0, maxLen) + "\n...";
                        }
                        System.err.println("eval source:\n" + src);
                    }
                }

                // Return undef/empty list
                if (callContext == RuntimeContextType.LIST) {
                    return new RuntimeList();
                } else {
                    return new RuntimeList(new RuntimeScalar());
                }

            } catch (Throwable e) {
                evalTrace("evalStringWithInterpreter exec throwable tag=" + evalTag + " ctx=" + callContext +
                        " ex=" + e.getClass().getSimpleName() + " msg=" + e.getMessage());
                WarnDie.catchEval(e);

                // Return undef/empty list
                if (callContext == RuntimeContextType.LIST) {
                    return new RuntimeList();
                } else {
                    return new RuntimeList(new RuntimeScalar());
                }
            } finally {
                evalDepth--;
            }

        } finally {
            evalTrace("evalStringWithInterpreter exit tag=" + evalTag + " ctx=" + callContext +
                    " $@=" + GlobalVariable.getGlobalVariable("main::@"));
            // Restore dynamic variables (local) to their state before eval
            DynamicVariableManager.popToLocalLevel(dynamicVarLevel);

            // Restore the original current scope, not the captured symbol table.
            // This prevents eval from leaking its compile-time scope to the caller.
            setCurrentScope(savedCurrentScope);

            // Store source lines in debugger symbol table if $^P flags are set
            // Do this on both success and failure paths when flags require retention
            // ast and tokens may be null if parsing failed early, but storeSourceLines handles that
            int debugFlags = GlobalVariable.getGlobalVariable(GlobalContext.encodeSpecialVar("P")).getInt();
            if (debugFlags != 0 && tokens != null) {
                String evalFilename = getNextEvalFilename();
                storeSourceLines(code.toString(), evalFilename, ast, tokens);
            }

            // Clean up ThreadLocal
            evalRuntimeContext.remove();
        }
    }

    // make sure we return a RuntimeScalar from __SUB__
    public static RuntimeScalar selfReferenceMaybeNull(RuntimeScalar codeRef) {
        return codeRef == null
                ? scalarUndef
                : codeRef;
    }

    /**
     * Factory method to create a CODE object (anonymous subroutine).
     * This is called right after a new Class is compiled.
     * The codeObject is an instance of the new Class, with the closure variables in place.
     *
     * @param codeObject the instance of the compiled Class
     * @return a RuntimeScalar representing the CODE object
     * @throws Exception if an error occurs during method retrieval
     */
    public static RuntimeScalar makeCodeObject(Object codeObject) throws Exception {
        return makeCodeObject(codeObject, null);
    }

    public static RuntimeScalar makeCodeObject(Object codeObject, String prototype) throws Exception {
        return makeCodeObject(codeObject, prototype, null);
    }

    /**
     * Factory method to create a CODE object with CvSTASH (package name) tracking.
     * The packageName parameter sets the CvSTASH equivalent, which determines where
     * $AUTOLOAD is set and what B::svref_2object->STASH->NAME returns.
     *
     * @param codeObject  the instance of the compiled Class
     * @param prototype   the prototype string (may be null)
     * @param packageName the compile-time package (CvSTASH equivalent, may be null)
     * @return a RuntimeScalar representing the CODE object
     * @throws Exception if an error occurs during method retrieval
     */
    public static RuntimeScalar makeCodeObject(Object codeObject, String prototype, String packageName) throws Exception {
        // Retrieve the class of the provided code object
        Class<?> clazz = codeObject.getClass();

        // Cast to PerlSubroutine - generated classes implement this interface
        // This allows direct interface calls without MethodHandle conversion errors
        PerlSubroutine subroutine = (PerlSubroutine) codeObject;

        // Create a new RuntimeCode using the functional interface
        RuntimeCode code = new RuntimeCode(subroutine, prototype);
        // Set CvSTASH (the package where this sub was compiled)
        if (packageName != null) {
            code.packageName = packageName;
        }

        // Look up pad constants registered at compile time for this class.
        // These track cached string literals referenced via \ inside the sub,
        // needed for optree reaping (clearing weak refs when sub is replaced).
        String internalClassName = clazz.getName().replace('.', '/');
        RuntimeBase[] padConsts = padConstantsByClassName.remove(internalClassName);
        if (padConsts != null) {
            code.padConstants = padConsts;
        }

        // Extract captured RuntimeScalar fields for closure DESTROY tracking.
        // Each instance field of type RuntimeScalar (except __SUB__) is a
        // captured lexical variable. We store them so that releaseCaptures()
        // can decrement blessed ref refCounts when the closure is discarded.
        Field[] allFields = clazz.getDeclaredFields();
        List<RuntimeScalar> captured = new ArrayList<>();
        for (Field f : allFields) {
            if (f.getType() == RuntimeScalar.class && !"__SUB__".equals(f.getName())) {
                RuntimeScalar capturedVar = (RuntimeScalar) f.get(codeObject);
                if (capturedVar != null) {
                    captured.add(capturedVar);
                    capturedVar.captureCount++;
                }
            }
        }
        if (!captured.isEmpty()) {
            code.capturedScalars = captured.toArray(new RuntimeScalar[0]);
            // Enable refCount tracking for closures with captures.
            // When the CODE ref's refCount drops to 0, releaseCaptures()
            // fires (via DestroyDispatch.callDestroy), letting captured
            // blessed objects run DESTROY.
            code.refCount = 0;
        }

        RuntimeScalar codeRef = new RuntimeScalar(code);

        // Set the __SUB__ instance field
        Field field = clazz.getDeclaredField("__SUB__");
        field.set(codeObject, codeRef);

        return codeRef;
    }

    /**
     * Call a method in a Perl-like class hierarchy using the C3 linearization algorithm.
     * This version accepts a native RuntimeBase[] array for parameters.
     *
     * @param runtimeScalar The object to call the method on.
     * @param method        The method to resolve.
     * @param currentSub    The subroutine to resolve SUPER::method in.
     * @param args          The arguments to pass to the method as native array.
     * @param callContext   The call context.
     * @return The result of the method call.
     */
    public static RuntimeList call(RuntimeScalar runtimeScalar,
                                   RuntimeScalar method,
                                   RuntimeScalar currentSub,
                                   RuntimeBase[] args,
                                   int callContext) {
        // Transform the native array to RuntimeArray of aliases (Perl variable `@_`)
        // Note: `this` (runtimeScalar) will be inserted by the RuntimeArray version
        RuntimeArray a = new RuntimeArray();
        for (RuntimeBase arg : args) {
            arg.setArrayOfAlias(a);
        }
        return call(runtimeScalar, method, currentSub, a, callContext);
    }

    /**
     * Call a method with inline caching for fast dispatch.
     * Each call site caches the resolved method for the most recent (blessId, methodName) pair.
     *
     * @param callsiteId    Unique ID for this call site (used for cache indexing).
     * @param runtimeScalar The object to call the method on.
     * @param method        The method to resolve.
     * @param currentSub    The subroutine to resolve SUPER::method in.
     * @param args          The arguments to pass to the method as native array.
     * @param callContext   The call context.
     * @return The result of the method call.
     */
    public static RuntimeList callCached(int callsiteId,
                                         RuntimeScalar runtimeScalar,
                                         RuntimeScalar method,
                                         RuntimeScalar currentSub,
                                         RuntimeBase[] args,
                                         int callContext) {
        // Fast path: check inline cache for monomorphic call sites
        if (method.type == RuntimeScalarType.STRING || method.type == RuntimeScalarType.BYTE_STRING) {
            // Unwrap READONLY_SCALAR for blessId check (same as in call())
            RuntimeScalar invocant = runtimeScalar;
            while (invocant.type == RuntimeScalarType.READONLY_SCALAR) {
                invocant = (RuntimeScalar) invocant.value;
            }
            if (RuntimeScalarType.isReference(invocant)) {
                int blessId = ((RuntimeBase) invocant.value).blessId;
                if (blessId != 0) {
                    int methodHash = System.identityHashCode(method.value);
                    int cacheIndex = callsiteId & (METHOD_CALL_CACHE_SIZE - 1);
                    
                    // Check if cache hit
                    if (inlineCacheBlessId[cacheIndex] == blessId && 
                        inlineCacheMethodHash[cacheIndex] == methodHash) {
                        RuntimeCode cachedCode = inlineCacheCode[cacheIndex];
                        if (cachedCode != null && (cachedCode.subroutine != null || cachedCode.methodHandle != null)) {
                            // Cache hit - ultra fast path: directly invoke method
                            try {
                                RuntimeArray a = new RuntimeArray();
                                a.elements.add(runtimeScalar);
                                for (RuntimeBase arg : args) {
                                    arg.setArrayOfAlias(a);
                                }
                                
                                // If this is an AUTOLOAD, set $AUTOLOAD before calling
                                String autoloadVariableName = cachedCode.autoloadVariableName;
                                if (autoloadVariableName != null) {
                                    String methodName = method.toString();
                                    // Only set $AUTOLOAD when dispatching to AUTOLOAD as a fallback
                                    // (method name != "AUTOLOAD"). When calling AUTOLOAD directly
                                    // (e.g., $self->SUPER::AUTOLOAD), the caller has already set it.
                                    if (!methodName.equals("AUTOLOAD")) {
                                        // Use the original calling class (perlClassName), not the class
                                        // where AUTOLOAD was found. Perl sets $AUTOLOAD to Child::method
                                        // even when AUTOLOAD is inherited from Base.
                                        String perlClassName = NameNormalizer.getBlessStr(blessId);
                                        String fullMethodName = NameNormalizer.normalizeVariableName(methodName, perlClassName);
                                        getGlobalVariable(autoloadVariableName).set(fullMethodName);
                                    }
                                }
                                
                                // Prefer PerlSubroutine interface over MethodHandle
                                if (cachedCode.subroutine != null) {
                                    return cachedCode.subroutine.apply(a, callContext);
                                } else if (cachedCode.isStatic) {
                                    return (RuntimeList) cachedCode.methodHandle.invoke(a, callContext);
                                } else {
                                    return (RuntimeList) cachedCode.methodHandle.invoke(cachedCode.codeObject, a, callContext);
                                }
                            } catch (Throwable e) {
                                if (e instanceof RuntimeException) throw (RuntimeException) e;
                                throw new RuntimeException(e);
                            }
                        }
                    }
                    
                    // Cache miss - do full lookup and update cache
                    String methodName = method.toString();
                    if (!methodName.contains("::")) {
                        String perlClassName = NameNormalizer.getBlessStr(blessId);
                        RuntimeScalar resolvedMethod = InheritanceResolver.findMethodInHierarchy(methodName, perlClassName, null, 0);
                        if (resolvedMethod != null && resolvedMethod.type == RuntimeScalarType.CODE) {
                            RuntimeCode code = (RuntimeCode) resolvedMethod.value;
                            
                            // Run compiler supplier if needed
                            if (code.compilerSupplier != null) {
                                code.compilerSupplier.get();
                                code = (RuntimeCode) resolvedMethod.value;
                            }
                            
                            // Only cache if method is defined and has a subroutine or method handle
                            if (code.subroutine != null || code.methodHandle != null) {
                                // Update cache
                                inlineCacheBlessId[cacheIndex] = blessId;
                                inlineCacheMethodHash[cacheIndex] = methodHash;
                                inlineCacheCode[cacheIndex] = code;
                            }
                            
                            // Call the method with function-scoped mortal boundary
                            RuntimeArray a = new RuntimeArray();
                            a.elements.add(runtimeScalar);
                            for (RuntimeBase arg : args) {
                                arg.setArrayOfAlias(a);
                            }
                            
                            String autoloadVariableName = code.autoloadVariableName;
                            if (autoloadVariableName != null && !methodName.equals("AUTOLOAD")) {
                                // Use the original calling class, not where AUTOLOAD was found
                                String fullMethodName = NameNormalizer.normalizeVariableName(methodName, perlClassName);
                                getGlobalVariable(autoloadVariableName).set(fullMethodName);
                            }
                            MortalList.pushMark();
                            try {
                                return code.apply(a, callContext);
                            } finally {
                                MortalList.popMark();
                            }
                        }
                    }
                }
            }
        }
        
        // Fall back to regular call
        return call(runtimeScalar, method, currentSub, args, callContext);
    }

    /**
     * Call a method in a Perl-like class hierarchy using the C3 linearization algorithm.
     *
     * @param runtimeScalar The object to call the method on.
     * @param method        The method to resolve.
     * @param currentSub    The subroutine to resolve SUPER::method in.
     * @param args          The arguments to pass to the method.
     * @param callContext   The call context.
     * @return The result of the method call.
     */
    public static RuntimeList call(RuntimeScalar runtimeScalar,
                                   RuntimeScalar method,
                                   RuntimeScalar currentSub,
                                   RuntimeArray args,
                                   int callContext) {
        // insert `this` into the parameter list
        args.elements.addFirst(runtimeScalar);

        // System.out.println("call ->" + method + " " + currentPackage + " " + args + " " + callContext);

        if (method.type == RuntimeScalarType.CODE) {
            // If method is a subroutine reference, just call it
            return apply(method, args, callContext);
        }

        String methodName = method.toString();

        // Unwrap READONLY_SCALAR for method dispatch.
        // Constants created via `use constant` with blessed refs go through
        // Internals::SvREADONLY which wraps the scalar in READONLY_SCALAR.
        // We must unwrap to see the actual reference type and blessId.
        RuntimeScalar invocant = runtimeScalar;
        while (invocant.type == RuntimeScalarType.READONLY_SCALAR) {
            invocant = (RuntimeScalar) invocant.value;
        }

        // Retrieve Perl class name
        String perlClassName;

        if (RuntimeScalarType.isReference(invocant)) {
            // Handle all reference types (REFERENCE, ARRAYREFERENCE, HASHREFERENCE, etc.)
            int blessId = ((RuntimeBase) invocant.value).blessId;
            if (blessId == 0) {
                if (invocant.type == GLOBREFERENCE) {
                    // Auto-bless file handler to IO::File which inherits from both IO::Handle and IO::Seekable
                    // This allows GLOBs to call methods like seek, tell, etc.
                    perlClassName = "IO::File";
                    // Load the module if needed
                    // TODO - optimize by creating a flag in RuntimeIO
                    ModuleOperators.require(new RuntimeScalar("IO/File.pm"));
                } else if (invocant.type == REGEX) {
                    // qr// objects are implicitly blessed into the Regexp class in Perl 5
                    // This allows $qr->isa("Regexp"), $qr->can("..."), etc.
                    perlClassName = "Regexp";
                } else {
                    // Not auto-blessed
                    throw new PerlCompilerException("Can't call method \"" + methodName + "\" on unblessed reference");
                }
            } else {
                perlClassName = NameNormalizer.getBlessStr(blessId);
            }
        } else if (invocant.type == RuntimeScalarType.GLOB) {
            // Bare typeglob used as method invocant (e.g., *FH->print(...))
            // Auto-bless to IO::File, same as GLOBREFERENCE
            perlClassName = "IO::File";
            ModuleOperators.require(new RuntimeScalar("IO/File.pm"));
        } else if (!invocant.getDefinedBoolean()) {
            throw new PerlCompilerException("Can't call method \"" + methodName + "\" on an undefined value");
        } else {
            perlClassName = invocant.toString();
            if (perlClassName.isEmpty()) {
                throw new PerlCompilerException("Can't call method \"" + methodName + "\" on an undefined value");
            }
            
            // Check if this string is a bareword filehandle (like IN, OUT, etc.)
            // If so, look up the glob and call the method on it
            String normalizedGlobName = NameNormalizer.normalizeVariableName(perlClassName, "main");
            if (GlobalVariable.isGlobalIODefined(normalizedGlobName)) {
                // This is a filehandle - get the glob reference and recurse
                RuntimeGlob glob = GlobalVariable.getGlobalIO(normalizedGlobName);
                RuntimeScalar globRef = glob.createReference();
                // Remove the invocant we already added and re-add with the glob reference
                args.elements.removeFirst();
                return call(globRef, method, currentSub, args, callContext);
            }
            
            if (perlClassName.endsWith("::")) {
                perlClassName = perlClassName.substring(0, perlClassName.length() - 2);
            }
            if (perlClassName.startsWith("::")) {
                perlClassName = perlClassName.substring(2);
            }
            if (perlClassName.startsWith("main::")) {
                perlClassName = perlClassName.substring(6);
            }
            if (perlClassName.isEmpty()) {
                // Nothing left
                perlClassName = "main";
            }
        }

        // Method name can be:
        // - A short name (e.g., "new")
        // - Fully qualified name
        // - A variable or dereference (e.g., $file->${ \'save' })
        // - "SUPER::name"

        // Class name can be:
        // - A string
        // - STDOUT
        // - A subroutine (e.g., Class->new() is Class()->new() if Class is a subroutine)
        // - Class::->new() is the same as Class->new()
        // - Class->Other::new() fully qualified method name

        // System.out.println("call perlClassName: " + perlClassName + " methodName: " + methodName);

        if (methodName.contains("::")) {

            // Handle next::method calls
            if (methodName.equals("next::method")) {
                return NextMethod.nextMethodWithContext(args, currentSub, callContext);
            }

            // Handle next::can calls
            if (methodName.equals("next::can")) {
                return NextMethod.nextCanWithContext(args, currentSub, callContext);
            }

            // Handle maybe::next::method calls
            if (methodName.equals("maybe::next::method")) {
                return NextMethod.maybeNextMethodWithContext(args, currentSub, callContext);
            }

            // Handle SUPER::method calls
            if (methodName.startsWith("SUPER::")) {
                method = NextMethod.superMethod(currentSub, methodName, perlClassName);
            } else if (methodName.contains("::SUPER::")) {
                // Handle Package::SUPER::method syntax
                // This is used by Moo to explicitly specify which package's parent to use
                // Example: $class->GrandChild::SUPER::new(@_)
                int superIdx = methodName.indexOf("::SUPER::");
                String packageName = methodName.substring(0, superIdx);
                String actualMethod = methodName.substring(superIdx + 9); // skip "::SUPER::"
                method = InheritanceResolver.findMethodInHierarchy(
                        actualMethod,
                        packageName,
                        methodName,  // cache key includes the full qualified name
                        1   // start looking in the parent package
                );
            } else {
                // Fully qualified method name - call the exact subroutine
                method = GlobalVariable.getGlobalCodeRef(methodName);
                if (!method.getDefinedBoolean()) {
                    throw new PerlCompilerException("Undefined subroutine &" + methodName + " called");
                }
            }
        } else {
            // Regular method lookup through inheritance
            if ("__ANON__".equals(perlClassName)) {
                throw new PerlCompilerException("Can't use anonymous symbol table for method lookup");
            }
            method = InheritanceResolver.findMethodInHierarchy(methodName, perlClassName, null, 0);
        }

        if (method != null) {
            // System.out.println("call ->" + method + " " + currentPackage + " " + args + " AUTOLOAD: " + ((RuntimeCode) method.value).autoloadVariableName);

            String autoloadVariableName = ((RuntimeCode) method.value).autoloadVariableName;
            if (autoloadVariableName != null
                    && !methodName.equals("AUTOLOAD") && !methodName.endsWith("::AUTOLOAD")) {
                // The inherited method is an autoloaded subroutine
                // Set the $AUTOLOAD variable to the name of the method that was called

                // Extract class name from the original calling class (perlClassName),
                // not from the AUTOLOAD variable name. In Perl, $AUTOLOAD is set to
                // OriginalClass::method even when AUTOLOAD is inherited from a parent.
                String fullMethodName = NameNormalizer.normalizeVariableName(methodName, perlClassName);
                // Set the $AUTOLOAD variable to the fully qualified name of the method
                getGlobalVariable(autoloadVariableName).set(fullMethodName);
            }

            return apply(method, args, callContext);
        }

        // If the method is not found in any class, handle special cases
        // 'import' is special in Perl - it should not throw an exception
        if (methodName.equals("import")) {
            return new RuntimeScalar().getList();
        } else {
            String errorMethodName = methodName;
            // For SUPER:: calls, strip the prefix for error reporting to match Perl behavior
            if (methodName.startsWith("SUPER::")) {
                errorMethodName = methodName.substring(7);
            }
            throw new PerlCompilerException("Can't locate object method \"" + errorMethodName + "\" via package \"" + perlClassName + "\" (perhaps you forgot to load \"" + perlClassName + "\"?)");
        }
    }

    /**
     * Implementation of Perl's caller() builtin.
     * This version doesn't have access to __SUB__, so it can't honor set_subname for JVM code.
     */
    public static RuntimeList caller(RuntimeList args, int ctx) {
        return callerWithSub(args, ctx, null);
    }

    /**
     * Implementation of Perl's caller() builtin with __SUB__ support.
     * When currentSub is provided, its subName is used for caller(0) to honor set_subname.
     *
     * @param args       The arguments (frame number)
     * @param ctx        The calling context
     * @param currentSub The __SUB__ reference from the calling subroutine (may be null)
     */
    public static RuntimeList callerWithSub(RuntimeList args, int ctx, RuntimeScalar currentSub) {
        RuntimeList res = new RuntimeList();
        int frame = 0;
        boolean hasExplicitExpr = !args.isEmpty();
        if (hasExplicitExpr) {
            frame = args.getFirst().getInt();
        }

        Throwable t = new Throwable();
        ExceptionFormatter.StackTraceResult result = ExceptionFormatter.formatExceptionDetailed(t);
        ArrayList<ArrayList<String>> stackTrace = result.frames();
        java.util.ArrayList<String> javaClassNames = extractJavaClassNames(t);
        int stackTraceSize = stackTrace.size();

        // Skip the first frame for JVM-compiled code, where the first frame represents
        // the sub's own location (not the call site). For interpreter code, the first
        // frame from CallerStack already IS the call site, so no skip is needed.
        if (stackTraceSize > 0 && !result.firstFrameFromInterpreter()) {
            frame++;
        }

        // Check if caller() is being called from package DB (for @DB::args support)
        boolean calledFromDB = false;
        if (stackTraceSize > 0) {
            String callerPackage = stackTrace.getFirst().getFirst();
            calledFromDB = "DB".equals(callerPackage);
        }

        if (frame >= 0 && frame < stackTraceSize) {
            // Runtime stack trace
            if (ctx == RuntimeContextType.SCALAR) {
                String pkg = stackTrace.get(frame).getFirst();
                res.add(new RuntimeScalar(pkg != null ? pkg : "main"));
            } else {
                ArrayList<String> frameInfo = stackTrace.get(frame);
                String pkg = frameInfo.get(0);
                res.add(new RuntimeScalar(pkg != null ? pkg : "main"));  // package
                res.add(new RuntimeScalar(frameInfo.get(1)));  // filename
                res.add(new RuntimeScalar(frameInfo.get(2)));  // line

                // Perl's caller() without EXPR returns only 3 elements: (package, filename, line).
                // caller(EXPR) returns 11 elements including subroutine name, hasargs, etc.
                if (hasExplicitExpr) {

                // The subroutine name at frame N is actually stored at frame N-1
                // because it represents the sub that IS CALLING frame N
                String subName = null;
                
                // For the innermost frame (frame == 1 after skip), check currentSub first
                // to honor set_subname() which modifies RuntimeCode.subName at runtime
                if (frame == 1 && currentSub != null && currentSub.type == RuntimeScalarType.CODE) {
                    RuntimeCode code = (RuntimeCode) currentSub.value;
                    if (code.subName != null && !code.subName.isEmpty()) {
                        String codePkg = code.packageName != null ? code.packageName : "main";
                        subName = codePkg + "::" + code.subName;
                    }
                }
                
                // Fall back to stack trace info
                if (subName == null && frame > 0 && frame - 1 < stackTraceSize) {
                    ArrayList<String> prevFrame = stackTrace.get(frame - 1);
                    if (prevFrame.size() > 3) {
                        subName = prevFrame.get(3);
                    }
                }

                // In Perl, caller() always returns a defined subroutine name.
                // For anonymous code blocks (closures, callbacks), use __ANON__.
                if (subName == null || subName.isEmpty()) {
                    if (frame > 0 && frame - 1 < stackTraceSize) {
                        String prevPkg = stackTrace.get(frame - 1).getFirst();
                        subName = (prevPkg != null && !prevPkg.isEmpty() ? prevPkg : "main") + "::__ANON__";
                    }
                }

                if (subName != null && !subName.isEmpty()) {
                    res.add(new RuntimeScalar(subName));  // subroutine
                } else {
                    res.add(RuntimeScalarCache.scalarUndef);
                }

                // Populate @DB::args when caller() is called from package DB
                // Carp.pm relies on this to get function arguments for stack traces
                if (calledFromDB) {
                    RuntimeArray dbArgs = GlobalVariable.getGlobalArray("DB::args");
                    if (DebugState.debugMode) {
                        RuntimeArray frameArgs = DebugState.getArgsForFrame(frame);
                        if (frameArgs != null) {
                            dbArgs.setFromList(frameArgs.getList());
                        } else {
                            dbArgs.setFromList(new RuntimeList());
                        }
                    } else {
                        // Not in debug mode - set to empty array
                        // This tells Carp we don't have args but prevents the
                        // "Incomplete caller override detected" message
                        dbArgs.setFromList(new RuntimeList());
                    }
                }

                // Add hasargs (element 4): 1 if @_ was populated for this sub
                // Subroutines always have @_ available, so this is 1 for subs
                // Check the subroutine name to determine if this is a sub call
                boolean hasArgs = subName != null && !subName.isEmpty() && 
                                  !subName.equals("(eval)") && !subName.endsWith("::(eval)");
                res.add(hasArgs ? RuntimeScalarCache.scalarTrue : RuntimeScalarCache.scalarUndef);

                // Add wantarray (element 5): undef for void, 0 for scalar, 1 for list
                // We don't currently track this per-frame, so return undef
                // TODO: Track call context per frame to return accurate wantarray
                res.add(RuntimeScalarCache.scalarUndef);

                // Add evaltext (element 6): The eval text if inside eval STRING
                // For eval {...}, this is undef; for eval "...", this is the string
                // Check if filename looks like an eval (e.g., "(eval 123)")
                String filename = frameInfo.get(1);
                if (filename != null && filename.startsWith("(eval ") && filename.endsWith(")")) {
                    // This is an eval frame - we don't have the actual text, return empty string
                    // Perl uses "" for eval {} and actual text for eval "..."
                    res.add(RuntimeScalarCache.scalarUndef);
                } else {
                    res.add(RuntimeScalarCache.scalarUndef);
                }

                // Add is_require (element 7): 1 if inside require/use, undef otherwise
                // We don't currently distinguish require from regular code
                res.add(RuntimeScalarCache.scalarUndef);

                // Add hints (element 8): Compile-time $^H value
                // Use per-call-site hints from callerHintsStack
                int hints = WarningBitsRegistry.getCallerHintsAtFrame(frame - 1);
                res.add(new RuntimeScalar(hints >= 0 ? hints : 0));

                // Add bitmask (element 9): Compile-time warnings bitmask
                // First try per-call-site bits from callerBitsStack (accurate per-statement)
                // frame is 1-based here (after skip increment), callerBitsStack is 0-based
                String warningBits = WarningBitsRegistry.getCallerBitsAtFrame(frame - 1);
                if (warningBits == null) {
                    // Fall back to per-class bits
                    if (frame < javaClassNames.size()) {
                        String className = javaClassNames.get(frame);
                        if (className != null) {
                            warningBits = WarningBitsRegistry.get(className);
                        }
                    }
                }
                if (warningBits != null) {
                    res.add(new RuntimeScalar(warningBits));
                } else {
                    res.add(RuntimeScalarCache.scalarUndef);
                }

                // Add hinthash (element 10): Compile-time %^H hash reference
                // Use the per-call-site hint hash registry to get the %^H snapshot
                // that was active when the calling code was compiled.
                // Falls back to the global %^H for compile-time calls (BEGIN blocks).
                java.util.Map<String, String> callerHintHash = HintHashRegistry.getCallerHintHashAtFrame(frame - 1);
                if (callerHintHash != null) {
                    RuntimeHash snapshot = new RuntimeHash();
                    for (java.util.Map.Entry<String, String> entry : callerHintHash.entrySet()) {
                        snapshot.elements.put(entry.getKey(), new RuntimeScalar(entry.getValue()));
                    }
                    res.add(snapshot.createReference());
                } else {
                    // Fallback: check global %^H (for compile-time calls in BEGIN blocks)
                    RuntimeHash hintHash = GlobalVariable.getGlobalHash(GlobalContext.encodeSpecialVar("H"));
                    if (!hintHash.elements.isEmpty()) {
                        RuntimeHash snapshot = new RuntimeHash();
                        snapshot.setFromList(hintHash.getList());
                        res.add(snapshot.createReference());
                    } else {
                        res.add(RuntimeScalarCache.scalarUndef);
                    }
                }
                } // end if (hasExplicitExpr)
            }
        } else if (frame >= stackTraceSize) {
            // Fallback: check CallerStack for synthetic frames pushed during compile-time
            // operations (e.g., MODIFY_*_ATTRIBUTES called from Java).
            // The excess frames beyond the Java stack trace are served from CallerStack.
            // Skip entries already consumed by ExceptionFormatter to avoid duplicates.
            int callerStackFrame = frame - stackTraceSize + result.callerStackConsumed();
            CallerStack.CallerInfo info = CallerStack.peek(callerStackFrame);
            if (info != null) {
                if (ctx == RuntimeContextType.SCALAR) {
                    res.add(new RuntimeScalar(info.packageName()));
                } else {
                    res.add(new RuntimeScalar(info.packageName()));
                    res.add(new RuntimeScalar(info.filename()));
                    res.add(new RuntimeScalar(info.line()));
                }
            }
        }
        return res;
    }

    /**
     * Returns the appropriate error prefix for undefined subroutine errors.
     * For tail calls (goto &sub), returns "Goto u" so message is "Goto undefined...".
     * For regular calls, returns "U" so message is "Undefined...".
     */
    private static String gotoErrorPrefix(String subroutineName) {
        return "tailcall".equals(subroutineName) ? "Goto u" : "U";
    }

    /**
     * Extracts Java class names from a Throwable's stack trace, parallel to
     * how ExceptionFormatter.formatException produces Perl frames.
     * This allows caller() to look up warning bits from WarningBitsRegistry.
     *
     * @param t The Throwable containing the stack trace
     * @return List of Java class names, one per Perl frame in same order as formatException
     */
    private static java.util.ArrayList<String> extractJavaClassNames(Throwable t) {
        java.util.ArrayList<String> classNames = new java.util.ArrayList<>();
        java.util.HashSet<String> seenLocations = new java.util.HashSet<>();
        
        // Track interpreter frames similar to ExceptionFormatter
        var interpreterFrames = InterpreterState.getStack();
        int interpreterFrameIndex = 0;
        boolean addedFrameForCurrentLevel = false;
        
        for (var element : t.getStackTrace()) {
            if (element.getClassName().equals("org.perlonjava.frontend.parser.StatementParser") &&
                    element.getMethodName().equals("parseUseDeclaration")) {
                // Use statement - no class name for warning bits lookup
                classNames.add(null);
            } else if (element.getClassName().equals("org.perlonjava.backend.bytecode.InterpretedCode") &&
                    element.getMethodName().equals("apply")) {
                // InterpretedCode.apply marks the END of a Perl call level
                if (addedFrameForCurrentLevel) {
                    interpreterFrameIndex++;
                    addedFrameForCurrentLevel = false;
                }
            } else if (element.getClassName().equals("org.perlonjava.backend.bytecode.BytecodeInterpreter") &&
                    element.getMethodName().equals("execute")) {
                // Interpreter frame - use InterpretedCode's class for warning bits lookup
                if (!addedFrameForCurrentLevel && interpreterFrameIndex < interpreterFrames.size()) {
                    var frame = interpreterFrames.get(interpreterFrameIndex);
                    if (frame != null && frame.code() != null) {
                        // For interpreter, warning bits come from InterpretedCode.warningBits
                        // For now, we use the code's identifier as a pseudo-class name
                        String codeId = "interpreter:" + System.identityHashCode(frame.code());
                        classNames.add(codeId);
                        addedFrameForCurrentLevel = true;
                    }
                }
            } else if (element.getClassName().contains("org.perlonjava.anon") ||
                    element.getClassName().contains("org.perlonjava.runtime.perlmodule")) {
                // JVM frame - use the actual class name for warning bits lookup
                // Use source location key to avoid duplicates (same logic as ExceptionFormatter)
                String locationKey = element.getFileName() + ":" + element.getLineNumber();
                if (!seenLocations.contains(locationKey)) {
                    seenLocations.add(locationKey);
                    classNames.add(element.getClassName());
                }
            }
        }
        
        return classNames;
    }

    // Method to apply (execute) a subroutine reference
    public static RuntimeList apply(RuntimeScalar runtimeScalar, RuntimeArray a, int callContext) {
        // NOTE: flush() was removed from here. Return values from nested calls
        // (e.g., receiver(coerce => quote_sub(...))) may have pending refCount
        // decrements from their scope exits. Flushing here would decrement them
        // to 0 and call clearWeakRefsTo before the callee captures them, breaking
        // weak ref tracking (Sub::Quote/Sub::Defer pattern). DESTROY still fires
        // at the next setLarge() or popAndFlush() — typically inside the callee.

        // Handle tied scalars - fetch the underlying value first
        if (runtimeScalar.type == RuntimeScalarType.TIED_SCALAR) {
            return apply(runtimeScalar.tiedFetch(), a, callContext);
        }
        if (runtimeScalar.type == READONLY_SCALAR) {
            return apply((RuntimeScalar) runtimeScalar.value, a, callContext);
        }
        // Check if the type of this RuntimeScalar is CODE
        if (runtimeScalar.type == RuntimeScalarType.CODE) {
            RuntimeCode code = (RuntimeCode) runtimeScalar.value;

            // Check for closure prototype — calling one should die
            if (code.isClosurePrototype) {
                throw new PerlDieException(new RuntimeScalar("Closure prototype called"));
            }

            // CRITICAL: Run compilerSupplier BEFORE checking defined()
            // The compilerSupplier may replace runtimeScalar.value with InterpretedCode
            if (code.compilerSupplier != null) {
                RuntimeList savedConstantValue = code.constantValue;
                java.util.List<String> savedAttributes = code.attributes;
                code.compilerSupplier.get();
                // Reload code from runtimeScalar.value in case it was replaced
                code = (RuntimeCode) runtimeScalar.value;
                // Transfer fields that were set on the old code (e.g., by :const attribute)
                if (savedConstantValue != null && code.constantValue == null) {
                    code.constantValue = savedConstantValue;
                }
                if (savedAttributes != null && code.attributes == null) {
                    code.attributes = savedAttributes;
                }
            }

            // Check if it's an unfilled forward declaration (not defined)
            if (!code.defined()) {
                // Lazily generate CORE:: subroutine wrappers on first call
                if ("CORE".equals(code.packageName) && code.subName != null) {
                    boolean generated = CoreSubroutineGenerator.generateWrapper(code.subName);
                    if (generated) {
                        // Reload code after wrapper generation
                        runtimeScalar = GlobalVariable.getGlobalCodeRef("CORE::" + code.subName);
                        code = (RuntimeCode) runtimeScalar.value;
                        if (code.defined()) {
                            // Fall through to normal execution below
                        }
                    }
                }
            }
            if (!code.defined()) {
                // Try to find AUTOLOAD for this subroutine
                String subroutineName = code.packageName + "::" + code.subName;
                if (code.packageName != null && code.subName != null && !subroutineName.isEmpty()) {
                    // If this is an imported forward declaration, check AUTOLOAD in the source package FIRST
                    // This matches Perl semantics where imported subs resolve via the exporting package's AUTOLOAD
                    if (code.sourcePackage != null && !code.sourcePackage.equals(code.packageName)) {
                        String sourceAutoloadString = code.sourcePackage + "::AUTOLOAD";
                        RuntimeScalar sourceAutoload = GlobalVariable.getGlobalCodeRef(sourceAutoloadString);
                        if (sourceAutoload.getDefinedBoolean()) {
                            // Set $AUTOLOAD name to the original package function name
                            String sourceSubroutineName = code.sourcePackage + "::" + code.subName;
                            getGlobalVariable(sourceAutoloadString).set(sourceSubroutineName);
                            // Call AUTOLOAD from the source package
                            return apply(sourceAutoload, a, callContext);
                        }
                    }

                    // Then check if AUTOLOAD exists in the current package
                    String autoloadString = code.packageName + "::AUTOLOAD";
                    RuntimeScalar autoload = GlobalVariable.getGlobalCodeRef(autoloadString);
                    if (autoload.getDefinedBoolean()) {
                        // Set $AUTOLOAD name
                        getGlobalVariable(autoloadString).set(subroutineName);
                        // Call AUTOLOAD
                        return apply(autoload, a, callContext);
                    }
                }
                throw new PerlCompilerException("Undefined subroutine &" + subroutineName + " called");
            }
            // Look up warning bits for the code's class and push to context stack
            // This enables FATAL warnings to work even at top-level (no caller frame)
            String warningBits = getWarningBitsForCode(code);
            if (warningBits != null) {
                WarningBitsRegistry.pushCurrent(warningBits);
            }
            // Save caller's call-site warning bits so caller()[9] can retrieve them
            WarningBitsRegistry.pushCallerBits();
            // Save caller's $^H so caller()[8] can retrieve them
            WarningBitsRegistry.pushCallerHints();
            // Save caller's call-site hint hash so caller()[10] can retrieve them
            HintHashRegistry.pushCallerHintHash();
            int cleanupMark = MyVarCleanupStack.pushMark();
            // Establish a function-scoped mortal boundary so that
            // statement-boundary flushAboveMark() inside this function
            // only processes entries from this scope, not entries from
            // the caller (e.g., bless mortal entries for method chain
            // temporaries like Foo->new()->method()).
            MortalList.pushMark();
            try {
                // Cast the value to RuntimeCode and call apply()
                RuntimeList result = code.apply(a, callContext);
                // Handle tail calls (goto &func) — trampoline loop
                // JVM-generated bytecode has its own trampoline; this handles calls from Java code
                while (result instanceof RuntimeControlFlowList cfList
                        && cfList.getControlFlowType() == ControlFlowType.TAILCALL) {
                    RuntimeScalar tailCodeRef = cfList.getTailCallCodeRef();
                    RuntimeArray tailArgs = cfList.getTailCallArgs();
                    result = apply(tailCodeRef, tailArgs != null ? tailArgs : a, callContext);
                }
                // Mortal-ize blessed refs with refCount==0 in void-context calls.
                // These are objects that were created but never stored in a named
                // variable (e.g., discarded return values from constructors).
                if (callContext == RuntimeContextType.VOID) {
                    MortalList.mortalizeForVoidDiscard(result);
                    // Flush deferred DESTROY decrements from the sub's scope exit.
                    // Sub bodies use flush=false in emitScopeExitNullStores to protect
                    // return values on the stack, but in void context there is no return
                    // value to protect. Without this flush, DESTROY fires outside the
                    // caller's dynamic scope — e.g., after local $SIG{__WARN__} unwinds,
                    // causing Test::Warn to miss warnings from DESTROY.
                    MortalList.flush();
                }
                return result;
            } catch (PerlNonLocalReturnException e) {
                // Non-local return from map/grep block
                if (code.isMapGrepBlock || code.isEvalBlock) {
                    throw e;  // Propagate through map/grep blocks and eval blocks
                }
                // Consume at normal subroutine boundary
                return e.returnValue != null ? e.returnValue.getList() : new RuntimeList();
            } catch (RuntimeException e) {
                // On die: run scopeExitCleanup for my-variables whose normal
                // SCOPE_EXIT_CLEANUP bytecodes were skipped by the exception.
                // PerlExitException (exit()) is excluded — global destruction handles it.
                if (!(e instanceof PerlExitException)) {
                    MyVarCleanupStack.unwindTo(cleanupMark);
                    MortalList.flush();
                }
                throw e;
            } finally {
                // Pop the function-scoped mortal mark. Entries added by this
                // function's scope-exit cleanup "fall" to the caller's scope
                // and will be processed by the caller's flushAboveMark().
                MortalList.popMark();
                // After unwindTo, entries are already removed; popMark is a no-op.
                // On normal return, popMark discards registrations without cleanup.
                MyVarCleanupStack.popMark(cleanupMark);
                HintHashRegistry.popCallerHintHash();
                WarningBitsRegistry.popCallerHints();
                WarningBitsRegistry.popCallerBits();
                if (warningBits != null) {
                    WarningBitsRegistry.popCurrent();
                }
                // eval BLOCK is compiled as an immediately-invoked anonymous sub
                // (sub { ... }->()) that captures outer lexicals, incrementing their
                // captureCount. Unlike a normal closure that may be stored and reused,
                // eval BLOCK executes once and is discarded. Release captures eagerly
                // so captureCount is decremented promptly, allowing scopeExitCleanup
                // to properly decrement refCount when the outer scope exits.
                // (eval STRING uses applyEval() which already does this.)
                if (code.isEvalBlock) {
                    code.releaseCaptures();
                }
            }
        }

        // Handle GLOB type - extract CODE slot from the glob
        if (runtimeScalar.type == RuntimeScalarType.GLOB) {
            RuntimeGlob glob = (RuntimeGlob) runtimeScalar.value;
            if (glob.globName != null) {
                RuntimeScalar resolved = GlobalVariable.getGlobalCodeRef(glob.globName);
                return apply(resolved, a, callContext);
            } else if (glob.codeSlot != null) {
                return apply(glob.codeSlot, a, callContext);
            }
        }

        // Handle REFERENCE to GLOB (e.g., \*Foo) - dereference to get the glob, then extract CODE
        if ((runtimeScalar.type == RuntimeScalarType.REFERENCE || runtimeScalar.type == RuntimeScalarType.GLOBREFERENCE)
                && runtimeScalar.value instanceof RuntimeGlob glob) {
            if (glob.globName != null) {
                RuntimeScalar resolved = GlobalVariable.getGlobalCodeRef(glob.globName);
                return apply(resolved, a, callContext);
            } else if (glob.codeSlot != null) {
                return apply(glob.codeSlot, a, callContext);
            }
        }

        if (runtimeScalar.type == STRING || runtimeScalar.type == BYTE_STRING) {
            String varName = NameNormalizer.normalizeVariableName(runtimeScalar.toString(), "main");
            RuntimeScalar resolved = GlobalVariable.getGlobalCodeRef(varName);
            return apply(resolved, a, callContext);
        }

        RuntimeScalar overloadedCode = handleCodeOverload(runtimeScalar);
        if (overloadedCode != null) {
            return apply(overloadedCode, a, callContext);
        }

        // If the type is not CODE, throw an exception indicating an invalid state
        throw new PerlCompilerException("Not a CODE reference");
    }

    // Method to apply (execute) a subroutine reference for eval/evalbytes.
    // Eval STRING must allow next/last/redo to propagate to the enclosing scope.
    // The caller is responsible for handling RuntimeControlFlowList markers.
    public static RuntimeList applyEval(RuntimeScalar runtimeScalar, RuntimeArray a, int callContext) {
        evalDepth++;
        try {
            RuntimeList result = apply(runtimeScalar, a, callContext);
            // Perl clears $@ on successful eval (even if nested evals previously set it).
            GlobalVariable.setGlobalVariable("main::@", "");
            return result;
        } catch (PerlNonLocalReturnException e) {
            // Non-local return from map/grep inside eval STRING - propagate, don't catch
            throw e;
        } catch (Throwable t) {
            // Perl eval catches exceptions; set $@ and return undef / empty list.
            WarnDie.catchEval(t);

            // If $@ is set and $^P flags require source retention, we may need to retain lines
            // for runtime errors (e.g., BEGIN/UNITCHECK die) where storeSourceLines wasn't called.
            // Try to extract the eval string from the codeRef if available
            String evalString = null;
            String filename = null;
            if (runtimeScalar.type == RuntimeScalarType.CODE) {
                RuntimeCode code = (RuntimeCode) runtimeScalar.value;
                // Use the evalString if it was captured in the codeRef
                // Note: This is a best-effort fallback; the primary path is evalStringHelper
                if (code.packageName != null && code.packageName.startsWith("(eval")) {
                    filename = code.packageName;
                    // We cannot reconstruct the exact eval string here, so skip retention
                }
            }

            if (callContext == RuntimeContextType.LIST) {
                return new RuntimeList();
            }
            return new RuntimeList(new RuntimeScalar());
        } finally {
            evalDepth--;
            // Release captured variable references from the eval's code object.
            // After eval STRING finishes executing, its captures are no longer needed.
            if (runtimeScalar.type == RuntimeScalarType.CODE && runtimeScalar.value instanceof RuntimeCode code) {
                code.releaseCaptures();
            }
        }
    }

    private static RuntimeScalar handleCodeOverload(RuntimeScalar runtimeScalar) {
        // Check if object is eligible for overloading
        int blessId = blessedId(runtimeScalar);
        if (blessId < 0) {
            // Prepare overload context and check if object is eligible for overloading
            OverloadContext ctx = OverloadContext.prepare(blessId);
            if (ctx != null) {
                // Try overload method
                RuntimeScalar result = ctx.tryOverload("(&{}", new RuntimeArray(runtimeScalar));
                // If the subroutine returns the object itself then it will not be called again
                if (result != null && result.value.hashCode() != runtimeScalar.value.hashCode()) {
                    return result;
                }
            }
        }
        return null;
    }

    /**
     * Gets the warning bits string for a RuntimeCode.
     * For InterpretedCode, uses the stored warningBitsString field.
     * For JVM-compiled code, looks up in WarningBitsRegistry by class name.
     *
     * @param code The RuntimeCode to get warning bits for
     * @return The warning bits string, or null if not available
     */
    private static String getWarningBitsForCode(RuntimeCode code) {
        // For InterpretedCode, use the stored field directly
        if (code instanceof org.perlonjava.backend.bytecode.InterpretedCode interpCode) {
            return interpCode.warningBitsString;
        }
        
        // For JVM-compiled code, look up by class name in the registry
        // The methodHandle's class is the generated class that has WARNING_BITS field
        if (code.methodHandle != null) {
            // Get the declaring class of the method handle
            try {
                // The type contains the declaring class as the first parameter type for instance methods
                // For our generated apply methods, we use the class that was loaded
                String className = code.methodHandle.type().parameterType(0).getName();
                return WarningBitsRegistry.get(className);
            } catch (Exception e) {
                // If we can't get the class name, fall back to null
                return null;
            }
        }
        
        return null;
    }

    // Method to apply (execute) a subroutine reference using native array for parameters
    public static RuntimeList apply(RuntimeScalar runtimeScalar, String subroutineName, RuntimeBase[] args, int callContext) {
        // Handle tied scalars - fetch the underlying value first
        if (runtimeScalar.type == RuntimeScalarType.TIED_SCALAR) {
            return apply(runtimeScalar.tiedFetch(), subroutineName, args, callContext);
        }
        if (runtimeScalar.type == READONLY_SCALAR) {
            return apply((RuntimeScalar) runtimeScalar.value, subroutineName, args, callContext);
        }
        // WORKAROUND for eval-defined subs not filling lexical forward declarations:
        // If the RuntimeScalar is undef (forward declaration never filled),
        // silently return undef so tests can continue running.
        // This is a temporary workaround for the architectural limitation that eval        // contexts are captured at compile time.
        if (runtimeScalar.type == RuntimeScalarType.UNDEF) {
            // Return undef in appropriate context
            if (callContext == RuntimeContextType.LIST) {
                return new RuntimeList();
            } else {
                return new RuntimeList(new RuntimeScalar());
            }
        }

        // Check if the type of this RuntimeScalar is CODE
        if (runtimeScalar.type == RuntimeScalarType.CODE) {

            // Transform the native array to RuntimeArray of aliases (Perl variable `@_`)
            RuntimeArray a = new RuntimeArray();
            for (RuntimeBase arg : args) {
                arg.setArrayOfAlias(a);
            }

            RuntimeCode code = (RuntimeCode) runtimeScalar.value;

            // Check for closure prototype — calling one should die
            if (code.isClosurePrototype) {
                throw new PerlDieException(new RuntimeScalar("Closure prototype called"));
            }

            // CRITICAL: Run compilerSupplier BEFORE checking defined()
            // The compilerSupplier may replace runtimeScalar.value with InterpretedCode
            if (code.compilerSupplier != null) {
                RuntimeList savedConstantValue = code.constantValue;
                java.util.List<String> savedAttributes = code.attributes;
                code.compilerSupplier.get();
                // Reload code from runtimeScalar.value in case it was replaced
                code = (RuntimeCode) runtimeScalar.value;
                // Transfer fields that were set on the old code (e.g., by :const attribute)
                if (savedConstantValue != null && code.constantValue == null) {
                    code.constantValue = savedConstantValue;
                }
                if (savedAttributes != null && code.attributes == null) {
                    code.attributes = savedAttributes;
                }
            }

            // Lazily generate CORE:: subroutine wrappers on first call
            if (!code.defined() && "CORE".equals(code.packageName) && code.subName != null) {
                if (CoreSubroutineGenerator.generateWrapper(code.subName)) {
                    runtimeScalar = GlobalVariable.getGlobalCodeRef("CORE::" + code.subName);
                    code = (RuntimeCode) runtimeScalar.value;
                }
            }

            if (code.defined()) {
                // Look up warning bits for the code's class and push to context stack
                String warningBits = getWarningBitsForCode(code);
                if (warningBits != null) {
                    WarningBitsRegistry.pushCurrent(warningBits);
                }
                // Save caller's call-site warning bits so caller()[9] can retrieve them
                WarningBitsRegistry.pushCallerBits();
                // Save caller's $^H so caller()[8] can retrieve them
                WarningBitsRegistry.pushCallerHints();
                // Save caller's call-site hint hash so caller()[10] can retrieve them
                HintHashRegistry.pushCallerHintHash();
                int cleanupMark = MyVarCleanupStack.pushMark();
                MortalList.pushMark();
                try {
                    // Cast the value to RuntimeCode and call apply()
                    RuntimeList result = code.apply(subroutineName, a, callContext);
                    // Flush deferred DESTROY decrements for void-context calls.
                    // See the 3-arg apply() overload for detailed rationale.
                    if (callContext == RuntimeContextType.VOID) {
                        MortalList.mortalizeForVoidDiscard(result);
                        MortalList.flush();
                    }
                    return result;
                } catch (PerlNonLocalReturnException e) {
                    // Non-local return from map/grep block
                    if (code.isMapGrepBlock || code.isEvalBlock) {
                        throw e;  // Propagate through map/grep blocks and eval blocks
                    }
                    // Consume at normal subroutine boundary
                    return e.returnValue != null ? e.returnValue.getList() : new RuntimeList();
                } catch (RuntimeException e) {
                    if (!(e instanceof PerlExitException)) {
                        MyVarCleanupStack.unwindTo(cleanupMark);
                        MortalList.flush();
                    }
                    throw e;
                } finally {
                    MortalList.popMark();
                    MyVarCleanupStack.popMark(cleanupMark);
                    HintHashRegistry.popCallerHintHash();
                    WarningBitsRegistry.popCallerHints();
                    WarningBitsRegistry.popCallerBits();
                    if (warningBits != null) {
                        WarningBitsRegistry.popCurrent();
                    }
                }
            }

            // Does AUTOLOAD exist?
            // If subroutineName is empty, construct it from the RuntimeCode's package and sub name
            String fullSubName = subroutineName;
            if (fullSubName.isEmpty() && code.packageName != null && code.subName != null) {
                fullSubName = code.packageName + "::" + code.subName;
            }

            if (!fullSubName.isEmpty()) {
                // If this is an imported forward declaration, check AUTOLOAD in the source package FIRST
                // This matches Perl semantics where imported subs resolve via the exporting package's AUTOLOAD
                if (code.sourcePackage != null && !code.sourcePackage.isEmpty()) {
                    String sourceAutoloadString = code.sourcePackage + "::AUTOLOAD";
                    RuntimeScalar sourceAutoload = GlobalVariable.getGlobalCodeRef(sourceAutoloadString);
                    if (sourceAutoload.getDefinedBoolean()) {
                        // Set $AUTOLOAD name to the original package function name
                        String sourceSubroutineName = code.sourcePackage + "::" + code.subName;
                        getGlobalVariable(sourceAutoloadString).set(sourceSubroutineName);
                        // Call AUTOLOAD from the source package
                        return apply(sourceAutoload, a, callContext);
                    }
                }

                // Then check if AUTOLOAD exists in the current package
                String autoloadString = fullSubName.substring(0, fullSubName.lastIndexOf("::") + 2) + "AUTOLOAD";
                RuntimeScalar autoload = GlobalVariable.getGlobalCodeRef(autoloadString);
                if (autoload.getDefinedBoolean()) {
                    // Set $AUTOLOAD name
                    getGlobalVariable(autoloadString).set(fullSubName);
                    // Call AUTOLOAD
                    return apply(autoload, a, callContext);
                }
                throw new PerlCompilerException(gotoErrorPrefix(subroutineName) + "ndefined subroutine &" + fullSubName + " called");
            }
        }

        // Handle GLOB type - extract CODE slot from the glob
        if (runtimeScalar.type == RuntimeScalarType.GLOB) {
            RuntimeGlob glob = (RuntimeGlob) runtimeScalar.value;
            if (glob.globName != null) {
                RuntimeScalar resolved = GlobalVariable.getGlobalCodeRef(glob.globName);
                return apply(resolved, subroutineName, args, callContext);
            } else if (glob.codeSlot != null) {
                return apply(glob.codeSlot, subroutineName, args, callContext);
            }
        }

        // Handle REFERENCE to GLOB (e.g., \*Foo) - dereference to get the glob, then extract CODE
        if ((runtimeScalar.type == RuntimeScalarType.REFERENCE || runtimeScalar.type == RuntimeScalarType.GLOBREFERENCE)
                && runtimeScalar.value instanceof RuntimeGlob glob) {
            if (glob.globName != null) {
                RuntimeScalar resolved = GlobalVariable.getGlobalCodeRef(glob.globName);
                return apply(resolved, subroutineName, args, callContext);
            } else if (glob.codeSlot != null) {
                return apply(glob.codeSlot, subroutineName, args, callContext);
            }
        }

        if (runtimeScalar.type == STRING || runtimeScalar.type == BYTE_STRING) {
            String varName = NameNormalizer.normalizeVariableName(runtimeScalar.toString(), "main");
            RuntimeScalar resolved = GlobalVariable.getGlobalCodeRef(varName);
            return apply(resolved, subroutineName, args, callContext);
        }

        RuntimeScalar overloadedCode = handleCodeOverload(runtimeScalar);
        if (overloadedCode != null) {
            return apply(overloadedCode, subroutineName, args, callContext);
        }

        throw new PerlCompilerException("Not a CODE reference");
    }

    // Method to apply (execute) a subroutine reference (legacy method for compatibility)
    public static RuntimeList apply(RuntimeScalar runtimeScalar, String subroutineName, RuntimeBase list, int callContext) {

        // Handle tied scalars - fetch the underlying value first
        if (runtimeScalar.type == RuntimeScalarType.TIED_SCALAR) {
            return apply(runtimeScalar.tiedFetch(), subroutineName, list, callContext);
        }
        if (runtimeScalar.type == READONLY_SCALAR) {
            return apply((RuntimeScalar) runtimeScalar.value, subroutineName, list, callContext);
        }

        // WORKAROUND for eval-defined subs not filling lexical forward declarations:
        // If the RuntimeScalar is undef (forward declaration never filled),
        // silently return undef so tests can continue running.
        // This is a temporary workaround for the architectural limitation that eval
        // contexts are captured at compile time.
        if (runtimeScalar.type == RuntimeScalarType.UNDEF) {
            // Return undef in appropriate context
            if (callContext == RuntimeContextType.LIST) {
                return new RuntimeList();
            } else {
                return new RuntimeList(new RuntimeScalar());
            }
        }

        // Check if the type of this RuntimeScalar is CODE
        if (runtimeScalar.type == RuntimeScalarType.CODE) {

            // Transform the value in the stack to RuntimeArray of aliases (Perl variable `@_`)
            RuntimeArray a = list.getArrayOfAlias();

            RuntimeCode code = (RuntimeCode) runtimeScalar.value;

            // Check for closure prototype — calling one should die
            if (code.isClosurePrototype) {
                throw new PerlDieException(new RuntimeScalar("Closure prototype called"));
            }

            // CRITICAL: Run compilerSupplier BEFORE checking defined()
            // The compilerSupplier may replace runtimeScalar.value with InterpretedCode
            if (code.compilerSupplier != null) {
                RuntimeList savedConstantValue = code.constantValue;
                java.util.List<String> savedAttributes = code.attributes;
                code.compilerSupplier.get();
                // Reload code from runtimeScalar.value in case it was replaced
                code = (RuntimeCode) runtimeScalar.value;
                // Transfer fields that were set on the old code (e.g., by :const attribute)
                if (savedConstantValue != null && code.constantValue == null) {
                    code.constantValue = savedConstantValue;
                }
                if (savedAttributes != null && code.attributes == null) {
                    code.attributes = savedAttributes;
                }
            }

            // Lazily generate CORE:: subroutine wrappers on first call
            if (!code.defined() && "CORE".equals(code.packageName) && code.subName != null) {
                if (CoreSubroutineGenerator.generateWrapper(code.subName)) {
                    runtimeScalar = GlobalVariable.getGlobalCodeRef("CORE::" + code.subName);
                    code = (RuntimeCode) runtimeScalar.value;
                }
            }

            if (code.defined()) {
                // Look up warning bits for the code's class and push to context stack
                String warningBits = getWarningBitsForCode(code);
                if (warningBits != null) {
                    WarningBitsRegistry.pushCurrent(warningBits);
                }
                // Save caller's call-site warning bits so caller()[9] can retrieve them
                WarningBitsRegistry.pushCallerBits();
                // Save caller's $^H so caller()[8] can retrieve them
                WarningBitsRegistry.pushCallerHints();
                // Save caller's call-site hint hash so caller()[10] can retrieve them
                HintHashRegistry.pushCallerHintHash();
                int cleanupMark = MyVarCleanupStack.pushMark();
                MortalList.pushMark();
                try {
                    // Cast the value to RuntimeCode and call apply()
                    RuntimeList result = code.apply(subroutineName, a, callContext);
                    // Flush deferred DESTROY decrements for void-context calls.
                    // See the 3-arg apply() overload for detailed rationale.
                    if (callContext == RuntimeContextType.VOID) {
                        MortalList.mortalizeForVoidDiscard(result);
                        MortalList.flush();
                    }
                    return result;
                } catch (PerlNonLocalReturnException e) {
                    // Non-local return from map/grep block
                    if (code.isMapGrepBlock || code.isEvalBlock) {
                        throw e;  // Propagate through map/grep blocks and eval blocks
                    }
                    // Consume at normal subroutine boundary
                    return e.returnValue != null ? e.returnValue.getList() : new RuntimeList();
                } catch (RuntimeException e) {
                    if (!(e instanceof PerlExitException)) {
                        MyVarCleanupStack.unwindTo(cleanupMark);
                        MortalList.flush();
                    }
                    throw e;
                } finally {
                    MortalList.popMark();
                    MyVarCleanupStack.popMark(cleanupMark);
                    HintHashRegistry.popCallerHintHash();
                    WarningBitsRegistry.popCallerHints();
                    WarningBitsRegistry.popCallerBits();
                    if (warningBits != null) {
                        WarningBitsRegistry.popCurrent();
                    }
                }
            }

            // Does AUTOLOAD exist?
            String fullSubName = (code.packageName != null && code.subName != null)
                    ? code.packageName + "::" + code.subName
                    : subroutineName;

            if (!fullSubName.isEmpty() && fullSubName.contains("::")) {
                // If this is an imported forward declaration, check AUTOLOAD in the source package FIRST
                if (code.sourcePackage != null && !code.sourcePackage.isEmpty()) {
                    String sourceAutoloadString = code.sourcePackage + "::AUTOLOAD";
                    RuntimeScalar sourceAutoload = GlobalVariable.getGlobalCodeRef(sourceAutoloadString);
                    if (sourceAutoload.getDefinedBoolean()) {
                        String sourceSubroutineName = code.sourcePackage + "::" + code.subName;
                        getGlobalVariable(sourceAutoloadString).set(sourceSubroutineName);
                        return apply(sourceAutoload, a, callContext);
                    }
                }

                // Check if AUTOLOAD exists in the current package
                String autoloadString = fullSubName.substring(0, fullSubName.lastIndexOf("::") + 2) + "AUTOLOAD";
                RuntimeScalar autoload = GlobalVariable.getGlobalCodeRef(autoloadString);
                if (autoload.getDefinedBoolean()) {
                    getGlobalVariable(autoloadString).set(fullSubName);
                    return apply(autoload, a, callContext);
                }
                throw new PerlCompilerException(gotoErrorPrefix(subroutineName) + "ndefined subroutine &" + fullSubName + " called");
            }
            throw new PerlCompilerException(gotoErrorPrefix(subroutineName) + "ndefined subroutine &" + fullSubName + " called");
        }

        // Handle GLOB type - extract CODE slot from the glob
        if (runtimeScalar.type == RuntimeScalarType.GLOB) {
            RuntimeGlob glob = (RuntimeGlob) runtimeScalar.value;
            if (glob.globName != null) {
                RuntimeScalar resolved = GlobalVariable.getGlobalCodeRef(glob.globName);
                return apply(resolved, subroutineName, list, callContext);
            } else if (glob.codeSlot != null) {
                return apply(glob.codeSlot, subroutineName, list, callContext);
            }
        }

        // Handle REFERENCE to GLOB (e.g., \*Foo) - dereference to get the glob, then extract CODE
        if ((runtimeScalar.type == RuntimeScalarType.REFERENCE || runtimeScalar.type == RuntimeScalarType.GLOBREFERENCE)
                && runtimeScalar.value instanceof RuntimeGlob glob) {
            if (glob.globName != null) {
                RuntimeScalar resolved = GlobalVariable.getGlobalCodeRef(glob.globName);
                return apply(resolved, subroutineName, list, callContext);
            } else if (glob.codeSlot != null) {
                return apply(glob.codeSlot, subroutineName, list, callContext);
            }
        }

        if (runtimeScalar.type == STRING || runtimeScalar.type == BYTE_STRING) {
            String varName = NameNormalizer.normalizeVariableName(runtimeScalar.toString(), "main");
            RuntimeScalar resolved = GlobalVariable.getGlobalCodeRef(varName);
            return apply(resolved, subroutineName, list, callContext);
        }

        RuntimeScalar overloadedCode = handleCodeOverload(runtimeScalar);
        if (overloadedCode != null) {
            return apply(overloadedCode, subroutineName, list, callContext);
        }

        throw new PerlCompilerException("Not a CODE reference");
    }

    // Handle \$var where $var might be a CODE reference (for lexical subs)
    // If the value is a CODE reference, return it directly
    // Otherwise, create a scalar reference to it
    public static RuntimeScalar maybeUnwrapCodeReference(RuntimeBase base) {
        if (base instanceof RuntimeScalar scalar) {
            // If it's already a CODE reference, return it directly
            // This handles \&foo where foo is a lexical sub
            if (scalar.type == RuntimeScalarType.CODE) {
                return scalar;
            }
        }
        // For all other cases, create a normal reference
        return base.createReference();
    }

    // Return a reference to the subroutine with this name: \&$a
    public static RuntimeScalar createCodeReference(RuntimeScalar runtimeScalar, String packageName) {
        // Special case: if the scalar already contains a CODE reference (lexical sub hidden variable),
        // just return it directly
        if (runtimeScalar.type == RuntimeScalarType.CODE) {
            // Ensure the subroutine is fully compiled before returning the reference
            // This is important for compile-time usage (e.g., use overload qr => \&lexical_sub)
            RuntimeCode code = (RuntimeCode) runtimeScalar.value;
            if (code.compilerSupplier != null) {
                code.compilerSupplier.get(); // Wait for compilation to finish
            }
            return runtimeScalar;
        }

        // Check if object is eligible for &{} overloading (e.g., blessed object with &{} operator)
        // This handles cases like \&{$constraint_obj} where $constraint_obj overloads &{}
        // blessId: negative = blessed with overload, positive = blessed without overload, 0 = not blessed
        int blessId = blessedId(runtimeScalar);
        // System.err.println("DEBUG createCodeReference: type=" + runtimeScalar.type + " blessId=" + blessId + " value=" + runtimeScalar.value);
        if (blessId != 0) {
            // Object is blessed
            if (blessId < 0) {
                // Has overloading - try to get &{} overload
                OverloadContext ctx = OverloadContext.prepare(blessId);
                if (ctx != null) {
                    RuntimeScalar result = ctx.tryOverload("(&{}", new RuntimeArray(runtimeScalar));
                    if (result != null && result.value.hashCode() != runtimeScalar.value.hashCode()) {
                        // Successfully got a CODE reference via overload, return it
                        if (result.type == RuntimeScalarType.CODE) {
                            return result;
                        }
                        // Recursively handle if not CODE yet
                        return createCodeReference(result, packageName);
                    }
                }
            }
            // Blessed reference without &{} overload - this is an error in Perl
            // "Not a subroutine reference"
            throw new PerlCompilerException("Not a subroutine reference");
        }

        // Check if this is a reference type that isn't CODE - error "Not a subroutine reference"
        // This catches cases like \&{$hashref} where $hashref is an unblessed reference
        if (runtimeScalar.type == RuntimeScalarType.REFERENCE) {
            RuntimeScalar deref = (RuntimeScalar) runtimeScalar.value;
            if (deref.type != RuntimeScalarType.CODE) {
                throw new PerlCompilerException("Not a subroutine reference");
            }
        }

        // Handle GLOB type: \&{*glob} - get the code slot directly from the glob
        // Globs stringify with a "*" prefix (e.g., "*main::test_sub") which would
        // cause normalizeVariableName to look up the wrong name
        if (runtimeScalar.type == RuntimeScalarType.GLOB) {
            RuntimeGlob glob = (RuntimeGlob) runtimeScalar.value;
            // For detached globs (null globName, from stash delete), use local code slot
            if (glob.globName == null) {
                if (glob.codeSlot != null) {
                    RuntimeScalar snapshot = new RuntimeScalar();
                    snapshot.type = glob.codeSlot.type;
                    snapshot.value = glob.codeSlot.value;
                    return snapshot;
                }
                return new RuntimeScalar(); // undef
            }
            RuntimeScalar codeRef = GlobalVariable.getGlobalCodeRef(glob.globName);
            
            // Return a snapshot of the current code reference
            RuntimeScalar snapshot = new RuntimeScalar();
            snapshot.type = codeRef.type;
            snapshot.value = codeRef.value;
            return snapshot;
        }

        String name = NameNormalizer.normalizeVariableName(runtimeScalar.toString(), packageName);
        // System.out.println("Creating code reference: " + name + " got: " + GlobalContext.getGlobalCodeRef(name));
        RuntimeScalar codeRef = GlobalVariable.getGlobalCodeRef(name);

        // Lazily generate CORE:: subroutine wrappers on first reference
        if (name.startsWith("CORE::") && codeRef.type == RuntimeScalarType.CODE
                && codeRef.value instanceof RuntimeCode rc && !rc.defined()) {
            String opName = name.substring(6);
            if (org.perlonjava.runtime.CoreSubroutineGenerator.generateWrapper(opName)) {
                codeRef = GlobalVariable.getGlobalCodeRef(name);
            }
        }

        // Check if this is a constant subroutine
        if (codeRef.type == RuntimeScalarType.CODE && codeRef.value instanceof RuntimeCode runtimeCode) {
            // Mark this as a symbolic reference created by \&{string} pattern
            // This ensures defined(\&{nonexistent}) returns true to match standard Perl behavior
            runtimeCode.isSymbolicReference = true;
            
            // Note: We used to return a reference to the constant value here, but that
            // breaks Exporter which does `*{$pkg::$sym} = \&{$src::$sym}`. The glob
            // assignment expects a CODE reference, not a scalar reference.
            // The constant value optimization is handled separately when calling the sub.
        }

        // Return a snapshot of the current code reference, not the global entry itself.
        // This ensures that saved code references (\&sub) point to the current RuntimeCode
        // and won't be affected if the subroutine is later redefined.
        // This matches Perl's behavior where $orig = \&foo; sub foo {...} leaves $orig
        // pointing to the old version.
        RuntimeScalar snapshot = new RuntimeScalar();
        snapshot.type = codeRef.type;
        snapshot.value = codeRef.value;
        return snapshot;
    }

    public static RuntimeScalar prototype(RuntimeScalar runtimeScalar, String packageName) {
        RuntimeScalar code = runtimeScalar;
        if (code.type != RuntimeScalarType.CODE) {
            String name = NameNormalizer.normalizeVariableName(code.toString(), packageName);
            // System.out.println("Looking for prototype: " + name);

            if (name.startsWith("CORE::")) {
                String key = name.substring(6);
                if (!CORE_PROTOTYPES.containsKey(key)) {
                    throw new PerlCompilerException("Can't find an opnumber for \"" + key + "\"");
                }
                return new RuntimeScalar(CORE_PROTOTYPES.get(key));
            }

            code = GlobalVariable.getGlobalCodeRef(name);
        }
        // System.out.println("type: " + code.type);
        if (code.type == RuntimeScalarType.CODE) {
            // System.out.println("prototype: " + ((RuntimeCode) code.value).prototype);
            return new RuntimeScalar(((RuntimeCode) code.value).prototype);
        }
        return scalarUndef;
    }

    /**
     * Gets the current package name using caller() information
     *
     * @return The current package name with "::" suffix
     */
    public static String getCurrentPackage() {
        // Use caller() to get the current package
        RuntimeList callerInfo = caller(new RuntimeList(), RuntimeContextType.LIST);

        if (!callerInfo.isEmpty()) {
            String packageName = callerInfo.getFirst().toString();
            // Ensure it ends with "::" for prefix matching
            return packageName.endsWith("::") ? packageName : packageName + "::";
        }

        // Fallback to main package if caller info is not available
        return "main::";
    }

    /**
     * Replace lazy {@link ScalarSpecialVariable} references ($1, $&amp;, etc.) and
     * {@link RuntimeArray} references in a return list with concrete copies.
     * Must be called BEFORE {@link RegexState#restore()} and local variable restoration
     * so that the values reflect the subroutine's state, not the caller's.
     *
     * <p>This is critical for returning localized arrays (e.g., {@code local @ARGV}).
     * Without this, the array reference in the return list would point to the restored
     * (original) values after the local scope exits.</p>
     */
    public static void materializeSpecialVarsInResult(RuntimeList result) {
        List<RuntimeBase> elems = result.elements;
        for (int i = 0; i < elems.size(); i++) {
            RuntimeBase elem = elems.get(i);
            if (elem instanceof ScalarSpecialVariable ssv) {
                RuntimeScalar resolved = ssv.getValueAsScalar();
                RuntimeScalar concrete = new RuntimeScalar();
                concrete.type = resolved.type;
                concrete.value = resolved.value;
                elems.set(i, concrete);
            } else if (elem instanceof RuntimeArray arr) {
                // Copy array elements to ensure independence from local restoration.
                // For tied arrays, use getList() which dispatches through FETCHSIZE/FETCH,
                // since TieArray.elements (the ArrayList) is empty — data lives in the tied object.
                // For regular arrays, copy elements directly.
                if (arr.type == RuntimeArray.TIED_ARRAY) {
                    RuntimeList arrList = arr.getList();
                    elems.set(i, arrList);
                } else {
                    RuntimeArray copy = new RuntimeArray();
                    for (RuntimeScalar arrElem : arr.elements) {
                        copy.elements.add(arrElem == null ? null : new RuntimeScalar(arrElem));
                    }
                    elems.set(i, copy);
                }
            } else if (elem instanceof RuntimeHash hash) {
                // Copy hash elements for the same reason as arrays.
                // For tied hashes, use getList() which dispatches through FIRSTKEY/NEXTKEY/FETCH.
                if (hash.type == RuntimeHash.TIED_HASH) {
                    RuntimeList hashList = hash.getList();
                    elems.set(i, hashList);
                } else {
                    RuntimeHash copy = new RuntimeHash();
                    for (var entry : hash.elements.entrySet()) {
                        copy.elements.put(entry.getKey(), new RuntimeScalar(entry.getValue()));
                    }
                    elems.set(i, copy);
                }
            }
        }
    }

    /**
     * Materialize a block's return value if it contains a lazy special variable proxy.
     * Must be called BEFORE {@link RegexState#restore()} at block exit so that
     * regex capture variables ($1, $&amp;, etc.) reflect the block's state, not the
     * restored caller state.
     *
     * <p>Uses {@code RuntimeScalar} parameter/return type (not {@code RuntimeBase})
     * to preserve JVM stack type information — the verifier needs the narrower type
     * when downstream code calls {@code RuntimeScalar}-specific methods.</p>
     *
     * @param result The block's return value (on the JVM operand stack)
     * @return A concrete copy if the value was a special variable proxy, otherwise the original
     */
    public static RuntimeScalar materializeBlockResult(RuntimeScalar result) {
        if (result instanceof ScalarSpecialVariable ssv) {
            RuntimeScalar resolved = ssv.getValueAsScalar();
            RuntimeScalar concrete = new RuntimeScalar();
            concrete.type = resolved.type;
            concrete.value = resolved.value;
            return concrete;
        }
        return result;
    }

    public boolean defined() {
        // Symbolic references created by \&{string} are always considered "defined" to match standard Perl
        if (this.isSymbolicReference) {
            return true;
        }
        // Built-in operators are always considered "defined"
        if (this.isBuiltin) {
            return true;
        }
        // Note: isDeclared is NOT checked here. In Perl 5, defined(&foo) returns
        // false for forward declarations (sub foo;). The isDeclared flag is used
        // only by RuntimeGlob.getGlobSlot("CODE") for *foo{CODE} visibility.
        return this.constantValue != null || this.compilerSupplier != null 
                || this.subroutine != null || this.methodHandle != null;
    }

    /**
     * Invokes the JVM-compiled method associated with this code object.
     *
     * <p>Regex state scoping ($1, $&amp;, etc.) is handled by {@link RegexState#save()}
     * at subroutine entry, restored by {@link DynamicVariableManager#popToLocalLevel} at exit.
     *
     * @param a           the RuntimeArray containing the arguments for the subroutine
     * @param callContext the context in which the subroutine is called
     * @return the result of the subroutine execution as a RuntimeList
     */
    public RuntimeList apply(RuntimeArray a, int callContext) {
        if (constantValue != null) {
            return new RuntimeList(constantValue);
        }
        try {
            if (this.compilerSupplier != null) {
                this.compilerSupplier.get();
            }

            // Check if subroutine is defined (prefer functional interface over methodHandle)
            if (this.subroutine == null && this.methodHandle == null) {
                // Lazily generate CORE:: subroutine wrappers on first call
                if ("CORE".equals(this.packageName) && this.subName != null) {
                    if (CoreSubroutineGenerator.generateWrapper(this.subName)) {
                        RuntimeScalar codeRef = GlobalVariable.getGlobalCodeRef("CORE::" + this.subName);
                        if (codeRef.type == RuntimeScalarType.CODE) {
                            RuntimeCode generated = (RuntimeCode) codeRef.value;
                            if (generated.defined()) {
                                return generated.apply(a, callContext);
                            }
                        }
                    }
                }
                String fullSubName = "";
                if (this.packageName != null && this.subName != null) {
                    fullSubName = this.packageName + "::" + this.subName;
                }
                if (!fullSubName.isEmpty()) {
                    if (this.sourcePackage != null && !this.sourcePackage.isEmpty()) {
                        String sourceAutoloadString = this.sourcePackage + "::AUTOLOAD";
                        RuntimeScalar sourceAutoload = GlobalVariable.getGlobalCodeRef(sourceAutoloadString);
                        if (sourceAutoload.getDefinedBoolean()) {
                            String sourceSubroutineName = this.sourcePackage + "::" + this.subName;
                            getGlobalVariable(sourceAutoloadString).set(sourceSubroutineName);
                            return apply(sourceAutoload, a, callContext);
                        }
                    }
                    String autoloadString = fullSubName.substring(0, fullSubName.lastIndexOf("::") + 2) + "AUTOLOAD";
                    RuntimeScalar autoload = GlobalVariable.getGlobalCodeRef(autoloadString);
                    if (autoload.getDefinedBoolean()) {
                        getGlobalVariable(autoloadString).set(fullSubName);
                        return apply(autoload, a, callContext);
                    }
                    throw new PerlCompilerException("Undefined subroutine &" + fullSubName + " called");
                }
                throw new PerlCompilerException("Undefined subroutine called at ");
            }

            // Debug mode: push args and track subroutine entry
            if (DebugState.debugMode) {
                String debugSubName = (this.subName != null)
                        ? NameNormalizer.normalizeVariableName(this.subName, this.packageName != null ? this.packageName : "main")
                        : "";
                DebugState.pushArgs(a);
                DebugHooks.enterSubroutine(debugSubName);
            }
            // Always push args for getCurrentArgs() support (used by List::Util::any/all/etc.)
            pushArgs(a);
            
            // Push warning bits for FATAL warnings support
            String warningBits = getWarningBitsForCode(this);
            if (warningBits != null) {
                WarningBitsRegistry.pushCurrent(warningBits);
            }
            try {
                RuntimeList result;
                // Prefer functional interface over MethodHandle for better performance
                if (this.subroutine != null) {
                    result = this.subroutine.apply(a, callContext);
                } else if (isStatic) {
                    result = (RuntimeList) this.methodHandle.invoke(a, callContext);
                } else {
                    result = (RuntimeList) this.methodHandle.invoke(this.codeObject, a, callContext);
                }
                return result;
            } finally {
                if (warningBits != null) {
                    WarningBitsRegistry.popCurrent();
                }
                popArgs();
                if (DebugState.debugMode) {
                    DebugHooks.exitSubroutine();
                    DebugState.popArgs();
                }
            }
        } catch (InvocationTargetException e) {
            Throwable targetException = e.getTargetException();
            // Handle fork-open completion (from exec in fork-open emulation)
            if (targetException instanceof ForkOpenCompleteException forkEx) {
                return forkOpenOutputToList(forkEx.capturedOutput, callContext);
            }
            if (targetException instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(targetException);
        } catch (ForkOpenCompleteException e) {
            // Handle fork-open completion (from exec in fork-open emulation)
            return forkOpenOutputToList(e.capturedOutput, callContext);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public RuntimeList apply(String subroutineName, RuntimeArray a, int callContext) {
        if (constantValue != null) {
            return new RuntimeList(constantValue);
        }
        try {
            if (this.compilerSupplier != null) {
                this.compilerSupplier.get();
            }

            // Check if subroutine is defined (prefer functional interface over methodHandle)
            if (this.subroutine == null && this.methodHandle == null) {
                String fullSubName = (this.packageName != null && this.subName != null)
                        ? this.packageName + "::" + this.subName
                        : subroutineName;
                if (fullSubName != null && !fullSubName.isEmpty() && fullSubName.contains("::")) {
                    if (this.sourcePackage != null && !this.sourcePackage.isEmpty()) {
                        String sourceAutoloadString = this.sourcePackage + "::AUTOLOAD";
                        RuntimeScalar sourceAutoload = GlobalVariable.getGlobalCodeRef(sourceAutoloadString);
                        if (sourceAutoload.getDefinedBoolean()) {
                            String sourceSubroutineName = this.sourcePackage + "::" + this.subName;
                            getGlobalVariable(sourceAutoloadString).set(sourceSubroutineName);
                            return apply(sourceAutoload, a, callContext);
                        }
                    }
                    String autoloadString = fullSubName.substring(0, fullSubName.lastIndexOf("::") + 2) + "AUTOLOAD";
                    RuntimeScalar autoload = GlobalVariable.getGlobalCodeRef(autoloadString);
                    if (autoload.getDefinedBoolean()) {
                        getGlobalVariable(autoloadString).set(fullSubName);
                        return apply(autoload, a, callContext);
                    }
                    throw new PerlCompilerException(gotoErrorPrefix(subroutineName) + "ndefined subroutine &" + fullSubName + " called");
                }
                throw new PerlCompilerException(gotoErrorPrefix(subroutineName) + "ndefined subroutine &" + (fullSubName != null ? fullSubName : "") + " called");
            }

            // Debug mode: push args and track subroutine entry
            if (DebugState.debugMode) {
                String debugSubName;
                if (this.subName != null) {
                    debugSubName = NameNormalizer.normalizeVariableName(this.subName, this.packageName != null ? this.packageName : "main");
                } else if (subroutineName != null) {
                    debugSubName = subroutineName;
                } else {
                    debugSubName = "";
                }
                DebugState.pushArgs(a);
                DebugHooks.enterSubroutine(debugSubName);
            }
            // Always push args for getCurrentArgs() support (used by List::Util::any/all/etc.)
            pushArgs(a);
            
            // Push warning bits for FATAL warnings support
            String warningBits = getWarningBitsForCode(this);
            if (warningBits != null) {
                WarningBitsRegistry.pushCurrent(warningBits);
            }
            try {
                RuntimeList result;
                // Prefer functional interface over MethodHandle for better performance
                if (this.subroutine != null) {
                    result = this.subroutine.apply(a, callContext);
                } else if (isStatic) {
                    result = (RuntimeList) this.methodHandle.invoke(a, callContext);
                } else {
                    result = (RuntimeList) this.methodHandle.invoke(this.codeObject, a, callContext);
                }
                return result;
            } finally {
                if (warningBits != null) {
                    WarningBitsRegistry.popCurrent();
                }
                popArgs();
                if (DebugState.debugMode) {
                    DebugHooks.exitSubroutine();
                    DebugState.popArgs();
                }
            }
        } catch (InvocationTargetException e) {
            Throwable targetException = e.getTargetException();
            // Handle fork-open completion (from exec in fork-open emulation)
            if (targetException instanceof ForkOpenCompleteException forkEx) {
                return forkOpenOutputToList(forkEx.capturedOutput, callContext);
            }
            if (targetException instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(targetException);
        } catch (ForkOpenCompleteException e) {
            // Handle fork-open completion (from exec in fork-open emulation)
            return forkOpenOutputToList(e.capturedOutput, callContext);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Converts fork-open captured output to a RuntimeList.
     * In list context, splits output into lines (like readline {@code <FH>}).
     * In scalar context, returns the entire output as a single scalar.
     */
    private static RuntimeList forkOpenOutputToList(String capturedOutput, int callContext) {
        if (callContext == RuntimeContextType.LIST && capturedOutput != null && !capturedOutput.isEmpty()) {
            // Split into lines preserving newlines (like Perl's <FH> in list context)
            RuntimeArray arr = new RuntimeArray();
            int start = 0;
            int len = capturedOutput.length();
            while (start < len) {
                int nlPos = capturedOutput.indexOf('\n', start);
                if (nlPos >= 0) {
                    arr.push(new RuntimeScalar(capturedOutput.substring(start, nlPos + 1)));
                    start = nlPos + 1;
                } else {
                    // Last line without trailing newline
                    arr.push(new RuntimeScalar(capturedOutput.substring(start)));
                    break;
                }
            }
            return new RuntimeList(arr);
        }
        return new RuntimeList(new RuntimeScalar(capturedOutput));
    }

    /**
     * Returns a string representation of the CODE reference.
     *
     * @return a string representing the CODE reference
     */
    public String toStringRef() {
        String ref = "CODE(0x" + Integer.toHexString(this.hashCode()) + ")";
        return (blessId == 0
                ? ref
                : NameNormalizer.getBlessStr(blessId) + "=" + ref);
    }

    /**
     * Returns an integer representation of the CODE reference.
     *
     * @return an integer representing the CODE reference
     */
    public int getIntRef() {
        return this.hashCode();
    }

    /**
     * Returns a double representation of the CODE reference.
     *
     * @return a double representing the CODE reference
     */
    public double getDoubleRef() {
        return this.hashCode();
    }

    /**
     * Returns a boolean representation of the CODE reference.
     *
     * @return true, indicating the presence of the CODE reference
     */
    public boolean getBooleanRef() {
        return true;
    }

    // Get the Scalar alias into an Array
    public RuntimeArray setArrayOfAlias(RuntimeArray arr) {
        arr.elements.add(new RuntimeScalar(this));
        return arr;
    }

    public int countElements() {
        return 1;
    }

    public RuntimeList getList() {
        return new RuntimeList(this);
    }

    public RuntimeScalar scalar() {
        return new RuntimeScalar(this);
    }

    public boolean getBoolean() {
        return true;
    }

    public boolean getDefinedBoolean() {
        return true;
    }

    public RuntimeScalar createReference() {
        RuntimeScalar result = new RuntimeScalar();
        result.type = RuntimeScalarType.REFERENCE;  // Fixed: should be REFERENCE, not CODE
        result.value = this;
        return result;
    }

    public void addToArray(RuntimeArray array) {
        List<RuntimeScalar> elements = array.elements;
        elements.add(new RuntimeScalar(this));
    }

    public RuntimeScalar addToScalar(RuntimeScalar scalar) {
        return scalar.set(this);
    }

    public Iterator<RuntimeScalar> iterator() {
        return this.scalar().iterator();
    }

    public RuntimeArray setFromList(RuntimeList value) {
        throw new PerlCompilerException("Can't modify constant item in list assignment");
    }

    public RuntimeArray keys() {
        throw new PerlCompilerException("Type of arg 1 to keys must be hash or array");
    }

    public RuntimeArray values() {
        throw new PerlCompilerException("Type of arg 1 to values must be hash or array");
    }

    public RuntimeList each(int ctx) {
        throw new PerlCompilerException("Type of arg 1 to each must be hash or array");
    }

    public RuntimeScalar chop() {
        throw new PerlCompilerException("Can't modify anonymous subroutine");
    }

    public RuntimeScalar chomp() {
        throw new PerlCompilerException("Can't modify anonymous subroutine");
    }

    public RuntimeGlob undefine() {
        throw new PerlCompilerException("Can't modify anonymous subroutine");
    }

    public void dynamicSaveState() {
        throw new PerlCompilerException("Can't modify anonymous subroutine");
    }

    public void dynamicRestoreState() {
        throw new PerlCompilerException("Can't modify anonymous subroutine");
    }

    /**
         * Container for runtime context during eval STRING compilation.
         * Holds both the runtime values and variable names so SpecialBlockParser can
         * match variables to their values.
         */
        public record EvalRuntimeContext(Object[] runtimeValues, String[] capturedEnv, String evalTag) {

        /**
             * Get the runtime value for a variable by name.
             * <p>
             * IMPORTANT: The capturedEnv array includes all variables (including 'this', '@_', 'wantarray'),
             * but runtimeValues array skips the first skipVariables (currently 3).
             * So if @imports is at capturedEnv[5], its value is at runtimeValues[5-3=2].
             *
             * @param varName The variable name (e.g., "@imports", "$scalar")
             * @return The runtime value, or null if not found
             */
            public Object getRuntimeValue(String varName) {
                int skipVariables = 3; // 'this', '@_', 'wantarray'
                for (int i = skipVariables; i < capturedEnv.length; i++) {
                    if (varName.equals(capturedEnv[i])) {
                        int runtimeIndex = i - skipVariables;
                        if (runtimeIndex >= 0 && runtimeIndex < runtimeValues.length) {
                            return runtimeValues[runtimeIndex];
                        }
                    }
                }
                return null;
            }
        }

}
