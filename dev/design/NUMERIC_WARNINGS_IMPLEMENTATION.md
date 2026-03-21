# Numeric Warnings Implementation Plan

## Problem Statement

Perl's `use warnings "numeric"` should emit warnings like `Argument "abc" isn't numeric` when non-numeric strings are used in numeric context. Currently:

1. `use warnings` and `no warnings` are compile-time pragmas
2. Numification happens at runtime via `RuntimeScalar.getDouble()` → `NumberParser.parseNumber()`
3. Runtime code doesn't know the compile-time warning state

### Current Behavior (broken)
```perl
use warnings;
my $x = 0 + "abc";  # Should warn, but doesn't (or warns incorrectly)
{
    no warnings "numeric";
    my $y = 0 + "def";  # Should NOT warn
}
my $z = 0 + "ghi";  # Should warn
```

## Core Design Decision: Warn on Cache Miss

Key insight: `NumberParser` has a numification cache. Most strings are only parsed once.
We only need to check the warning flag on **cache misses**.

### Flow
```
Cache hit path (fast, common):
  getDouble() → parseNumber() → cache hit → return
  [no warning check needed]

Cache miss path (rare):
  getDouble() → parseNumber() → parse string → 
    if (isNonNumeric && warningsEnabled()) warn → cache result → return
  [one flag check only on cache miss]
```

### Behavior Difference from Perl

- **Perl**: Warns every time a non-numeric string is used
- **Our approach**: Warns only on cache miss (first use of that string)

This is acceptable. We can later add a warning flag to the cache entry if exact Perl behavior is needed.

## Open Question: How to Track Warning State at Runtime

The compile-time symbol table knows if warnings are enabled, but `parseNumber()` runs at runtime and needs to check this. Several options exist with different trade-offs.

### Option A: Perl Global Variable with `local`

Use a Perl global variable `$warnings::_numeric_enabled` that:
- Is set to 1 by `use warnings "numeric"`
- Is set to 0 by `no warnings "numeric"` using `local` for automatic scope restore

```java
public static boolean isNumericWarningsEnabled() {
    return getGlobalVariable("warnings::_numeric_enabled").getBoolean();
}
```

**Pros:**
- Automatic block scoping via existing `local`/DynamicVariableManager
- Handles `goto` correctly (DynamicVariableManager already handles this)
- Simple to implement

**Cons:**
- Hash lookup on every cache miss (slower than ThreadLocal)

### Option B: ThreadLocal with try/finally

```java
private static final ThreadLocal<Boolean> numericWarningsEnabled = 
    ThreadLocal.withInitial(() -> false);

// Compiler generates for "no warnings" blocks:
boolean _saved = Warnings.isNumericWarningsEnabled();
Warnings.setNumericWarningsEnabled(false);
try {
    // block code
} finally {
    Warnings.setNumericWarningsEnabled(_saved);
}
```

**Pros:**
- Fast ThreadLocal read
- Proper block scoping

**Cons:**
- **Conflicts with `goto`** - JVM bytecode issues when goto jumps out of try/finally
- More complex compiler changes

### Option C: Per-Class Static Field + ThreadLocal on Entry

Each generated class (subroutine) has a compile-time constant:
```java
public class Sub_foo {
    static final boolean NUMERIC_WARNINGS = true;  // set at compile time
}
```

On subroutine entry, set a ThreadLocal:
```java
// Generated at subroutine entry
Warnings.setNumericWarnings(NUMERIC_WARNINGS);
```

**Pros:**
- Fast ThreadLocal read on cache miss
- No try/finally (no goto issues)
- Warning state is per-subroutine (matches Perl's lexical scoping)

**Cons:**
- One ThreadLocal write per subroutine call
- Nested calls overwrite - need to verify this matches Perl semantics
- Doesn't handle block-level `no warnings` within a subroutine

### Option D: Simple Global Flag (No Block Scoping)

Just use a simple flag without block-level scoping:
- `use warnings` → enable globally
- `no warnings` → disable globally

**Pros:**
- Simplest implementation
- No goto issues
- Correct for 99% of real code (most use file-level `use warnings`)

**Cons:**
- Block-level `no warnings "numeric"` won't restore on block exit
- Less correct than Perl

## NumberParser Changes (Common to All Options)

Regardless of which option is chosen for tracking state, the parseNumber() changes are the same:

```java
// In parseNumber(), after parsing determines the string is non-numeric:
// (isNonNumeric is already computed during parsing - no extra work)
if (isNonNumeric && Warnings.isNumericWarningsEnabled()) {
    WarnDie.warn(new RuntimeScalar("Argument \"" + str + "\" isn't numeric"),
            RuntimeScalarCache.scalarEmptyString);
}
```

Note: `isNonNumeric` is already determined during parsing (e.g., "abc" → 0).
The only new check is `isNumericWarningsEnabled()`.

## Files to Modify

1. `Warnings.java` - add `isNumericWarningsEnabled()` method (implementation depends on option chosen)
2. `NumberParser.java` - check flag on cache miss, emit warning
3. Possibly compiler changes depending on option chosen

## Testing

```perl
use warnings;
my $warned = 0;
local $SIG{__WARN__} = sub { $warned++ };

my $x = 0 + "abc";
ok($warned == 1, "warns on non-numeric");

$warned = 0;
my $y = 0 + "123";
ok($warned == 0, "no warning for numeric string");

$warned = 0;
{
    no warnings "numeric";
    my $z = 0 + "def";
}
ok($warned == 0, "no warning in no-warnings block");

# After block, warnings should be restored (if block scoping implemented)
$warned = 0;
my $w = 0 + "ghi";
ok($warned == 1, "warning restored after block");
```

## Current Status

- [x] Design documented
- [ ] Decision on runtime state tracking option (A, B, C, or D)
- [ ] Implementation
- [ ] Testing with op/numify.t
