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

### Key Insight: Mirror the Compiler's Approach

Analysis of `./jperl --disassemble` output reveals the compiler:

1. **Uses RuntimeBase objects everywhere** - no primitive unboxing in operations
2. **Calls static operator methods** - `MathOperators.add(RuntimeScalar, RuntimeScalar)`
3. **Shares global variables** - via `GlobalVariable` maps
4. **Uses RuntimeScalarCache** - for constant values

**The interpreter must do EXACTLY the same:**

```java
// Interpreter operations mirror compiled bytecode
RuntimeBase[] locals = new RuntimeBase[maxLocals];  // RuntimeScalar, RuntimeArray, RuntimeHash, etc.
RuntimeBase[] stack = new RuntimeBase[maxStack];
int sp = 0;

case Opcodes.ADD_SCALAR:
    RuntimeScalar b = (RuntimeScalar) stack[--sp];
    RuntimeScalar a = (RuntimeScalar) stack[--sp];
    // Call the SAME method the compiler uses
    stack[sp++] = MathOperators.add(a, b);  // org.perlonjava.operators.MathOperators
    break;

case Opcodes.LOAD_GLOBAL_SCALAR:
    String name = code.stringPool[bytecode[pc++]];
    stack[sp++] = GlobalVariable.getGlobalScalar(name);  // Shared with compiled code
    break;

case Opcodes.ARRAY_GET:
    RuntimeScalar index = (RuntimeScalar) stack[--sp];
    RuntimeArray array = (RuntimeArray) stack[--sp];
    stack[sp++] = array.get(index.getInt());  // Use existing RuntimeArray API
    break;
```

### Critical Requirements

1. **No Primitive Unboxing** - Everything stays as RuntimeBase objects
2. **Reuse Operator Implementations** - Call `org.perlonjava.operators.*` methods directly
3. **Share Global State** - Same `GlobalVariable` maps for `$::x`, `@::arr`, `%::hash`
4. **RuntimeCode Compatibility** - Interpreted code looks exactly like compiled code to callers

## Architecture: Hybrid Bytecode Interpreter

### Design: Custom Bytecode with Switch Dispatch

- **Custom bytecode format** (NOT JVM bytecode) - optimized for Perl semantics
- **Switch-based dispatch** - JVM JIT optimizes to tableswitch (O(1) jump table)
- **Register machine** - fewer instructions than stack machine
- **Zero compilation overhead** - fast AST-to-bytecode translation (~microseconds)
- **Runtime object stack** - RuntimeScalar/Array/Hash/List (no primitives)

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

**CRITICAL: Must be compatible with RuntimeCode**

```java
/**
 * Interpreted bytecode that looks exactly like compiled RuntimeCode to the system.
 * Can be stored in global variables, passed as code refs, captured in closures.
 */
public class InterpretedCode extends RuntimeCode {
    byte[] bytecode;          // Instruction opcodes (compact)
    Object[] constants;       // Constant pool (RuntimeBase objects)
    String[] stringPool;      // String constants (variable names, etc.)
    int maxLocals;            // Number of local variable slots
    int maxStack;             // Max operand stack depth
    RuntimeBase[] capturedVars; // Closure support (captured from outer scope)

    @Override
    public RuntimeList apply(RuntimeArray args, int contextType) {
        // Dispatch to interpreter
        return BytecodeInterpreter.execute(this, args, contextType);
    }

    @Override
    public RuntimeList call(RuntimeScalar invocant, String method, RuntimeBase[] args, int context) {
        // Method call support
        return BytecodeInterpreter.executeMethod(this, invocant, method, args, context);
    }
}
```

**Key insight**: Interpreted closures are indistinguishable from compiled closures - both extend `RuntimeCode`, both have `apply()` method, both can capture variables.

### 2. Opcodes (Instruction Set)

Register-machine instructions that mirror compiler operations:

```java
public class Opcodes {
    // Control flow
    public static final byte NOP = 0;
    public static final byte RETURN = 1;
    public static final byte JUMP = 2;
    public static final byte JUMP_IF_FALSE = 3;

    // Variable access (uses same GlobalVariable maps as compiler)
    public static final byte LOAD_LOCAL = 10;        // locals[index]
    public static final byte STORE_LOCAL = 11;       // locals[index] = value
    public static final byte LOAD_GLOBAL_SCALAR = 12; // GlobalVariable.getGlobalScalar(name)
    public static final byte STORE_GLOBAL_SCALAR = 13;
    public static final byte LOAD_GLOBAL_ARRAY = 14;  // GlobalVariable.getGlobalArray(name)
    public static final byte STORE_GLOBAL_ARRAY = 15;
    public static final byte LOAD_GLOBAL_HASH = 16;   // GlobalVariable.getGlobalHash(name)
    public static final byte STORE_GLOBAL_HASH = 17;

    // Constants (from RuntimeScalarCache or constant pool)
    public static final byte LOAD_CONST = 20;        // constants[index]
    public static final byte LOAD_STRING = 21;       // stringPool[index]
    public static final byte LOAD_INT = 22;          // RuntimeScalarCache.getScalarInt(n)

    // Operators (call org.perlonjava.operators.* methods)
    public static final byte ADD_SCALAR = 30;        // MathOperators.add(a, b)
    public static final byte SUB_SCALAR = 31;        // MathOperators.subtract(a, b)
    public static final byte MUL_SCALAR = 32;        // MathOperators.multiply(a, b)
    public static final byte CONCAT = 33;            // StringOperators.concat(a, b)
    public static final byte COMPARE_NUM = 34;       // CompareOperators.compareNum(a, b)

    // Array operations (use RuntimeArray API)
    public static final byte ARRAY_GET = 40;         // array.get(index)
    public static final byte ARRAY_SET = 41;         // array.set(index, value)
    public static final byte ARRAY_PUSH = 42;        // array.push(value)

    // Hash operations (use RuntimeHash API)
    public static final byte HASH_GET = 50;          // hash.get(key)
    public static final byte HASH_SET = 51;          // hash.put(key, value)
    public static final byte HASH_EXISTS = 52;       // hash.exists(key)

    // Subroutine calls (RuntimeCode.apply)
    public static final byte CALL_SUB = 60;          // RuntimeCode.apply(codeRef, args, context)
    public static final byte CALL_METHOD = 61;       // RuntimeCode.call(obj, method, args, context)
    public static final byte CALL_BUILTIN = 62;      // BuiltinRegistry.call(id, args, context)

    // Context operations
    public static final byte LIST_TO_SCALAR = 70;    // RuntimeList.scalar()
    public static final byte SCALAR_TO_LIST = 71;    // new RuntimeList(scalar)
}
```

### 3. BytecodeInterpreter (Execution Engine)

**Switch-based dispatch calling existing operator methods:**

```java
public class BytecodeInterpreter {

    public static RuntimeList execute(InterpretedCode code, RuntimeArray args, int contextType) {
        // Runtime object arrays (NOT primitives)
        RuntimeBase[] locals = new RuntimeBase[code.maxLocals];
        RuntimeBase[] stack = new RuntimeBase[code.maxStack];
        int sp = 0;

        // Initialize special variables
        locals[0] = code;           // $this (for closures)
        locals[1] = args;           // @_
        locals[2] = RuntimeScalarCache.getScalarInt(contextType); // wantarray

        // Copy captured variables (closure support)
        if (code.capturedVars != null) {
            System.arraycopy(code.capturedVars, 0, locals, 3, code.capturedVars.length);
        }

        int pc = 0;
        byte[] bytecode = code.bytecode;

        // Main dispatch loop - JVM optimizes to tableswitch
        while (pc < bytecode.length) {
            byte opcode = bytecode[pc++];

            switch (opcode) {
                case Opcodes.LOAD_LOCAL:
                    int index = bytecode[pc++] & 0xFF;
                    stack[sp++] = locals[index];
                    break;

                case Opcodes.STORE_LOCAL:
                    int storeIndex = bytecode[pc++] & 0xFF;
                    locals[storeIndex] = stack[--sp];
                    break;

                case Opcodes.LOAD_GLOBAL_SCALAR:
                    int nameIndex = bytecode[pc++] & 0xFF;
                    String name = code.stringPool[nameIndex];
                    // Uses SAME GlobalVariable as compiled code
                    stack[sp++] = GlobalVariable.getGlobalScalar(name);
                    break;

                case Opcodes.LOAD_INT:
                    int value = readInt(bytecode, pc);
                    pc += 4;
                    // Uses SAME cache as compiled code
                    stack[sp++] = RuntimeScalarCache.getScalarInt(value);
                    break;

                case Opcodes.ADD_SCALAR:
                    RuntimeScalar b = (RuntimeScalar) stack[--sp];
                    RuntimeScalar a = (RuntimeScalar) stack[--sp];
                    // Calls SAME method as compiled code
                    stack[sp++] = MathOperators.add(a, b);
                    break;

                case Opcodes.ARRAY_GET:
                    RuntimeScalar idx = (RuntimeScalar) stack[--sp];
                    RuntimeArray arr = (RuntimeArray) stack[--sp];
                    // Uses RuntimeArray API directly
                    stack[sp++] = arr.get(idx.getInt());
                    break;

                case Opcodes.HASH_GET:
                    RuntimeScalar key = (RuntimeScalar) stack[--sp];
                    RuntimeHash hash = (RuntimeHash) stack[--sp];
                    // Uses RuntimeHash API directly
                    stack[sp++] = hash.get(key);
                    break;

                case Opcodes.CALL_SUB:
                    int argCount = bytecode[pc++] & 0xFF;
                    int callContext = bytecode[pc++] & 0xFF;
                    RuntimeArray callArgs = new RuntimeArray();
                    for (int i = 0; i < argCount; i++) {
                        callArgs.push(stack[--sp]);
                    }
                    RuntimeScalar codeRef = (RuntimeScalar) stack[--sp];
                    // RuntimeCode.apply works for both compiled AND interpreted code
                    RuntimeList result = RuntimeCode.apply(codeRef, "", callArgs, callContext);
                    stack[sp++] = result;
                    break;

                case Opcodes.RETURN:
                    if (sp > 0) {
                        RuntimeBase retVal = stack[--sp];
                        if (retVal instanceof RuntimeList) {
                            return (RuntimeList) retVal;
                        } else {
                            return new RuntimeList((RuntimeScalar) retVal);
                        }
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

### 4. BytecodeCompiler (AST Translator)

```java
public class BytecodeCompiler implements Visitor {
    ByteArrayOutputStream bytecode;
    List<RuntimeBase> constants;      // RuntimeBase objects
    List<String> stringPool;          // Variable names
    Map<String, Integer> localIndices; // Variable name → local slot

    public InterpretedCode compile(Node ast, EmitterContext ctx) {
        this.localIndices = buildLocalMap(ctx.symbolTable);
        ast.accept(this);  // Traverse AST
        emit(Opcodes.RETURN);
        return buildInterpretedCode();
    }

    @Override
    public void visit(BinaryOperatorNode node) {
        node.left.accept(this);   // Compile left operand → stack
        node.right.accept(this);  // Compile right operand → stack

        // Emit opcode that calls same operator as compiler
        switch (node.operator) {
            case "+" -> emit(Opcodes.ADD_SCALAR);    // → MathOperators.add()
            case "." -> emit(Opcodes.CONCAT);         // → StringOperators.concat()
            case "<=>" -> emit(Opcodes.COMPARE_NUM); // → CompareOperators.compareNum()
            // ... more operators
        }
    }

    @Override
    public void visit(IdentifierNode node) {
        if (localIndices.containsKey(node.name)) {
            // Lexical variable
            emit(Opcodes.LOAD_LOCAL);
            emit(localIndices.get(node.name));
        } else {
            // Global variable - uses same GlobalVariable map as compiled code
            emit(Opcodes.LOAD_GLOBAL_SCALAR);
            emit(getStringPoolIndex(node.name));
        }
    }

    @Override
    public void visit(ArrayIndexNode node) {
        node.array.accept(this);   // → RuntimeArray on stack
        node.index.accept(this);   // → RuntimeScalar on stack
        emit(Opcodes.ARRAY_GET);   // → RuntimeArray.get()
    }
}
```

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

1. **InterpretedCode extends RuntimeCode**
   - Implements `apply(RuntimeArray, int)` → calls interpreter
   - Supports closure variable capture
   - Indistinguishable from compiled code to callers

2. **Opcodes.java** - Define 50-100 opcodes mirroring compiler operations
   - Variable access (local, global scalar/array/hash)
   - Operators (call `org.perlonjava.operators.*`)
   - Array/Hash operations (use RuntimeArray/RuntimeHash API)
   - Control flow (JUMP, RETURN)
   - Subroutine calls (RuntimeCode.apply)

3. **BytecodeInterpreter.java** - Switch-based execution
   - RuntimeBase[] stack and locals (no primitives)
   - Call existing operator methods
   - Share GlobalVariable maps
   - Return RuntimeList

4. **BytecodeCompiler.java** - AST → bytecode translator
   - Visitor pattern (like EmitterVisitor)
   - Generate opcodes that mirror compiler operations
   - Build constant pool (RuntimeBase objects)
   - Build string pool (variable names)

5. **Unit tests**
   - Basic operations (arithmetic, string concat)
   - Variable access (lexical, global)
   - Arrays and hashes
   - Control flow
   - Mixed compiled/interpreted calls

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

- [x] **Phase 0 complete** - Switch-based dispatch proven 2.25-4.63x faster
- [ ] Small eval strings (< 100 chars) execute 10-50x faster than compilation
- [ ] Large one-time code (> 50KB) executes 10-20x faster than compilation
- [ ] InterpretedCode is indistinguishable from RuntimeCode to callers
- [ ] Compiled and interpreted code can call each other seamlessly
- [ ] Global variables shared between compiled and interpreted code
- [ ] Interpreted closures work exactly like compiled closures
- [ ] All existing unit tests pass with interpreter enabled
- [ ] Interpreter uses RuntimeBase objects (no primitive unboxing)
- [ ] Interpreter calls org.perlonjava.operators.* methods (reuse)
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
