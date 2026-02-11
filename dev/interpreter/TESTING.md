# Interpreter Testing Guide

This document explains how to test and benchmark the PerlOnJava interpreter.

## Running Tests

### Unit Tests

The interpreter shares the same test suite as the compiler. All tests run against both execution modes.

```bash
# Run fast unit tests (runs in seconds)
make test-unit

# Run comprehensive tests (includes Perl 5 core tests)
make test-all

# Run specific test file
perl dev/tools/perl_test_runner.pl src/test/resources/unit/array.t
```

### Test Organization

```
src/test/resources/
├── unit/           # Fast unit tests (core functionality, operators, syntax)
├── perl5_t/t/      # Perl 5 core test suite
└── perl5_t/[Module]/  # Perl 5 module tests
```

## Benchmarks

### ForLoopBenchmark

Micro-benchmark for interpreter performance on loop-heavy code.

**Run the benchmark:**

```bash
# Method 1: Via Gradle (recommended)
./gradlew run -PmainClass=org.perlonjava.interpreter.ForLoopBenchmark

# Method 2: Direct Java execution
java -cp "build/classes/java/main:$(find ~/.gradle/caches -name 'icu4j*.jar' -o -name 'asm*.jar' -o -name 'fastjson*.jar' | tr '\n' ':')" \
     org.perlonjava.interpreter.ForLoopBenchmark
```

**Enable debug output:**

Edit `ForLoopBenchmark.java` and set:
```java
private static final boolean DEBUG = true;
```

Then rebuild:
```bash
make build
```

**Example output:**
```
=== For Loop Benchmark: Interpreter ===

Warming up JIT...
Running benchmark...

Iterations: 10000
Loop size: 100
Total operations: 1000000
Elapsed time: 0.048123 seconds
Operations/sec: 20.78 million

Compare with compiler:
  ./jperl dev/interpreter/tests/for_loop_benchmark.pl
```

### Comparing Interpreter vs Compiler

**Interpreter benchmark** (above): ~20-21M ops/sec

**Compiler benchmark:**

```bash
cat > /tmp/bench.pl << 'EOF'
use strict;
use warnings;

my $iterations = 10000;
my $loop_size = 100;

my $start = Time::HiRes::time();

for my $iter (1..$iterations) {
    my $sum = 0;
    for (my $i = 0; $i < $loop_size; $i++) {
        $sum = $sum + $i;
    }
}

my $elapsed = Time::HiRes::time() - $start;
my $total_ops = $iterations * $loop_size;
my $ops_per_sec = $total_ops / $elapsed;

printf "Compiler: %.2f million ops/sec\n", $ops_per_sec / 1_000_000;
EOF

./jperl -MTime::HiRes /tmp/bench.pl
```

**Expected output:**
```
Compiler: 37-40 million ops/sec
```

**Performance ratio:** Interpreter is typically 1.8-2.0x slower than compiler (within target).

## Profiling

### JIT Compilation Analysis

See what methods the JVM is compiling and inlining:

```bash
java -XX:+PrintCompilation \
     -cp "build/classes/java/main:$(find ~/.gradle/caches -name 'icu4j*.jar' -o -name 'asm*.jar' -o -name 'fastjson*.jar' | tr '\n' ':')" \
     org.perlonjava.interpreter.ForLoopBenchmark 2>&1 | grep -E "(BytecodeInterpreter|MathOperators|CompareOperators)"
```

### Inlining Decisions

See what the C2 compiler is inlining:

```bash
java -XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining \
     -cp "build/classes/java/main:$(find ~/.gradle/caches -name 'icu4j*.jar' -o -name 'asm*.jar' -o -name 'fastjson*.jar' | tr '\n' ':')" \
     org.perlonjava.interpreter.ForLoopBenchmark 2>&1 > /tmp/inline.txt

# Check if operators are being inlined into execute loop
grep "BytecodeInterpreter::execute" /tmp/inline.txt | grep -E "(add|lessThan|subtract)"
```

### Bytecode Disassembly

View the generated interpreter bytecode:

```bash
# Enable DEBUG mode in ForLoopBenchmark.java, then:
make build
java -cp "build/classes/java/main:$(find ~/.gradle/caches -name 'icu4j*.jar' -o -name 'asm*.jar' -o -name 'fastjson*.jar' | tr '\n' ':')" \
     org.perlonjava.interpreter.ForLoopBenchmark 2>&1 | head -30
```

**Example disassembly:**
```
=== Bytecode Disassembly ===
Source: benchmark.pl:1
Registers: 10
Bytecode length: 50 bytes

   0: LOAD_INT r4 = 0
   6: MOVE r3 = r4
   9: LOAD_INT r6 = 0
  15: MOVE r5 = r6
  18: LOAD_INT r8 = 100
  24: LT_NUM r9 = r5 < r8
  28: GOTO_IF_FALSE r9 -> 48
  34: ADD_ASSIGN r3 += r5        # Optimized: was ADD_SCALAR + MOVE
  37: ADD_ASSIGN_INT r5 += 1     # Optimized: was ADD_SCALAR_INT + MOVE
  43: GOTO 18
  48: RETURN r3
```

## Performance Debugging

### Common Issues

**1. Lower than expected performance**

Check if JIT is warming up properly:
- Increase warmup iterations in ForLoopBenchmark (default: 1000)
- Run benchmark multiple times and compare

**2. Regression after changes**

Compare before/after:
```bash
# Before changes
git stash
make build
java ... ForLoopBenchmark > /tmp/before.txt

# After changes
git stash pop
make build
java ... ForLoopBenchmark > /tmp/after.txt

# Compare
diff /tmp/before.txt /tmp/after.txt
```

**3. Verifying optimizations**

Enable DEBUG mode and check bytecode disassembly:
- Count opcodes in hot loop
- Verify superinstructions are being used (ADD_ASSIGN, ADD_ASSIGN_INT)
- Check for unnecessary MOVE or LOAD_INT instructions

### Measuring Specific Opcodes

Add instrumentation to BytecodeInterpreter.java:

```java
private static final boolean PROFILE = false;
private static final long[] opcodeCount = new long[256];

// In execute() method:
if (PROFILE) {
    opcodeCount[opcode & 0xFF]++;
}

// After benchmark, print stats:
if (PROFILE) {
    for (int i = 0; i < 256; i++) {
        if (opcodeCount[i] > 0) {
            System.out.printf("Opcode %d: %d times\n", i, opcodeCount[i]);
        }
    }
}
```

## Continuous Integration

Tests run automatically on every commit via GitHub Actions:

```bash
# Local CI simulation
make clean
make build
make test-unit
make test-all
```

## Regression Tests

Create performance regression tests to catch slowdowns:

```perl
# tests/interpreter/performance_regression.t
use strict;
use warnings;
use Test::More tests => 1;
use Time::HiRes qw(time);

my $start = time();
for (1..1000) {
    my $sum = 0;
    for (my $i = 0; $i < 100; $i++) {
        $sum += $i;
    }
}
my $elapsed = time() - $start;

# Allow some variance, but catch major regressions
ok($elapsed < 0.1, "Performance regression check: $elapsed sec");
```

## Troubleshooting

### Build Issues

```bash
# Clean rebuild
make clean
make dev

# Verify all dependencies
./gradlew dependencies
```

### Test Failures

```bash
# Run single failing test with verbose output
perl dev/tools/perl_test_runner.pl -v path/to/failing.t

# Check if it's interpreter-specific
# (temporarily disable interpreter and retest)
```

### Benchmark Variability

JIT warmup and system load can cause variance. For stable measurements:

1. Close other applications
2. Run benchmark 3-5 times
3. Take median result
4. Increase iteration count if needed

Example:
```bash
for i in {1..5}; do
    echo "Run $i:"
    java ... ForLoopBenchmark | grep "Operations/sec"
done
```

## See Also

- `/dev/interpreter/OPTIMIZATION_RESULTS.md` - Phase 1 optimization results
- `/dev/prompts/phase2_optimization_analysis.md` - Phase 2 analysis
- `/docs/TESTING.md` - General testing guide for PerlOnJava
- `CLAUDE.md` - Build commands and project overview
