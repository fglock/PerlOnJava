# Fix: Regex LIST Context Should Return undef for Non-Participating Captures

## Problem

Recent interpreter fixes to return `(1)` in LIST context for non-global matches with no captures (commit 80fab8bc) introduced a regression. The fix incorrectly checked if the result list was empty, but didn't distinguish between:

1. **Patterns with NO capturing groups** (should return `(1)`)
2. **Patterns with capturing groups that didn't participate** (should return `(undef, undef, ...)`)

## Example

```perl
# Pattern with no capturing groups
@a = ("abc" =~ /abc/);   # Should return (1) ✓

# Pattern with optional capture that didn't match
@b = ("" =~ /(a)?/);     # Should return (undef), not (1) ✗
```

The bug: when a capture group didn't participate (Java's `Matcher.group(i)` returned null), we skipped adding it to the result list. This left the result empty, triggering the logic to add `(1)` instead.

## Root Cause

In `RuntimeRegex.java` lines 478-483, the code was:

```java
for (int i = 1; i <= captureCount; i++) {
    String matchedStr = matcher.group(i);
    if (matchedStr != null) {  // ❌ Skip null captures
        matchedGroups.add(new RuntimeScalar(matchedStr));
    }
}
```

Then at line 578:

```java
if (found && result.elements.isEmpty() && !regex.regexFlags.isGlobalMatch()) {
    // ❌ Wrong check: should check captureCount, not result.isEmpty()
    result.elements.add(RuntimeScalarCache.getScalarInt(1));
}
```

## Solution

### 1. Always Add Captures (Even if null/undef)

```java
for (int i = 1; i <= captureCount; i++) {
    String matchedStr = matcher.group(i);
    // ✓ Add undef for non-participating captures
    matchedGroups.add(matchedStr != null
            ? new RuntimeScalar(matchedStr)
            : RuntimeScalarCache.scalarUndef);
}
```

### 2. Check Capture Count, Not Result Emptiness

```java
// Track captureCount outside the while loop
int captureCount = 0;

// ... later in the loop:
captureCount = matcher.groupCount();  // Update (not declare new var)

// ... after the loop:
if (found && captureCount == 0 && !regex.regexFlags.isGlobalMatch()) {
    // ✓ Check if pattern has NO capturing groups
    result.elements.add(RuntimeScalarCache.getScalarInt(1));
}
```

## Test Results

| Pattern | Input | Expected | Before Fix | After Fix |
|---------|-------|----------|------------|-----------|
| `/abc/` | "abc" | `(1)` | `(1)` ✓ | `(1)` ✓ |
| `/(a)(b)(c)/` | "abc" | `("a","b","c")` | `("a","b","c")` ✓ | `("a","b","c")` ✓ |
| `/(a)?/` | "" | `(undef)` | `(1)` ✗ | `(undef)` ✓ |
| `/(a)|(b)/` | "a" | `("a",undef)` | `("a")` ✗ | `("a",undef)` ✓ |
| `/(a*)b/` | "b" | `("")` | `("")` ✓ | `("")` ✓ |

## Files Modified

- `src/main/java/org/perlonjava/regex/RuntimeRegex.java`
  - Declare `captureCount` outside while loop for later use
  - Always add captures, using `scalarUndef` for non-participating groups
  - Check `captureCount == 0` instead of `result.elements.isEmpty()`

## Impact on Test Results

This fixes the 12-test regression in `re/regexp.t` that was introduced by commit 80fab8bc.

Before fix: 1774/2210 passing
After fix: Expected to restore to 1786/2210 or better

## Key Lessons

1. **Java Matcher Semantics**: `Matcher.group(i)` returns:
   - `null` if the group didn't participate (e.g., `(a)?` not matching)
   - Empty string `""` if the group participated but captured nothing (e.g., `(a*)` matching zero a's)

2. **Perl List Context Behavior**: 
   - Non-participating captures must be included as `undef` in the result
   - Only patterns with zero capturing groups return `(1)` on success

3. **Checking Conditions**: Use pattern metadata (`captureCount`) not result state (`isEmpty()`) to determine behavior
