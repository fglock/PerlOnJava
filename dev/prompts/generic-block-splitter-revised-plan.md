# Generic Block Splitter - REVISED PLAN (Much Simpler!)

**Date:** 2025-10-22  
**Discovery:** Most infrastructure ALREADY EXISTS!  
**Actual Work Needed:** ~4-6 hours instead of 20-26 hours

---

## What Already Exists ✅

### 1. `BytecodeSizeEstimator.java` (COMPLETE!)
```java
public static int estimateSize(Node ast)
```
- **Location:** `src/main/java/org/perlonjava/astvisitor/BytecodeSizeEstimator.java`
- **Status:** ✅ Scientifically calibrated (R² = 1.0000)
- **Accuracy:** 99.6-100% for large methods
- **Formula:** actual = 1.035 × estimated + 1950
- **Usage:** Just call `BytecodeSizeEstimator.estimateSize(node)`

### 2. `ControlFlowDetectorVisitor.java` (COMPLETE!)
```java
public boolean hasUnsafeControlFlow()
```
- **Location:** `src/main/java/org/perlonjava/astvisitor/ControlFlowDetectorVisitor.java`
- **Status:** ✅ Detects: next, last, redo, goto
- **Usage:** Already used in `LargeBlockRefactorer.tryWholeBlockRefactoring()`

### 3. `LargeBlockRefactorer.java` (MOSTLY COMPLETE!)
```java
private static Node createChunkClosure(List<Node> statements, int tokenIndex)
```
- **Location:** `src/main/java/org/perlonjava/codegen/LargeBlockRefactorer.java`
- **Status:** ✅ Creates closures: `sub { ... }->()`
- **What works:** Smart chunking, whole-block refactoring
- **What's missing:** Only checks element count, not bytecode size

### 4. AST Node Structure (COMPLETE!)
- ✅ `SubroutineNode` - for creating subs
- ✅ `BinaryOperatorNode` - for `->` operator
- ✅ `BlockNode` - for bodies
- ✅ All control structure nodes have `body` fields

---

## What's Actually Missing ❌

### Problem 1: Not Using BytecodeSizeEstimator
**Current code (LargeBlockRefactorer.java:78-81):**
```java
private static boolean shouldRefactorBlock(BlockNode node, ...) {
    // Check element count threshold
    if (node.elements.size() <= LARGE_BLOCK_ELEMENT_COUNT) {
        return false;
    }
    // ...
}
```

**Fix:** Add bytecode size check:
```java
private static boolean shouldRefactorBlock(BlockNode node, ...) {
    // Check element count first (fast check)
    if (node.elements.size() <= LARGE_BLOCK_ELEMENT_COUNT) {
        return false;
    }
    
    // For blocks that pass element count, check actual bytecode size
    int estimatedBytes = BytecodeSizeEstimator.estimateSize(node);
    if (estimatedBytes < LARGE_BYTECODE_SIZE) {
        return false; // Block is large by count but small by bytecode
    }
    
    return refactorEnabled || !emitterVisitor.ctx.javaClassInfo.gotoLabelStack.isEmpty();
}
```

### Problem 2: Not Splitting Control Structure Bodies
**Current:** Only splits top-level blocks via `processBlock()`

**Missing:** If-body, while-body, for-body are NOT checked/split

**Example - TestProp.pl:**
```perl
if (!$::TESTCHUNK or $::TESTCHUNK == 1) {
    # 42,000 lines here - NEVER gets split!
    Expect(...);
    Expect(...);
    # ... 42,000 more lines
}
```

**Why:** `EmitOperator.emitIf()` emits the body directly without checking size

**Fix:** Add split logic to control structure emitters

---

## Implementation Plan (REVISED)

### Phase 1: Use BytecodeSizeEstimator (30 minutes)

**File:** `src/main/java/org/perlonjava/codegen/LargeBlockRefactorer.java`

**Changes:**
1. Import BytecodeSizeEstimator
2. Modify `shouldRefactorBlock()` to check bytecode size
3. Update `trySmartChunking()` to use bytecode size for chunking decisions

**Code:**
```java
import org.perlonjava.astvisitor.BytecodeSizeEstimator;

// In shouldRefactorBlock:
int estimatedBytes = BytecodeSizeEstimator.estimateSize(node);
if (estimatedBytes < LARGE_BYTECODE_SIZE) {
    return false;
}

// In trySmartChunking (line ~107):
for (Node element : node.elements) {
    int elementSize = BytecodeSizeEstimator.estimateSize(element);
    
    // If single element too large, handle recursively
    if (elementSize > LARGE_BYTECODE_SIZE) {
        // ... existing recursion logic
    }
    
    currentChunkSize += elementSize;
    if (currentChunkSize >= LARGE_BYTECODE_SIZE) {
        // Flush chunk
    }
}
```

### Phase 2: Add splitBlockIfNeeded() Method (1 hour)

**File:** `src/main/java/org/perlonjava/codegen/LargeBlockRefactorer.java`

**New public method:**
```java
/**
 * Split a block if it exceeds bytecode size limits.
 * Public API for use by control structure emitters.
 * 
 * @param block The block to potentially split
 * @param emitterVisitor The emitter context
 * @return The original block (if small) or a modified block with chunks
 */
public static BlockNode splitBlockIfNeeded(BlockNode block, EmitterVisitor emitterVisitor) {
    // Quick check: small blocks don't need splitting
    if (block.elements.size() <= LARGE_BLOCK_ELEMENT_COUNT) {
        return block;
    }
    
    // Check bytecode size
    int estimatedBytes = BytecodeSizeEstimator.estimateSize(block);
    if (estimatedBytes < LARGE_BYTECODE_SIZE) {
        return block;
    }
    
    // Check for control flow issues
    controlFlowDetector.reset();
    block.accept(controlFlowDetector);
    if (controlFlowDetector.hasUnsafeControlFlow()) {
        // Can't split - has last/next/return
        System.err.println("Warning: Block too large but contains control flow. Cannot split safely.");
        return block;
    }
    
    // Split the block using existing smart chunking
    if (trySmartChunking(block)) {
        return block; // Block was modified in place
    }
    
    // Fallback: couldn't split
    return block;
}
```

### Phase 3: Integrate with Control Structure Emitters (2-3 hours)

**Files to modify:**
- `src/main/java/org/perlonjava/codegen/EmitOperator.java`
- Maybe: `src/main/java/org/perlonjava/codegen/EmitBlock.java`

**Pattern to apply:**

**Before (current):**
```java
public void emitIf(IfNode node) {
    // ... emit condition
    node.thenBranch.accept(this); // Emit body directly
    // ... emit else
}
```

**After (with splitting):**
```java
public void emitIf(IfNode node) {
    // ... emit condition
    
    // Split body if too large
    BlockNode thenBody = node.thenBranch instanceof BlockNode
        ? LargeBlockRefactorer.splitBlockIfNeeded((BlockNode) node.thenBranch, this)
        : node.thenBranch;
    
    thenBody.accept(this);
    
    // Same for else branch
    if (node.elseBranch != null) {
        BlockNode elseBody = node.elseBranch instanceof BlockNode
            ? LargeBlockRefactorer.splitBlockIfNeeded((BlockNode) node.elseBranch, this)
            : node.elseBranch;
        elseBody.accept(this);
    }
}
```

**Apply to:**
- `emitIf()` - if/elsif/else branches
- `emitWhile()` - while/until bodies
- `emitFor()` - for/foreach bodies
- `emitDoWhile()` - do-while bodies
- Any other control structures with bodies

### Phase 4: Testing (1-2 hours)

**Test 1: TestProp.pl**
```bash
JPERL_LARGECODE=refactor ./jperl -c lib/unicore/TestProp.pl
# Expected: "lib/unicore/TestProp.pl syntax OK"
```

**Test 2: Existing Test Suite**
```bash
make test-all
# Expected: All tests pass (no regressions)
```

**Test 3: Manual Test with Control Flow**
```perl
# Create test file: test_large_if.pl
if (1) {
    for my $i (1..10000) {
        print "test $i\n";
    }
}
```

**Test 4: Control Flow Rejection**
```perl
# Create test file: test_control_flow.pl
if (1) {
    for my $i (1..10000) {
        last if $i > 5; # Should NOT be split
    }
}
```

### Phase 5: Documentation (30 minutes)

Update `LargeBlockRefactorer.java` javadoc with:
- How BytecodeSizeEstimator is used
- When blocks are split
- Control flow limitations
- Performance characteristics

---

## Revised Time Estimate

| Phase | Task | Old Estimate | New Estimate |
|-------|------|--------------|--------------|
| 1 | BytecodeSizeEstimator | 2-3 hours | ✅ EXISTS (0 hours) |
| 2 | GenericBlockSplitter | 2-3 hours | ✅ EXISTS (0 hours) |
| 3 | ControlFlowDetector | 2-3 hours | ✅ EXISTS (0 hours) |
| 4 | Use BytecodeSizeEstimator | NEW | 30 min |
| 5 | Add splitBlockIfNeeded() | NEW | 1 hour |
| 6 | Integrate with emitters | 2-3 hours | 2-3 hours |
| 7 | Testing | 2-3 hours | 1-2 hours |
| 8 | Documentation | 1 hour | 30 min |
| **TOTAL** | **20-26 hours** | **4-6 hours** |

---

## Implementation Checklist

### Phase 1: Use BytecodeSizeEstimator ✅
- [ ] Import `BytecodeSizeEstimator` in `LargeBlockRefactorer`
- [ ] Modify `shouldRefactorBlock()` to check bytecode size
- [ ] Update `trySmartChunking()` to use bytecode size for chunking
- [ ] Test: Small blocks aren't unnecessarily split

### Phase 2: Add splitBlockIfNeeded() ✅
- [ ] Create public `splitBlockIfNeeded()` method
- [ ] Handle control flow detection
- [ ] Return original or split block
- [ ] Test: Method works standalone

### Phase 3: Integrate with Emitters ✅
- [ ] Update `EmitOperator.emitIf()`
- [ ] Update `EmitOperator.emitWhile()`
- [ ] Update `EmitOperator.emitFor()`  
- [ ] Update `EmitOperator.emitDoWhile()`
- [ ] Search for other control structures with bodies
- [ ] Test: Each structure individually

### Phase 4: Testing ✅
- [ ] TestProp.pl compiles with `JPERL_LARGECODE=refactor`
- [ ] Full test suite passes (no regressions)
- [ ] Manual test with large if-block
- [ ] Manual test with control flow (should NOT split)
- [ ] Verify bytecode size stays under 64KB

### Phase 5: Documentation ✅
- [ ] Update `LargeBlockRefactorer.java` class javadoc
- [ ] Add comments to `splitBlockIfNeeded()`
- [ ] Document control flow limitations
- [ ] Add usage examples

---

## Success Criteria

### Must Have
- ✅ TestProp.pl compiles without "Method too large" error
- ✅ Uses existing `BytecodeSizeEstimator` (no new estimator)
- ✅ Uses existing `ControlFlowDetectorVisitor` (no changes needed)
- ✅ All existing tests pass (no regressions)

### Should Have
- ✅ Works with `JPERL_LARGECODE=refactor` environment variable
- ✅ Automatically splits ANY large control structure body
- ✅ Rejects splitting blocks with unsafe control flow
- ✅ Clear warning messages when can't split

### Nice to Have
- ✅ Minimal changes to existing code (< 200 lines total)
- ✅ Fast (BytecodeSizeEstimator is already optimized)
- ✅ Transparent (users don't need to know it's happening)

---

## Risk Analysis (MUCH LOWER NOW!)

### ~~High Risk: Bytecode Estimation~~ ✅ ELIMINATED
**Status:** Already implemented and scientifically calibrated!

### ~~High Risk: AST Transformation~~ ✅ ELIMINATED
**Status:** Already implemented in `LargeBlockRefactorer`!

### ~~Medium Risk: Control Flow Detection~~ ✅ ELIMINATED
**Status:** Already implemented in `ControlFlowDetectorVisitor`!

### Low Risk: Integration with Emitters
**Problem:** Need to modify multiple emitter methods

**Mitigation:**
- Use consistent pattern across all emitters
- Test each one individually
- Can rollback easily if issues arise

### Low Risk: Performance
**Problem:** Extra bytecode estimation overhead

**Mitigation:**
- Only for blocks > 4 elements (fast check first)
- BytecodeSizeEstimator is already optimized
- Only applies when JPERL_LARGECODE=refactor is set

---

## Key Files

### Will Modify (3 files)
1. `src/main/java/org/perlonjava/codegen/LargeBlockRefactorer.java` (~100 lines added)
2. `src/main/java/org/perlonjava/codegen/EmitOperator.java` (~50 lines added)
3. `src/main/java/org/perlonjava/codegen/EmitBlock.java` (maybe ~20 lines)

### Will Use (no changes needed)
1. `src/main/java/org/perlonjava/astvisitor/BytecodeSizeEstimator.java` ✅
2. `src/main/java/org/perlonjava/astvisitor/ControlFlowDetectorVisitor.java` ✅

### Test Files
1. `lib/unicore/TestProp.pl` (primary test case)
2. `t/re/uniprops*.t` (integration tests)

---

## Next Steps

1. **Review this revised plan** ✅
2. **Start Phase 1** - Use BytecodeSizeEstimator (30 min)
3. **Phase 2** - Add splitBlockIfNeeded() (1 hour)
4. **Phase 3** - Integrate with emitters (2-3 hours)
5. **Phase 4** - Test with TestProp.pl (1-2 hours)
6. **Phase 5** - Document (30 min)

**Total Time:** 4-6 hours instead of 20-26 hours!

---

**Status:** ✅ Revised plan complete - much simpler than expected!  
**Estimated Effort:** 4-6 hours (80% reduction from original estimate)  
**Risk Level:** LOW (most infrastructure already exists)  
**Value:** HIGH (solves TestProp.pl + general problem)  
**Ready to implement:** YES!

