package org.perlonjava.backend.bytecode;

import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.backend.jvm.EmitterContext;
import org.perlonjava.backend.jvm.EmitterMethodCreator;
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

            // Step 2: Parse the string to AST
            Lexer lexer = new Lexer(perlCode);
            List<LexerToken> tokens = lexer.tokenize();

            // Create minimal EmitterContext for parsing
            // IMPORTANT: Inherit strict/feature/warning flags from parent scope
            // This matches Perl's eval STRING semantics where eval inherits lexical pragmas
            // Generate a unique eval filename so ByteCodeSourceMapper entries from
            // different evals don't collide (each eval's token indices start from 0,
            // so sharing a single filename would mix package-at-location data).
            String evalFileName = RuntimeCode.getNextEvalFilename();

            CompilerOptions opts = new CompilerOptions();
            opts.fileName = evalFileName;
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

            // Seed the symbol table with the caller's visible lexical variables so
            // that parse-time name resolution inside the eval body can find them.
            //
            // Without this, named subs inside the eval that reference outer `my`
            // variables would fail with "Global symbol requires explicit package
            // name" (parse-time strict-vars check in Variable.java:285), and if
            // they got past parse, their JVM-compiled closure would capture the
            // wrong thing because `SubroutineParser.handleNamedSub` relies on
            // `parser.ctx.symbolTable` to decide what to capture.
            //
            // We use the same "BEGIN package alias" trick that the JVM backend
            // uses in `RuntimeCode.evalStringHelper` (search for
            // `PersistentVariable.beginPackage`): each captured `my` variable is
            // aliased into a fresh package global under
            // `PerlOnJava::_BEGIN_<id>::<name>`, and seeded into the parser's
            // symbol table as `our` with that package. When a named sub inside
            // the eval is later compiled by `SubroutineParser.handleNamedSub`,
            // it goes through the `decl == "our"` branch (line 1153), resolves
            // the global by the aliased name, and picks up the real runtime
            // value shared with the outer scope.
            //
            // Parsing flow is unaffected for direct references: the interpreter
            // (BytecodeCompiler) uses its OWN parentRegistry-populated symbol
            // table for variable resolution, so direct `$y` in the eval body
            // still resolves to the captured-register path.
            //
            // We compute the capturedVars/adjustedRegistry up-front so the
            // seeding step sees the final, filtered set of variables.
            //
            // See dev/design/nested-eval-string-lexicals.md for full background.
            RuntimeBase[] capturedVars = new RuntimeBase[0];
            Map<String, Integer> adjustedRegistry = null;
            Map<String, Integer> registry = siteRegistry != null ? siteRegistry
                    : (currentCode != null ? currentCode.variableRegistry : null);
            if (registry != null && registers != null) {
                List<Map.Entry<String, Integer>> sortedVars = new ArrayList<>(registry.entrySet());
                sortedVars.sort(Map.Entry.comparingByValue());
                List<RuntimeBase> capturedList = new ArrayList<>();
                adjustedRegistry = new HashMap<>();
                adjustedRegistry.put("this", 0);
                adjustedRegistry.put("@_", 1);
                adjustedRegistry.put("wantarray", 2);
                // Per-eval-invocation unique alias namespace for seeded lexicals.
                int seedBeginId = EmitterMethodCreator.classCounter++;
                String seedPkg = PersistentVariable.beginPackage(seedBeginId);
                int captureIndex = 0;
                for (Map.Entry<String, Integer> entry : sortedVars) {
                    String varName = entry.getKey();
                    int parentRegIndex = entry.getValue();
                    if (parentRegIndex < 3) continue;
                    if (parentRegIndex >= registers.length) continue;
                    RuntimeBase value = registers[parentRegIndex];
                    // Skip non-Perl values (like Iterator objects from for loops).
                    if (value == null) {
                        // Null is fine — capture it.
                    } else if (value instanceof RuntimeScalar scalar) {
                        if (scalar.value instanceof java.util.Iterator) continue;
                    } else if (!(value instanceof RuntimeArray ||
                            value instanceof RuntimeHash ||
                            value instanceof RuntimeCode)) {
                        continue;
                    }
                    capturedList.add(value);
                    int newRegIndex = 3 + captureIndex;
                    adjustedRegistry.put(varName, newRegIndex);
                    captureIndex++;

                    // Alias this variable into the seed package's globals AND
                    // declare it as `our` in the parser's symbol table so named
                    // subs inside the eval body capture it correctly via the
                    // JVM subroutine-compilation path.
                    if (varName.length() < 2) continue;
                    char sigil = varName.charAt(0);
                    if (sigil != '$' && sigil != '@' && sigil != '%') continue;
                    String bareName = varName.substring(1);
                    String fullName = seedPkg + "::" + bareName;
                    if (sigil == '$' && value instanceof RuntimeScalar rs) {
                        GlobalVariable.globalVariables.put(fullName, rs);
                    } else if (sigil == '@' && value instanceof RuntimeArray ra) {
                        GlobalVariable.globalArrays.put(fullName, ra);
                    } else if (sigil == '%' && value instanceof RuntimeHash rh) {
                        GlobalVariable.globalHashes.put(fullName, rh);
                    } else {
                        // Sigil / value-type mismatch (e.g. captured as null).
                        // Skip the alias but still proceed to the symbol-table
                        // seeding below — it keeps parse-time checks happy
                        // even when the runtime capture is missing.
                    }
                    if (symbolTable.getSymbolEntry(varName) == null) {
                        symbolTable.addVariable(varName, "our", seedPkg, null);
                    }
                }
                capturedVars = capturedList.toArray(new RuntimeBase[0]);
                if (EVAL_TRACE) {
                    evalTrace("EvalStringHandler varRegistry keys=" + registry.keySet());
                    evalTrace("EvalStringHandler adjustedRegistry=" + adjustedRegistry);
                    evalTrace("EvalStringHandler seedPkg=" + seedPkg);
                    for (int ci = 0; ci < capturedVars.length; ci++) {
                        evalTrace("EvalStringHandler captured[" + ci + "]=" + (capturedVars[ci] != null ? capturedVars[ci].getClass().getSimpleName() + ":" + capturedVars[ci] : "null"));
                    }
                }
            }

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

            // (Captured variables and adjustedRegistry were computed above,
            //  before parsing, so the parser's symbol table could be seeded
            //  with consistent register indices.)

            // Step 4: Compile AST to interpreter bytecode with adjusted variable registry.
            // The compile-time package is already propagated via ctx.symbolTable.
            // NOTE: We do NOT propagate 'our' decls from the seeded symbol table here,
            // because nested evals (this code path) inject captured outer `my` variables
            // as `our` in a synthetic seed package purely for parser purposes. Those
            // captured-lexical variables must still live in registers at runtime, so
            // we keep the default "my" decl in the BytecodeCompiler.
            BytecodeCompiler compiler = new BytecodeCompiler(
                    evalFileName,
                    sourceLine,
                    errorUtil,
                    adjustedRegistry  // Pass adjusted registry for variable capture
            );
            InterpretedCode evalCode = compiler.compile(ast, ctx);  // Pass ctx for context propagation

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

            // Step 6: Execute the compiled code.
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
            RuntimeCode.evalDepth++;
            try {
                result = evalCode.apply(args, callContext);
            } finally {
                RuntimeCode.evalDepth--;
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

            // Parse the string
            Lexer lexer = new Lexer(perlCode);
            List<LexerToken> tokens = lexer.tokenize();

            // Generate a unique eval filename (see comment in evalStringList above)
            String evalFileName = RuntimeCode.getNextEvalFilename();

            CompilerOptions opts = new CompilerOptions();
            opts.fileName = evalFileName;
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
                    evalFileName,
                    sourceLine,
                    errorUtil
            );
            InterpretedCode evalCode = compiler.compile(ast, ctx);  // Pass ctx for context propagation
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

            // Scope currentPackage around eval — see Step 6 comment in evalStringHelper above.
            int pkgLevel = DynamicVariableManager.getLocalLevel();
            String savedPkg = InterpreterState.currentPackage.get().toString();
            DynamicVariableManager.pushLocalVariable(InterpreterState.currentPackage.get());
            InterpreterState.currentPackage.get().set(savedPkg);
            RuntimeArray args = new RuntimeArray();
            RuntimeList result;
            RuntimeCode.evalDepth++;
            try {
                result = evalCode.apply(args, RuntimeContextType.SCALAR);
            } finally {
                RuntimeCode.evalDepth--;
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
