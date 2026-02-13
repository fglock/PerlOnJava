# Interpreter Opcodes Architecture

## Two-Level Dispatch Design

The interpreter uses a two-level dispatch strategy for optimal performance:

```
Fast Opcodes (0-92) → Main switch in BytecodeInterpreter
Slow Opcodes (SLOW_OP + id) → SlowOpcodeHandler.handleSlowOp()
```

### Why Two-Level Dispatch?

**Decision Date**: 2026-02-13

With short[] bytecode, we have 65,536 opcodes available. We could unify all opcodes into a single flat range (0-127), but we deliberately chose to keep the two-level design.

### Performance Rationale

1. **CPU Instruction Cache (i-cache) Locality**
   - Hot path (fast opcodes): ~1KB of code, stays in L1 i-cache
   - Cold path (slow opcodes): Separate method, doesn't pollute hot path
   - Better cache efficiency for frequently-executed operations

2. **JVM Optimization**
   - Both designs use tableswitch (O(1) jump table)
   - Two-level overhead: ~1-2 cycles per slow op
   - Negligible cost (<1% based on analysis)

3. **Code Organization**
   - Clear separation: hot vs cold operations
   - Easy to identify performance-critical paths
   - Proven pattern: Lua 5.x, Python 2.x, Ruby YARV

### When to Use Each

**Fast Opcodes (0-92)**: Frequently executed operations
- Arithmetic: ADD_SCALAR, SUB_SCALAR, MUL_SCALAR
- Data structures: ARRAY_GET, ARRAY_SET, HASH_GET, HASH_SET
- Control flow: GOTO, GOTO_IF_FALSE, RETURN
- Common operators: CONCAT, NOT, AND, OR

**Slow Opcodes (SLOW_OP + id)**: Rarely used operations
- System calls: SLOWOP_SYSCALL, SLOWOP_FORK
- IPC: SLOWOP_SEMGET, SLOWOP_SHMREAD
- Complex operations: SLOWOP_EVAL_STRING, SLOWOP_SPLICE
- Specialized features: SLOWOP_LOAD_GLOB, SLOWOP_LOCAL_SCALAR

### Promoting Slow to Fast

If profiling shows a slow opcode is frequently executed, it can be promoted:

1. Add new fast opcode in dense range (e.g., `public static final byte OP_NAME = 93;`)
2. Implement in main BytecodeInterpreter switch
3. Update BytecodeCompiler to emit fast opcode
4. Remove from SlowOpcodeHandler

**Example**: If `SLOWOP_SPLICE` became hot, we could:
```java
// In Opcodes.java
public static final byte SPLICE = 93;  // Promoted from SLOWOP_SPLICE

// In BytecodeInterpreter.java
case Opcodes.SPLICE: {
    // Implementation here (moved from SlowOpcodeHandler)
}
```

### Benchmarking Results

**Theoretical analysis** (2026-02-13):
- Two-level dispatch: 2-3 cycles per slow op
- Single-level dispatch: 1-2 cycles per op
- Difference: ~1 cycle (negligible)
- Benefit: Better i-cache utilization for hot path

**Empirical validation**: Not yet benchmarked (expected <1% difference)

### Alternative Considered

**Unified single-level dispatch**: All opcodes in range 0-127
- **Pros**: Simpler architecture, one less branch
- **Cons**: Mixes hot/cold code, worse i-cache locality
- **Decision**: Rejected in favor of current design

### References

- Plan: `/Users/fglock/.claude/plans/glimmering-marinating-frost.md` (Part 1)
- Implementation: `src/main/java/org/perlonjava/interpreter/BytecodeInterpreter.java`
- Slow opcodes: `src/main/java/org/perlonjava/interpreter/SlowOpcodeHandler.java`
- Opcode constants: `src/main/java/org/perlonjava/interpreter/Opcodes.java`
