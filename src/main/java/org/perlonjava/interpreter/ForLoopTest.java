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
 * Test for loop implementation in interpreter.
 */
public class ForLoopTest {

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
                null, null,
                RuntimeContextType.VOID,
                false, errorUtil, opts, null
            );

            // Step 3: Parse tokens to AST
            Parser parser = new Parser(ctx, tokens);
            Node ast = parser.parse();

            // Step 4: Compile AST to interpreter bytecode
            BytecodeCompiler compiler = new BytecodeCompiler(sourceName, sourceLine);
            InterpretedCode code = compiler.compile(ast);

            // Step 5: Execute via apply()
            RuntimeArray args = new RuntimeArray();
            int context = RuntimeContextType.SCALAR;

            return code.apply(args, context);

        } catch (Exception e) {
            System.err.println("Error in " + sourceName + ":" + sourceLine);
            System.err.println("Code: " + perlCode);
            e.printStackTrace();
            throw new RuntimeException("Interpreter test failed", e);
        }
    }

    public static void main(String[] args) {
        System.out.println("=== For Loop Interpreter Test ===\n");

        try {
            // Test 1: Simple C-style for loop
            System.out.println("Test 1: C-style for loop sum");
            String code1 = "my $sum = 0; for (my $i = 0; $i < 10; $i++) { $sum = $sum + $i; } $sum";
            RuntimeList result1 = runCode(code1, "test1.pl", 1);
            System.out.println("Result: " + result1);
            System.out.println("Expected: 45");

            System.out.println("\n=== All tests completed ===");
        } catch (Exception e) {
            System.err.println("\n=== Test failed ===");
            e.printStackTrace();
            System.exit(1);
        }
    }
}
