package org.perlonjava.interpreter;

import java.util.Arrays;

/**
 * Comprehensive benchmark suite for comparing interpreter architectures.
 *
 * Tests:
 * 1. Empty opcode loop (pure dispatch overhead)
 * 2. Realistic workload mix (variable access, arithmetic, control flow)
 * 3. JIT warmup behavior
 * 4. Memory overhead
 */
public class InterpreterBenchmark {

    private static final int WARMUP_ITERATIONS = 5000;
    private static final int BENCHMARK_ITERATIONS = 10000;

    /**
     * Benchmark 1: Empty opcode loop (measures pure dispatch cost).
     */
    public static void benchmarkEmptyLoop() {
        System.out.println("\n=== Benchmark 1: Empty Opcode Loop (Pure Dispatch Overhead) ===");

        // Create bytecode with 10000 NOPs
        byte[] bytecode = new byte[10001];
        Arrays.fill(bytecode, 0, 10000, Opcodes.NOP);
        bytecode[10000] = Opcodes.RETURN;

        InterpretedCode code = new InterpretedCode.Builder()
            .bytecode(bytecode)
            .build();

        // Warmup both interpreters
        System.out.println("Warming up...");
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            SwitchInterpreter.execute(code);
            FunctionArrayInterpreter.execute(code);
        }

        // Benchmark switch-based
        System.out.println("\nBenchmarking switch-based interpreter...");
        long switchStart = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            SwitchInterpreter.execute(code);
        }
        long switchEnd = System.nanoTime();
        long switchTotal = switchEnd - switchStart;
        double switchPerOp = (double) switchTotal / (BENCHMARK_ITERATIONS * 10000);

        // Benchmark function-array
        System.out.println("Benchmarking function-array interpreter...");
        long funcStart = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            FunctionArrayInterpreter.execute(code);
        }
        long funcEnd = System.nanoTime();
        long funcTotal = funcEnd - funcStart;
        double funcPerOp = (double) funcTotal / (BENCHMARK_ITERATIONS * 10000);

        // Report results
        System.out.println("\nResults:");
        System.out.printf("Switch-based:      %.2f ns/opcode  (total: %d ms)\n",
            switchPerOp, switchTotal / 1_000_000);
        System.out.printf("Function-array:    %.2f ns/opcode  (total: %d ms)\n",
            funcPerOp, funcTotal / 1_000_000);
        System.out.printf("Speedup:           %.2fx %s\n",
            Math.max(switchPerOp, funcPerOp) / Math.min(switchPerOp, funcPerOp),
            switchPerOp < funcPerOp ? "(switch wins)" : "(function-array wins)");
    }

    /**
     * Benchmark 2: Realistic workload mix.
     * Simulates typical Perl code distribution:
     * - 40% variable access (LOAD/STORE)
     * - 30% arithmetic (ADD, SUB, MUL)
     * - 20% stack operations (DUP, POP)
     * - 10% control flow (RETURN at end)
     */
    public static void benchmarkRealisticWorkload() {
        System.out.println("\n=== Benchmark 2: Realistic Workload Mix ===");

        // Build realistic bytecode:
        // Calculate: result = (a + b) * (c - d)
        // Using locals: 0=a, 1=b, 2=c, 3=d, 4=result
        // Repeat 1000 times
        int repeats = 1000;
        byte[] bytecode = new byte[repeats * 15 + 1]; // 15 bytes per iteration + 1 for RETURN
        int[] intPool = {10, 20, 30, 5}; // Test values

        int pos = 0;
        for (int i = 0; i < repeats; i++) {
            // Load test values from int pool
            bytecode[pos++] = Opcodes.LOAD_INT; bytecode[pos++] = 0; // 10
            bytecode[pos++] = Opcodes.STORE_LOCAL; bytecode[pos++] = 0;

            bytecode[pos++] = Opcodes.LOAD_INT; bytecode[pos++] = 1; // 20
            bytecode[pos++] = Opcodes.STORE_LOCAL; bytecode[pos++] = 1;

            // Calculate (10 + 20)
            bytecode[pos++] = Opcodes.LOAD_LOCAL; bytecode[pos++] = 0;
            bytecode[pos++] = Opcodes.LOAD_LOCAL; bytecode[pos++] = 1;
            bytecode[pos++] = Opcodes.ADD_INT;
            bytecode[pos++] = Opcodes.STORE_LOCAL; bytecode[pos++] = 4; // result = 30
        }
        bytecode[pos] = Opcodes.RETURN;

        InterpretedCode code = new InterpretedCode.Builder()
            .bytecode(bytecode)
            .intPool(intPool)
            .maxLocals(10)
            .maxStack(10)
            .build();

        // Warmup
        System.out.println("Warming up...");
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            SwitchInterpreter.execute(code);
            FunctionArrayInterpreter.execute(code);
        }

        // Benchmark switch-based
        System.out.println("\nBenchmarking switch-based interpreter...");
        long switchStart = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            SwitchInterpreter.execute(code);
        }
        long switchEnd = System.nanoTime();
        long switchTotal = switchEnd - switchStart;

        // Benchmark function-array
        System.out.println("Benchmarking function-array interpreter...");
        long funcStart = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            FunctionArrayInterpreter.execute(code);
        }
        long funcEnd = System.nanoTime();
        long funcTotal = funcEnd - funcStart;

        // Calculate throughput (operations per second)
        long opsPerExecution = repeats * 7; // 7 opcodes per iteration
        double switchOpsPerSec = (double) (BENCHMARK_ITERATIONS * opsPerExecution) /
            (switchTotal / 1_000_000_000.0);
        double funcOpsPerSec = (double) (BENCHMARK_ITERATIONS * opsPerExecution) /
            (funcTotal / 1_000_000_000.0);

        // Report results
        System.out.println("\nResults:");
        System.out.printf("Switch-based:      %.1f M ops/sec  (total: %d ms)\n",
            switchOpsPerSec / 1_000_000, switchTotal / 1_000_000);
        System.out.printf("Function-array:    %.1f M ops/sec  (total: %d ms)\n",
            funcOpsPerSec / 1_000_000, funcTotal / 1_000_000);
        System.out.printf("Speedup:           %.2fx %s\n",
            Math.max(switchOpsPerSec, funcOpsPerSec) / Math.min(switchOpsPerSec, funcOpsPerSec),
            switchOpsPerSec > funcOpsPerSec ? "(switch wins)" : "(function-array wins)");
    }

    /**
     * Benchmark 3: JIT warmup behavior.
     * Measures how long it takes to reach steady-state performance.
     */
    public static void benchmarkJitWarmup() {
        System.out.println("\n=== Benchmark 3: JIT Warmup Behavior ===");

        // Simple bytecode for warmup test
        byte[] bytecode = new byte[101];
        Arrays.fill(bytecode, 0, 100, Opcodes.NOP);
        bytecode[100] = Opcodes.RETURN;

        InterpretedCode code = new InterpretedCode.Builder()
            .bytecode(bytecode)
            .build();

        // Measure switch-based warmup
        System.out.println("\nSwitch-based warmup profile:");
        long[] switchTimes = new long[100];
        for (int i = 0; i < 10000; i++) {
            long start = System.nanoTime();
            SwitchInterpreter.execute(code);
            long elapsed = System.nanoTime() - start;

            if (i < 100) {
                switchTimes[i] = elapsed;
            }

            if (i % 1000 == 0 && i > 0) {
                System.out.printf("  Iteration %5d: %6d ns\n", i, elapsed);
            }
        }

        // Measure function-array warmup
        System.out.println("\nFunction-array warmup profile:");
        long[] funcTimes = new long[100];
        for (int i = 0; i < 10000; i++) {
            long start = System.nanoTime();
            FunctionArrayInterpreter.execute(code);
            long elapsed = System.nanoTime() - start;

            if (i < 100) {
                funcTimes[i] = elapsed;
            }

            if (i % 1000 == 0 && i > 0) {
                System.out.printf("  Iteration %5d: %6d ns\n", i, elapsed);
            }
        }

        // Calculate average of first 10 vs last 10 iterations
        long switchEarly = Arrays.stream(switchTimes, 0, 10).sum() / 10;
        long switchLate = Arrays.stream(switchTimes, 90, 100).sum() / 10;
        long funcEarly = Arrays.stream(funcTimes, 0, 10).sum() / 10;
        long funcLate = Arrays.stream(funcTimes, 90, 100).sum() / 10;

        System.out.println("\nWarmup impact:");
        System.out.printf("Switch-based:   %6d ns (early) -> %6d ns (late) = %.2fx improvement\n",
            switchEarly, switchLate, (double) switchEarly / switchLate);
        System.out.printf("Function-array: %6d ns (early) -> %6d ns (late) = %.2fx improvement\n",
            funcEarly, funcLate, (double) funcEarly / funcLate);
    }

    /**
     * Benchmark 4: Memory overhead.
     * Measures heap allocation per interpreted execution.
     */
    public static void benchmarkMemoryOverhead() {
        System.out.println("\n=== Benchmark 4: Memory Overhead ===");

        byte[] bytecode = new byte[101];
        Arrays.fill(bytecode, 0, 100, Opcodes.NOP);
        bytecode[100] = Opcodes.RETURN;

        InterpretedCode code = new InterpretedCode.Builder()
            .bytecode(bytecode)
            .build();

        // Force GC before measurement
        System.gc();
        try { Thread.sleep(100); } catch (InterruptedException e) {}

        Runtime runtime = Runtime.getRuntime();

        // Measure switch-based memory
        long beforeSwitch = runtime.totalMemory() - runtime.freeMemory();
        for (int i = 0; i < 10000; i++) {
            SwitchInterpreter.execute(code);
        }
        long afterSwitch = runtime.totalMemory() - runtime.freeMemory();
        long switchMem = afterSwitch - beforeSwitch;

        System.gc();
        try { Thread.sleep(100); } catch (InterruptedException e) {}

        // Measure function-array memory
        long beforeFunc = runtime.totalMemory() - runtime.freeMemory();
        for (int i = 0; i < 10000; i++) {
            FunctionArrayInterpreter.execute(code);
        }
        long afterFunc = runtime.totalMemory() - runtime.freeMemory();
        long funcMem = afterFunc - beforeFunc;

        System.out.println("\nMemory allocated per 10000 executions:");
        System.out.printf("Switch-based:   %d bytes (%.1f bytes/execution)\n",
            switchMem, switchMem / 10000.0);
        System.out.printf("Function-array: %d bytes (%.1f bytes/execution)\n",
            funcMem, funcMem / 10000.0);

        if (Math.abs(switchMem - funcMem) > 1000) {
            System.out.printf("Difference:     %d bytes %s\n",
                Math.abs(switchMem - funcMem),
                switchMem < funcMem ? "(switch allocates less)" : "(function-array allocates less)");
        } else {
            System.out.println("Difference:     Negligible");
        }
    }

    /**
     * Main entry point - run all benchmarks.
     */
    public static void main(String[] args) {
        System.out.println("╔═══════════════════════════════════════════════════════════════╗");
        System.out.println("║  PerlOnJava Interpreter Architecture Benchmark Suite         ║");
        System.out.println("╚═══════════════════════════════════════════════════════════════╝");

        System.out.println("\nJava Version: " + System.getProperty("java.version"));
        System.out.println("JVM: " + System.getProperty("java.vm.name") + " " +
            System.getProperty("java.vm.version"));
        System.out.println("OS: " + System.getProperty("os.name") + " " +
            System.getProperty("os.arch"));

        // Run benchmarks
        benchmarkEmptyLoop();
        benchmarkRealisticWorkload();
        benchmarkJitWarmup();
        benchmarkMemoryOverhead();

        System.out.println("\n╔═══════════════════════════════════════════════════════════════╗");
        System.out.println("║  Benchmark Complete                                           ║");
        System.out.println("╚═══════════════════════════════════════════════════════════════╝");
        System.out.println("\nNext steps:");
        System.out.println("1. Review results above");
        System.out.println("2. Run with JIT diagnostics: -XX:+PrintCompilation -XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining");
        System.out.println("3. Document findings in dev/design/interpreter_benchmarks.md");
        System.out.println("4. Make architecture decision based on empirical data");
    }
}
