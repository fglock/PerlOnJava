# Fallback Refactoring for Large Blocks with Control Flow

## Problem Statement

The `LargeBlockRefactorer` currently fails completely when a large block contains control flow statements (`next`, `last`, `redo`, `goto`) or labels. 

**FUNDAMENTAL CONSTRAINT**: Control flow statements cannot be wrapped in closures because closures create a new scope, breaking the loop context. A `next` statement inside a closure will fail with "Can't 'next' outside a loop block" even if the closure is called from within a loop.

This document describes a fallback mechanism to refactor blocks by working around control flow statements rather than wrapping them.

## Current Behavior

Located in `src/main/java/org/perlonjava/codegen/LargeBlockRefactorer.java`:

- Lines 162-169: Early exit if any control flow detected in block
- Lines 214-227: Throws error if block remains too large after failed refactoring

## Key Constraints

### Variable Scoping

**Only extract code that doesn't introduce variables needed by later statements:**

- ✅ **Tail-position statements** - nothing comes after, so their variables don't matter
- ✅ **Declaration-free statements** - don't introduce new variables with `my`/`our`/`local`
- ❌ **Non-tail statements with declarations** - subsequent code may depend on those variables

### Control Flow Semantics

- `next`/`last`/`redo` must target the correct loop after refactoring
- Labels must stay at the correct scope level
- Labeled blocks must be extracted as atomic units (label + block together)

### Labeled vs Unlabeled Blocks

**Unlabeled block with trailing control flow - CAN split:**
```perl
# Original:
{
    statement_A;
    statement_B;
    next;  # targets outer loop
}

# Refactored:
{
    sub { statement_A; statement_B }->(@_);
    next;  # still targets outer loop correctly
}
```

**Labeled block - must extract as unit:**
```perl
# Original:
LABEL: {
    statement_A;
    next if $check;  # targets LABEL
}

# Refactored (extract entire structure):
sub {
    LABEL: {
        statement_A;
        next if $check;  # still targets LABEL correctly
    }
}->(@_);
```

## Revised Approach: Reduce Chunk Size Instead

Since control flow statements cannot be wrapped in closures, the fallback strategies cannot work as originally designed. Instead, we need a different approach:

**Solution**: Make the normal chunking more aggressive by reducing the chunk size. This creates more, smaller closures from blocks that DON'T contain control flow, which indirectly helps by reducing the overall method size.

### Strategy 0: Recursive Inner Block Refactoring (FIRST PASS)

Before attempting any top-level refactoring, recursively scan and refactor inner blocks within control structures that DON'T contain control flow. Inner blocks without control flow can be safely refactored.

**Example - SAFE to refactor:**
```perl
if ($pass == 0) {
    # Inner if block without control flow - CAN be refactored!
    if (@commonArgs and not defined $argsLeft) { 
        $argsLeft = scalar(@ARGV) + scalar(@moreArgs);
        unshift @ARGV, @commonArgs;
        undef @commonArgs unless $argsLeft;
        # No control flow here
    }
    ...
}
```

**Example - UNSAFE to refactor:**
```perl
if ($pass == 0) {
    if (@commonArgs and not defined $argsLeft) { 
        $argsLeft = scalar(@ARGV) + scalar(@moreArgs);
        next;  # Control flow - CANNOT wrap in closure!
    }
    ...
}
```

**Where to scan for inner blocks:**
- `IfNode` - scan `thenBranch` and `elseBranch` blocks
- `For1Node`/`For3Node` - scan loop body block
- `TryNode` - scan try/catch blocks
- `BlockNode` - scan nested blocks
- `LabelNode` + `BlockNode` - scan the labeled block

**Algorithm:** Work depth-first (innermost blocks first), applying normal chunking only to blocks without control flow.

### Strategy 1: Reduce MIN_CHUNK_SIZE

Make the normal chunking more aggressive by reducing `MIN_CHUNK_SIZE` from 4 to 2 or 3. This creates more, smaller closures from safe code sequences.

### Strategy 2: Skip Blocks with Control Flow

Do NOT attempt to refactor blocks that contain `next`/`last`/`redo`/`goto` statements. These blocks must remain as-is to preserve loop context.

### Strategy 3: Recursive Refactoring of Safe Inner Blocks

Recursively refactor inner blocks (within if statements, loops, etc.) that don't contain control flow. This reduces the size of the overall method even if the top-level block can't be refactored.

**Example:**
```perl
# Original loop body with control flow - cannot refactor
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

## Revised Implementation Plan

### Modified Approach

1. **Reduce `MIN_CHUNK_SIZE`** from 4 to 2 to create smaller closures
2. **Skip refactoring for blocks with control flow** - return early if control flow detected
3. **Recursively refactor inner blocks** that don't have control flow

### Key Changes to LargeBlockRefactorer.java

1. **`trySmartChunking(BlockNode node, Parser parser)`**
   - If control flow detected, return false (don't refactor)
   - Otherwise proceed with normal chunking using smaller MIN_CHUNK_SIZE

2. **`recursivelyRefactorInnerBlocks(Node node)`** (NEW)
   - Depth-first traversal of control structures
   - For each inner BlockNode, check if it has control flow
   - If no control flow, apply normal chunking to it
   - Visit: IfNode branches, For1Node/For3Node bodies, TryNode blocks, nested BlockNodes

3. **Configuration**
   - Change `MIN_CHUNK_SIZE` from 4 to 2
   - This makes chunking more aggressive for blocks without control flow

### Modified Logic Flow

```java
trySmartChunking(BlockNode node, Parser parser):
    // Check for unsafe control flow at top level
    if (hasUnsafeControlFlow()) {
        // Don't refactor blocks with control flow
        // But still try to refactor inner blocks that don't have control flow
        return recursivelyRefactorInnerBlocks(node);
    }
    // ... existing chunking logic with MIN_CHUNK_SIZE = 2

recursivelyRefactorInnerBlocks(Node node):
    boolean improved = false;
    
    if (node instanceof IfNode ifNode) {
        // Recursively process branches
        improved |= recursivelyRefactorInnerBlocks(ifNode.thenBranch);
        if (ifNode.elseBranch != null) {
            improved |= recursivelyRefactorInnerBlocks(ifNode.elseBranch);
        }
        
        // Try to refactor branches if they don't have control flow
        if (ifNode.thenBranch instanceof BlockNode thenBlock) {
            if (!hasUnsafeControlFlow(thenBlock)) {
                improved |= applyNormalChunking(thenBlock);
            }
        }
        if (ifNode.elseBranch instanceof BlockNode elseBlock) {
            if (!hasUnsafeControlFlow(elseBlock)) {
                improved |= applyNormalChunking(elseBlock);
            }
        }
    }
    else if (node instanceof For1Node loop) {
        // Don't refactor loop bodies - they contain valid control flow
        improved |= recursivelyRefactorInnerBlocks(loop.body);
    }
    else if (node instanceof For3Node loop) {
        // Don't refactor loop bodies - they contain valid control flow
        improved |= recursivelyRefactorInnerBlocks(loop.body);
    }
    else if (node instanceof BlockNode block) {
        for (Node element : block.elements) {
            improved |= recursivelyRefactorInnerBlocks(element);
        }
    }
    
    return improved;
```

## Success Criteria

- Reduce block size by at least 20% (configurable threshold)
- If still too large after all fallback strategies, throw error with helpful message listing:
  - Attempted strategies
  - Obstacles found (labels, control flow statements)
  - Suggestion to manually split into smaller subroutines

## Error Handling

If all fallback strategies fail and block is still too large:

```
Block is too large and cannot be automatically refactored.
Attempted strategies: [recursive inner blocks, tail extraction, complete structures, trailing control flow, declaration-free sequences]
Obstacles: [labels: LABEL1, LABEL2; control flow: next at line X, last at line Y]
Please manually split into smaller subroutines.
```

## Testing Considerations

1. Test with exiftool's large blocks containing `next`/`last`
2. Verify control flow semantics preserved after refactoring
3. Verify variable scoping - declarations visible to subsequent statements
4. Test labeled blocks extracted as atomic units
5. Test nested control structures with inner block refactoring
