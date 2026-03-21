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

## Proposed Solution: Thread-Local Flag with Cache Optimization

Key insight: `NumberParser` has a numification cache. Most strings are only parsed once.
Thread-local overhead only occurs on **cache misses**, which is rare after warmup.

### Design
1. `parseNumber()` sets a thread-local flag when a non-numeric string is parsed (cache miss only)
2. Compiler emits a warning check **after** numeric operations when `use warnings "numeric"` is active
3. The warning check reads and clears the thread-local flag

### Flow
```
Cache hit path (fast, common):
  getDouble() → parseNumber() → cache hit → return
  [no thread-local access]

Cache miss path (rare):
  getDouble() → parseNumber() → parse string → set flag if non-numeric → cache result → return
  [one thread-local write]

Compiled code (when warnings enabled):
  result = a + b;
  NumberParser.emitPendingNumericWarning();  // one thread-local read + clear
```

### Advantages
- **Zero overhead for cache hits** (most common case)
- **No API changes** to operators, RuntimeScalar, etc.
- **No bytecode changes** needed
- **Simple implementation** - only NumberParser and compiler emit code change
- **Correct scoping** - compiler decides at compile-time whether to emit the check

## Implementation

### Phase 1: NumberParser Changes

#### 1.1 Add thread-local flag
```java
// In NumberParser.java
private static final ThreadLocal<String> pendingNumericWarning = new ThreadLocal<>();

public static void clearPendingWarning() {
    pendingNumericWarning.remove();
}

public static void emitPendingNumericWarning() {
    String str = pendingNumericWarning.get();
    if (str != null) {
        pendingNumericWarning.remove();
        WarnDie.warn(new RuntimeScalar("Argument \"" + str + "\" isn't numeric"),
                RuntimeScalarCache.scalarEmptyString);
    }
}
```

#### 1.2 Set flag on cache miss when non-numeric
```java
// In parseNumber(), when shouldWarn is true:
if (shouldWarn) {
    pendingNumericWarning.set(warnStr);
}
```

### Phase 2: JVM Backend Changes

#### 2.1 EmitBinaryOperator.java
After emitting numeric operations, check if warnings enabled and emit the check:
```java
// After emitting: MathOperators.add(a, b)
if (symbolTable.isWarningCategoryEnabled("numeric")) {
    mv.visitMethodInsn(INVOKESTATIC, 
        "org/perlonjava/frontend/parser/NumberParser",
        "emitPendingNumericWarning", "()V", false);
}
```

#### 2.2 Operators that need the check
- Binary: `+`, `-`, `*`, `/`, `%`, `**`
- Compound assignment: `+=`, `-=`, `*=`, `/=`, `%=`, `**=`
- Comparison: `<`, `<=`, `>`, `>=`, `==`, `!=`, `<=>`
- Unary: unary `-`, `abs`, `int`
- Increment/decrement: `++`, `--`

### Phase 3: Bytecode Interpreter Changes

#### 3.1 BytecodeCompiler.java
Similar to JVM - after numeric opcodes, emit warning check if enabled:
```java
if (symbolTable.isWarningCategoryEnabled("numeric")) {
    emit(Opcodes.EMIT_NUMERIC_WARNING);
}
```

#### 3.2 Add new opcode
```java
// In Opcodes.java
public static final int EMIT_NUMERIC_WARNING = ...;

// In OpcodeHandler
case EMIT_NUMERIC_WARNING:
    NumberParser.emitPendingNumericWarning();
    break;
```

## Files to Modify

1. `NumberParser.java` - add thread-local flag and methods
2. `EmitBinaryOperator.java` - emit warning check after numeric ops
3. `EmitOperator.java` - emit warning check after unary numeric ops
4. `BytecodeCompiler.java` - emit warning opcode
5. `Opcodes.java` - add EMIT_NUMERIC_WARNING opcode
6. `OpcodeHandler.java` - handle the new opcode

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
```

## Current Status

- [ ] Phase 1: NumberParser thread-local flag
- [ ] Phase 2: JVM backend warning emission
- [ ] Phase 3: Bytecode interpreter warning emission
- [ ] Testing with op/numify.t
