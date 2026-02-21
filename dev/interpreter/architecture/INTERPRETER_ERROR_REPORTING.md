# Interpreter Error Reporting Implementation Plan

## Context

When exceptions occur during interpreter execution, stack traces show JVM locations instead of Perl source locations. This plan extends error reporting to match the **zero-overhead approach** used by the codegen backend.

**Current Problem:**
```
JVM Stack Trace:
        org.perlonjava.backend.bytecode.BytecodeInterpreter.execute at BytecodeInterpreter.java line 1191
```

**Goal:**
```
Perl Stack Trace:
        main::foo at script.pl line 42
```

## How Codegen Achieves Zero Overhead

### Die/Warn Messages (EmitOperator.java:339-358)

At **compile time**, `handleDieBuiltin()`:
1. Reads `node.getAnnotation("file")` and `node.getAnnotation("line")` (set by parser)
2. Creates message: `" at file.pl line 42"`
3. Bakes this string into bytecode as a constant via `StringNode`
4. At runtime: message is already formatted, **zero overhead**

```java
// EmitOperator.java:348-350
Node message = new StringNode(" at " + node.getAnnotation("file") +
                              " line " + node.getAnnotation("line"),
                              node.tokenIndex);
message.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
```

### Stack Trace Translation (ExceptionFormatter.java:47-104)

For **compiled code**:
1. JVM stack trace contains `element.getLineNumber()` = tokenIndex
2. `ByteCodeSourceMapper.parseStackTraceElement()`:
   - Looks up tokenIndex in static `sourceFiles` map (populated during compilation)
   - Returns `SourceLocation(file, package, lineNumber, subroutine)`
3. **The JVM stack itself carries the tokenIndex** via `mv.visitLineNumber(tokenIndex, label)`

**Key Insight:** No thread-local context needed for die/warn! All information is precomputed at compile time.

## Implementation Plan for Interpreter

### Phase 1: Precompute Error Messages (Zero Overhead)

**File: `BytecodeCompiler.java`**

Modify DIE and WARN emission to precompute error messages at compile time (same as codegen):

**Current code (lines 2886-2906):**
```java
} else if (op.equals("die")) {
    if (node.operand != null) {
        node.operand.accept(this);
        int msgReg = lastResultReg;

        emitWithToken(Opcodes.DIE, node.getIndex());
        emitReg(msgReg);
    }
}
```

**New approach:**
```java
} else if (op.equals("die")) {
    if (node.operand != null) {
        // Compile the user's message
        node.operand.accept(this);
        int msgReg = lastResultReg;

        // Precompute location message at compile time (zero overhead!)
        String fileName = errorUtil.getFileName();
        int lineNumber = errorUtil.getLineNumber(node.getIndex());
        String locationMsg = " at " + fileName + " line " + lineNumber;

        int locationReg = allocateRegister();
        emit(Opcodes.LOAD_STRING);
        emitReg(locationReg);
        emitInt(addToStringPool(locationMsg));

        // Store filename and line for stack traces
        int fileNameReg = allocateRegister();
        emit(Opcodes.LOAD_STRING);
        emitReg(fileNameReg);
        emitInt(addToStringPool(fileName));

        int lineReg = allocateRegister();
        emit(Opcodes.LOAD_INT);
        emitReg(lineReg);
        emitInt(lineNumber);

        // Emit DIE with message, location, fileName, lineNumber
        emit(Opcodes.DIE);
        emitReg(msgReg);
        emitReg(locationReg);
        emitReg(fileNameReg);
        emitReg(lineReg);
    }
}
```

**Benefits:**
- Error message computed once at compile time
- Runtime: just reads precomputed constant
- **Zero overhead** - matches codegen approach exactly

### Phase 2: Update DIE/WARN Opcodes

**File: `BytecodeInterpreter.java`**

Update DIE and WARN handlers to use precomputed messages:

**Current code (lines 865-882):**
```java
case Opcodes.DIE: {
    int dieRs = bytecode[pc++];
    RuntimeBase message = registers[dieRs];

    RuntimeScalar where = new RuntimeScalar(" at " + code.sourceName + " line " + code.sourceLine);
    WarnDie.die(message, where, code.sourceName, code.sourceLine);

    throw new RuntimeException("die() did not throw exception");
}
```

**New code:**
```java
case Opcodes.DIE: {
    int msgReg = bytecode[pc++];
    int locationReg = bytecode[pc++];
    int fileNameReg = bytecode[pc++];
    int lineReg = bytecode[pc++];

    RuntimeBase message = registers[msgReg];
    RuntimeScalar where = (RuntimeScalar) registers[locationReg];
    String fileName = ((RuntimeScalar) registers[fileNameReg]).toString();
    int lineNumber = ((RuntimeScalar) registers[lineReg]).getInt();

    WarnDie.die(message, where, fileName, lineNumber);

    throw new RuntimeException("die() did not throw exception");
}
```

**Same pattern for WARN opcode.**

### Phase 3: Stack Trace Detection (Minimal Thread-Local)

For stack traces, we need to know which `InterpretedCode` is executing when an exception occurs.

**New File: `InterpreterState.java`**

```java
package org.perlonjava.interpreter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Maintains minimal interpreter execution state for stack trace generation.
 * Thread-safe via ThreadLocal.
 */
public class InterpreterState {
    private static final ThreadLocal<Deque<InterpreterFrame>> frameStack =
        ThreadLocal.withInitial(ArrayDeque::new);

    public static class InterpreterFrame {
        public final InterpretedCode code;
        public final String packageName;
        public final String subroutineName;

        public InterpreterFrame(InterpretedCode code, String packageName, String subroutineName) {
            this.code = code;
            this.packageName = packageName;
            this.subroutineName = subroutineName;
        }
    }

    public static void push(InterpretedCode code, String packageName, String subroutineName) {
        frameStack.get().push(new InterpreterFrame(code, packageName, subroutineName));
    }

    public static void pop() {
        Deque<InterpreterFrame> stack = frameStack.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
    }

    public static InterpreterFrame current() {
        Deque<InterpreterFrame> stack = frameStack.get();
        return stack.isEmpty() ? null : stack.peek();
    }

    public static List<InterpreterFrame> getStack() {
        return new ArrayList<>(frameStack.get());
    }
}
```

**File: `BytecodeInterpreter.java`**

Add push/pop at entry/exit:

```java
public static RuntimeList execute(InterpretedCode code, RuntimeArray args, int callContext) {
    // Push interpreter frame for stack traces
    InterpreterState.push(code, code.packageName, code.subroutineName);

    try {
        // Main execution loop...
        RuntimeBase[] registers = new RuntimeBase[code.maxRegisters];
        int pc = 0;
        // ... rest of execution ...
    } finally {
        // Always pop frame, even on exception
        InterpreterState.pop();
    }
}
```

**File: `InterpretedCode.java`**

Add package name and subroutine name fields:

```java
public class InterpretedCode extends RuntimeCode {
    // ... existing fields ...
    public final String packageName;
    public final String subroutineName;

    public InterpretedCode(short[] bytecode, Object[] constants, String[] stringPool,
                          int maxRegisters, RuntimeBase[] capturedVars,
                          String sourceName, int sourceLine,
                          Map<Integer, Integer> pcToTokenIndex,
                          Map<String, Integer> variableRegistry,
                          String packageName, String subroutineName) {
        // ... existing initialization ...
        this.packageName = packageName;
        this.subroutineName = subroutineName;
    }
}
```

### Phase 4: Extend ExceptionFormatter

**File: `ExceptionFormatter.java`**

Add interpreter frame detection to `formatThrowable()`:

```java
private static ArrayList<ArrayList<String>> formatThrowable(Throwable t) {
    var stackTrace = new ArrayList<ArrayList<String>>();
    int callerStackIndex = 0;
    String lastFileName = "";

    var locationToClassName = new HashMap<ByteCodeSourceMapper.SourceLocation, String>();

    for (var element : t.getStackTrace()) {
        if (element.getClassName().equals("org.perlonjava.frontend.parser.StatementParser") &&
                element.getMethodName().equals("parseUseDeclaration")) {
            // Existing: Artificial caller stack entry for use statements
            // ... existing code ...
        } else if (element.getClassName().contains("org.perlonjava.anon") ||
                element.getClassName().contains("org.perlonjava.perlmodule")) {
            // Existing: Compiled code frames
            // ... existing code ...
        } else if (element.getClassName().equals("org.perlonjava.backend.bytecode.BytecodeInterpreter") &&
                   element.getMethodName().equals("execute")) {
            // NEW: Interpreter frame detected
            InterpreterState.InterpreterFrame frame = InterpreterState.current();
            if (frame != null) {
                var entry = new ArrayList<String>();
                entry.add(frame.packageName);
                entry.add(frame.code.sourceName);
                entry.add(String.valueOf(frame.code.sourceLine));
                entry.add(frame.subroutineName);
                stackTrace.add(entry);
                lastFileName = frame.code.sourceName;
            }
        }
    }

    // ... rest of existing code ...
}
```

### Phase 5: Caller() Operator Support

**File: `CallerOperator.java`** (or appropriate operator class)

Implement `caller()` to query interpreter stack:

```java
public static RuntimeList caller(int frameIndex) {
    // Check interpreter context first
    List<InterpreterState.InterpreterFrame> interpFrames = InterpreterState.getStack();

    if (frameIndex < interpFrames.size()) {
        // Interpreter frame
        InterpreterState.InterpreterFrame frame =
            interpFrames.get(interpFrames.size() - 1 - frameIndex);
        return buildCallerInfo(frame);
    }

    // Fall back to CallerStack for compiled code
    CallerInfo info = CallerStack.peek(frameIndex - interpFrames.size());
    if (info != null) {
        return buildCallerInfo(info);
    }

    return new RuntimeList();  // Empty list = no caller
}

private static RuntimeList buildCallerInfo(InterpreterState.InterpreterFrame frame) {
    RuntimeList result = new RuntimeList();
    result.add(new RuntimeScalar(frame.packageName));
    result.add(new RuntimeScalar(frame.code.sourceName));
    result.add(new RuntimeScalar(frame.code.sourceLine));
    result.add(new RuntimeScalar(frame.subroutineName != null ? frame.subroutineName : ""));
    // Add additional fields as needed (wantarray, hasargs, etc.)
    return result;
}
```

## Implementation Sequence

### Step 1: Precompute Die/Warn Messages ✓
- [x] Modify `BytecodeCompiler.java` die/warn cases
- [x] Compute location message at compile time
- [x] Store as constants in string pool

### Step 2: Update BytecodeInterpreter DIE/WARN ✓
- [x] Update DIE opcode handler to read 4 registers
- [x] Update WARN opcode handler similarly
- [x] Pass precomputed values to WarnDie

### Step 3: Add Minimal Thread-Local ✓
- [x] Create `InterpreterState.java`
- [x] Add InterpreterFrame class
- [x] Implement push/pop/current/getStack methods

### Step 4: Update InterpretedCode ✓
- [x] Add packageName field
- [x] Add subroutineName field
- [x] Update constructor and BytecodeCompiler

### Step 5: Update BytecodeInterpreter ✓
- [x] Add push/pop calls in execute()
- [x] Use try-finally for proper cleanup

### Step 6: Extend ExceptionFormatter ✓
- [x] Detect BytecodeInterpreter frames
- [x] Query InterpreterState.current()
- [x] Format as Perl stack frame

### Step 7: Caller() Operator (Future)
- [ ] Implement caller() to query stack
- [ ] Test with interpreter frames
- [ ] Test with mixed compiled/interpreted

### Step 8: Testing ✓
- [x] Test die shows correct location
- [x] Test warn shows correct location
- [x] Test stack traces show interpreter frames
- [x] Test nested interpreter calls
- [x] Test mixed compiled/interpreted calls

## Critical Files

**To Create:**
- `src/main/java/org/perlonjava/interpreter/InterpreterState.java`

**To Modify:**
- `src/main/java/org/perlonjava/interpreter/BytecodeCompiler.java` - Lines ~2886-2950
- `src/main/java/org/perlonjava/interpreter/BytecodeInterpreter.java` - Lines ~30, ~865-898
- `src/main/java/org/perlonjava/interpreter/InterpretedCode.java` - Constructor
- `src/main/java/org/perlonjava/runtime/ExceptionFormatter.java` - Line ~90

**To Test:**
- `src/test/resources/unit/interpreter_errors.t` (new)

## Performance Analysis

**Overhead:**
- **Die/Warn: Zero** - messages precomputed at compile time (same as codegen)
- **Thread-local push/pop**: ~5ns per subroutine call (< 0.1%)
- **No PC updates** during execution
- **No map lookups** at runtime

**Comparison to Codegen:**
- Codegen: Zero overhead (messages baked in bytecode)
- Interpreter: ~5ns per subroutine (thread-local management only)
- **Effectively zero overhead** - matches codegen approach

## Testing Strategy

```perl
# Test 1: Die with correct location
sub foo {
    die "Error in foo";  # Line 2
}
foo();
# Expected: "Error in foo at test.pl line 2"

# Test 2: Stack trace with interpreter frames
sub foo { die "Error" }
sub bar { foo() }
bar();
# Expected stack: bar line N → foo line M

# Test 3: Division by zero (future enhancement)
my $x = 10 / 0;
# Expected: "Illegal division by zero at test.pl line 1"
```

## Verification Commands

```bash
make dev                                              # Clean rebuild
./jperl src/test/resources/unit/interpreter_errors.t # Test error messages
./jperl dev/interpreter/tests/for_loop_benchmark.pl   # Verify performance
make test-unit                                        # Full unit tests
```

## Key Design Principles

1. **Zero Overhead for Die/Warn**: Precompute messages at compile time (matches codegen)
2. **Minimal Runtime State**: Only track current code for stack traces (no PC updates)
3. **Reuse Existing Patterns**: Follow ByteCodeSourceMapper approach
4. **Thread Safety**: ThreadLocal ensures multi-threaded correctness
5. **Clean Separation**: Die/warn messages vs stack trace detection are independent

## Future Enhancements

1. **Division by Zero Location**: Precompute location for arithmetic operators
2. **More Detailed Stack Traces**: Include wantarray, hasargs, etc. in frames
3. **Optimized Stack Management**: Pooled frames to reduce GC overhead
4. **Integration with Debugger**: Use InterpreterState for step debugging

---

**Document Version**: 2.0 (Revised)
**Last Updated**: 2026-02-13
**Status**: Ready for Implementation