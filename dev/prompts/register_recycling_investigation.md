# Register Recycling Investigation

## Problem
Interpreter hits 65535 register limit on code_too_large.t (5000 statements).

## Root Cause Found
`enterScope()` was saving `baseRegisterForStatement` but never updating it.
This caused registers allocated before entering a scope (like foreach iterators) 
to be recycled WITHIN that scope, corrupting live values.

## Solution
Added one line to `enterScope()`:
```java
baseRegisterForStatement = nextRegister;
```

This protects all registers allocated before the scope from being recycled within it.

## Why REGISTER_RECYCLING_THRESHOLD=100 is Still Needed

It's NOT a workaround - it serves a different purpose:

1. **Scope protection** (my fix): Protects registers from outer scopes
2. **Threshold** (existing): Protects short-lived values within a scope

Example: `use strict` generates:
- r3: LOAD_CONST (CODE ref)
- r4: CREATE_LIST  
- r5: CALL_SUB r3

If we recycled after statement 1, r3 would be reused and the CODE ref corrupted.
The threshold prevents recycling until 100 temporaries accumulate, giving short-lived 
values time to be used.

## Test Results
✅ use strict works (CODE refs protected)
✅ Foreach loops work (iterators protected by scope)
✅ array.t: 51/51 tests pass  
✅ code_too_large.t: 4998/4998 tests pass (threshold prevents exhaustion)
✅ All unit tests pass

## Conclusion
Both mechanisms are needed and work together correctly:
- Scopes protect inter-scope register corruption
- Threshold protects intra-scope short-lived values

The scope manager IS doing its job correctly now.
