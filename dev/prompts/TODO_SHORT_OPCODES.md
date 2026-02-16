# TODO: Migrate to Short Opcodes

## Executive Summary

**Change opcodes from `byte` to `short`** to unlock 65,536 opcode space. This enables:
1. Eliminating SLOW_OP mechanism entirely
2. Promoting ALL 200+ OperatorHandler operations to direct opcodes
3. Massive performance gains (10-100x for promoted operations)

**CRITICAL: Keep opcodes CONTIGUOUS** for JVM `tableswitch` optimization!

## Why Short Opcodes?

### Current Limitations (byte opcodes)

**Opcode Space:**
- Byte range: -128 to 127 (256 values)
- Currently used: 114 opcodes (0-113)
- Available: 142 slots

**Three Dispatch Mechanisms:**
1. **Direct opcodes** (0-113): Fast
2. **SLOW_OP** (opcode 87 + 41 operations): Medium (extra indirection)
3. **OperatorHandler** (200+ operations): Slow (ASM method calls)

**Problem:** Need 350+ opcodes total, only have 256 available!

### Solution: Short Opcodes

**Short range:** 0 to 32,767 (32,768 positive values)
- Room for ALL operations (114 + 41 + 200+ = 350+)
- Room for future growth (32,000+ slots available!)
- **Infrastructure already there** (bytecode is `short[]`)

## CRITICAL: Contiguous Opcodes for Performance

### JVM Switch Optimization

The JVM uses different switch implementations based on opcode density:

**tableswitch (FAST - O(1)):**
```java
// Dense/contiguous opcodes
case 1000:  // ADD
case 1001:  // SUBTRACT
case 1002:  // MULTIPLY
case 1003:  // DIVIDE
// JVM generates: jump_table[opcode - 1000]
// Direct array lookup!
```

**lookupswitch (SLOW - O(log n)):**
```java
// Sparse opcodes
case 1000:  // ADD
case 5000:  // SUBTRACT
case 10000: // MULTIPLY
case 20000: // DIVIDE
// JVM generates: binary search
// Much slower!
```

**Rule:** **NEVER skip opcode numbers within a functional group!**

### Example: Good vs Bad

**✅ GOOD (Contiguous):**
```java
public static final short EQ_NUM = 300;
public static final short NE_NUM = 301;
public static final short LT_NUM = 302;
public static final short GT_NUM = 303;
public static final short LE_NUM = 304;
public static final short GE_NUM = 305;
// Generates tableswitch - O(1)
```

**❌ BAD (Sparse):**
```java
public static final short EQ_NUM = 300;
public static final short NE_NUM = 500;
public static final short LT_NUM = 1000;
public static final short GT_NUM = 5000;
public static final short LE_NUM = 10000;
public static final short GE_NUM = 20000;
// Generates lookupswitch - O(log n)
// Performance loss!
```

## Opcode Allocation Plan

**Organize into CONTIGUOUS 100-opcode blocks:**

```
RANGE          COUNT   PURPOSE                           STATUS
======================================================================
0-99           100     Core Control Flow                 Current
100-199        100     Register Operations               Current
200-299        100     Reserved                          Future

// Comparison operators (CONTIGUOUS!)
300-349        50      Numeric Comparisons               Current
350-399        50      String Comparisons                Current

// Arithmetic operators (CONTIGUOUS!)
400-449        50      Basic Math (+, -, *, /, %)        Current
450-499        50      Advanced Math (pow, sqrt, etc.)   Current
500-549        50      Bitwise Operations                Current

// String operators (CONTIGUOUS!)
550-649        100     String Operations                 Current/Promoted

// Array operations (CONTIGUOUS!)
650-749        100     Array Operations                  Current
750-849        100     Array Slices & Access             Promoted

// Hash operations (CONTIGUOUS!)
850-949        100     Hash Operations                   Current
950-1049       100     Hash Slices                       Promoted

// I/O operations (CONTIGUOUS!)
1050-1149      100     File I/O                          Current/Promoted
1150-1249      100     Socket I/O                        Promoted
1250-1349      100     Directory Operations              Promoted

// System operations (CONTIGUOUS!)
1350-1449      100     Process Management                Promoted (SLOW_OP)
1450-1549      100     System Calls                      Promoted (SLOW_OP)
1550-1649      100     IPC Operations                    Promoted (SLOW_OP)

// Special operations (CONTIGUOUS!)
1650-1749      100     Regex Operations                  Future
1750-1849      100     Format Operations                 Promoted
1850-1949      100     Reference Operations              Current
1950-2049      100     Closure/Scope Operations          Promoted (SLOW_OP)

// OperatorHandler migrations (CONTIGUOUS blocks by class!)
2050-2149      100     MathOperators                     Promoted
2150-2249      100     StringOperators                   Promoted
2250-2349      100     CompareOperators                  Promoted
2350-2449      100     BitwiseOperators                  Promoted
2450-2549      100     IOOperators                       Promoted
2550-2649      100     ListOperators                     Promoted
...
5000-9999      5000    Future OperatorHandler            Available
10000-32767    22768   Reserved                          Future
```

**Key Principles:**
1. ✅ **CONTIGUOUS within groups** - never skip numbers
2. ✅ **100-op blocks** - room to grow without breaking continuity
3. ✅ **Functional grouping** - related ops together
4. ✅ **Clear boundaries** - easy to find opcodes

## Infrastructure (Already There!)

**No infrastructure changes needed:**

```java
// BytecodeInterpreter.java - ALREADY uses short!
short[] bytecode = code.bytecode;
short opcode = bytecode[pc++];  // ✅ Already short

// BytecodeCompiler.java - ALREADY emits short!
public void emit(int value) {
    bytecode.add((short) value);  // ✅ Already short
}
```

**Only change opcode type:**
```java
// Opcodes.java - Change from byte to short
public static final short NOP = 0;      // was byte
public static final short RETURN = 1;   // was byte
public static final short GOTO = 2;     // was byte
// ... etc
```

## Migration Plan

### Phase 1: Migrate to Short Opcodes (Breaking Change)

**Step 1:** Update Opcodes.java
```java
// Change ALL definitions from byte to short
public static final short NOP = 0;
public static final short RETURN = 1;
public static final short GOTO = 2;
// ... all 114 existing opcodes
```

**Step 2:** Reorganize into contiguous ranges
```java
// Keep existing opcodes where they are (0-113)
// Reserve blocks for future use (see allocation table)
```

**Step 3:** Test thoroughly
```bash
make build
make test-all
./dev/tools/scan-all-method-sizes.sh
```

### Phase 2: Eliminate SLOW_OP (~41 operations)

**Promote to contiguous ranges:**

**System Calls (1350-1369) - CONTIGUOUS:**
```java
public static final short CHOWN = 1350;
public static final short FORK = 1351;
public static final short WAITPID = 1352;
public static final short GETPPID = 1353;
public static final short GETPGRP = 1354;
public static final short SETPGRP = 1355;
public static final short GETPRIORITY = 1356;
public static final short SETPRIORITY = 1357;
public static final short GETSOCKOPT = 1358;
public static final short SETSOCKOPT = 1359;
public static final short SYSCALL = 1360;
// ... continue contiguously to 1369
```

**IPC Operations (1550-1569) - CONTIGUOUS:**
```java
public static final short SEMGET = 1550;
public static final short SEMOP = 1551;
public static final short MSGGET = 1552;
public static final short MSGSND = 1553;
public static final short MSGRCV = 1554;
public static final short MSGCTL = 1555;
public static final short SEMCTL = 1556;
public static final short SHMGET = 1557;
public static final short SHMCTL = 1558;
public static final short SHMREAD = 1559;
public static final short SHMWRITE = 1560;
// ... continue contiguously
```

**Slice Operations (750-759) - CONTIGUOUS:**
```java
public static final short ARRAY_SLICE = 750;
public static final short ARRAY_SLICE_SET = 751;
public static final short HASH_SLICE = 752;
public static final short HASH_SLICE_SET = 753;
public static final short HASH_SLICE_DELETE = 754;
public static final short LIST_SLICE_FROM = 755;
```

**Special Operations (1950-1959) - CONTIGUOUS:**
```java
public static final short DEREF_ARRAY = 1950;
public static final short DEREF_HASH = 1951;
public static final short RETRIEVE_BEGIN_SCALAR = 1952;
public static final short RETRIEVE_BEGIN_ARRAY = 1953;
public static final short RETRIEVE_BEGIN_HASH = 1954;
public static final short LOCAL_SCALAR = 1955;
public static final short EVAL_STRING = 1956;
public static final short LOAD_GLOB = 1957;
public static final short SELECT_OP = 1958;
public static final short SLEEP_OP = 1959;
```

**Update BytecodeInterpreter (range delegation):**
```java
// System calls (1350-1369) - tableswitch!
case 1350: case 1351: case 1352: case 1353: case 1354:
case 1355: case 1356: case 1357: case 1358: case 1359:
case 1360: case 1361: case 1362: case 1363: case 1364:
case 1365: case 1366: case 1367: case 1368: case 1369:
    pc = executeSystemCalls(opcode, bytecode, pc, registers);
    break;
```

**Delete SlowOpcodeHandler.java** - no longer needed!

### Phase 3: Promote OperatorHandler Operations (Gradual)

**Identify hot operations via profiling:**
```bash
# Profile which operators are used most
./dev/tools/profile-operator-usage.sh
```

**Promote by class in contiguous ranges:**

**MathOperators (2050-2099) - CONTIGUOUS:**
```java
public static final short OP_ADD = 2050;
public static final short OP_SUBTRACT = 2051;
public static final short OP_MULTIPLY = 2052;
public static final short OP_DIVIDE = 2053;
public static final short OP_MODULUS = 2054;
public static final short OP_POW = 2055;
public static final short OP_SQRT = 2056;
// ... continue contiguously
```

**Update EmitOperatorNode:**
```java
// OLD: Emit INVOKESTATIC (6 bytes)
methodVisitor.visitMethodInsn(
    INVOKESTATIC,
    "org/perlonjava/operators/MathOperators",
    "add",
    descriptor
);

// NEW: Emit opcode (2 bytes)
emit(Opcodes.OP_ADD);
emitReg(rd);
emitReg(rs1);
emitReg(rs2);
```

**Update BytecodeInterpreter:**
```java
// MathOperators (2050-2099) - tableswitch!
case 2050: case 2051: case 2052: case 2053: case 2054:
case 2055: case 2056: case 2057: case 2058: case 2059:
// ... all cases through 2099
    pc = executeMathOps(opcode, bytecode, pc, registers);
    break;
```

## Benefits

### Performance
✅ **SLOW_OP**: Eliminate indirection (~5ns per op)
✅ **OperatorHandler**: 10-100x speedup (INVOKESTATIC → direct dispatch)
✅ **tableswitch**: O(1) lookup for contiguous ranges
✅ **Cache**: Better instruction cache locality

### Bytecode Size
✅ **SLOW_OP**: Same size (was 2 bytes: SLOW_OP+id, now 2 bytes: opcode)
✅ **OperatorHandler**: -4 bytes per op (was 6 bytes INVOKESTATIC, now 2 bytes opcode)
❌ **Simple ops**: +1 byte (was 1 byte, now 2 bytes)
**Net**: ~10% smaller bytecode for typical scripts

### Architecture
✅ **Consistent**: One dispatch mechanism (BytecodeInterpreter switch)
✅ **Simple**: No SlowOpcodeHandler, no OperatorHandler indirection
✅ **Maintainable**: Clear organization, contiguous ranges
✅ **Scalable**: 32,000+ opcodes available

## Trade-offs

### Pros
✅ 32,768 opcode space (vs 256)
✅ Eliminate ALL indirection
✅ 10-100x performance for promoted ops
✅ Consistent architecture
✅ tableswitch optimization
✅ Future-proof

### Cons
❌ Breaking change (must recompile bytecode)
❌ +1 byte per simple opcode (+10% bytecode size)
❌ Migration effort (medium)

### Decision
**STRONGLY RECOMMEND: Proceed with short opcodes**

Benefits far outweigh costs. 10% bytecode overhead is negligible vs 10-100x performance gains.

## Verification

### Confirm tableswitch Usage
```bash
# Check that JVM generates tableswitch (not lookupswitch)
javap -c build/classes/java/main/org/perlonjava/interpreter/BytecodeInterpreter.class | grep -A 30 "switch"

# Should see "tableswitch" for contiguous ranges
# Should NOT see "lookupswitch"
```

### Performance Benchmarks
```bash
# Benchmark before/after
time ./jperl --interpreter benchmark.pl

# Measure per-operation speedup
./dev/tools/benchmark-opcodes.sh
```

### Size Analysis
```bash
# Compare bytecode sizes
ls -lh *.pbc

# Verify ~10% increase is acceptable
```

## Timeline

**Phase 1 (Short opcodes)**: 1-2 days
- Type change + reorganization
- Breaking change, coordinate release

**Phase 2 (Eliminate SLOW_OP)**: 2-3 days
- 41 operations in 4 contiguous groups
- Test thoroughly

**Phase 3 (OperatorHandler)**: Ongoing (months)
- Promote 5-10 hot ops per release
- Measure performance gains
- Eventually all 200+ operators

## Priority

**HIGH** - Major performance and architectural improvement

This unlocks:
- Massive performance gains
- Clean, consistent architecture
- Unlimited future growth

## References

- Bytecode: `BytecodeInterpreter.java` (already uses `short[]`)
- Compiler: `BytecodeCompiler.java` (already emits `short`)
- Opcodes: `src/main/java/org/perlonjava/interpreter/Opcodes.java`
- SLOW_OP: `src/main/java/org/perlonjava/interpreter/SlowOpcodeHandler.java`
- Operators: `src/main/java/org/perlonjava/operators/OperatorHandler.java`

---

**Created**: 2026-02-16
**Status**: TODO (not started)
**Effort**: Phase 1: 1-2 days, Phase 2: 2-3 days, Phase 3: Ongoing
**Priority**: HIGH
**Risk**: Medium (breaking change, but infrastructure ready)
