# Fix Hash Assignment Scalar Context Bug

## Objective
Fix the bug where hash assignment in scalar context returns the hash size instead of the source list element count.

## Background Context

### The Bug
```perl
my %h = (1,2,1,3,1,4,1,5);  # 8 elements in source list, 1 unique key
my $count = scalar(%h = (1,2,1,3,1,4,1,5));
# Expected: 8 (number of elements in source list)
# Got: 2 (number of key-value pairs in hash, i.e., 1 key * 2)
```

### Test Case
A test file exists at `test_hash_assign_scalar.pl` that demonstrates the bug:
- Test 1: `(1,2,1,3,1,4,1,5)` returns 2 (wrong), should return 8
- Test 3: `(1,2,3)` returns 4 (wrong), should return 3

### Current Behavior
- **Scalar context:** Returns hash size (number of unique keys * 2)
- **List context:** Returns hash contents (unique key-value pairs) ✅ Correct

### Expected Behavior (Perl standard)
- **Scalar context:** Returns source list element count
- **List context:** Returns hash contents (unique key-value pairs)

### Test Impact
- **28 tests failing** in `t/op/hashassign.t`
- Test file has 90% pass rate (281/309 passing)
- All failures related to scalar/list context behavior

## Technical Details

### Root Cause
The `setFromList()` method in `RuntimeBase` (implemented by `RuntimeHash`, `RuntimeArray`, etc.) doesn't distinguish between scalar and list context.

**Current implementation in RuntimeHash.java (lines 113-120):**
```java
public RuntimeArray setFromList(RuntimeList value) {
    return switch (type) {
        case PLAIN_HASH -> {
            RuntimeHash hash = createHash(value);
            this.elements = hash.elements;
            yield new RuntimeArray(this);  // Always returns hash, not source list
        }
        // ...
    }
}
```

### How Context is Passed in PerlOnJava
Most operators accept an `int ctx` parameter for context-aware behavior:
- `RuntimeContextType.SCALAR` (1) - Scalar context
- `RuntimeContextType.LIST` (2) - List context  
- `RuntimeContextType.VOID` (0) - Void context
- `RuntimeContextType.RUNTIME` (3) - Runtime-determined context

**Example from RuntimeHash.each():**
```java
public RuntimeList each(int ctx) {
    // Uses ctx parameter to determine behavior
}
```

### Bytecode Generation
The emitter uses `pushCallContext()` to pass context to runtime methods.

**Current call in EmitVariable.java (line 362):**
```java
mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeBase", 
    "setFromList", "(Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeArray;", false);
```

## The Challenge: RuntimeBase Hierarchy

**CRITICAL:** `setFromList()` is defined in `RuntimeBase` and implemented by multiple classes:

### Classes that implement setFromList():
1. **RuntimeHash.java** (2 calls) - PRIMARY TARGET
2. **RuntimeArray.java** (2 calls)
3. **RuntimeGlob.java** (3 calls)
4. **RuntimeStashEntry.java** (2 calls)
5. **StateVariable.java** (2 calls)
6. **PerlRange.java** (2 calls)
7. **RuntimeCode.java** (1 call)
8. **RuntimeFormat.java** (1 call)
9. **RuntimeList.java** (1 call)
10. **RuntimeScalar.java** (1 call)
11. **RuntimeRegex.java** (1 call)

### Classes that CALL setFromList():
- **DBI.java** (2 calls)
- **Exporter.java** (2 calls)
- **Lib.java** (1 call)
- **YAMLPP.java** (1 call)
- Plus all the implementing classes above

**Total: 16+ files need updates**

## Implementation Strategy

### Phase 1: Update RuntimeBase Method Signature

**File:** `src/main/java/org/perlonjava/runtime/RuntimeBase.java`

1. Find the `setFromList()` method declaration
2. Add `int ctx` parameter:
```java
public RuntimeArray setFromList(RuntimeList value, int ctx) {
    // Default implementation
}
```

### Phase 2: Update RuntimeHash Implementation

**File:** `src/main/java/org/perlonjava/runtime/RuntimeHash.java` (lines 113-128)

```java
public RuntimeArray setFromList(RuntimeList value, int ctx) {
    return switch (type) {
        case PLAIN_HASH -> {
            RuntimeHash hash = createHash(value);
            this.elements = hash.elements;
            // Return value depends on context:
            // SCALAR: return source list (for proper element count)
            // LIST/VOID: return hash contents (unique key-value pairs)
            yield (ctx == RuntimeContextType.SCALAR) 
                ? new RuntimeArray(value) 
                : new RuntimeArray(this);
        }
        case AUTOVIVIFY_HASH -> {
            AutovivificationHash.vivify(this);
            yield this.setFromList(value, ctx);  // Pass ctx through
        }
        case TIED_HASH -> {
            TieHash.tiedClear(this);
            Iterator<RuntimeScalar> iterator = value.iterator();
            RuntimeArray result = new RuntimeArray();
            while (iterator.hasNext()) {
                RuntimeScalar key = iterator.next();
                RuntimeScalar val = iterator.hasNext() ? new RuntimeScalar(iterator.next()) : new RuntimeScalar();
                TieHash.tiedStore(this, key, val);
                RuntimeArray.push(result, key);
                RuntimeArray.push(result, val);
            }
            // For tied hash, return based on context
            yield (ctx == RuntimeContextType.SCALAR)
                ? new RuntimeArray(value)
                : result;
        }
    };
}
```

### Phase 3: Update Bytecode Emitter

**File:** `src/main/java/org/perlonjava/codegen/EmitVariable.java` (line 362)

Change from:
```java
mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeBase", 
    "setFromList", "(Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeArray;", false);
```

To:
```java
emitterVisitor.pushCallContext();  // Push context onto stack
mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeBase", 
    "setFromList", "(Lorg/perlonjava/runtime/RuntimeList;I)Lorg/perlonjava/runtime/RuntimeArray;", false);
```

### Phase 4: Update All Other Implementing Classes

For each class that implements `setFromList()`, update the signature to include `int ctx`:

**RuntimeArray.java:**
```java
public RuntimeArray setFromList(RuntimeList value, int ctx) {
    // Most likely just pass through, arrays don't have the same context issue
    // But signature must match RuntimeBase
}
```

**RuntimeGlob.java, RuntimeStashEntry.java, StateVariable.java, etc.:**
- Update method signature to include `int ctx`
- Pass `ctx` through to any recursive calls
- Most implementations can ignore `ctx` and maintain current behavior

### Phase 5: Update All Callers

For each file that CALLS `setFromList()`:

1. Determine the appropriate context:
   - If in a scalar context operation: `RuntimeContextType.SCALAR`
   - If in a list context operation: `RuntimeContextType.LIST`
   - If context doesn't matter: `RuntimeContextType.VOID`

2. Add the context parameter to the call:
```java
// Before
someHash.setFromList(someList);

// After
someHash.setFromList(someList, RuntimeContextType.LIST);
```

## Testing Strategy

### Step 1: Test with Isolated Test Case
```bash
./jperl test_hash_assign_scalar.pl
```
All 4 tests should pass.

### Step 2: Test with Full Test Suite
```bash
./jperl t/op/hashassign.t 2>&1 | grep -c "^ok"
./jperl t/op/hashassign.t 2>&1 | grep -c "^not ok"
```
Expected: 309 passing, 0 failing (currently 281 passing, 28 failing)

### Step 3: Verify No Regressions
Run a broader test to ensure the changes don't break other functionality:
```bash
# Test a few key test files
./jperl t/op/hash.t
./jperl t/op/array.t
./jperl t/base/lex.t
```

## Implementation Checklist

- [ ] Phase 1: Update `RuntimeBase.setFromList()` signature
- [ ] Phase 2: Update `RuntimeHash.setFromList()` implementation
- [ ] Phase 3: Update `EmitVariable.java` bytecode generation
- [ ] Phase 4: Update all implementing classes (10+ files)
- [ ] Phase 5: Update all calling code (5+ files)
- [ ] Test with `test_hash_assign_scalar.pl`
- [ ] Test with `t/op/hashassign.t`
- [ ] Verify no regressions in other tests
- [ ] Commit with comprehensive message

## Expected Results

**Before fix:**
- `t/op/hashassign.t`: 281 passing / 28 failing

**After fix:**
- `t/op/hashassign.t`: 309 passing / 0 failing
- **Impact: 28 tests fixed** ✅

## Complexity Assessment

**Difficulty:** HIGH - Requires systematic refactoring across entire RuntimeBase hierarchy

**Time Estimate:** 1-2 hours for careful implementation and testing

**Risk:** MODERATE - Changes affect core runtime behavior, thorough testing required

## Tips for Success

1. **Start with RuntimeBase** - Get the signature right first
2. **Use grep to find all occurrences** - Don't miss any calls
3. **Test incrementally** - Compile after each phase
4. **Most classes can ignore ctx** - Only RuntimeHash needs special handling
5. **Use RuntimeContextType constants** - Don't use magic numbers
6. **Check bytecode signature** - The `I` in the signature means integer parameter

## Technical Investigation Results (2025-09-30)

### Actual Bug Discovered

**Initial assumption was WRONG.** The document focused on scalar context, but investigation revealed:
- ✅ **Scalar context works correctly** in PerlOnJava (returns 8)
- ❌ **List context is broken** in PerlOnJava (returns original list instead of deduplicated hash)

### Root Cause Analysis

**The Bytecode Flow:**
1. `setFromList()` is called and returns `RuntimeArray` containing the hash
2. In **list context**: `RuntimeList.add(RuntimeArray)` is called
3. In **scalar context**: `RuntimeArray.scalar()` is called

**The Problem:**
`RuntimeList.add(RuntimeArray)` at line 128-130 adds the array as a **single element**:
```java
public void add(RuntimeArray value) {
    this.elements.add(value);  // Adds array itself, not its contents!
}
```

Compare with `RuntimeList` handling at lines 117-118:
```java
if (value instanceof RuntimeList list) {
    this.elements.addAll(list.elements);  // Flattens list!
}
```

### Why Simple Fixes Don't Work

**Attempted Fix 1:** Flatten arrays in `RuntimeList.add(RuntimeArray)`
```java
public void add(RuntimeArray value) {
    for (RuntimeScalar element : value) {
        this.elements.add(element);  // Flatten
    }
}
```

**Result:**
- ✅ List context: Works (returns deduplicated pairs)
- ❌ Scalar context: Breaks (returns 2 instead of 8)

**Why it fails:** By flattening, the `RuntimeArray` now has 2 elements, so `.scalar()` returns 2 instead of the source list count (8).

### Why Context Parameter is THE Solution

**The Fundamental Issue:**
`setFromList()` needs to return **different values** based on context:
- **Scalar context:** Return source list (for element count: 8)
- **List context:** Return deduplicated hash (for key-value pairs: 1, 5)

**This is impossible without context information!**

A single return value cannot satisfy both requirements:
- If we return `new RuntimeArray(value)` → scalar context gets wrong count
- If we return `new RuntimeArray(this)` → list context gets wrong elements (current behavior)

**The Only Clean Solution:**
```java
public RuntimeArray setFromList(RuntimeList value, int ctx) {
    RuntimeHash hash = createHash(value);
    this.elements = hash.elements;
    
    // Return value depends on context:
    return (ctx == RuntimeContextType.SCALAR) 
        ? new RuntimeArray(value)      // Source list for count
        : new RuntimeArray(this);       // Deduplicated hash for iteration
}
```

### Why This Requires Large Refactoring

`setFromList()` is defined in `RuntimeBase` and implemented by 11+ classes. Changing the signature requires:
1. Update `RuntimeBase.setFromList()` signature
2. Update all 11+ implementing classes
3. Update all calling code (5+ files)
4. Update bytecode emitter to pass context

**Total impact:** 16+ files

### Alternative Approaches Considered

1. **Store source list size in RuntimeArray** - Requires modifying RuntimeArray structure
2. **Return special wrapper object** - Breaks existing code expecting RuntimeArray
3. **Use thread-local context** - Fragile, hard to maintain
4. **Flatten in RuntimeList.add()** - Breaks scalar context (tested and failed)

**Conclusion:** Context parameter is the cleanest, most maintainable solution.

## Reference Files

- **Test case:** `test_hash_assign_scalar.pl`, `test_hash_context_detailed.pl`
- **Main test suite:** `t/op/hashassign.t` (273 passing, 26 failing - 88% pass rate)
- **Key implementation:** `src/main/java/org/perlonjava/runtime/RuntimeHash.java`
- **Problem location:** `src/main/java/org/perlonjava/runtime/RuntimeList.java` (line 128-130)
- **Bytecode emitter:** `src/main/java/org/perlonjava/codegen/EmitVariable.java`
- **Context constants:** `src/main/java/org/perlonjava/runtime/RuntimeContextType.java`

## Recommendation

This is a **high-impact fix** (26 tests) but requires **significant refactoring** (16+ files). 

**Estimated effort:** 1-2 hours for careful implementation and testing.

**Priority:** DEFER - Create this prompt document for future work when time allows for large refactoring.

Good luck! This is a high-impact fix that will improve Perl compatibility significantly. 🚀
