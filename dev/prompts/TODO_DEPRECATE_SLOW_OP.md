# TODO: Deprecate SLOW_OP Mechanism

## Current State

**SLOW_OP (opcode 87)** dispatches to SlowOpcodeHandler with a 2-byte format:
- `[SLOW_OP] [operation_id] [operands...]`
- Handles 41 rare operations (system calls, IPC, slices, etc.)
- ~1,185 lines in SlowOpcodeHandler.java

## Why Deprecate?

1. **Inconsistent Architecture**: All other operations use direct opcodes
2. **Bytecode Overhead**: Uses 2 bytes instead of 1 for each operation
3. **Performance**: Adds one extra dispatch (though minimal ~5ns)
4. **Complexity**: Separate file and indirection makes code harder to understand

## Compatibility Discovery

SlowOpcodeHandler methods are **already compatible** with range-based delegation:

```java
// Current SlowOpcodeHandler pattern:
private static int executeDerefArray(short[] bytecode, int pc, RuntimeBase[] registers) {
    int rd = bytecode[pc++];
    int rs = bytecode[pc++];
    // ... implementation
    return pc;
}

// Same signature as executeComparisons/executeArithmetic!
// Can be moved directly into BytecodeInterpreter
```

## Migration Plan

### Phase 1: Assign Direct Opcodes (114-127 available)

**Group 1: Dereferencing** (114-115)
- DEREF_ARRAY (was SLOWOP_DEREF_ARRAY)
- DEREF_HASH (was SLOWOP_DEREF_HASH)

**Group 2: Slice Operations** (116-120)
- ARRAY_SLICE (was SLOWOP_ARRAY_SLICE)
- ARRAY_SLICE_SET (was SLOWOP_ARRAY_SLICE_SET)
- HASH_SLICE (was SLOWOP_HASH_SLICE)
- HASH_SLICE_SET (was SLOWOP_HASH_SLICE_SET)
- HASH_SLICE_DELETE (was SLOWOP_HASH_SLICE_DELETE)

**Group 3: Array/String Ops** (121-124)
- SPLICE_OP (was SLOWOP_SPLICE)
- REVERSE_OP (was SLOWOP_REVERSE)
- SPLIT_OP (was SLOWOP_SPLIT)
- LIST_SLICE_FROM (was SLOWOP_LIST_SLICE_FROM)

**Group 4: Special Operations** (125-127)
- EXISTS_OP (was SLOWOP_EXISTS)
- DELETE_OP (was SLOWOP_DELETE)
- LENGTH_OP (was SLOWOP_LENGTH)

### Phase 2: Use Negative Byte Range (-128 to -1)

**Remaining 27 operations** use negative opcodes:

**Group 5: Closure/Scope** (-1 to -4)
- RETRIEVE_BEGIN_SCALAR (was SLOWOP_RETRIEVE_BEGIN_SCALAR)
- RETRIEVE_BEGIN_ARRAY (was SLOWOP_RETRIEVE_BEGIN_ARRAY)
- RETRIEVE_BEGIN_HASH (was SLOWOP_RETRIEVE_BEGIN_HASH)
- LOCAL_SCALAR (was SLOWOP_LOCAL_SCALAR)

**Group 6: System Calls** (-5 to -24)
- CHOWN, WAITPID, FORK, GETPPID, GETPGRP, SETPGRP
- SEMGET, SEMOP, MSGGET, MSGSND, MSGRCV
- SHMGET, SHMREAD, SHMWRITE
- GETPRIORITY, SETPRIORITY
- GETSOCKOPT, SETSOCKOPT
- SYSCALL

**Group 7: Special I/O** (-25 to -27)
- EVAL_STRING (was SLOWOP_EVAL_STRING)
- SELECT_OP (was SLOWOP_SELECT)
- LOAD_GLOB (was SLOWOP_LOAD_GLOB)
- SLEEP_OP (was SLOWOP_SLEEP)

### Phase 3: Range-Based Delegation in BytecodeInterpreter

```java
// Replace:
case Opcodes.SLOW_OP: {
    pc = SlowOpcodeHandler.execute(bytecode, pc, registers, code);
    break;
}

// With range-based delegation:
case Opcodes.DEREF_ARRAY:
case Opcodes.DEREF_HASH:
    pc = executeDeref(opcode, bytecode, pc, registers);
    break;

case Opcodes.ARRAY_SLICE:
case Opcodes.ARRAY_SLICE_SET:
case Opcodes.HASH_SLICE:
case Opcodes.HASH_SLICE_SET:
case Opcodes.HASH_SLICE_DELETE:
    pc = executeSliceOps(opcode, bytecode, pc, registers, code);
    break;

// ... etc for all 7 groups
```

### Phase 4: Move Methods to BytecodeInterpreter

```java
/**
 * Handle dereferencing operations.
 * Moved from SlowOpcodeHandler for consistent architecture.
 */
private static int executeDeref(short opcode, short[] bytecode, int pc,
                                 RuntimeBase[] registers) {
    int rd = bytecode[pc++];
    int rs = bytecode[pc++];

    switch (opcode) {
        case Opcodes.DEREF_ARRAY:
            // Move executeDerefArray logic here
            return pc;
        case Opcodes.DEREF_HASH:
            // Move executeDerefHash logic here
            return pc;
    }
    return pc;
}

// Similarly for all 7 groups
```

## Benefits

### Performance
- **Bytecode size**: Save 1 byte per operation (millions in large scripts)
- **Dispatch**: Remove one method call indirection (~5ns per operation)
- **Cache**: Better instruction cache locality

### Architecture
- **Consistency**: All operations use same pattern
- **Simplicity**: One file (BytecodeInterpreter) instead of two
- **Maintainability**: Easier to understand and modify

### Scalability
- **Room to grow**: 128 negative opcodes available for future operations
- **Clear organization**: Operations grouped by functionality

## Migration Strategy

### Backward Compatibility (Optional)

**Option A: Big Bang Migration**
- Change all opcodes at once
- Recompile all test bytecode
- Fast but disruptive

**Option B: Gradual Migration** (Recommended)
- Keep SLOW_OP for 1-2 releases
- Emit both SLOW_OP and new opcodes
- Bytecode supports both formats
- Deprecate SLOW_OP in docs
- Remove after 2 releases

### Implementation Steps

1. **Add new opcodes** (114-127, -128 to -1) in Opcodes.java
2. **Update BytecodeCompiler** to emit new opcodes
3. **Move methods** from SlowOpcodeHandler to BytecodeInterpreter
4. **Add range delegation** in main switch
5. **Test thoroughly** with existing test suite
6. **Benchmark** to verify performance improvement
7. **Update documentation**
8. **Mark SLOW_OP as @Deprecated**
9. **Remove SlowOpcodeHandler.java** (2+ releases later)

## Size Impact on BytecodeInterpreter

Current situation:
- BytecodeInterpreter.execute(): 7,270 bytes (warning range)

After moving SlowOpcodeHandler methods:
- Each group secondary method: ~500-1500 bytes
- 7 new methods: ~6,000 bytes total
- Main switch additions: ~500 bytes
- **Total size increase**: ~6,500 bytes across 7 methods

**Important**: Each method stays under 8,000 byte limit!
- executeDeref(): ~800 bytes
- executeSliceOps(): ~1,200 bytes
- executeArrayStringOps(): ~1,000 bytes
- executeScopeOps(): ~800 bytes
- executeSystemCalls(): ~1,500 bytes
- executeSpecialIO(): ~1,000 bytes
- Main execute(): 7,270 → ~7,800 bytes (still under limit)

## Testing

1. **Unit tests**: All existing tests must pass
2. **Performance**: Benchmark before/after for each operation
3. **Bytecode size**: Measure bytecode size reduction
4. **Memory**: Check memory usage for large scripts
5. **Integration**: Run full perl5_t test suite

## Documentation Updates

1. **Opcodes.java**: Document new opcode ranges
2. **BytecodeInterpreter.java**: Add javadoc for new methods
3. **ARCHITECTURE.md**: Update interpreter architecture section
4. **SPECIALIST_GUIDE.md**: Document opcode ranges and patterns
5. **CHANGELOG.md**: Note SLOW_OP deprecation

## Timeline (Suggested)

- **Release N**: Add new opcodes, mark SLOW_OP as deprecated
- **Release N+1**: Emit warnings when SLOW_OP is used
- **Release N+2**: Remove SLOW_OP completely

## Priority

**Medium** - Not urgent, but good architectural cleanup.

- Improves code consistency
- Minor performance gains
- Better maintainability

Tackle after:
1. Critical bugs
2. Feature requests
3. Major refactorings

## Related Work

This TODO builds on:
- ✅ BytecodeInterpreter.execute() refactoring (8x speedup achieved)
- ✅ BytecodeCompiler visitor method refactoring (JIT compilation enabled)
- ✅ Range-based delegation pattern established

## References

- Current implementation: `src/main/java/org/perlonjava/interpreter/SlowOpcodeHandler.java`
- Target pattern: `BytecodeInterpreter.executeComparisons()` and similar methods
- Opcode definitions: `src/main/java/org/perlonjava/interpreter/Opcodes.java`

---

**Created**: 2026-02-16
**Status**: TODO (not started)
**Estimated Effort**: 2-3 days
**Risk**: Low (SlowOpcodeHandler methods already have correct signature)
