# Control Flow Fix: Implementation Results and Findings

**Date:** January 6, 2026  
**Related:** `CONTROL_FLOW_FINAL_STATUS.md`, `CONTROL_FLOW_FINAL_STEPS.md`

## Summary

This document captures the results of implementing the control-flow fix for tagged returns (`last LABEL`, `next LABEL`, `redo LABEL`, `goto LABEL`) and the subsequent work to resolve ASM `Frame.merge` crashes caused by non-empty JVM operand stacks at merge points.

## Phase 1: Tagged Returns (Completed ✓)

**Goal:** Enable `last LABEL`, `next LABEL`, `redo LABEL`, `goto LABEL` to work across subroutine boundaries.

**Implementation:**
- Introduced `RuntimeControlFlowList` with `ControlFlowMarker` to carry control-flow metadata through the call stack
- Modified `EmitSubroutine` to check every subroutine return for control-flow markers and dispatch to appropriate loop labels or propagate upward
- Updated `EmitControlFlow` to emit markers for non-local jumps (when target label is not in current scope)

**Result:** ✓ **SUCCESS**
- All 11 tests in `skip_control_flow.t` pass
- Tagged returns work correctly across nested subroutines and loops

## Phase 2: ASM Frame.merge Crashes (In Progress)

**Problem:** After implementing tagged returns, `pack.t` (which loads `Data::Dumper` and `Test2::API`) started failing with:
```
java.lang.ArrayIndexOutOfBoundsException: Index 1 out of bounds for length 1
	at org.objectweb.asm.Frame.merge(Frame.java:1280)
```

**Root Cause:** 
The JVM operand stack was non-empty at certain `GOTO` instructions that jump to merge points (labels with multiple incoming edges). ASM's frame computation requires all incoming edges to a merge point to have compatible stack heights.

### Approach 1: Spill-to-Local with Preallocated Pool (Current)

**Strategy:** Before evaluating subexpressions that can produce non-local control flow (subroutine calls, operators), spill intermediate operands to local variables, keeping the JVM operand stack empty.

**Implementation:**
1. **Preallocated Spill-Slot Pool:**
   - Added `spillSlots[]` array to `JavaClassInfo`, preallocated in `EmitterMethodCreator`
   - Default: 16 slots (configurable via `JPERL_SPILL_SLOTS` env var)
   - `acquireSpillSlot()` / `releaseSpillSlot()` manage the pool
   - Fallback to `allocateLocalVariable()` if pool exhausted

2. **Applied Spills:**
   - **String concatenation** (`EmitOperator.emitConcatenation`): spill LHS before evaluating RHS
   - **Binary operators** (`EmitBinaryOperator`): spill LHS before evaluating RHS (for operators that can trigger control flow)
   - **Scalar assignment** (`EmitVariable.handleAssignOperator`): spill RHS value before evaluating LHS

3. **Control Switch:**
   - `JPERL_NO_SPILL_BINARY_LHS=1` disables spills (for A/B testing)
   - Default: spills enabled

**Advantages:**
- ✓ By-construction invariant: operand stack is empty when we evaluate potentially-escaping subexpressions
- ✓ No dependency on `TempLocalCountVisitor` sizing (uses fixed preallocated pool)
- ✓ Deterministic and predictable
- ✓ Works with ASM's existing frame computation

**Limitations:**
- ⚠ May not cover all edge cases (still finding failure sites in `pack.t`)
- ⚠ Could theoretically exhaust the spill-slot pool on very deeply nested expressions (though 16 slots should be sufficient for typical code)

**Status:** In progress. Most common patterns covered, but `pack.t` still fails on some edge cases.

### Approach 2: AnalyzerAdapter-Based Stack Cleanup (Abandoned)

**Strategy:** Wrap the generated `apply()` method's `MethodVisitor` with ASM's `AnalyzerAdapter`, which tracks the operand stack linearly. Before each non-local `GOTO`, emit `POP/POP2` instructions to empty the stack based on `AnalyzerAdapter.stack`.

**Implementation Attempted:**
1. Added `asm-commons` dependency
2. Wrapped `apply()` method visitor with `AnalyzerAdapter` in `EmitterMethodCreator`
3. Added `JavaClassInfo.emitPopOperandStackToEmpty(mv)` to emit POPs based on adapter's stack
4. Called `emitPopOperandStackToEmpty()` before all `GOTO returnLabel` and loop label jumps

**Why It Failed:**
- ❌ `AnalyzerAdapter` tracks the stack **linearly** during emission, not across control-flow merges
- ❌ At a `GOTO L`, the adapter only knows the stack state on the **current linear path**
- ❌ It cannot know what stack state other predecessor paths will have when they reach `L`
- ❌ Result: we can "pop to empty" on one path, but another path might still arrive at the same label with items on the stack → incompatible stack heights at merge

**Fundamental Limitation:**
`AnalyzerAdapter` is not a full control-flow dataflow analyzer. It cannot guarantee the invariant "all incoming edges to a merge point have the same stack height" because it doesn't compute merged states during emission.

**Conclusion:** This approach cannot work without a full two-pass compiler (emit bytecode, analyze with `Analyzer`, rewrite to insert POPs on all incoming edges).

### Approach 3: Full Control-Flow Analysis + Rewrite (Not Attempted)

**Strategy:** 
1. Generate bytecode normally
2. Run ASM's `Analyzer` to compute stack heights at every instruction (including merges)
3. Rewrite the method to insert `POP/POP2` on all incoming edges to merge points as needed

**Advantages:**
- ✓ Would be truly systematic and handle all cases
- ✓ No need for manual spilling or stack tracking during emission

**Disadvantages:**
- ❌ Requires a full additional compiler pass
- ❌ Complex rewriting logic
- ❌ May be overkill for this problem

**Status:** Not pursued. The spill-slot pool approach is simpler and should be sufficient.

## Current Status

**Working:**
- ✓ Tagged returns (`last LABEL`, `next LABEL`, `redo LABEL`, `goto LABEL`) across subroutine boundaries
- ✓ `skip_control_flow.t` (11/11 tests pass)
- ✓ Spill-slot pool infrastructure in place
- ✓ Spills applied to concat, binary operators, scalar assignment

**In Progress:**
- ⚠ `pack.t` still fails with ASM frame merge errors at `anon453` instructions 104/108
- Root cause: When a subroutine call (inside an expression) returns a control-flow marker, the propagation logic tries to jump to `returnLabel`, but there are values on the JVM operand stack from the outer expression context
- Current `stackLevelManager` doesn't track the actual JVM operand stack during expression evaluation - it only tracks "logical" stack levels (loop nesting, etc.)

**Two Possible Solutions:**

### Solution A: Comprehensive Stack Tracking (Preferred)
Track every JVM operand stack operation throughout expression evaluation:
- Add `increment(1)` after every operation that pushes to stack
- Add `decrement(1)` after every operation that pops from stack  
- This would make `stackLevelManager.getStackLevel()` accurately reflect the JVM operand stack depth
- Then `stackLevelManager.emitPopInstructions(mv, 0)` would correctly clean the stack before control-flow propagation

**Requires changes to:**
- All binary operators
- All method calls
- All local variable stores/loads
- All expression evaluation sites

### Solution B: Targeted Spills (Current Approach)
Continue applying spills to ensure stack is empty before subroutine calls:
- Already applied to: concat, binary ops, scalar assignment
- Still need to identify remaining patterns where subroutine calls happen with non-empty stack

**Next Steps:**
1. Decide between Solution A (comprehensive tracking) vs Solution B (targeted spills)
2. If Solution A: Implement stack tracking in binary operators and expression evaluation
3. If Solution B: Continue identifying and fixing specific failure patterns
4. Re-test `pack.t` until it passes
5. Run full regression suite
6. Prepare PR

## Lessons Learned

1. **By-construction invariants are more reliable than runtime tracking** when dealing with ASM frame computation.

2. **`AnalyzerAdapter` is not sufficient for merge-point analysis** — it only tracks linear paths during emission.

3. **Preallocated resource pools** (spill slots) are better than dynamic allocation (`TempLocalCountVisitor` + buffer) for avoiding VerifyError and frame computation issues.

4. **The spill approach is not "whack-a-mole"** if we apply it systematically to all expression evaluation sites that can trigger non-local control flow (subroutine calls, operators that can return marked lists).

5. **Environment variable switches** (`JPERL_NO_SPILL_BINARY_LHS`) are valuable for A/B testing and debugging.

## References

- `CONTROL_FLOW_FINAL_STATUS.md` - Original design for tagged returns
- `CONTROL_FLOW_FINAL_STEPS.md` - Implementation steps
- `ASM_FRAME_COMPUTATION_BLOCKER.md` - Earlier notes on frame computation issues
