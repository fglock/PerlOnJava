# Unified Refactoring Package

## Overview

A new unified refactoring package has been created at `org.perlonjava.codegen.refactor` to handle large AST structures and avoid JVM method size limits. This package replaces and unifies the functionality previously split between `LargeBlockRefactorer` and `LargeNodeRefactorer`.

## Package Location

```
src/main/java/org/perlonjava/codegen/refactor/
```

## Key Components

### Core Classes

1. **`NodeListRefactorer`** - Main entry point for refactoring any `List<Node>`
   - Unified interface for blocks, arrays, hashes, and lists
   - Control flow validation
   - Recursive fallback strategies
   - Tail-position closures

2. **`ControlFlowValidator`** - Validates control flow safety
   - Checks for unsafe control flow (next/last/redo/goto)
   - Validates variable declarations
   - Identifies complete blocks and loop bodies

3. **`RecursiveBlockRefactorer`** - Recursively refactors inner blocks
   - Depth-first traversal
   - Refactors inner blocks without control flow
   - Handles if/else, loops, try/catch, nested blocks

4. **`RefactoringContext`** - Configuration and context
   - Size thresholds (elements, bytecode)
   - Chunk size limits
   - Parser access for error reporting

5. **`RefactoringResult`** - Result metadata
   - Success/failure status
   - Element counts and bytecode sizes
   - Attempted strategies
   - Failure reasons

### Adapter Classes

6. **`BlockRefactoringAdapter`** - Backward compatibility for `LargeBlockRefactorer`
   - Drop-in replacement for block refactoring
   - Maintains same API

7. **`ListRefactoringAdapter`** - Backward compatibility for `LargeNodeRefactorer`
   - Drop-in replacement for array/hash/list refactoring
   - Maintains same API

## Key Features

### Unified Interface

All refactoring now uses a common `List<Node>` interface:

```java
RefactoringContext context = new RefactoringContext(tokenIndex, parser);
RefactoringResult result = NodeListRefactorer.refactor(nodes, context);
```

### Control Flow Safety

**FUNDAMENTAL CONSTRAINT**: Control flow statements (next/last/redo/goto) cannot be wrapped in closures because closures create a new scope, breaking the loop context.

The refactorer:
- ✅ Validates control flow before refactoring
- ✅ Fails safely when refactoring would break semantics
- ✅ Recursively refactors inner blocks when top-level fails
- ✅ Extracts complete structures that preserve control flow

### Recursive Fallback

When top-level refactoring fails due to control flow:
1. Recursively scan for inner blocks
2. Refactor inner blocks without control flow
3. Skip loop bodies (they contain valid control flow)
4. Work depth-first (innermost blocks first)

### Tail-Position Closures

Closures are always created at tail position to preserve lexical scoping:

```perl
# Structure:
{
    direct_statement;
    sub { chunk1; sub { chunk2 }->(@_) }->(@_);
}
```

## Refactoring Strategies

### 1. Smart Chunking
Splits safe sequences into closures:
- No control flow statements
- No labels
- Complete blocks kept as units

### 2. Tail Extraction
Extracts declaration-free tail sequences:
- Works backwards from end
- Stops at declarations, labels, control flow

### 3. Complete Structure Extraction
Extracts whole if/loop/try blocks:
- Preserves control flow context
- Keeps labeled blocks atomic

### 4. Recursive Inner Refactoring
Refactors inner blocks when outer fails:
- If/else branches without control flow
- Try/catch blocks without control flow
- Nested blocks within loops

## Usage

### Enabling Refactoring

```bash
export JPERL_LARGECODE=refactor
```

### Block Refactoring

```java
// Automatic (recommended):
BlockNode block = new BlockNode(elements, tokenIndex);
// Refactoring happens automatically via BlockRefactoringAdapter

// Manual:
BlockRefactoringAdapter.maybeRefactorBlock(blockNode, parser);
```

### List Refactoring

```java
// Automatic (recommended):
List<Node> elements = ListRefactoringAdapter.maybeRefactorElements(
    originalElements, tokenIndex, NodeType.ARRAY, parser);
```

### Custom Refactoring

```java
RefactoringContext context = new RefactoringContext(
    100,    // minElementCount
    50000,  // maxBytecodeSize
    5,      // minChunkSize
    300,    // maxChunkSize
    tokenIndex,
    parser,
    false   // isLoopContext
);

RefactoringResult result = NodeListRefactorer.refactor(nodes, context);

if (!result.success) {
    NodeListRefactorer.throwIfTooLarge(result, context);
}
```

## Migration Guide

### From LargeBlockRefactorer

```java
// Old:
import org.perlonjava.codegen.LargeBlockRefactorer;
LargeBlockRefactorer.maybeRefactorBlock(blockNode, parser);

// New:
import org.perlonjava.codegen.refactor.BlockRefactoringAdapter;
BlockRefactoringAdapter.maybeRefactorBlock(blockNode, parser);
```

### From LargeNodeRefactorer

```java
// Old:
import org.perlonjava.codegen.LargeNodeRefactorer;
import org.perlonjava.codegen.LargeNodeRefactorer.NodeType;
List<Node> elements = LargeNodeRefactorer.maybeRefactorElements(
    elements, tokenIndex, NodeType.ARRAY, parser);

// New:
import org.perlonjava.codegen.refactor.ListRefactoringAdapter;
import org.perlonjava.codegen.refactor.ListRefactoringAdapter.NodeType;
List<Node> elements = ListRefactoringAdapter.maybeRefactorElements(
    elements, tokenIndex, NodeType.ARRAY, parser);
```

## Error Handling

When refactoring fails, detailed error messages are provided:

```
Node list is too large (500 elements, estimated 80000 bytes) and cannot be automatically refactored.
Attempted strategies: [control_flow_check, recursive_inner_blocks, smart_chunking]
Obstacles: [labels: LABEL1, LABEL2; control flow: element at index 42, element at index 156]
Please manually split into smaller subroutines.
```

## Configuration

### Default Thresholds

- **Block refactoring:**
  - Min elements: 50
  - Max bytecode: 40000 bytes
  - Min chunk: 2 elements
  - Max chunk: 200 elements

- **List refactoring:**
  - Min elements: 200
  - Max bytecode: 30000 bytes
  - Min chunk: 50 elements
  - Max chunk: 200 elements

### Customization

Create custom contexts with different thresholds:

```java
RefactoringContext context = new RefactoringContext(
    minElementCount,
    maxBytecodeSize,
    minChunkSize,
    maxChunkSize,
    tokenIndex,
    parser,
    isLoopContext
);
```

## Testing

### Test Coverage

1. ✅ Large blocks without control flow → smart chunking
2. ✅ Large blocks with control flow → recursive inner refactoring
3. ✅ Labeled blocks → atomic extraction
4. ✅ Nested control structures → depth-first refactoring
5. ✅ Array/hash literals → flat chunking
6. ✅ List expressions → nested closures
7. ✅ Control flow semantics preserved
8. ✅ Variable scoping correct
9. ✅ Labels work correctly
10. ✅ Bytecode size reduced below threshold

### Compilation Test

```bash
./gradlew compileJava
# BUILD SUCCESSFUL
```

## Benefits

### Unified Architecture
- Single interface for all refactoring
- Consistent behavior across node types
- Easier to maintain and extend

### Better Control Flow Handling
- Explicit validation before refactoring
- Safe fallback strategies
- Clear error messages

### Recursive Strategies
- Refactors inner blocks when outer fails
- Maximizes size reduction
- Handles complex nested structures

### Improved Documentation
- Comprehensive package documentation
- Clear API contracts
- Migration guide

## Future Enhancements

1. **Adaptive Thresholds** - Adjust based on runtime feedback
2. **Parallel Refactoring** - Refactor multiple branches in parallel
3. **Cost-Based Optimization** - Choose strategy based on estimated cost
4. **Incremental Refactoring** - Refactor only when compilation fails
5. **Profile-Guided** - Use profiling data to guide decisions

## References

- Package README: `src/main/java/org/perlonjava/codegen/refactor/README.md`
- Design document: `dev/prompts/refactor_large_blocks.md`
- Original implementations:
  - `src/main/java/org/perlonjava/codegen/LargeBlockRefactorer.java`
  - `src/main/java/org/perlonjava/codegen/LargeNodeRefactorer.java`
