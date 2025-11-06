# Tagged Return Value Control Flow Design

## Quick Reference

**Development Branch:** `nonlocal-goto-wip`

**Before committing, ALWAYS run regression tests:**
```bash
cd /Users/fglock/projects/PerlOnJava
git checkout nonlocal-goto-wip  # Make sure you're on the right branch
./gradlew build -x test  # Rebuild jar
timeout 900 dev/tools/perl_test_runner.pl \
    perl5_t/t/uni/variables.t \
    perl5_t/t/op/hash.t \
    perl5_t/t/op/for.t \
    perl5_t/t/cmd/mod.t \
    perl5_t/t/op/list.t \
    perl5_t/t/perf/benchmarks.t \
    src/test/resources/unit/nonlocal_goto.t
```

**Expected baseline:** ≥99.8% pass rate, no VerifyErrors, no "Method too large" errors.

---

## Overview

This document describes the design for implementing Perl's non-local control flow (`last`, `next`, `redo`, `goto`) using **tagged return values** instead of exceptions. This approach avoids JVM bytecode verification errors while maintaining correct Perl semantics.

## Perl Semantics (from perldoc)

### `last LABEL` / `last EXPR` / `last`
- Exits the loop immediately (like C's `break`)
- If LABEL is omitted, refers to innermost enclosing loop
- `last EXPR` allows computed labels (runtime evaluation)
- **Cannot return a value** from blocks like `eval {}`, `sub {}`, or `do {}`
- Should NOT be used in `grep` or `map`
- Can be used to exit a bare block (semantically a one-iteration loop)
- Works across subroutine boundaries within dynamic scope

### `next LABEL` / `next EXPR` / `next`
- Starts next iteration of loop (like C's `continue`)
- If LABEL is omitted, refers to innermost enclosing loop
- `next EXPR` allows computed labels (runtime evaluation)
- **Cannot return a value** from blocks like `eval {}`, `sub {}`, or `do {}`
- Should NOT be used in `grep` or `map`
- Can exit a bare block early

### `redo LABEL` / `redo EXPR` / `redo`
- Restarts loop block without re-evaluating conditional
- Continue block is NOT executed
- If LABEL is omitted, refers to innermost enclosing loop
- `redo EXPR` allows computed labels (runtime evaluation)
- **Cannot return a value** from blocks like `eval {}`, `sub {}`, or `do {}`
- Should NOT be used in `grep` or `map`
- Can turn a bare block into a looping construct

### `goto LABEL` / `goto EXPR` / `goto &NAME`
- **`goto LABEL`**: Finds statement labeled LABEL and resumes there
  - Works within dynamic scope, including out of subroutines
  - **Cannot** jump out of `sort` blocks
  - **Deprecated** to jump INTO constructs (will be fatal in Perl 5.42)
  - Cannot jump into constructs requiring initialization (`foreach`, `given`, subroutines)
- **`goto EXPR`**: Evaluates EXPR to get label name or code reference
  - If code reference, behaves like `goto &NAME`
  - If label name, scope resolved dynamically (computed gotos)
  - Allows: `goto ("FOO", "BAR", "GLARCH")[$i];`
- **`goto &NAME`**: Tail call optimization (NOT a goto)
  - Exits current subroutine and immediately calls named subroutine
  - Uses current @_ (not relevant to our label-based goto implementation)

### Key Precedence Rule
All these operators have **assignment precedence** and are exempt from "looks-like-a-function" rule:
```perl
last ("foo")."bar"   # "bar" is part of the argument to last
```

## Problem Statement

### Why Exceptions Don't Work

The current exception-based approach has fundamental incompatibilities with JVM bytecode verification:

1. **Stack Inconsistency**: Perl allows loops in expression contexts:
   ```perl
   $result = "" . do { for (@list) { ... } }
   ```
   Parent expression values remain on the operand stack when entering the loop's try-catch block. When control flow exceptions are thrown, they clear the stack, creating inconsistent stack states at merge points.

2. **Method Too Large**: Adding try-catch blocks and local variables to clean the stack bloats bytecode, pushing methods over JVM's 64KB limit.

3. **Verification Errors**: Multiple attempts to fix stack inconsistencies resulted in:
   - `Inconsistent stackmap frames at branch target`
   - `Bad type on operand stack`
   - `Bad local variable type`
   - `ArrayIndexOutOfBoundsException` in ASM frame computation

### Failed Approaches

1. **Manual stack cleanup at loop entry**: ASM frame computation mismatch
2. **Save/restore parent stack values**: Compilation errors, type mismatches
3. **Local variables in operators**: `Bad type on operand stack` errors
4. **Disable exception handlers for unlabeled loops**: Breaks `Test::More::skip()` and SKIP blocks
5. **Extract loops to subroutines**: Doesn't solve expression context problem
6. **Two-phase control flow** (local goto + exception): New VerifyErrors

## Solution: Tagged Return Values

### Core Concept

Instead of throwing exceptions, mark the `RuntimeList` return value with control flow information.

**Design Options:**

#### Option 1: Direct Fields in RuntimeList (Simple)
```java
class RuntimeList extends RuntimeBaseEntity {
    // Existing fields
    private List<RuntimeScalar> elements;
    
    // New fields for control flow
    private boolean isControlFlow = false;
    private String controlFlowType;  // "last", "next", "redo", "goto"
    private String controlFlowLabel; // null for unlabeled, or label name
    
    public boolean isNonLocalGoto() {
        return isControlFlow;
    }
    
    public void markAsControlFlow(String type, String label) {
        this.isControlFlow = true;
        this.controlFlowType = type;
        this.controlFlowLabel = label;
    }
    
    public String getControlFlowType() { return controlFlowType; }
    public String getControlFlowLabel() { return controlFlowLabel; }
    
    public void clearControlFlow() {
        this.isControlFlow = false;
        this.controlFlowType = null;
        this.controlFlowLabel = null;
    }
}
```

**Pros:**
- Simple, direct access
- No extra object allocation in common case (just null references)
- Fast check (single boolean field)

**Cons:**
- Every RuntimeList has 3 extra fields (16 bytes overhead)
- Fields unused 99.99% of the time (control flow is rare)

---

#### Option 2: Separate Marker Object (Memory Optimized)
```java
class RuntimeList extends RuntimeBaseEntity {
    // Existing fields
    private List<RuntimeScalar> elements;
    
    // Control flow marker (usually null)
    private ControlFlowMarker controlFlowMarker = null;
    
    public boolean isNonLocalGoto() {
        return controlFlowMarker != null;
    }
    
    public void markAsControlFlow(String type, String label) {
        controlFlowMarker = new ControlFlowMarker(type, label);
    }
    
    public String getControlFlowType() {
        return controlFlowMarker != null ? controlFlowMarker.type : null;
    }
    
    public String getControlFlowLabel() {
        return controlFlowMarker != null ? controlFlowMarker.label : null;
    }
    
    public void clearControlFlow() {
        controlFlowMarker = null;
    }
}

class ControlFlowMarker {
    final String type;   // "last", "next", "redo", "goto"
    final String label;  // null for unlabeled
    
    ControlFlowMarker(String type, String label) {
        this.type = type;
        this.label = label;
    }
}
```

**Pros:**
- Normal RuntimeList: only 8 bytes overhead (one null reference)
- Marked RuntimeList: 8 bytes reference + 24 bytes marker object = 32 bytes total
- Memory efficient (control flow is rare, so most lists don't allocate marker)

**Cons:**
- Extra object allocation when control flow happens (negligible - control flow is rare)
- Slightly more complex code

---

#### Option 3: Type Enum (Type-Safe)
```java
class RuntimeList extends RuntimeBaseEntity {
    // Existing fields
    private List<RuntimeScalar> elements;
    
    // Control flow marker (usually null)
    private ControlFlowMarker controlFlowMarker = null;
    
    public boolean isNonLocalGoto() {
        return controlFlowMarker != null;
    }
    
    public void markAsControlFlow(ControlFlowType type, String label) {
        controlFlowMarker = new ControlFlowMarker(type, label);
    }
    
    public ControlFlowType getControlFlowType() {
        return controlFlowMarker != null ? controlFlowMarker.type : null;
    }
    
    public String getControlFlowLabel() {
        return controlFlowMarker != null ? controlFlowMarker.label : null;
    }
    
    public void clearControlFlow() {
        controlFlowMarker = null;
    }
}

enum ControlFlowType {
    LAST, NEXT, REDO, GOTO
}

class ControlFlowMarker {
    final ControlFlowType type;
    final String label;  // null for unlabeled
    
    ControlFlowMarker(ControlFlowType type, String label) {
        this.type = type;
        this.label = label;
    }
}
```

**Pros:**
- Type-safe (no typo bugs with "last" vs "LAST")
- Enables switch statements in Java (cleaner than string comparisons)
- JVM can optimize enum switches better than string switches
- Same memory efficiency as Option 2

**Cons:**
- Bytecode generation needs to map strings to enum values
- Slightly more complex

---

### Recommended: Option 3 (Type Enum)

**Rationale:**
1. **Type safety** prevents bugs (can't accidentally use "lst" instead of "last")
2. **Performance** - JVM optimizes enum switches (tableswitch instead of lookupswitch with string hashing)
3. **Memory efficient** - only allocates marker object when control flow happens (rare)
4. **Cleaner bytecode** - emit integer constants instead of string constants
5. **Better debugging** - enum values are easier to see in stack traces

**Label Storage Strategy:**
- **Labels are stored as Strings** (not symbolic references)
- No need to optimize for symbolic labels - control flow is rare
- String comparison at runtime is fast enough (control flow is the slow path anyway)
- Simplifies implementation - no need to track label symbols or resolve them
- Works naturally with computed labels (`last EXPR`)

**Bytecode generation example:**
```java
// In EmitControlFlow.java, for `last OUTER`:

// Create marker object
mv.visitTypeInsn(NEW, "org/perlonjava/runtime/ControlFlowMarker");
mv.visitInsn(DUP);

// Load enum value (LAST)
mv.visitFieldInsn(GETSTATIC, 
    "org/perlonjava/runtime/ControlFlowType", 
    "LAST", 
    "Lorg/perlonjava/runtime/ControlFlowType;");

// Load label
mv.visitLdcInsn("OUTER");

// Call constructor
mv.visitMethodInsn(INVOKESPECIAL, 
    "org/perlonjava/runtime/ControlFlowMarker", 
    "<init>", 
    "(Lorg/perlonjava/runtime/ControlFlowType;Ljava/lang/String;)V", 
    false);

// Mark the RuntimeList
mv.visitMethodInsn(INVOKEVIRTUAL,
    "org/perlonjava/runtime/RuntimeList",
    "markAsControlFlow",
    "(Lorg/perlonjava/runtime/ControlFlowMarker;)V",
    false);
```

**Handler bytecode (using enum for switch):**
```java
// Get control flow type
ALOAD marked_list
INVOKEVIRTUAL getControlFlowType()Lorg/perlonjava/runtime/ControlFlowType;
INVOKEVIRTUAL ordinal()I  // Convert enum to int

// Tableswitch (very fast)
TABLESWITCH
    0: handle_last    // LAST.ordinal() = 0
    1: handle_next    // NEXT.ordinal() = 1
    2: handle_redo    // REDO.ordinal() = 2
    3: handle_goto    // GOTO.ordinal() = 3
    default: error
```

This is faster than `LOOKUPSWITCH` with strings because it's a direct jump table without hash lookups!

### Bytecode Generation

#### 1. Control Flow Operators (EmitControlFlow.java)

**LOCAL (known at compile time):**
```java
// For local last/next/redo/goto (label exists in current scope)
// Use existing fast GOTO implementation - NO CHANGE
stackLevelManager.emitPopInstructions(mv, targetStackLevel);
mv.visitJumpInsn(Opcodes.GOTO, targetLabel);
```

**NON-LOCAL (unknown at compile time):**
```java
// Create marked RuntimeList
mv.visitTypeInsn(NEW, "org/perlonjava/runtime/RuntimeList");
mv.visitInsn(DUP);
mv.visitMethodInsn(INVOKESPECIAL, "RuntimeList", "<init>", "()V");
mv.visitInsn(DUP);

// Mark it with control flow info
mv.visitLdcInsn(type);        // "last", "next", "redo", "goto"
if (label != null) {
    mv.visitLdcInsn(label);   // Label name
} else {
    mv.visitInsn(ACONST_NULL); // Unlabeled
}
mv.visitMethodInsn(INVOKEVIRTUAL, "RuntimeList", "markAsControlFlow", 
    "(Ljava/lang/String;Ljava/lang/String;)V");

// Clean stack and return the marked list (same as return)
stackLevelManager.emitPopInstructions(mv, 0);
mv.visitJumpInsn(GOTO, returnLabel);
```

#### 2. Subroutine Call Sites (EmitSubroutine.java, EmitVariable.java, EmitEval.java)

**After every `apply()` call inside a loop:**
```java
// Call subroutine
code.apply(args)                           // Returns RuntimeList
                                          // Stack: [parent...] [RuntimeList]

// Check if it's marked for control flow
DUP                                        // Stack: [parent...] [RuntimeList] [RuntimeList]
INVOKEVIRTUAL isNonLocalGoto()Z           // Stack: [parent...] [RuntimeList] [boolean]
IFNE need_cleanup                         // Stack: [parent...] [RuntimeList]

// Normal path: discard duplicate
POP                                        // Stack: [parent...]
// Continue normal execution
...

need_cleanup:
    // Stack: [parent...] [RuntimeList]
    ASTORE temp_marked_list               // Stack: [parent...]
    
    // Clean stack to level 0 (same as return does)
    stackLevelManager.emitPopInstructions(mv, 0);  // Stack: []
    
    // Load marked RuntimeList
    ALOAD temp_marked_list                // Stack: [RuntimeList]
    
    // Jump to loop's control flow handler
    GOTO loopControlFlowHandler
```

**Calls OUTSIDE loops:**
Just check at `returnLabel` (see below).

#### 3. Loop Control Flow Handlers (EmitForeach.java, EmitStatement.java)

**Generated once per loop:**
```java
loopControlFlowHandler:
    // Stack: [RuntimeList] - guaranteed clean
    
    // Extract control flow info
    DUP
    INVOKEVIRTUAL getControlFlowType()Ljava/lang/String;
    ASTORE temp_type
    
    DUP
    INVOKEVIRTUAL getControlFlowLabel()Ljava/lang/String;
    ASTORE temp_label
    
    // Switch on type
    ALOAD temp_type
    LOOKUPSWITCH
        "last" -> handle_last
        "next" -> handle_next
        "redo" -> handle_redo
        "goto" -> handle_goto
        default -> propagate_to_caller

handle_last:
    ALOAD temp_label
    // Check if unlabeled or matches this loop
    IFNULL do_last                        // Unlabeled - handle it
    LDC "THIS_LOOP_LABEL"
    INVOKEVIRTUAL String.equals()Z
    IFNE do_last
    GOTO propagate_to_caller              // Not for us
do_last:
    POP                                   // Remove marked RuntimeList
    GOTO loop_end                         // Exit loop

handle_next:
    ALOAD temp_label
    IFNULL do_next
    LDC "THIS_LOOP_LABEL"
    INVOKEVIRTUAL String.equals()Z
    IFNE do_next
    GOTO propagate_to_caller
do_next:
    POP
    GOTO loop_continue                    // Continue point (increment, test)

handle_redo:
    ALOAD temp_label
    IFNULL do_redo
    LDC "THIS_LOOP_LABEL"
    INVOKEVIRTUAL String.equals()Z
    IFNE do_redo
    GOTO propagate_to_caller
do_redo:
    POP
    GOTO loop_body_start                  // Redo (skip increment)

handle_goto:
    // Check if label exists in current scope (goto labels, not just loop labels)
    ALOAD temp_label
    LDC "SOME_GOTO_LABEL"
    INVOKEVIRTUAL String.equals()Z
    IFNE do_goto
    // ... check other goto labels ...
    GOTO propagate_to_caller
do_goto:
    POP
    GOTO SOME_GOTO_LABEL

propagate_to_caller:
    // Marked RuntimeList still on stack
    // Two cases:
    // 1. If this loop is nested inside another loop:
    //    Jump directly to outer loop's handler (handler chaining)
    // 2. If this is the outermost loop in the subroutine:
    //    Return to caller via returnLabel
    
    // For nested loops (INNER inside OUTER):
    GOTO outerLoopControlFlowHandler  // Chain to outer handler
    
    // For outermost loop:
    GOTO returnLabel  // Propagate to caller
```

#### 4. Return Label (EmitterMethodCreator.java)

**No changes needed!** The existing return label handles both normal and marked `RuntimeList`:

```java
returnLabel:
    // RuntimeList is on stack (marked or unmarked)
    // Always do the same thing:
    mv.visitMethodInsn(INVOKEVIRTUAL, "RuntimeBase", "getList", 
        "()Lorg/perlonjava/runtime/RuntimeList;");  // NO-OP if already RuntimeList
    Local.localTeardown(localRecord, mv);
    ARETURN  // Return whatever is on stack (marked list propagates naturally)
```

### Nested Loops

Nested loops chain handlers **directly** without returning through `returnLabel`:

```perl
OUTER: for (@outer) {
    sub_a();  # Checks at call site, jumps to OUTER handler
    
    INNER: for (@inner) {
        sub_b();  # Checks at call site, jumps to INNER handler
    }
    
    sub_c();  # Checks at call site, jumps to OUTER handler
}
```

**Handler Chaining Example:**

If `sub_b()` does `last OUTER`:

1. **In `sub_b()`**: Creates marked RuntimeList `{type:"last", label:"OUTER"}` and returns it
2. **Call site in INNER loop**: Detects marked return, cleans stack, jumps to `innerLoopControlFlowHandler`
3. **INNER's handler**: Checks label "OUTER" - no match in INNER's switch
4. **INNER's handler default**: Marked RuntimeList still on stack, directly **chains to OUTER's handler**: `GOTO outerLoopControlFlowHandler`
5. **OUTER's handler**: Checks label "OUTER" - match!
6. **OUTER's handler**: Pops marked RuntimeList, jumps to `OUTER_loop_end`

**Key insight**: The `default` case in inner loop handler doesn't return to caller - it **jumps directly to the outer loop's handler**. This creates a chain of handlers that efficiently propagates control flow without returning through multiple call frames.

**Implementation Detail:**

```java
// INNER loop handler
innerLoopControlFlowHandler:
    LOOKUPSWITCH
        "last" -> check_inner_last
        "next" -> check_inner_next
        "redo" -> check_inner_redo
        "goto" -> check_inner_goto
        default -> propagate_to_outer  // <-- Key: chain to outer handler

propagate_to_outer:
    // RuntimeList still on stack - no need to return via returnLabel
    // Jump directly to outer loop's handler
    GOTO outerLoopControlFlowHandler  // <-- Direct chaining!

// OUTER loop handler
outerLoopControlFlowHandler:
    LOOKUPSWITCH
        "last" -> check_outer_last
        "next" -> check_outer_next
        "redo" -> check_outer_redo
        "goto" -> check_outer_goto
        default -> propagate_to_caller  // <-- Outermost: return via returnLabel

propagate_to_caller:
    // No outer handler to chain to - return to caller
    GOTO returnLabel
```

**Stack remains clean** throughout the entire chain - each handler receives exactly one `RuntimeList` on the stack, checks it, and either handles it locally or passes it to the next handler.

### Computed Labels (EXPR forms)

For `last EXPR`, `next EXPR`, `redo EXPR`, `goto EXPR`:

```java
// Evaluate expression to get label name
node.operand.accept(visitor.with(RuntimeContextType.SCALAR));
mv.visitMethodInsn(INVOKEVIRTUAL, "RuntimeScalar", "toString", "()Ljava/lang/String;");
ASTORE computed_label

// Create and mark RuntimeList (same as above, but with computed label)
...
ALOAD computed_label
mv.visitMethodInsn(INVOKEVIRTUAL, "RuntimeList", "markAsControlFlow", 
    "(Ljava/lang/String;Ljava/lang/String;)V");
```

### Bare Blocks

Bare blocks (`{ ... }`) with labels are semantically one-iteration loops:

```perl
BLOCK: {
    do_something();
    last BLOCK if $condition;  # Exit block early
    do_more();
}
```

Implementation: Treat labeled bare blocks as `for (1) { ... }` internally.

## Special Cases & Errors Encountered

### 1. Loops in Expression Context
```perl
$result = "" . do { for (@list) { sub(); } }
```
**Problem**: Parent expression values on stack before loop.
**Solution**: Tagged return values work with any stack depth. Call site cleanup uses `stackLevelManager.emitPopInstructions(mv, 0)`.

### 2. Multiple Subroutine Calls
```perl
is(do {foreach...}, "", "test");  # Multiple arguments on stack
```
**Problem**: Function arguments on stack before evaluating loop argument.
**Solution**: Each call site independently checks and cleans stack.

### 3. Test::More::skip()
```perl
SKIP: {
    skip "reason", $count if $condition;
    # tests here
}
```
**Problem**: `skip()` uses `last SKIP` to exit the block.
**Solution**: Treat SKIP block as a loop, or implement as a one-shot loop.

### 4. Nested Loops with Same-Name Subroutines
```perl
OUTER: for (@list) {
    sub { last OUTER; }->();  # Anonymous sub
    INNER: for (@list2) {
        sub { last OUTER; }->();  # Different closure, same label
    }
}
```
**Solution**: Label resolution is dynamic (runtime), so both work correctly.

### 5. goto into Loop (Deprecated)
```perl
goto INSIDE;
for (@list) {
    INSIDE: ...;
}
```
**Problem**: Perl deprecates this (fatal in 5.42).
**Solution**: Emit warning, then either support it or throw error.

### 6. Loops in grep/map
```perl
@result = map { last if $condition; $_ } @list;
```
**Problem**: `last` should NOT be used in grep/map (undefined behavior).
**Solution**: Document limitation or detect and warn.

### 7. goto Expressions with Array Index
```perl
goto ("FOO", "BAR", "GLARCH")[$i];
```
**Problem**: Complex expression evaluation.
**Solution**: Evaluate expression to get label string, then mark RuntimeList with computed label.

### 8. Return from do Block (Transparent)
```perl
sub foo {
    my $x = do { return 42; };  # Returns from foo, not from do
    print "never reached\n";
}
```
**Problem**: `return` inside `do` should return from enclosing sub, not the block.
**Solution**: `return` is NOT a control flow marker - it already uses returnLabel. No conflict.

### 9. Stack Consistency After Control Flow
**Problem**: JVM verifier requires consistent stack states at all branch targets.
**Solution**: Always clean stack to level 0 before jumping to handler. Handler receives clean stack with only marked RuntimeList.

### 10. redo in Nested Loops
```perl
OUTER: for (@outer) {
    INNER: for (@inner) {
        redo OUTER;  # Redo outer loop
    }
}
```
**Problem**: `redo OUTER` needs to jump to OUTER's body start, not INNER's.
**Solution**: Handler checks label, propagates if no match.

## Implementation Checklist

### Phase 1: Runtime Classes (ControlFlowMarker, ControlFlowType, RuntimeList)
- [ ] Create `ControlFlowType` enum with values: `LAST`, `NEXT`, `REDO`, `GOTO`
- [ ] Create `ControlFlowMarker` class with fields: `ControlFlowType type`, `String label`
- [ ] Add `controlFlowMarker` field to `RuntimeList` (initialized to null)
- [ ] Add `isNonLocalGoto()` method to `RuntimeList` (returns `controlFlowMarker != null`)
- [ ] Add `markAsControlFlow(ControlFlowMarker marker)` method to `RuntimeList`
- [ ] Add `getControlFlowType()` method returning `ControlFlowType` (or null)
- [ ] Add `getControlFlowLabel()` method returning `String` (or null)
- [ ] Add `clearControlFlow()` method (sets `controlFlowMarker = null`)
- [ ] Add convenience method: `markAsControlFlow(ControlFlowType type, String label)`

### Phase 2: Control Flow Emission (EmitControlFlow.java)
- [ ] Modify `handleNextLastRedo()` to create and return marked RuntimeList for non-local cases
- [ ] Modify `handleGotoLabel()` to create and return marked RuntimeList for non-local cases
- [ ] Handle `EXPR` forms (computed labels)
- [ ] Keep local (compile-time known) cases unchanged (fast GOTO)

### Phase 3: Call Site Checks
- [ ] Add checks after `apply()` in `EmitSubroutine.handleApplyOperator()`
- [ ] Add checks after `apply()` in `EmitVariable` (method calls)
- [ ] Add checks after `eval` in `EmitEval`
- [ ] Use `stackLevelManager.emitPopInstructions(mv, 0)` for cleanup

### Phase 4: Loop Handlers
- [ ] Generate control flow handler in `EmitForeach.emitFor1()` for labeled loops
- [ ] Generate control flow handler in `EmitStatement.handleFor3()` for labeled loops
- [ ] Implement LOOKUPSWITCH for type dispatch
- [ ] Implement label checking for each loop
- [ ] Implement propagation (GOTO returnLabel)

### Phase 5: Top-Level Error Handler
- [ ] Add error handler in `RuntimeCode.apply()` to catch unhandled control flow
- [ ] Check if returned `RuntimeList` has control flow marker after method execution
- [ ] If marker found, throw error: "Can't find label LABEL_NAME"
- [ ] This catches cases like signal handlers doing control flow to non-existent labels

### Phase 6: Cleanup
- [ ] Remove exception classes (LastException, NextException, RedoException, GotoException)
- [ ] Remove try-catch blocks from loops (EmitForeach, EmitStatement)
- [ ] Remove LoopExtractor.java (no longer needed)
- [ ] Revert local variable changes in EmitBinaryOperator, EmitOperator, Dereference, etc.
- [ ] Update NON_LOCAL_GOTO.md or retire it

### Phase 7: Bytecode Size Estimation and Loop Extraction
- [ ] Add `LoopSizeEstimator` utility class
- [ ] Update `LargeBlockRefactorer.estimateMethodSize()` to account for control flow overhead
- [ ] Add `countCallSitesInNode()` helper to count subroutine calls in a node
- [ ] Add `extractLoopToAnonSub()` to extract large inner loops into anonymous subroutines
- [ ] Add `findInnerLoops()` to identify extractable inner loops
- [ ] Implement recursive extraction (deepest loops first)
- [ ] Test size estimation accuracy on real code
- [ ] Test loop extraction preserves semantics (control flow across extracted boundaries)

### Phase 8: Testing

**Critical Regression Tests (run BEFORE every commit):**

These are the tests that failed during development with various VerifyErrors and stack issues. They MUST pass before committing:

```bash
# Quick regression check (5-10 minutes)
timeout 900 dev/tools/perl_test_runner.pl \
    perl5_t/t/uni/variables.t \
    perl5_t/t/op/hash.t \
    perl5_t/t/op/for.t \
    perl5_t/t/cmd/mod.t \
    perl5_t/t/op/list.t \
    perl5_t/t/io/through.t \
    perl5_t/t/io/fs.t \
    perl5_t/t/op/avhv.t \
    perl5_t/t/op/aassign.t \
    perl5_t/t/perf/benchmarks.t \
    perl5_t/t/re/pat_advanced.t \
    src/test/resources/unit/nonlocal_goto.t \
    src/test/resources/unit/loop_label.t
```

**Baseline expectations (from logs/test_20251104_152600):**
- `uni/variables.t`: 66683/66880 ok (99.7%)
- `op/hash.t`: 26937/26942 ok (99.98%)
- `op/for.t`: 119/119 ok (100%)
- `cmd/mod.t`: 15/15 ok (100%)
- `op/list.t`: 69/75 ok (incomplete - acceptable)
- `io/through.t`: 942/942 ok (but very slow - 227s)
- `perf/benchmarks.t`: 1960/1960 ok (100%)
- `re/pat_advanced.t`: 48/83 ok (baseline)

**Why these tests are critical:**
1. **uni/variables.t**: Caught stack inconsistency in nested loops with expressions
2. **op/hash.t**: Exposed loop-in-expression-context VerifyError
3. **op/for.t**: Multiple loop contexts and control flow edge cases
4. **cmd/mod.t**: Loop modifiers and control flow
5. **op/list.t**: List operations with loops (VerifyError prone)
6. **io/through.t**: Timeout-prone, catches infinite loops
7. **perf/benchmarks.t**: Catches "Method too large" errors
8. **re/pat_advanced.t**: Complex regex with loops

**Unit Tests:**
- [ ] Test unlabeled last/next/redo
- [ ] Test labeled last/next/redo (local and non-local)
- [ ] Test goto LABEL (local and non-local)
- [ ] Test computed labels (last EXPR, goto EXPR)
- [ ] Test nested loops with control flow
- [ ] Test Test::More::skip()
- [ ] Test loops in expression contexts
- [ ] Test while/until labeled loops
- [ ] Test do-while labeled loops
- [ ] Test continue blocks with next/last
- [ ] Test control flow across eval boundaries
- [ ] Test control flow in closures

**Full Test Suite:**
- [ ] Run full test suite (make test)
- [ ] Compare against baseline (logs/test_20251104_152600)
- [ ] Ensure pass rate >= 99.8%
- [ ] No new VerifyErrors
- [ ] No new "Method too large" errors
- [ ] No new timeouts (except known slow tests)

## Performance Characteristics

### Tagged Return Approach
- **Call site overhead**: ~8 bytes (DUP, isNonLocalGoto check, conditional jump)
- **Handler overhead**: ~200 bytes per loop (one-time per labeled loop)
- **Runtime overhead**: One boolean field check per subroutine call
  - Fast path (no control flow): Branch prediction makes this nearly free
  - Slow path (control flow): Handler executes (rare case)

### vs. Exception Approach
- **Exception approach**: ~50 bytes exception table + ~30 bytes handler per loop
- **Tagged approach**: ~8 bytes per call site + ~200 bytes handler per loop
- For 10 calls in a loop: Exception = ~800 bytes, Tagged = ~280 bytes
- **Tagged approach uses ~65% less bytecode**

## Edge Cases to Test

1. Control flow across multiple subroutine levels
2. Control flow in anonymous subroutines
3. Control flow in closures capturing loop variables
4. Computed labels with expressions
5. goto to labels outside loops
6. goto to non-existent labels (should throw error)
7. last/next/redo in VOID vs SCALAR vs LIST context
8. Nested loops with same label names in different scopes
9. Control flow in eval blocks
10. Control flow in sort comparators (should fail)

## Additional Considerations

### 1. Call Sites to Check

**ALL places where user code can be called:**
- [ ] `apply()` - regular subroutine calls (EmitSubroutine.java)
- [ ] `applyList()` - list context calls (EmitSubroutine.java)
- [ ] Method calls via `->` (EmitVariable.java)
- [ ] `eval` string/block (EmitEval.java)
- [ ] `map` block - special case? (may need custom handling)
- [ ] `grep` block - special case? (may need custom handling)
- [ ] `sort` block - should NOT allow control flow out
- [ ] Anonymous subroutine calls (EmitSubroutine.java)
- [ ] Autoloaded subroutines (same as regular apply)

### 2. Context Handling

**Loops in different contexts:**
```perl
# VOID context (common)
for (@list) { ... }  # No return value expected

# SCALAR context (rare)
$x = for (@list) { last; }  # Returns undef on last

# LIST context (rare)  
@y = for (@list) { ... }  # Returns empty list normally
```

**Key**: Marked RuntimeList should be handled BEFORE context conversion. The control flow handler clears the marker before jumping locally, so normal context handling applies.

### 3. Exception Handling Interaction

**What if control flow happens inside an eval?**
```perl
eval {
    for (@list) {
        last OUTER;  # Crosses eval boundary!
    }
};
```

**Answer**: Tagged return value will propagate through eval's return, bypassing exception handling. This is **correct** - control flow is not an exception.

### 4. Special Variables

**$@ (eval error)** should NOT be set by control flow (it's not an error).
**caller()** should work correctly (control flow uses normal returns).

### 5. Loop Label Stack Management

**During bytecode generation**, we maintain a stack of loop labels (EmitterContext):
```java
ctx.javaClassInfo.loopLabelStack.push(new LoopLabels(...));
// Generate loop body
ctx.javaClassInfo.loopLabelStack.pop();
```

**Handler chaining** requires knowing the parent handler label:
```java
Label outerHandler = null;
if (!ctx.javaClassInfo.loopLabelStack.isEmpty()) {
    LoopLabels parent = ctx.javaClassInfo.loopLabelStack.peek();
    outerHandler = parent.controlFlowHandlerLabel;
}

// In inner handler's default case:
if (outerHandler != null) {
    mv.visitJumpInsn(GOTO, outerHandler);  // Chain to parent
} else {
    mv.visitJumpInsn(GOTO, returnLabel);   // Return to caller
}
```

### 6. Performance Optimizations

**Unlabeled control flow** (most common case):
```perl
for (@list) {
    last;   # Unlabeled - always targets this loop
    next;   # Unlabeled - always targets this loop
}
```

**Optimization**: Unlabeled `last`/`next`/`redo` can be detected at compile time and use **local GOTO** (existing fast path), avoiding the handler entirely!

**Implementation**: Only add handler and call-site checks for **labeled loops**. Unlabeled loops use existing fast implementation.

### 7. Memory Management

**RuntimeList instances** are created frequently. Adding 3 fields increases memory overhead:
- `boolean isControlFlow` - 1 byte (+ padding)
- `String controlFlowType` - 8 bytes (reference)
- `String controlFlowLabel` - 8 bytes (reference)

**Total**: ~16 bytes per RuntimeList (due to object alignment).

**Optimization**: Consider using a **separate control flow marker class** that's only created when needed:
```java
class RuntimeList {
    private ControlFlowMarker controlFlowMarker = null;  // Usually null
    
    public boolean isNonLocalGoto() {
        return controlFlowMarker != null;
    }
}

class ControlFlowMarker {
    String type;
    String label;
}
```

This way, normal RuntimeList instances have just one null reference (8 bytes), and marker objects are only created for the rare control flow case.

### 8. Thread Safety

**RuntimeList** is NOT thread-safe. If the same RuntimeList is shared between threads and one marks it for control flow, chaos ensues.

**Current status**: PerlOnJava doesn't support threading yet, so this is not an immediate concern. Document as limitation.

### 9. Debugging Support

**Stack traces** should be clear when control flow happens:
- Current: Exception stack traces show the control flow path (helpful for debugging)
- Tagged return: No stack trace (silent propagation)

**Solution**: Add debug logging (controlled by flag) to track control flow propagation for troubleshooting.

### 10. Compatibility with Existing Code

**grep/map with control flow** - currently may work by accident (exceptions):
```perl
@result = map { last if $cond; $_ } @list;
```

With tagged returns, this needs special handling - grep/map need to check their block's return value.

**sort blocks** - must NOT allow control flow to escape:
```perl
@sorted = sort { last OUTER; $a <=> $b } @list;  # Should fail
```

Need to detect this and throw an error.

### 11. while/until Loops

**Don't forget C-style while/until:**
```perl
OUTER: while ($condition) {
    sub { last OUTER; }->();
}
```

These also need handlers! Check EmitStatement.java for while/until loop emission.

### 12. do-while Loops

```perl
OUTER: do {
    sub { last OUTER; }->();
} while ($condition);
```

These are different from do-blocks - they're loops and need handlers.

### 13. Bare Blocks with Control Flow

```perl
BLOCK: {
    do_something();
    last BLOCK;
}
```

Bare blocks are currently implemented as For3 loops. Ensure they get handlers too!

### 14. continue Blocks

```perl
for (@list) {
    next;
} continue {
    print "continue block\n";  # Executes even on next
}
```

**Important**: `continue` blocks execute on `next` but NOT on `last`. Ensure jump targets are correct:
- `next` jumps to continue block
- `last` jumps past continue block

### 15. Testing Edge Cases

**Add tests for:**
- [ ] Control flow across 3+ nested loops
- [ ] Control flow across 5+ subroutine call levels
- [ ] Control flow with closures capturing variables
- [ ] Control flow in string eval
- [ ] Control flow in block eval  
- [ ] Control flow with DESTROY (destructors running during unwind)
- [ ] Control flow with local() variables
- [ ] Control flow with tie'd variables
- [ ] Deeply nested expression contexts
- [ ] Control flow in overloaded operators

### 17. What We Almost Forgot

#### A. `wantarray` and Call Context
**Problem**: `wantarray` checks the calling context. If control flow returns a marked `RuntimeList`, does `wantarray` still work?

```perl
sub foo {
    my @result = (1, 2, 3);
    if (wantarray) {
        return @result;  # List context
    } else {
        return scalar(@result);  # Scalar context
    }
}
```

**Solution**: `wantarray` is checked at compile time and encoded in the call. Control flow doesn't affect it.

However, if `return` happens inside a loop called from another sub:
```perl
sub outer {
    my @x = inner();  # List context
}

sub inner {
    for (@list) {
        return (1, 2, 3);  # Should return to outer in list context
    }
}
```

**Answer**: `return` already uses `returnLabel`, which converts to `RuntimeList` via `getList()`. This is **independent** of control flow markers. No conflict!

#### B. Return Value from Loops
**Perl semantics**: Loops can return values, but control flow operators change this:

```perl
# Normal loop completion
my $x = do { for (1..3) { $_ } };  # Returns 3

# With last
my $y = do { for (1..3) { last } };  # Returns undef

# With next (exits early on last iteration)
my $z = do { for (1..3) { next } };  # Returns empty list
```

**Our implementation**: When control flow handler clears the marker and jumps locally, what value is on the stack?

**Solution**: 
- `last`: Jump to loop end with nothing on stack (loop returns undef/empty)
- `next`: Jump to continue/condition check
- `redo`: Jump to loop body start

The loop's **natural return value** (if any) is only produced on **normal completion**, not on control flow exits. This is correct!

#### C. Implicit Return from Subroutines
**Perl semantics**: Subroutines return the last evaluated expression:

```perl
sub foo {
    for (@list) {
        last if $condition;
        do_something();
    }
    # Implicitly returns result of for loop (which may have done `last`)
}
```

If the loop did `last` and returns undef, the sub returns undef. **This just works** because the loop statement's value is what's on the stack at the end.

#### D. Control Flow in String Eval
```perl
eval 'OUTER: for (@list) { last OUTER; }';
```

**Concern**: String eval compiles to a separate class. Can control flow cross eval boundaries?

**Answer**: 
- If `OUTER` is defined in the eval, it's local - works fine
- If `OUTER` is defined outside eval, the label is not in scope during compilation of the eval string
- This is **correct Perl behavior** - string eval has separate compilation scope

**For block eval:**
```perl
OUTER: for (@list) {
    eval { last OUTER; };  # Should work!
}
```

This works because block eval is compiled inline and sees `OUTER` in scope. The marked `RuntimeList` propagates through eval's return. ✅

#### E. `caller()` and Control Flow
**Perl's `caller()`** returns information about the call stack.

```perl
sub inner {
    my @info = caller(0);  # Gets info about who called us
    last OUTER;
}
```

**Concern**: Does control flow affect `caller()`?

**Answer**: No! `caller()` inspects the Java call stack at runtime. Control flow returns are normal Java returns (just with a marked `RuntimeList`). The call stack is unchanged. ✅

#### F. Destructors (DESTROY) During Unwind
```perl
{
    my $obj = MyClass->new();  # Will call DESTROY when $obj goes out of scope
    for (@list) {
        last OUTER;  # Jumps out of block - does $obj get destroyed?
    }
}
```

**Concern**: When control flow jumps out of a block, do local variables get cleaned up?

**Answer**: Yes! Our implementation uses `stackLevelManager.emitPopInstructions(mv, 0)` which cleans the stack, and then returns via `returnLabel`, which calls `Local.localTeardown()`. Local variables (including blessed objects) are properly cleaned up. ✅

**However**: For local GOTOs (compile-time known labels in same scope), we use direct `GOTO` which **bypasses teardown**. This is a **potential bug**!

```perl
LABEL: {
    my $obj = MyClass->new();
    for (@list) {
        goto LABEL;  # Direct GOTO - $obj might not be destroyed!
    }
}
```

**Fix**: Even local `goto` should go through cleanup. Either:
1. Emit teardown code before local `GOTO`
2. Or use the tagged return approach for `goto` too (even local ones)

**Decision needed**: Check standard Perl behavior for local goto with destructors.

#### G. Signal Handlers and Control Flow
```perl
$SIG{INT} = sub {
    last OUTER;  # Can a signal handler do non-local control flow?
};

OUTER: for (@list) {
    sleep 10;  # User presses Ctrl-C
}
```

**Concern**: Can signal handlers use control flow to jump into user code?

**Answer**: In standard Perl, signal handlers run in arbitrary context and **cannot** safely use non-local control flow. The labeled loop might not even be on the call stack when the signal fires!

**Our implementation**: Signal handlers would create a marked `RuntimeList` and return it. If no loop is on the call stack to handle it, it would propagate to the top level and... what happens?

**Solution**: Need an error handler at the top level (in `RuntimeCode.apply()`) to catch unhandled control flow markers and throw an error: "Can't find label OUTER".

#### H. Threads and Control Flow (Future)
**Concern**: If two threads share a `RuntimeList` object, and one marks it for control flow, the other thread might incorrectly handle it.

**Answer**: Not a concern yet (no threading support), but document as limitation for future.

#### I. Performance: Marked RuntimeList Reuse
**Concern**: If a `RuntimeList` gets marked, then cleared, then reused, does the marker object get GC'd?

**Answer**: Yes. Setting `controlFlowMarker = null` makes it eligible for GC. This is fine.

**Optimization idea**: Could pool marker objects to avoid allocation, but probably not worth the complexity (control flow is rare).

#### J. Debugger Integration
**Concern**: How do debuggers see control flow? Stack traces?

**Answer**: Tagged return approach is "invisible" to debuggers - it looks like normal returns. Exception-based approach had visible stack traces. This is a **tradeoff**:
- **Pro**: Cleaner (no exception noise in debugger)
- **Con**: Less visible (harder to debug control flow issues)

**Solution**: Add optional debug logging (controlled by flag) to trace control flow propagation.

### 16. Bytecode Size Estimation and Loop Extraction

The **LargeBlockRefactorer** uses bytecode size estimation to determine when to split methods. We need to update the size estimator to account for new control flow checking code.

**Current estimator location**: `LargeBlockRefactorer.java` (method size estimation logic)

**Strategy for large loops**: If a loop (especially with labeled control flow overhead) becomes too large, inner loops can be extracted into anonymous subroutines. This reduces the parent method's size while preserving semantics.

**Example transformation:**
```perl
# Before: Large loop in single method
OUTER: for my $i (@outer) {
    # ... lots of code ...
    INNER: for my $j (@inner) {
        # ... even more code ...
    }
    # ... more code ...
}

# After: Inner loop extracted
OUTER: for my $i (@outer) {
    # ... lots of code ...
    sub {  # Anonymous sub
        INNER: for my $j (@inner) {
            # ... even more code ...
            last OUTER;  # Still works! Returns marked RuntimeList
        }
    }->();
    # ... more code ...
}
```

**Key insight**: Extracting inner loops to anonymous subs is **semantically safe** with tagged return values:
- ✅ Closures capture outer loop variables correctly
- ✅ `last OUTER` inside the anonymous sub returns a marked RuntimeList
- ✅ The marked RuntimeList propagates through the sub's return
- ✅ Call site in OUTER loop detects it and handles it

**When to extract:**
1. If estimated loop size exceeds threshold (e.g., 10KB)
2. Extract **labeled** inner loops first (they have more overhead)
3. Extract **unlabeled** inner loops if still too large
4. Can be done recursively (extract deepest loops first)

**Extraction logic in LargeBlockRefactorer:**
```java
// Check if loop is too large
if (estimatedLoopSize > LOOP_SIZE_THRESHOLD) {
    // Find inner loops that can be extracted
    List<LoopNode> innerLoops = findInnerLoops(loopNode);
    
    // Extract labeled loops first (higher overhead)
    for (LoopNode inner : innerLoops) {
        if (inner.labelName != null) {
            extractLoopToAnonSub(inner);
        }
    }
    
    // Re-estimate size after extraction
    estimatedLoopSize = estimateLoopSize(loopNode);
    
    // If still too large, extract unlabeled loops
    if (estimatedLoopSize > LOOP_SIZE_THRESHOLD) {
        for (LoopNode inner : innerLoops) {
            if (inner.labelName == null) {
                extractLoopToAnonSub(inner);
            }
        }
    }
}
```

**New bytecode overhead per labeled loop:**

```java
// Per subroutine call site inside labeled loop: ~15 bytes
DUP                           // 1 byte
INVOKEVIRTUAL isNonLocalGoto  // 3 bytes
IFNE need_cleanup             // 3 bytes
POP                           // 1 byte (normal path)
// ... normal code ...
need_cleanup:
  ASTORE temp                 // 2 bytes
  POP (n times)               // n bytes (stack cleanup)
  ALOAD temp                  // 2 bytes
  GOTO handler                // 3 bytes
```

**Per labeled loop handler: ~200-300 bytes**
```java
loopControlFlowHandler:
  DUP                         // 1 byte
  INVOKEVIRTUAL getType       // 3 bytes
  ASTORE temp_type            // 2 bytes
  DUP                         // 1 byte
  INVOKEVIRTUAL getLabel      // 3 bytes
  ASTORE temp_label           // 2 bytes
  ALOAD temp_type             // 2 bytes
  LOOKUPSWITCH (4 cases)      // ~40 bytes
  // Each case: ~30 bytes
  handle_last:
    ALOAD temp_label          // 2 bytes
    IFNULL do_last            // 3 bytes
    LDC "LABEL"               // 2 bytes
    INVOKEVIRTUAL equals      // 3 bytes
    IFNE do_last              // 3 bytes
    GOTO propagate            // 3 bytes
  do_last:
    POP                       // 1 byte
    GOTO loop_end             // 3 bytes
  // Repeat for next, redo, goto
  propagate:
    GOTO parent_handler       // 3 bytes (or returnLabel)
```

**Size estimation formula:**
```java
class LoopSizeEstimator {
    // Estimate additional bytecode for labeled loop
    public static int estimateControlFlowOverhead(
        int numCallSitesInLoop,
        int avgStackDepth,
        boolean hasParentLoop
    ) {
        // Call site overhead
        int callSiteBytes = numCallSitesInLoop * (15 + avgStackDepth);
        
        // Handler overhead
        int handlerBytes = 250;  // Base handler size
        
        // No parent handler means we need returnLabel logic (smaller)
        if (!hasParentLoop) {
            handlerBytes -= 20;
        }
        
        return callSiteBytes + handlerBytes;
    }
}
```

**Integration with LargeBlockRefactorer:**

Update the size estimation in `LargeBlockRefactorer.estimateMethodSize()` to add:
```java
// For each labeled loop in the method
if (node instanceof For1Node for1 && for1.labelName != null) {
    int callSites = countCallSitesInNode(for1.body);
    int avgStackDepth = 2;  // Conservative estimate
    boolean hasParent = !loopStack.isEmpty();
    size += LoopSizeEstimator.estimateControlFlowOverhead(
        callSites, avgStackDepth, hasParent
    );
}
```

**Testing the estimator:**
- [ ] Compile method with labeled loops and check actual bytecode size
- [ ] Compare estimated vs actual size (should be within 10%)
- [ ] Ensure methods near 64KB limit are correctly split

## Open Questions

1. **Can unlabeled loops be optimized?** Unlabeled `last`/`next`/`redo` always target innermost loop - can we skip the handler?
   - **Answer**: Yes, unlabeled can use local GOTO (compile-time known target). Only labeled loops need handlers.

2. **What about goto &NAME (tail call)?** This is unrelated to label-based goto.
   - **Answer**: Keep existing implementation separate.

3. **Should we warn on deprecated constructs?** (goto into loops, etc.)
   - **Answer**: Yes, emit warnings matching Perl 5 behavior.

4. **How to handle control flow in grep/map?** Perl says "should not be used".
   - **Answer**: grep/map need to check return value and handle/propagate control flow markers.

5. **Should sort blocks prevent control flow?** Perl doesn't allow jumping out of sort.
   - **Answer**: Yes, detect and throw error (or add special handling to block it).

6. **Memory overhead acceptable?** 16 bytes per RuntimeList vs separate marker class.
   - **Decision needed**: Profile memory usage and decide on optimization.

7. **What about given/when blocks?** (Perl 5.10+)
   - **Answer**: Treat like switch statements - may need special handling.

## References

- `perldoc -f last`
- `perldoc -f next`
- `perldoc -f redo`
- `perldoc -f goto`
- JVM Specification: Stack Map Frames and Verification
- ASM Framework Documentation: StackMapTable generation

