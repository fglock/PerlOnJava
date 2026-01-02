# Control Flow Registry Solution

**Date**: 2025-11-06  
**Status**: ✅ COMPLETE - `last SKIP` fully functional  
**Test Pass Rate**: 100% (1911/1911 tests passing)

## The Problem

Implementing Perl's non-local control flow (`last SKIP`, `next OUTER`, etc.) required propagating control flow markers across subroutine boundaries. The initial approaches all failed due to ASM (Java bytecode manipulation library) frame computation issues:

1. **Tagged Return Values (RuntimeControlFlowList)** - Broke ASM when checking at call sites
2. **Loop Handlers** - Broke ASM with complex branching
3. **Call-Site Checks** - Broke ASM when jumping to returnLabel

The root cause: Any complex bytecode pattern that jumps to `returnLabel` or has intricate branching confuses ASM's stack frame merger.

## The Solution: Runtime Control Flow Registry

Instead of embedding control flow info in return values, we use a **ThreadLocal registry** to store control flow markers separately from the normal return path.

### Architecture

```
┌─────────────────────────────────────────────────────────────┐
│ Subroutine with non-local control flow                      │
│                                                              │
│   sub inner {                                                │
│       last SKIP;  ← Creates ControlFlowMarker               │
│                     Registers in ThreadLocal                 │
│                     Returns empty list (ARETURN)             │
│   }                                                          │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼ Returns normally
┌─────────────────────────────────────────────────────────────┐
│ SKIP: {                                                      │
│     inner();      ← Call completes normally                  │
│     ┌─────────────────────────────────────────┐             │
│     │ CHECK REGISTRY                          │             │
│     │ action = checkLoopAndGetAction("SKIP")  │             │
│     │ TABLESWITCH action:                     │             │
│     │   1 → lastLabel                         │             │
│     │   2 → nextLabel                         │             │
│     │   3 → redoLabel                         │             │
│     └─────────────────────────────────────────┘             │
│     print "after";  ← Skipped if action=1                   │
│ }                                                            │
└─────────────────────────────────────────────────────────────┘
```

### Key Components

#### 1. RuntimeControlFlowRegistry (ThreadLocal Storage)

```java
public class RuntimeControlFlowRegistry {
    private static final ThreadLocal<ControlFlowMarker> currentMarker = new ThreadLocal<>();
    
    public static void register(ControlFlowMarker marker);
    public static int checkLoopAndGetAction(String labelName);
    // Returns: 0=none, 1=LAST, 2=NEXT, 3=REDO
}
```

#### 2. Non-Local Control Flow Registration

When `last/next/redo` can't find a matching loop label (non-local):

```java
// Create marker
new ControlFlowMarker(type, label, fileName, lineNumber)
// Register in ThreadLocal
RuntimeControlFlowRegistry.register(marker)
// Return empty list normally (ARETURN - simple, ASM-friendly)
return new RuntimeList()
```

**Critical**: We use `ARETURN` (return normally) instead of `GOTO returnLabel`. This is the key to avoiding ASM issues.

#### 3. Loop Boundary Checks

After each statement in **labeled loops** (optimization: only loops with explicit labels like `SKIP:`):

```java
// Call registry checker (single static method call)
mv.visitLdcInsn(labelName);  // Push label
mv.visitMethodInsn(INVOKESTATIC, "RuntimeControlFlowRegistry", 
                   "checkLoopAndGetAction", "(String)I");

// Use TABLESWITCH for clean dispatch (ASM-friendly)
mv.visitTableSwitchInsn(
    1, 3,              // min/max (LAST/NEXT/REDO)
    nextLabel,         // default (continue normally)
    lastLabel,         // 1: LAST
    nextLabel,         // 2: NEXT
    redoLabel          // 3: REDO
);
```

**Why This Works**:
- Single method call: simple, predictable stack state
- TABLESWITCH: native JVM instruction, well-understood by ASM
- No frame-breaking jumps to `returnLabel`
- No complex branching with DUP/ASTORE/conditional jumps

### Optimizations

1. **Only Labeled Loops**: Registry checks only added to loops with explicit labels (e.g., `SKIP:`, `OUTER:`), not all loops
   - Reduces overhead for regular `for`/`while` loops
   - 99% of loops don't need non-local control flow checks

2. **Fast Path**: `checkLoopAndGetAction()` returns 0 immediately if no marker is registered
   - Most calls are no-ops (no active control flow)

3. **Local Control Flow Still Fast**: Within the same loop, `last`/`next`/`redo` still use direct JVM `GOTO` instructions
   - Only cross-subroutine control flow uses the registry

### File Changes

1. **RuntimeControlFlowRegistry.java** (NEW)
   - ThreadLocal storage for ControlFlowMarker
   - `register()`, `checkLoopAndGetAction()`, `clear()`

2. **EmitControlFlow.java**
   - Modified non-local control flow emission
   - Create marker + register + ARETURN (instead of RuntimeControlFlowList + GOTO returnLabel)

3. **EmitBlock.java**
   - Added registry check after each statement in labeled blocks

4. **EmitForeach.java**
   - Added registry check after loop body in foreach loops

5. **EmitStatement.java**
   - Added registry check after loop body in do-while/bare blocks

### Test Results

```
Total tests: 1911
OK:          1911
Not OK:      0
Pass rate:   100.0%
```

**Key Functionality Verified**:
- ✅ `last SKIP` in Test::More (primary goal)
- ✅ Nested labeled loops
- ✅ Non-local `next`/`redo`
- ✅ Mixing local and non-local control flow
- ✅ `goto __SUB__` tail calls (unaffected)
- ✅ All existing tests continue to pass

### Why This Succeeded Where Others Failed

| Approach | Issue | Registry Solution |
|----------|-------|-------------------|
| RuntimeControlFlowList | Complex call-site checks | No call-site checks needed |
| Loop Handlers | Dead code + complex branching | Simple TABLESWITCH at loop boundary |
| GOTO returnLabel | Frame merge issues | Direct ARETURN (simple return) |
| Manual frame hints | Still broke in complex methods | No frame manipulation needed |

**The Key Insight**: ASM can't handle jumps to `returnLabel` from arbitrary points because the stack state varies. By using normal returns (`ARETURN`) and checking the registry at predictable points (loop boundaries), we keep the stack state simple and predictable.

### Perl Semantics

Correctly implements:
- Unlabeled `last` matches innermost loop
- Labeled `last LABEL` matches specific loop
- Non-local control flow crosses subroutine boundaries
- Error messages for invalid usage (e.g., `last` outside loop)

### Performance

- **Local control flow**: No overhead (direct JVM GOTO)
- **Labeled loops**: Small overhead (1 method call + TABLESWITCH per statement)
- **Unlabeled loops**: No overhead (no registry checks)
- **Non-local control flow**: ThreadLocal get/set (very fast)

### Future Optimizations (Optional)

1. **Statement-Level Analysis**: Only add registry checks after statements that could contain subroutine calls
   - Requires deeper AST analysis
   - Would eliminate checks after simple assignments like `$x = 1;`

2. **Flow Analysis**: Track which subroutines can use non-local control flow
   - Only check registry for calls to "dangerous" subs
   - Complex to implement, modest benefit

3. **Selective Enablement**: Environment variable to disable registry checks for performance testing
   - Useful for profiling overhead

## Conclusion

The runtime control flow registry successfully implements Perl's non-local control flow in a JVM-friendly way. By decoupling control flow markers from return values and using simple, predictable bytecode patterns, we avoid ASM frame computation issues while maintaining 100% test compatibility.

**Status**: Ready for merge to master.

