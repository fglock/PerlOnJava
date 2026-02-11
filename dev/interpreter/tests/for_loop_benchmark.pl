#!/usr/bin/env perl
use strict;
use warnings;
use Time::HiRes qw(time);

# Benchmark: For loop performance
# Compare interpreter vs compiler on loop-heavy workload

print "=== For Loop Benchmark: Interpreter vs Compiler ===\n\n";

# Test case: Sum loop
sub benchmark_sum {
    my ($iterations, $loop_size) = @_;

    my $start = time();
    for (my $iter = 0; $iter < $iterations; $iter++) {
        my $sum = 0;
        for (my $i = 0; $i < $loop_size; $i++) {
            $sum = $sum + $i;
        }
    }
    my $elapsed = time() - $start;

    return $elapsed;
}

# Warm up
print "Warming up JIT...\n";
benchmark_sum(100, 100);

# Actual benchmark
print "Running benchmark...\n\n";

my $iterations = 1000;
my $loop_size = 100;

my $elapsed = benchmark_sum($iterations, $loop_size);
my $total_ops = $iterations * $loop_size;
my $ops_per_sec = $total_ops / $elapsed;

printf "Iterations: %d\n", $iterations;
printf "Loop size: %d\n", $loop_size;
printf "Total operations: %d\n", $total_ops;
printf "Elapsed time: %.6f seconds\n", $elapsed;
printf "Operations/sec: %.2f million\n\n", $ops_per_sec / 1_000_000;

print "Note: Run this with both:\n";
print "  ./jperl (compiler)\n";
print "  java -cp ... ForLoopBenchmark (interpreter)\n";
