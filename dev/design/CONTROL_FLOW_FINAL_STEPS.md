# Control Flow - Final Steps to Complete

## Current Status

✅ **What's Working:**
- Call-site checks work perfectly (tested and confirmed)
- Local control flow (`last`/`next`/`redo` within same method) works
- `goto LABEL`, `goto &NAME`, `goto __SUB__` all work
- Tagged return propagation works
- Unit tests: 100% pass (1980/1980)

❌ **What's Broken:**
- Loop handlers cause ASM `ArrayIndexOutOfBoundsException: Index -1 out of bounds for length 0`
- This breaks non-local control flow (e.g., `last SKIP` from `skip()` sub to SKIP block)

## The Problem

**Loop handlers** generate bytecode that breaks ASM's frame computation:
```
Error: java.lang.ArrayIndexOutOfBoundsException: Index -1 out of bounds for length 0
       at org.objectweb.asm.Frame.merge(Frame.java:1280)
```

**Currently enabled:**
- ✅ `ENABLE_CONTROL_FLOW_CHECKS = true` (call-site checks work!)
- ❌ `ENABLE_LOOP_HANDLERS = false` (breaks ASM)

**Impact:** Without loop handlers, call-site checks jump to `returnLabel` instead of loop handler, so control flow propagates up instead of being caught at loop level.

---

## Plan: Fix Loop Handler ASM Issues

### Step 1: Identify the Bad Bytecode Pattern

**Goal:** Find what bytecode in loop handlers breaks ASM frame computation.

**Actions:**
1. Enable `DEBUG_LOOP_CONTROL_FLOW = true` in both files
2. Create minimal test case (one loop with `last` from subroutine)
3. Use `--disassemble` to examine bytecode
4. Compare with working call-site check bytecode

**Files:** `EmitForeach.java`, `EmitStatement.java`

**Success criteria:** Know exactly which bytecode pattern causes the error.

---

### Step 2: Try Static Frame Hints

**Goal:** Help ASM with explicit frame information at merge points.

**Pattern:** Add `visitFrame()` calls at labels where branches merge:
```java
mv.visitLabel(controlFlowHandler);
mv.visitFrame(F_SAME, 0, null, 0, null);  // Frame hint
// ... handler code ...
```

**Files:** Loop handler emission in `EmitForeach.java`, `EmitStatement.java`

**Success criteria:** ASM error disappears, loop handlers work.

---

### Step 3: Simplify Handler Pattern (if Step 2 fails)

**Goal:** Use simpler bytecode that ASM can verify.

**Current pattern (suspected issue):**
```
// After loop body
INSTANCEOF RuntimeControlFlowList
IFEQ skipHandler
// Jump to handler with complex stack state
```

**Try instead:**
```
// Store result first
ASTORE tempSlot
ALOAD tempSlot
INSTANCEOF RuntimeControlFlowList
IFEQ skipHandler
// Handler has known stack state
```

**Files:** Loop handler call sites in `EmitForeach.java`, `EmitStatement.java`

**Success criteria:** ASM error disappears, loop handlers work.

---

### Step 4: Test `last SKIP`

**Goal:** Verify non-local control flow works end-to-end.

**Test cases:**
1. `skip()` function with `last SKIP` - should exit SKIP block
2. Nested loops with non-local `last OUTER` through subroutine
3. Run full test suite to verify no regressions

**Files:** Create `unit/last_skip.t`

**Success criteria:** 
- `last SKIP` works correctly
- Test suite pass rate ≥ 99.8%
- No ASM errors

---

### Step 5: Update Workarounds

**Goal:** Re-enable proper `last SKIP` now that it works.

**Actions:**
1. Update `Test::More.pm` - remove `skip()` stub, make it call `last SKIP`
2. Remove `skip_internal()` workaround
3. Remove TestMoreHelper macro if no longer needed
4. Update `dev/import-perl5` patches if any

**Files:** 
- `src/main/perl/lib/Test/More.pm`
- `src/main/java/org/perlonjava/perlmodule/Test/More.java`
- Check for AST transformations related to SKIP

**Success criteria:** Standard Perl `SKIP` blocks work correctly.

---

### Step 6: Full Validation

**Goal:** Verify the complete implementation.

**Actions:**
1. Run full unit test suite: `make test`
2. Run full Perl5 test suite: `make test-all` (or critical subset)
3. Compare with baseline to verify improvements
4. Update MILESTONES.md and FEATURE_MATRIX.md

**Success criteria:**
- Unit tests: 100% pass
- Full suite: improvements in SKIP-heavy tests
- No regressions from baseline

---

## Contingency Plan

**If loop handlers remain unfixable with ASM:**

### Option A: Manual Stack Frames
Use ASM's `COMPUTE_FRAMES` mode and provide manual frame computation instead of letting ASM do it.

### Option B: Handler-per-Method
Instead of one handler per loop, generate a separate static method for each loop's handler. This isolates the complex control flow from ASM's frame computation.

### Option C: Bytecode Post-Processing
Generate bytecode without handlers, then use ASM's tree API to insert handlers in a second pass when frames are already computed.

---

## Timeline Estimate

- Step 1 (Identify): 15-30 min
- Step 2 (Frame hints): 15-30 min
- Step 3 (Simplify): 30-60 min if needed
- Step 4 (Test): 15-30 min
- Step 5 (Workarounds): 30-60 min
- Step 6 (Validation): 30-60 min

**Total: 2-4 hours** (assuming Steps 2 or 3 succeed)

---

## Why This Will Work

1. **Call-site checks already work** - proven by test
2. **The error is specific to loop handlers** - isolated problem
3. **ASM frame issues are well-documented** - known solutions exist
4. **We have a working baseline** - can compare bytecode patterns
5. **Small scope** - just loop handler emission, not the whole system

**This is NOT starting over** - it's debugging one specific ASM issue in an otherwise working system!

