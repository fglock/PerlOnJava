// package org.perlonjava.benchmark;

import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.util.Random;
import java.util.ArrayList;
import java.util.List;

public class NumificationBenchmark {

    private static final int ITERATIONS = 1_000_000;
    private static final int WARMUP_ITERATIONS = 100_000;

    private static List<String> generateTestCases() {
        List<String> testCases = new ArrayList<>();
        Random random = new Random();

        // Add some common cases
        testCases.add("42");
        testCases.add("3.14");
        testCases.add("-123");
        testCases.add("1e10");
        testCases.add("0xFF");

        // Generate random integers and floats
        for (int i = 0; i < 100; i++) {
            testCases.add(String.valueOf(random.nextInt(10000) - 5000));
            testCases.add(String.valueOf(random.nextDouble() * 1000 - 500));
        }

        return testCases;
    }

    private static void runBenchmark(List<String> testCases, boolean useCache) {
        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            for (String testCase : testCases) {
                RuntimeScalar scalar = new RuntimeScalar(testCase);
                scalar.parseNumber();
            }
        }

        // Actual benchmark
        long startTime = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            for (String testCase : testCases) {
                RuntimeScalar scalar = new RuntimeScalar(testCase);
                scalar.parseNumber();
            }
        }
        long endTime = System.nanoTime();

        double totalTimeMs = (endTime - startTime) / 1_000_000.0;
        System.out.printf("Total time %s cache: %.2f ms%n", 
                          useCache ? "with" : "without", totalTimeMs);
        System.out.printf("Average time per parse: %.3f µs%n", 
                          totalTimeMs * 1000 / (ITERATIONS * testCases.size()));
    }

    public static void main(String[] args) {
        List<String> testCases = generateTestCases();

        System.out.println("Running benchmark without cache:");
        runBenchmark(testCases, false);

        System.out.println("\nRunning benchmark with cache:");
        runBenchmark(testCases, true);
    }
}

