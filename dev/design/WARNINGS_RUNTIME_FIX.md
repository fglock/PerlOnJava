# Fix Runtime Warning Checks

## Problem Statement

The `use warnings` pragma doesn't work correctly at runtime. Warning flags are set at compile time but runtime checks (like `substr outside of string`) always return false because `getCurrentScope()` doesn't preserve compile-time state.

### Current Behavior

```perl
use warnings;
my $str = "Short";
my $warned = 0;
local $SIG{__WARN__} = sub { $warned++ };
substr($str, 10, 5) = "long";  # Should warn, but doesn't
print $warned;  # Prints 0, should print 1
```

### Root Cause

1. At compile time, `use warnings` calls `Warnings.useWarnings()` which sets warning flags in the current `ScopedSymbolTable` via `getCurrentScope()`

2. At runtime, when `RuntimeSubstrLvalue.set()` checks `Warnings.warningManager.isWarningEnabled("substr")`, it calls `getCurrentScope()` which returns a static `symbolTable` from `SpecialBlockParser`

3. After compilation completes, this static symbol table may be in a different state (e.g., reset, or in a different scope) than the compile-time scope where `use warnings` was active

### Evidence

```perl
use warnings;
BEGIN { print warnings::enabled("substr") ? "yes" : "no"; }  # Prints "no"
print warnings::enabled("substr") ? "yes" : "no";            # Prints "no"
```

Both compile-time (BEGIN) and runtime checks return false even with `use warnings`.

## Solution Options

### Option A: Compile Warning Checks into Bytecode (Recommended)

Instead of checking warning state at runtime, emit the warning check at compile time when warnings are enabled.

**How it works:**
1. During compilation, when generating code for operations that may warn (substr, numeric conversions, etc.), check if the relevant warning category is enabled in the current compile-time scope
2. If enabled, generate code that unconditionally calls `WarnDie.warn()`
3. If disabled (via `no warnings 'substr'`), don't generate the warning code

**Pros:**
- Matches Perl 5 semantics exactly
- No runtime overhead for disabled warnings
- Simple conceptual model

**Cons:**
- Requires changes to code generation for each warning site
- Runtime-only warning functions (like `warnings::enabled()`) won't work correctly

**Implementation:**
1. In `EmitterVisitor` (JVM backend) and `BytecodeCompiler` (interpreter), check warning state before generating warning calls
2. For `RuntimeSubstrLvalue` and similar runtime classes, add a parameter to indicate whether to warn
3. Pass the compile-time warning state when constructing lvalue objects

### Option B: Store Warning Bits in Generated Code

Pass the compile-time warning bits to runtime functions.

**How it works:**
1. At compile time, capture the warning BitSet state
2. Pass it as a parameter to runtime operations that may warn
3. Runtime checks use the passed bits instead of `getCurrentScope()`

**Implementation:**
1. Add `warningBits` parameter to `RuntimeSubstrLvalue` constructor
2. Store bits in the lvalue object
3. Check stored bits in `set()` instead of calling `isWarningEnabled()`

**Pros:**
- Preserves exact compile-time state
- Works with lexical scoping

**Cons:**
- Increases object size and parameter counts
- Complex to thread through all code paths

### Option C: Fix getCurrentScope() to Track Runtime State

Make `getCurrentScope()` return the correct scope at runtime.

**How it works:**
1. Track the "current warning state" in a thread-local or call-stack-based structure
2. Update it when entering/exiting scopes at runtime
3. Runtime checks read from this tracked state

**Cons:**
- Complex to implement correctly
- Performance overhead for scope tracking
- May not match Perl 5 semantics exactly

### Option D: Unconditional Warnings (Simplest)

Always emit warnings for runtime errors like "substr outside of string", regardless of warning state.

**Rationale:**
- These are serious runtime issues that should always be visible
- Matches the behavior on master branch before the regression
- Perl 5 actually throws an error (dies) for substr outside of string in lvalue context

**Implementation:**
1. Remove the `isWarningEnabled()` check from `RuntimeSubstrLvalue.set()`
2. Always call `WarnDie.warn()` for these runtime conditions

**Pros:**
- Simple fix
- No risk of silent data corruption

**Cons:**
- Doesn't respect `no warnings 'substr'`
- May produce unwanted warnings in some code

## Recommended Approach

**Phase 1: Immediate Fix (Option D)**
- Remove conditional warning checks that were added in commit fa2bc48e9
- This restores the working behavior from master

**Phase 2: Proper Fix (Option A)**
- Implement compile-time warning emission for substr and similar operations
- This matches Perl 5 semantics and fixes the DateTime test issues properly

## Files to Modify

### Phase 1
- `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeSubstrLvalue.java` - Remove isWarningEnabled check
- `src/main/java/org/perlonjava/runtime/operators/Operator.java` - Remove isWarningEnabled check for substr
- `src/main/java/org/perlonjava/frontend/parser/NumberParser.java` - Remove isWarningEnabled check (may need different approach)

### Phase 2
- `src/main/java/org/perlonjava/backend/jvm/EmitOperator.java` - Emit conditional warning based on compile-time state
- `src/main/java/org/perlonjava/backend/bytecode/CompileOperator.java` - Same for interpreter
- May need new runtime method signatures to accept "should warn" boolean

## Test Cases

The fix must pass:
- `src/test/resources/unit/lvalue_substr.t` - Test 2 "Assignment beyond string length warns"
- DateTime tests should not have spurious warnings (Phase 2)

## Progress Tracking

### Current Status: Analysis complete

### Completed
- [x] Identified root cause (2024-03-20)
- [x] Documented solution options

### Next Steps
1. Implement Phase 1 fix
2. Verify lvalue_substr.t passes
3. Run full test suite
4. Plan Phase 2 implementation if needed

## References

- Commit fa2bc48e9: Added the warning checks that broke the test
- Commit b573a61b8: Original substr warning implementation
- `perldoc warnings` - Perl 5 warnings documentation
