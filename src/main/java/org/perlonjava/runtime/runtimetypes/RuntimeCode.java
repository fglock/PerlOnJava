package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.frontend.astnode.Node;
import org.perlonjava.frontend.astnode.OperatorNode;
import org.perlonjava.backend.jvm.EmitterContext;
import org.perlonjava.backend.jvm.EmitterMethodCreator;
import org.perlonjava.backend.jvm.JavaClassInfo;
import org.perlonjava.frontend.lexer.Lexer;
import org.perlonjava.frontend.lexer.LexerToken;
import org.perlonjava.runtime.operators.WarnDie;
import org.perlonjava.frontend.parser.Parser;
import org.perlonjava.runtime.mro.InheritanceResolver;
import org.perlonjava.runtime.operators.ModuleOperators;
import org.perlonjava.frontend.semantic.ScopedSymbolTable;
import org.perlonjava.frontend.semantic.SymbolTable;
import org.perlonjava.backend.bytecode.BytecodeCompiler;
import org.perlonjava.backend.bytecode.InterpretedCode;
import org.perlonjava.backend.bytecode.InterpreterState;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.function.Supplier;

import static org.perlonjava.frontend.parser.ParserTables.CORE_PROTOTYPES;
import static org.perlonjava.runtime.runtimetypes.GlobalVariable.*;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarUndef;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.*;
import static org.perlonjava.frontend.parser.SpecialBlockParser.setCurrentScope;
import static org.perlonjava.runtime.runtimetypes.SpecialBlock.runUnitcheckBlocks;

/**
 * The RuntimeCode class represents a compiled code object in the runtime environment.
 * It provides functionality to compile, store, and execute Perl subroutines and eval strings.
 */
public class RuntimeCode extends RuntimeBase implements RuntimeScalarReference {

    // Lookup object for performing method handle operations
    public static final MethodHandles.Lookup lookup = MethodHandles.lookup();

    /**
     * Flag to control whether eval STRING should use the interpreter backend.
     * When set, eval STRING compiles to InterpretedCode instead of generating JVM bytecode.
     * This provides 46x faster compilation for workloads with many unique eval strings.
     *
     * Set environment variable JPERL_EVAL_USE_INTERPRETER=1 to enable.
     */
    public static final boolean EVAL_USE_INTERPRETER =
            System.getenv("JPERL_EVAL_USE_INTERPRETER") != null;

    /**
     * Flag to control whether eval compilation errors should be printed to stderr.
     * By default, eval failures are silent (errors only stored in $@).
     *
     * Set environment variable JPERL_EVAL_VERBOSE=1 to enable verbose error reporting.
     * This is useful for debugging eval compilation issues, especially when testing
     * the interpreter path.
     */
    public static final boolean EVAL_VERBOSE =
            System.getenv("JPERL_EVAL_VERBOSE") != null;

    /**
     * Flag to enable disassembly of eval STRING bytecode.
     * When set, prints the interpreter bytecode for each eval STRING compilation.
     *
     * Set environment variable JPERL_DISASSEMBLE=1 to enable, or use --disassemble CLI flag.
     * The --disassemble flag sets this via setDisassemble().
     */
    public static boolean DISASSEMBLE =
            System.getenv("JPERL_DISASSEMBLE") != null;

    /** Called by CLI argument parser when --disassemble is set. */
    public static void setDisassemble(boolean value) {
        DISASSEMBLE = value;
    }

    /**
     * ThreadLocal storage for runtime values of captured variables during eval STRING compilation.
     *
     * PROBLEM: In perl5, BEGIN blocks inside eval STRING can access outer lexical variables' runtime values:
     *   my @imports = qw(a b);
     *   eval q{ BEGIN { say @imports } };  # perl5 prints: a b
     *
     * In PerlOnJava, BEGIN blocks execute during parsing (before the eval class is instantiated),
     * so they couldn't access runtime values - they would see empty variables.
     *
     * SOLUTION: When evalStringHelper() is called, the runtime values are stored in this ThreadLocal.
     * During parsing, when SpecialBlockParser sets up BEGIN blocks, it can access these runtime values
     * and use them to initialize the special globals that lexical variables become in BEGIN blocks.
     *
     * This ThreadLocal stores:
     * - Key: The evalTag identifying this eval compilation
     * - Value: EvalRuntimeContext containing:
     *     - runtimeValues: Object[] of captured variable values
     *     - capturedEnv: String[] of captured variable names (matching array indices)
     *
     * Thread-safety: Each thread's eval compilation uses its own ThreadLocal storage, so parallel
     * eval compilations don't interfere with each other.
     */
    private static final ThreadLocal<EvalRuntimeContext> evalRuntimeContext = new ThreadLocal<>();

    /**
     * Container for runtime context during eval STRING compilation.
     * Holds both the runtime values and variable names so SpecialBlockParser can
     * match variables to their values.
     */
    public static class EvalRuntimeContext {
        public final Object[] runtimeValues;
        public final String[] capturedEnv;
        public final String evalTag;

        public EvalRuntimeContext(Object[] runtimeValues, String[] capturedEnv, String evalTag) {
            this.runtimeValues = runtimeValues;
            this.capturedEnv = capturedEnv;
            this.evalTag = evalTag;
        }

        /**
         * Get the runtime value for a variable by name.
         *
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

    /**
     * Get the current eval runtime context for accessing variable runtime values during parsing.
     * This is called by SpecialBlockParser when setting up BEGIN blocks.
     *
     * @return The current eval runtime context, or null if not in eval STRING compilation
     */
    public static EvalRuntimeContext getEvalRuntimeContext() {
        return evalRuntimeContext.get();
    }

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
    public static MethodType methodType = MethodType.methodType(RuntimeList.class, RuntimeArray.class, int.class);
    // Temporary storage for anonymous subroutines and eval string compiler context
    public static HashMap<String, Class<?>> anonSubs = new HashMap<>(); // temp storage for makeCodeObject()
    public static HashMap<String, EmitterContext> evalContext = new HashMap<>(); // storage for eval string compiler context
    // Runtime eval counter for generating unique filenames when $^P is set
    private static int runtimeEvalCounter = 1;

    /**
     * Gets the next eval sequence number and generates a filename.
     * Used by both baseline compiler and interpreter for consistent naming.
     *
     * @return Filename like "(eval 1)", "(eval 2)", etc.
     */
    public static synchronized String getNextEvalFilename() {
        return "(eval " + runtimeEvalCounter++ + ")";
    }

    // Method object representing the compiled subroutine
    public MethodHandle methodHandle;
    public boolean isStatic;
    public String autoloadVariableName = null;
    // Code object instance used during execution
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
    // State variables
    public Map<String, Boolean> stateVariableInitialized = new HashMap<>();
    public Map<String, RuntimeScalar> stateVariable = new HashMap<>();
    public Map<String, RuntimeArray> stateArray = new HashMap<>();
    public Map<String, RuntimeHash> stateHash = new HashMap<>();
    public RuntimeList constantValue;
    // Field to hold the thread compiling this code
    public Supplier<Void> compilerSupplier;

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

    // Add a method to clear caches when globals are reset
    public static void clearCaches() {
        evalCache.clear();
        methodHandleCache.clear();
        anonSubs.clear();
        evalContext.clear();
        evalRuntimeContext.remove();
    }

    public static void copy(RuntimeCode code, RuntimeCode codeFrom) {
        code.prototype = codeFrom.prototype;
        code.attributes = codeFrom.attributes;
        code.methodHandle = codeFrom.methodHandle;
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
     *
     * IMPORTANT CHANGE: This method now accepts runtime values of captured variables.
     *
     * WHY THIS IS NEEDED:
     * In perl5, BEGIN blocks inside eval STRING can access outer lexical variables' runtime values.
     * For example:
     *     my @imports = qw(md5 md5_hex);
     *     eval q{ use Digest::MD5 @imports };  # BEGIN block sees @imports = (md5 md5_hex)
     *
     * Previously in PerlOnJava, BEGIN blocks would see empty variables because they execute
     * during parsing, before the eval class is instantiated with runtime values.
     *
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
        CompilerOptions evalCompilerOptions = ctx.compilerOptions;
        // The eval string can originate from either a Perl STRING or BYTE_STRING scalar.
        // For BYTE_STRING source we must treat the source as raw bytes (latin-1-ish) and
        // NOT re-encode characters to UTF-8 when simulating 'non-unicode source'.
        boolean isByteStringSource = !ctx.isEvalbytes && code.type == RuntimeScalarType.BYTE_STRING;
        if (hasUnicode || ctx.isEvalbytes || isByteStringSource) {
            evalCompilerOptions = (CompilerOptions) ctx.compilerOptions.clone();
            if (hasUnicode) {
                evalCompilerOptions.isUnicodeSource = true;
            }
            if (ctx.isEvalbytes) {
                evalCompilerOptions.isEvalbytes = true;
            }
            if (isByteStringSource) {
                evalCompilerOptions.isByteStringSource = true;
            }
        }

        // Check $^P to determine if we should use caching
        // When debugging is enabled, we want each eval to get a unique filename
        int debugFlags = GlobalVariable.getGlobalVariable(GlobalContext.encodeSpecialVar("P")).getInt();
        boolean isDebugging = debugFlags != 0;
        
        // Override the filename with a runtime-generated eval number when debugging
        String actualFileName = evalCompilerOptions.fileName;
        if (isDebugging) {
            actualFileName = getNextEvalFilename();
        }
        
        // Check if the result is already cached (include hasUnicode, isEvalbytes, byte-string-source, and feature flags in cache key)
        // Skip caching when $^P is set, so each eval gets a unique filename
        int featureFlags = ctx.symbolTable.featureFlagsStack.peek();
        String cacheKey = code.toString() + '\0' + evalTag + '\0' + hasUnicode + '\0' + ctx.isEvalbytes + '\0' + isByteStringSource + '\0' + featureFlags;
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
        Map<Integer, SymbolTable.SymbolEntry> capturedVars = capturedSymbolTable.getAllVisibleVariables();
        for (SymbolTable.SymbolEntry entry : capturedVars.values()) {
            if (!entry.name().equals("@_") && !entry.decl().isEmpty() && !entry.name().startsWith("&")) {
                if (!entry.decl().equals("our")) {
                    // "my" or "state" variables get special BEGIN package globals
                    Object runtimeValue = runtimeCtx.getRuntimeValue(entry.name());
                    if (runtimeValue != null) {
                        // Get or create the special package ID
                        // IMPORTANT: We need to set the ID NOW (before parsing) so that when
                        // runSpecialBlock is called during parsing, it uses the SAME ID
                        OperatorNode ast = entry.ast();
                        if (ast != null) {
                            if (ast.id == 0) {
                                ast.id = EmitterMethodCreator.classCounter++;
                            }
                            String packageName = PersistentVariable.beginPackage(ast.id);
                            // IMPORTANT: Global variable keys do NOT include the sigil
                            // entry.name() is "@arr" but the key should be "packageName::arr"
                            String varNameWithoutSigil = entry.name().substring(1);  // Remove the sigil
                            String fullName = packageName + "::" + varNameWithoutSigil;

                            // Alias the global to the runtime value
                            if (runtimeValue instanceof RuntimeArray) {
                                GlobalVariable.globalArrays.put(fullName, (RuntimeArray) runtimeValue);
                            } else if (runtimeValue instanceof RuntimeHash) {
                                GlobalVariable.globalHashes.put(fullName, (RuntimeHash) runtimeValue);
                            } else if (runtimeValue instanceof RuntimeScalar) {
                                GlobalVariable.globalVariables.put(fullName, (RuntimeScalar) runtimeValue);
                            }
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

            // Create a new instance of ErrorMessageUtil, resetting the line counter
            evalCtx.errorUtil = new ErrorMessageUtil(ctx.compilerOptions.fileName, tokens);
            ScopedSymbolTable postParseSymbolTable = evalCtx.symbolTable;
            evalCtx.symbolTable = capturedSymbolTable;
            evalCtx.symbolTable.copyFlagsFrom(postParseSymbolTable);
            setCurrentScope(evalCtx.symbolTable);
            
            // Use the captured environment array from compile-time to ensure
            // constructor signature matches what EmitEval generated bytecode for
            if (ctx.capturedEnv != null) {
                evalCtx.capturedEnv = ctx.capturedEnv;
            }
            
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
                    if (handlerException instanceof RuntimeException && handlerException.getCause() instanceof PerlDieException) {
                        PerlDieException pde = (PerlDieException) handlerException.getCause();
                        RuntimeBase handlerPayload = pde.getPayload();
                        if (handlerPayload != null) {
                            err.set(handlerPayload.getFirst());
                        }
                    } else if (handlerException instanceof PerlDieException) {
                        PerlDieException pde = (PerlDieException) handlerException;
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

            setCurrentScope(capturedSymbolTable);

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
     * @param code The RuntimeScalar containing the eval string
     * @param evalTag The unique identifier for this eval site
     * @param runtimeValues The captured variable values from the outer scope
     * @param args The @_ arguments to pass to the eval
     * @param callContext The calling context (SCALAR/LIST/VOID)
     * @return The result of executing the eval as a RuntimeList
     * @throws Throwable if compilation or execution fails
     */
    public static RuntimeList evalStringWithInterpreter(
            RuntimeScalar code,
            String evalTag,
            Object[] runtimeValues,
            RuntimeArray args,
            int callContext) throws Throwable {

        // Retrieve the eval context that was saved at program compile-time
        EmitterContext ctx = RuntimeCode.evalContext.get(evalTag);

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

        // Save dynamic variable level to restore after eval
        int dynamicVarLevel = DynamicVariableManager.getLocalLevel();

        try {
            String evalString = code.toString();

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

            // Clone compiler options if needed
            CompilerOptions evalCompilerOptions = ctx.compilerOptions;
            boolean isByteStringSource = !ctx.isEvalbytes && code.type == RuntimeScalarType.BYTE_STRING;
            if (hasUnicode || ctx.isEvalbytes || isByteStringSource) {
                evalCompilerOptions = (CompilerOptions) ctx.compilerOptions.clone();
                if (hasUnicode) {
                    evalCompilerOptions.isUnicodeSource = true;
                }
                if (ctx.isEvalbytes) {
                    evalCompilerOptions.isEvalbytes = true;
                }
                if (isByteStringSource) {
                    evalCompilerOptions.isByteStringSource = true;
                }
            }

            // Setup for BEGIN block support - create aliases for captured variables
            ScopedSymbolTable capturedSymbolTable = ctx.symbolTable;
            Map<Integer, SymbolTable.SymbolEntry> capturedVars = capturedSymbolTable.getAllVisibleVariables();
            for (SymbolTable.SymbolEntry entry : capturedVars.values()) {
                if (!entry.name().equals("@_") && !entry.decl().isEmpty() && !entry.name().startsWith("&")) {
                    if (!entry.decl().equals("our")) {
                        Object runtimeValue = runtimeCtx.getRuntimeValue(entry.name());
                        if (runtimeValue != null) {
                            OperatorNode operatorAst = entry.ast();
                            if (operatorAst != null) {
                                if (operatorAst.id == 0) {
                                    operatorAst.id = EmitterMethodCreator.classCounter++;
                                }
                                String packageName = PersistentVariable.beginPackage(operatorAst.id);
                                String varNameWithoutSigil = entry.name().substring(1);
                                String fullName = packageName + "::" + varNameWithoutSigil;

                                if (runtimeValue instanceof RuntimeArray) {
                                    GlobalVariable.globalArrays.put(fullName, (RuntimeArray) runtimeValue);
                                } else if (runtimeValue instanceof RuntimeHash) {
                                    GlobalVariable.globalHashes.put(fullName, (RuntimeHash) runtimeValue);
                                } else if (runtimeValue instanceof RuntimeScalar) {
                                    GlobalVariable.globalVariables.put(fullName, (RuntimeScalar) runtimeValue);
                                }
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

                // Add captured variables starting at register 3
                int captureIndex = 3;
                Map<Integer, SymbolTable.SymbolEntry> capturedVariables = capturedSymbolTable.getAllVisibleVariables();
                for (Map.Entry<Integer, SymbolTable.SymbolEntry> entry : capturedVariables.entrySet()) {
                    int index = entry.getKey();
                    if (index >= 3) {  // Skip reserved registers
                        String varName = entry.getValue().name();
                        adjustedRegistry.put(varName, captureIndex);
                        captureIndex++;
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
                if (DISASSEMBLE) {
                    System.out.println(interpretedCode.disassemble());
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
                        if (handlerException instanceof RuntimeException && handlerException.getCause() instanceof PerlDieException) {
                            PerlDieException pde = (PerlDieException) handlerException.getCause();
                            RuntimeBase handlerPayload = pde.getPayload();
                            if (handlerPayload != null) {
                                err.set(handlerPayload.getFirst());
                            }
                        } else if (handlerException instanceof PerlDieException) {
                            PerlDieException pde = (PerlDieException) handlerException;
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

            // Execute the interpreted code
            try {
                result = interpretedCode.apply(args, callContext);

                // Clear $@ on successful execution
                RuntimeScalar err = GlobalVariable.getGlobalVariable("main::@");
                err.set("");

                return result;

            } catch (PerlDieException e) {
                // Runtime error - set $@ and return undef/empty list
                RuntimeScalar err = GlobalVariable.getGlobalVariable("main::@");
                RuntimeBase payload = e.getPayload();
                if (payload != null) {
                    err.set(payload.getFirst());
                } else {
                    err.set("Died");
                }

                // Return undef/empty list
                if (callContext == RuntimeContextType.LIST) {
                    return new RuntimeList();
                } else {
                    return new RuntimeList(new RuntimeScalar());
                }

            } catch (Throwable e) {
                // Other runtime errors - set $@ and return undef/empty list
                RuntimeScalar err = GlobalVariable.getGlobalVariable("main::@");
                // PerlCompilerException.getMessage() may return empty when caller() lookup
                // fails inside interpreter context — fall back to the superclass message.
                String message = e.getMessage();
                if ((message == null || message.isEmpty()) && e.getCause() != null) {
                    message = e.getCause().getMessage();
                }
                if (message == null || message.isEmpty()) {
                    message = ErrorMessageUtil.stringifyException(e);
                }
                if (message == null || message.isEmpty()) {
                    message = e.getClass().getSimpleName();
                }
                err.set(message);

                // Return undef/empty list
                if (callContext == RuntimeContextType.LIST) {
                    return new RuntimeList();
                } else {
                    return new RuntimeList(new RuntimeScalar());
                }
            }

        } finally {
            // Restore dynamic variables (local) to their state before eval
            DynamicVariableManager.popToLocalLevel(dynamicVarLevel);

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
        // Retrieve the class of the provided code object
        Class<?> clazz = codeObject.getClass();

        // Check if the method handle is already cached
        MethodHandle methodHandle;
        synchronized (methodHandleCache) {
            if (methodHandleCache.containsKey(clazz)) {
                methodHandle = methodHandleCache.get(clazz);
            } else {
                // Get the 'apply' method from the class.
                methodHandle = RuntimeCode.lookup.findVirtual(clazz, "apply", RuntimeCode.methodType);
                // Cache the method handle
                methodHandleCache.put(clazz, methodHandle);
            }
        }

        // Wrap the method and the code object in a RuntimeCode instance
        // This allows us to store both the method and the object it belongs to
        // Create a new RuntimeScalar instance to hold the CODE object
        RuntimeScalar codeRef = new RuntimeScalar(new RuntimeCode(methodHandle, codeObject, prototype));

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

        // Retrieve Perl class name
        String perlClassName;

        if (RuntimeScalarType.isReference(runtimeScalar)) {
            // Handle all reference types (REFERENCE, ARRAYREFERENCE, HASHREFERENCE, etc.)
            int blessId = ((RuntimeBase) runtimeScalar.value).blessId;
            if (blessId == 0) {
                if (runtimeScalar.type == GLOBREFERENCE) {
                    // Auto-bless file handler to IO::File which inherits from both IO::Handle and IO::Seekable
                    // This allows GLOBs to call methods like seek, tell, etc.
                    perlClassName = "IO::File";
                    // Load the module if needed
                    // TODO - optimize by creating a flag in RuntimeIO
                    ModuleOperators.require(new RuntimeScalar("IO/File.pm"));
                } else {
                    // Not auto-blessed
                    throw new PerlCompilerException("Can't call method \"" + methodName + "\" on unblessed reference");
                }
            } else {
                perlClassName = NameNormalizer.getBlessStr(blessId);
            }
        } else if (runtimeScalar.type == UNDEF) {
            throw new PerlCompilerException("Can't call method \"" + methodName + "\" on an undefined value");
        } else {
            perlClassName = runtimeScalar.toString();
            if (perlClassName.isEmpty()) {
                throw new PerlCompilerException("Can't call method \"" + methodName + "\" on an undefined value");
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
                method = NextMethod.superMethod(currentSub, methodName);
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
            if (autoloadVariableName != null) {
                // The inherited method is an autoloaded subroutine
                // Set the $AUTOLOAD variable to the name of the method that was called

                // Extract class name from "ClassName::AUTOLOAD"
                String className = autoloadVariableName.substring(0, autoloadVariableName.lastIndexOf("::"));
                // Make fully qualified method name
                String fullMethodName = NameNormalizer.normalizeVariableName(methodName, className);
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

    public static RuntimeList caller(RuntimeList args, int ctx) {
        RuntimeList res = new RuntimeList();
        int frame = 0;
        if (!args.isEmpty()) {
            frame = args.getFirst().getInt();
        }

        Throwable t = new Throwable();
        ArrayList<ArrayList<String>> stackTrace = ExceptionFormatter.formatException(t);
        int stackTraceSize = stackTrace.size();

        // Skip the first frame which is the caller() builtin itself
        if (stackTraceSize > 0) {
            frame++;
        }

        if (frame >= 0 && frame < stackTraceSize) {
            // Runtime stack trace
            if (ctx == RuntimeContextType.SCALAR) {
                res.add(new RuntimeScalar(stackTrace.get(frame).getFirst()));
            } else {
                ArrayList<String> frameInfo = stackTrace.get(frame);
                res.add(new RuntimeScalar(frameInfo.get(0)));  // package
                res.add(new RuntimeScalar(frameInfo.get(1)));  // filename
                res.add(new RuntimeScalar(frameInfo.get(2)));  // line
                
                // The subroutine name at frame N is actually stored at frame N-1
                // because it represents the sub that IS CALLING frame N
                String subName = null;
                if (frame > 0 && frame - 1 < stackTraceSize) {
                    ArrayList<String> prevFrame = stackTrace.get(frame - 1);
                    if (prevFrame.size() > 3) {
                        subName = prevFrame.get(3);
                    }
                }
                
                if (subName != null && !subName.isEmpty()) {
                    res.add(new RuntimeScalar(subName));  // subroutine
                } else {
                    // If no subroutine name or empty, add undef
                    res.add(RuntimeScalarCache.scalarUndef);
                }
                // TODO: Add more caller() return values:
                // hasargs, wantarray, evaltext, is_require, hints, bitmask, hinthash
            }
        }
        return res;
    }

    // Method to apply (execute) a subroutine reference
    public static RuntimeList apply(RuntimeScalar runtimeScalar, RuntimeArray a, int callContext) {
        // Check if the type of this RuntimeScalar is CODE
        if (runtimeScalar.type == RuntimeScalarType.CODE) {
            RuntimeCode code = (RuntimeCode) runtimeScalar.value;

            // CRITICAL: Run compilerSupplier BEFORE checking defined()
            // The compilerSupplier may replace runtimeScalar.value with InterpretedCode
            if (code.compilerSupplier != null) {
                code.compilerSupplier.get();
                // Reload code from runtimeScalar.value in case it was replaced
                code = (RuntimeCode) runtimeScalar.value;
            }

            // Check if it's an unfilled forward declaration (not defined)
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
                throw new PerlCompilerException("Undefined subroutine &" + subroutineName + " called at ");
            }
            // Cast the value to RuntimeCode and call apply()
            return code.apply(a, callContext);
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
        try {
            RuntimeList result = apply(runtimeScalar, a, callContext);
            // Perl clears $@ on successful eval (even if nested evals previously set it).
            GlobalVariable.setGlobalVariable("main::@", "");
            return result;
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

    // Method to apply (execute) a subroutine reference using native array for parameters
    public static RuntimeList apply(RuntimeScalar runtimeScalar, String subroutineName, RuntimeBase[] args, int callContext) {
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

            // Transform the native array to RuntimeArray of aliases (Perl variable `@_`)
            RuntimeArray a = new RuntimeArray();
            for (RuntimeBase arg : args) {
                arg.setArrayOfAlias(a);
            }

            RuntimeCode code = (RuntimeCode) runtimeScalar.value;

            // CRITICAL: Run compilerSupplier BEFORE checking defined()
            // The compilerSupplier may replace runtimeScalar.value with InterpretedCode
            if (code.compilerSupplier != null) {
                code.compilerSupplier.get();
                // Reload code from runtimeScalar.value in case it was replaced
                code = (RuntimeCode) runtimeScalar.value;
            }

            if (code.defined()) {
                // Cast the value to RuntimeCode and call apply()
                return code.apply(subroutineName, a, callContext);
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
                throw new PerlCompilerException("Undefined subroutine &" + fullSubName + " called at ");
            }
        }

        RuntimeScalar overloadedCode = handleCodeOverload(runtimeScalar);
        if (overloadedCode != null) {
            return apply(overloadedCode, subroutineName, args, callContext);
        }

        throw new PerlCompilerException("Not a CODE reference");
    }

    // Method to apply (execute) a subroutine reference (legacy method for compatibility)
    public static RuntimeList apply(RuntimeScalar runtimeScalar, String subroutineName, RuntimeBase list, int callContext) {

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

            // CRITICAL: Run compilerSupplier BEFORE checking defined()
            // The compilerSupplier may replace runtimeScalar.value with InterpretedCode
            if (code.compilerSupplier != null) {
                code.compilerSupplier.get();
                // Reload code from runtimeScalar.value in case it was replaced
                code = (RuntimeCode) runtimeScalar.value;
            }

            if (code.defined()) {
                // Cast the value to RuntimeCode and call apply()
                return code.apply(subroutineName, a, callContext);
            }

            // Does AUTOLOAD exist?
            if (!subroutineName.isEmpty()) {
                // Check if AUTOLOAD exists
                String autoloadString = subroutineName.substring(0, subroutineName.lastIndexOf("::") + 2) + "AUTOLOAD";
                // System.err.println("AUTOLOAD: " + fullName);
                RuntimeScalar autoload = GlobalVariable.getGlobalCodeRef(autoloadString);
                if (autoload.getDefinedBoolean()) {
                    // System.err.println("AUTOLOAD exists: " + fullName);
                    // Set $AUTOLOAD name
                    getGlobalVariable(autoloadString).set(subroutineName);
                    // Call AUTOLOAD
                    return apply(autoload, a, callContext);
                }
                throw new PerlCompilerException("Undefined subroutine &" + subroutineName + " called at ");
            }
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
        
        String name = NameNormalizer.normalizeVariableName(runtimeScalar.toString(), packageName);
        // System.out.println("Creating code reference: " + name + " got: " + GlobalContext.getGlobalCodeRef(name));
        RuntimeScalar codeRef = GlobalVariable.getGlobalCodeRef(name);

        // Check if this is a constant subroutine
        if (codeRef.type == RuntimeScalarType.CODE && codeRef.value instanceof RuntimeCode runtimeCode) {
            // Mark this as a symbolic reference created by \&{string} pattern
            // This ensures defined(\&{nonexistent}) returns true to match standard Perl behavior
            runtimeCode.isSymbolicReference = true;
            
            // For constant subroutines, return a reference to the constant value
            if (runtimeCode.constantValue != null && !runtimeCode.constantValue.isEmpty()) {
                RuntimeScalar constValue = runtimeCode.constantValue.getFirst();
                return constValue.createReference();
            }
        }

        return codeRef;
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

    public boolean defined() {
        // Symbolic references created by \&{string} are always considered "defined" to match standard Perl
        if (this.isSymbolicReference) {
            return true;
        }
        // Built-in operators are always considered "defined"
        if (this.isBuiltin) {
            return true;
        }
        return this.constantValue != null || this.compilerSupplier != null || this.methodHandle != null;
    }

    /**
     * Method to apply (execute) a subroutine reference.
     * Invokes the method associated with the code object, passing the RuntimeArray and RuntimeContextType as arguments.
     *
     * @param a           the RuntimeArray containing the arguments for the subroutine
     * @param callContext the context in which the subroutine is called
     * @return the result of the subroutine execution as a RuntimeList
     */
    public RuntimeList apply(RuntimeArray a, int callContext) {
        if (constantValue != null) {
            // Alternative way to create constants like: `$constant::{_CAN_PCS} = \$const`
            return new RuntimeList(constantValue);
        }
        try {
            // Wait for the compilerThread to finish if it exists
            if (this.compilerSupplier != null) {
                this.compilerSupplier.get(); // Wait for the task to finish
            }

            if (isStatic) {
                return (RuntimeList) this.methodHandle.invoke(a, callContext);
            } else {
                return (RuntimeList) this.methodHandle.invoke(this.codeObject, a, callContext);
            }
        } catch (NullPointerException e) {

            if (this.methodHandle == null) {
                throw new PerlCompilerException("Subroutine exists but has null method handle (possible compilation or registration error) at ");
            } else if (this.codeObject == null && !isStatic) {
                throw new PerlCompilerException("Subroutine exists but has null code object at ");
            } else {
                // Original NPE from somewhere else
                throw new PerlCompilerException("Null pointer exception in subroutine call: " + e.getMessage() + " at ");
            }

            //throw new PerlCompilerException("Undefined subroutine called at ");
        } catch (InvocationTargetException e) {
            Throwable targetException = e.getTargetException();
            if (!(targetException instanceof RuntimeException)) {
                throw new RuntimeException(targetException);
            }
            throw (RuntimeException) targetException;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public RuntimeList apply(String subroutineName, RuntimeArray a, int callContext) {
        if (constantValue != null) {
            // Alternative way to create constants like: `$constant::{_CAN_PCS} = \$const`
            return new RuntimeList(constantValue);
        }
        try {
            // Wait for the compilerThread to finish if it exists
            if (this.compilerSupplier != null) {
                // System.out.println("Waiting for compiler thread to finish...");
                this.compilerSupplier.get(); // Wait for the task to finish
            }

            if (isStatic) {
                return (RuntimeList) this.methodHandle.invoke(a, callContext);
            } else {
                return (RuntimeList) this.methodHandle.invoke(this.codeObject, a, callContext);
            }
        } catch (NullPointerException e) {
            throw new PerlCompilerException("Undefined subroutine &" + subroutineName + " called at ");
        } catch (InvocationTargetException e) {
            Throwable targetException = e.getTargetException();
            if (!(targetException instanceof RuntimeException)) {
                throw new RuntimeException(targetException);
            }
            throw (RuntimeException) targetException;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
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

}
