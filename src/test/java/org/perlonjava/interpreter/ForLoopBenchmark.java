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

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import javax.script.Compilable;
import javax.script.CompiledScript;
import java.util.List;

/**
 * Benchmark for loop performance: Interpreter vs Compiler
 *
 * This benchmark measures both interpreter and compiler performance on loop-heavy code
 * using the same JVM session for fair comparison.
 *
 * The compiler is invoked via JSR 223 (Java Scripting API), ensuring identical
 * warmup and execution conditions.
 */
public class ForLoopBenchmark {

    // Debug flag - JVM will optimize this away when false
    private static final boolean DEBUG = false;

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
        System.out.println("=== For Loop Benchmark: Interpreter vs Compiler ===\n");

        // Test code: for loop with 1000 iterations
        String code = "my $sum = 0; for (my $i = 0; $i < 1000; $i++) { $sum = $sum + $i; } $sum";

        int iterations = 10000;  // 10k iterations for stable measurement
        int loop_size = 1000;     // 1000 operations per iteration

        // =====================================================================
        // INTERPRETER BENCHMARK
        // =====================================================================

        System.out.println("--- Interpreter Benchmark ---\n");

        // Compile once
        InterpretedCode interpretedCode = compileCode(code);

        if (DEBUG) {
            System.out.println(interpretedCode.disassemble());
        }

        // Warm up JIT
        System.out.println("Warming up interpreter JIT...");
        RuntimeArray emptyArgs = new RuntimeArray();
        for (int i = 0; i < 1000; i++) {
            interpretedCode.apply(emptyArgs, RuntimeContextType.SCALAR);
        }

        // Benchmark interpreter
        System.out.println("Running interpreter benchmark...\n");
        long start = System.nanoTime();
        for (int iter = 0; iter < iterations; iter++) {
            interpretedCode.apply(emptyArgs, RuntimeContextType.SCALAR);
        }
        long elapsed_interpreter = System.nanoTime() - start;

        double seconds_interp = elapsed_interpreter / 1_000_000_000.0;
        long total_ops = (long) iterations * loop_size;
        double ops_per_sec_interp = total_ops / seconds_interp;

        System.out.printf("Interpreter Results:\n");
        System.out.printf("  Iterations: %,d\n", iterations);
        System.out.printf("  Loop size: %,d\n", loop_size);
        System.out.printf("  Total operations: %,d\n", total_ops);
        System.out.printf("  Elapsed time: %.6f seconds\n", seconds_interp);
        System.out.printf("  Operations/sec: %.2f million\n\n", ops_per_sec_interp / 1_000_000);

        // =====================================================================
        // COMPILER BENCHMARK (via JSR 223 Compilable)
        // =====================================================================

        System.out.println("--- Compiler Benchmark (JSR 223 Compilable) ---\n");

        // Get Perl script engine
        ScriptEngineManager manager = new ScriptEngineManager();
        ScriptEngine engine = manager.getEngineByName("perl");

        if (engine == null) {
            System.err.println("ERROR: Perl script engine not found!");
            System.err.println("Make sure PerlScriptEngineFactory is registered.");
            System.exit(1);
        }

        // Compile once using Compilable interface
        System.out.println("Compiling code once via Compilable interface...");
        CompiledScript compiledScript = null;
        if (engine instanceof Compilable) {
            try {
                compiledScript = ((Compilable) engine).compile(code);
                System.out.println("Compilation successful\n");
            } catch (ScriptException e) {
                System.err.println("Compilation failed: " + e.getMessage());
                throw e;
            }
        } else {
            System.err.println("ERROR: Engine doesn't support Compilable interface");
            System.exit(1);
        }

        // Warm up compiler JIT
        System.out.println("Warming up compiler JIT...");
        for (int i = 0; i < 1000; i++) {
            try {
                compiledScript.eval();
            } catch (ScriptException e) {
                System.err.println("Compiler warmup failed: " + e.getMessage());
                throw e;
            }
        }

        // Benchmark compiled code
        System.out.println("Running compiler benchmark...\n");
        long start_compiler = System.nanoTime();
        for (int iter = 0; iter < iterations; iter++) {
            try {
                compiledScript.eval();
            } catch (ScriptException e) {
                System.err.println("Compiler benchmark failed: " + e.getMessage());
                throw e;
            }
        }
        long elapsed_compiler = System.nanoTime() - start_compiler;

        double seconds_compiler = elapsed_compiler / 1_000_000_000.0;
        double ops_per_sec_compiler = total_ops / seconds_compiler;

        System.out.printf("Compiler Results:\n");
        System.out.printf("  Iterations: %,d\n", iterations);
        System.out.printf("  Loop size: %,d\n", loop_size);
        System.out.printf("  Total operations: %,d\n", total_ops);
        System.out.printf("  Elapsed time: %.6f seconds\n", seconds_compiler);
        System.out.printf("  Operations/sec: %.2f million\n\n", ops_per_sec_compiler / 1_000_000);

        // =====================================================================
        // COMPARISON
        // =====================================================================

        System.out.println("=== Performance Comparison ===\n");

        double speedup = ops_per_sec_compiler / ops_per_sec_interp;
        double interpreter_percent = (ops_per_sec_interp / ops_per_sec_compiler) * 100;

        System.out.printf("Compiler:    %.2f million ops/sec\n", ops_per_sec_compiler / 1_000_000);
        System.out.printf("Interpreter: %.2f million ops/sec\n", ops_per_sec_interp / 1_000_000);
        System.out.printf("\n");
        System.out.printf("Interpreter is %.2fx slower than compiler\n", speedup);
        System.out.printf("Interpreter achieves %.1f%% of compiler speed\n", interpreter_percent);
        System.out.printf("\n");

        // Check against target
        if (speedup <= 5.0) {
            System.out.println("✓ Within target range (2-5x slower)");
        } else {
            System.out.println("✗ Outside target range (2-5x slower)");
        }
    }
}
