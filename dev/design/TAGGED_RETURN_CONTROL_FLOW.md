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

### **CURRENT: Phase 3 - Debug Call-Site Checks**

**STATUS:** Call-site checks are enabled but causing `ArrayIndexOutOfBoundsException: Index -1` in ASM frame computation.

**WHY:** Call-site checks make loop handlers reachable (without them, handlers are dead code and ASM fails). But complex branching control flow with `DUP`/`instanceof`/`ASTORE`/stack cleanup breaks ASM's frame computation.

**ISSUE:** Using fixed slot 200 (`CONTROL_FLOW_TEMP_SLOT`) may conflict with actual local variables.

**TODO:**
1. [ ] **Investigate slot allocation**
   - Check if `CONTROL_FLOW_TEMP_SLOT = 200` conflicts with method's actual slots
   - Consider using `ctx.symbolTable.allocateLocalVariable()` dynamically (like tail call trampoline)
   
2. [ ] **Simplify control flow check pattern**
   - Current: `DUP → instanceof → branch → ASTORE → cleanup → ALOAD → GOTO`
   - ASM struggles with this complex branching + stack manipulation
   - Try: Allocate local var at method start, store result immediately, check, branch
   
3. [ ] **Add frame hints if needed**
   - May need explicit `mv.visitFrame()` calls around complex branches
   
4. [ ] **Test with minimal example**
   ```bash
   ./jperl -e 'for (1..3) { print "$_ "; last; }'
   ```

**COMMIT MESSAGE:** (after fix)
```
fix: Resolve ASM frame computation in call-site checks

- Fixed slot allocation for control flow temp storage
- Simplified branching pattern for ASM compatibility
- Call-site checks now work without VerifyErrors

Phase 3 (call-site checks) complete!
```

---

### **Phase 4 - Enable Loop Handlers**

**WHY:** Loop handlers process marked returns (dispatch LAST/NEXT/REDO/GOTO to correct actions). They're currently disabled because they depend on call-site checks.

**STATUS:** Code is written in `EmitForeach.emitControlFlowHandler()` but disabled (`ENABLE_LOOP_HANDLERS = false`).

**TODO:**
1. [ ] Set `ENABLE_LOOP_HANDLERS = true` in `EmitForeach.java`
2. [ ] Test foreach loops with labeled control flow
3. [ ] Add handlers to `EmitStatement.java` for:
   - `For3Node` (C-style for loops)
   - `while`/`until` loops
   - Bare blocks (labeled `{ }`)

**FILES:**
- `src/main/java/org/perlonjava/codegen/EmitForeach.java` - already has handler
- `src/main/java/org/perlonjava/codegen/EmitStatement.java` - needs handlers added

**COMMIT MESSAGE:**
```
feat: Enable loop control flow handlers

- Enabled ENABLE_LOOP_HANDLERS in EmitForeach
- Added handlers to EmitStatement for for/while/until/bareblock
- Handlers use TABLESWITCH to dispatch control flow
- Handler chaining for nested loops

Phase 4 (loop handlers) complete!
```

---

### **Phase 5 - Top-Level Safety**

**WHY:** Control flow that escapes all loops must throw a descriptive error (e.g., "Label not found for 'last SKIP'").

**STATUS:** Partially implemented in `RuntimeCode.apply()` and loop handlers.

**TODO:**
1. [ ] Verify main program detection works (`isMainProgram` flag set correctly)
2. [ ] Test that outermost loop in main program calls `marker.throwError()` for unmatched labels
3. [ ] Test that subroutine-level unhandled control flow also errors properly

**TEST CASES:**
```perl
# Should error: "Label not found for 'last OUTER'"
sub foo { last OUTER; }
foo();

# Should work: SKIP is a loop label in test files
SKIP: { last SKIP; }
```

**COMMIT MESSAGE:**
```
feat: Add top-level control flow error handling

- Main program outermost loops throw descriptive errors
- Source location included in error messages
- Subroutine-level unhandled flow also errors

Phase 5 (top-level safety) complete!
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
