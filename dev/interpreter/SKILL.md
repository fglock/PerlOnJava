# PerlOnJava Interpreter Developer Guide

- name all test files /tmp/test.pl

## Quick Reference

**Performance:** 46.84M ops/sec (1.75x slower than compiler ✓)
**Opcodes:** 0-157 (contiguous) for JVM tableswitch optimization
**Runtime:** 100% API compatibility with compiler (zero duplication)

### Testing Modes

**JPERL_EVAL_USE_INTERPRETER=1** - Forces all eval STRING to use the interpreter
- Used for testing interpreter implementation of operators in eval context
- Compiler still used for main code, only eval STRING uses interpreter
- Example: `JPERL_EVAL_USE_INTERPRETER=1 ./jperl test.pl`

**JPERL_EVAL_VERBOSE=1** - Enable verbose eval error reporting
- By default, eval failures are silent (errors only stored in $@)
- With verbose mode, eval compilation errors print to stderr
- Useful for debugging interpreter eval issues
- Example: `JPERL_EVAL_USE_INTERPRETER=1 JPERL_EVAL_VERBOSE=1 ./jperl test.pl`

**--interpreter** - Forces the interpreter EVERYWHERE
- All code (main and eval) runs in interpreter mode
- Used for full interpreter testing and development
- Example: `./jperl --interpreter test.pl`

## Core Files

- `Opcodes.java` - Opcode constants (0-157, contiguous)
- `BytecodeInterpreter.java` - Execution loop with range-based delegation
- `BytecodeCompiler.java` - AST to bytecode with register allocation
- `InterpretedCode.java` - Bytecode container with disassembler
- `SlowOpcodeHandler.java` - Handlers for rare operations (151-154)

## Code Generation Tool

**Location:** `dev/tools/generate_opcode_handlers.pl`

Automates creation of opcode handlers for built-in functions with simple signatures.

### Quick Start

```bash
# Generate handlers for all eligible operators in OperatorHandler.java
perl dev/tools/generate_opcode_handlers.pl

# Rebuilds:
# - ScalarUnaryOpcodeHandler.java (31 ops: chr, ord, abs, sin, cos, etc.)
# - ScalarBinaryOpcodeHandler.java (12 ops: atan2, eq, ne, lt, le, gt, ge, cmp, etc.)
# - Opcodes.java (adds new opcode constants)
# - BytecodeInterpreter.java (adds dispatch cases)
# - InterpretedCode.java (adds disassembly cases)
```

### What Gets Generated

**Automatically:**
1. Handler classes with zero-overhead dispatch pattern
2. Opcode constants in Opcodes.java
3. Dispatch cases in BytecodeInterpreter.java
4. Disassembly cases in InterpretedCode.java

**Still Manual:**
- Emit cases in BytecodeCompiler.java (between `// GENERATED_OPERATORS_START/END`)

### Eligibility Criteria

**Included:**
- Scalar unary: `(RuntimeScalar) → RuntimeScalar`
- Scalar binary: `(RuntimeScalar, RuntimeScalar) → RuntimeScalar`
- Scalar ternary: `(RuntimeScalar, RuntimeScalar, RuntimeScalar) → RuntimeScalar`

**Excluded:**
- Varargs signatures: `(int, RuntimeBase...)` - getc
- Array/List/Hash parameters
- Primitive parameters (except in skipped varargs)
- Already existing opcodes (rand=91, length=30, rindex=173, index=172, require=170, isa=105, bless=104, ref=103, join=88, prototype=158)

### Adding BytecodeCompiler Cases

Tool prints list of operators needing emit cases. Add between markers:

```java
// GENERATED_OPERATORS_START
} else if (op.equals("chr")) {
    // chr($x) - convert codepoint to character
    if (node.operand instanceof ListNode) {
        ListNode list = (ListNode) node.operand;
        if (!list.elements.isEmpty()) {
            list.elements.get(0).accept(this);
        } else {
            throwCompilerException("chr requires an argument");
        }
    } else {
        node.operand.accept(this);
    }
    int argReg = lastResultReg;
    int rd = allocateRegister();
    emit(Opcodes.CHR);
    emitReg(rd);
    emitReg(argReg);
    lastResultReg = rd;
// GENERATED_OPERATORS_END
```

### Critical: LASTOP Management

Tool reads `LASTOP` from Opcodes.java to determine starting opcode:

```java
// In Opcodes.java
public static final short REDO = 220;

// Last manually-assigned opcode (for tool reference)
private static final short LASTOP = 220;  // ← UPDATE WHEN ADDING MANUAL OPCODES
```

**When adding manual opcodes:**
1. Add constant BEFORE generated section
2. Update `LASTOP = <your new opcode number>`
3. Run tool - it starts at LASTOP + 1

### Gotchas

**1. Don't Edit Generated Sections**
- Between `// GENERATED_*_START` and `// GENERATED_*_END`
- Tool overwrites on regeneration
- Your changes will be lost!

**2. LASTOP Drift**
```java
// WRONG: Forgot to update LASTOP
public static final short MY_NEW_OP = 221;
private static final short LASTOP = 220;  // ← Still 220!

// Tool starts at 221, collides with MY_NEW_OP!

// RIGHT: Always update LASTOP
public static final short MY_NEW_OP = 221;
private static final short LASTOP = 221;  // ← Updated!
```

**3. Import Path Conversion**
- Tool auto-converts: `org/perlonjava/operators/...` → `org.perlonjava.operators....`
- Works correctly for all Java imports

**4. BytecodeCompiler Not Automated**
- Tool can't automatically add emit cases (too many variations)
- Must add manually between markers
- Tool prints list of operators needing implementation

**5. Signature Mismatches**
- Tool skips complex signatures silently
- Check tool output for "Skipping X" messages
- These need manual implementation

### Testing Generated Opcodes

```bash
# Build
make

# Test in interpreter mode (forces eval STRING to use interpreter)
JPERL_EVAL_USE_INTERPRETER=1 ./jperl /tmp/test.pl

# Test script example:
cat > /tmp/test.pl << 'EOF'
print "chr(65): ", eval("chr(65)"), "\n";
print "ord('A'): ", eval("ord('A')"), "\n";
print "abs(-42): ", eval("abs(-42)"), "\n";
EOF

# Expected output (after adding BytecodeCompiler cases):
# chr(65): A
# ord('A'): 65
# abs(-42): 42
```

### Regenerating After Changes

```bash
# After adding new operators to OperatorHandler.java
perl dev/tools/generate_opcode_handlers.pl

# After updating LASTOP
perl dev/tools/generate_opcode_handlers.pl

# Tool output shows:
# - Existing opcodes skipped
# - New opcodes generated
# - Next available opcode number
# - List of operators needing BytecodeCompiler cases
```

### Manual Implementation Still Needed For

- **Varargs functions**: getc, printf, sprintf
- **List operators**: map, grep, sort, push, pop
- **Hash operators**: keys, values, each
- **Array operators**: splice (complex signature)
- **Special forms**: defined, wantarray (already manual)

## Adding New Operators

### 1. Decide: Fast Opcode or slow opcode?

**Use Fast Opcode when:**
- Operation is used frequently (>1% of execution)
- Simple 1-3 operand format
- Performance-critical (loops, arithmetic)

**Use slow opcode when:**
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
