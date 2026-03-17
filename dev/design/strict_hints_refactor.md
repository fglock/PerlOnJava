# Strict/$^H Refactoring Design

**Status**: Analysis complete, implementation deferred  
**Created**: 2026-03-17  
**Related**: moo_support.md (Phase 34)

## Current Implementation

PerlOnJava has two mechanisms for strict options:

1. **`strictOptionsStack`** in `ScopedSymbolTable` - internal stack that tracks strict flags per scope
2. **`$^H`** - now a magical variable that syncs with `strictOptionsStack`

The current approach (implemented 2026-03-17) makes `$^H` a `ScalarSpecialVariable` that:
- On read: returns `strictOptionsStack.peek()`
- On write: calls `strictOptionsStack.setStrictOptions(value)`

This fixes Mo's `t/strict.t` which uses `$^H |= 1538` to enable strict.

## Alternative Approach: Use $^H as Single Source of Truth

In real Perl, `$^H` IS the hints variable - there's no separate internal stack. The `strict.pm` pragma simply does `$^H |= $bits`.

### Proposed Changes

1. **Eliminate `strictOptionsStack`** - use `$^H` directly
2. **Handle lexical scoping of `$^H`** - save/restore at scope boundaries
3. **Use original `strict.pm`** from Perl distribution
4. **Change all `isStrictOptionEnabled()` calls** to read `$^H` directly

### Implementation Details

#### Lexical Scoping of $^H

In Perl, `$^H` is lexically scoped - its value is saved when entering a scope and restored when leaving:

```perl
{
    use strict;      # $^H |= 0x602
    # strict is in effect here
}
# strict is NOT in effect here (original $^H restored)
```

This is similar to `local $^H` but automatic. We'd need to:

1. In `enterScope()`: Save current `$^H` value
2. In `exitScope()`: Restore saved `$^H` value

```java
// In ScopedSymbolTable
private final Stack<Integer> hintsStack = new Stack<>();

public int enterScope() {
    // Save current $^H
    int currentHints = GlobalVariable.getGlobalVariable("main::^H").getInt();
    hintsStack.push(currentHints);
    // ... rest of enterScope
}

public void exitScope() {
    // Restore $^H
    int savedHints = hintsStack.pop();
    GlobalVariable.getGlobalVariable("main::^H").set(savedHints);
    // ... rest of exitScope
}
```

#### Checking Strict Options

Replace all calls like:
```java
if (ctx.symbolTable.isStrictOptionEnabled(Strict.HINT_STRICT_VARS)) {
```

With:
```java
if ((GlobalVariable.getGlobalVariable("main::^H").getInt() & Strict.HINT_STRICT_VARS) != 0) {
```

Or create a helper:
```java
public static boolean isHintEnabled(int hint) {
    return (GlobalVariable.getGlobalVariable(GlobalContext.encodeSpecialVar("H")).getInt() & hint) != 0;
}
```

#### Using Original strict.pm

Perl's `strict.pm` is relatively simple:

```perl
package strict;

my %bitmask = (
    refs => 0x00000002,
    subs => 0x00000200,
    vars => 0x00000400,
);

sub import {
    shift;
    my $bits = 0;
    if (@_) {
        $bits |= $bitmask{$_} for @_;
    } else {
        $bits = 0x602;  # all
    }
    $^H |= $bits;
}

sub unimport {
    shift;
    my $bits = 0;
    if (@_) {
        $bits |= $bitmask{$_} for @_;
    } else {
        $bits = 0x602;
    }
    $^H &= ~$bits;
}
```

We could use this directly instead of the Java `Strict.java` implementation.

## Challenges

### 1. Compile-Time vs Runtime Access

`$^H` needs to be accessible at compile time (during parsing/code generation) to affect how code is compiled. Currently this works because:
- BEGIN blocks run during compilation
- `$^H` modifications in BEGIN affect subsequent parsing

### 2. Scope Boundary Detection

Need to ensure `$^H` is saved/restored at exactly the right points:
- Block entry/exit (`{ }`)
- Subroutine definitions
- `eval STRING` boundaries
- File boundaries (`use`, `require`, `do`)

### 3. %^H (Hints Hash)

Perl also has `%^H` for storing arbitrary compile-time hints. This is used by:
- `feature.pm`
- `warnings.pm`
- User pragmas

We'd need to handle this similarly.

### 4. Performance

Reading `$^H` from a global variable on every strict check might be slower than reading from an in-memory stack. Need to benchmark.

## Files Affected

If we implement this refactoring:

1. **Remove/modify**: `ScopedSymbolTable.java` - remove strictOptionsStack
2. **Remove/modify**: `Strict.java` - possibly remove entirely
3. **Modify**: ~40+ files that call `isStrictOptionEnabled()`
4. **Add**: Perl `strict.pm` to lib/
5. **Modify**: `GlobalContext.java` - initialize `$^H` properly
6. **Modify**: Scope enter/exit code to save/restore `$^H`

## Decision

**Deferred** - The current magical `$^H` approach works for Mo and is simpler. The refactoring to use `$^H` as single source of truth is cleaner but requires:
- Careful handling of lexical scoping
- Many file changes
- Thorough testing

Consider implementing when:
- We need better pragma compatibility
- We want to use original Perl pragmas
- The two-source-of-truth approach causes bugs

## Related Documents

- `moo_support.md` - Moo/Mo test status
- `Strict.java` - Current strict implementation
- `ScopedSymbolTable.java` - Current scope management
