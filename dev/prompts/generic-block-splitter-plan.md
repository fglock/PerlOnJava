# Generic Block Splitter for JVM Bytecode Limits

**Date:** 2025-10-22  
**Problem:** TestProp.pl (190K lines) has 42K-line if-blocks that exceed JVM's 64KB method bytecode limit  
**Current Status:** Smart chunking helps but doesn't split block bodies  
**Solution:** Generic block splitter that works for ANY large block structure

---

## Problem Analysis

### Current Limitation
```perl
if (!$::TESTCHUNK or $::TESTCHUNK == 1) {
    # 42,000 lines of Expect() calls here!
    # Lines 391-42,218
}
```

**Current refactorer:**
- ✅ Splits sequences of statements into chunks
- ❌ Doesn't split bodies of if/while/for/etc blocks
- ❌ Treats the entire if-body as one unit → too large!

### Why Generic Block Splitter?

Instead of special-casing each control structure (if, while, for, foreach, etc.),
create **ONE** generic mechanism that works for all block types.

**Key Insight:** All control structures have similar structure:
```
CONTROL_KEYWORD (CONDITION) { BODY }
```

The BODY is just a BlockNode - we can split ANY BlockNode!

---

## Architecture

### Phase 1: Block Size Estimation
Before emitting any block, estimate its bytecode size:
```
BlockNode → estimate bytecode size → if > threshold → split
```

### Phase 2: Generic Block Splitting
Split any BlockNode into multiple sub-blocks:
```perl
# Original
if (condition) {
    statement1;
    statement2;
    ...
    statement1000;
}

# Split into
if (condition) {
    sub { statement1; statement2; ... statement100; }->();
    sub { statement101; ... statement200; }->();
    ...
    sub { statement901; ... statement1000; }->();
}
```

### Phase 3: Preserve Semantics
Ensure splitting doesn't break:
- Variable scope
- Control flow (return, last, next, redo)
- Exception handling
- Context (scalar/list/void)

---

## Implementation Strategy

### Step 1: Add Bytecode Size Estimator

**File:** `src/main/java/org/perlonjava/codegen/BytecodeEstimator.java` (NEW)

```java
public class BytecodeEstimator {
    /**
     * Estimate bytecode size for a node without actually emitting it.
     * 
     * Returns approximate bytecode size based on:
     * - Node type (if = 10 bytes, while = 15 bytes, etc.)
     * - Number of child nodes
     * - Nesting depth
     * 
     * Conservative: overestimate to ensure we stay under 64KB
     */
    public static int estimateSize(Node node) {
        // Recursive estimation
        int size = getBaseSize(node);
        for (Node child : node.getChildren()) {
            size += estimateSize(child);
        }
        return size;
    }
    
    private static int getBaseSize(Node node) {
        // Conservative estimates per node type
        // ...
    }
}
```

**Why estimator?**
- Can't wait until emission fails (too late)
- Need to decide BEFORE we start emitting
- Estimation allows proactive splitting

### Step 2: Generic Block Splitter

**File:** `src/main/java/org/perlonjava/codegen/GenericBlockSplitter.java` (NEW)

```java
public class GenericBlockSplitter {
    private static final int MAX_BLOCK_SIZE = 30000; // Conservative (64KB limit)
    private static final int MIN_CHUNK_SIZE = 10;     // Statements per chunk
    
    /**
     * Split a BlockNode if it's too large.
     * 
     * @param block The block to potentially split
     * @return The same block (if small) or a new block with chunked statements
     */
    public static BlockNode splitIfNeeded(BlockNode block) {
        int estimatedSize = BytecodeEstimator.estimateSize(block);
        
        if (estimatedSize < MAX_BLOCK_SIZE) {
            return block; // No splitting needed
        }
        
        // Split the block
        return splitBlock(block);
    }
    
    private static BlockNode splitBlock(BlockNode block) {
        List<Node> newElements = new ArrayList<>();
        List<Node> currentChunk = new ArrayList<>();
        int currentChunkSize = 0;
        
        for (Node element : block.elements) {
            int elementSize = BytecodeEstimator.estimateSize(element);
            
            // If single element is too large, recursively split it
            if (elementSize > MAX_BLOCK_SIZE && element instanceof BlockNode) {
                // Flush current chunk
                if (!currentChunk.isEmpty()) {
                    newElements.add(createChunkClosure(currentChunk, block.tokenIndex));
                    currentChunk.clear();
                    currentChunkSize = 0;
                }
                
                // Recursively split the large element
                Node splitElement = splitIfNeeded((BlockNode) element);
                newElements.add(splitElement);
                continue;
            }
            
            // Add to current chunk
            currentChunk.add(element);
            currentChunkSize += elementSize;
            
            // Chunk is full, flush it
            if (currentChunkSize >= MAX_BLOCK_SIZE || currentChunk.size() >= MIN_CHUNK_SIZE * 3) {
                newElements.add(createChunkClosure(currentChunk, block.tokenIndex));
                currentChunk.clear();
                currentChunkSize = 0;
            }
        }
        
        // Flush remaining elements
        if (!currentChunk.isEmpty()) {
            if (currentChunk.size() < MIN_CHUNK_SIZE && !newElements.isEmpty()) {
                // Too small for a chunk, merge into last chunk
                // (Would need to extract from last closure - complex)
                // For now, just create tiny chunk
            }
            newElements.add(createChunkClosure(currentChunk, block.tokenIndex));
        }
        
        return new BlockNode(newElements, block.tokenIndex);
    }
    
    /**
     * Create a closure that executes immediately: sub { ... }->()
     * This creates a new lexical scope and keeps bytecode small.
     */
    private static Node createChunkClosure(List<Node> statements, int tokenIndex) {
        // Create: sub { statements }->()
        BlockNode chunkBlock = new BlockNode(new ArrayList<>(statements), tokenIndex);
        
        SubroutineNode sub = new SubroutineNode(
            null,  // anonymous
            null,  // no signature
            null,  // no attributes
            chunkBlock,
            false, // not a method
            tokenIndex
        );
        
        // Call it immediately with no args: ->()
        return new BinaryOperatorNode(
            "->",
            sub,
            new ListNode(tokenIndex), // empty args
            tokenIndex
        );
    }
}
```

### Step 3: Integration Points

**Modify:** `src/main/java/org/perlonjava/codegen/EmitOperator.java`

```java
// In emitIf(), emitWhile(), emitFor(), etc.
public void emitIf(IfNode node) {
    // BEFORE emitting the if block body:
    BlockNode thenBlock = GenericBlockSplitter.splitIfNeeded(node.thenBlock);
    BlockNode elseBlock = node.elseBlock != null 
        ? GenericBlockSplitter.splitIfNeeded(node.elseBlock) 
        : null;
    
    // Now emit with potentially split blocks
    // ... rest of emitIf logic
}
```

**Apply to ALL control structures:**
- if/elsif/else
- while/until
- for/foreach
- do/while
- eval blocks
- sub bodies (already handled, but reinforce)

### Step 4: Handle Control Flow

**Challenge:** Closures create new scope, breaking return/last/next/redo

```perl
# Original
for my $i (1..10) {
    if ($i > 5) { last; }  # Works
}

# After splitting (BROKEN)
for my $i (1..10) {
    sub {
        if ($i > 5) { last; }  # ERROR: Can't "last" outside a loop!
    }->();
}
```

**Solution 1: Control Flow Detection**
```java
// Before creating closure, check if block contains control flow
if (ControlFlowDetectorVisitor.hasLoopControl(statements)) {
    // Don't split - keep as one block
    return createSingleBlock(statements);
}
```

**Solution 2: Label-Based Control Flow**
```perl
# Transform
BLOCK: {
    sub {
        if ($i > 5) { last BLOCK; }  # Works with label!
    }->();
}
```

### Step 5: Handle Returns

```perl
sub foo {
    if (condition) {
        # 42K lines
        return 42;  # Must return from foo(), not from closure!
    }
}
```

**Solution: Return Value Passing**
```perl
sub foo {
    if (condition) {
        my $result = sub {
            # 42K lines
            return 42;  # Returns from closure
        }->();
        return $result if defined $result;  # Propagate return
    }
}
```

**Better Solution: Don't Split Sub Bodies**
- Subs are already split (separate method)
- Only split main-level code and block bodies
- Skip if parent is SubroutineNode

---

## Implementation Plan

### Phase 1: Foundation (2-3 hours)
1. ✅ Create `BytecodeEstimator.java`
   - Conservative size estimates for each node type
   - Test with known-size examples
   
2. ✅ Create `GenericBlockSplitter.java`
   - Basic splitting logic
   - Closure creation
   - Unit tests

### Phase 2: Integration (2-3 hours)
3. ✅ Integrate with `LargeBlockRefactorer`
   - Call `GenericBlockSplitter.splitIfNeeded()` before emission
   - Replace current chunking logic
   
4. ✅ Update all control structure emitters
   - EmitOperator: if, while, for, foreach, do-while
   - Ensure all BlockNodes are checked

### Phase 3: Control Flow Safety (3-4 hours)
5. ✅ Enhance `ControlFlowDetectorVisitor`
   - Detect: return, last, next, redo, goto
   - Track which structures are safe to split
   
6. ✅ Implement safe splitting rules
   - Skip blocks with problematic control flow
   - Or use label-based workaround
   - Document limitations

### Phase 4: Testing (2-3 hours)
7. ✅ Test with TestProp.pl
   - `JPERL_LARGECODE=refactor ./jperl -c lib/unicore/TestProp.pl`
   - Should compile without "Method too large"
   
8. ✅ Test with existing test suite
   - Ensure no regressions
   - All tests still pass

9. ✅ Edge cases
   - Nested control structures
   - Mixed control flow
   - Large subs (shouldn't split)

### Phase 5: Documentation (1 hour)
10. ✅ Document the feature
    - How it works
    - When it triggers
    - Limitations
    - Performance impact

---

## Success Criteria

### Must Have
- ✅ TestProp.pl compiles with `JPERL_LARGECODE=refactor`
- ✅ All existing tests still pass (no regressions)
- ✅ Generic - works for any control structure

### Should Have
- ✅ Handles control flow correctly (last/next/return)
- ✅ Minimal performance overhead (only for large blocks)
- ✅ Conservative - only splits when necessary

### Nice to Have
- ✅ Automatic (no manual intervention)
- ✅ Transparent (original code behavior unchanged)
- ✅ Debuggable (clear source mapping)

---

## Risk Analysis

### High Risk: Control Flow
**Problem:** Closures break loop control (last/next/redo) and returns

**Mitigation:**
1. Detect control flow and skip splitting
2. Use label-based workarounds
3. Document limitations clearly

### Medium Risk: Scope Issues
**Problem:** Closures create new lexical scope

**Mitigation:**
- Closures called immediately capture outer scope
- No new variables in closure
- Test variable accessibility

### Low Risk: Performance
**Problem:** Extra closure overhead

**Mitigation:**
- Only applies to huge blocks (rare)
- Closure overhead minimal vs compilation failure
- Benefits outweigh costs

---

## Alternatives Considered

### Alternative 1: Special-case if-bodies
**Pros:** Simpler, targeted at TestProp.pl
**Cons:** Need to repeat for while/for/foreach/etc. Not generic.
**Verdict:** ❌ Rejected - not maintainable

### Alternative 2: Preprocess TestProp.pl
**Pros:** One-time fix for this specific file
**Cons:** Doesn't solve the general problem
**Verdict:** ❌ Rejected - not a compiler solution

### Alternative 3: Split at compilation
**Pros:** Current approach (selected!)
**Cons:** More complex, requires AST transformation
**Verdict:** ✅ Selected - proper compiler solution

### Alternative 4: Generate smaller bytecode
**Pros:** Would reduce size without splitting
**Cons:** Requires complete bytecode rewrite
**Verdict:** ❌ Rejected - too much work

---

## Implementation Order

```
Day 1 (Foundation):
├─ BytecodeEstimator.java (1-2 hours)
│  ├─ Basic size estimation
│  └─ Unit tests
└─ GenericBlockSplitter.java (2-3 hours)
   ├─ Split logic
   ├─ Closure creation
   └─ Unit tests

Day 2 (Integration):
├─ LargeBlockRefactorer integration (1-2 hours)
├─ EmitOperator updates (2-3 hours)
│  ├─ if/elsif/else
│  ├─ while/until
│  ├─ for/foreach
│  └─ do-while
└─ Initial testing (1 hour)

Day 3 (Control Flow):
├─ ControlFlowDetectorVisitor enhancement (2-3 hours)
├─ Safe splitting rules (2-3 hours)
└─ Testing with control flow (1 hour)

Day 4 (Testing & Polish):
├─ TestProp.pl testing (1 hour)
├─ Full test suite run (2 hours)
├─ Edge cases (2 hours)
└─ Documentation (1 hour)
```

**Total Estimated Time:** 20-26 hours (~3-4 days)

---

## Measurement

### Before Implementation
```bash
JPERL_LARGECODE=refactor ./jperl -c lib/unicore/TestProp.pl
# Result: Method too large error at line 395
```

### After Implementation
```bash
JPERL_LARGECODE=refactor ./jperl -c lib/unicore/TestProp.pl
# Result: lib/unicore/TestProp.pl syntax OK
```

### Regression Testing
```bash
make test-all
# Result: All tests pass (or same failures as before)
```

---

## Open Questions

1. **Should we split sub bodies?**
   - Probably NO - subs are already separate methods
   - Only split main-level and block bodies

2. **What's the size threshold?**
   - Start with 30KB (conservative, half of 64KB limit)
   - Tune based on testing

3. **How to handle return values from closures?**
   - Option A: Don't split blocks with returns
   - Option B: Propagate return values explicitly
   - Decision: Start with A, implement B if needed

4. **Performance impact on small files?**
   - Only check blocks with > 100 elements (fast)
   - Estimation is O(n) where n = number of nodes
   - Minimal impact

---

## Next Steps

1. **Review this plan** - Get feedback before implementation
2. **Create branch** - `feature/generic-block-splitter`
3. **Implement Phase 1** - BytecodeEstimator + unit tests
4. **Iterate** - One phase at a time with testing
5. **Merge** - After all phases complete and tested

---

## Related Files

### Will Create
- `src/main/java/org/perlonjava/codegen/BytecodeEstimator.java`
- `src/main/java/org/perlonjava/codegen/GenericBlockSplitter.java`
- `src/test/java/org/perlonjava/codegen/BytecodeEstimatorTest.java`
- `src/test/java/org/perlonjava/codegen/GenericBlockSplitterTest.java`

### Will Modify
- `src/main/java/org/perlonjava/codegen/LargeBlockRefactorer.java`
- `src/main/java/org/perlonjava/codegen/EmitOperator.java`
- `src/main/java/org/perlonjava/astvisitor/ControlFlowDetectorVisitor.java`

### Will Reference
- `lib/unicore/TestProp.pl` (test case)
- `t/re/uniprops*.t` (integration tests)

---

**Status:** ✅ Plan complete, ready for review  
**Estimated Effort:** 20-26 hours  
**Risk Level:** Medium (control flow handling)  
**Value:** High (solves TestProp.pl + general problem)

