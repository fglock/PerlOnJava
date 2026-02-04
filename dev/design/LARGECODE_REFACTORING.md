# Large Code Refactoring - Automatic Retry Implementation

## Status: ✅ IMPLEMENTED

## Overview

PerlOnJava automatically handles large code blocks that exceed JVM's 65KB method size limit through on-demand refactoring. When compilation fails with "Method too large" error, the compiler automatically retries with refactoring enabled.

## Problem Discovery

When attempting to enable proactive refactoring by default, all 151 unit tests failed with errors like:
```
Global symbol "%Config" requires explicit package name
```

### Root Cause

Large-code refactoring wraps chunks of block statements in closures to avoid JVM's 65KB method size limit. However, when `use` or `require` statements are wrapped in closures, their imports happen in the closure's lexical scope instead of the package scope, breaking code that expects those imports to be available.

Example:
```perl
# Original code
package Foo;
use Config qw/%Config/;   # Import %Config into Foo package
my $x = $Config{foo};      # Access imported variable

# After refactoring (BROKEN)
package Foo;
sub {
    use Config qw/%Config/;   # Import happens in closure scope!
}->();
my $x = $Config{foo};      # ERROR: %Config not in scope
```

## Solution Evolution

### Initial Approach: Proactive Detection
First considered detecting `use`/`require`/`BEGIN` statements and keeping them in unrefactored prefix sections. However, this approach had several issues:
- Estimation overhead for all code
- Complex logic to detect all compile-time statements
- Still produces false positives
- Changes semantics even when refactoring not needed

### Better Approach: Reactive/On-Demand Refactoring ✅

Instead of predicting when refactoring is needed, **react to actual compilation errors**:

**Old flow (Proactive):**
```
Parse → Estimate size → Refactor if large → Compile
Problems:
- Estimation overhead for all code
- False positives break imports
- Semantic changes even when not needed
```

**New flow (Reactive):**
```
Parse → Compile normally
  ↓ (if Method too large error)
Catch error → Enable refactoring → Retry compilation
Benefits:
- Zero overhead for normal code
- Only refactor when truly necessary
- No semantic changes unless required
```

## Implementation

### Code Changes

#### LargeBlockRefactorer.java
Added support for automatic retry that bypasses the `IS_REFACTORING_ENABLED` check:

```java
public static void forceRefactorForCodegen(BlockNode node, boolean isAutoRetry) {
    // Only check IS_REFACTORING_ENABLED if NOT auto-retry
    if (!isAutoRetry && !IS_REFACTORING_ENABLED) {
        return;
    }
    if (node == null) {
        return;
    }
    // Prevent infinite retry loops
    Object attemptsObj = node.getAnnotation("refactorAttempts");
    int attempts = attemptsObj instanceof Integer ? (Integer) attemptsObj : 0;
    if (attempts >= MAX_REFACTOR_ATTEMPTS) {
        return;
    }
    node.setAnnotation("refactorAttempts", attempts + 1);
    node.setAnnotation("blockAlreadyRefactored", false);
    trySmartChunking(node, null, 256);
    processPendingRefactors();
}
```

#### EmitterMethodCreator.java
Modified to catch `MethodTooLargeException` and automatically retry with refactoring:

```java
try {
    return getBytecodeInternal(ctx, ast, useTryCatch, false);
} catch (MethodTooLargeException tooLarge) {
    // Automatic retry with refactoring on "Method too large" error
    if (ast instanceof BlockNode blockAst) {
        try {
            // Notify user that automatic refactoring is happening
            System.err.println("Note: Method too large, retrying with automatic refactoring.");
            // Force refactoring with auto-retry flag
            LargeBlockRefactorer.forceRefactorForCodegen(blockAst, true);
            // Reset JavaClassInfo to avoid reusing partially-resolved Labels
            if (ctx != null && ctx.javaClassInfo != null) {
                String previousName = ctx.javaClassInfo.javaClassName;
                ctx.javaClassInfo = new JavaClassInfo();
                ctx.javaClassInfo.javaClassName = previousName;
                ctx.clearContextCache();
            }
            return getBytecodeInternal(ctx, ast, useTryCatch, false);
        } catch (MethodTooLargeException retryTooLarge) {
            throw retryTooLarge;
        } catch (Throwable retryError) {
            System.err.println("Warning: Automatic refactoring failed: " + retryError.getMessage());
        }
    }
    throw tooLarge;
}
```

## Testing Results

### Test Case: 30,000 Element Array

```bash
# Automatic retry (default behavior)
./jperl /tmp/test_auto_refactor.pl
# Output: "Note: Method too large, retrying with automatic refactoring."
#         May still fail if code is extremely large (exceeds limits even after refactoring)
```

### Unit Tests
- ✅ All 2012 unit tests pass
- ✅ Normal code has zero overhead (no refactoring unless needed)
- ✅ Config imports work correctly
- ✅ op/pack.t: 14656/14726 ok (same as before)

## Benefits

1. **Zero overhead for normal code** - No bytecode estimation unless actually needed
2. **Semantic correctness** - Imports and `use` statements work normally
3. **Automatic handling** - Users don't need to set environment variables
4. **Fail-safe** - Catches extreme cases that exceed limits even after refactoring

## User Messages

### On automatic retry:
```
Note: Method too large, retrying with automatic refactoring.
```

### On failure after retry:
```
Hint: If this is a 'Method too large' error after automatic refactoring,
      the code may be too complex to compile. Consider splitting into smaller methods.
```

## Usage Recommendations

- **Default behavior (on-demand)**: ✅ Automatic and transparent for all code
- No configuration needed - refactoring happens automatically when required

## Technical Details

### JVM Method Size Limits
- Maximum method bytecode size: 65,535 bytes (64KB)
- Threshold for refactoring check: 40,000 bytes
- Refactoring splits large blocks into closure-wrapped chunks

### Refactoring Strategy
1. **Smart chunking**: Groups statements into manageable chunks
2. **Closure wrapping**: Each chunk becomes `sub { ... }->()`
3. **Context preservation**: Return values and control flow maintained
4. **Safe boundaries**: Never splits statements mid-expression

### Retry Logic
- Maximum retry attempts per block: 1 (prevents infinite loops)
- Tracks attempts via block annotation `refactorAttempts`
- Resets `JavaClassInfo` between attempts to clear partial state
