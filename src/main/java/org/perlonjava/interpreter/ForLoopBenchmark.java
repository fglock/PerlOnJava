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
 * Benchmark for loop performance: Interpreter vs Compiler
 *
 * This benchmark measures the interpreter's performance on loop-heavy code.
 * Compare with compiled version by running the same code via ./jperl
 */
public class ForLoopBenchmark {

    private static InterpretedCode compileCode(String perlCode) throws Exception {
        Lexer lexer = new Lexer(perlCode);
        List<LexerToken> tokens = lexer.tokenize();

        CompilerOptions opts = new CompilerOptions();
        opts.fileName = "benchmark.pl";
        ScopedSymbolTable symbolTable = new ScopedSymbolTable();
        ErrorMessageUtil errorUtil = new ErrorMessageUtil("benchmark.pl", tokens);
        EmitterContext ctx = new EmitterContext(
            new JavaClassInfo(), symbolTable, null, null,
            RuntimeContextType.VOID, false, errorUtil, opts, null
        );

        Parser parser = new Parser(ctx, tokens);
        Node ast = parser.parse();

        BytecodeCompiler compiler = new BytecodeCompiler("benchmark.pl", 1);
        return compiler.compile(ast);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== For Loop Benchmark: Interpreter ===\n");

        // Test code: nested for loops
        String code = "my $sum = 0; for (my $i = 0; $i < 100; $i++) { $sum = $sum + $i; } $sum";

        // Compile once
        InterpretedCode interpretedCode = compileCode(code);

        // Warm up JIT (more iterations for better optimization)
        System.out.println("Warming up JIT...");
        RuntimeArray emptyArgs = new RuntimeArray();
        for (int i = 0; i < 1000; i++) {
            interpretedCode.apply(emptyArgs, RuntimeContextType.SCALAR);
        }

        // Actual benchmark
        System.out.println("Running benchmark...\n");

        int iterations = 10000;  // 10x more iterations for stable measurement
        int loop_size = 100;

        long start = System.nanoTime();
        for (int iter = 0; iter < iterations; iter++) {
            interpretedCode.apply(emptyArgs, RuntimeContextType.SCALAR);
        }
        long elapsed = System.nanoTime() - start;

        double seconds = elapsed / 1_000_000_000.0;
        long total_ops = (long) iterations * loop_size;
        double ops_per_sec = total_ops / seconds;

        System.out.printf("Iterations: %d\n", iterations);
        System.out.printf("Loop size: %d\n", loop_size);
        System.out.printf("Total operations: %d\n", total_ops);
        System.out.printf("Elapsed time: %.6f seconds\n", seconds);
        System.out.printf("Operations/sec: %.2f million\n\n", ops_per_sec / 1_000_000);

        System.out.println("Compare with compiler:");
        System.out.println("  ./jperl dev/interpreter/tests/for_loop_benchmark.pl");
    }
}
