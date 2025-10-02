# Fix Read-Only Undef in Chop Operations

## Objective
Fix the "Modification of a read-only value attempted" error when using chop on `+()` (unary plus on empty list), which currently crashes op/chop.t at line 245.

## Problem Statement
When `chop(+())` is executed, PerlOnJava throws a read-only modification error, but standard Perl allows this operation. This causes op/chop.t to crash at test 138.

## Current Status
- **Test file:** t/op/chop.t
- **Crash at:** line 245 (`map chop(+()), ('')x68;`)
- **Error:** "Modification of a read-only value attempted"
- **Impact:** Test suite crashes, unable to run remaining tests

## Root Cause Analysis

### Investigation Done (45 minutes)
1. Used `--disassemble` to trace bytecode generation
2. Found that `+()` generates: `INVOKESTATIC org/perlonjava/operators/Operator.undef()`
3. `Operator.undef()` returns `scalarUndef`, a cached read-only value
4. Also found `RuntimeList.scalar()` returns `scalarUndef` for empty lists

### Current Implementation
```java
// In Operator.java
public static RuntimeScalar undef() {
    return scalarUndef; // Returns cached read-only value
}

// In RuntimeList.java
public RuntimeScalar scalar() {
    if (isEmpty()) {
        return scalarUndef; // Returns cached read-only value
    }
    return elements.getLast().scalar();
}
```

### Attempted Fixes That Failed
1. **Changed Operator.undef() to return new RuntimeScalar()** - Still fails
2. **Changed RuntimeList.scalar() to return new RuntimeScalar()** - Still fails
3. **Created undefLvalue() method** - Couldn't identify where to use it

### The Real Issue
The problem appears to be in how `addToScalar()` works. When `+()` is evaluated:
1. It calls `Operator.undef()` to get an initial value
2. Then calls `addToScalar()` on that value
3. But somewhere in this chain, a read-only value is being created or preserved

## Proposed Solutions

### Option 1: Track Lvalue Context
Modify the code generation to detect when undef is needed in an lvalue context (like as an argument to chop) and use a different method.

### Option 2: Make All Undefs Modifiable
Always return modifiable undefs, accepting the performance cost. This is simpler but may have memory implications.

### Option 3: Fix addToScalar Chain
Investigate the full chain of `addToScalar()` calls to find where the read-only value is introduced.

## Test Cases to Verify

```perl
# Minimal test case
chop(+());  # Should not error

# From op/chop.t line 245
map chop(+()), ('')x68;  # Should not error

# Comparison
chop(undef);  # Should error (literal undef is read-only)
my $x; chop($x);  # Should work (variable undef is modifiable)
```

## Implementation Checklist
- [ ] Identify exact code path from `+()` to chop
- [ ] Determine where read-only flag is set
- [ ] Implement fix to return modifiable undef in this context
- [ ] Test with op/chop.t
- [ ] Verify no regressions in other tests

## Expected Impact
- **Direct fix:** 1 crash in op/chop.t
- **Indirect benefit:** Tests 138-148 can run (currently blocked by crash)
- **Potential issues:** May affect performance if all undefs become modifiable

## Complexity Assessment
- **Estimated effort:** 1-2 hours (complex interaction between multiple systems)
- **Risk level:** Medium (affects core scalar operations)
- **Files involved:**
  - Operator.java
  - RuntimeList.java
  - RuntimeScalar.java
  - Possibly EmitOperator.java

## Next Steps
1. Trace the exact execution path with debugger
2. Identify where RuntimeScalarReadOnly is introduced
3. Implement targeted fix for this specific case
4. Consider broader implications for other similar operations

## Test Files Preserved
- `dev/sandbox/readonly_undef_chop_test.pl` - Test cases for the issue

## Cleanup Checklist
- [ ] Remove `dev/sandbox/readonly_undef_*.pl` test files after fix
- [ ] Revert changes to `Operator.java` and `RuntimeList.java` if not working
- [ ] Clean up any `test_*.pl` files in project root
- [ ] Move useful test cases to `src/test/resources/` if fix is successful

## Code References
- Bytecode generation: `EmitOperator.emitUndef()`
- Operator implementation: `Operator.undef()`
- List scalar conversion: `RuntimeList.scalar()`
- Scalar assignment: `RuntimeList.addToScalar()`
