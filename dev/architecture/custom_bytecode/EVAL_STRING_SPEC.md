# eval STRING with Interpreter Backend - Specification

## Goal
Allow the compiler's eval STRING to use the interpreter backend for 46x faster compilation when executing many unique eval strings.

## Environment Variable

```java
// In RuntimeCode.java or EmitterMethodCreator.java
private static final boolean EVAL_USE_INTERPRETER =
    System.getenv("JPERL_EVAL_USE_INTERPRETER") != null;
```

## Behavior

### Case 1: Compiler Mode with JPERL_EVAL_USE_INTERPRETER=1

**Location:** `RuntimeCode.evalStringHelper()`

**Current behavior (ENV not set):**
1. Parse eval string to AST
2. Compile AST to JVM bytecode (Class)
3. EmitEval instantiates Class with captured variables
4. EmitEval calls apply() on the instance

**New behavior (ENV is set):**
1. Parse eval string to AST
2. Compile AST to **InterpretedCode** instead of Class
3. Set captured variables on InterpretedCode
4. Call InterpretedCode.apply() directly
5. Return result

**Implementation:**
- Modify `RuntimeCode.evalStringHelper()` to check `EVAL_USE_INTERPRETER`
- If true: Call `BytecodeCompiler.compile(ast)` → `InterpretedCode`
- Set captured vars: `interpretedCode.withCapturedVars(runtimeValues)`
- Execute: `interpretedCode.apply(args, context)`
- Return result **directly** (no Class generation, no reflection, no makeCodeObject)

**Key difference:** Skip ALL class generation. Just create InterpretedCode and run it.

### Case 2: Interpreter Mode (EVAL_STRING opcode)

**Location:** `SlowOpcodeHandler.java` case `EVAL_STRING`

**Behavior:** ALWAYS uses interpreter recursively

1. Get eval string from register
2. Parse to AST
3. Compile to InterpretedCode
4. Execute InterpretedCode
5. Store result in register

**No changes needed** - this is already the correct behavior.

## Implementation Checklist

### Step 1: Add constant
```java
// In RuntimeCode.java (near other eval code)
private static final boolean EVAL_USE_INTERPRETER =
    System.getenv("JPERL_EVAL_USE_INTERPRETER") != null;
```

### Step 2: Modify evalStringHelper()
```java
public static Class<?> evalStringHelper(RuntimeScalar code, String evalTag, Object[] runtimeValues) throws Exception {
    if (EVAL_USE_INTERPRETER) {
        // NEW PATH: Use interpreter directly
        return evalStringWithInterpreter(code, evalTag, runtimeValues);
    }

    // EXISTING PATH: Compile to Class
    // ... current implementation ...
}
```

### Step 3: Add evalStringWithInterpreter()
```java
private static Class<?> evalStringWithInterpreter(RuntimeScalar code, String evalTag, Object[] runtimeValues) {
    // 1. Get eval context
    EmitterContext ctx = evalContext.get(evalTag);

    // 2. Parse eval string
    String evalString = code.toString();
    Lexer lexer = new Lexer(evalString);
    List<LexerToken> tokens = lexer.tokenize();
    Parser parser = new Parser(ctx, tokens);
    Node ast = parser.parse();

    // 3. Compile to InterpretedCode
    BytecodeCompiler compiler = new BytecodeCompiler(ctx.compilerOptions.fileName, 1, ctx.errorUtil);
    InterpretedCode interpretedCode = compiler.compile(ast);

    // 4. Set captured variables
    if (runtimeValues.length > 0) {
        RuntimeBase[] capturedVars = new RuntimeBase[runtimeValues.length];
        for (int i = 0; i < runtimeValues.length; i++) {
            capturedVars[i] = (RuntimeBase) runtimeValues[i];
        }
        interpretedCode = interpretedCode.withCapturedVars(capturedVars);
    }

    // 5. Execute directly
    // TODO: How to return result without EmitEval expecting a Class?
    // ANSWER: Change evalStringHelper signature or create wrapper
}
```

## Problem: Return Type Mismatch

**Issue:** `evalStringHelper()` returns `Class<?>` but EmitEval expects a Class to instantiate.

**Solution Options:**

### Option A: Return Marker Class (SIMPLEST)
Create a single pre-compiled marker class that EmitEval can instantiate:

```java
// EvalInterpreterMarker.java - compiled once, used for all evals
public class EvalInterpreterMarker {
    private final String key;

    public EvalInterpreterMarker(String key, RuntimeBase... captured) {
        this.key = key;
        // Captured vars already set by evalStringWithInterpreter
    }

    public RuntimeList apply(RuntimeArray args, int ctx) {
        return RuntimeCode.executeStoredInterpretedCode(key, args, ctx);
    }
}
```

Then:
```java
private static Class<?> evalStringWithInterpreter(...) {
    // ... compile to InterpretedCode ...

    // Store in map with unique key
    String key = "eval_" + counter++;
    interpretedCodeCache.put(key, interpretedCode);

    // Return marker class
    return EvalInterpreterMarker.class;
}
```

EmitEval continues to work unchanged - it instantiates EvalInterpreterMarker and calls apply().

### Option B: Modify EmitEval (MORE COMPLEX)
Add runtime check in EmitEval to handle both Class and InterpretedCode returns.
**Rejected:** Requires modifying bytecode generation logic.

## Recommendation

**Use Option A:** Single marker class that all evals share.

**Benefits:**
- No changes to EmitEval bytecode generation
- No per-eval class generation (46x speedup achieved)
- Simple map lookup for stored InterpretedCode
- Clean separation of concerns

**Implementation:**
1. Create `EvalInterpreterMarker.java` (one-time, pre-compiled)
2. Add `interpretedCodeCache` map in RuntimeCode
3. Modify `evalStringHelper()` to store InterpretedCode and return marker class
4. Add `executeStoredInterpretedCode()` helper

## Performance Target

**Before:**
```bash
time ./jperl -e 'for my $x (1..5_000_000) { eval "\$var$x++" }'
# 14.08s (class generation overhead)
```

**After:**
```bash
time JPERL_EVAL_USE_INTERPRETER=1 ./jperl -e 'for my $x (1..5_000_000) { eval "\$var$x++" }'
# <1s (no class generation, direct interpreter execution)
```

## Testing

```bash
# Test basic eval
JPERL_EVAL_USE_INTERPRETER=1 ./jperl -E 'say eval "1 + 1"'  # Should print 2

# Test with variables
JPERL_EVAL_USE_INTERPRETER=1 ./jperl -E 'my $x = 42; say eval "\$x + 1"'  # Should print 43

# Test many unique evals (performance test)
time JPERL_EVAL_USE_INTERPRETER=1 ./jperl -e 'for (1..10000) { eval "1+$_" }'

# Verify compiler mode still works
./jperl -E 'say eval "1 + 1"'  # Should print 2
```
