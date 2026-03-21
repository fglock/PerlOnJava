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

## Proposed Solution: Thread-Local Flag Checked on Cache Miss

Key insight: `NumberParser` has a numification cache. Most strings are only parsed once.
We only need to check the warning flag on **cache misses**.

### Design
1. `use warnings "numeric"` sets a thread-local flag (using `local` mechanism for scoping)
2. `no warnings "numeric"` clears the flag (with `local` for proper block scoping)
3. `parseNumber()` on cache miss: if flag is set AND string is non-numeric, warn immediately
4. Cache hits: no checking at all

### Flow
```
Cache hit path (fast, common):
  getDouble() → parseNumber() → cache hit → return
  [no thread-local access, no warning check]

Cache miss path (rare):
  getDouble() → parseNumber() → parse string → 
    if (non-numeric && warningsEnabled) warn → cache result → return
  [one thread-local read only on cache miss]
```

### Advantages
- **Zero overhead for cache hits** (most common case)
- **No operator duplication** - no addWithWarn, subtractWithWarn, etc.
- **No caller changes** - warning happens inside parseNumber()
- **Proper scoping** - uses existing `local` mechanism for thread-local flag
- **Simple implementation** - only NumberParser and Warnings module change

## Implementation

### Phase 1: Thread-Local Warning Flag

#### 1.1 Add flag to Warnings.java
```java
// Thread-local flag for numeric warnings, works with `local` mechanism
private static final String VAR_NUMERIC_WARNINGS = "warnings::_numeric_enabled";

public static boolean isNumericWarningsEnabled() {
    return getGlobalVariable(VAR_NUMERIC_WARNINGS).getBoolean();
}
```

#### 1.2 Update useWarnings/noWarnings
```java
// In useWarnings():
getGlobalVariable(VAR_NUMERIC_WARNINGS).set(1);

// In noWarnings() - use local for proper scoping:
// The compiler should emit: local $warnings::_numeric_enabled = 0;
// For now, direct set (scoping handled by caller):
getGlobalVariable(VAR_NUMERIC_WARNINGS).set(0);
```

### Phase 2: NumberParser Changes

#### 2.1 Check flag on cache miss
```java
// In parseNumber(), after parsing determines the string is non-numeric:
// (isNonNumeric is already computed during parsing - no extra work)
if (isNonNumeric && Warnings.isNumericWarningsEnabled()) {
    WarnDie.warn(new RuntimeScalar("Argument \"" + str + "\" isn't numeric"),
            RuntimeScalarCache.scalarEmptyString);
}
```

Note: `isNonNumeric` is already determined during parsing (e.g., "abc" → 0 with isNonNumeric=true).
The only new check is `isNumericWarningsEnabled()` - one thread-local read on cache miss.

### Phase 3: Proper Scoping for `no warnings`

For `no warnings "numeric"` to work with proper block scoping, the compiler should emit:
```perl
# What `no warnings "numeric"` should compile to:
local $warnings::_numeric_enabled = 0;
```

This uses Perl's existing `local` mechanism (DynamicVariableManager) to automatically restore the previous value when the block exits.

#### 3.1 Update StatementParser or Warnings module
When `no warnings "numeric"` is parsed, emit code equivalent to:
```java
DynamicVariableManager.pushLocalVariable(getGlobalVariable(VAR_NUMERIC_WARNINGS));
getGlobalVariable(VAR_NUMERIC_WARNINGS).set(0);
```

The block exit will automatically call `popToLocalLevel()` which restores the value.

## Files to Modify

1. `Warnings.java` - add thread-local flag and `isNumericWarningsEnabled()` method
2. `NumberParser.java` - check flag on cache miss, emit warning
3. `StatementParser.java` (optional) - emit `local` for `no warnings`

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

# After block, warnings should be restored
$warned = 0;
my $w = 0 + "ghi";
ok($warned == 1, "warning restored after block");
```

## Performance Analysis

| Scenario | Overhead |
|----------|----------|
| Cache hit, no warnings | Zero |
| Cache hit, warnings enabled | Zero |
| Cache miss, no warnings | One thread-local read |
| Cache miss, warnings enabled, numeric string | One thread-local read |
| Cache miss, warnings enabled, non-numeric | One thread-local read + warning emission |

Since cache misses are rare after warmup, the thread-local read overhead is negligible.

## Current Status

- [ ] Phase 1: Add thread-local flag to Warnings.java
- [ ] Phase 2: Check flag in NumberParser on cache miss
- [ ] Phase 3: Proper `local` scoping for `no warnings`
- [ ] Testing with op/numify.t
