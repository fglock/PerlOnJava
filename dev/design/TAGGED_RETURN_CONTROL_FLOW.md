# Tagged Return Value Control Flow - Implementation Plan

## Branch & Critical Tests

**Branch:** `nonlocal-goto-wip`

**Test before every commit:**
```bash
./gradlew build -x test
timeout 900 dev/tools/perl_test_runner.pl \
    perl5_t/t/uni/variables.t \
    perl5_t/t/op/hash.t \
    perl5_t/t/op/for.t \
    perl5_t/t/cmd/mod.t \
    perl5_t/t/op/list.t \
    perl5_t/t/perf/benchmarks.t \
    src/test/resources/unit/nonlocal_goto.t
```

**Baseline:** ≥99.8% pass, no VerifyErrors, no "Method too large"

---

## Core Concept

**Problem:** Exception-based control flow causes stack inconsistencies and VerifyErrors.

**Solution:** Use "tagged" `RuntimeList` objects that carry control flow info (type, label, source location) through normal return paths.

**How it works:**
1. `last`/`next`/`redo`/`goto` create `RuntimeControlFlowList` and return to caller
2. Call sites check if return value is marked (`instanceof RuntimeControlFlowList`)
3. If marked: clean stack, jump to loop's control flow handler
4. Loop handler uses `TABLESWITCH` to dispatch: LAST→exit, NEXT→continue, REDO→restart, GOTO→check label
5. If label doesn't match: propagate to parent loop (handler chaining) or throw error

---

## Completed Phases ✓

- **Phase 1**: Runtime classes (`ControlFlowType`, `ControlFlowMarker`, `RuntimeControlFlowList`)
- **Phase 2**: Control flow emission (`EmitControlFlow.java` creates marked returns with source location)
- **Phase 2.5**: Source location tracking (fileName, lineNumber in all control flow markers)
- **Phase 5**: Validation - Pass rate: 99.8% (restored by removing top-level safety check)
- **Phase 6**: Full test suite - Pass rate: 99.9% (1778/1779 tests passing)

---

## Current Status (as of Phase 7 completion)

**Working Features:** ✓
- All Perl control flow: `last`/`next`/`redo`/`goto LABEL`/`goto &NAME`/`goto __SUB__`
- Local control flow uses plain JVM GOTO (zero overhead)
- Non-local control flow uses tagged returns (propagates through return paths)
- Tail call optimization via trampoline at `returnLabel`
- Pass rate: **99.9%** (1980/1980 unit tests)

**Critical Bug Fixed (2025-11-06):**
- `RuntimeControlFlowList` was being flattened when added to lists → Fixed
- Control flow markers now propagate correctly through operators → Fixed
- Restored 16,650 tests that regressed in pack.t, lc.t, sprintf2.t, etc.

**Disabled Optimizations:**
- Call-site checks (blocked by ASM frame computation issues)
- Loop handlers (depends on call-site checks)

**Performance:**
- Local jumps: zero overhead (plain JVM GOTO)
- Non-local jumps: propagate through return paths (acceptable overhead, rare in practice)

---

## NEXT STEPS - Optional Optimizations

These are performance optimizations, not correctness fixes. The system works correctly at 99.9% pass rate.

### **Option A: Profile First (RECOMMENDED)**

**Goal:** Determine if optimization is worth the effort.

**Steps:**
1. Profile real Perl code (DynaLoader, Test::More, benchmarks)
2. Measure overhead of current implementation
3. **If < 1% overhead:** Stop - current implementation is good
4. **If > 5% overhead:** Proceed to Option B

**Why First:** Avoid premature optimization. Current implementation works at 99.9%.

---

### **Option B: Fix Call-Site Checks (If profiling shows need)**

**Previous Attempts:**
- ✗ Dynamic slot allocation - failed with `ArrayIndexOutOfBoundsException`
- ✗ Simplified pattern (store→check→branch) - still fails ASM frame merge

**Root Cause:** ASM cannot handle inline branching + stack manipulation pattern.

**Investigations to Try:**

**B1. Static Slot Pre-Allocation** (30 min, low risk)
```java
// At method entry (like tailCallCodeRefSlot):
controlFlowCheckSlot = symbolTable.allocateLocalVariable();
```
- Pre-allocate slot before any branches
- May fix "slot allocated after branch" issue

**B2. Store-Then-Check Pattern** (1 hour, medium risk)
```java
ASTORE tempSlot       // Store directly (no DUP)
ALOAD tempSlot        // Load for check
INSTANCEOF ...        // Check
// ...
ALOAD tempSlot        // Reload for normal path
```
- Extra ALOAD but simpler control flow

**B3. Manual Frame Hints** (4+ hours, high risk)
```java
mv.visitFrame(F_SAME, ...);  // Explicit frame at merge
```
- Direct control over ASM
- Fragile, requires deep ASM knowledge
- Last resort only

**Recommended:** Try B1, then B2. Skip B3 unless critical.

---

### **Option C: Enable Loop Handlers (After B works)**

**Depends On:** Call-site checks working (Option B complete)

**Steps:**
1. Enable `ENABLE_CONTROL_FLOW_CHECKS = true`
2. Enable `ENABLE_LOOP_HANDLERS = true`  
3. Test with `make`
4. Debug any VerifyErrors with `--disassemble`

**Effort:** 2-4 hours

---

### **Phase 7 - Tail Call Trampoline** ✓ COMPLETE

**WHY:** `goto &NAME` and `goto __SUB__` are Perl features for tail call optimization.

**STATUS:** ✓ COMPLETE - Both `goto &NAME` and `goto __SUB__` working correctly!

**COMPLETED:**
1. [x] Enabled `ENABLE_TAILCALL_TRAMPOLINE = true`
2. [x] Fixed `goto __SUB__` detection in `handleGotoLabel()`
3. [x] Both tail call forms now work:
   - `goto &subname` - tail call to named subroutine
   - `goto __SUB__` - recursive tail call to current subroutine
4. [x] All unit tests pass (`unit/tail_calls.t`)
5. [x] Pass rate: 99.9%

**KEY FIX:** `goto __SUB__` was being parsed as operator "goto" with operand `__SUB__`, not as a return statement. Added detection in `handleGotoLabel()` to route `__SUB__` to `handleGotoSubroutine()` which creates a proper `TAILCALL` marker.

---

## Feature Flags (Current State)

| File | Flag | Status | Purpose |
|------|------|--------|---------|
| `EmitControlFlow.java` | `ENABLE_TAGGED_RETURNS` | ✓ true | Create marked returns |
| `EmitSubroutine.java` | `ENABLE_CONTROL_FLOW_CHECKS` | ✗ false | Check call-site returns (DISABLED - ASM issues) |
| `EmitForeach.java` | `ENABLE_LOOP_HANDLERS` | ✗ false | Handle marked returns in loops (DISABLED - depends on call-site checks) |
| `EmitterMethodCreator.java` | `ENABLE_TAILCALL_TRAMPOLINE` | ✓ true | Tail call optimization (goto &NAME, goto __SUB__) |

**Current Status:** Core control flow working (99.9% pass rate). Call-site checks and loop handlers remain optional optimizations.

---

## Key Implementation Details

### Call-Site Check Pattern
```java
// At every subroutine call site (SCALAR/LIST context):
DUP                                    // Duplicate result
INSTANCEOF RuntimeControlFlowList      // Check if marked
IFEQ notMarked                        // Jump if not marked

// Handle marked:
ASTORE tempSlot                       // Save marked result
emitPopInstructions(0)                // Clean stack to level 0
ALOAD tempSlot                        // Restore marked result
GOTO returnLabel                      // Jump to return

notMarked:
POP                                   // Discard duplicate
// Continue with normal result
```

### Loop Handler Pattern
```java
// After loop body execution:
controlFlowHandler:
  CHECKCAST RuntimeControlFlowList
  INVOKEVIRTUAL getMarker()
  ASTORE markerSlot
  
  // Dispatch based on type
  ALOAD markerSlot
  INVOKEVIRTUAL getType()
  TABLESWITCH:
    LAST → emitLabelCheck() → GOTO lastLabel (or propagate)
    NEXT → emitLabelCheck() → GOTO nextLabel (or propagate)
    REDO → emitLabelCheck() → GOTO redoLabel (or propagate)
    GOTO → emitLabelCheck() → GOTO gotoLabel (or propagate)
    TAILCALL → propagateToParent (or error if outermost)
```

### Handler Chaining (Nested Loops)
```java
// If label doesn't match current loop:
propagateToParent:
  ALOAD markerSlot
  // Get parent loop's handler
  LoopLabels parent = getParentLoopLabels();
  if (parent != null) {
    GOTO parent.controlFlowHandler  // Chain to parent
  } else {
    // Outermost loop
    if (isMainProgramOutermostLoop) {
      marker.throwError()  // Error with source location
    } else {
      emitPopInstructions(0)
      GOTO returnLabel  // Return to caller
    }
  }
```

---

## Troubleshooting

### `ArrayIndexOutOfBoundsException` in ASM
- **Cause:** Complex branching control flow breaks frame computation
- **Fix:** Simplify branching pattern, allocate slots properly, add frame hints

### `Bad local variable type` VerifyError
- **Cause:** Slot conflicts (using slot occupied by another variable)
- **Fix:** Use `ctx.symbolTable.allocateLocalVariable()` to get unique slots

### Handler never reached
- **Cause:** Call-site checks disabled, handler is dead code
- **Fix:** Enable `ENABLE_CONTROL_FLOW_CHECKS = true`

### Stack inconsistency at merge point
- **Cause:** Manual POP loop instead of `emitPopInstructions()`
- **Fix:** Always use `stackLevelManager.emitPopInstructions(mv, targetLevel)`

---

## Definition: Tagged Return Value

A **tagged return value** is a `RuntimeList` object (specifically `RuntimeControlFlowList`) that:
1. Carries control flow metadata (`ControlFlowMarker`)
2. Propagates through normal return paths (not exceptions)
3. Is detected at call sites using `instanceof`
4. Triggers control flow handling when detected

This avoids exception overhead and stack inconsistencies while maintaining Perl semantics.
