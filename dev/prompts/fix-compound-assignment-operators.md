# Fix Compound Assignment Operators Architecture

## Objective
Refactor compound assignment operators (`*=`, `/=`, `**=`, `x=`, `&=`, `<<=`, `>>=`) from AST macro expansion to dedicated Java methods to enable uninitialized warnings and operator overloading.

## Problem Statement
PerlOnJava is not generating "Use of uninitialized value" warnings for certain assignment operators when the left-hand side is undefined. This is causing 20 test failures in t/op/assignwarn.t. Additionally, the current AST macro expansion approach prevents operator overloading support.

## Current Status
- **Test file:** t/op/assignwarn.t
- **Failures:** 20 tests (all related to missing warnings)
- **Pass rate:** Currently 82% (would be 100% after fix)

## Root Cause Analysis

### Current Implementation (AST Macro)
```java
// In EmitBinaryOperator.handleCompoundAssignment
// $x *= 2 is expanded to:
// $x.set(MathOperators.multiply($x, 2))
```

This happens at compile time in the AST, making it impossible to:
1. Check if `$x` is uninitialized and generate warnings
2. Support operator overloading (no runtime method to override)
3. Handle special cases differently

### Disassembly Evidence
```
$x *= 2 generates:
ALOAD 3  // load $x
DUP
LDC 2
INVOKESTATIC MathOperators.multiply
INVOKEVIRTUAL RuntimeScalar.set
```

## Proposed Solution

### Phase 1: Create Dedicated Methods
Create methods in RuntimeScalar for each compound assignment operator:
```java
public RuntimeScalar multiplyAssign(RuntimeScalar right) {
    if (!this.isDefined()) {
        WarnDie.warn("Use of uninitialized value in multiplication (*)");
    }
    return this.set(MathOperators.multiply(this, right));
}

public RuntimeScalar divideAssign(RuntimeScalar right) {
    if (!this.isDefined()) {
        WarnDie.warn("Use of uninitialized value in division (/)");
    }
    return this.set(MathOperators.divide(this, right));
}

// etc. for other operators
```

### Phase 2: Update Code Generation
Modify EmitBinaryOperator.handleCompoundAssignment to generate:
```java
// Instead of expanding the AST, emit:
emitterVisitor.ctx.mv.visitMethodInsn(
    Opcodes.INVOKEVIRTUAL, 
    "org/perlonjava/runtime/RuntimeScalar", 
    "multiplyAssign",  // or divideAssign, etc.
    "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", 
    false
);
```

## Warning Rules (from Perl)

### Operators that SHOULD warn with undef:
- `*=` (multiply)
- `/=` (divide)
- `**=` (power)
- `x=` (string repeat)
- `&=` (bitwise AND)
- `<<=` (left shift)
- `>>=` (right shift)
- `%=` (modulo)

### Operators that should NOT warn with undef:
- `+=` (add - treats undef as 0)
- `-=` (subtract - treats undef as 0)
- `.=` (concat - treats undef as "")
- `|=` (bitwise OR)
- `^=` (bitwise XOR)
- `||=` (logical OR)
- `&&=` (logical AND)
- `//=` (defined-or)

## Benefits

1. **Correct Warnings:** Can check for uninitialized values and warn appropriately
2. **Operator Overloading:** Methods can be overridden in tied/blessed objects
3. **Better Performance:** Potentially fewer method calls (one vs three)
4. **Cleaner Architecture:** Operations encapsulated in proper methods
5. **Easier Debugging:** Stack traces show actual operation names

## Test Cases to Verify

### Minimal Reproduction
```perl
#!/usr/bin/perl -w
use strict;
use warnings;

my $x;
$x *= 2;  # Should warn: Use of uninitialized value
# Current: No warning
# Expected: Warning

my $y;
$y += 2;  # Should NOT warn (treats undef as 0)
# Current: No warning (correct)
# Expected: No warning
```

### Test Script Available
See `/Users/fglock/projects/PerlOnJava/test_assignwarn.pl` for comprehensive test coverage.

## Implementation Steps

### Step 1: Create RuntimeScalar Methods
Add these methods to `src/main/java/org/perlonjava/runtime/RuntimeScalar.java`:

```java
public RuntimeScalar multiplyAssign(RuntimeScalar right) {
    if (!this.isDefined()) {
        WarnDie.warn("Use of uninitialized value in multiplication (*)");
    }
    return this.set(MathOperators.multiply(this, right));
}

// Similar for other operators...
```

### Step 2: Update Code Generation
Modify `src/main/java/org/perlonjava/codegen/EmitBinaryOperator.java`:

Change `handleCompoundAssignment()` method to emit direct method calls instead of AST expansion.

### Step 3: Create Operator Map
Map operator strings to method names:
- `"*="` → `"multiplyAssign"`
- `"/="` → `"divideAssign"`
- `"**="` → `"powerAssign"`
- etc.

## Implementation Checklist

- [ ] Create multiplyAssign() method in RuntimeScalar
- [ ] Create divideAssign() method in RuntimeScalar  
- [ ] Create powerAssign() method in RuntimeScalar
- [ ] Create repeatAssign() method in RuntimeScalar
- [ ] Create bitwiseAndAssign() method in RuntimeScalar
- [ ] Create leftShiftAssign() method in RuntimeScalar
- [ ] Create rightShiftAssign() method in RuntimeScalar
- [ ] Create moduloAssign() method in RuntimeScalar
- [ ] Update EmitBinaryOperator.handleCompoundAssignment()
- [ ] Add operator-to-method mapping
- [ ] Add warning checks based on operator type
- [ ] Test with op/assignwarn.t
- [ ] Verify no regressions in other tests

## Testing Strategy

1. **Run test_assignwarn.pl** to verify warnings work
2. **Run t/op/assignwarn.t** to verify all 20 failures are fixed
3. **Check disassembly** with `--disassemble` to verify correct bytecode
4. **Test overloading** once implemented

## Expected Impact
- **Tests fixed:** 20 tests in op/assignwarn.t
- **Pass rate improvement:** 82% → 100% (+18%)
- **Architectural improvement:** Enables operator overloading
- **Side benefits:** Cleaner stack traces, better performance

## Complexity Assessment
- **Estimated effort:** 2-3 hours
- **Risk level:** Medium (changes code generation)
- **Files to modify:** 
  - RuntimeScalar.java (add methods)
  - EmitBinaryOperator.java (change code generation)
  - Possibly OperatorHandler.java

## Alternative Approaches

### Option 1: Warning in Existing Methods
Add warning checks to MathOperators.multiply(), etc.
- **Pro:** Simpler implementation
- **Con:** Doesn't enable overloading, may warn in wrong contexts

### Option 2: Wrapper Methods
Create wrapper methods that check and delegate.
- **Pro:** Minimal changes to existing code
- **Con:** Extra method call overhead

## Recommendation
Implement dedicated compound assignment methods. This is the cleanest solution that enables both warnings and overloading, aligning with Perl's operator semantics.
