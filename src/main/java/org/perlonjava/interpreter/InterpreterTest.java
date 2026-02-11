package org.perlonjava.interpreter;

import org.perlonjava.lexer.Lexer;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.parser.Parser;
import org.perlonjava.astnode.Node;
import org.perlonjava.runtime.*;
import org.perlonjava.codegen.EmitterContext;
import org.perlonjava.codegen.JavaClassInfo;
import org.perlonjava.CompilerOptions;
import org.perlonjava.symbols.ScopedSymbolTable;

import java.util.List;

/**
 * Simple test harness for the interpreter.
 *
 * Usage: Parse Perl code -> Compile to bytecode -> Execute via apply()
 */
public class InterpreterTest {

    /**
     * Parse, compile, and execute Perl code using the interpreter.
     */
    public static RuntimeList runCode(String perlCode, String sourceName, int sourceLine) {
        try {
            // Step 1: Tokenize Perl code
            Lexer lexer = new Lexer(perlCode);
            List<LexerToken> tokens = lexer.tokenize();

            // Step 2: Create minimal EmitterContext for parsing
            CompilerOptions opts = new CompilerOptions();
            opts.fileName = sourceName;
            ScopedSymbolTable symbolTable = new ScopedSymbolTable();
            ErrorMessageUtil errorUtil = new ErrorMessageUtil(sourceName, tokens);
            EmitterContext ctx = new EmitterContext(
                new JavaClassInfo(),
                symbolTable,
                null, // mv
                null, // cw
                RuntimeContextType.VOID,
                false, // isBoxed
                errorUtil,
                opts,
                null  // unitcheckBlocks
            );

            // Step 3: Parse tokens to AST
            Parser parser = new Parser(ctx, tokens);
            Node ast = parser.parse();

            // Step 4: Compile AST to interpreter bytecode
            BytecodeCompiler compiler = new BytecodeCompiler(sourceName, sourceLine);
            InterpretedCode code = compiler.compile(ast);

            // Step 5: Execute via apply() (just like compiled code)
            RuntimeArray args = new RuntimeArray();  // Empty @_
            int context = RuntimeContextType.VOID;   // Void context

            return code.apply(args, context);

        } catch (Exception e) {
            System.err.println("Error in " + sourceName + ":" + sourceLine);
            System.err.println("Code: " + perlCode);
            e.printStackTrace();
            throw new RuntimeException("Interpreter test failed", e);
        }
    }

    /**
     * Main entry point for manual testing.
     */
    public static void main(String[] args) {
        System.out.println("=== Interpreter Test Suite ===\n");

        try {
            // Test 1: Simple integer
            System.out.println("Test 1: my $x = 5");
            RuntimeList result1 = runCode("my $x = 5", "test1.pl", 1);
            System.out.println("Result: " + result1);

            /*
            // Test 2: Arithmetic
            System.out.println("\nTest 2: my $x = 10 + 20; say $x");
            runCode("my $x = 10 + 20; say $x", "test2.pl", 1);

            // Test 3: String concatenation
            System.out.println("\nTest 3: my $x = 'Hello' . ' World'; say $x");
            runCode("my $x = 'Hello' . ' World'; say $x", "test3.pl", 1);
            */

            System.out.println("\n=== All tests completed ===");
        } catch (Exception e) {
            System.err.println("\n=== Test failed ===");
            e.printStackTrace();
            System.exit(1);
        }
    }
}
