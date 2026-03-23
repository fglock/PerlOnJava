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
- [x] Phase 1: ThreadLocal Caching (2026-03-23) - Cache RuntimeScalar reference, no measurable speedup but cleaner code
- [x] Phase 2: Lazy CallerStack (2026-03-23) - **~19% speedup** (127s → 103s)
- [x] Phase 3: Inline Apply Path (2026-03-23) - **~2% speedup** (103s → 101s)
- [x] Phase 4: Register Array Pooling (2026-03-23) - **~4% speedup** (101s → 97s)

### Pending
- [ ] Phase 5: Cache pcToTokenIndex Lookup (moved from Phase 4)

## Profile Results After Phase 1

Second profile showed `getCallSiteInfo` (16 samples) + `getSourceLocationAccurate` (15 samples) = ~10% overhead.
This is spent computing call site info for `caller()` support on every subroutine call.

## Phase 2 Results

Implemented lazy CallerStack:
- `CallerStack.pushLazy()` stores a lambda that computes CallerInfo on demand
- Line number computation deferred until `caller()` is actually called
- `pop()` doesn't resolve lazy entries (no computation needed)

**Benchmark improvement:** 127s → 103s = **~19% speedup**

## Phase 3 Results

Inline InterpretedCode apply path in CALL_SUB:
- Check if code is `InterpretedCode` and call `BytecodeInterpreter.execute()` directly
- Bypasses `RuntimeCode.apply()` → `InterpretedCode.apply()` chain

**Benchmark improvement:** 103s → 101s = **~2% speedup**

## Phase 4 Results

Register array pooling in InterpretedCode:
- `InterpretedCode.getRegisters()` caches register arrays per-code-object
- Uses ThreadLocal for thread safety with recursion detection
- Recursive calls fallback to fresh allocation (no contention)
- `BytecodeInterpreter.execute()` releases registers in finally block

**Benchmark improvement:** 101s → 97s = **~4% speedup**

## Total Performance Improvement

| Phase | Time (s) | Improvement |
|-------|----------|-------------|
| Baseline | 127 | - |
| Phase 2 (Lazy CallerStack) | 103 | 19% |
| Phase 3 (Inline Apply) | 101 | 2% |
| Phase 4 (Register Pooling) | 97 | 4% |
| **Total** | **97** | **~24%** |

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
