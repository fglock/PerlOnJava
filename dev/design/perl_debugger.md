# Perl Debugger Implementation for PerlOnJava

## Overview

This document explores implementation approaches for `perl -d` style debugging in PerlOnJava, supporting traditional Perl debugger semantics including breakpoints, single-stepping, variable inspection, and the standard `perl5db.pl` debugger.

## Perl Debugger Architecture (from perldebguts)

When Perl is invoked with `-d`, the interpreter enables special debugging hooks:

### Core Hooks Enabled by `-d` (via `$^P` bits)

1. **`$ENV{PERL5DB}` injection** - Before the first line, Perl inserts:
   ```perl
   BEGIN { require 'perl5db.pl' }
   ```

2. **Source line storage** - `@{"_<$filename"}` holds source lines; values are magical (non-zero = breakable)

3. **Breakpoint hash** - `%{"_<$filename"}` stores breakpoints/actions keyed by line number

4. **Subroutine tracking** - `%DB::sub` maps `subname → "filename:startline-endline"`

5. **DB::DB() hook** - Called before each executable statement when `$DB::trace`, `$DB::single`, or `$DB::signal` is true

6. **DB::sub() hook** - All subroutine calls are routed through `&DB::sub` with `$DB::sub` identifying the target

7. **@DB::args** - When `caller()` is called from package DB, args are copied here

### Key Variables (`$^P` bits)

| Bit | Value | Meaning |
|-----|-------|---------|
| 0x01 | 1 | Debug subroutine enter/exit |
| 0x02 | 2 | Line-by-line debugging (call DB::DB) |
| 0x04 | 4 | Switch off optimizations |
| 0x08 | 8 | Preserve data for inspection |
| 0x10 | 16 | Keep sub definition line info |
| 0x20 | 32 | Start with single-step on |
| 0x80 | 128 | Report `goto &sub` |
| 0x100 | 256 | Informative eval "file" names |
| 0x200 | 512 | Informative anonymous sub names |
| 0x400 | 1024 | Save source lines |

---

## Primary Implementation: DEBUG Opcode Approach (Recommended)

**Concept**: When `-d` is used, force interpreter mode and inject DEBUG opcodes at statement boundaries. All debugger logic lives in the DEBUG opcode handler - no changes to the interpreter loop itself.

### Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         -d flag                                 │
│                            │                                    │
│                            ▼                                    │
│              ┌─────────────────────────────┐                   │
│              │  Set global debugMode flag  │                   │
│              │  Force --interpreter mode   │                   │
│              └─────────────────────────────┘                   │
│                            │                                    │
│                            ▼                                    │
│              ┌─────────────────────────────┐                   │
│              │         Parser              │                   │
│              │  - Store source lines       │                   │
│              │  - Mark breakable lines     │                   │
│              │  - Add debug info to AST    │                   │
│              └─────────────────────────────┘                   │
│                            │                                    │
│                            ▼                                    │
│              ┌─────────────────────────────┐                   │
│              │    BytecodeCompiler         │                   │
│              │  - Emit DEBUG opcode at     │                   │
│              │    each statement boundary  │                   │
│              └─────────────────────────────┘                   │
│                            │                                    │
│                            ▼                                    │
│              ┌─────────────────────────────┐                   │
│              │  BytecodeInterpreter        │                   │
│              │  (NO changes to loop)       │                   │
│              │                             │                   │
│              │  case DEBUG:                │                   │
│              │    DebugHooks.debug(...)    │                   │
│              └─────────────────────────────┘                   │
│                            │                                    │
│                            ▼                                    │
│              ┌─────────────────────────────┐                   │
│              │      DebugHooks.java        │                   │
│              │  - Check breakpoint table   │                   │
│              │  - Check $DB::single/trace  │                   │
│              │  - Call DB::DB() if needed  │                   │
│              │  - Handle step/next/cont    │                   │
│              └─────────────────────────────┘                   │
└─────────────────────────────────────────────────────────────────┘
```

### Key Design Decisions

1. **Global static flag**: `DebugState.debugMode` - checked only at startup to decide compilation mode

2. **Force interpreter mode**: All code runs through interpreter when debugging (simpler, single debug implementation)

3. **DEBUG opcode**: Emitted by `BytecodeCompiler` at statement boundaries when `debugMode` is true

4. **No interpreter loop changes**: DEBUG is just another opcode - all logic in its handler

5. **Breakpoint table**: `DebugState.breakpoints` - a `Set<String>` of `"file:line"` checked inside DEBUG handler

6. **Zero overhead when not debugging**: No DEBUG opcodes emitted, no checks in hot path

### Implementation

**Opcodes.java**:
```java
public class Opcodes {
    // ... existing opcodes ...
    
    public static final byte DEBUG = (byte) 0xFE;  // High value, rare collision
    // Format: DEBUG [fileIndex:2] [line:4]
}
```

**BytecodeCompiler.java** (AST → interpreter bytecode):
```java
public void visitStatement(StatementNode node) {
    if (DebugState.debugMode) {
        // Emit DEBUG opcode before each statement
        emit(Opcodes.DEBUG);
        emitShort(getFileIndex(node.getFilename()));
        emitInt(node.getLine());
    }
    
    // ... emit normal statement bytecode ...
}
```

**BytecodeInterpreter.java** (just add case, no loop changes):
```java
switch (opcode) {
    // ... existing cases ...
    
    case Opcodes.DEBUG:
        int fileIdx = readShort(bytecode, pc); pc += 2;
        int line = readInt(bytecode, pc); pc += 4;
        DebugHooks.debug(code, registers, fileIdx, line);
        break;
}
```

**DebugHooks.java** (all debugger logic here):
```java
public class DebugHooks {
    public static void debug(InterpretedCode code, RuntimeBase[] registers,
                            int fileIdx, int line) {
        String file = code.stringPool[fileIdx];
        String key = file + ":" + line;
        
        // Fast path: no debugging active
        if (!DebugState.single && !DebugState.trace && !DebugState.signal
            && !DebugState.breakpoints.contains(key)) {
            return;
        }
        
        // Set up DB:: variables
        GlobalVariable.getGlobalScalar("DB::filename").set(file);
        GlobalVariable.getGlobalScalar("DB::line").set(line);
        
        // Check breakpoint condition if any
        if (DebugState.breakpoints.contains(key)) {
            String condition = DebugState.breakpointConditions.get(key);
            if (condition != null && !evalCondition(condition)) {
                return;
            }
        }
        
        // Call DB::DB()
        callDbDb();
    }
    
    private static void callDbDb() {
        // Look up &DB::DB and call it
        RuntimeScalar dbdb = GlobalVariable.getGlobalCodeRef("DB::DB");
        if (dbdb.getDefinedBoolean()) {
            RuntimeCode.apply(dbdb, "", new RuntimeArray(), RuntimeContextType.VOID);
        }
    }
}
```

**DebugState.java**:
```java
public class DebugState {
    // Set at startup, controls compilation mode
    public static boolean debugMode = false;
    
    // Runtime debug flags (set by Perl code via $DB::single etc.)
    public static volatile boolean single = false;
    public static volatile boolean trace = false;
    public static volatile boolean signal = false;
    
    // Breakpoint table: "file:line" -> true
    public static final Set<String> breakpoints = ConcurrentHashMap.newKeySet();
    
    // Conditional breakpoints: "file:line" -> "condition_expr"
    public static final Map<String, String> breakpointConditions = new ConcurrentHashMap<>();
    
    // Source lines: filename -> String[]
    public static final Map<String, String[]> sourceLines = new ConcurrentHashMap<>();
    
    // Breakable lines: filename -> Set<Integer>
    public static final Map<String, Set<Integer>> breakableLines = new ConcurrentHashMap<>();
}
```

### Package Location

**Recommended**: `org.perlonjava.runtime.debugger`

```
src/main/java/org/perlonjava/runtime/debugger/
├── DebugState.java      # Flags, breakpoints, source storage
├── DebugHooks.java      # debug() method, DB::DB() calls
└── SourceLineArray.java # Magical @{"_<$filename"} implementation
```

### Advantages

1. **Clean separation**: All debug logic in `DebugHooks`, interpreter stays simple
2. **Zero overhead**: When not debugging, no DEBUG opcodes exist
3. **Easy breakpoints**: Just check `breakpoints.contains(key)` in DEBUG handler
4. **Reusable for JVM**: Later, JVM backend can call same `DebugHooks.debug()` method
5. **Single implementation**: Only interpreter needs debugging, one code path to maintain

### Future: JVM Backend Support

When adding debug support to JVM backend, emit calls to same `DebugHooks.debug()`:

```java
// In EmitStatement.java (JVM codegen)
if (DebugState.debugMode) {
    mv.visitLdcInsn(fileIndex);
    mv.visitLdcInsn(line);
    mv.visitVarInsn(ALOAD, codeRef);
    mv.visitVarInsn(ALOAD, registers);
    mv.visitMethodInsn(INVOKESTATIC, "org/perlonjava/runtime/debugger/DebugHooks",
                       "debug", "(Lorg/perlonjava/backend/bytecode/InterpretedCode;[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;II)V", false);
}
```

Same debugger, both backends.

---

## Alternative Approaches

### Alternative 1: Pure Perl Instrumentation

**Concept**: At compile time, inject Perl code that implements the debugging hooks without modifying the Java runtime.

**How it works**:

1. **Source line storage**: During parsing, populate `@{"_<$filename"}` with source lines

2. **Statement instrumentation**: After each statement, conditionally call `DB::DB()`:
   ```perl
   # Original:
   $x = foo();
   
   # Instrumented:
   $x = foo();
   DB::DB() if $DB::single || $DB::trace || $DB::signal;
   ```

3. **Subroutine wrapping**: Replace all sub calls with routing through `DB::sub`:
   ```perl
   # Original:
   foo($arg);
   
   # Instrumented:
   do { local $DB::sub = 'main::foo'; &DB::sub($arg) };
   ```

**Advantages**:
- No Java changes required
- Works with existing `perl5db.pl`

**Disadvantages**:
- Performance overhead (extra code per statement)
- Complex AST transformation

---

### Alternative 2: JDWP/Java Debug Integration

**Concept**: Leverage Java's Debug Wire Protocol for IDE integration.

**Current state**: Documented in `dev/design/jdwp_debugger.md` - requires line number tables in generated bytecode.

**Limitations**:
- Debugs at Java level, not Perl level
- Variable inspection shows Java objects, not Perl semantics
- No Perl debugger command language

---

### Alternative 3: Event-Driven Debug API

**Concept**: Create a clean Java API for debugging that can be consumed by multiple frontends.

```java
public interface DebugEventListener {
    void onStatementBegin(String file, int line, RuntimeScalar[] locals);
    void onSubroutineEnter(String name, RuntimeArray args);
    void onSubroutineExit(String name, RuntimeList result);
    void onBreakpoint(String file, int line);
    void onException(RuntimeScalar error);
}

public class DebugController {
    public void setBreakpoint(String file, int line, String condition);
    public void removeBreakpoint(String file, int line);
    public void stepInto();
    public void stepOver();
    public void stepOut();
    public void resume();
    
    public RuntimeScalar evaluate(String expr);
    public Map<String, RuntimeScalar> getLocals();
    public List<StackFrame> getCallStack();
}
```

**Frontends**:
- `perl5db.pl` compatibility layer
- Custom CLI debugger
- IDE plugins via Debug Adapter Protocol (DAP)
- Web-based debugger UI

---

## Implementation Plan

### Phase 1: Infrastructure

1. **Parse `-d` flag** in `ArgumentParser.java`
   - Set `DebugState.debugMode = true`
   - Force `--interpreter` mode
   - Set `$^P` appropriately

2. **Create `DebugState.java`** with debug flags and breakpoint tables

3. **Create `DebugHooks.java`** with minimal `debug()` method

4. **Add DEBUG opcode** to `Opcodes.java`

5. **Emit DEBUG opcode** in `BytecodeCompiler.java` at statement boundaries

6. **Handle DEBUG opcode** in `BytecodeInterpreter.java` (single case statement)

### Phase 2: Source Line Support

1. **Store source lines** during parsing into `DebugState.sourceLines`
2. **Track breakable lines** (statements vs. comments/blank lines)
3. **Implement `@{"_<$filename"}`** as magical array backed by `DebugState`
4. **Implement `%{"_<$filename"}`** for breakpoint storage

### Phase 3: Debug Variables

1. **Implement `$DB::single`, `$DB::trace`, `$DB::signal`** as special tied variables
2. **Implement `$DB::filename`, `$DB::line`** (set by DEBUG opcode)
3. **Implement `@DB::args`** support in `caller()`
4. **Implement `%DB::sub`** for subroutine location tracking

### Phase 4: Minimal Debugger Testing

1. **Test with minimal debugger**:
   ```perl
   sub DB::DB {
       print "At $DB::filename:$DB::line\n";
       my $cmd = <STDIN>;
       $DB::single = 1 if $cmd =~ /^s/;  # step
   }
   ```

2. **Test breakpoints**: Setting via `%{"_<$filename"}`

3. **Test stepping**: `$DB::single` control flow

### Phase 5: perl5db.pl Compatibility

1. **Inject `BEGIN { require 'perl5db.pl' }`** when `-d` is used
2. **Implement `DB::sub()` routing** for subroutine tracing
3. **Test with actual perl5db.pl**
4. **Fix any missing features**
5. **Document differences/limitations**

### Phase 6: JVM Backend Support (Future)

1. **Emit `DebugHooks.debug()` calls** in `EmitStatement.java`
2. **Share same `DebugState` and `DebugHooks`** classes
3. **Allow mixed interpreted/compiled debugging**

---

## Key Implementation Details

### 1. Debug State Storage

Global Java statics in `DebugState.java`:
- `debugMode` - set once at startup, controls opcode emission
- `single`, `trace`, `signal` - volatile, modified by Perl code at runtime
- `breakpoints` - `ConcurrentHashMap.newKeySet()` for thread-safety

### 2. Breakpoint Efficiency

Inside DEBUG opcode handler:
```java
// Fast path: single check for common case
if (!DebugState.single && !DebugState.trace && !DebugState.signal
    && !DebugState.breakpoints.contains(key)) {
    return;  // No-op, minimal overhead
}
```

When debugging is active but no breakpoint at this line, still very fast (hash lookup).

### 3. Magical Source Arrays (`@{"_<$filename"}`)

Implement as special tied array:
- String value: source line text
- Numeric context: 0 for non-breakable, non-zero (line number or address) for breakable
- Populated by parser during lexing

```java
public class SourceLineArray extends RuntimeArray {
    private String filename;
    
    @Override
    public RuntimeScalar get(int index) {
        String text = DebugState.sourceLines.get(filename)[index];
        boolean breakable = DebugState.breakableLines.get(filename).contains(index);
        return new MagicalSourceLine(text, breakable ? index : 0);
    }
}
```

### 4. Statement Boundary Detection

In `BytecodeCompiler`, emit DEBUG opcode when visiting:
- `StatementNode` (most statements)
- `BlockNode` (entering blocks)
- NOT inside expressions (only at statement level)

This matches Perl's behavior where breakpoints are only valid on statement-starting lines.

---

## Testing Strategy

1. **Unit tests** for debug state management
2. **Integration tests** with minimal DB::DB
3. **Compatibility tests** with perl5db.pl commands:
   - `n` (next), `s` (step), `c` (continue)
   - `b` (breakpoint), `B` (delete breakpoint)
   - `p` (print), `x` (dump)
   - `l` (list), `v` (view)
   - `T` (stack trace)
4. **Regression tests** ensuring debug mode doesn't break normal execution

---

## Open Questions

1. **Eval debugging**: `eval "string"` creates dynamic source - need to store in `@{"_<(eval N)"}` and emit DEBUG opcodes. The interpreter already handles eval, so this should work naturally.

2. **DB::sub routing**: Should all subroutine calls go through `DB::sub()` when debugging? This is needed for subroutine enter/exit tracing but adds overhead. Could be a separate `$^P` bit.

3. **Profiling integration**: The DEBUG opcode infrastructure could support profiling (like Devel::NYTProf) by tracking time between DEBUG calls. Consider adding optional timing hooks.

4. **Remote debugging**: perl5db.pl's `RemotePort` option - may need socket I/O support in the debugger. Lower priority.

5. **Step over/out**: Implementing `n` (next) and `r` (return) requires tracking call depth. Add `DebugState.stepOverDepth` to skip DEBUG calls until returning to target depth.

---

## Progress Tracking

### Current Status: Phase 2 mostly complete

### Completed Phases

- [x] Phase 1: Infrastructure (complete)
  - DEBUG opcode (376) in `Opcodes.java`
  - `-d` flag in `ArgumentParser.java` sets `debugMode=true`, forces interpreter
  - `BytecodeCompiler` emits DEBUG at statement boundaries when `debugMode=true`
  - `BytecodeInterpreter` handles DEBUG opcode, calls `DebugHooks.debug()`
  - `DebugState.java` - global debug flags, breakpoints, source storage
  - `DebugHooks.java` - command loop with n/s/c/q/l/b/B/L/h commands
  - Source line extraction from tokens (`ErrorMessageUtil.extractSourceLines()`)
  - `l` command shows source with `==>` current line marker
  - Compile-time statements (`use`/`no`) correctly skipped via `compileTimeOnly` annotation
  - Infrastructure nodes in BEGIN blocks skipped via `skipDebug` annotation

- [x] Phase 2: Source Line Support (partially complete, 2024-03-10)
  - [x] Store source lines during parsing
  - [x] Skip compile-time statements (use/no)
  - [x] Display subroutine names when stepping into code (e.g., `main::foo(/file:line)`)
    - Added `subNameStack` in `DebugState` for tracking current subroutine
    - Modified `RuntimeCode.apply()` to track subroutine entry/exit (zero overhead when not debugging)
    - Uses `NameNormalizer.normalizeVariableName()` for consistent name formatting
  - [ ] Track breakable lines (statements vs comments)
  - [ ] Implement `@{"_<$filename"}` magical array
  - [ ] Implement `%{"_<$filename"}` for breakpoint storage

### Working Commands
| Command | Description |
|---------|-------------|
| `n` | Next (step over) |
| `s` | Step into |
| `r` | Return (step out) |
| `c [line]` | Continue (optionally to line) |
| `q` | Quit |
| `l [range]` | List source (`l 10-20` or `l 15`) |
| `.` | Show current line |
| `b [line]` | Set breakpoint |
| `B [line]` | Delete breakpoint (`B *` = all) |
| `L` | List breakpoints |
| `T` | Stack trace |
| `p expr` | Print expression |
| `x expr` | Dump expression |
| `h` | Help |

### Next Steps

1. Phase 2 completion:
   - Implement `@{"_<$filename"}` magical array
   - Track breakable lines

2. Phase 3: Debug Variables
   - `$DB::single`, `$DB::trace`, `$DB::signal` as tied variables (partially done - synced from Java)
   - `@DB::args` support in `caller()` (done)
   - `%DB::sub` for subroutine location tracking (done)

3. Phase 5: perl5db.pl Compatibility
   - Test with actual perl5db.pl

### Known Issues

1. **Package scoping after block-local packages**: After `{ package Foo; ... }` block ends, the debugger display may show `Foo::` instead of `main::` for subsequent statements. This is a package scoping issue in the interpreter, not debugger-specific.

---

## References

- `perldoc perldebug` - User documentation
- `perldoc perldebguts` - Implementation details  
- `perldoc perldebtut` - Tutorial
- `perl5/lib/perl5db.pl` - Reference implementation (~10,000 lines)
- `dev/design/jdwp_debugger.md` - Existing JDWP notes
- `dev/design/interpreter.md` - Interpreter architecture
