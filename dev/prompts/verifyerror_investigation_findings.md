# VerifyError Investigation Findings

## Current Status

Attempted to fix VerifyError in ExifTool by implementing the plan to:
1. Restore min-128 baseline for temp slot pre-initialization ✅
2. Preserve original slot indices in closure creation ❌

## What Worked

- Restored min-128 baseline (and tried min-256)
- Unit tests pass with this change
- Simple closures work

## What Didn't Work

### Attempt 1: Preserve Slot Indices with `addVariableWithIndex()`

Added new methods:
- `SymbolTable.addVariableWithIndex(SymbolEntry)`
- `ScopedSymbolTable.addVariableWithIndex(SymbolEntry)`

Modified `EmitSubroutine.java` to call `addVariableWithIndex()` instead of `addVariable()`.

**Result**: `NegativeArraySizeException: -24` in ASM's Frame.merge
- Suggests bytecode generation created invalid state
- ASM couldn't compute stack frames

### Attempt 2: Increase Baseline to 256

Changed min baseline from 128 to 256 slots.

**Result**: VerifyError persists at slot 68
- Slot 68 shows as `top` (uninitialized) at offset 4498
- Bytecode shows slot 68 IS being initialized (ACONST_NULL, ASTORE 68)
- Suggests control flow issue: JVM sees path where slot 68 accessed before initialization

## Key Observations

1. **ExifTool-specific**: The error occurs in `org/perlonjava/anon830` which is a deeply nested closure
2. **Slot 68 mystery**: Despite initialization code present, JVM verifier sees it as `top`
3. **Frame locals pattern**: Shows gaps (null) mixed with defined slots, and some `top` slots (59-60, 68)
4. **Control flow hypothesis**: There may be a jump/branch that bypasses initialization

## The Core Problem (from plan)

The plan identified that when closures are created:
1. Parent variables have sparse indices (e.g., [0, 5, 10, 15])
2. `addVariable()` reassigns sequential indices ([0, 1, 2, 3])
3. This creates mismatch:
   - Constructor expects sequential arguments
   - Instantiation loads from original sparse parent slots
   - Field assignments use wrong indices

## Why Index Preservation Failed

Creating `new ScopedSymbolTable()` starts fresh with index 0. Even with `addVariableWithIndex()`:
- The symbol table state may not match parent context
- Frame computation in ASM failed with negative size
- Suggests deeper structural issue

## Possible Paths Forward

### Option A: Debug the Control Flow
- Use `--disassemble` to examine the bytecode paths
- Understand why JVM sees uninit path to slot 68
- May reveal actual bug in bytecode generation logic

### Option B: Fix the Constructor/Field Loading
- Instead of preserving indices in symbol table, fix how closure loads variables
- Ensure constructor and field assignments match actual parent slot numbers

### Option C: Investigate Frame Computation
- The negative array size suggests ASM's frame merge is seeing invalid state
- May need to ensure proper frame setup before/after closure creation

### Option D: Simpler Workaround
- Detect when we're in a closure and force all parent variables into sequential slots
- Trade memory for correctness

## Questions

1. Why does index preservation cause NegativeArraySizeException in ASM?
2. Why does slot 68 show as `top` despite initialization code?
3. Is the control flow jumping over initialization, or is frame computation wrong?
4. Can we test with a simpler reproducer than ExifTool?

## Next Steps

Need guidance on which path to pursue. The root cause analysis seems correct, but the implementation approach may need revision.
