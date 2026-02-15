# Compound Assignment Operator Overload Support - Status

## Summary

Compound assignment operators (`+=`, `-=`, `*=`, `/=`, `%=`) are partially implemented but **lack proper overload support**. The current implementation only uses the base operator (`+`, `-`, etc.) and doesn't check for compound assignment overloads.

## Current Behavior

### Compiler (JVM bytecode generation)
- Located in: `EmitBinaryOperator.handleCompoundAssignment()` (line 203)
- Current implementation:
  1. Strips the `=` from the operator (e.g., `+=` → `+`)
  2. Creates a BinaryOperatorNode for the base operation
  3. Calls `emitOperator()` which invokes the base operator (e.g., `MathOperators.add()`)
  4. Assigns result back to lvalue

### Interpreter
- Located in: `BytecodeCompiler.java`
  - `+=` at line 2680: Uses `ADD_ASSIGN` opcode
  - `-=`, `*=`, `/=`, `%=` at line 2709+: Just added, emit direct opcodes (SUB_SCALAR, MUL_SCALAR, etc.)
- Interpreter opcodes call `MathOperators.add()`, etc. which have overload support for BASE operators only

## Problem

**Real Perl behavior:**
```perl
package MyNum {
    use overload
        '+=' => sub { print "Called +=\n"; ... },  # Direct compound overload
        '+' => sub { print "Called +\n"; ... };     # Base operator

}
my $x = MyNum->new(10);
$x += 5;  # Should call += overload if defined, else fall back to +
```

**PerlOnJava behavior:**
- Always calls `+` overload, never checks for `+=` overload
- Test output: "Called +" instead of "Called +="

## What Needs to Be Done

### 1. Compiler Fix (Priority: HIGH)

**File:** `src/main/java/org/perlonjava/codegen/EmitBinaryOperator.java`
**Method:** `handleCompoundAssignment()`

**Changes needed:**
1. Before line 235 (`String baseOperator = node.operator.substring...`), add overload check:
   ```java
   // Check if compound assignment operator is overloaded
   // e.g., for +=, check for (+= overload
   String compoundOp = "(" + node.operator;  // e.g., "(+="

   // Try to call compound assignment overload if it exists
   // If found, call it and return
   // If not found, fall back to current implementation (base operator)
   ```

2. Need to emit code that:
   - Gets left operand (the variable)
   - Gets right operand (the value)
   - Calls `OverloadContext.tryTwoArgumentOverload()` with compound operator name
   - If result is null, falls back to base operator

### 2. Interpreter Fix (Priority: HIGH)

**Files:**
- `src/main/java/org/perlonjava/interpreter/BytecodeCompiler.java` (compound assignment cases)
- `src/main/java/org/perlonjava/operators/MathOperators.java` (add new methods)

**Option A: Create new methods in MathOperators**
```java
public static RuntimeScalar addAssign(RuntimeScalar arg1, RuntimeScalar arg2) {
    // Check for (+= overload first
    int blessId = blessedId(arg1);
    int blessId2 = blessedId(arg2);
    if (blessId < 0 || blessId2 < 0) {
        RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(
            arg1, arg2, blessId, blessId2, "(+=", "+="
        );
        if (result != null) {
            // Compound overload found, use it
            // IMPORTANT: Must assign result back to arg1
            arg1.set(result);
            return arg1;
        }
    }
    // Fall back to base operator
    return add(arg1, arg2);  // This already handles (+ overload
}
```

Then update interpreter to call these methods instead of emit opcodes directly.

**Option B: Add new opcodes**
- ADD_ASSIGN_OVERLOAD, SUB_ASSIGN_OVERLOAD, etc.
- These opcodes would check for overloads at runtime

### 3. Update Feature Matrix

**File:** `docs/reference/feature-matrix.md`
**Line:** 601

Change from:
```markdown
- ❌ Missing: `+=`, `-=`, `*=`, `/=`, `%=`, ...
```

To:
```markdown
- ✅ Implemented: `+=`, `-=`, `*=`, `/=`, `%=` (with overload support)
- ❌ Missing: `**=`, `<<=`, `>>=`, `x=`, `.=`, `&=`, `|=`, `^=`, `&.=`, `|.=`, `^.=`
```

## Testing

**Test file:** `src/test/resources/unit/overload_compound_assignment.t`
- Created ✅
- All tests currently pass, but this is misleading because:
  - The fallback to base operators works (e.g., `+` when `+=` not defined)
  - Tests don't verify WHICH overload method is called

**Need to add debug output to verify correct behavior:**
- Add print statements in overload methods to see which is called
- Or check overload invocation counts

## Architecture Notes

### OperatorHandler.java
- Maps operators to their runtime implementations
- Example: `"+"` → `MathOperators.add()`
- Compiler looks up handlers to generate method calls
- Does NOT currently have entries for compound assignment operators

### Overload System
- `Overload.java`: Handles stringify, numify, boolify
- `OverloadContext.java`: Manages overload context, provides `tryOverload()` and `tryTwoArgumentOverload()`
- Operators check for overloads at the START of their implementation
- Format: `(operator` (e.g., `(+`, `(+=`, `(-=`)

### Two-Argument Overload Pattern
```java
int blessId = RuntimeScalarType.blessedId(arg1);
int blessId2 = RuntimeScalarType.blessedId(arg2);
if (blessId < 0 || blessId2 < 0) {
    RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(
        arg1, arg2, blessId, blessId2, "(+", "+"
    );
    if (result != null) return result;
}
// Default implementation...
```

## Related Files

- `src/main/java/org/perlonjava/codegen/EmitBinaryOperator.java` - Compiler compound assignment
- `src/main/java/org/perlonjava/codegen/EmitBinaryOperatorNode.java` - Operator dispatch
- `src/main/java/org/perlonjava/interpreter/BytecodeCompiler.java` - Interpreter compound assignment
- `src/main/java/org/perlonjava/operators/MathOperators.java` - Arithmetic operators with overload support
- `src/main/java/org/perlonjava/operators/OperatorHandler.java` - Operator→method mapping
- `src/main/java/org/perlonjava/runtime/OverloadContext.java` - Overload resolution
- `src/test/resources/unit/overload_compound_assignment.t` - Test file

## Next Steps

1. Implement compiler support for compound assignment overloads
2. Implement interpreter support (probably via new MathOperators methods)
3. Verify tests actually check correct overload method is called
4. Update feature matrix
5. Consider implementing other compound assignments (.**=**, **<<=**, etc.)

## Timeline Estimate

- Compiler implementation: ~2 hours
- Interpreter implementation: ~1 hour
- Testing and verification: ~1 hour
- Documentation: ~30 minutes
- **Total: ~4.5 hours**
