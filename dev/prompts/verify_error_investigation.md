# VerifyError Investigation: ExifTool anon830

## Problem
`java.lang.VerifyError: Bad local variable type` occurs in `org/perlonjava/anon830.apply()` at bytecode offset 4064.
- Slot 68 contains `top` (uninitialized) when code tries to `aload` from it
- Slot 70 contains a `RuntimeHash`, so captured variables extend past slot 68

## Root Cause Analysis
The error occurs because there's a gap in slot initialization:
- Slots 3-67: Captured variables (initialized in loop 539-551)
- Slots 68-69: **UNINITIALIZED** (marked as `top`)
- Slot 70+: More captured variables or temporaries

## Attempted Fix
Modified `EmitterMethodCreator.java`:
1. Reset symbol table index to `Math.max(env.length, currentSymbolTableIndex)` to prevent going backward
2. Pre-initialize from `env.length` instead of `resetIndex` to avoid gaps
3. Restored `Math.max(128, ...)` baseline for temp slot pre-initialization

## Current Status
- Fix works for simple closures
- **anon830 still fails** - suggests env.length might be 68, leaving a gap before slot 70

## Next Steps
1. Add logging to see actual env.length and resetIndex for anon830
2. Investigate why slot 70 has a value if env.length < 70
3. May need to pre-initialize ALL slots from env.length to highest used slot
4. Consider whether symbol table tracks all captured variables correctly
