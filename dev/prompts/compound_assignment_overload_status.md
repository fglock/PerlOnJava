# Compound Assignment Operator Overload Support - COMPLETED

## Summary

Compound assignment operators (`+=`, `-=`, `*=`, `/=`, `%=`) now have **full overload support** in both compiler and interpreter modes.

## Implementation Status

### Compiler (JVM bytecode generation) - ✅ COMPLETE
- Located in: `EmitBinaryOperator.handleCompoundAssignment()` (line 203)
- **How it works:**
  1. Checks if operator handler exists for compound operator (e.g., `+=`)
  2. Calls corresponding `*Assign` method (e.g., `MathOperators.addAssign()`)
  3. These methods check for compound overload first (e.g., `(+=`), then fall back to base operator (e.g., `(+`)
  4. Falls back to old approach (strip `=` and call base operator) for operators without handlers

### Interpreter - ✅ COMPLETE (with limitations)
- **New opcodes added:**
  - `SUBTRACT_ASSIGN` (110)
  - `MULTIPLY_ASSIGN` (111)
  - `DIVIDE_ASSIGN` (112)
  - `MODULUS_ASSIGN` (113)
- **BytecodeCompiler** emits these opcodes for `-=`, `*=`, `/=`, `%=`
- **BytecodeInterpreter** handlers call `MathOperators.*Assign()` methods
- **InterpretedCode** disassembler entries added

**Known Limitation:**
- Interpreter only supports compound assignments on simple scalar variables (e.g., `$x -= 5`)
- Does NOT support compound assignments on lvalues like `$hash{key} -= 5` or `$array[0] -= 5`
- Compiler supports all lvalues
- This limitation can be addressed in future work if needed

## Current Behavior

**Real Perl behavior (now matched!):**
```perl
package MyNum {
    use overload
        '+=' => sub { print "Called +=\n"; ... },  # Direct compound overload
        '+' => sub { print "Called +\n"; ... };     # Base operator
}
my $x = MyNum->new(10);
$x += 5;  # Calls += overload if defined, else falls back to +
```

**PerlOnJava behavior:**
- ✅ Compiler: Calls `+=` overload when defined, falls back to `+` when not
- ✅ Interpreter: Calls `+=` overload when defined, falls back to `+` when not (for simple variables)

## Test Results

**Compiler test:**
```
=== Test 1: With += overload defined ===
TRACE: Called += overload    ← Correct!
After: 15
```

**Interpreter test:**
```
=== Test 1: With -= overload defined ===
INTERPRETER: Called -= overload    ← Correct!
Result: 75
```

All unit tests pass: `make` ✅

## Implementation Details

### MathOperators.java
Added five new methods:
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
            arg1.set(result);
            return arg1;
        }
    }
    // Fall back to base operator (already has (+ overload support)
    RuntimeScalar result = add(arg1, arg2);
    arg1.set(result);
    return arg1;
}
```

Similarly: `subtractAssign()`, `multiplyAssign()`, `divideAssign()`, `modulusAssign()`

### OperatorHandler.java
Registered compound assignment operators:
```java
put("+=", "addAssign", "org/perlonjava/operators/MathOperators");
put("-=", "subtractAssign", "org/perlonjava/operators/MathOperators");
put("*=", "multiplyAssign", "org/perlonjava/operators/MathOperators");
put("/=", "divideAssign", "org/perlonjava/operators/MathOperators");
put("%=", "modulusAssign", "org/perlonjava/operators/MathOperators");
```

### Compiler: EmitBinaryOperator.handleCompoundAssignment()
```java
OperatorHandler operatorHandler = OperatorHandler.get(node.operator);
if (operatorHandler != null) {
    // Use the new *Assign methods
    node.left.accept(scalarVisitor);
    node.right.accept(scalarVisitor);
    mv.visitMethodInsn(...);  // Call *Assign method
} else {
    // Fallback for operators without handlers
    // (old approach: strip = and call base operator)
}
```

### Interpreter: New Opcodes
```java
case Opcodes.SUBTRACT_ASSIGN: {
    int rd = bytecode[pc++];
    int rs = bytecode[pc++];
    RuntimeScalar s1 = ...;
    RuntimeScalar s2 = ...;
    registers[rd] = MathOperators.subtractAssign(s1, s2);
    break;
}
```

## Files Modified

### Commits:
1. **5f2b2f2f** - Add overload support for compound assignment operators (compiler)
2. **b84e570d** - Update feature matrix
3. **c002cb71** - Add overload support for compound assignment operators in interpreter

### Files:
- `src/main/java/org/perlonjava/operators/MathOperators.java` - Added *Assign methods
- `src/main/java/org/perlonjava/operators/OperatorHandler.java` - Registered operators
- `src/main/java/org/perlonjava/codegen/EmitBinaryOperator.java` - Updated compiler
- `src/main/java/org/perlonjava/interpreter/Opcodes.java` - Added new opcodes
- `src/main/java/org/perlonjava/interpreter/BytecodeCompiler.java` - Emit new opcodes
- `src/main/java/org/perlonjava/interpreter/BytecodeInterpreter.java` - Added handlers
- `src/main/java/org/perlonjava/interpreter/InterpretedCode.java` - Added disassembler
- `docs/reference/feature-matrix.md` - Updated documentation
- `src/test/resources/unit/overload_compound_assignment.t` - Test file

## Feature Matrix Update

Changed from:
```markdown
- ❌ Missing: `+=`, `-=`, `*=`, `/=`, `%=`, `**=`, ...
```

To:
```markdown
- ✅ Implemented: `+=`, `-=`, `*=`, `/=`, `%=` (with full overload support in compiler; interpreter support for simple variables)
- ❌ Missing: `**=`, `<<=`, `>>=`, `x=`, `.=`, `&=`, `|=`, `^=`, `&.=`, `|.=`, `^.=`
```

## Future Work

**Optional improvements:**
1. Extend interpreter to support compound assignments on all lvalues (hash elements, array elements, etc.)
2. Implement remaining compound assignment operators (`**=`, `<<=`, `>>=`, etc.)
3. Consider superinstruction optimization for compound assignments in interpreter

## Conclusion

✅ **Task complete!** Compound assignment operators now have proper overload support matching Perl's behavior. The correct overload method is called when defined, with fallback to base operators when not defined.
