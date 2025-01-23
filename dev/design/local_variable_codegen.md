# Local Variable Declaration Code Generation Design

## Current Behavior

The current implementation of the `local` operator in PerlOnJava clears variable values during localization, which causes incorrect behavior in cases like:

```perl
local $v = $v;  # Should preserve $v's value
local ($a, $b) = ($b, $a);  # Should preserve both values
```

## Issue

When processing `local` declarations that include assignments referencing the same variables, the current implementation clears the variables before the assignment completes. This results in `undef` values being assigned instead of the intended values.

## Proposed Solution

Add context tracking to properly handle self-referential assignments in `local` declarations:

1. Add flag to EmitterContext:
```java
boolean isLocalAssignment = false;
```

2. Update EmitOperator.handleLocal():
```java
emitterVisitor.ctx.isLocalAssignment = true;
node.operand.accept(emitterVisitor.with(lvalueContext));
emitterVisitor.ctx.isLocalAssignment = false;
```

3. Modify DynamicVariableManager.pushLocalVariable():
```java
public static RuntimeGlob pushLocalVariable(RuntimeGlob value, boolean preserveValue) {
    if (!preserveValue) {
        value.clear();
    }
    // existing implementation
}
```

## Benefits

- Correctly handles self-referential assignments in `local` declarations
- Maintains compatibility with Perl's behavior
- Simple implementation using existing visitor pattern
- Minimal changes to existing codebase

## Files Affected

- EmitterContext.java
- EmitOperator.java  
- DynamicVariableManager.java

## Testing

Test cases should include:

```perl
# Basic self-assignment
local $v = $v;

# List assignment
local ($a, $b) = ($b, $a);

# Mixed assignments
local ($x, $y) = ($y, 42);
```

## Alternative Approaches Considered

### Using FindDeclarationVisitor

While FindDeclarationVisitor.java provides AST traversal capabilities for finding operator declarations, the proposed context flag solution is more efficient for this use case because:

1. The visitor is already traversing nodes in the correct order during code generation
2. Using FindDeclarationVisitor would require an additional AST traversal pass
3. The context flag directly integrates with the existing emission logic

The FindDeclarationVisitor remains valuable for other use cases like analyzing block-level declarations and logical operations, but for local variable assignments the context flag provides a more direct solution.

