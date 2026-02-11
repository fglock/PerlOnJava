# Fast Interpreter Architecture for PerlOnJava

## Context

PerlOnJava currently compiles all Perl code to JVM bytecode via ASM, which has significant overhead for two use cases:

1. **Small eval strings** - Compilation overhead (~50ms) dominates execution time for one-liners
2. **Very large code executed once** - Compilation time and memory for class metadata is wasted

The current compilation pipeline:
```
Lexer → Parser → AST → EmitterVisitor → ASM bytecode → ClassLoader → MethodHandle
```

Fixed costs include ClassWriter initialization, ASM frame computation (expensive), and class loading. This makes compilation inefficient for small/one-time code despite an LRU eval cache (100 entries).

**Solution**: Add a VERY FAST interpreter that shares the same internal API and runtime, allowing seamless mixing of compiled and interpreted code.

## Phase 0: Architecture Benchmarking - COMPLETE ✓

**DECISION: Switch-based dispatch selected**

Empirical benchmarking showed switch-based dispatch is **2.25-4.63x faster** than function-array dispatch:
- Pure dispatch: 0.13 ns/opcode vs 0.58 ns/opcode (4.63x faster)
- Realistic workload: 599M ops/sec vs 266M ops/sec (2.25x faster)
- Memory: 100.7 bytes/exec vs 151.0 bytes/exec (50% less allocation)

See `dev/design/interpreter_benchmarks.md` for detailed results.

## Correct Architecture (Based on Compiler Analysis)

### Key Insight: Pure Register Architecture (NOT Stack-Based)

**CRITICAL**: The compiler uses register architecture (not stack-based) because Perl's complex control flow (GOTO, last/next/redo, die/eval) would corrupt a stack. The interpreter **MUST** use the same architecture for correctness.

Analysis of `./jperl --disassemble` output reveals the compiler:

1. **Uses register architecture** - JVM local variables as registers (no expression stack)
2. **Uses RuntimeBase objects everywhere** - no primitive unboxing in operations
3. **Calls static operator methods** - `MathOperators.add(RuntimeScalar, RuntimeScalar)`
4. **Shares global variables** - via `GlobalVariable` maps
5. **Uses RuntimeScalarCache** - for constant values
6. **Has specialized unboxed operations** - rare optimizations like `add(RuntimeScalar, int)`

**The interpreter must do EXACTLY the same:**

```java
// Pure register architecture (like compiler's JVM locals)
RuntimeBase[] registers = new RuntimeBase[code.maxRegisters];

case Opcodes.ADD_SCALAR:
    // 3-address code: rd = rs1 + rs2
    int rd = bytecode[pc++] & 0xFF;   // destination register
    int rs1 = bytecode[pc++] & 0xFF;  // source register 1
    int rs2 = bytecode[pc++] & 0xFF;  // source register 2
    // Call the SAME method the compiler uses
    registers[rd] = MathOperators.add(
        (RuntimeScalar) registers[rs1],
        (RuntimeScalar) registers[rs2]
    );
    break;

case Opcodes.ADD_SCALAR_INT:
    // Specialized unboxed operation (rare optimization)
    // 2-address code: rd = rs + immediate_int
    int rd = bytecode[pc++] & 0xFF;
    int rs = bytecode[pc++] & 0xFF;
    int immediate = readInt(bytecode, pc);
    pc += 4;
    // Call specialized unboxed method
    registers[rd] = MathOperators.add(
        (RuntimeScalar) registers[rs],
        immediate  // primitive int, not RuntimeScalar
    );
    break;

case Opcodes.LOAD_GLOBAL_SCALAR:
    int destReg = bytecode[pc++] & 0xFF;
    int nameIdx = bytecode[pc++] & 0xFF;
    String name = code.stringPool[nameIdx];
    registers[destReg] = GlobalVariable.getGlobalScalar(name);  // Shared with compiled code
    break;

case Opcodes.ARRAY_GET:
    int resultReg = bytecode[pc++] & 0xFF;
    int arrayReg = bytecode[pc++] & 0xFF;
    int indexReg = bytecode[pc++] & 0xFF;
    RuntimeArray array = (RuntimeArray) registers[arrayReg];
    RuntimeScalar index = (RuntimeScalar) registers[indexReg];
    registers[resultReg] = array.get(index.getInt());  // Use existing RuntimeArray API
    break;

case Opcodes.GOTO:
    // Jump doesn't corrupt register state (unlike stack-based)!
    int offset = readInt(bytecode, pc);
    pc = offset;  // All registers stay valid
    break;
```

### Critical Requirements

1. **Pure Register Architecture** - NO expression stack (matches compiler for control flow correctness)
2. **No Primitive Unboxing** - Everything stays as RuntimeBase objects (except rare specialized ops)
3. **Reuse Operator Implementations** - Call `org.perlonjava.operators.*` methods directly
4. **Share Global State** - Same `GlobalVariable` maps for `$::x`, `@::arr`, `%::hash`
5. **RuntimeCode Compatibility** - Interpreted code looks exactly like compiled code to callers
6. **Specialized Unboxed Ops** - Support rare optimizations like `add(RuntimeScalar, int)` when beneficial

## Architecture: Pure Register Bytecode Interpreter

### Design: Custom Bytecode with Register Machine

- **Custom bytecode format** (NOT JVM bytecode) - optimized for Perl semantics
- **Switch-based dispatch** - JVM JIT optimizes to tableswitch (O(1) jump table)
- **Pure register machine** - NO expression stack (matches compiler for control flow correctness)
- **3-address code** - `rd = rs1 op rs2` (explicit register operands)
- **Zero compilation overhead** - fast AST-to-bytecode translation (~microseconds)
- **Runtime object registers** - RuntimeScalar/Array/Hash/List (no primitives, except rare specialized ops)

**Why register architecture**: Perl's complex control flow (GOTO, last/next/redo, die/eval) requires registers that persist across jumps. Stack-based architecture would corrupt state on non-local jumps.

**Expected performance**: 2-5x slower than compiled code in steady-state, but 10-50x faster for small eval strings due to eliminated compilation overhead.

## Module Structure

New package under existing src tree:
```
src/main/java/org/perlonjava/interpreter/
├── InterpretedCode.java       # Bytecode container (extends RuntimeCode)
├── BytecodeInterpreter.java   # Execution engine (switch-based dispatch)
├── BytecodeCompiler.java      # AST → bytecode translator (implements Visitor)
└── Opcodes.java               # Opcode definitions (byte constants)
```

## Key Components

### 1. InterpretedCode (Bytecode Container)

**CRITICAL: Subclass of RuntimeCode for perfect compatibility**

```java
/**
 * Interpreted bytecode that IS-A RuntimeCode (subclass).
 * Can be stored in global variables, passed as code refs, captured in closures.
 * COMPLETELY INDISTINGUISHABLE from compiled RuntimeCode to the system.
 */
public class InterpretedCode extends RuntimeCode {
    // Bytecode and metadata
    byte[] bytecode;          // Instruction opcodes (compact)
    Object[] constants;       // Constant pool (RuntimeBase objects)
    String[] stringPool;      // String constants (variable names, etc.)
    int maxRegisters;         // Number of registers needed
    RuntimeBase[] capturedVars; // Closure support (captured from outer scope)

    // Constructor
    public InterpretedCode(byte[] bytecode, Object[] constants, String[] stringPool,
                          int maxRegisters, RuntimeBase[] capturedVars) {
        this.bytecode = bytecode;
        this.constants = constants;
        this.stringPool = stringPool;
        this.maxRegisters = maxRegisters;
        this.capturedVars = capturedVars;
    }

    /**
     * Override RuntimeCode.apply() to dispatch to interpreter.
     * This is the ONLY difference from compiled code - execution engine.
     * API signature is IDENTICAL.
     */
    @Override
    public RuntimeList apply(RuntimeArray args, int contextType) {
        // Dispatch to interpreter (not compiled bytecode)
        return BytecodeInterpreter.execute(this, args, contextType);
    }

    /**
     * Override RuntimeCode.call() for method call support.
     * API signature is IDENTICAL to compiled code.
     */
    @Override
    public RuntimeList call(RuntimeScalar invocant, String method, RuntimeBase[] args, int context) {
        // Dispatch to interpreter with method call context
        return BytecodeInterpreter.executeMethod(this, invocant, method, args, context);
    }

    /**
     * Closures: Capture variables from outer scope.
     * Works EXACTLY like compiled closures.
     */
    public void captureVariables(RuntimeBase[] vars) {
        this.capturedVars = vars;
    }
}
```

**Key insight**: InterpretedCode **IS-A** RuntimeCode (not "implements same interface"). This means:
- Can be assigned to `RuntimeScalar` holding code ref
- Can be stored in `$::func` global
- Can be passed to/from compiled code
- Can be captured in closures (both directions)
- Can be used in `@ISA`, method dispatch, overload, etc.
- **No code in PerlOnJava can tell the difference** (except profiling/debugging)

**Closure example:**
```perl
# Outer compiled code
my $x = 10;
my $closure = sub { $x + $_[0] };  # Interpreted (small eval) - captures $x

# Inner interpreted code can access $x from outer compiled scope
say $closure->(5);  # 15

# Reverse: compiled code captures interpreted closure
my $interpreted = eval 'sub { shift + 1 }';  # InterpretedCode
my $compiled = sub { $interpreted->($_[0]) + 10 };  # Compiled, captures InterpretedCode
say $compiled->(5);  # 16
```

Both directions work seamlessly because InterpretedCode extends RuntimeCode.

### 2. Opcodes (Instruction Set)

Register-machine instructions (3-address code format) that mirror compiler operations:

```java
public class Opcodes {
    // Control flow
    public static final byte NOP = 0;
    public static final byte RETURN = 1;             // return rd (register)
    public static final byte GOTO = 2;               // pc = offset (absolute)
    public static final byte GOTO_IF_FALSE = 3;      // if (!rs) pc = offset

    // Register operations
    public static final byte MOVE = 10;              // rd = rs (register copy)
    public static final byte LOAD_CONST = 11;        // rd = constants[index]
    public static final byte LOAD_INT = 12;          // rd = RuntimeScalarCache.getScalarInt(immediate)
    public static final byte LOAD_STRING = 13;       // rd = new RuntimeScalar(stringPool[index])

    // Variable access (uses same GlobalVariable maps as compiler)
    public static final byte LOAD_GLOBAL_SCALAR = 20;  // rd = GlobalVariable.getGlobalScalar(name)
    public static final byte STORE_GLOBAL_SCALAR = 21; // GlobalVariable.getGlobalScalar(name).set(rs)
    public static final byte LOAD_GLOBAL_ARRAY = 22;   // rd = GlobalVariable.getGlobalArray(name)
    public static final byte STORE_GLOBAL_ARRAY = 23;
    public static final byte LOAD_GLOBAL_HASH = 24;    // rd = GlobalVariable.getGlobalHash(name)
    public static final byte STORE_GLOBAL_HASH = 25;

    // Operators (call org.perlonjava.operators.* methods) - 3-address format
    public static final byte ADD_SCALAR = 30;        // rd = MathOperators.add(rs1, rs2)
    public static final byte SUB_SCALAR = 31;        // rd = MathOperators.subtract(rs1, rs2)
    public static final byte MUL_SCALAR = 32;        // rd = MathOperators.multiply(rs1, rs2)
    public static final byte DIV_SCALAR = 33;        // rd = MathOperators.divide(rs1, rs2)
    public static final byte CONCAT = 34;            // rd = StringOperators.concat(rs1, rs2)
    public static final byte COMPARE_NUM = 35;       // rd = CompareOperators.compareNum(rs1, rs2)

    // Specialized unboxed operations (rare optimizations)
    public static final byte ADD_SCALAR_INT = 40;    // rd = MathOperators.add(rs, immediate_int)
    public static final byte CMP_SCALAR_INT = 41;    // rd = CompareOperators.compareNum(rs, immediate_int)

    // Array operations (use RuntimeArray API) - 3-address format
    public static final byte ARRAY_GET = 50;         // rd = array_reg.get(index_reg)
    public static final byte ARRAY_SET = 51;         // array_reg.set(index_reg, value_reg)
    public static final byte ARRAY_PUSH = 52;        // array_reg.push(value_reg)
    public static final byte ARRAY_SIZE = 53;        // rd = new RuntimeScalar(array_reg.size())

    // Hash operations (use RuntimeHash API) - 3-address format
    public static final byte HASH_GET = 60;          // rd = hash_reg.get(key_reg)
    public static final byte HASH_SET = 61;          // hash_reg.put(key_reg, value_reg)
    public static final byte HASH_EXISTS = 62;       // rd = hash_reg.exists(key_reg)
    public static final byte HASH_DELETE = 63;       // rd = hash_reg.delete(key_reg)

    // Subroutine calls (RuntimeCode.apply)
    public static final byte CALL_SUB = 70;          // rd = RuntimeCode.apply(coderef_reg, args_reg, context)
    public static final byte CALL_METHOD = 71;       // rd = RuntimeCode.call(obj_reg, method, args_reg, context)
    public static final byte CALL_BUILTIN = 72;      // rd = BuiltinRegistry.call(builtin_id, args_reg, context)

    // Context operations
    public static final byte LIST_TO_SCALAR = 80;    // rd = list_reg.scalar()
    public static final byte SCALAR_TO_LIST = 81;    // rd = new RuntimeList(scalar_reg)
}
```

**Encoding format:**
- Most opcodes: `[opcode] [rd] [rs1] [rs2]` (4 bytes for 3-address ops)
- Load immediate: `[opcode] [rd] [imm32]` (6 bytes for 32-bit immediate)
- Jump: `[opcode] [offset32]` (5 bytes for absolute offset)
- Conditional jump: `[opcode] [rs] [offset32]` (6 bytes)

### 3. BytecodeInterpreter (Execution Engine)

**Pure register-based dispatch calling existing operator methods:**

```java
public class BytecodeInterpreter {

    public static RuntimeList execute(InterpretedCode code, RuntimeArray args, int contextType) {
        // Pure register file (NOT stack-based - matches compiler for control flow correctness)
        RuntimeBase[] registers = new RuntimeBase[code.maxRegisters];

        // Initialize special registers
        registers[0] = code;           // $this (for closures)
        registers[1] = args;           // @_
        registers[2] = RuntimeScalarCache.getScalarInt(contextType); // wantarray

        // Copy captured variables (closure support)
        if (code.capturedVars != null) {
            System.arraycopy(code.capturedVars, 0, registers, 3, code.capturedVars.length);
        }

        int pc = 0;
        byte[] bytecode = code.bytecode;

        // Main dispatch loop - JVM optimizes to tableswitch
        while (pc < bytecode.length) {
            byte opcode = bytecode[pc++];

            switch (opcode) {
                case Opcodes.MOVE:
                    // Register-to-register copy: rd = rs
                    int dest = bytecode[pc++] & 0xFF;
                    int src = bytecode[pc++] & 0xFF;
                    registers[dest] = registers[src];
                    break;

                case Opcodes.LOAD_INT:
                    // Load immediate integer: rd = RuntimeScalarCache.getScalarInt(imm)
                    int destReg = bytecode[pc++] & 0xFF;
                    int value = readInt(bytecode, pc);
                    pc += 4;
                    // Uses SAME cache as compiled code
                    registers[destReg] = RuntimeScalarCache.getScalarInt(value);
                    break;

                case Opcodes.LOAD_GLOBAL_SCALAR:
                    // Load global scalar: rd = GlobalVariable.getGlobalScalar(name)
                    int rd = bytecode[pc++] & 0xFF;
                    int nameIndex = bytecode[pc++] & 0xFF;
                    String name = code.stringPool[nameIndex];
                    // Uses SAME GlobalVariable as compiled code
                    registers[rd] = GlobalVariable.getGlobalScalar(name);
                    break;

                case Opcodes.STORE_GLOBAL_SCALAR:
                    // Store global scalar: GlobalVariable.getGlobalScalar(name).set(rs)
                    int nameIdx = bytecode[pc++] & 0xFF;
                    int srcReg = bytecode[pc++] & 0xFF;
                    String varName = code.stringPool[nameIdx];
                    GlobalVariable.getGlobalScalar(varName).set((RuntimeScalar) registers[srcReg]);
                    break;

                case Opcodes.ADD_SCALAR:
                    // 3-address addition: rd = rs1 + rs2
                    int rdAdd = bytecode[pc++] & 0xFF;
                    int rs1Add = bytecode[pc++] & 0xFF;
                    int rs2Add = bytecode[pc++] & 0xFF;
                    // Calls SAME method as compiled code
                    registers[rdAdd] = MathOperators.add(
                        (RuntimeScalar) registers[rs1Add],
                        (RuntimeScalar) registers[rs2Add]
                    );
                    break;

                case Opcodes.ADD_SCALAR_INT:
                    // Specialized unboxed operation: rd = rs + immediate_int
                    int rdAddInt = bytecode[pc++] & 0xFF;
                    int rsAddInt = bytecode[pc++] & 0xFF;
                    int immediate = readInt(bytecode, pc);
                    pc += 4;
                    // Calls specialized unboxed method (rare optimization)
                    registers[rdAddInt] = MathOperators.add(
                        (RuntimeScalar) registers[rsAddInt],
                        immediate  // primitive int, not RuntimeScalar
                    );
                    break;

                case Opcodes.CONCAT:
                    // String concatenation: rd = rs1 . rs2
                    int rdConcat = bytecode[pc++] & 0xFF;
                    int rs1Concat = bytecode[pc++] & 0xFF;
                    int rs2Concat = bytecode[pc++] & 0xFF;
                    registers[rdConcat] = StringOperators.concat(
                        (RuntimeScalar) registers[rs1Concat],
                        (RuntimeScalar) registers[rs2Concat]
                    );
                    break;

                case Opcodes.ARRAY_GET:
                    // Array element access: rd = array_reg[index_reg]
                    int rdArray = bytecode[pc++] & 0xFF;
                    int arrayReg = bytecode[pc++] & 0xFF;
                    int indexReg = bytecode[pc++] & 0xFF;
                    RuntimeArray arr = (RuntimeArray) registers[arrayReg];
                    RuntimeScalar idx = (RuntimeScalar) registers[indexReg];
                    // Uses RuntimeArray API directly
                    registers[rdArray] = arr.get(idx.getInt());
                    break;

                case Opcodes.HASH_GET:
                    // Hash element access: rd = hash_reg{key_reg}
                    int rdHash = bytecode[pc++] & 0xFF;
                    int hashReg = bytecode[pc++] & 0xFF;
                    int keyReg = bytecode[pc++] & 0xFF;
                    RuntimeHash hash = (RuntimeHash) registers[hashReg];
                    RuntimeScalar key = (RuntimeScalar) registers[keyReg];
                    // Uses RuntimeHash API directly
                    registers[rdHash] = hash.get(key);
                    break;

                case Opcodes.CALL_SUB:
                    // Subroutine call: rd = coderef_reg->(args_reg)
                    int rdCall = bytecode[pc++] & 0xFF;
                    int coderefReg = bytecode[pc++] & 0xFF;
                    int argsReg = bytecode[pc++] & 0xFF;
                    int callContext = bytecode[pc++] & 0xFF;
                    RuntimeScalar codeRef = (RuntimeScalar) registers[coderefReg];
                    RuntimeArray callArgs = (RuntimeArray) registers[argsReg];
                    // RuntimeCode.apply works for both compiled AND interpreted code
                    RuntimeList result = RuntimeCode.apply(codeRef, "", callArgs, callContext);
                    registers[rdCall] = result;
                    break;

                case Opcodes.GOTO:
                    // Unconditional jump: pc = offset
                    // Registers persist across jump (unlike stack-based!)
                    int offset = readInt(bytecode, pc);
                    pc = offset;
                    break;

                case Opcodes.GOTO_IF_FALSE:
                    // Conditional jump: if (!rs) pc = offset
                    int condReg = bytecode[pc++] & 0xFF;
                    int target = readInt(bytecode, pc);
                    pc += 4;
                    RuntimeScalar cond = (RuntimeScalar) registers[condReg];
                    if (!cond.getBoolean()) {
                        pc = target;  // Jump - all registers stay valid!
                    }
                    break;

                case Opcodes.RETURN:
                    // Return from subroutine: return rd
                    int retReg = bytecode[pc++] & 0xFF;
                    RuntimeBase retVal = registers[retReg];
                    if (retVal instanceof RuntimeList) {
                        return (RuntimeList) retVal;
                    } else if (retVal instanceof RuntimeScalar) {
                        return new RuntimeList((RuntimeScalar) retVal);
                    }
                    return new RuntimeList();

                // ... more opcodes
            }
        }

        return new RuntimeList();
    }

    private static int readInt(byte[] bytecode, int pc) {
        return ((bytecode[pc] & 0xFF) << 24) |
               ((bytecode[pc+1] & 0xFF) << 16) |
               ((bytecode[pc+2] & 0xFF) << 8) |
               (bytecode[pc+3] & 0xFF);
    }
}
```

**Key features:**
- **Pure register file** - no expression stack, no stack pointer management
- **3-address code** - all operands explicit in bytecode
- **Control flow safe** - GOTO/last/next don't corrupt state
- **Operator reuse** - calls same `org.perlonjava.operators.*` methods as compiler
- **Specialized unboxing** - rare optimizations like `add(RuntimeScalar, int)` when beneficial

### 4. BytecodeCompiler (AST Translator)

```java
public class BytecodeCompiler implements Visitor {
    ByteArrayOutputStream bytecode;
    List<RuntimeBase> constants;      // RuntimeBase objects
    List<String> stringPool;          // Variable names
    Map<String, Integer> registerMap; // Variable name → register index
    int nextRegister = 0;             // Next available register

    public InterpretedCode compile(Node ast, EmitterContext ctx) {
        this.registerMap = buildRegisterMap(ctx.symbolTable);
        this.nextRegister = registerMap.size() + 3; // After special regs: $this, @_, wantarray

        ast.accept(this);  // Traverse AST

        // Emit return with register 0 (default return value)
        emit(Opcodes.RETURN);
        emit(0);

        return buildInterpretedCode();
    }

    @Override
    public void visit(BinaryOperatorNode node) {
        // Allocate registers for operands and result
        int rs1 = allocateTempRegister();
        int rs2 = allocateTempRegister();
        int rd = allocateTempRegister();

        // Compile left operand → rs1
        node.left.accept(this);
        emit(Opcodes.MOVE);
        emit(rs1);
        emit(getLastResultRegister());

        // Compile right operand → rs2
        node.right.accept(this);
        emit(Opcodes.MOVE);
        emit(rs2);
        emit(getLastResultRegister());

        // Emit 3-address opcode: rd = rs1 op rs2
        switch (node.operator) {
            case "+" -> {
                // Check if right operand is constant int for optimization
                if (node.right instanceof NumberNode && ((NumberNode) node.right).isInteger()) {
                    // Specialized unboxed operation
                    emit(Opcodes.ADD_SCALAR_INT);
                    emit(rd);
                    emit(rs1);
                    emitInt(((NumberNode) node.right).intValue());
                } else {
                    // General operation
                    emit(Opcodes.ADD_SCALAR);    // → MathOperators.add(rs1, rs2)
                    emit(rd);
                    emit(rs1);
                    emit(rs2);
                }
            }
            case "." -> {
                emit(Opcodes.CONCAT);         // → StringOperators.concat(rs1, rs2)
                emit(rd);
                emit(rs1);
                emit(rs2);
            }
            case "<=>" -> {
                emit(Opcodes.COMPARE_NUM);    // → CompareOperators.compareNum(rs1, rs2)
                emit(rd);
                emit(rs1);
                emit(rs2);
            }
            // ... more operators
        }

        setLastResultRegister(rd);
        freeTempRegister(rs1);
        freeTempRegister(rs2);
    }

    @Override
    public void visit(IdentifierNode node) {
        int rd = allocateTempRegister();

        if (registerMap.containsKey(node.name)) {
            // Lexical variable - register is already allocated
            emit(Opcodes.MOVE);
            emit(rd);
            emit(registerMap.get(node.name));
        } else {
            // Global variable - uses same GlobalVariable map as compiled code
            emit(Opcodes.LOAD_GLOBAL_SCALAR);
            emit(rd);
            emit(getStringPoolIndex(node.name));
        }

        setLastResultRegister(rd);
    }

    @Override
    public void visit(ArrayIndexNode node) {
        int arrayReg = allocateTempRegister();
        int indexReg = allocateTempRegister();
        int rd = allocateTempRegister();

        // Compile array expression → arrayReg
        node.array.accept(this);
        emit(Opcodes.MOVE);
        emit(arrayReg);
        emit(getLastResultRegister());

        // Compile index expression → indexReg
        node.index.accept(this);
        emit(Opcodes.MOVE);
        emit(indexReg);
        emit(getLastResultRegister());

        // Emit array access: rd = array[index]
        emit(Opcodes.ARRAY_GET);   // → RuntimeArray.get()
        emit(rd);
        emit(arrayReg);
        emit(indexReg);

        setLastResultRegister(rd);
        freeTempRegister(arrayReg);
        freeTempRegister(indexReg);
    }

    @Override
    public void visit(GotoNode node) {
        // Emit GOTO - registers persist across jump (unlike stack-based!)
        emit(Opcodes.GOTO);
        emitInt(getLabelOffset(node.label));
    }

    private int allocateTempRegister() {
        return nextRegister++;
    }

    private void freeTempRegister(int reg) {
        // Simple allocator - could be optimized with register reuse
    }

    private void emit(byte opcode) {
        bytecode.write(opcode);
    }

    private void emitInt(int value) {
        bytecode.write((value >> 24) & 0xFF);
        bytecode.write((value >> 16) & 0xFF);
        bytecode.write((value >> 8) & 0xFF);
        bytecode.write(value & 0xFF);
    }
}
```

**Key features:**
- **3-address code generation** - all operands explicit in bytecode
- **Register allocation** - maps variables to registers
- **Specialized unboxing** - detects constant integers and uses optimized opcodes
- **Control flow safe** - GOTO generates absolute offsets, registers persist

## Integration with Existing Code

### RuntimeCode Integration

InterpretedCode **extends RuntimeCode** (or implements same interface), so it's indistinguishable from compiled code:

```java
// User code - doesn't know if $coderef is compiled or interpreted
my $coderef = sub { $_[0] + 1 };  # Could be either!
say $coderef->(41);                # Works the same

# Global code refs - shared between compiled and interpreted
$::my_func = sub { ... };          # Interpreted closure
some_compiled_function($::my_func); # Passes interpreted code to compiled code

# Eval creates interpreted code (fast)
my $result = eval '$x + 1';        # InterpretedCode, not compiled
```

**API Compatibility**: The internal API is **IDENTICAL**:
- `RuntimeCode.apply(RuntimeArray @_, int wantarray)` → `RuntimeList`
- Can be stored in `$scalar`, `@array`, `%hash`
- Can capture variables from outer scope (closures)
- Can be passed to/from compiled code

### Shared Runtime Components

Interpreter reuses **ALL** existing runtime (no duplication):

1. **Global Variables** - `GlobalVariable.getGlobalScalar()`, `getGlobalArray()`, `getGlobalHash()`
2. **Runtime Types** - `RuntimeScalar`, `RuntimeArray`, `RuntimeHash`, `RuntimeList`, `RuntimeCode`
3. **Operators** - `org.perlonjava.operators.MathOperators`, `StringOperators`, `CompareOperators`, etc.
4. **Caches** - `RuntimeScalarCache.getScalarInt()`, `getScalarByteString()`
5. **Symbol Tables** - `ScopedSymbolTable` (for lexical scopes)
6. **Context** - `RuntimeContextType` (VOID=0, SCALAR=1, LIST=2, RUNTIME=3)
7. **Dynamic Variables** - `DynamicVariableManager` (for `local` operator)

**No code duplication** - compiled and interpreted code share identical runtime semantics.

### Heuristics for Interpreter vs Compiler

Add decision logic in compilation pipeline:

```java
boolean shouldInterpret(Node ast, String source) {
    // Small eval strings - avoid compilation overhead
    if (source.length() < 100) {
        return true;  // Interpreter wins: 10-50x faster
    }

    // Very large one-time code - avoid compilation time/memory
    int estimatedSize = estimateSize(ast);
    if (estimatedSize > 50000 && isOneTimeExecution()) {
        return true;
    }

    // Hot path (loop, repeated eval) - compile for speed
    if (isInEvalLoop() || isHotPath()) {
        return false;
    }

    // Default: compile (existing behavior)
    return false;
}
```

## Implementation Phases

### Phase 0: Architecture Benchmarking - COMPLETE ✓

**Result**: Switch-based dispatch is 2.25-4.63x faster than function-array dispatch.

### Phase 1: Core Interpreter (1-2 weeks)

1. **InterpretedCode extends RuntimeCode** (subclass, not interface)
   - Implements `apply(RuntimeArray, int)` → calls BytecodeInterpreter.execute()
   - Implements `call(RuntimeScalar, String, RuntimeBase[], int)` → calls BytecodeInterpreter.executeMethod()
   - Supports closure variable capture via `capturedVars` array
   - **Completely indistinguishable from compiled RuntimeCode** to all system code

2. **Opcodes.java** - Define 50-100 opcodes in 3-address format
   - Variable access (register moves, global scalar/array/hash)
   - Operators (call `org.perlonjava.operators.*` with 3-address encoding)
   - Array/Hash operations (use RuntimeArray/RuntimeHash API)
   - Control flow (GOTO, GOTO_IF_FALSE, RETURN with absolute offsets)
   - Subroutine calls (RuntimeCode.apply - works for compiled AND interpreted)
   - Specialized unboxed ops (rare optimizations like `add(RuntimeScalar, int)`)

3. **BytecodeInterpreter.java** - Switch-based execution with pure register file
   - RuntimeBase[] registers (NOT stack - no sp management)
   - 3-address code execution (rd = rs1 op rs2)
   - Call existing operator methods from `org.perlonjava.operators.*`
   - Share GlobalVariable maps with compiled code
   - Return RuntimeList
   - Handle GOTO/control flow correctly (registers persist across jumps)

4. **BytecodeCompiler.java** - AST → bytecode translator
   - Visitor pattern (like EmitterVisitor)
   - Generate 3-address code with register allocation
   - Build constant pool (RuntimeBase objects)
   - Build string pool (variable names)
   - Detect constant integers for specialized unboxed operations
   - Generate absolute offsets for GOTO (not relative)

5. **Unit tests**
   - Basic operations (arithmetic, string concat)
   - Variable access (lexical registers, global variables)
   - Arrays and hashes
   - Control flow (GOTO, conditionals, loops)
   - Mixed compiled/interpreted calls (both directions)
   - Closures (both directions: compiled capturing interpreted, interpreted capturing compiled)

### Phase 2: Integration (1 week)

1. **Modify compilation pipeline** - Add interpreter decision heuristics
2. **Integration tests** - Compiled code calling interpreted, vice versa
3. **Closure tests** - Interpreted closures captured by compiled code
4. **Global variable tests** - Shared state between compiled and interpreted

### Phase 3: Optimization (1-2 weeks)

1. **Inline caching** for polymorphic operations (if needed)
2. **Fast paths** for common operations (if profiling shows hotspots)
3. **Reduce allocation** where possible (object pooling, reuse)
4. **Profile and optimize** based on real workload data

### Phase 4: Coverage (2-3 weeks)

1. **Expand opcode set** - Cover all Perl operators
2. **Special cases** - regex, tie, overload, etc.
3. **Comprehensive test suite** - Perl 5 compatibility tests
4. **Edge cases** - closures, eval, die/catch, control flow

### Phase 5: Benchmarking & Tuning (1 week)

1. **Micro-benchmarks** - Verify performance targets
2. **Compare with compilation** - Measure speedup for small evals
3. **Document performance** - Update docs with benchmarks
4. **Final optimizations** - Based on profiling data

**Total Timeline: 6-9 weeks**

## Performance Targets

| Use Case | Interpreter | Compiler | Result |
|----------|------------|----------|---------|
| Small eval (< 50 chars) | 1-5ms | 50ms | **10-50x faster** ✓ |
| Large code (one-time) | 10-20ms | 200ms+ | **10-20x faster** ✓ |
| Steady-state execution | 200-600M ops/sec | 2M-5M ops/sec | 2-5x slower (acceptable) |

## Critical Files

### Files to Modify
1. `/Users/fglock/projects/PerlOnJava/src/main/java/org/perlonjava/runtime/RuntimeCode.java` - InterpretedCode extends this or implements same interface
2. `/Users/fglock/projects/PerlOnJava/src/main/java/org/perlonjava/scriptengine/PerlLanguageProvider.java` - Add interpreter decision heuristics

### Files to Reference
1. `/Users/fglock/projects/PerlOnJava/src/main/java/org/perlonjava/operators/*.java` - All operator implementations to call
2. `/Users/fglock/projects/PerlOnJava/src/main/java/org/perlonjava/astvisitor/EmitterVisitor.java` - Visitor pattern for AST traversal
3. `/Users/fglock/projects/PerlOnJava/src/main/java/org/perlonjava/runtime/GlobalVariable.java` - Global variable access
4. `/Users/fglock/projects/PerlOnJava/src/main/java/org/perlonjava/runtime/RuntimeScalarCache.java` - Cached constants

### Files to Create
All new files under `src/main/java/org/perlonjava/interpreter/`:
- `InterpretedCode.java` (extends RuntimeCode)
- `BytecodeInterpreter.java` (switch-based dispatch)
- `BytecodeCompiler.java` (AST → bytecode translator)
- `Opcodes.java` (opcode constants)

## Success Criteria

- [x] **Phase 0 complete** - Switch-based dispatch proven 2.25-4.63x faster than function-array
- [x] **Register architecture confirmed** - Required for control flow correctness (GOTO/last/next/redo)
- [ ] Small eval strings (< 100 chars) execute 10-50x faster than compilation
- [ ] Large one-time code (> 50KB) executes 10-20x faster than compilation
- [ ] InterpretedCode extends RuntimeCode (IS-A subclass, perfect compatibility)
- [ ] Compiled and interpreted code can call each other seamlessly (both directions)
- [ ] Global variables shared between compiled and interpreted code (same GlobalVariable maps)
- [ ] Interpreted closures work exactly like compiled closures (capture works both directions)
- [ ] All existing unit tests pass with interpreter enabled
- [ ] Interpreter uses RuntimeBase objects in registers (no primitives except specialized ops)
- [ ] Interpreter calls org.perlonjava.operators.* methods (100% code reuse)
- [ ] Specialized unboxed operations used where beneficial (e.g., add(RuntimeScalar, int))
- [ ] Control flow (GOTO/last/next/redo) works correctly (registers persist across jumps)
- [ ] Memory overhead < 1KB per InterpretedCode instance

## Documentation

### Plan File Location
This plan: `dev/design/interpreter.md`
Benchmarking results: `dev/design/interpreter_benchmarks.md`

### Documentation Updates
Add to README.md:
- **Experimental feature** - interpreter is new and under active development
- Performance characteristics - when interpreter is used vs compiler
- Opt-out mechanism - environment variable to disable interpreter (for testing)

### Example Documentation

```markdown
## Interpreter Mode (Experimental)

PerlOnJava includes an experimental bytecode interpreter for:
- Small eval strings (< 100 characters)
- Very large code executed once (> 50KB)

The interpreter is 10-50x faster than compilation for small code,
but 2-5x slower than compiled code in steady-state execution.

Compiled and interpreted code can be mixed seamlessly:
- Same RuntimeScalar/Array/Hash/List objects
- Shared global variables ($::x, @::arr, %::hash)
- Interpreted closures work like compiled closures
- No API differences

To disable the interpreter (use compiler for all code):
```bash
export PERLONJAVA_DISABLE_INTERPRETER=1
```

## Future Enhancements (Out of Scope)

- JIT compilation: hot interpreted code promoted to compiled code
- Adaptive optimization: profile-guided opcode selection
- Tiered compilation: interpreter → baseline compiler → optimizing compiler
- Specialized opcodes: regex-specific, hash-specific operations
