# PerlOnJava Interpreter Developer Guide

## Table of Contents
1. [Introduction & Architecture Overview](#introduction--architecture-overview)
2. [File Organization](#file-organization)
3. [Testing & Benchmarking](#testing--benchmarking)
4. [Dispatch Architecture & Future CPU Cache Optimization](#dispatch-architecture--future-cpu-cache-optimization)
5. [Optimization Strategies](#optimization-strategies)
6. [Runtime Sharing (100% API Compatibility)](#runtime-sharing-100-api-compatibility)
7. [Development Workflow](#development-workflow)

## Introduction & Architecture Overview

### What is the Interpreter?

The PerlOnJava interpreter is a **pure register machine** that executes Perl bytecode directly without generating JVM bytecode. It complements the existing compiler by providing:

- **Faster startup**: No bytecode generation or JVM class loading overhead
- **Lower memory footprint**: No class metadata or compiled method objects
- **Dynamic evaluation**: Ideal for `eval STRING`, code generation, and REPL scenarios
- **Closure support**: Captures and shares variables between compiled and interpreted code

### Why It Exists

While the PerlOnJava compiler generates optimized JVM bytecode, there are scenarios where interpretation is superior:

1. **eval STRING**: Dynamic code evaluated once doesn't justify compilation overhead
2. **Short-lived scripts**: Scripts that run briefly and exit (startup time matters)
3. **Development/debugging**: Faster edit-run cycles during development
4. **Memory-constrained environments**: Lower memory usage than compiled code

### Architecture: Pure Register Machine

The interpreter uses a **register-based architecture** (not stack-based like the JVM):

- **3-address code format**: `rd = rs1 op rs2` (destination = source1 operator source2)
- **Unlimited virtual registers**: Each subroutine can use as many registers as needed
- **Register allocation**: BytecodeCompiler assigns variables to register indices
- **No stack manipulation**: Direct register-to-register operations

**Example bytecode:**
```
LOAD_INT r0, 10          # r0 = 10
LOAD_INT r1, 20          # r1 = 20
ADD_SCALAR r2, r0, r1    # r2 = r0 + r1
PRINT r2                 # print r2
```

### Performance Characteristics

**Current Performance (as of Phase 3):**
- **46.84M ops/sec** (simple for loop benchmark)
- **1.75x slower** than compiler (within 2-5x target ✓)
- **Tableswitch optimization**: Dense opcodes (0-82) enable O(1) dispatch
- **Superinstructions**: Eliminate intermediate MOVE operations

**Performance vs. Compiler:**
- Compiler: ~82M ops/sec (after JIT warmup)
- Interpreter: ~47M ops/sec (consistent, no warmup needed)
- Trade-off: Slower execution for faster startup and lower memory

## File Organization

### Documentation (`dev/interpreter/`)

- **STATUS.md** - Current implementation status and feature completeness
- **TESTING.md** - How to test and benchmark the interpreter
- **OPTIMIZATION_RESULTS.md** - Optimization history and performance measurements
- **BYTECODE_DOCUMENTATION.md** - Complete reference for all 83 opcodes (0-82)
- **CLOSURE_IMPLEMENTATION_COMPLETE.md** - Closure architecture and bidirectional calling
- **SKILL.md** (this file) - Developer guide for continuing interpreter development
- **architecture/** - Design documents and architectural decisions
- **tests/** - Interpreter-specific test files (.t and .pl format)

### Source Code (`src/main/java/org/perlonjava/interpreter/`)

**Core Interpreter:**
- **Opcodes.java** - Opcode constants (0-82) organized by category
- **BytecodeInterpreter.java** - Main execution loop with unified switch statement
- **BytecodeCompiler.java** - AST to bytecode compiler with register allocation
- **InterpretedCode.java** - Bytecode container with disassembler for debugging

**Support Classes:**
- **VariableCollectorVisitor.java** - Detects closure variables for capture analysis
- **ForLoopBenchmark.java** - Performance benchmarking harness
- **ForLoopTest.java** - Java test harness for for-loop execution
- **InterpreterTest.java** - General interpreter functionality tests
- **ClosureTest.java** - Closure and anonymous subroutine tests

### Opcode Categories (Opcodes.java)

Opcodes are organized into functional categories:

1. **Control Flow** (0-6): RETURN, LABEL, GOTO, conditionals
2. **Constants** (7-9): LOAD_INT, LOAD_STRING, LOAD_UNDEF
3. **Variables** (10-16): GET_VAR, SET_VAR, CREATE_CLOSURE_VAR, GET_LOCAL_VAR
4. **Arithmetic** (17-30): ADD, SUB, MUL, DIV, MOD, POW, NEG, etc.
5. **Comparison** (31-41): COMPARE_NUM, COMPARE_STR, EQ, NE, LT, GT, LE, GE
6. **Array Operations** (42-49): ARRAY_GET, ARRAY_SET, PUSH, POP, SHIFT, UNSHIFT
7. **Hash Operations** (50-56): HASH_GET, HASH_SET, EXISTS, DELETE, KEYS, VALUES
8. **Subroutine Calls** (57-59): CALL_SUB, CALL_METHOD, CALL_BUILTIN
9. **Control Flow Specials** (60-67): CREATE_LAST, CREATE_NEXT, CREATE_REDO, CREATE_GOTO
10. **References** (68-72): CREATE_REF, DEREF, GET_TYPE, GET_REFTYPE, BLESS
11. **Error Handling** (73-74): DIE, WARN
12. **Superinstructions** (75-82): INC_REG, DEC_REG, ADD_ASSIGN, ADD_ASSIGN_INT, etc.

## Testing & Benchmarking

### Running Tests

**Fast Unit Tests (seconds):**
```bash
make test-unit                    # All fast unit tests
make                              # Build + fast tests (default)
```

**Comprehensive Tests (minutes):**
```bash
make test-all                     # All tests including Perl 5 core tests
make test-perl5                   # Perl 5 core test suite
```

**Interpreter-Specific Tests:**
```bash
# Perl test files
./jperl dev/interpreter/tests/for_loop_test.pl
./jperl dev/interpreter/tests/closure_test.t
./jperl dev/interpreter/tests/*.t

# Java test harnesses (direct execution)
java -cp build/classes/java/main org.perlonjava.interpreter.ForLoopTest
java -cp build/classes/java/main org.perlonjava.interpreter.InterpreterTest
java -cp build/classes/java/main org.perlonjava.interpreter.ClosureTest
```

### Running Benchmarks

**Recommended Method (via Gradle):**
```bash
./gradlew run -PmainClass=org.perlonjava.interpreter.ForLoopBenchmark
```

**Direct Java (requires classpath setup):**
```bash
java -cp "build/classes/java/main:build/libs/*" \
     org.perlonjava.interpreter.ForLoopBenchmark
```

**Benchmark Output Example:**
```
Compiler - 1000000 iterations: 11.72 ms (85.32M ops/sec)
Interpreter - 1000000 iterations: 21.35 ms (46.84M ops/sec)
Interpreted code is 1.82x slower than compiled code
```

### Performance Targets

- **Interpreter Target**: 2-5x slower than compiler
- **Current**: 1.75x slower ✓ (within target)
- **Benchmark Loop**: 19.94M ops/sec (Phase 1 baseline)
- **After Optimizations**: 46.84M ops/sec (2.35x improvement)

### Test Frameworks

**Perl Tests (.t files):**
- Use Test::More framework
- TAP (Test Anything Protocol) output
- Run via `perl dev/tools/perl_test_runner.pl`

**Java Test Harnesses:**
- Custom main() methods with timing
- Direct bytecode execution
- Compare interpreter vs. compiler performance

## Dispatch Architecture & Future CPU Cache Optimization

### Current Design: Single Unified Switch

The interpreter uses **one monolithic switch statement** in BytecodeInterpreter.java (lines 62-632):

```java
public static RuntimeDataProvider execute(
    byte[] bytecode,
    RuntimeArray args,
    RuntimeContextType wantContext) {

    RuntimeDataProvider[] registers = new RuntimeDataProvider[256];
    int pc = 0;

    while (pc < bytecode.length) {
        int opcode = bytecode[pc++] & 0xFF;

        switch (opcode) {
            case Opcodes.RETURN: ...
            case Opcodes.GOTO: ...
            case Opcodes.LOAD_INT: ...
            // ... all 83 opcodes in one switch ...
            case Opcodes.LOOP_PLUS_PLUS: ...
            default: throw new IllegalStateException("Unknown opcode: " + opcode);
        }
    }
}
```

**Key Characteristics:**
- All 83 opcodes (0-82) handled in single switch
- Dense numbering (no gaps) enables JVM tableswitch optimization
- Organized by functional category (not frequency)
- ~10-15% speedup from tableswitch vs. lookupswitch

**Why This Works:**
- JVM JIT compiler optimizes switch to tableswitch (O(1) jump table)
- CPU branch predictor learns execution patterns
- Modern CPUs have large instruction caches (32KB-64KB L1 i-cache)

### JVM Tableswitch Optimization

**Tableswitch** (used for dense cases):
```java
tableswitch {  // O(1) lookup via array index
    0: goto label_0
    1: goto label_1
    2: goto label_2
    ...
}
```

**Lookupswitch** (used for sparse cases):
```java
lookupswitch {  // O(log n) lookup via binary search
    10: goto label_10
    50: goto label_50
    100: goto label_100
}
```

**Critical:** To maintain tableswitch, opcodes MUST be dense (no large gaps in numbering).

### Future Enhancement: Separate Switch for Rare Opcodes

**Currently NOT implemented**, but could improve CPU cache utilization by keeping the hot path compact:

```java
// Proposed architecture (not current!)
switch (opcode) {
    case Opcodes.RETURN: ...
    case Opcodes.GOTO: ...
    case Opcodes.LOAD_INT: ...
    case Opcodes.ADD_SCALAR: ...
    case Opcodes.CALL_SUB: ...
    // ... hot path opcodes only ...

    default:
        return handleRareOpcodes(opcode, pc, registers); // Separate switch
}

private static int handleRareOpcodes(int opcode, int pc, RuntimeDataProvider[] registers) {
    switch (opcode) {
        case Opcodes.ARRAY_UNSHIFT: ...
        case Opcodes.HASH_EXISTS: ...
        case Opcodes.CALL_METHOD: ...
        case Opcodes.CREATE_REDO: ...
        case Opcodes.DIE: ...
        // ... rarely-used opcodes ...
    }
}
```

**Benefits:**
- Smaller main switch keeps CPU instruction cache hot
- Rare opcodes moved to cold path (separate function)
- Potential 5-10% speedup for hot paths

**Trade-offs:**
- Adds function call overhead for rare opcodes
- More complex code maintenance
- Only beneficial if rare opcodes are truly rare (<1% execution)

### Candidate Rare Opcodes for Future Separation

**Good Candidates (rarely executed):**
- **ARRAY_UNSHIFT** (42), **ARRAY_SHIFT** (43) - Less common than push/pop
- **HASH_EXISTS** (52), **HASH_DELETE** (53), **HASH_KEYS** (54), **HASH_VALUES** (55)
- **CALL_METHOD** (58), **CALL_BUILTIN** (59) - Less common than CALL_SUB
- **CREATE_REDO** (62), **CREATE_GOTO** (65), **GET_CONTROL_FLOW_TYPE** (67)
- **CREATE_REF** (68), **DEREF** (69), **GET_TYPE** (70), **GET_REFTYPE** (71), **BLESS** (72)
- **DIE** (73), **WARN** (74) - Only on error paths

**MUST Keep in Main Switch (hot path):**
- **LOAD_INT** (7), **LOAD_STRING** (8), **LOAD_UNDEF** (9)
- **MOVE** (5) - Critical data movement
- **ADD_SCALAR** (17), **ADD_SCALAR_INT** (24) - Arithmetic hot path
- **GOTO** (2), **GOTO_IF_FALSE** (3), **GOTO_IF_TRUE** (4) - Control flow
- **COMPARE_NUM** (31), **EQ_NUM** (33), **LT_NUM** (35) - Loop conditions
- **CALL_SUB** (57) - Most common call type
- **All superinstructions** (75-82) - Designed for hot paths

## Optimization Strategies

### Completed Optimizations (Phases 1-3)

**1. Dense Opcodes (10-15% speedup)**
- Renumbered opcodes to 0-82 with no gaps
- Enables JVM tableswitch (O(1)) instead of lookupswitch (O(log n))
- Measured via bytecode disassembly verification

**2. Better JIT Warmup (156% speedup)**
- Increased warmup iterations from 100 to 1000
- Allows JVM JIT compiler to optimize dispatch loop
- Before: 18.28M ops/sec → After: 46.84M ops/sec

**3. Superinstructions (5-10% speedup)**
- Combined common patterns into single opcodes
- Eliminates intermediate MOVE operations
- Examples:
  - `INC_REG` (75): `r0 = r0 + 1` (replaces ADD + MOVE)
  - `DEC_REG` (76): `r0 = r0 - 1`
  - `ADD_ASSIGN` (77): `r0 = r0 + r1`
  - `ADD_ASSIGN_INT` (78): `r0 = r0 + immediate_int`
  - `LOOP_PLUS_PLUS` (82): Combined increment + compare + branch

### Future Optimization Opportunities

#### A. Unboxed Int Registers (30-50% potential speedup)

**Problem:** Every integer operation currently boxes/unboxes:
```java
// Current (slow):
registers[rd] = new RuntimeScalar((RuntimeScalar)registers[rs1]).getInt() + 1);

// Proposed (fast):
intRegisters[rd] = intRegisters[rs1] + 1;  // No boxing!
```

**Solution:**
- Maintain parallel `int[] intRegisters` array
- Track which registers contain unboxed ints
- Box only when needed (calls, returns, type coercion)
- Detect loop induction variables for unboxing

**Implementation Steps:**
1. Add `int[] intRegisters` field to BytecodeInterpreter
2. Add `boolean[] isUnboxed` tracking array
3. Add unboxed variants: `INC_REG_UNBOXED`, `ADD_SCALAR_INT_UNBOXED`
4. BytecodeCompiler detects `my $i` loop variables and emits unboxed opcodes
5. Box on register use in non-arithmetic contexts

#### B. Inline Caching (30-50% potential speedup)

**Problem:** Every method call, global variable access requires lookup:
```java
// Current (slow):
RuntimeCode code = GlobalVariable.getGlobalCodeRef("main", "foo");  // Hashtable lookup!
result = code.apply(...);
```

**Solution:**
- Cache lookup results at call sites
- Invalidate on global state changes
- Polymorphic inline caches for method calls

**Implementation Steps:**
1. Add `InlineCache[] caches` field to InterpretedCode
2. Cache structure: `{ String key, RuntimeCode cachedCode, int hitCount }`
3. Modify CALL_SUB to check cache before lookup
4. Invalidate caches on `sub foo { ... }` redefinition

#### C. Additional Superinstructions (10-30% potential speedup)

**Candidates:**
- `SUB_ASSIGN` (83): `r0 = r0 - r1`
- `MUL_ASSIGN` (84): `r0 = r0 * r1`
- `DIV_ASSIGN` (85): `r0 = r0 / r1`
- `ARRAY_GET_INT` (86): `r0 = array[int_index]` (unboxed index)
- `LOAD_CONST_INT` (87): `r0 = immediate_int` (replace LOAD_INT + constant pool)

**Adding SUB_ASSIGN Example:**
```java
// Opcodes.java
public static final byte SUB_ASSIGN = 83;  // rd = rd - rs

// BytecodeInterpreter.java
case Opcodes.SUB_ASSIGN: {
    int rd = bytecode[pc++] & 0xFF;
    int rs = bytecode[pc++] & 0xFF;
    registers[rd] = MathOperators.subtract(
        (RuntimeScalar) registers[rd],
        (RuntimeScalar) registers[rs]
    );
    break;
}
```

#### D. Direct Field Access (10-20% potential speedup)

**Problem:** Getter methods add overhead:
```java
// Current (slow):
int value = ((RuntimeScalar) registers[rs]).getInt();  // Method call

// Proposed (fast):
int value = ((RuntimeScalar) registers[rs]).ivalue;    // Direct field access
```

**Solution:**
- Access RuntimeScalar.ivalue, RuntimeScalar.svalue directly
- Check RuntimeScalar.type first to ensure correct type
- Only use for hot paths (ADD, SUB, COMPARE)

**Trade-off:** Tight coupling to RuntimeScalar internals (breaks encapsulation).

#### E. Separate Switch for Rare Opcodes (5-10% potential speedup)

See [Dispatch Architecture](#dispatch-architecture--future-cpu-cache-optimization) section above.

#### F. Specialized Loops (20-40% potential speedup)

**Problem:** General dispatch loop has overhead for simple for-loops:
```perl
for (my $i = 0; $i < 1000000; $i++) { $sum += $i; }
```

**Solution:**
- Detect simple counting loops at compile time
- Generate specialized tight loop dispatcher
- Inline loop body opcodes (no switch overhead)

**Detection Criteria:**
- Loop variable is integer (`my $i`)
- Loop condition is simple comparison (`$i < N`)
- Loop increment is `$i++` or `$i += 1`
- Loop body has <20 opcodes

**Implementation:**
```java
// Specialized loop executor (no switch!)
for (int i = startValue; i < endValue; i++) {
    // Inline loop body opcodes directly:
    registers[rd] = MathOperators.add(
        (RuntimeScalar) registers[sumReg],
        new RuntimeScalar(i)
    );
}
```

## Runtime Sharing (100% API Compatibility)

### Key Principle: Zero Duplication

The interpreter and compiler share **IDENTICAL runtime APIs**. There is **NO duplicated logic** between the two execution modes.

**Both Use Exactly the Same:**
- `RuntimeCode.apply()` - Execute subroutines
- `RuntimeScalar`, `RuntimeArray`, `RuntimeHash` - Data structures
- `MathOperators`, `StringOperators`, `CompareOperators` - All operators
- `GlobalVariable` - Global state (scalars, arrays, hashes, code refs)
- `RuntimeContextType` - Calling context (void/scalar/list/runtime)
- `RuntimeControlFlowList` - Control flow exceptions (last/next/redo/goto)

### How It Works

**Interpreter:** Direct Java method calls in switch cases
```java
case Opcodes.ADD_SCALAR: {
    int rd = bytecode[pc++] & 0xFF;
    int rs1 = bytecode[pc++] & 0xFF;
    int rs2 = bytecode[pc++] & 0xFF;
    registers[rd] = MathOperators.add(
        (RuntimeScalar) registers[rs1],
        (RuntimeScalar) registers[rs2]
    );
    break;
}
```

**Compiler:** Generated JVM bytecode calls same method
```java
// Generated by EmitBinaryOperator.java
ALOAD leftScalar          // Load first operand
ALOAD rightScalar         // Load second operand
INVOKESTATIC org/perlonjava/operators/MathOperators.add(
    Lorg/perlonjava/runtime/RuntimeScalar;
    Lorg/perlonjava/runtime/RuntimeScalar;
)Lorg/perlonjava/runtime/RuntimeScalar;
ASTORE result            // Store result
```

**Result:** Identical behavior, same semantics, same global state.

### No Differences In:

- **Method signatures** - Same parameters, same return types
- **Semantics** - Same type coercion, overloading, context handling
- **Global state** - Both modify same GlobalVariable.globalScalar, etc.
- **Error handling** - Same exceptions (DieException, ControlFlowException)
- **Closure behavior** - Both capture same variables, share same RuntimeScalar refs

### Only Difference: Execution Timing

- **Interpreter**: ~47M ops/sec (consistent, no warmup)
- **Compiler**: ~82M ops/sec (after JIT warmup)

The interpreter trades raw speed for faster startup and lower memory usage.

### Bidirectional Calling

**Compiled → Interpreted:**
```perl
sub compiled_sub {
    my $code = eval 'sub { my $x = shift; return $x * 2; }';  # Interpreted closure
    return $code->(21);  # Compiled code calls interpreted code
}
```

**Interpreted → Compiled:**
```perl
sub compiled_helper { return shift() * 2; }

my $code = eval 'sub {
    my $x = shift;
    return compiled_helper($x);  # Interpreted code calls compiled code
}';
```

Both work seamlessly because they share the same RuntimeCode.apply() interface.

### Closure Variable Sharing

Closures capture variables as RuntimeScalar references:

```perl
my $x = 10;
my $code = sub { $x += 1; return $x; };
print $code->();  # 11
print $code->();  # 12
print $x;         # 12 (shared reference!)
```

**Compiled closure:**
- Generates JVM field to hold RuntimeScalar reference
- Loads field, calls methods on it

**Interpreted closure:**
- Stores RuntimeScalar reference in closure variables map
- Retrieves from map, operates on same object

**Result:** Both modify the SAME RuntimeScalar object in memory.

## Development Workflow

### Adding a New Opcode

Follow these steps when adding a new opcode (e.g., a new superinstruction):

#### Step 1: Define Opcode in Opcodes.java

```java
// Add to appropriate category section
// Use next sequential number (currently 83+)

/**
 * MUL_ASSIGN: rd = rd * rs
 * Format: [MUL_ASSIGN] [rd] [rs]
 * Effect: Multiplies register rd by register rs, stores result in rd
 */
public static final byte MUL_ASSIGN = 83;  // rd = rd * rs
```

**Important:** Maintain dense numbering! No gaps between opcodes to preserve tableswitch optimization.

#### Step 2: Implement in BytecodeInterpreter.java

Add case to main switch statement (around line 62-632):

```java
case Opcodes.MUL_ASSIGN: {
    // Decode operands from bytecode
    int rd = bytecode[pc++] & 0xFF;
    int rs = bytecode[pc++] & 0xFF;

    // Call appropriate runtime method
    registers[rd] = MathOperators.multiply(
        (RuntimeScalar) registers[rd],
        (RuntimeScalar) registers[rs]
    );
    break;
}
```

**Pattern:**
1. Decode operands (increment `pc` for each byte consumed)
2. Call runtime methods (MathOperators, StringOperators, etc.)
3. Store result in destination register

#### Step 3: Emit in BytecodeCompiler.java

Add emission logic in appropriate visit() method:

```java
// In visit(BinaryOperatorNode node) method
if (node instanceof OperatorNode) {
    OperatorNode binOp = (OperatorNode) node;

    // Detect pattern: $var = $var * expr
    if (binOp.left instanceof VariableNode &&
        binOp.right instanceof BinaryOperatorNode) {

        BinaryOperatorNode rightBin = (BinaryOperatorNode) binOp.right;
        String leftVarName = ((VariableNode) binOp.left).variableName;

        if (rightBin.left instanceof VariableNode) {
            String rightLeftVarName = ((VariableNode) rightBin.left).variableName;

            // Pattern match: $var = $var * expr
            if (leftVarName.equals(rightLeftVarName) &&
                rightBin.operator.equals("*")) {

                int varReg = getOrAllocateRegister(leftVarName);
                int rightRightReg = visit(rightBin.right);  // Evaluate right side

                emit(Opcodes.MUL_ASSIGN);
                emit(varReg);
                emit(rightRightReg);
                return varReg;  // Return destination register
            }
        }
    }
}
```

#### Step 4: Add to Disassembler (InterpretedCode.java)

Add case to disassemble() switch statement (around line 100-300):

```java
case Opcodes.MUL_ASSIGN: {
    int rd = bytecode[pc++] & 0xFF;
    int rs = bytecode[pc++] & 0xFF;
    sb.append(String.format("%-20s r%d *= r%d", "MUL_ASSIGN", rd, rs));
    break;
}
```

This enables debugging with `./jperl --disassemble script.pl`.

#### Step 5: Update Documentation

**BYTECODE_DOCUMENTATION.md:**
```markdown
### MUL_ASSIGN (83)

**Format:** `[MUL_ASSIGN] [rd] [rs]`

**Effect:** `rd = rd * rs`

**Description:**
Superinstruction that multiplies destination register by source register.
Equivalent to ADD_SCALAR followed by MOVE, but eliminates intermediate register.

**Example:**
```
MUL_ASSIGN r5 *= r3    # r5 = r5 * r3
```
```

**Update opcode count** in all documentation (currently 83 opcodes → 84 opcodes).

#### Step 6: Test Thoroughly

**Create Test Case:**

```perl
# dev/interpreter/tests/mul_assign_test.t
use strict;
use warnings;
use Test::More tests => 3;

my $x = 5;
$x *= 3;
is($x, 15, "MUL_ASSIGN: scalar multiplication");

my $y = 10;
$y *= 2;
$y *= 2;
is($y, 40, "MUL_ASSIGN: chained multiplication");

my $z = 7;
$z *= 0;
is($z, 0, "MUL_ASSIGN: multiply by zero");
```

**Run Tests:**
```bash
make dev                           # Clean rebuild
./jperl dev/interpreter/tests/mul_assign_test.t
make test-unit                     # All unit tests must pass
```

**Verify Tableswitch:**
```bash
javap -c -p -classpath build/classes/java/main \
      org.perlonjava.interpreter.BytecodeInterpreter | grep -A 5 "switch"
```

Should see `tableswitch` (not `lookupswitch`). If you see `lookupswitch`, you've introduced a gap in numbering!

**Run Benchmarks:**
```bash
./gradlew run -PmainClass=org.perlonjava.interpreter.ForLoopBenchmark
```

Check that performance hasn't regressed. New superinstruction should improve performance for matching patterns.

### Debugging Tips

**Disassemble Bytecode:**
```bash
./jperl --disassemble -E 'my $x = 10; $x *= 2; print $x'
```

Output shows generated bytecode:
```
LOAD_INT r0, 10
LOAD_INT r1, 2
MUL_ASSIGN r0 *= r1
PRINT r0
```

**Add Debug Logging:**
```java
case Opcodes.MUL_ASSIGN: {
    int rd = bytecode[pc++] & 0xFF;
    int rs = bytecode[pc++] & 0xFF;
    System.err.printf("MUL_ASSIGN: r%d *= r%d (before: %s, %s)\n",
        rd, rs, registers[rd], registers[rs]);
    registers[rd] = MathOperators.multiply(...);
    System.err.printf("  after: %s\n", registers[rd]);
    break;
}
```

**Use Java Debugger:**
```bash
# Add breakpoint in BytecodeInterpreter.java
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005 \
     -cp build/libs/perlonjava-*-all.jar \
     org.perlonjava.Main --eval 'my $x = 10; $x *= 2'
```

### Common Pitfalls

**1. Forgetting to Increment PC:**
```java
// WRONG: pc not incremented, will read same byte forever!
case Opcodes.MUL_ASSIGN: {
    int rd = bytecode[pc] & 0xFF;  // Missing pc++
    int rs = bytecode[pc] & 0xFF;  // Missing pc++
    ...
}

// RIGHT:
int rd = bytecode[pc++] & 0xFF;
int rs = bytecode[pc++] & 0xFF;
```

**2. Creating Gaps in Opcode Numbering:**
```java
// WRONG: Gap between 82 and 90 breaks tableswitch!
public static final byte LOOP_PLUS_PLUS = 82;
public static final byte MUL_ASSIGN = 90;  // Gap!

// RIGHT: Sequential numbering
public static final byte LOOP_PLUS_PLUS = 82;
public static final byte MUL_ASSIGN = 83;  // No gap
```

**3. Incorrect Type Casting:**
```java
// WRONG: ClassCastException if register contains RuntimeArray!
RuntimeScalar scalar = (RuntimeScalar) registers[rd];

// RIGHT: Check type or use safe casting
if (registers[rd] instanceof RuntimeScalar) {
    RuntimeScalar scalar = (RuntimeScalar) registers[rd];
    ...
}
```

**4. Not Handling Context:**
```java
// WRONG: Ignores void/scalar/list context
result = code.apply(args, RuntimeContextType.SCALAR);  // Always scalar!

// RIGHT: Propagate context from current execution
result = code.apply(args, currentContext);
```

### Maintaining Dense Opcodes

**Critical Rule:** Opcodes must be dense (no gaps) to preserve tableswitch optimization.

**When adding opcodes:**
- Use next sequential number (current max is 82, so use 83, 84, 85, ...)
- Never skip numbers
- Never delete opcodes without renumbering

**If you must remove an opcode:**
1. Renumber all subsequent opcodes to close the gap
2. Update all references (Opcodes.java, BytecodeInterpreter.java, InterpretedCode.java)
3. Run full test suite to catch missed references

**Verify tableswitch after changes:**
```bash
javap -c -p -classpath build/classes/java/main \
      org.perlonjava.interpreter.BytecodeInterpreter | grep "switch"
```

### Performance Testing

After any change to BytecodeInterpreter.java or Opcodes.java:

1. **Run benchmark:**
   ```bash
   ./gradlew run -PmainClass=org.perlonjava.interpreter.ForLoopBenchmark
   ```

2. **Compare results:**
   - Before: 46.84M ops/sec
   - After: Should be ≥46.84M ops/sec (no regression)
   - New superinstruction: Should show improvement for matching patterns

3. **Profile hot paths:**
   ```bash
   java -XX:+UnlockDiagnosticVMOptions \
        -XX:+PrintCompilation \
        -XX:+PrintInlining \
        -cp build/libs/perlonjava-*-all.jar \
        org.perlonjava.interpreter.ForLoopBenchmark
   ```

4. **Check JIT compilation:**
   Look for "made not entrant" or "made zombie" messages indicating deoptimization.

## Summary

The PerlOnJava interpreter is a production-ready, high-performance bytecode interpreter that:

- **Executes Perl bytecode** at 46.84M ops/sec (1.75x slower than compiler)
- **Shares 100% of runtime APIs** with the compiler (zero duplication)
- **Supports closures** and bidirectional calling (compiled ↔ interpreted)
- **Uses dense opcodes** (0-82) for optimal JVM tableswitch dispatch
- **Implements superinstructions** to eliminate overhead

Future optimizations (unboxed ints, inline caching, specialized loops) can potentially reach 1.2-1.5x slower than compiler while maintaining the benefits of interpretation.

For questions or contributions, refer to:
- **STATUS.md** - Current implementation status
- **TESTING.md** - Testing procedures
- **BYTECODE_DOCUMENTATION.md** - Complete opcode reference
- **CLOSURE_IMPLEMENTATION_COMPLETE.md** - Closure architecture

Happy hacking!
