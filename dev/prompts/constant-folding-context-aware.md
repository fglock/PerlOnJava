# Context-Aware Constant Folding (Future Enhancement)

## Current Limitation

The `ConstantFoldingVisitor.getConstantValue()` method currently treats all expressions as being in **scalar context**. This works for regex code blocks `(?{...})` because `$^R` always operates in scalar context, but it's not a complete solution for general constant folding.

## The Issue

Perl's context system (`RuntimeContextType`) affects how expressions evaluate:

- **SCALAR context**: Empty list `()` returns `undef`
- **LIST context**: Empty list `()` returns empty list `[]`
- **VOID context**: Result is discarded

### Current Implementation

```java
// In ConstantFoldingVisitor.getConstantValue()
else if (node instanceof ListNode listNode) {
    // Handle empty list/statement - returns undef in scalar context
    if (listNode.elements.isEmpty()) {
        return RuntimeScalarCache.scalarUndef;  // Always scalar context!
    }
}
```

This works for `(?{})` in regex because:
1. Regex code blocks always evaluate in scalar context for `$^R`
2. Empty blocks correctly return `undef`

### Example Behavior

```perl
# Scalar context (current implementation correct)
my $x = (?{});  # $x = undef ✅

# List context (not currently handled)
my @x = (?{});  # @x = () - but we'd return (undef) ❌
```

## Future Enhancement

To make constant folding fully context-aware:

### 1. Add Context Parameter to getConstantValue()

```java
public static RuntimeScalar getConstantValue(Node node, int context) {
    // ... existing code ...
    
    if (node instanceof ListNode listNode) {
        if (listNode.elements.isEmpty()) {
            if (context == RuntimeContextType.SCALAR) {
                return RuntimeScalarCache.scalarUndef;
            } else if (context == RuntimeContextType.LIST) {
                // Return empty list representation
                return new RuntimeArray();  // Or appropriate empty list marker
            }
        }
    }
}
```

### 2. Thread Context Through Constant Folding

The constant folding visitor would need to track context as it walks the AST:

- Function arguments: LIST context
- Scalar assignments: SCALAR context  
- Array assignments: LIST context
- Ternary operator: Inherits from parent context
- etc.

### 3. Benefits

- More accurate constant folding across all contexts
- Could optimize more complex expressions
- Better match Perl semantics

## Current Status

**NOT NEEDED NOW** - The current scalar-only implementation is sufficient for:
- Regex code blocks `(?{...})` and `$^R` support
- Most common constant folding use cases (numbers, strings, simple expressions)

**FUTURE WORK** - Context-aware folding would be valuable for:
- General-purpose optimization pass
- More aggressive compile-time evaluation
- Edge cases involving list/scalar context differences

## References

- `RuntimeContextType.java` - Defines SCALAR, LIST, VOID, RUNTIME contexts
- `ConstantFoldingVisitor.java` - Current implementation
- Perl documentation on contexts: `perldoc perldata` (CONTEXT section)

## Related Code

```java
// Current usage in parseRegexCodeBlock (StringSegmentParser.java)
RuntimeScalar constantValue = 
    ConstantFoldingVisitor.getConstantValue(folded);  // Always scalar context

// Future enhanced version would be:
RuntimeScalar constantValue = 
    ConstantFoldingVisitor.getConstantValue(folded, RuntimeContextType.SCALAR);
```

## Priority

**LOW** - Current implementation is correct for all existing use cases. Only implement this if:
1. We need more aggressive constant folding optimization
2. We encounter bugs related to context mismatches
3. We want to optimize list operations at compile time
