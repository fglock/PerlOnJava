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

---

## NEXT STEPS - Implementation Order

### **CURRENT: Phase 3 - Fix Call-Site Checks (BLOCKED)**

**STATUS:** ✗ BLOCKED by ASM frame computation issues

**COMPLETED:**
- ✓ Dynamic slot allocation (`controlFlowTempSlot`)  
- ✓ Simplified pattern (store→check→branch)
- ✗ Still fails with `ArrayIndexOutOfBoundsException` in ASM Frame.merge()

**ROOT CAUSE:** ASM's COMPUTE_FRAMES cannot handle inline branching checks that manipulate the stack. The pattern `DUP → ASTORE → ALOAD → instanceof → branch → pop/cleanup → GOTO` creates control flow paths that ASM cannot compute frames for, even with simplified patterns.

**WHY THIS IS HARD:** 
- Call sites are in expression context (value on stack)
- Need to check value without consuming it (requires DUP)
- Need to clean stack if marked (requires knowing stack depth)
- Branching + stack manipulation = ASM frame computation failure

**ALTERNATIVE APPROACHES TO INVESTIGATE:**

1. **Explicit Frame Hints** (High effort, error-prone)
   - Manually call `mv.visitFrame()` at every branch point
   - Requires tracking exact local variable types and stack types
   - Fragile - breaks if bytecode changes

2. **VOID Context Checks** (Promising!)
   - Only check in VOID context (after value is POPped)
   - No DUP needed, no stack manipulation
   - But: marked returns would be lost in VOID context
   - Would need to store EVERY call result temporarily

3. **No Inline Checks - Return Path Only** (RECOMMENDED)
   - Remove inline call-site checks entirely
   - Only check at `returnLabel` (global return point)
   - Loop handlers become "optional optimization" for nested loops
   - Non-local control flow ALWAYS returns to caller, caller checks and re-dispatches
   - **This matches how exceptions work!**

**RECOMMENDED PATH FORWARD:**
Skip call-site checks for now. Focus on validating that Phase 2 (creating marked returns) and Phase 5 (top-level safety in RuntimeCode.apply()) work correctly. Call-site checks can be added later as an optimization if needed.

**TEST:** Basic control flow already works without call-site checks:
```bash
./jperl -e 'for (1..3) { print "$_\n"; last; }'  # ✓ Works!
```

---

### **Phase 4 - Enable Loop Handlers (SKIPPED)**

**STATUS:** ✗ SKIPPED - depends on call-site checks which are blocked

**WHY NEEDED:** Loop handlers would optimize nested loops by processing marked returns immediately instead of propagating to caller.

**WHY SKIPPED:** Loop handlers are only reachable via call-site checks. Without call-site checks, the handler code is unreachable (dead code) and ASM frame computation fails.

**ALTERNATIVE:** Non-local control flow propagates through return path. `RuntimeCode.apply()` catches it at the top level. This works but is less efficient for deeply nested loops.

---

### **NEXT: Phase 5 - Validate Current Implementation**

**WHY:** With call-site checks and loop handlers skipped, we need to validate that the current "exception-like" propagation model works correctly.

**CURRENT BEHAVIOR:**
- `last`/`next`/`redo`/`goto` create `RuntimeControlFlowList` and return
- Control flow propagates up the call stack via normal returns  
- `RuntimeCode.apply()` catches marked returns and throws error with source location
- Local `last`/`next`/`redo` in loops work via fast GOTO (no marked returns)

**TODO:**
1. [x] Test local control flow (unlabeled last/next/redo in immediate loop) - **DONE ✓**
2. [ ] Test non-local control flow from subroutine
   ```perl
   OUTER: for (1..3) {
       sub { last OUTER }->(); # Should error at RuntimeCode.apply()
   }
   ```
3. [ ] Test that error messages include source location
4. [ ] Run critical regression tests

**COMMIT MESSAGE:**
```
test: Validate tagged return propagation without call-site checks

- Local control flow works via fast GOTO
- Non-local control flow propagates via return path
- RuntimeCode.apply() catches and errors with source location
- Pass rate: maintained at ≥99.8%

Phase 5 (validation) complete!
```

---

### **Phase 6 - Full Test Suite**

**WHY:** Verify that the implementation works across all Perl tests, not just critical regressions.

**TODO:**
1. [ ] Run `make test` (full test suite)
2. [ ] Compare results with baseline (should maintain ≥99.8%)
3. [ ] Investigate any new failures
4. [ ] Document any known limitations

**COMMIT MESSAGE:**
```
test: Full test suite validation for tagged return control flow

- Pass rate: X.X% (baseline: 99.8%)
- All critical tests passing
- No new VerifyErrors or Method too large errors

Phase 6 (testing) complete!
```

---

### **Phase 7 - Tail Call Trampoline** (Optional Enhancement)

**WHY:** `goto &NAME` is a Perl feature for tail call optimization. Currently disabled.

**STATUS:** Code written in `EmitterMethodCreator.java` but disabled (`ENABLE_TAILCALL_TRAMPOLINE = false`).

**TODO:**
1. [ ] Enable `ENABLE_TAILCALL_TRAMPOLINE = true`
2. [ ] Test `goto &subname` with argument passing
3. [ ] Ensure trampoline loop terminates correctly

**COMMIT MESSAGE:**
```
feat: Enable tail call trampoline for goto &NAME

- Enabled ENABLE_TAILCALL_TRAMPOLINE
- Tail calls now properly optimized
- Tested with recursive examples

Phase 7 (tail calls) complete!
```

---

## Feature Flags (Current State)

| File | Flag | Status | Purpose |
|------|------|--------|---------|
| `EmitControlFlow.java` | `ENABLE_TAGGED_RETURNS` | ✓ true | Create marked returns |
| `EmitSubroutine.java` | `ENABLE_CONTROL_FLOW_CHECKS` | ⚠️ true | Check call-site returns (BROKEN) |
| `EmitForeach.java` | `ENABLE_LOOP_HANDLERS` | ✗ false | Handle marked returns in loops |
| `EmitterMethodCreator.java` | `ENABLE_TAILCALL_TRAMPOLINE` | ✗ false | Tail call optimization |

**Goal:** All flags ✓ true, no VerifyErrors, ≥99.8% pass rate

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
