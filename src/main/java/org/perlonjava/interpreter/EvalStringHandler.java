package org.perlonjava.interpreter;

import org.perlonjava.astnode.Node;
import org.perlonjava.codegen.EmitterContext;
import org.perlonjava.codegen.JavaClassInfo;
import org.perlonjava.lexer.Lexer;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.parser.Parser;
import org.perlonjava.runtime.*;
import org.perlonjava.operators.WarnDie;
import org.perlonjava.symbols.ScopedSymbolTable;
import org.perlonjava.CompilerOptions;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

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
     * @return RuntimeScalar result of evaluation (undef on error)
     */
    public static RuntimeScalar evalString(String perlCode,
                                          InterpretedCode currentCode,
                                          RuntimeBase[] registers,
                                          String sourceName,
                                          int sourceLine) {
        try {
            // Step 1: Clear $@ at start of eval
            GlobalVariable.getGlobalVariable("main::@").set("");

            // Step 2: Parse the string to AST
            Lexer lexer = new Lexer(perlCode);
            List<LexerToken> tokens = lexer.tokenize();

            // Create minimal EmitterContext for parsing
            CompilerOptions opts = new CompilerOptions();
            opts.fileName = sourceName + " (eval)";
            ScopedSymbolTable symbolTable = new ScopedSymbolTable();
            ErrorMessageUtil errorUtil = new ErrorMessageUtil(sourceName, tokens);
            EmitterContext ctx = new EmitterContext(
                new JavaClassInfo(),
                symbolTable,
                null, // mv
                null, // cw
                RuntimeContextType.SCALAR,
                false, // isBoxed
                errorUtil,
                opts,
                null  // unitcheckBlocks
            );

            Parser parser = new Parser(ctx, tokens);
            Node ast = parser.parse();

            // Step 3: Compile AST to interpreter bytecode
            BytecodeCompiler compiler = new BytecodeCompiler(
                sourceName + " (eval)",
                sourceLine
            );
            InterpretedCode evalCode = compiler.compile(ast);

            // Step 4: Capture variables from outer scope if needed
            // For now, we create a new closure with empty captured vars
            // TODO: Implement proper variable capture detection
            RuntimeBase[] capturedVars = new RuntimeBase[0];
            if (currentCode != null && currentCode.capturedVars != null) {
                // Share captured variables from parent scope
                capturedVars = currentCode.capturedVars;
            }
            evalCode = evalCode.withCapturedVars(capturedVars);

            // Step 5: Execute the compiled code
            RuntimeArray args = new RuntimeArray();  // Empty @_
            RuntimeList result = evalCode.apply(args, RuntimeContextType.SCALAR);

            // Step 6: Return scalar result
            return result.scalar();

        } catch (Exception e) {
            // Step 7: Handle errors - set $@ and return undef
            WarnDie.catchEval(e);
            return RuntimeScalarCache.scalarUndef;
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

            // Compile to bytecode
            BytecodeCompiler compiler = new BytecodeCompiler(
                sourceName + " (eval)",
                sourceLine
            );
            InterpretedCode evalCode = compiler.compile(ast);

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
