# Caller Stack Fix Plan - Consolidated

## Status: Active Development

## Branch: `fix/caller-line-numbers`

## Executive Summary

PerlOnJava's `caller()` implementation has several issues that cause incorrect file, line, and package information. This document consolidates all related investigations and provides a clear roadmap to fix them.

**Current Test Status (croak-locations.t):**
- Tests 1-27: PASSING
- Test 28: FAILING - reports `LocationTestFile line 18` instead of expected `line 21`
- Test 29: PASSING

## Problem Overview

### The Core Issue

When Perl code runs through the **interpreter** (default for eval), call sites aren't recorded in the JVM stack trace. This causes Carp to blame the wrong location.

**Expected call stack for test 28:**
```
1. isa check sub (in Moo-generated accessor)
2. Moo internals
3. Moo::Role::apply_roles_to_object
4. LocationTestFile line 21  ← MISSING!
5. test28_exact.pl line 44 ($sub->())
```

**Actual call stack:**
```
1. isa check sub (in Moo-generated accessor)
2. Moo internals  
3. Moo::Role::apply_roles_to_object
4. test28_exact.pl line 44 ($sub->())  ← Frame 4 missing!
```

The frame for "LocationTestFile line 21" (the `apply_roles_to_object` call inside the anonymous sub) is missing because the anonymous sub runs via the interpreter, not as JIT-compiled bytecode.

### Root Cause

PerlOnJava has two execution paths with different stack tracking:

| Path | Execution | Stack Tracking | Call Site Recording |
|------|-----------|----------------|---------------------|
| JVM Bytecode | Compiled to bytecode | JVM stack trace | Automatic (line numbers in bytecode) |
| Interpreter | `BytecodeInterpreter.execute()` | `CallerStack` | **Must be manually tracked** |

For interpreted code, when a subroutine call is made, the call site location must be explicitly pushed onto `CallerStack`. Currently this isn't happening.

## Completed Work

### Phase A: `#line` Directive Filename Mapping (DONE)

**Problem:** `#line N "filename"` directives weren't affecting `caller()` output.

**Solution implemented in `ByteCodeSourceMapper.java`:**
1. Extended `LineInfo` record with `sourceFileNameId` to store `#line`-adjusted filename
2. `saveSourceLocation()` stores original filename for key, adjusted filename in LineInfo
3. `parseStackTraceElement()` returns the `#line`-adjusted filename
4. Added logic to inherit `#line`-adjusted filename for entries stored before directive was processed

**Result:** Error messages now show `LocationTestFile` instead of `(eval 1)` ✓

### Phase B: Package Context During Emit (DONE)

**Problem:** `saveSourceLocation()` was only called during parsing, before subroutine classes were created.

**Solution:** Added `saveSourceLocation()` call to `setDebugInfoLineNumber()` so source locations are saved during emit with correct package context.

**Files changed:** `ByteCodeSourceMapper.java`

## Remaining Work

### Phase 1: Investigate CallerStack for Interpreter (COMPLETED)

**Goal:** Understand how CallerStack should track call sites for interpreted code.

**Findings (2026-03-17):**

#### Current Architecture

| Component | Purpose | When Used |
|-----------|---------|-----------|
| `CallerStack` | Simple list of `CallerInfo(pkg, file, line)` | Compile-time (`use` statements) |
| `InterpreterState.frameStack` | Tracks interpreter frames | Runtime (function being called) |
| `InterpreterState.pcStack` | Tracks program counters | Runtime (current execution point) |

#### The Gap

`InterpreterState` tracks **which function is executing** but NOT **where the call was made from**.

When `BytecodeInterpreter.execute()` runs:
1. At entry: `InterpreterState.push(code, packageName, subroutineName)` - records the called function
2. At CALL_SUB/CALL_METHOD: calls `RuntimeCode.apply()` or `RuntimeCode.call()`
3. **MISSING**: No record of the call site location (which line/file made the call)

The JVM bytecode path doesn't have this problem because call sites are naturally in the JVM stack trace.

#### Key Code Locations

**BytecodeInterpreter.java lines 794-876 (CALL_SUB):**
```java
case Opcodes.CALL_SUB -> {
    // ... prepare codeRef and callArgs ...
    RuntimeList result = RuntimeCode.apply(codeRef, "", callArgs, context);
    // ← No call site info pushed before this!
}
```

**BytecodeInterpreter.java lines 878-918 (CALL_METHOD):**
```java
case Opcodes.CALL_METHOD -> {
    // ... prepare invocant, method, callArgs ...
    RuntimeList result = RuntimeCode.call(invocant, method, currentSub, callArgs, context);
    // ← No call site info pushed before this!
}
```

#### Solution: Use CallerStack for Call Sites

`CallerStack` is the right mechanism because:
1. It already exists for tracking call locations
2. `ExceptionFormatter` already checks it (lines 177-189)
3. Minimal changes required

### Phase 2: Track Call Sites in Interpreter (READY TO IMPLEMENT)

**Goal:** Push call site info to CallerStack before subroutine calls in interpreter.

**Specific changes to BytecodeInterpreter.java:**

1. **Before CALL_SUB (around line 829):**
```java
// Push call site info BEFORE the call
String callSiteFile = code.errorUtil.getFileName();  // #line-adjusted
int callSiteLine = code.errorUtil.getLineNumber(tokenIndexFromPC(pcHolder[0]));
String callSitePkg = InterpreterState.currentPackage.get().toString();
CallerStack.push(callSitePkg, callSiteFile, callSiteLine);

RuntimeList result = RuntimeCode.apply(codeRef, "", callArgs, context);

// Pop after call returns
CallerStack.pop();
```

2. **Before CALL_METHOD (around line 903):**
```java
// Push call site info BEFORE the call
String callSiteFile = code.errorUtil.getFileName();
int callSiteLine = code.errorUtil.getLineNumber(tokenIndexFromPC(pcHolder[0]));
String callSitePkg = InterpreterState.currentPackage.get().toString();
CallerStack.push(callSitePkg, callSiteFile, callSiteLine);

RuntimeList result = RuntimeCode.call(invocant, method, currentSub, callArgs, context);

// Pop after call returns (must be in finally block for exception safety)
CallerStack.pop();
```

3. **Helper method needed:**
```java
private static int tokenIndexFromPC(int pc, InterpretedCode code) {
    if (code.pcToTokenIndex == null || code.pcToTokenIndex.isEmpty()) {
        return pc;  // fallback
    }
    var entry = code.pcToTokenIndex.floorEntry(pc);
    return entry != null ? entry.getValue() : pc;
}
```

**Exception safety:** The `CallerStack.pop()` must be in a finally block to ensure it's called even if the subroutine throws.

### Phase 3: Merge Interpreter Frames into Stack Trace (MAY NOT BE NEEDED)

**Goal:** Ensure ExceptionFormatter properly interleaves CallerStack entries with JVM frames.

**Current behavior (ExceptionFormatter.java:177-189):**
```java
var callerInfo = CallerStack.peek(callerStackIndex);
if (callerInfo != null && callerInfo.filename() != null && !lastFileName.equals(callerInfo.filename())) {
    // Add CallerStack entry
}
```

**Required changes:**
1. Insert CallerStack frames at the correct position (between JVM frames)
2. Handle the case where interpreter frame should appear in middle of stack
3. Ensure no duplicate frames

### Phase 4: Testing & Verification

**Test commands:**
```bash
# Quick verification
./jperl /tmp/test28_exact.pl

# Full croak-locations.t
cd ~/.cpan/build/Moo-2.005005-2 && /path/to/jperl t/croak-locations.t

# Ensure no regressions
make
```

**Success criteria:**
- Test 28 reports `LocationTestFile line 21`
- All other croak-locations.t tests still pass
- No regressions in unit tests

## Technical Details

### CallerStack Architecture

`CallerStack` is a thread-local stack that tracks Perl call sites:

```java
public class CallerStack {
    private static final ThreadLocal<ArrayDeque<CallerInfo>> stack = ...;
    
    public static void push(CallerInfo info) { ... }
    public static CallerInfo pop() { ... }
    public static CallerInfo peek(int depth) { ... }
}
```

For JIT-compiled code, the JVM stack naturally contains call site info. For interpreted code, we must explicitly push/pop CallerInfo.

### Interpreter PC to Source Location

The interpreter tracks execution via Program Counter (PC). To get source location:

```java
// In BytecodeInterpreter
int pc = state.getPC();
int tokenIndex = frame.code().pcToTokenIndex.floorEntry(pc).getValue();

// Use ByteCodeSourceMapper for full location
StackTraceElement synthetic = new StackTraceElement(
    "interpreter", "execute", frame.code().errorUtil.getFileName(), tokenIndex);
SourceLocation loc = ByteCodeSourceMapper.parseStackTraceElement(synthetic, locationToClassName);
```

### Debug Environment Variables

| Variable | Effect |
|----------|--------|
| `DEBUG_CALLER=1` | Enable CallerInfo debug output in ByteCodeSourceMapper and ExceptionFormatter |
| `JPERL_EVAL_NO_INTERPRETER=1` | Force JVM compilation for eval (bypass interpreter) |
| `JPERL_SHOW_FALLBACK=1` | Show when interpreter fallback is triggered |

## Related Documents

- `dev/design/unified_caller_stack.md` - Previous analysis of two-path issue
- `dev/design/caller_package_context.md` - Package context investigation (Issues 1-6)
- `dev/design/interpreter.md` - Interpreter architecture

## Progress Tracking

### Completed
- [x] Phase A: `#line` directive filename mapping
- [x] Phase B: Package context during emit
- [x] Tests 1-27, 29 passing
- [x] Phase 1: Investigate CallerStack for interpreter
- [x] Phase 2: Track call sites in interpreter (implemented CallerStack push/pop)

### Current Status (2026-03-17)

**Implementation completed:**
1. Added `getCallSiteInfo()` helper method to `BytecodeInterpreter.java`
2. Modified `CALL_SUB` handler to push/pop CallerStack around calls
3. Modified `CALL_METHOD` handler to push/pop CallerStack around calls
4. Added debug output to `CallerStack.push()` for tracing

**Results:**
- Unit tests: ALL PASSING (no regressions)
- Simple caller() tests: WORKING CORRECTLY
- Moo croak-locations.t test 28: STILL FAILING (line 18 instead of line 21)

**Root cause of remaining issue:**
The CallerStack push/pop is happening correctly at call time, but `ExceptionFormatter` doesn't use CallerStack for interpreter frame tracking. Instead, it uses `InterpreterState.getPcStack()` which returns the current PC, not the call-site PC.

When a very simple isa sub (single croak statement) is called:
- CallerStack.push correctly records line 21 (apply_roles_to_object call site)
- But ExceptionFormatter gets line info from InterpreterState, which reflects the last instruction executed in the parent frame
- For some reason, with simple subs the line mapping returns line 18 instead of line 21

**Interesting observation:**
- Adding ANY code to the isa sub (even `my $x = 1;`) changes the line number reported
- With 2+ extra statements, the correct line (21) is reported
- This suggests the issue is in how simple single-statement subs affect PC-to-line mapping

### Remaining Work
- [ ] Phase 3: Modify ExceptionFormatter to use CallerStack for interpreter call-site tracking
- [ ] Phase 4: Investigate why simple subs have incorrect line mapping

### Not Started
- [ ] Thread-safety analysis (CallerStack uses static ArrayList, not ThreadLocal)

## Appendix: Test 28 Code Structure

```perl
# The test code (simplified):
my $sub = eval qq{ sub {
package $PACKAGE;
#line 1 LocationTestFile
BEGIN {
  eval qq{
    package ${PACKAGE}::Role;
    use Moo::Role;
    has attr => (
      is => 'ro',
      default => sub { 0 },
      isa => sub { croak "must be true" unless $_[0]; },
    );
  };
}
use Moo;
my $o = $PACKAGE->new;           # Line 18 - object created (no role yet)
package Elsewhere;
use Moo::Role ();
Moo::Role->apply_roles_to_object($o, "${PACKAGE}::Role");  # Line 21 - isa fails here
}};

$sub->();  # This call's frame IS in the stack
           # But the frame for line 21 INSIDE $sub is MISSING
```

The anonymous sub runs via interpreter. When it calls `apply_roles_to_object` at line 21, that call site isn't recorded in CallerStack, so Carp can't find it.
