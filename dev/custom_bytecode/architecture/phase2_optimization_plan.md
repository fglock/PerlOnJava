# Phase 2: Interpreter Performance Optimization Plan

## Executive Summary

Phase 1 achieved **19.94M ops/sec** (2.7x slower than compiler at 54.13M ops/sec). This is within the 2-5x target, but we can do significantly better.

**Phase 2 Goal:** Push to **35-40M ops/sec** (1.5-2x slower than compiler) through:
1. **Empirical profiling** - measure actual bottlenecks with Java profiler
2. **Unboxed int registers** - eliminate allocation overhead for integer arithmetic
3. **Inline hot operations** - remove method call overhead
4. **Superinstructions** - reduce dispatch overhead by combining common patterns

**Target: 75-100% speedup** (proven achievable in JRuby, Jython, Nashorn)

---

## Context: Why Optimize Now?

- **Small codebase** (~600 lines BytecodeInterpreter.java) - easy to refactor
- **Proven benchmarks** - can measure every change
- **Clear baseline** - 19.94M ops/sec to improve from
- **Industry precedent** - similar interpreters achieved 1.5-2x slowdown

**These optimizations become exponentially harder as code grows!**

---

## Phase 2A: Empirical Profiling (Week 1)

**CRITICAL FIRST STEP**: Before implementing optimizations, **profile actual execution** to validate assumptions.

### Profiling Strategy

```bash
# 1. Create long-running benchmark (avoid startup noise)
cat > dev/interpreter/tests/profile_benchmark.pl <<'EOF'
#!/usr/bin/env perl
# Long-running loop for profiler analysis
my $sum = 0;
for (my $outer = 0; $outer < 1000; $outer++) {
    for (my $i = 0; $i < 10000; $i++) {
        $sum = $sum + $i;
    }
}
print "Sum: $sum\n";
EOF

# 2. Compile to interpreted bytecode
cat > dev/interpreter/tests/ProfileBenchmark.java <<'EOF'
// Java program that runs interpreted bytecode for profiling
public class ProfileBenchmark {
    public static void main(String[] args) throws Exception {
        InterpretedCode code = compileCode(
            "my $sum = 0; " +
            "for (my $outer = 0; $outer < 1000; $outer++) { " +
            "  for (my $i = 0; $i < 10000; $i++) { " +
            "    $sum = $sum + $i; " +
            "  } " +
            "} " +
            "$sum"
        );

        // Warm up JIT
        for (int i = 0; i < 100; i++) {
            code.apply(new RuntimeArray(), RuntimeContextType.SCALAR);
        }

        // Long run for profiling (10 seconds+)
        System.out.println("Starting profiling run...");
        long start = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            code.apply(new RuntimeArray(), RuntimeContextType.SCALAR);
        }
        long elapsed = (System.nanoTime() - start) / 1_000_000_000;
        System.out.println("Completed in " + elapsed + " seconds");
    }
}
EOF

# 3. Run with Java Flight Recorder (JFR) profiler
java -XX:+FlightRecorder \
     -XX:StartFlightRecording=duration=30s,filename=interpreter_profile.jfr \
     -cp build/libs/perlonjava-1.0-SNAPSHOT-all.jar \
     org.perlonjava.interpreter.ProfileBenchmark

# 4. Analyze with JDK Mission Control (jmc) or jfr CLI
jfr print --events jdk.ExecutionSample interpreter_profile.jfr > profile_report.txt

# 5. Identify hot spots:
# - Which methods consume most CPU time?
# - How much time in MathOperators.add()?
# - How much time in array access (registers[])?
# - How much time in switch dispatch?
# - How much time in RuntimeScalar allocation?
```

### Alternative: Async-Profiler (More Detailed)

```bash
# Download async-profiler
wget https://github.com/async-profiler/async-profiler/releases/latest/download/async-profiler-2.9-macos.tar.gz
tar xzf async-profiler-2.9-macos.tar.gz

# Run benchmark with profiler attached
java -agentpath:./async-profiler/lib/libasyncProfiler.so=start,event=cpu,file=profile.html \
     -cp build/libs/perlonjava-1.0-SNAPSHOT-all.jar \
     org.perlonjava.interpreter.ProfileBenchmark

# Open profile.html in browser - shows flame graph of CPU hotspots
```

### Expected Profile Results (Hypothesis)

Based on code analysis, we expect to see:

| Hotspot | Expected CPU % | Root Cause |
|---------|---------------|------------|
| `MathOperators.add()` | 25-30% | Virtual method call overhead |
| `RuntimeScalar.<init>` | 20-25% | Object allocation (new RuntimeScalar) |
| `BytecodeInterpreter.execute()` switch | 15-20% | Dispatch overhead |
| Array access `registers[rd]` | 10-15% | Memory access, bounds checking |
| Type checking `instanceof`, `type ==` | 10-15% | Polymorphic type system |
| Other | 10-20% | Miscellaneous |

**If profiling shows different hotspots, ADJUST PLAN ACCORDINGLY!**

### Profiling Deliverables

1. **Profile report** (`dev/interpreter/architecture/profiling_results.md`):
   - Flame graph (CPU hotspots)
   - Top 20 methods by CPU time
   - Allocation profile (top allocation sites)
   - Analysis of bottlenecks

2. **Updated optimization priorities** based on empirical data

---

## Phase 2B: Performance Bottleneck Analysis

Current interpreter execution for `$sum = $sum + $i`:

```java
// Step 1: Read operands from register array (2 array accesses + bounds checks)
RuntimeScalar left = (RuntimeScalar) registers[rs1];   // Array load + cast
RuntimeScalar right = (RuntimeScalar) registers[rs2];  // Array load + cast

// Step 2: Virtual method call (polymorphic dispatch)
registers[rd] = MathOperators.add(left, right);        // Method call overhead

// Inside MathOperators.add():
// Step 3: Type checking (branch)
if (left.type == SCALAR_INTEGER && right.type == SCALAR_INTEGER) {
    // Step 4: Unboxing (field access)
    int a = left.intValue;
    int b = right.intValue;

    // Step 5: Actual operation (finally!)
    int result = a + b;

    // Step 6: ALLOCATION (major overhead)
    return new RuntimeScalar(result);
}
```

**Problem:** 6 operations where compiled code does **1 operation**: `iload; iload; iadd; istore`

### Comparison with Compiled Code

```java
// Compiled code (EmitterVisitor generates):
visitVarInsn(ILOAD, var_sum);   // Load int from local variable
visitVarInsn(ILOAD, var_i);     // Load int from local variable
visitInsn(IADD);                // Add (single CPU instruction)
visitVarInsn(ISTORE, var_sum);  // Store int to local variable

// After JIT: Single CPU instruction (register-to-register add)
```

**Gap:** Interpreter has 10-15x more overhead per operation!

---

## Phase 2C: Tier 1 Optimizations (Weeks 2-3)

### Optimization 1A: Unboxed Int Registers

**Target: 30-50% speedup**

Add parallel integer register file to eliminate boxing overhead:

```java
public class BytecodeInterpreter {
    // Existing: boxed values (RuntimeScalar, RuntimeArray, etc.)
    private RuntimeBase[] registers;

    // NEW: unboxed integer fast path
    private int[] intRegs;           // Parallel array for primitive ints
    private byte[] regType;          // Type tag: 0=empty, 1=int, 2=boxed

    // Type constants
    private static final byte TYPE_EMPTY = 0;
    private static final byte TYPE_INT = 1;
    private static final byte TYPE_BOXED = 2;

    public static RuntimeList execute(InterpretedCode code, RuntimeArray args, int callContext) {
        // Allocate register files
        RuntimeBase[] registers = new RuntimeBase[code.maxRegisters];
        int[] intRegs = new int[code.maxRegisters];
        byte[] regType = new byte[code.maxRegisters];

        // ... initialization ...

        while (pc < bytecode.length) {
            byte opcode = bytecode[pc++];

            switch (opcode) {
                case Opcodes.LOAD_INT: {
                    int rd = bytecode[pc++] & 0xFF;
                    int value = readInt(bytecode, pc);
                    pc += 4;

                    // Store unboxed - NO ALLOCATION
                    intRegs[rd] = value;
                    regType[rd] = TYPE_INT;
                    break;
                }

                case Opcodes.ADD_SCALAR: {
                    int rd = bytecode[pc++] & 0xFF;
                    int rs1 = bytecode[pc++] & 0xFF;
                    int rs2 = bytecode[pc++] & 0xFF;

                    // FAST PATH: both operands are unboxed ints
                    if (regType[rs1] == TYPE_INT && regType[rs2] == TYPE_INT) {
                        intRegs[rd] = intRegs[rs1] + intRegs[rs2];  // NO ALLOCATION!
                        regType[rd] = TYPE_INT;
                        break;
                    }

                    // SLOW PATH: fallback to boxed RuntimeScalar
                    RuntimeScalar left = getScalar(rs1, registers, intRegs, regType);
                    RuntimeScalar right = getScalar(rs2, registers, intRegs, regType);
                    registers[rd] = MathOperators.add(left, right);
                    regType[rd] = TYPE_BOXED;
                    break;
                }

                case Opcodes.MOVE: {
                    int dest = bytecode[pc++] & 0xFF;
                    int src = bytecode[pc++] & 0xFF;

                    // Copy both value and type
                    registers[dest] = registers[src];
                    intRegs[dest] = intRegs[src];
                    regType[dest] = regType[src];
                    break;
                }

                // ... more opcodes ...
            }
        }
    }

    /**
     * Helper: Get value as RuntimeScalar (boxing intRegs if needed).
     */
    private static RuntimeScalar getScalar(int reg, RuntimeBase[] registers,
                                          int[] intRegs, byte[] regType) {
        if (regType[reg] == TYPE_INT) {
            // Box on demand
            return RuntimeScalarCache.getScalarInt(intRegs[reg]);
        } else {
            return (RuntimeScalar) registers[reg];
        }
    }
}
```

**Impact:**
- Integer arithmetic: `intRegs[rd] = intRegs[rs1] + intRegs[rs2]` (no allocation!)
- Benchmark loop is pure integer math → HUGE WIN
- Proven 30-50% speedup in JRuby, Jython, Nashorn

**Opcodes to Update:**
1. **Arithmetic** (6 opcodes): ADD_SCALAR, SUB_SCALAR, MUL_SCALAR, DIV_SCALAR, MOD_SCALAR, NEG_SCALAR
2. **Comparison** (5 opcodes): EQ_NUM, LT_NUM, GT_NUM, COMPARE_NUM, NE_NUM
3. **Register ops** (2 opcodes): LOAD_INT, MOVE
4. **Control flow** (2 opcodes): GOTO_IF_FALSE, GOTO_IF_TRUE (check int == 0)

**Boundary Handling:**
- **Global variables**: Box when storing, unbox when loading
- **Subroutine calls**: Box arguments, unbox returns if possible
- **Array/hash access**: Box keys/values

**Complexity:** Medium (2-3 days per opcode category)

### Optimization 1B: Inline Hot Operations

**Target: 10-20% additional speedup**

Remove virtual method call overhead by inlining operator fast paths:

```java
case Opcodes.ADD_SCALAR: {
    int rd = bytecode[pc++] & 0xFF;
    int rs1 = bytecode[pc++] & 0xFF;
    int rs2 = bytecode[pc++] & 0xFF;

    RuntimeScalar left = (RuntimeScalar) registers[rs1];
    RuntimeScalar right = (RuntimeScalar) registers[rs2];

    // INLINE fast path from MathOperators.add()
    if (left.type == InternalIndex.SCALAR_INTEGER &&
        right.type == InternalIndex.SCALAR_INTEGER) {
        // Integer addition (fast path)
        registers[rd] = new RuntimeScalar(left.intValue + right.intValue);
    } else if (left.type == InternalIndex.SCALAR_DOUBLE ||
               right.type == InternalIndex.SCALAR_DOUBLE) {
        // Float addition (medium path)
        registers[rd] = new RuntimeScalar(left.getDouble() + right.getDouble());
    } else {
        // String/complex types (fallback to operator)
        registers[rd] = MathOperators.add(left, right);
    }
}
```

**Impact:**
- Eliminates virtual method call (saves 5-10 CPU cycles)
- JIT can inline better (simpler control flow)
- Still handles all Perl semantics (fallback preserved)
- 10-20% speedup on operator-heavy code

**Operators to Inline:**
1. **Arithmetic**: add, subtract, multiply (hot paths from MathOperators.java)
2. **Comparison**: equalTo, lessThan, greaterThan (hot paths from CompareOperators.java)
3. **String**: concat (hot path from StringOperators.java)
4. **Logical**: NOT (trivial inline)

**Complexity:** Low (1 hour per operator, just copy code)

---

## Phase 2D: Tier 2 Optimizations (Weeks 4-5)

### Optimization 2A: Superinstructions

**Target: 20-30% additional speedup**

Combine common opcode sequences into single instructions:

```java
// Current bytecode for `$i++`:
LOAD_LOCAL 0        // Load $i (dispatch 1)
LOAD_INT 1          // Load 1 (dispatch 2)
ADD_SCALAR 2, 0, 1  // Add (dispatch 3)
MOVE 0, 2           // Store back (dispatch 4)
// Total: 4 dispatches

// With superinstruction:
INC_LOCAL 0         // Increment local[0] (dispatch 1)
// Total: 1 dispatch (4x improvement!)
```

**Implementation:**

1. **Profile to find hot patterns** (see Phase 2A profiling):
   - Run benchmark with opcode sequence logging
   - Identify patterns that occur > 5% of the time

2. **Expected hot patterns**:
   - `LOAD_INT + ADD_SCALAR` → `ADD_IMM` (add immediate)
   - `ADD_SCALAR + MOVE` → `ADD_STORE` (add and store)
   - `LOAD_LOCAL + LOAD_LOCAL + ADD_SCALAR + MOVE` → `ADD_LOCAL` (locals addition)
   - `LOAD_INT 1 + ADD_SCALAR + MOVE` → `INC_LOCAL` (increment)
   - `LOAD_INT -1 + ADD_SCALAR + MOVE` → `DEC_LOCAL` (decrement)

3. **Add superinstruction opcodes** (75-90):
   ```java
   // Opcodes.java
   public static final byte INC_LOCAL = 75;      // local[rd]++
   public static final byte DEC_LOCAL = 76;      // local[rd]--
   public static final byte ADD_LOCAL_IMM = 77;  // local[rd] += imm
   public static final byte ADD_LOCAL_LOCAL = 78; // local[rd] = local[rs1] + local[rs2]
   // ... 10-15 more superinstructions based on profiling
   ```

4. **Implement in interpreter**:
   ```java
   case Opcodes.INC_LOCAL: {
       int rd = bytecode[pc++] & 0xFF;

       // Fast path: unboxed int
       if (regType[rd] == TYPE_INT) {
           intRegs[rd]++;
       } else {
           // Slow path: boxed value
           RuntimeScalar val = (RuntimeScalar) registers[rd];
           registers[rd] = new RuntimeScalar(val.getInt() + 1);
       }
       break;
   }
   ```

5. **Update compiler** (peephole optimization):
   ```java
   // BytecodeCompiler.java
   private void optimizeBytecode() {
       // Pattern match and replace with superinstructions
       for (int i = 0; i < bytecode.size() - 3; i++) {
           if (isPattern_LoadInt1_AddScalar_Move(i)) {
               replaceCith_IncLocal(i);
               i += 3;  // Skip replaced instructions
           }
       }
   }
   ```

**Impact:**
- 3-4x fewer dispatches for common patterns
- Better CPU branch prediction (fewer switch cases)
- 20-30% additional speedup

**Complexity:** Medium (2-3 weeks)

---

## Phase 2E: Implementation Schedule

### Week 1: Profiling & Analysis
- [ ] Create ProfileBenchmark.java (long-running loop)
- [ ] Run with Java Flight Recorder (JFR)
- [ ] Run with async-profiler (flame graphs)
- [ ] Analyze CPU hotspots
- [ ] Document profiling results
- [ ] **DECISION GATE**: Validate optimization priorities based on empirical data

### Week 2-3: Tier 1 Implementation
- [ ] Day 1-2: Add int[] intRegs + byte[] regType to BytecodeInterpreter
- [ ] Day 3-4: Update LOAD_INT, MOVE opcodes
- [ ] Day 5-7: Update arithmetic opcodes (ADD, SUB, MUL, DIV, MOD, NEG)
- [ ] Day 8-9: Update comparison opcodes (EQ, LT, GT, COMPARE)
- [ ] Day 10: Inline hot operators (copy fast paths)
- [ ] Day 11-12: Handle boundaries (globals, calls, arrays)
- [ ] Day 13-14: Test and benchmark
- [ ] **TARGET**: 26-30M ops/sec (30-50% improvement)

### Week 4-5: Tier 2 Implementation
- [ ] Day 1-2: Add opcode sequence profiling
- [ ] Day 3-4: Identify top 10 hot patterns
- [ ] Day 5-7: Design and implement superinstructions
- [ ] Day 8-10: Update BytecodeCompiler (pattern matching)
- [ ] Day 11-12: Test and benchmark
- [ ] **TARGET**: 35-40M ops/sec (75-100% total improvement)

### Week 6: Documentation & Polish
- [ ] Update OPTIMIZATION_RESULTS.md with Phase 2 results
- [ ] Add optimization guide for contributors
- [ ] Create performance regression tests
- [ ] Clean up dead code

---

## Verification & Testing

### Benchmarks

```bash
# Baseline (Phase 1)
java -cp build/libs/perlonjava-1.0-SNAPSHOT-all.jar \
     org.perlonjava.interpreter.ForLoopBenchmark
# Expected: 19.94M ops/sec

# After Tier 1 (unboxed ints + inline)
# Expected: 26-30M ops/sec (30-50% improvement)

# After Tier 2 (superinstructions)
# Expected: 35-40M ops/sec (75-100% total improvement)
```

### Unit Tests

```bash
# All existing tests must pass
make test-unit

# Performance regression tests
perl dev/tools/perl_test_runner.pl src/test/resources/unit/interpreter/performance_regression.t
```

### Edge Cases to Test

```perl
# Mixed int/float operations
my $x = 5;
my $y = 3.14;
my $z = $x + $y;  # Should still work (fallback to boxed)

# String concatenation with numbers
my $s = "value: " . 42;  # Should still work

# Global variables
$global_int = 10;
my $local = $global_int + 5;  # Should box/unbox correctly

# Subroutine calls
sub add { $_[0] + $_[1] }
my $result = add(3, 4);  # Should box/unbox args/returns
```

---

## Critical Files

### Files to Modify

1. **src/main/java/org/perlonjava/interpreter/BytecodeInterpreter.java**
   - Add `int[] intRegs`, `byte[] regType` fields
   - Update all arithmetic/comparison opcodes with fast paths
   - Inline hot operator methods
   - Implement superinstructions (Tier 2)

2. **src/main/java/org/perlonjava/interpreter/Opcodes.java**
   - Document int register conventions
   - Add superinstruction opcodes (75-90)

3. **src/main/java/org/perlonjava/interpreter/BytecodeCompiler.java**
   - Pattern detection for superinstructions (Tier 2)
   - Emit superinstructions when patterns match

4. **dev/interpreter/OPTIMIZATION_RESULTS.md**
   - Add Phase 2 results section
   - Document profiling findings

5. **dev/interpreter/tests/ProfileBenchmark.java** (NEW)
   - Long-running benchmark for profiling

6. **dev/interpreter/architecture/profiling_results.md** (NEW)
   - Profiling analysis and findings

### Files to Reference

- **src/main/java/org/perlonjava/operators/MathOperators.java** - Copy fast paths for inlining
- **src/main/java/org/perlonjava/operators/CompareOperators.java** - Copy fast paths for inlining
- **src/main/java/org/perlonjava/runtime/RuntimeScalar.java** - Understand type system and caching

---

## Expected Outcome

**Phase 2 Performance Target:**
- **Interpreter**: 35-40M ops/sec (75-100% improvement from Phase 1)
- **Compiler**: ~55M ops/sec (stable)
- **Gap**: 1.5-2x slower (excellent for interpreter!)

**Comparison with Industry:**
- JRuby interpreter: ~1.5x slower than compiled (after 10+ years of optimization)
- Jython interpreter: ~2x slower than compiled
- PerlOnJava target: **1.5-2x slower** (competitive!)

This would make the interpreter **competitive with mature JVM interpreters** while maintaining perfect Perl semantics.

---

## Risk Mitigation

1. **Maintain correctness**: Always keep slow path fallback for edge cases
2. **Incremental changes**: Benchmark after each opcode update
3. **Comprehensive testing**: Run full test suite after each change
4. **Rollback plan**: Git branch for each optimization tier
5. **Profile-driven**: Let empirical data guide optimization priorities (Phase 2A!)

---

## Success Criteria

- [ ] **Profiling completed** - empirical data collected and analyzed
- [ ] **Tier 1 speedup**: 30-50% improvement (26-30M ops/sec)
- [ ] **Tier 2 speedup**: Additional 20-30% improvement (35-40M ops/sec)
- [ ] **All unit tests pass** - no regressions
- [ ] **Edge cases work** - mixed types, globals, calls
- [ ] **Documentation updated** - profiling results, optimization guide

**Stretch Goal**: If profiling reveals different bottlenecks, pivot to optimize those instead!
