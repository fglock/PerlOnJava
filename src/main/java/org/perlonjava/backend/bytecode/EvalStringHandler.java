package org.perlonjava.backend.bytecode;

import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;
import org.perlonjava.backend.jvm.EmitterContext;
import org.perlonjava.backend.jvm.JavaClassInfo;
import org.perlonjava.frontend.astnode.Node;
import org.perlonjava.frontend.lexer.Lexer;
import org.perlonjava.frontend.lexer.LexerToken;
import org.perlonjava.frontend.parser.Parser;
import org.perlonjava.frontend.semantic.ScopedSymbolTable;
import org.perlonjava.runtime.operators.WarnDie;
import org.perlonjava.runtime.runtimetypes.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * Handler for eval STRING operations in the interpreter.
 * <p>
 * Implements dynamic code evaluation with proper variable capture and error handling:
 * - Parses Perl string to AST
 * - Compiles AST to interpreter bytecode
 * - Captures variables from outer scope
 * - Executes with eval block semantics (catch errors, set $@)
 */
public class EvalStringHandler {

    private static final boolean EVAL_TRACE =
            System.getenv("JPERL_EVAL_TRACE") != null;

    private static void evalTrace(String msg) {
        if (EVAL_TRACE) {
            System.err.println("[eval-trace] " + msg);
        }
    }

    /**
     * Evaluate a Perl string dynamically.
     * <p>
     * This implements eval STRING semantics:
     * - Parse and compile the string
     * - Execute in the current scope context
     * - Capture variables referenced from outer scope
     * - Return result or undef on error
     * - Set $@ on error
     *
     * @param perlCode    The Perl code string to evaluate
     * @param currentCode The current InterpretedCode (for context)
     * @param registers   Current register array (for variable access)
     * @param sourceName  Source name for error messages
     * @param sourceLine  Source line for error messages
     * @param callContext The calling context (VOID/SCALAR/LIST) for wantarray inside eval
     * @return RuntimeScalar result of evaluation (undef on error)
     */
    public static RuntimeScalar evalString(String perlCode,
                                           InterpretedCode currentCode,
                                           RuntimeBase[] registers,
                                           String sourceName,
                                           int sourceLine,
                                           int callContext) {
        return evalStringList(perlCode, currentCode, registers, sourceName, sourceLine, callContext, null).scalar();
    }

    public static RuntimeScalar evalString(String perlCode,
                                           InterpretedCode currentCode,
                                           RuntimeBase[] registers,
                                           String sourceName,
                                           int sourceLine,
                                           int callContext,
                                           Map<String, Integer> siteRegistry) {
        return evalStringList(perlCode, currentCode, registers, sourceName, sourceLine, callContext, siteRegistry).scalar();
    }

    public static RuntimeList evalStringList(String perlCode,
                                             InterpretedCode currentCode,
                                             RuntimeBase[] registers,
                                             String sourceName,
                                             int sourceLine,
                                             int callContext) {
        return evalStringList(perlCode, currentCode, registers, sourceName, sourceLine, callContext, null);
    }

    public static RuntimeList evalStringList(String perlCode,
                                             InterpretedCode currentCode,
                                             RuntimeBase[] registers,
                                             String sourceName,
                                             int sourceLine,
                                             int callContext,
                                             Map<String, Integer> siteRegistry) {
        return evalStringList(perlCode, currentCode, registers, sourceName, sourceLine,
                callContext, siteRegistry, -1, -1);
    }

    public static RuntimeList evalStringList(String perlCode,
                                             InterpretedCode currentCode,
                                             RuntimeBase[] registers,
                                             String sourceName,
                                             int sourceLine,
                                             int callContext,
                                             Map<String, Integer> siteRegistry,
                                             int siteStrictOptions,
                                             int siteFeatureFlags) {
        try {
            evalTrace("EvalStringHandler enter ctx=" + callContext + " srcName=" + sourceName +
                    " srcLine=" + sourceLine + " codeLen=" + (perlCode != null ? perlCode.length() : -1));
            // Step 1: Clear $@ at start of eval
            GlobalVariable.getGlobalVariable("main::@").set("");

            // Steps 2-4: Parse and compile under the global compile lock.
            // The parser and emitter have shared mutable static state that is not thread-safe.
            InterpretedCode evalCode;
            PerlLanguageProvider.COMPILE_LOCK.lock();
            try {
                // Step 2: Parse the string to AST
                Lexer lexer = new Lexer(perlCode);
                List<LexerToken> tokens = lexer.tokenize();

                // Create minimal EmitterContext for parsing
                // IMPORTANT: Inherit strict/feature/warning flags from parent scope
                // This matches Perl's eval STRING semantics where eval inherits lexical pragmas
                CompilerOptions opts = new CompilerOptions();
                opts.fileName = sourceName + " (eval)";
                ScopedSymbolTable symbolTable = new ScopedSymbolTable();

                // Add standard variables that are always available in eval context.
                // This matches PerlLanguageProvider and evalStringWithInterpreter which
                // ensure @_ is visible in the symbol table. Without this, named subs
                // parsed inside this eval (e.g., eval q{sub foo { shift }}) would get
                // an empty filteredSnapshot and fail strict vars checks for @_.
                symbolTable.enterScope();
                symbolTable.addVariable("this", "", null);
                symbolTable.addVariable("@_", "our", null);
                symbolTable.addVariable("wantarray", "", null);

                // Inherit lexical pragma flags from parent if available
                if (currentCode != null) {
                    int strictOpts = (siteStrictOptions >= 0) ? siteStrictOptions : currentCode.strictOptions;
                    int featFlags = (siteFeatureFlags >= 0) ? siteFeatureFlags : currentCode.featureFlags;
                    symbolTable.strictOptionsStack.pop();
                    symbolTable.strictOptionsStack.push(strictOpts);
                    symbolTable.featureFlagsStack.pop();
                    symbolTable.featureFlagsStack.push(featFlags);
                    symbolTable.warningFlagsStack.pop();
                    symbolTable.warningFlagsStack.push((java.util.BitSet) currentCode.warningFlags.clone());
                }

                // Use runtime package (maintained by PUSH_PACKAGE/SET_PACKAGE opcodes).
                // This correctly reflects the current package scope when eval STRING runs
                // inside dynamic package blocks like: package Foo { eval("__PACKAGE__") }
                // For INIT/END blocks, the runtime package is set by the block's own
                // PUSH_PACKAGE opcode before execution begins.
                String compilePackage = InterpreterState.currentPackage.get().toString();
                symbolTable.setCurrentPackage(compilePackage, false);

                evalTrace("EvalStringHandler compilePackage=" + compilePackage + " fileName=" + opts.fileName);

                ErrorMessageUtil errorUtil = new ErrorMessageUtil(sourceName, tokens);
                EmitterContext ctx = new EmitterContext(
                        new JavaClassInfo(),
                        symbolTable,
                        null, // mv
                        null, // cw
                        callContext,
                        false, // isBoxed
                        errorUtil,
                        opts,
                        null  // unitcheckBlocks
                );

                Parser parser = new Parser(ctx, tokens);
                Node ast = parser.parse();

                // Step 3: Build captured variables and adjusted registry for eval context
                // Collect all parent scope variables (except reserved registers 0-2)
                RuntimeBase[] capturedVars = new RuntimeBase[0];
                Map<String, Integer> adjustedRegistry = null;

                // Use per-eval-site registry if available, otherwise fall back to global registry
                Map<String, Integer> registry = siteRegistry != null ? siteRegistry
                        : (currentCode != null ? currentCode.variableRegistry : null);

                if (registry != null && registers != null) {

                    List<Map.Entry<String, Integer>> sortedVars = new ArrayList<>(
                            registry.entrySet()
                    );
                    sortedVars.sort(Map.Entry.comparingByValue());

                    // Build capturedVars array and adjusted registry
                    // Captured variables will be placed at registers 3+ in eval'd code
                    List<RuntimeBase> capturedList = new ArrayList<>();
                    adjustedRegistry = new HashMap<>();

                    // Always include reserved registers in adjusted registry
                    adjustedRegistry.put("this", 0);
                    adjustedRegistry.put("@_", 1);
                    adjustedRegistry.put("wantarray", 2);

                    int captureIndex = 0;
                    for (Map.Entry<String, Integer> entry : sortedVars) {
                        String varName = entry.getKey();
                        int parentRegIndex = entry.getValue();

                        // Skip reserved registers (they're handled separately in interpreter)
                        if (parentRegIndex < 3) {
                            continue;
                        }

                        if (parentRegIndex < registers.length) {
                            RuntimeBase value = registers[parentRegIndex];

                            // Skip non-Perl values (like Iterator objects from for loops)
                            // Only capture actual Perl variables: Scalar, Array, Hash, Code
                            if (value == null) {
                                // Null is fine - capture it
                            } else if (value instanceof RuntimeScalar scalar) {
                                // Check if the scalar contains an Iterator (used by for loops)
                                if (scalar.value instanceof java.util.Iterator) {
                                    // Skip - this is a for loop iterator, not a user variable
                                    continue;
                                }
                            } else if (!(value instanceof RuntimeArray ||
                                    value instanceof RuntimeHash ||
                                    value instanceof RuntimeCode)) {
                                // Skip this register - it contains an internal object
                                continue;
                            }

                            capturedList.add(value);
                            // Map to new register index starting at 3
                            adjustedRegistry.put(varName, 3 + captureIndex);
                            captureIndex++;
                        }
                    }
                    capturedVars = capturedList.toArray(new RuntimeBase[0]);
                    if (EVAL_TRACE) {
                        evalTrace("EvalStringHandler varRegistry keys=" + registry.keySet());
                        evalTrace("EvalStringHandler adjustedRegistry=" + adjustedRegistry);
                        for (int ci = 0; ci < capturedVars.length; ci++) {
                            evalTrace("EvalStringHandler captured[" + ci + "]=" + (capturedVars[ci] != null ? capturedVars[ci].getClass().getSimpleName() + ":" + capturedVars[ci] : "null"));
                        }
                    }
                }

                // Step 4: Compile AST to interpreter bytecode with adjusted variable registry.
                // The compile-time package is already propagated via ctx.symbolTable.
                BytecodeCompiler compiler = new BytecodeCompiler(
                        sourceName + " (eval)",
                        sourceLine,
                        errorUtil,
                        adjustedRegistry  // Pass adjusted registry for variable capture
                );
                evalCode = compiler.compile(ast, ctx);  // Pass ctx for context propagation

                evalTrace("EvalStringHandler compiled bytecodeLen=" + (evalCode != null ? evalCode.bytecode.length : -1) +
                        " src=" + (evalCode != null ? evalCode.sourceName : "null"));
                if (RuntimeCode.DISASSEMBLE) {
                    System.out.println(Disassemble.disassemble(evalCode));
                }

                // Step 4.5: Store source lines in debugger symbol table if $^P flags are set
                int debugFlags = GlobalVariable.getGlobalVariable(GlobalContext.encodeSpecialVar("P")).getInt();
                if (debugFlags != 0) {
                    String evalFilename = RuntimeCode.getNextEvalFilename();
                    RuntimeCode.storeSourceLines(perlCode, evalFilename, ast, tokens);
                }

                // Step 5: Attach captured variables to eval'd code
                if (capturedVars.length > 0) {
                    evalCode = evalCode.withCapturedVars(capturedVars);
                } else if (currentCode != null && currentCode.capturedVars != null) {
                    // Fallback: share captured variables from parent scope (nested evals)
                    evalCode = evalCode.withCapturedVars(currentCode.capturedVars);
                }
            } finally {
                PerlLanguageProvider.COMPILE_LOCK.unlock();
            }

            // Step 6: Execute the compiled code (outside the lock — execution is thread-safe).
            // IMPORTANT: Scope InterpreterState.currentPackage around eval execution.
            // currentPackage is a runtime-only field used by caller() — it does NOT
            // affect name resolution (which is fully compile-time). However, if the
            // eval contains SET_PACKAGE opcodes (e.g. "package Foo;"), those would
            // permanently mutate the caller's currentPackage without this scoping.
            // We use DynamicVariableManager (same mechanism as PUSH_PACKAGE/POP_LOCAL_LEVEL)
            // to save and restore it automatically.
            int pkgLevel = DynamicVariableManager.getLocalLevel();
            String savedPkg = InterpreterState.currentPackage.get().toString();
            DynamicVariableManager.pushLocalVariable(InterpreterState.currentPackage.get());
            InterpreterState.currentPackage.get().set(savedPkg);
            RuntimeArray args = new RuntimeArray();  // Empty @_
            RuntimeList result;
            RuntimeCode.incrementEvalDepth();
            try {
                result = evalCode.apply(args, callContext);
            } finally {
                RuntimeCode.decrementEvalDepth();
                DynamicVariableManager.popToLocalLevel(pkgLevel);
            }
            evalTrace("EvalStringHandler exec ok ctx=" + callContext +
                    " resultScalar=" + (result != null ? result.scalar().toString() : "null") +
                    " resultBool=" + (result != null && result.scalar() != null && result.scalar().getBoolean()) +
                    " $@=" + GlobalVariable.getGlobalVariable("main::@"));
            return result;
        } catch (Exception e) {
            evalTrace("EvalStringHandler exec exception ctx=" + callContext + " ex=" + e.getClass().getSimpleName() + " msg=" + e.getMessage());
            WarnDie.catchEval(e);
            return new RuntimeList(new RuntimeScalar());
        }
    }

    /**
     * Evaluate a Perl string with explicit variable capture.
     * <p>
     * This version allows passing specific captured variables for the eval context.
     *
     * @param perlCode     The Perl code string to evaluate
     * @param capturedVars Variables to capture from outer scope
     * @param sourceName   Source name for error messages
     * @param sourceLine   Source line for error messages
     * @return RuntimeScalar result of evaluation (undef on error)
     */
    public static RuntimeScalar evalString(String perlCode,
                                           RuntimeBase[] capturedVars,
                                           String sourceName,
                                           int sourceLine) {
        try {
            // Clear $@ at start
            GlobalVariable.getGlobalVariable("main::@").set("");

            // Parse and compile under the global compile lock
            InterpretedCode evalCode;
            PerlLanguageProvider.COMPILE_LOCK.lock();
            try {
                // Parse the string
                Lexer lexer = new Lexer(perlCode);
                List<LexerToken> tokens = lexer.tokenize();

                CompilerOptions opts = new CompilerOptions();
                opts.fileName = sourceName + " (eval)";
                ScopedSymbolTable symbolTable = new ScopedSymbolTable();

                // Add standard variables that are always available in eval context.
                // Without this, subs parsed inside the eval would fail strict vars
                // checks for @_ (same setup as the evalStringList overload).
                symbolTable.enterScope();
                symbolTable.addVariable("this", "", null);
                symbolTable.addVariable("@_", "our", null);
                symbolTable.addVariable("wantarray", "", null);

                ErrorMessageUtil errorUtil = new ErrorMessageUtil(sourceName, tokens);
                EmitterContext ctx = new EmitterContext(
                        new JavaClassInfo(),
                        symbolTable,
                        null, null,
                        RuntimeContextType.SCALAR,
                        false,
                        errorUtil,
                        opts,
                        null
                );

                Parser parser = new Parser(ctx, tokens);
                Node ast = parser.parse();

                // Compile to bytecode.
                // IMPORTANT: Do NOT call compiler.setCompilePackage() here — same reason as the
                // first evalString overload above: it corrupts die/warn location baking.
                BytecodeCompiler compiler = new BytecodeCompiler(
                        sourceName + " (eval)",
                        sourceLine,
                        errorUtil
                );
                evalCode = compiler.compile(ast, ctx);  // Pass ctx for context propagation
                if (RuntimeCode.DISASSEMBLE) {
                    System.out.println(Disassemble.disassemble(evalCode));
                }

                // Store source lines in debugger symbol table if $^P flags are set
                int debugFlags = GlobalVariable.getGlobalVariable(GlobalContext.encodeSpecialVar("P")).getInt();
                if (debugFlags != 0) {
                    String evalFilename = RuntimeCode.getNextEvalFilename();
                    RuntimeCode.storeSourceLines(perlCode, evalFilename, ast, tokens);
                }

                // Attach captured variables
                evalCode = evalCode.withCapturedVars(capturedVars);
            } finally {
                PerlLanguageProvider.COMPILE_LOCK.unlock();
            }

            // Execute outside the lock — execution is thread-safe
            // Scope currentPackage around eval — see Step 6 comment in evalStringHelper above.
            int pkgLevel = DynamicVariableManager.getLocalLevel();
            String savedPkg = InterpreterState.currentPackage.get().toString();
            DynamicVariableManager.pushLocalVariable(InterpreterState.currentPackage.get());
            InterpreterState.currentPackage.get().set(savedPkg);
            RuntimeArray args = new RuntimeArray();
            RuntimeList result;
            RuntimeCode.incrementEvalDepth();
            try {
                result = evalCode.apply(args, RuntimeContextType.SCALAR);
            } finally {
                RuntimeCode.decrementEvalDepth();
                DynamicVariableManager.popToLocalLevel(pkgLevel);
            }

            return result.scalar();

        } catch (Exception e) {
            WarnDie.catchEval(e);
            return RuntimeScalarCache.scalarUndef;
        }
    }

    /**
     * Detect which variables from outer scope are referenced in eval string.
     * <p>
     * This is used for proper variable capture (similar to closure analysis).
     * TODO: Implement proper lexical variable detection from AST
     *
     * @param ast The parsed AST
     * @return Map of variable names to their types
     */
    private static Map<String, String> detectCapturedVariables(Node ast) {
        // TODO: Use VariableCollectorVisitor or similar to detect:
        // - Lexical variables referenced from outer scope
        // - Package variables accessed
        // For now, return empty map
        return new HashMap<>();
    }
}
