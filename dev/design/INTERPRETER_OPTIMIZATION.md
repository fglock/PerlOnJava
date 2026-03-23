# Interpreter Performance Optimization

## Profile Analysis (2026-03-23)

**Benchmark:** `./jperl --interpreter dev/bench/benchmark_closure.pl`
- Interpreter mode: ~127 seconds
- JVM mode: ~4 seconds
- Ratio: ~32x (expected for bytecode interpreter vs JIT-compiled code)

## Top Hotspots by Sample Count

| Samples | Location | Description |
|---------|----------|-------------|
| 90 | `BytecodeInterpreter.execute` | Main interpreter loop |
| 54 | `RuntimeCode.apply` | Subroutine dispatch |
| 39 | `InterpretedCode.apply` | Delegation to interpreter |
| 7 | `getCallSiteInfo` | TreeMap lookup for caller() |
| 5 | `getSourceLocationAccurate` | Line number computation |

## Detailed Hotspot Analysis

### CALL Opcode Handling (BytecodeInterpreter.java lines 816-838)

```
Line 816 (6 samples): ThreadLocal lookup - InterpreterState.currentPackage.get()
Line 834 (7 samples): getCallSiteInfo - TreeMap.floorEntry() 
Line 835 (4 samples): CallerStack.push
Line 838 (10 samples): RuntimeCode.apply - actual call
```

### Call Chain Overhead

The subroutine call dispatch has deep indirection:

```
CALL opcode (BytecodeInterpreter)
    → RuntimeCode.apply (54 samples)
        → InterpretedCode.apply (39 samples)  
            → BytecodeInterpreter.execute (90 samples)
```

Each call goes through multiple layers before reaching the actual interpreter execution.

## Optimization Plan

### Phase 1: ThreadLocal Caching (High Impact, Low Risk)

**Problem:** `InterpreterState.currentPackage.get()` is called on every CALL opcode.

**Solution:** Cache the package name at the start of execute() and pass it through or use a local variable.

**Files:** `BytecodeInterpreter.java`

### Phase 2: Lazy CallerStack (High Impact, Medium Risk)

**Problem:** `CallerStack.push/pop` and `getCallSiteInfo` happen on EVERY subroutine call, even when `caller()` is never invoked.

**Solution:** Defer CallerStack operations until caller() is actually called:
1. Store call site info in a lightweight structure
2. Only populate CallerStack on-demand when caller() executes
3. Use a "dirty" flag to track if stack needs updating

**Files:** `BytecodeInterpreter.java`, `CallerStack.java`

### Phase 3: Inline Apply Path (Medium Impact, Medium Risk)

**Problem:** Call dispatch goes through multiple indirection layers.

**Solution:** For InterpretedCode, bypass RuntimeCode.apply and call BytecodeInterpreter.execute directly from the CALL opcode handler.

**Files:** `BytecodeInterpreter.java`

### Phase 4: Cache pcToTokenIndex Lookup (Low Impact, Low Risk)

**Problem:** `TreeMap.floorEntry()` is O(log n) for line number lookups.

**Solution:** Cache last lookup result since sequential execution often hits nearby PCs.

**Files:** `BytecodeInterpreter.java`

## Implementation Status

### Completed
- [x] Profile analysis (2026-03-23)

### In Progress
- [ ] Phase 1: ThreadLocal Caching

### Pending
- [ ] Phase 2: Lazy CallerStack
- [ ] Phase 3: Inline Apply Path
- [ ] Phase 4: Cache pcToTokenIndex Lookup

## Verification

After each optimization:
1. Run `make` to ensure no regressions
2. Re-run benchmark to measure improvement
3. Re-profile to confirm hotspot reduction

## Related Files

- `src/main/java/org/perlonjava/backend/bytecode/BytecodeInterpreter.java`
- `src/main/java/org/perlonjava/backend/bytecode/InterpretedCode.java`
- `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeCode.java`
- `src/main/java/org/perlonjava/runtime/runtimetypes/CallerStack.java`
- `src/main/java/org/perlonjava/backend/bytecode/InterpreterState.java`
