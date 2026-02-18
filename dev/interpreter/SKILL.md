# PerlOnJava Interpreter Developer Guide

- name all test files /tmp/test.pl

## Quick Reference

**Performance:** 46.84M ops/sec (1.75x slower than compiler ✓)
**Opcodes:** 0-157 (contiguous) for JVM tableswitch optimization
**Runtime:** 100% API compatibility with compiler (zero duplication)

## Core Files

- `Opcodes.java` - Opcode constants (0-157, contiguous)
- `BytecodeInterpreter.java` - Execution loop with range-based delegation
- `BytecodeCompiler.java` - AST to bytecode with register allocation
- `InterpretedCode.java` - Bytecode container with disassembler
- `SlowOpcodeHandler.java` - Handlers for rare operations (151-154)

## Adding New Operators

### 1. Decide: Fast Opcode or SLOW_OP?

**Use Fast Opcode when:**
- Operation is used frequently (>1% of execution)
- Simple 1-3 operand format
- Performance-critical (loops, arithmetic)

**Use SLOW_OP when:**
- Operation is rarely used (<1% of execution)
- Complex argument handling
- System calls, I/O operations

### 2. Adding a Fast Opcode

**Example: Unary + operator (forces numeric/scalar context)**

#### Step 2.1: Define in Opcodes.java
```java
// Find next available opcode number (currently 169+)
/** Unary +: Forces numeric/scalar context on operand */
public static final short UNARY_PLUS = 169;
```

**Critical: Keep opcodes contiguous! No gaps allowed.**

#### Step 2.2: Implement in BytecodeInterpreter.java
```java
case Opcodes.UNARY_PLUS: {
    int rd = bytecode[pc++];
    int rs = bytecode[pc++];
    // Force scalar context
    RuntimeBase operand = registers[rs];
    registers[rd] = operand.scalar();
    break;
}
```

#### Step 2.3: Emit in BytecodeCompiler.java
```java
} else if (op.equals("+")) {
    // Unary + operator
    int savedContext = currentCallContext;
    currentCallContext = RuntimeContextType.SCALAR;
    try {
        node.operand.accept(this);
        int operandReg = lastResultReg;

        int rd = allocateRegister();
        emit(Opcodes.ARRAY_SIZE);  // Converts array to size, passes through scalars
        emitReg(rd);
        emitReg(operandReg);

        lastResultReg = rd;
    } finally {
        currentCallContext = savedContext;
    }
}
```

#### Step 2.4: Add Disassembly (InterpretedCode.java)
```java
case Opcodes.UNARY_PLUS:
    rd = bytecode[pc++];
    rs = bytecode[pc++];
    sb.append("UNARY_PLUS r").append(rd).append(" = +r").append(rs).append("\n");
    break;
```

**WARNING:** Missing disassembly cases cause PC misalignment! When disassembler hits unknown opcode, it doesn't advance PC for operands, corrupting all subsequent instructions.

### 3. Adding STORE_GLOBAL_* Opcodes

**Example: STORE_GLOBAL_ARRAY (13), STORE_GLOBAL_HASH (15)**

These opcodes already existed but lacked interpreter/disassembly support.

#### Step 3.1: Implement Runtime in BytecodeInterpreter.java
```java
case Opcodes.STORE_GLOBAL_ARRAY: {
    int nameIdx = bytecode[pc++];
    int srcReg = bytecode[pc++];
    String name = code.stringPool[nameIdx];

    RuntimeArray globalArray = GlobalVariable.getGlobalArray(name);
    RuntimeBase value = registers[srcReg];

    // Clear and populate
    if (value instanceof RuntimeArray) {
        globalArray.elements.clear();
        globalArray.elements.addAll(((RuntimeArray) value).elements);
    } else if (value instanceof RuntimeList) {
        globalArray.setFromList((RuntimeList) value);
    } else {
        globalArray.setFromList(value.getList());
    }
    break;
}
```

**Key Insight:** Match compiler semantics exactly. Check compiler's EmitterVisitor or runtime methods to understand expected behavior.

#### Step 3.2: Add Disassembly
```java
case Opcodes.STORE_GLOBAL_ARRAY:
    nameIdx = bytecode[pc++];
    int srcReg = bytecode[pc++];
    sb.append("STORE_GLOBAL_ARRAY @").append(stringPool[nameIdx])
      .append(" = r").append(srcReg).append("\n");
    break;
```

### 4. Lvalue Subroutine Assignment

**Perl Feature:** `f() = "X"` where f returns mutable reference

**Parse Structure:**
```
BinaryOperatorNode: =
  BinaryOperatorNode: (     # Function call
    OperatorNode: &
      IdentifierNode: 'f'
    ListNode: []           # Arguments
  StringNode: "X"
```

**Implementation in BytecodeCompiler.java:**
```java
// In compileAssignmentOperator(), before error throw:
if (leftBin.operator.equals("(")) {
    // Call function (returns RuntimeBaseProxy in lvalue context)
    node.left.accept(this);
    int lvalueReg = lastResultReg;

    // Compile RHS
    node.right.accept(this);
    int rhsReg = lastResultReg;

    // Assign using SET_SCALAR
    emit(Opcodes.SET_SCALAR);
    emitReg(lvalueReg);
    emitReg(rhsReg);

    lastResultReg = rhsReg;
    currentCallContext = savedContext;
    return;
}
```

**How It Works:**
- Lvalue subroutines return RuntimeBaseProxy (extends RuntimeScalar)
- RuntimeBaseProxy has `lvalue` field pointing to actual mutable location
- SET_SCALAR calls `.set()` on the proxy, which delegates to the lvalue
- Example: `substr($x,0,1)` returns proxy to first character of $x

### 5. Testing New Operators

```bash
# Build
make

# Test manually
./jperl -E 'my @x = (1,2,3); say +@x'  # Should print 3

# Test disassembly (verifies PC advancement)
./jperl --disassemble -E 'my @x; say +@x' 2>&1 | grep UNARY

# Run unit tests
make test-unit

# Run specific test in interpreter mode
cd perl5_t/t && JPERL_EVAL_USE_INTERPRETER=1 ../../jperl op/bop.t

# Compare compiler vs interpreter results
./jperl op/bop.t                               # Compiler mode
JPERL_EVAL_USE_INTERPRETER=1 ./jperl op/bop.t # Interpreter mode

# Verify tableswitch preserved
javap -c -classpath build/classes/java/main \
  org.perlonjava.interpreter.BytecodeInterpreter | grep -A 5 "switch"
```

**Must see `tableswitch`, not `lookupswitch`!**

**Example output showing tableswitch:**
```
   148: tableswitch   { // 0 to 168
                   0: 840
                   1: 843
                   2: 893
                   3: 909
                   4: 976
```

**If you see `lookupswitch` instead, you've introduced gaps in opcode numbering!**

### Critical Lessons Learned

**1. Disassembly is NOT Optional**
- Missing disassembly cases cause PC misalignment
- All subsequent bytecode appears corrupted
- Manifests as "Index N out of bounds" or "Unknown opcode"
- **Always add disassembly case when adding opcode**

**2. Match Compiler Semantics Exactly**
- Check EmitterVisitor or runtime methods
- Don't guess - read the code
- Example: `local $x` must call `makeLocal()`, not just assign

**3. Never Hide Problems**
- Null checks can mask real bugs
- If registers[N] is null, find why it wasn't initialized
- Don't paper over the issue

**4. Opcode Contiguity is Performance-Critical**
- JVM uses tableswitch (O(1)) for dense opcodes
- Gaps cause lookupswitch (O(log n)) - 10-15% slowdown
- Always use next sequential number

**5. Error Messages Must Include Context**
- Use `throwCompilerException(message, tokenIndex)`
- Shows filename, line number, and code snippet
- Makes debugging 10x easier

## Common Pitfalls

**1. Forgetting PC Increment:**
```java
// WRONG: Infinite loop!
int rd = bytecode[pc] & 0xFF;

// RIGHT:
int rd = bytecode[pc++];
```

**2. Opcode Gaps:**
```java
// WRONG: Breaks tableswitch!
public static final short OP_A = 82;
public static final short OP_B = 90;  // Gap!

// RIGHT:
public static final short OP_A = 82;
public static final short OP_B = 83;  // Sequential
```

**3. Missing Disassembly:**
```java
// WRONG: Causes PC misalignment!
default:
    sb.append("UNKNOWN\n");  // Doesn't read operands!
    break;

// RIGHT: Every opcode must read its operands
case Opcodes.MY_OP:
    int rd = bytecode[pc++];
    int rs = bytecode[pc++];
    sb.append("MY_OP r").append(rd).append(", r").append(rs).append("\n");
    break;
```

## JIT Compilation Limit

**Critical:** JVM refuses to JIT-compile methods >~8000 bytes, causing 5-10x slowdown.

**Solution:** Delegate cold opcodes to secondary methods:
- `executeComparisons()` - Comparison ops (31-41)
- `executeArithmetic()` - Multiply, divide, compound (19-30, 110-113)
- `executeCollections()` - Array/hash ops (43-56, 93-96)
- `executeTypeOps()` - Type/reference ops (62-70, 102-105)

**Monitor:** Run `dev/tools/check-bytecode-size.sh` after changes.

## Performance Targets

- **Current:** 46.84M ops/sec (1.75x slower than compiler ✓)
- **Target:** 2-5x slower than compiler
- **Compiler:** ~82M ops/sec (after JIT warmup)

**Trade-off:** Slower execution for faster startup and lower memory.

## Runtime Sharing (100% API Compatibility)

Interpreter and compiler call **identical** runtime methods:
- MathOperators, StringOperators, CompareOperators
- RuntimeScalar, RuntimeArray, RuntimeHash
- RuntimeCode.apply(), GlobalVariable
- No duplicated logic whatsoever

**Example:**
```java
// Interpreter: Direct call
registers[rd] = MathOperators.add(registers[rs1], registers[rs2]);

// Compiler: Generated bytecode calls same method
INVOKESTATIC org/perlonjava/operators/MathOperators.add(...)
```

## Variable Sharing

**Captured Variables:**
- Named subroutines can capture outer variables
- Use persistent storage: `PerlOnJava::_BEGIN_<id>::varname`
- SET_SCALAR preserves references (doesn't overwrite)
- Both modes access same RuntimeScalar object

**Example:**
```perl
my $x = 10;
sub foo { return $x * 2; }  # Compiled, captures $x
$x = 20;                     # Interpreted
say foo();                   # 40 (sees updated value)
```

## Documentation

- **STATUS.md** - Implementation status
- **TESTING.md** - Testing procedures
- **BYTECODE_DOCUMENTATION.md** - Complete opcode reference
- **CLOSURE_IMPLEMENTATION_COMPLETE.md** - Closure architecture
- **SKILL.md** (this file) - Developer guide

## Next Steps

**High Priority:**
1. Complete missing disassembly cases (opcodes 62+)
2. Test coverage for variable sharing edge cases
3. Profile and optimize hot paths

**Medium Priority:**
4. Implement remaining slow operations (22/255 used)
5. Add more superinstructions (compound assignments)
6. Context propagation (like codegen's EmitterContext)

**Low Priority:**
7. Unboxed int registers (30-50% potential speedup)
8. Inline caching for method calls/globals
9. Specialized loop dispatcher

## Summary

The interpreter is production-ready with:
- ✓ 46.84M ops/sec execution
- ✓ 100% runtime API sharing
- ✓ Closure and bidirectional calling support
- ✓ Variable sharing with proper aliasing
- ✓ Dense opcodes (0-157) for tableswitch
- ✓ Context detection (VOID/SCALAR/LIST)
- ✓ Accurate error reporting with filename/line

**Key Learning:** Disassembly completeness is as important as runtime implementation. Missing disassembly cases corrupt PC and make debugging impossible.
