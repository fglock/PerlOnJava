package org.perlonjava.backend.bytecode;

import org.perlonjava.frontend.astnode.Node;
import org.perlonjava.backend.jvm.EmitterContext;
import org.perlonjava.backend.jvm.JavaClassInfo;
import org.perlonjava.frontend.lexer.Lexer;
import org.perlonjava.frontend.lexer.LexerToken;
import org.perlonjava.frontend.parser.Parser;
import org.perlonjava.runtime.runtimetypes.*;
import org.perlonjava.runtime.operators.WarnDie;
import org.perlonjava.frontend.semantic.ScopedSymbolTable;
import org.perlonjava.app.cli.CompilerOptions;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;


/**
 * Handler for eval STRING operations in the interpreter.
 *
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
     *
     * This implements eval STRING semantics:
     * - Parse and compile the string
     * - Execute in the current scope context
     * - Capture variables referenced from outer scope
     * - Return result or undef on error
     * - Set $@ on error
     *
     * @param perlCode      The Perl code string to evaluate
     * @param currentCode   The current InterpretedCode (for context)
     * @param registers     Current register array (for variable access)
     * @param sourceName    Source name for error messages
     * @param sourceLine    Source line for error messages
     * @param callContext   The calling context (VOID/SCALAR/LIST) for wantarray inside eval
     * @return RuntimeScalar result of evaluation (undef on error)
     */
    public static RuntimeScalar evalString(String perlCode,
                                          InterpretedCode currentCode,
                                          RuntimeBase[] registers,
                                          String sourceName,
                                          int sourceLine,
                                          int callContext) {
        return evalStringList(perlCode, currentCode, registers, sourceName, sourceLine, callContext).scalar();
    }

    public static RuntimeList evalStringList(String perlCode,
                                             InterpretedCode currentCode,
                                             RuntimeBase[] registers,
                                             String sourceName,
                                             int sourceLine,
                                             int callContext) {
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
            CompilerOptions opts = new CompilerOptions();
            opts.fileName = sourceName + " (eval)";
            ScopedSymbolTable symbolTable = new ScopedSymbolTable();

            // Inherit lexical pragma flags from parent if available
            if (currentCode != null) {
                // Replace default values with parent's flags
                symbolTable.strictOptionsStack.pop();
                symbolTable.strictOptionsStack.push(currentCode.strictOptions);
                symbolTable.featureFlagsStack.pop();
                symbolTable.featureFlagsStack.push(currentCode.featureFlags);
                symbolTable.warningFlagsStack.pop();
                symbolTable.warningFlagsStack.push((java.util.BitSet) currentCode.warningFlags.clone());
            }

            // Use the runtime package at the eval call site.
            // InterpreterState.currentPackage tracks runtime package changes from SET_PACKAGE/
            // PUSH_PACKAGE opcodes, so it correctly reflects the package active when eval is called.
            // This matches Perl's behaviour: eval("__PACKAGE__") returns the package at call site.
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

            if (currentCode != null && currentCode.variableRegistry != null && registers != null) {

                // Sort parent variables by register index for consistent ordering
                List<Map.Entry<String, Integer>> sortedVars = new ArrayList<>(
                        currentCode.variableRegistry.entrySet()
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
                        } else if (value instanceof RuntimeScalar) {
                            // Check if the scalar contains an Iterator (used by for loops)
                            RuntimeScalar scalar = (RuntimeScalar) value;
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
            }

            // Step 4: Compile AST to interpreter bytecode with adjusted variable registry.
            // The compile-time package is already propagated via ctx.symbolTable.
            BytecodeCompiler compiler = new BytecodeCompiler(
                    sourceName + " (eval)",
                    sourceLine,
                    errorUtil,
                    adjustedRegistry  // Pass adjusted registry for variable capture
            );
            InterpretedCode evalCode = compiler.compile(ast, ctx);  // Pass ctx for context propagation

            evalTrace("EvalStringHandler compiled bytecodeLen=" + (evalCode != null ? evalCode.bytecode.length : -1) +
                    " src=" + (evalCode != null ? evalCode.sourceName : "null"));
            if (RuntimeCode.DISASSEMBLE) {
                System.out.println(evalCode.disassemble());
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

            // Step 6: Execute the compiled code
            RuntimeArray args = new RuntimeArray();  // Empty @_
            RuntimeList result = evalCode.apply(args, callContext);
            evalTrace("EvalStringHandler exec ok ctx=" + callContext +
                    " resultScalar=" + (result != null ? result.scalar().toString() : "null") +
                    " resultBool=" + (result != null && result.scalar() != null ? result.scalar().getBoolean() : false) +
                    " $@=" + GlobalVariable.getGlobalVariable("main::@").toString());
            return result;
        } catch (Exception e) {
            evalTrace("EvalStringHandler exec exception ctx=" + callContext + " ex=" + e.getClass().getSimpleName() + " msg=" + e.getMessage());
            WarnDie.catchEval(e);
            return new RuntimeList(new RuntimeScalar());
        }
    }

    /**
     * Evaluate a Perl string with explicit variable capture.
     *
     * This version allows passing specific captured variables for the eval context.
     *
     * @param perlCode      The Perl code string to evaluate
     * @param capturedVars  Variables to capture from outer scope
     * @param sourceName    Source name for error messages
     * @param sourceLine    Source line for error messages
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

            CompilerOptions opts = new CompilerOptions();
            opts.fileName = sourceName + " (eval)";
            ScopedSymbolTable symbolTable = new ScopedSymbolTable();
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
                sourceLine
            );
            InterpretedCode evalCode = compiler.compile(ast, ctx);  // Pass ctx for context propagation
            if (RuntimeCode.DISASSEMBLE) {
                System.out.println(evalCode.disassemble());
            }

            // Store source lines in debugger symbol table if $^P flags are set
            int debugFlags = GlobalVariable.getGlobalVariable(GlobalContext.encodeSpecialVar("P")).getInt();
            if (debugFlags != 0) {
                String evalFilename = RuntimeCode.getNextEvalFilename();
                RuntimeCode.storeSourceLines(perlCode, evalFilename, ast, tokens);
            }

            // Attach captured variables
            evalCode = evalCode.withCapturedVars(capturedVars);

            // Execute
            RuntimeArray args = new RuntimeArray();
            RuntimeList result = evalCode.apply(args, RuntimeContextType.SCALAR);

            return result.scalar();

        } catch (Exception e) {
            WarnDie.catchEval(e);
            return RuntimeScalarCache.scalarUndef;
        }
    }

    /**
     * Detect which variables from outer scope are referenced in eval string.
     *
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
