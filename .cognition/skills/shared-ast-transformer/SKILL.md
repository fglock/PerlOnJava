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

## Known Issues

### ListNode/OperatorNode(@) context mismatches (707 occurrences)

**Symptom**: Mismatch log shows:
```
ListNode cached=SCALAR expected=LIST : 707 times
OperatorNode(@) cached=SCALAR expected=LIST : 698 times
```

**Root cause**: The `visitOperatorDefault()` method sets SCALAR context on all operands, but some operators going through `handleOperator()` in the emitter expect LIST context for their ListNode operands.

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

**Root cause**: The emitter generates different bytecode based on context. When cached context differs from what the emitter code path expects, the generated bytecode has inconsistent stack states.

**Example**: An operator's emitter code may:
1. Call `acceptChild(node, SCALAR)` expecting scalar result on stack
2. But ContextResolver cached LIST context
3. Emitter continues assuming scalar, but LIST code path left different stack

**Solution approaches**:
1. Fix ContextResolver to match emitter expectations exactly
2. Make emitter more robust to context variations
3. Use `acceptChild` only for nodes where context doesn't affect stack layout

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

## Next Steps (as of 2025-03-09)

1. **Fix the 707 ListNode mismatches**: The `OperatorNode(@) cached=SCALAR` cases are **expected** behavior when `@array` is wrapped with `scalar()` by the parser for `$` prototype slots. The true issue is operators going through `visitOperatorDefault()` that should use LIST context for their ListNode operands.

2. **Approach**: Rather than changing `visitOperatorDefault()` globally (which broke 2695+ cases), identify specific operators that:
   - Fall through to `default` in both ContextResolver and EmitOperatorNode
   - Have `@` prototype slots expecting LIST context
   - Add them explicitly to the switch statement with `visitListOperand(node)`

3. **Phase 2b**: Variable resolution pass
