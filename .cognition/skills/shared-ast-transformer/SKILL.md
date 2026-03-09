---
name: shared-ast-transformer
description: Debug and develop the shared AST transformer for backend parity
argument-hint: "[context issue, ContextResolver, acceptChild]"
triggers:
  - user
  - model
---

# Shared AST Transformer Development

This skill covers development and debugging of the shared AST transformer that ensures parity between JVM and interpreter backends.

## Key Files

| File | Purpose |
|------|---------|
| `src/main/java/org/perlonjava/frontend/analysis/ContextResolver.java` | Propagates SCALAR/LIST/VOID context through AST |
| `src/main/java/org/perlonjava/frontend/analysis/EmitterVisitor.java` | Contains `acceptChild()` for context-aware node visiting |
| `src/main/java/org/perlonjava/frontend/analysis/ASTTransformPass.java` | Base class for transformer passes; `setContext()` preserves parser context |
| `src/main/java/org/perlonjava/frontend/analysis/ASTTransformer.java` | Pass orchestrator |
| `src/main/java/org/perlonjava/frontend/astnode/AbstractNode.java` | AST node with cached context fields |
| `src/main/java/org/perlonjava/frontend/astnode/Node.java` | Interface with `setCachedContext()`/`getCachedContext()` |
| `src/main/java/org/perlonjava/frontend/parser/PrototypeArgs.java` | Sets `cachedContext` for prototype arguments |
| `src/main/java/org/perlonjava/backend/jvm/EmitOperator.java` | `handleOperator()` reads `getCachedContext()` |
| `dev/design/shared_ast_transformer.md` | Design document with progress tracking |

## Architecture

```
Parser → Raw AST → ASTTransformer (ContextResolver pass) → Annotated AST
                                                              ↓
                                              ┌───────────────┴───────────────┐
                                              ↓                               ↓
                                        JVM Emitter                  BytecodeCompiler
                                     (uses acceptChild)            (uses cached context)
```

## ContextResolver Pattern

The `ContextResolver` uses `visitInContext()` helper to cleanly propagate context:

```java
private void visitInContext(Node node, int context) {
    if (node == null) return;
    int saved = currentContext;
    currentContext = context;
    node.accept(this);
    currentContext = saved;
}

// Usage - clean and consistent:
private void visitAssignment(BinaryOperatorNode node) {
    int lhsContext = LValueVisitor.getContext(node.left);
    int rhsContext = (lhsContext == RuntimeContextType.LIST)
            ? RuntimeContextType.LIST : RuntimeContextType.SCALAR;
    visitInContext(node.left, lhsContext);
    visitInContext(node.right, rhsContext);
}
```

## Debugging Context Issues

### 1. Enable context mismatch warnings

In `EmitterVisitor.acceptChild()`, add logging to identify mismatches:

```java
public void acceptChild(Node child, int fallbackContext) {
    if (child instanceof AbstractNode an && an.hasCachedContext()) {
        int cached = an.getCachedContext();
        if (cached != fallbackContext) {
            System.err.println("Context mismatch: " + nodeDescription(child) +
                    " cached=" + contextName(cached) +
                    " fallback=" + contextName(fallbackContext));
        }
    }
    // Use fallback for safe mode, or cached for testing
    child.accept(with(fallbackContext));
}
```

### 2. Analyze emitter context expectations

Run this script to extract all `acceptChild` calls and their expected contexts:

```bash
grep -rn "acceptChild" src/main/java/org/perlonjava/backend/jvm/*.java | \
    perl dev/tools/analyze_context_calls.pl
```

This shows:
- **Consistent patterns**: Always same context (e.g., `node.condition` → SCALAR)
- **Varying patterns**: Context depends on operator (e.g., `node.left` → LIST or SCALAR)

### 3. Check AST context with --parse

```bash
./jperl --parse -e 'my @a = (1,2,3); print "@a"'
```

Look for `ctx: SCALAR/LIST/VOID` annotations on nodes.

**Example**: Analyzing `substr($x, @array)` shows the parser wrapping `@array` with `scalar()`:

```bash
$ ./jperl --parse -e 'substr($x, @array)'
BlockNode:
  ctx: VOID
  OperatorNode: substr  pos:1
    ctx: VOID
    ListNode:
      ctx: SCALAR
      OperatorNode: $  pos:4
        ctx: SCALAR
        IdentifierNode: 'x'
          ctx: SCALAR
      OperatorNode: scalar  pos:8       # ← Parser wrapped @array with scalar()
        ctx: SCALAR
        OperatorNode: @  pos:8          # ← Inner @ node gets SCALAR from parent
          ctx: SCALAR
          IdentifierNode: 'array'
            ctx: SCALAR
```

This shows that `substr` has `$$` prototype, so `@array` is wrapped with `scalar()` by `ParserNodeUtils.toScalarContext()`.

## Common Context Rules

| Pattern | Context | Notes |
|---------|---------|-------|
| Assignment LHS (`$x`, `@a`, `%h`) | Matches sigil | `$`→SCALAR, `@`/`%`→LIST |
| Assignment RHS | Matches LHS | If LHS is LIST, RHS is LIST |
| Condition (`if`, `while`, `?:`) | SCALAR | Boolean test |
| Loop body | VOID | Unless used as expression |
| Loop list (`for @list`) | LIST | Elements to iterate |
| Subroutine args | LIST | `foo($a, $b)` |
| Subroutine body | RUNTIME | Determined by caller |
| `return` operand | RUNTIME | Passes caller context |
| `print`/`die`/`warn` args | LIST | Print list of values |
| `join` (binary) | left=SCALAR, right=LIST | Separator + list |
| `map`/`grep`/`sort` | block=SCALAR, list=LIST | |
| Logical `||`/`&&`/`//` | LHS=SCALAR, RHS=SCALAR or LIST | SCALAR in VOID/SCALAR context, LIST in LIST context |
| Comma in list context | Both LIST | `(@a, @b)` |
| Comma in scalar context | LHS=VOID, RHS=SCALAR | `($x, $y)` returns `$y` |

## Unified Context Annotation System

**Important**: There is a single source of truth for context: `cachedContext` field on nodes.

### How It Works

1. **Parser** sets `cachedContext` for prototype arguments via `PrototypeArgs.java`:
   - `$` prototype → `setCachedContext(RuntimeContextType.SCALAR)`
   - `@`/`%` prototype → no context set (defaults to LIST in emitter)

2. **ContextResolver** sets `cachedContext` for all other nodes:
   - Uses `setContext()` which does NOT overwrite parser-set context
   - This preserves prototype semantics

3. **Emitter** reads `getCachedContext()` in `handleOperator()`:
   - If SCALAR, use SCALAR context
   - Otherwise (including unset/-1), default to LIST

### Key Rule: Parser Context Takes Precedence

In `ASTTransformPass.setContext()`:
```java
protected void setContext(Node node, int context) {
    AbstractNode abstractNode = asAbstractNode(node);
    if (abstractNode != null && !abstractNode.hasCachedContext()) {
        abstractNode.setCachedContext(context);  // Only if not already set
    }
}
```

### Prototype Operators Needing LIST Context

Operators with `@` prototype need LIST context for operands. Add them to ContextResolver:

```java
// In visit(OperatorNode) switch:
case "pack", "mkdir", "opendir", "seekdir", "crypt", "vec", "read", "chmod",
     "chop", "chomp", "system", "exec", "$#", "splice", "reverse" -> visitListOperand(node);
```

## Known Issues (ALL MUST BE FIXED)

**IMPORTANT**: Every issue listed here is a BUG that must be fixed. None are acceptable.

### ListNode/OperatorNode(@) context mismatches

**Symptom**: Mismatch log shows:
```
ListNode cached=SCALAR expected=LIST : 707 times
OperatorNode(@) cached=SCALAR expected=LIST : 698 times
```

**Root cause**: The `visitOperatorDefault()` method sets SCALAR context on all operands, but some operators going through `handleOperator()` in the emitter expect LIST context for their ListNode operands.

**This MUST be fixed** by either:
1. Updating ContextResolver to set LIST context where the emitter expects LIST
2. OR fixing the emitter to pass SCALAR where ContextResolver sets SCALAR

The operators that fall through to `default -> visitOperatorDefault(node)` in ContextResolver and `default -> EmitOperator.handleOperator()` in EmitOperatorNode are prototype-based operators. The emitter's `handleOperator()` expects:
- ListNode operand: LIST context
- Individual elements: SCALAR if parser set it ($ prototype), otherwise LIST (@ prototype)

**Why OperatorNode(@) gets SCALAR**: When `@array` is used as an argument to a `$` prototype slot, `ParserNodeUtils.toScalarContext()` wraps it with `scalar()` operator. The ContextResolver then propagates SCALAR to the inner `@` node.

**Fix approach**: Update `visitOperatorDefault()` to use LIST context for ListNode operands, matching `handleOperator()` behavior:
```java
private void visitOperatorDefault(OperatorNode node) {
    if (node.operand instanceof ListNode list) {
        setContext(list, RuntimeContextType.LIST);
        for (Node element : list.elements) {
            if (element instanceof AbstractNode an && an.hasCachedContext()) {
                visitInContext(element, an.getCachedContext());
            } else {
                visitInContext(element, RuntimeContextType.LIST);
            }
        }
    } else if (node.operand != null) {
        visitInContext(node.operand, RuntimeContextType.SCALAR);
    }
}
```

### Stack frame errors when using cached context

When `acceptChild` uses cached context instead of fallback, JVM bytecode verification fails with "Operand stack underflow" or frame mismatches.

**This indicates a context mismatch that MUST be fixed.**

**Root cause**: The emitter generates different bytecode based on context. When cached context differs from what the emitter code path expects, the generated bytecode has inconsistent stack states.

**Example**: An operator's emitter code may:
1. Call `acceptChild(node, SCALAR)` expecting scalar result on stack
2. But ContextResolver cached LIST context
3. Emitter continues assuming scalar, but LIST code path left different stack

**The ONLY solution**: Fix ContextResolver to match emitter expectations exactly, OR fix the emitter to expect what ContextResolver sets. There is no workaround - the mismatch must be fixed.

### String interpolation (`"@a"`)

String interpolation like `"@a"` parses as:
```
BinaryOperatorNode: join
  left: StringNode (separator)
  right: ListNode
    BinaryOperatorNode: join
      left: OperatorNode($) → $"
      right: OperatorNode(@) → @a   ← This needs LIST context!
```

**Fix**: Add `case "join" -> visitJoinBinary(node)` in ContextResolver for BinaryOperatorNode.

## Building and Testing

### Jar File Locations

**IMPORTANT**: The `jperl` script uses `target/perlonjava-3.0.0.jar` (fat jar with dependencies).

| Location | Type | Created By |
|----------|------|------------|
| `target/perlonjava-3.0.0.jar` | Fat jar (~26MB) | `./gradlew shadowJar` or `./gradlew build` |
| `build/libs/perlonjava-3.0.0.jar` | Thin jar (~2.7MB) | `./gradlew jar` |

The thin jar in `build/libs/` is missing ASM dependencies and will fail with ClassNotFound errors.

### Build Commands

```bash
# Full build with fat jar (updates target/perlonjava-3.0.0.jar)
./gradlew build

# Just rebuild the fat jar (faster, skips tests)
./gradlew shadowJar

# Thin jar only (don't use with jperl!)
./gradlew jar
```

### Running Tests

```bash
# Run all tests
./gradlew test

# Run single test file
./jperl src/test/resources/unit/array.t

# Compare JVM vs interpreter
./jperl -e 'code'           # JVM backend
./jperl --int -e 'code'     # Interpreter backend
```

## Progress Tracking

Always update `dev/design/shared_ast_transformer.md` when:
1. Completing a phase
2. Discovering new issues
3. Adding ContextResolver fixes

Format:
```markdown
**ContextResolver Fixes Applied**:
- `join` binary: left=SCALAR (separator), right=LIST (for string interpolation)
- etc.

**Current State (YYYY-MM-DD)**:
- All 156 gradle tests pass
- String interpolation works correctly
```

## Making ContextResolver 100% Accurate (for interpreter migration)

### VALIDATION CRITERIA

**Success is defined as:**
```bash
perl dev/tools/run_exiftool_tests.pl
```
**Must pass 100% of all tests AND report ZERO context mismatches.**

### NO MISMATCHES ARE SAFE - NONE!

**CRITICAL**: There is no such thing as a "safe" or "acceptable" mismatch. Every single mismatch indicates a bug that MUST be fixed:

| Mismatch Example | Why It's NOT Safe |
|------------------|-------------------|
| `StringNode cached=SCALAR expected=LIST` | Backend is passing wrong context - FIX THE BACKEND |
| `NumberNode cached=SCALAR expected=LIST` | Backend is passing wrong context - FIX THE BACKEND |
| `OperatorNode(@) cached=SCALAR expected=LIST` | Either ContextResolver or backend is wrong - INVESTIGATE AND FIX |

If the code "works" despite a mismatch, **the backend has a bug that masks the issue**. The backend is being defensive and ignoring the wrong context. This must still be fixed because:
1. It creates technical debt
2. It prevents migration to cached context
3. It may cause subtle bugs in edge cases

### WHY THIS MATTERS

Without 100% accurate context annotation, **we cannot use the AST pre-processor**. The pre-processor relies on cached context to:
1. Optimize code based on known context
2. Enable backend-agnostic transformations
3. Allow the interpreter to share the same annotated AST as the JVM emitter

If context is wrong, the pre-processor will generate incorrect code.

### Current State (2026-03-09)

The JVM emitter uses `acceptChild()` with fallback context (safe mode). The interpreter uses `compileNode()` with explicit context. To migrate the interpreter to use cached context:

### CRITICAL REQUIREMENT: Context Must Be 100% Identical

**The context for both backends (JVM emitter and BytecodeCompiler/interpreter) must be EXACTLY the same. No exceptions are allowed.**

If the JVM emitter's `acceptChild()` passes SCALAR, ContextResolver must set SCALAR.
If the interpreter's `compileNode()` passes LIST, ContextResolver must set LIST.
If they disagree, **one of the backends must be fixed** to match the other.

**THERE ARE NO SAFE MISMATCHES. EVERY MISMATCH MUST BE FIXED.**

Every single mismatch must be resolved by either:
1. Fixing ContextResolver to set the correct context
2. Fixing the backend that has the wrong expectation

The current mismatch tracking exists to find these bugs. The goal is ZERO mismatches.

### METHODOLOGY: How to Achieve 100% Accuracy

#### Step A: Add Mismatch Tracking to BOTH Backends

**JVM Emitter** - in `EmitterVisitor.acceptChild()`:
```java
public void acceptChild(Node child, int fallbackContext) {
    if (child instanceof AbstractNode an && an.hasCachedContext()) {
        if (an.getCachedContext() != fallbackContext) {
            logMismatch("JVM", child, an.getCachedContext(), fallbackContext);
        }
    }
    child.accept(with(fallbackContext));
}
```

**Interpreter** - in `BytecodeCompiler.compileNode()`:
```java
void compileNode(Node node, int targetReg, int fallbackContext) {
    if (node instanceof AbstractNode an && an.hasCachedContext()) {
        if (an.getCachedContext() != fallbackContext) {
            logMismatch("INTERP", node, an.getCachedContext(), fallbackContext);
        }
    }
    // ... rest of method
}
```

#### Step B: For Each Mismatch, Trace the Source

When you see a mismatch like:
```
JVM: BinaryOperatorNode({) cached=LIST expected=SCALAR
```

1. **Find where the emitter sets this context**:
   ```bash
   grep -rn "acceptChild.*SCALAR" src/main/java/org/perlonjava/backend/jvm/
   ```
   Look for the code that visits `BinaryOperatorNode` with operator `{`.

2. **Find where the interpreter sets this context**:
   ```bash
   grep -rn "compileNode.*SCALAR" src/main/java/org/perlonjava/backend/bytecode/
   ```
   Look for the code that compiles subscript operations.

3. **Find where ContextResolver sets this context**:
   ```bash
   grep -n "visitSubscript\|case \"{\"" src/main/java/org/perlonjava/frontend/analysis/ContextResolver.java
   ```

#### Step C: Determine the CORRECT Context

For each node type, determine what context is semantically correct:

| Node | Correct Context | Reasoning |
|------|-----------------|-----------|
| `$hash{key}` | SCALAR | Single element access returns scalar |
| `@hash{@keys}` | LIST | Slice returns list |
| `$x + $y` | SCALAR | Arithmetic produces scalar |
| `@array` in `print @array` | LIST | Print consumes list |
| `@array` in `$n = @array` | SCALAR | Assignment to scalar wants count |

#### Step D: Fix the Mismatch

**Option 1: Fix ContextResolver** (preferred if it's wrong)
```java
// In ContextResolver.java
private void visitSubscript(BinaryOperatorNode node) {
    boolean isSlice = isSliceAccess(node.left);
    setContext(node, isSlice ? LIST : SCALAR);  // Match what backends expect
    // ...
}
```

**Option 2: Fix the Backend** (if backend has wrong expectation)
```java
// In EmitSubscript.java - if it was passing wrong context
// Change from:
emitterVisitor.acceptChild(node, RuntimeContextType.LIST);
// To:
emitterVisitor.acceptChild(node, RuntimeContextType.SCALAR);
```

#### Step E: Verify BOTH Backends Now Match

After each fix:
1. Run `./gradlew test` - check mismatch log at end
2. Verify the specific mismatch is gone from BOTH backends
3. Ensure no new mismatches were introduced

### KEY FILES FOR CONTEXT TRACING

When fixing mismatches, you need to examine these files:

**ContextResolver** (what it currently sets):
- `src/main/java/org/perlonjava/frontend/analysis/ContextResolver.java`

**JVM Emitter** (what it expects):
- `src/main/java/org/perlonjava/backend/jvm/EmitterVisitor.java` - `acceptChild()` calls
- `src/main/java/org/perlonjava/backend/jvm/EmitOperator.java` - operator handling
- `src/main/java/org/perlonjava/backend/jvm/EmitOperatorNode.java` - OperatorNode dispatch
- `src/main/java/org/perlonjava/backend/jvm/EmitBinaryOperator.java` - binary operators
- `src/main/java/org/perlonjava/backend/jvm/EmitSubscript.java` - subscript operations
- `src/main/java/org/perlonjava/backend/jvm/EmitVariable.java` - variable access

**Interpreter/BytecodeCompiler** (what it expects):
- `src/main/java/org/perlonjava/backend/bytecode/BytecodeCompiler.java` - `compileNode()` calls
- Search for `compileNode(` to find all context expectations

**To find all context expectations for a node type**:
```bash
# Find where JVM emitter visits BinaryOperatorNode
grep -rn "BinaryOperatorNode\|acceptChild" src/main/java/org/perlonjava/backend/jvm/*.java | grep -i subscript

# Find where interpreter compiles subscripts
grep -rn "compileNode\|visit.*BinaryOp" src/main/java/org/perlonjava/backend/bytecode/*.java
```

### Current State: Two Different Context Expectations

The **JVM emitter** and **BytecodeCompiler (interpreter)** currently have different context expectations:

| Backend | Context Source | Current Behavior |
|---------|---------------|------------------|
| JVM Emitter | `acceptChild(node, fallback)` | Uses fallback, logs mismatch if cached differs |
| Interpreter | `compileNode(node, reg, context)` | Uses explicit context parameter |

When we tried making the interpreter use cached context:
```java
void compileNode(Node node, int targetReg, int fallbackContext) {
    if (node instanceof AbstractNode an && an.hasCachedContext()) {
        currentCallContext = an.getCachedContext();  // ← This broke things!
    } else {
        currentCallContext = fallbackContext;
    }
}
```

It caused `unpack: unsupported format character` errors in ExifTool because:
1. ContextResolver sets context based on JVM emitter expectations
2. Interpreter has different expectations in some places
3. Cached context didn't match what interpreter code expected

### Step-by-Step Guide to Fix All Mismatches

#### Step 1: Identify All Mismatches

Add mismatch tracking to EmitterVisitor.acceptChild() (already done):
```java
private static final ConcurrentHashMap<String, AtomicInteger> contextMismatches = new ConcurrentHashMap<>();

static {
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        if (!contextMismatches.isEmpty()) {
            System.err.println("\n=== Context Mismatches ===");
            contextMismatches.entrySet().stream()
                .sorted((a, b) -> b.getValue().get() - a.getValue().get())
                .forEach(e -> System.err.println(e.getKey() + " : " + e.getValue().get() + " times"));
        }
    }));
}

public void acceptChild(Node child, int fallbackContext) {
    if (child instanceof AbstractNode an && an.hasCachedContext()) {
        if (an.getCachedContext() != fallbackContext) {
            String key = nodeDescription(child) + " cached=" + contextName(an.getCachedContext()) + 
                        " expected=" + contextName(fallbackContext);
            contextMismatches.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        }
    }
    child.accept(with(fallbackContext));  // Safe mode: use fallback
}
```

Run tests to collect mismatches:
```bash
mvn test 2>&1 | grep -A50 "Context Mismatches"
```

#### Step 2: Fix Each Mismatch Category

**Mismatch: `BinaryOperatorNode({) cached=LIST expected=SCALAR`**

**Root cause**: `visitSubscript()` didn't call `setContext()` on the node itself.

**Fix**: Set context based on slice vs single element:
```java
private void visitSubscript(BinaryOperatorNode node) {
    boolean isSlice = node.left instanceof OperatorNode opNode &&
            ("@".equals(opNode.operator) || "%".equals(opNode.operator));
    
    // THIS WAS MISSING! Set node context: slice→LIST, single element→SCALAR
    setContext(node, isSlice ? RuntimeContextType.LIST : RuntimeContextType.SCALAR);
    
    visitInContext(node.left, currentContext);
    visitInContext(node.right, isSlice ? RuntimeContextType.LIST : RuntimeContextType.SCALAR);
}
```

**Mismatch: `OperatorNode(unaryMinus) cached=LIST expected=SCALAR`**

**Root cause**: Numeric operators inherit `currentContext` but always produce SCALAR.

**Fix**: Set SCALAR context for scalar-producing operators:
```java
public void visit(OperatorNode node) {
    switch (node.operator) {
        // Numeric/string operators always produce SCALAR
        case "unaryMinus", "unaryPlus", "~", "!", "not",
             "abs", "int", "sqrt", "sin", "cos", "exp", "log", "rand",
             "length", "defined", "exists", "ref",
             "ord", "chr", "hex", "oct",
             "lc", "uc", "lcfirst", "ucfirst", "quotemeta",
             "++", "--", "++postfix", "--postfix" -> { 
            setContext(node, RuntimeContextType.SCALAR); 
            visitOperatorDefault(node); 
        }
        // ... other cases
        default -> { setContext(node, currentContext); visitOperatorDefault(node); }
    }
}
```

**Mismatch: `NumberNode cached=LIST expected=SCALAR`**

**Root cause**: Numbers always produce scalars but inherited `currentContext`.

**Fix**:
```java
public void visit(NumberNode node) {
    setContext(node, RuntimeContextType.SCALAR);  // Numbers are always scalar
}

public void visit(StringNode node) {
    setContext(node, RuntimeContextType.SCALAR);  // Strings are always scalar
}
```

**Mismatch: `OperatorNode(@) cached=SCALAR expected=LIST`**

**Root cause**: `@` operator in SCALAR context (e.g., inside `$` prototype argument) but emitter passes LIST.

**Analysis**:
1. When parser wraps `@array` with `scalar()` for `$` prototype, ContextResolver correctly sets SCALAR
2. The emitter is WRONG to pass LIST for nodes that are already wrapped with `scalar()`
3. Find where the emitter passes LIST for `OperatorNode(@)` and fix it to check the node's context

**MUST BE FIXED**: Find every `acceptChild` call that visits `OperatorNode(@)` with LIST context and fix it to pass the correct context based on what the node actually is.

#### Step 3: Fix ALL Remaining Mismatches - NO EXCEPTIONS

After initial fixes, run tests and check remaining mismatches:
```bash
./gradlew test 2>&1 | tail -30
```

**THERE ARE NO SAFE MISMATCHES. FIX EVERY SINGLE ONE:**
- `StringNode cached=SCALAR expected=LIST` → The backend is passing LIST for a string literal. Find where and fix the backend to pass SCALAR.
- `NumberNode cached=SCALAR expected=LIST` → The backend is passing LIST for a number literal. Find where and fix the backend to pass SCALAR.
- `OperatorNode(@) cached=SCALAR expected=LIST` → Either the parser correctly wrapped with `scalar()` and the backend should pass SCALAR, OR the backend incorrectly passes LIST. Investigate and fix.

**If code "works" despite a mismatch, the backend is being defensive.** This is still a bug that must be fixed. The backend should not have to be defensive - it should receive the correct context.

#### Step 4: Migrate Interpreter to Use Cached Context

Once all breaking mismatches are fixed, update `BytecodeCompiler.compileNode()`:

```java
void compileNode(Node node, int targetReg, int fallbackContext) {
    int savedTarget = targetOutputReg;
    int savedContext = currentCallContext;
    targetOutputReg = targetReg;
    
    // Use cached context from ContextResolver if available
    if (node instanceof AbstractNode an && an.hasCachedContext()) {
        currentCallContext = an.getCachedContext();
    } else {
        currentCallContext = fallbackContext;
    }
    
    node.accept(this);
    targetOutputReg = savedTarget;
    currentCallContext = savedContext;
}
```

**WARNING**: Only do this after ALL mismatches that cause functional issues are fixed!

### Testing the Migration

**The ONLY acceptance criteria is:**
```bash
perl dev/tools/run_exiftool_tests.pl
```
**Must pass 100% of all tests AND report ZERO context mismatches.**

Additional test commands:
1. Run unit tests: `./gradlew test`
2. Run ExifTool tests individually:
   ```bash
   cd Image-ExifTool-13.44
   timeout 180 java -jar ../target/perlonjava-3.0.0.jar -Ilib t/Writer.t
   timeout 180 java -jar ../target/perlonjava-3.0.0.jar -Ilib t/QuickTime.t
   ```
3. Check stderr for `=== Context Mismatches ===` - there should be NONE

### Checklist for 100% Accuracy

- [ ] **ZERO context mismatches** when running `perl dev/tools/run_exiftool_tests.pl`
- [ ] **ZERO context mismatches** when running `./gradlew test`
- [ ] All nodes have `setContext()` called (not just operands)
- [ ] Subscript nodes (`[`, `{`) set SCALAR or LIST based on slice vs element
- [ ] Scalar-producing operators set SCALAR context on themselves
- [ ] Terminal nodes (NumberNode, StringNode) have SCALAR context
- [ ] Arrow operator (`->`) handles RHS context correctly
- [ ] All visit methods call `setContext(node, ...)` before visiting children
- [ ] Both backends pass identical context for every node
- [ ] ExifTool tests pass 100%

### Files Changed for Context Fixes

| File | Changes |
|------|---------|
| `ContextResolver.java` | Added setContext() for subscripts, scalar operators |
| `EmitterVisitor.java` | Mismatch tracking in acceptChild() |
| `BytecodeCompiler.java` | (Future) Use cached context in compileNode() |

## Next Steps (as of 2026-03-09)

### IMMEDIATE GOAL: Zero Mismatches

**Validation command:**
```bash
perl dev/tools/run_exiftool_tests.pl
```
**Must pass 100% with ZERO context mismatches reported.**

### Current Mismatches to Fix (from `./gradlew test`):
- `StringNode cached=SCALAR expected=LIST` : 731 times → FIX BACKEND
- `OperatorNode(@) cached=SCALAR expected=LIST` : 475 times → INVESTIGATE
- `NumberNode cached=SCALAR expected=LIST` : 99 times → FIX BACKEND
- `StringNode cached=SCALAR expected=RUNTIME` : 8 times → FIX BACKEND
- `ListNode cached=SCALAR expected=LIST` : 7 times → FIX
- And others...

### After Zero Mismatches:
1. **Phase 2b**: Variable resolution pass
2. **Phase 3**: Unify both backends to use identical context handling
