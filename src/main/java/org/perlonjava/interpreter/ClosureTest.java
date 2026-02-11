package org.perlonjava.interpreter;

import org.perlonjava.CompilerOptions;
import org.perlonjava.astnode.Node;
import org.perlonjava.codegen.EmitterContext;
import org.perlonjava.codegen.JavaClassInfo;
import org.perlonjava.lexer.Lexer;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.parser.Parser;
import org.perlonjava.runtime.*;
import org.perlonjava.symbols.ScopedSymbolTable;

import java.util.List;

/**
 * Test harness for interpreter closure support.
 *
 * Demonstrates that InterpretedCode can be stored as named subroutines
 * and called from compiled code, bypassing eval STRING complexity.
 */
public class ClosureTest {

    private static int closureCounter = 0;

    public static void main(String[] args) {
        System.out.println("=== Interpreter Closure Test ===\n");

        // Test 1: Simple interpreted function (no closure)
        System.out.println("Test 1: Simple interpreted function");
        testSimpleFunction();

        // Test 2: Store as named sub and call
        System.out.println("\nTest 2: Call interpreted code as named sub");
        testNamedSubCall();

        System.out.println("\n=== All manual tests completed ===");
    }

    private static void testSimpleFunction() {
        try {
            // Compile: sub { $_[0] + $_[1] }
            String perlCode = "$_[0] + $_[1]";
            InterpretedCode code = compileSimple(perlCode);

            // Register as named sub
            String subName = "main::test_add";
            RuntimeScalar codeRef = code.registerAsNamedSub(subName);

            // Call it
            RuntimeArray args = new RuntimeArray();
            args.push(new RuntimeScalar(10));
            args.push(new RuntimeScalar(20));

            RuntimeList result = code.apply(args, RuntimeContextType.SCALAR);
            System.out.println("  Result: " + result.scalar().toString());
            System.out.println("  Expected: 30");
            System.out.println("  Status: " + (result.scalar().getInt() == 30 ? "PASS" : "FAIL"));

        } catch (Exception e) {
            System.err.println("  [ERROR] " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void testNamedSubCall() {
        try {
            // Compile: sub { $_[0] * 2 }
            String perlCode = "$_[0] * 2";
            InterpretedCode code = compileSimple(perlCode);

            // Register as named sub
            String subName = "main::test_double";
            code.registerAsNamedSub(subName);

            // Now compiled code can call &test_double
            // For this test, we'll call it directly via GlobalVariable
            RuntimeScalar codeRef = GlobalVariable.getGlobalCodeRef(subName);
            RuntimeCode runtimeCode = (RuntimeCode) codeRef.value;

            // Call it
            RuntimeArray args = new RuntimeArray();
            args.push(new RuntimeScalar(5));

            RuntimeList result = runtimeCode.apply(args, RuntimeContextType.SCALAR);
            System.out.println("  Result: " + result.scalar().toString());
            System.out.println("  Expected: 10");
            System.out.println("  Status: " + (result.scalar().getInt() == 10 ? "PASS" : "FAIL"));

        } catch (Exception e) {
            System.err.println("  [ERROR] " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Helper to compile simple Perl expressions to InterpretedCode.
     */
    private static InterpretedCode compileSimple(String perlCode) {
        try {
            Lexer lexer = new Lexer(perlCode);
            List<LexerToken> tokens = lexer.tokenize();

            // Create minimal EmitterContext for parsing
            CompilerOptions opts = new CompilerOptions();
            opts.fileName = "test.pl";
            ScopedSymbolTable symbolTable = new ScopedSymbolTable();
            ErrorMessageUtil errorUtil = new ErrorMessageUtil(opts.fileName, tokens);

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

            BytecodeCompiler compiler = new BytecodeCompiler("test.pl", 1);
            return compiler.compile(ast, ctx);  // Pass context for closure detection

        } catch (Exception e) {
            throw new RuntimeException("Compilation failed: " + e.getMessage(), e);
        }
    }

    /**
     * Generate a unique closure name.
     */
    private static String generateClosureName() {
        return "main::__closure_" + (closureCounter++);
    }
}
