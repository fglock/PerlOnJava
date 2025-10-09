# Deep Dive: `||` Operator in List Context

## Problem Statement

The `||` operator in Perl has complex context propagation semantics that differ from most other operators. When used in list context, it exhibits asymmetric behavior that's challenging to implement correctly in bytecode.

## Failing Test Case

```perl
# From t/op/list.t line 119
$x = 666;
@a = ($x == 12345 || (1,2,3));
# Expected: @a = (1, 2, 3)
# Got: @a = (3)  # Only last element
```

## Perl's Semantics

### Standard Behavior
```perl
@a = (0 || (1,2,3));  # Returns (1,2,3) - list from RHS
@a = (1 || (1,2,3));  # Returns (1) - scalar from LHS
```

### Key Rules
1. **Left side**: Always evaluated in **scalar context** (needed for boolean test)
2. **Right side**: Evaluated in **caller's context** (list or scalar)
3. **Result**: Depends on which branch is taken
   - If LHS is true: returns LHS value (scalar)
   - If LHS is false: returns RHS value (in caller's context)

## Why This Is Hard

### Bytecode Challenge
At the merge point (`endLabel`), the stack must have consistent types:

```java
// Branch 1 (LHS true):
node.left.accept(SCALAR);  // Stack: [RuntimeScalar]
goto endLabel;

// Branch 2 (LHS false):
node.right.accept(LIST);   // Stack: [RuntimeList]  ❌ TYPE MISMATCH!

endLabel:
// Stack type must be consistent!
```

### JVM VerifyError
When we tried to fix this by using caller's context for RHS:
```java
node.right.accept(emitterVisitor); // Use caller's context
```

Result: **JVM VerifyError** - "Expecting a stackmap frame at branch target"
- The JVM requires both branches to produce the same stack type
- RuntimeScalar vs RuntimeList causes verification failure

## Attempted Solutions

### Attempt 1: Propagate Context to RHS ❌
```java
node.right.accept(emitterVisitor); // Use caller's context
```
**Problem**: Stack type mismatch at merge point causes VerifyError

### Attempt 2: Convert at Merge Point (Not Implemented)
```java
// Pseudo-code:
if (context == LIST) {
    // Convert scalar to list if needed
    mv.visitMethodInsn("scalarToList");
}
```
**Problem**: Complex, requires context-aware conversion logic

### Attempt 3: Wrapper Approach (Not Implemented)
```java
// Always return RuntimeBase, let caller convert
node.left.accept(emitterVisitor);   // Returns RuntimeBase
node.right.accept(emitterVisitor);  // Returns RuntimeBase
// Caller handles getList() or getScalar()
```
**Problem**: Requires refactoring entire operator emission system

## Root Cause Analysis

The fundamental issue is that Perl's `||` operator violates the principle of **uniform context propagation**:
- Most operators propagate context uniformly to all operands
- `||` has **asymmetric context**: LHS always scalar, RHS depends on caller

This asymmetry is difficult to express in statically-typed bytecode where branch merge points require type consistency.

## Correct Implementation Strategy

### Option A: Context Conversion at Merge
```java
Label endLabel = new Label();
Label convertLabel = new Label();

// Evaluate LHS in scalar context
node.left.accept(emitterVisitor.with(SCALAR));
mv.visitInsn(DUP);
mv.visitMethodInsn("getBoolean");
mv.visitJumpInsn(compareOpcode, convertLabel);

// LHS false: evaluate RHS in caller's context
mv.visitInsn(POP);
node.right.accept(emitterVisitor);  // Caller's context
mv.visitJumpInsn(GOTO, endLabel);

// LHS true: convert scalar to caller's context if needed
convertLabel:
if (emitterVisitor.context == LIST) {
    // Convert RuntimeScalar to RuntimeList
    mv.visitMethodInsn("scalarToList");
}

endLabel:
// Stack now has consistent type
```

### Option B: Delayed Context Resolution
```java
// Return RuntimeBase (superclass of both Scalar and List)
// Let the caller extract the appropriate type
node.left.accept(emitterVisitor.with(SCALAR));
// ... boolean test ...
node.right.accept(emitterVisitor.with(SCALAR));  // Always scalar

endLabel:
// Stack: [RuntimeBase]
// Caller converts: result.getList() or result.getScalar()
```

### Option C: Special Case in Parser
```java
// Transform at AST level:
// @a = (COND || LIST) 
// becomes:
// @a = COND ? (COND_VALUE) : LIST

// This makes the context explicit in the AST
```

## Recommended Solution

**Option A** (Context Conversion at Merge) is the most correct approach:

1. **Pros**:
   - Matches Perl semantics exactly
   - No AST transformation needed
   - Localized to EmitLogicalOperator

2. **Cons**:
   - Requires implementing `scalarToList()` conversion
   - Slightly more complex bytecode

3. **Implementation**:
   ```java
   static void emitLogicalOperator(EmitterVisitor emitterVisitor, 
                                    BinaryOperatorNode node, 
                                    int compareOpcode, 
                                    String getBoolean) {
       MethodVisitor mv = emitterVisitor.ctx.mv;
       Label endLabel = new Label();
       Label convertLabel = new Label();
       RuntimeContextType callerContext = emitterVisitor.context;
       
       // LHS always scalar (for boolean test)
       node.left.accept(emitterVisitor.with(SCALAR));
       mv.visitInsn(DUP);
       mv.visitMethodInsn(INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeBase", 
                          getBoolean, "()Z", false);
       mv.visitJumpInsn(compareOpcode, convertLabel);
       
       // LHS false: evaluate RHS in caller's context
       mv.visitInsn(POP);
       node.right.accept(emitterVisitor.with(callerContext));
       mv.visitJumpInsn(GOTO, endLabel);
       
       // LHS true: convert to caller's context if needed
       mv.visitLabel(convertLabel);
       if (callerContext == RuntimeContextType.LIST) {
           // Convert scalar to single-element list
           mv.visitMethodInsn(INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar",
                              "scalarToList", "()Lorg/perlonjava/runtime/RuntimeList;", 
                              false);
       }
       
       mv.visitLabel(endLabel);
       EmitOperator.handleVoidContext(emitterVisitor);
   }
   ```

## Required Helper Method

Add to `RuntimeScalar.java`:
```java
/**
 * Convert a scalar to a single-element list.
 * Used for context conversion in logical operators.
 */
public RuntimeList scalarToList() {
    RuntimeList list = new RuntimeList();
    list.elements.add(this);
    return list;
}
```

## Testing Strategy

```perl
# Test cases to verify:
@a = (0 || (1,2,3));      # Should be (1,2,3)
@a = (1 || (1,2,3));      # Should be (1)
@a = ("" || (1,2,3));     # Should be (1,2,3)
@a = ("x" || (1,2,3));    # Should be ("x")

# Edge cases:
@a = (0 || ());           # Should be ()
@a = (1 || ());           # Should be (1)
@a = (0 || (1));          # Should be (1)
```

## Impact Assessment

- **Affected operators**: `||`, `&&`, `//`, `or`, `and` (all use `emitLogicalOperator`)
- **Test files**: `t/op/list.t` (test 39), potentially others
- **Risk**: Medium - requires careful bytecode generation and testing

## Status

**OPEN** - Requires implementation of Option A (Context Conversion at Merge)

## Related Issues

- Similar issue may exist in ternary operator `? :`
- May affect other short-circuit operators

## References

- Test file: `t/op/list.t` line 119
- Code: `EmitLogicalOperator.java` line 98
- Perl docs: `perlop` - "Logical Or"
