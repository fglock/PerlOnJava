package org.perlonjava.interpreter;

import org.perlonjava.parser.Parser;
import org.perlonjava.astnode.Node;
import org.perlonjava.runtime.*;

/**
 * Simple test harness for the interpreter.
 *
 * Usage: Parse Perl code -> Compile to bytecode -> Execute via apply()
 */
public class InterpreterTest {

    /**
     * Parse, compile, and execute Perl code using the interpreter.
     *
     * @param perlCode   The Perl code to execute
     * @param sourceName Source name for debugging
     * @param sourceLine Source line for debugging
     * @return RuntimeList containing the result
     */
    public static RuntimeList runCode(String perlCode, String sourceName, int sourceLine) {
        try {
            // Step 1: Parse Perl code to AST using existing parser
            Parser parser = new Parser(perlCode);
            Node ast = parser.parse();

            // Step 2: Compile AST to interpreter bytecode
            BytecodeCompiler compiler = new BytecodeCompiler(sourceName, sourceLine);
            InterpretedCode code = compiler.compile(ast);

            // Step 3: Execute via apply() (just like compiled code)
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

        // Test 1: Simple integer
        System.out.println("Test 1: my $x = 5; say $x");
        runCode("my $x = 5; say $x", "test1.pl", 1);

        // Test 2: Arithmetic
        System.out.println("\nTest 2: my $x = 10 + 20; say $x");
        runCode("my $x = 10 + 20; say $x", "test2.pl", 1);

        // Test 3: String concatenation
        System.out.println("\nTest 3: my $x = 'Hello' . ' World'; say $x");
        runCode("my $x = 'Hello' . ' World'; say $x", "test3.pl", 1);

        System.out.println("\n=== All tests completed ===");
    }
}
