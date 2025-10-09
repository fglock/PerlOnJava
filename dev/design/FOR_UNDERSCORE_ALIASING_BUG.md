# Bug: `for $_` with Transliteration Doesn't Modify Array Elements

## Problem

```perl
@a = (1,2); map { y/1/./ for $_ } @a;
# Expected: @a = (".", 2)
# Got: @a = (1, 2)  ❌
```

The transliteration operator `y///` inside `for $_` doesn't modify the original array elements.

## Root Cause Analysis

### Bytecode Investigation

Using `--disassemble` on `y/1/./ for $_` reveals:

```
L1: makeLocal("main::_")  // Localizes $_
    POP
L2: getGlobalVariable("main::_")  // Get localized $_ for iteration
    iterator()
L3: aliasGlobalVariable("main::_", iteratorValue)  // ❌ PROBLEM HERE
    ... y/// operator ...
    GOTO L3
```

### The Issue

1. **Step 1:** `local $_` creates a new `GlobalRuntimeScalar` and replaces the global `$_`
2. **Step 2:** Get the localized `$_` to create an iterator
3. **Step 3:** `aliasGlobalVariable("main::_", iteratorValue)` **REPLACES** the localized `$_` with the iterator value
4. **Result:** The localization is destroyed, and modifications don't propagate back

### Why It Fails

`GlobalVariable.aliasGlobalVariable()` does:
```java
public static void aliasGlobalVariable(String key, RuntimeScalar var) {
    globalVariables.put(key, var);  // Replaces the entry!
}
```

This **replaces** the global variable entry, destroying any localization in effect.

## Test Cases

### Works ✅
```perl
$x = 1; y/1/./ for $x;  # Works - $x becomes "."
@a = (1,2); map { y/1/./; } @a;  # Works - implicit $_
@a = (1,2); map { $_ =~ y/1/./; } @a;  # Works - explicit binding
```

### Fails ❌
```perl
$_ = 1; y/1/./ for $_;  # Fails - $_ stays "1"
@a = (1,2); map { y/1/./ for $_ } @a;  # Fails - array unchanged
```

## Why This Is Complex

The issue involves the interaction of three mechanisms:
1. **Localization** (`local $_`) - creates a new RuntimeScalar
2. **Aliasing** (`for` loops) - should create references, not copies
3. **Global variables** - stored in a shared map

When `for $_` tries to alias a localized variable, it destroys the localization by replacing the map entry.

## Attempted Fixes

### Attempt 1: Check for Localization
```java
public static void aliasGlobalVariable(String key, RuntimeScalar var) {
    RuntimeScalar existing = globalVariables.get(key);
    if (existing instanceof GlobalRuntimeScalar) {
        existing.set(var);  // Copy value instead of replacing
    } else {
        globalVariables.put(key, var);
    }
}
```

**Problem:** `set()` copies the VALUE, not the REFERENCE. Modifications to `var` don't propagate back to `existing`.

## Correct Solution (Not Yet Implemented)

The `for` loop should detect when the loop variable is localized and use a different mechanism:

1. **Option A:** Make `GlobalRuntimeScalar` act as a proxy that forwards operations to the aliased value
2. **Option B:** Store the aliasing information separately from the global variables map
3. **Option C:** Use a different bytecode sequence for localized variables in `for` loops

All options require significant refactoring of the localization and aliasing mechanisms.

## Impact

- **op/tr.t:** Test 210 fails (and possibly others)
- **Workaround:** Use explicit binding: `$_ =~ y///` instead of `y/// for $_`
- **Severity:** Medium - affects specific pattern of `for $_` with localized `$_`

## Related Code

- `EmitForeach.java` line 128: Emits `aliasGlobalVariable` call
- `GlobalVariable.java` line 102: `aliasGlobalVariable` implementation  
- `GlobalRuntimeScalar.java` line 33: `dynamicSaveState()` for localization
- `DynamicVariableManager.java` line 38: `pushLocalVariable()` triggers localization

## Status

**OPEN** - Requires architectural changes to localization/aliasing interaction.
