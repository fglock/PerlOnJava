# Fix Regex Capture Variable Preservation in Eval Blocks

## ✅ STATUS: COMPLETED

**Implementation Date**: Prior to 2025-10-07  
**Files Created/Modified**:
- `src/main/java/org/perlonjava/runtime/RegexState.java` - Created
- `src/main/java/org/perlonjava/regex/RuntimeRegex.java` - Added saveState/restoreState methods
- Eval block code generation - Modified to save/restore regex state

**Result**: Regex capture variables now properly preserved across eval blocks.

---

## Original Problem Statement
Regex capture variables ($1, $2, etc.) were being lost when entering eval blocks because eval blocks are compiled as separate subroutines with try-catch, and the regex state wasn't properly preserved. This affected test files like t/re/pat.t.

## Current Architecture Analysis

### How Eval Blocks Work
1. `eval { ... }` is transformed to `sub { ... }->(@_)` with `useTryCatch=true`
2. This creates a new execution context that doesn't preserve regex captures
3. The eval block runs in isolation from the parent scope's regex state

### How Local Variables Work
1. `local $var` uses `GlobalRuntimeScalar.makeLocal()` 
2. This pushes a marker to `DynamicVariableManager`
3. The marker's `dynamicSaveState()` and `dynamicRestoreState()` are called on scope entry/exit
4. For regular variables, this saves/restores the value

### Special Variables Issue
1. `ScalarSpecialVariable` (like $1) doesn't store values - it computes from `RuntimeRegex` state
2. Current `makeLocal()` doesn't handle this properly
3. The special variable's save/restore methods are never called

## Perl's Expected Behavior

```perl
"ABC" =~ /(.)/;       # $1 = "A"
eval {
    print $1;         # Should print "A"
    "XYZ" =~ /(.)/;   # $1 = "X"
    print $1;         # Should print "X"
};
print $1;             # Should print "A" (restored)
```

## Why Previous Attempts Failed

### Attempt 1: Direct ScalarSpecialVariable Push
- Pushed ScalarSpecialVariable to DynamicVariableManager
- FAILED: ScalarSpecialVariable doesn't replace itself in symbol table, so restore doesn't work

### Attempt 2: Automatic Visitor Injection
- Created visitor to inject `local $1` in eval blocks with regex
- FAILED: Broke for loops where $_ was used (interaction bug)

### Attempt 3: Manual local $1
- Even manual `local $1` didn't work
- FAILED: The save/restore mechanism wasn't properly connected

## Root Cause
The fundamental issue is that `ScalarSpecialVariable` is a computed property, not a stored value. The `local` mechanism was designed for stored values that can be saved and restored. We need a different approach for computed properties that depend on global state.

## Proposed Solution

### Option 1: Automatic State Preservation (Simplest)
**Always save/restore regex state when entering/exiting eval blocks**

Implementation:
1. Modify eval block generation to always wrap with regex save/restore
2. No AST visitor needed
3. No interaction with `local` mechanism

Pros:
- Simple, guaranteed to work
- No complex interactions
- Matches Perl behavior

Cons:
- Minor performance overhead for eval blocks without regex
- But eval blocks are already expensive

### Option 2: Explicit Local Support (More Complex)
**Make `local $1` work correctly**

Implementation:
1. Create a proxy variable that saves regex state
2. Replace special variable in symbol table temporarily
3. Restore both symbol table and regex state on exit

Pros:
- Supports manual `local $1`
- More flexible

Cons:
- Complex interaction with existing local mechanism
- Risk of breaking other special variables

## Recommended Approach: Option 1

### Implementation Steps

1. **Create RegexState.java**
   - Simple class to hold all regex state
   - Fields: matcher, matchString, lastMatchedString, etc.

2. **Add to RuntimeRegex.java**
   ```java
   public static RegexState saveState() { ... }
   public static void restoreState(RegexState state) { ... }
   ```

3. **Modify eval block code generation**
   - In the code that transforms `eval { }` to `sub { }->()` 
   - Wrap the subroutine body with save/restore calls
   - Location: Where useTryCatch is handled

4. **No visitor needed**
   - Always save/restore for eval blocks
   - Simple and reliable

### Test Cases

```perl
# Test 1: Basic preservation
"ABC" =~ /(.)/;
my $before = $1;
eval { "XYZ" =~ /(.)/ };
die unless $1 eq $before;

# Test 2: Nested eval
"ABC" =~ /(.)/;
eval {
    "DEF" =~ /(.)/;
    eval {
        "GHI" =~ /(.)/;
    };
    die unless $1 eq "D";
};
die unless $1 eq "A";

# Test 3: Eval without regex
"ABC" =~ /(.)/;
eval { my $x = 1 + 1 };
die unless $1 eq "A";
```

## Success Criteria
1. All test cases pass
2. No regression in existing tests (`./gradlew test`)
3. digest.t still passes
4. No performance regression in eval blocks

## Files to Modify
1. Create: `src/main/java/org/perlonjava/runtime/RegexState.java`
2. Modify: `src/main/java/org/perlonjava/regex/RuntimeRegex.java` (add save/restore)
3. Modify: Code generation for eval blocks (find where useTryCatch is used)

## Implementation Notes

### What Was Implemented
Option 1 (Automatic State Preservation) was chosen and successfully implemented:
- `RegexState.java` class created to hold all regex state
- `RuntimeRegex.saveState()` and `restoreState()` methods added
- Eval blocks automatically save/restore regex state on entry/exit
- No visitor needed - simple and reliable approach

### Verification
The implementation successfully passes test cases and preserves regex captures across eval blocks, including nested eval scenarios.

## Archive Note
This document is kept for historical reference. The issue has been resolved and the feature is working as expected in the current codebase.
