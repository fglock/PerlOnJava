# Refactoring Package

This package provides a unified framework for refactoring large AST structures to avoid JVM method size limits.

## Overview

The JVM has a hard limit of 65535 bytes per method. Large Perl code blocks and literals can exceed this limit when compiled to Java bytecode. This package automatically splits large structures into smaller chunks wrapped in closures.

## Architecture

### Core Components

#### `NodeListRefactorer`
The main entry point for refactoring any `List<Node>`. Provides a unified interface that works with blocks, arrays, hashes, and lists.

**Key Features:**
- Unified interface for all node types
- Control flow validation
- Recursive fallback strategies
- Tail-position closures for proper scoping

#### `ControlFlowValidator`
Validates that refactoring won't break control flow semantics.

**Control Flow Safety:**
Control flow statements (next/last/redo/goto) cannot be wrapped in closures because closures create a new scope, breaking the loop context. The validator ensures refactoring only happens when safe.

#### `RecursiveBlockRefactorer`
Recursively refactors inner blocks when top-level refactoring fails.

**Strategy:**
- Work depth-first (innermost blocks first)
- Refactor inner blocks that don't contain control flow
- Skip loop bodies (they contain valid control flow)

#### `RefactoringContext`
Configuration and utilities for refactoring operations.

**Parameters:**
- `minElementCount`: Minimum elements before refactoring (default: 50)
- `maxBytecodeSize`: Maximum bytecode size threshold (default: 40000)
- `minChunkSize`: Minimum chunk size (default: 2)
- `maxChunkSize`: Maximum chunk size (default: 200)

#### `RefactoringResult`
Result metadata from refactoring operations.

**Contains:**
- Success/failure status
- Original and final element counts
- Bytecode size estimates
- Attempted strategies
- Failure reasons

### Adapter Classes

#### `BlockRefactoringAdapter`
Adapter for `BlockNode` refactoring, maintains backward compatibility with `LargeBlockRefactorer`.

#### `ListRefactoringAdapter`
Adapter for array/hash/list literal refactoring, maintains backward compatibility with `LargeNodeRefactorer`.

## Refactoring Strategies

### 1. Smart Chunking
Splits safe statement sequences into closures at tail position.

**Safe sequences:**
- No control flow statements (next/last/redo/goto)
- No labels
- Complete blocks (if/loop/try) are kept as units

**Example:**
```perl
# Original:
{
    statement1;
    statement2;
    statement3;
    statement4;
}

# Refactored:
{
    sub { statement1; statement2 }->(@_);
    sub { statement3; statement4 }->(@_);
}
```

### 2. Tail Extraction
Extracts declaration-free tail sequences.

**Works backwards from end until hitting:**
- Variable declarations (my/our/local)
- Labels
- Control flow statements

**Example:**
```perl
# Original:
{
    my $x = 1;
    statement1;
    statement2;
    statement3;
}

# Refactored:
{
    my $x = 1;
    sub { statement1; statement2; statement3 }->(@_);
}
```

### 3. Complete Structure Extraction
Extracts whole if/loop/try blocks at tail position.

**Preserves control flow context:**
```perl
# Original:
{
    statement1;
    if ($condition) {
        next;  # Control flow preserved
    }
}

# Refactored:
{
    sub {
        statement1;
        if ($condition) {
            next;  # Still works correctly
        }
    }->(@_);
}
```

### 4. Recursive Inner Refactoring
When top-level refactoring fails, recursively refactors inner blocks.

**Targets:**
- If/else branches without control flow
- Try/catch blocks without control flow
- Nested blocks within loops

**Example:**
```perl
# Original loop with control flow - cannot refactor top level
for (...) {
    if ($condition) {
        # This inner block has no control flow - CAN refactor
        statement1;
        statement2;
        statement3;
    }
    next if $check;  # Control flow in outer block
}
```

## Control Flow Constraints

### Unsafe Control Flow
These statements cannot be wrapped in closures:
- `next` / `last` / `redo` targeting outer loops
- `goto` targeting outer labels
- Labels referenced from outside

### Safe Patterns
These can be refactored:
- Complete blocks with internal control flow
- Tail-position sequences without control flow
- Inner blocks that don't contain control flow

### Example: Labeled Block
```perl
# MUST extract as atomic unit:
LABEL: {
    statement_A;
    next if $check;  # targets LABEL
}

# Refactored (entire structure):
sub {
    LABEL: {
        statement_A;
        next if $check;  # still targets LABEL correctly
    }
}->(@_);
```

## Usage

### Enabling Refactoring
Set environment variable:
```bash
export JPERL_LARGECODE=refactor
```

### Block Refactoring
```java
// Automatic during parsing:
BlockNode block = new BlockNode(elements, tokenIndex);
// Refactoring happens in constructor via BlockRefactoringAdapter

// Manual refactoring:
RefactoringContext context = new RefactoringContext(tokenIndex, parser);
RefactoringResult result = NodeListRefactorer.refactor(block.elements, context);
```

### List Refactoring
```java
// Automatic during parsing:
List<Node> elements = ListRefactoringAdapter.maybeRefactorElements(
    originalElements, tokenIndex, NodeType.ARRAY, parser);
```

### Custom Refactoring
```java
// Create custom context:
RefactoringContext context = new RefactoringContext(
    100,    // minElementCount
    50000,  // maxBytecodeSize
    5,      // minChunkSize
    300,    // maxChunkSize
    tokenIndex,
    parser,
    false   // isLoopContext
);

// Refactor:
RefactoringResult result = NodeListRefactorer.refactor(nodes, context);

// Check result:
if (result.success && result.modified) {
    System.out.println("Refactored: " + result.getSummary());
} else if (!result.success) {
    System.err.println("Failed: " + result.failureReason);
}
```

## Error Handling

When refactoring fails and the structure is still too large, a detailed error message is provided:

```
Node list is too large (500 elements, estimated 80000 bytes) and cannot be automatically refactored.
Attempted strategies: [control_flow_check, recursive_inner_blocks, smart_chunking]
Obstacles: [labels: LABEL1, LABEL2; control flow: element at index 42, element at index 156]
Please manually split into smaller subroutines.
```

## Testing

### Test Cases
1. Large blocks without control flow → smart chunking
2. Large blocks with control flow → recursive inner refactoring
3. Labeled blocks → atomic extraction
4. Nested control structures → depth-first refactoring
5. Array/hash literals → flat chunking
6. List expressions → nested closures

### Verification
- Control flow semantics preserved
- Variable scoping correct
- Labels work correctly
- Bytecode size reduced below threshold

## Migration from Old Code

### From LargeBlockRefactorer
```java
// Old:
LargeBlockRefactorer.maybeRefactorBlock(blockNode, parser);

// New:
BlockRefactoringAdapter.maybeRefactorBlock(blockNode, parser);
```

### From LargeNodeRefactorer
```java
// Old:
List<Node> elements = LargeNodeRefactorer.maybeRefactorElements(
    elements, tokenIndex, NodeType.ARRAY, parser);

// New:
List<Node> elements = ListRefactoringAdapter.maybeRefactorElements(
    elements, tokenIndex, NodeType.ARRAY, parser);
```

## Performance Considerations

### Bytecode Estimation
Uses sampling for large lists to avoid O(n) traversal:
- Sample 10 elements
- Extrapolate total size
- Trade accuracy for speed

### Recursion Safety
Thread-local flag prevents infinite recursion when creating chunk blocks:
```java
private static final ThreadLocal<Boolean> skipRefactoring = 
    ThreadLocal.withInitial(() -> false);
```

### Chunk Size Calculation
Dynamically calculates optimal chunk size based on element bytecode estimates:
- Target ~20KB per chunk
- Clamp between min and max
- Ensure even numbers for hash pairs

## Future Enhancements

1. **Adaptive Thresholds**: Adjust thresholds based on runtime feedback
2. **Parallel Refactoring**: Refactor multiple branches in parallel
3. **Cost-Based Optimization**: Choose strategy based on estimated cost
4. **Incremental Refactoring**: Refactor only when compilation fails
5. **Profile-Guided**: Use profiling data to guide refactoring decisions
