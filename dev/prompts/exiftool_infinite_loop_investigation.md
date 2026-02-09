# Investigation: ExifTool Infinite Loop During Compilation

## Summary
ExifTool compilation triggers an infinite loop creating thousands of anonymous classes (anon4341+) before hitting method size limits.

## Root Cause Analysis

### The Problem
1. Exiftool's main script declares 164 `my` variables at file scope
2. When refactoring encounters "method too large" error, it creates nested closures
3. Each closure captures ALL 164 visible variables from outer scope  
4. The closure constructor with 164 parameters itself becomes huge
5. When compiling that closure, it hits "method too large" again
6. Triggers another refactoring cycle, creating more nested closures
7. Process repeats infinitely

### Why Grep Block Triggers It
```perl
if (grep /^-common_args$/i, @ARGV) {
```
This simple grep only uses the implicit `$_` variable, but the anonymous sub block captures all 164 outer variables unnecessarily.

## Potential Solutions

### 1. Variable Capture Optimization (HIGH IMPACT but COMPLEX)
- Scan subroutine AST to find which variables are actually referenced
- Only capture those variables in the closure
- **Challenge**: Requires remapping local variable indices throughout the symbol table and AST
- **Benefit**: Dramatically reduces closure size (164 vars -> ~1 var for grep example)

### 2. Refactoring Size Verification (MEDIUM IMPACT, SAFER)
- Before creating each closure chunk, verify the chunk size
- If a single chunk is still > LARGE_BYTECODE_SIZE, don't wrap it in a closure
- Prevents creating closures that will immediately trigger another refactoring
- **Benefit**: Breaks the infinite loop without changing variable semantics

### 3. Recursion Detection (LOW IMPACT, DEFENSIVE)
- Add compilation depth counter in `emitSubroutine`
- Throw clear error when depth exceeds threshold
- **Benefit**: Fails fast with helpful error instead of consuming resources

### 4. Skip Refactoring Flag (LOW IMPACT, DEFENSIVE)
- Check `skipRefactoring` ThreadLocal in `forceRefactorElements`  
- Prevents recursive refactoring during BlockNode creation
- **Benefit**: Avoids some edge cases of nested refactoring

## Recommended Approach
Implement solutions #2, #3, and #4 together:
- #3 and #4 are simple defensive measures (low risk)
- #2 prevents the core issue without complex variable remapping
- Defer #1 (variable capture optimization) for future work as it requires careful index remapping

## Files to Modify
- `EmitSubroutine.java`: Add recursion detection (#3)
- `LargeNodeRefactorer.java`: Add skipRefactoring check (#4), chunk size verification (#2)
- `EmitterMethodCreator.java`: Optional - add "already refactored" annotation check

## Implementation Status (Complete)

### Successfully Implemented:

1. **Recursion Detection** ✅
   - Detects infinite loops at depth 50
   - Provides clear error message with common causes
   - File: `EmitSubroutine.java`

2. **Skip Refactoring Flag** ✅  
   - Prevents recursive refactoring during BlockNode creation
   - File: `LargeNodeRefactorer.java`

3. **Chunk Size Verification** ✅
   - Checks if largest chunk > 70% of limit before creating closures
   - Returns original elements if refactoring won't help
   - File: `LargeNodeRefactorer.java`

4. **Variable Reference Visitor** ✅ (Infrastructure Only)
   - Implemented `VariableReferenceCollector` using Visitor pattern
   - Correctly identifies all variable references in closures
   - File: `EmitSubroutine.java`
   - Status: Working but filtering disabled (see below)

### Variable Capture Optimization - Blocked:

**Problem**: AST contains variable references with indices from the outer symbol table.
When we create a new inner symbol table with only used variables, those indices
become invalid, causing VerifyError at runtime.

**Root Cause**: The AST block is parsed in the outer scope context before we know
which variables the closure will use. Variable lookups are index-based, not name-based.

**Attempted Solutions**:
1. ❌ Filter visibleVariables map - breaks indices completely  
2. ❌ Create filtered usedVariables map - AST still references old indices
3. ❌ Add only used variables to new scope - lookups still use old indices

**Required Solution** (Future Work):
- Implement name-based variable resolution instead of index-based, OR
- Implement full AST traversal to remap all variable indices after filtering

### Test Results:

- ✅ All unit tests pass
- ✅ ExifTool fails quickly (depth 51) with clear error instead of infinite loop
- ✅ Simple closures work correctly
- ⚠️  ExifTool still cannot compile (needs variable capture optimization)

### Conclusion:

The defensive measures successfully prevent infinite loops and provide clear error
messages. The variable capture optimization requires deeper architectural changes
to variable resolution that are beyond the scope of this fix.

## Why Variable Capture Optimization Is Complex

### The Index Problem (SOLVED):

The original blocker was that AST nodes contain SymbolEntry objects with baked-in indices from parse time. When we filter variables to create a new symbol table, those indices become invalid.

**Solution Implemented**: EmitVariable.java now does name-based lookup instead of using baked-in indices:
```java
SymbolTable.SymbolEntry currentEntry = emitterVisitor.ctx.symbolTable.getSymbolEntry(symbolEntry.name());
int currentIndex = (currentEntry != null) ? currentEntry.index() : symbolEntry.index();
mv.visitVarInsn(Opcodes.ALOAD, currentIndex);
```

This allows the filtered symbol table to have different indices than the parse-time AST. The lookup finds the correct index in the current symbol table.

### The Bytecode Corruption Problem (NEW BLOCKER):

While name-based lookup solves the index problem, enabling filtering triggers a different issue:

**Error**: VerifyError: Bad local variable type - "Type null (current frame, locals[2]) is not assignable to integer"
**Location**: Inside generated closure apply() methods (e.g., anon2.apply)
**Symptom**: Slot 2 (the wantarray parameter) shows as null instead of integer

**Root Cause**: Unknown. Filtering changes the constructor signature (fewer captured variable parameters), which somehow corrupts the bytecode generation for the apply() method's local variable slots.

**Possible Causes**:
1. Local variable slot allocation issue - the slot counter might be incorrectly reset
2. Frame map computation - the JVM's stack map frames might be calculated wrong
3. Parameter slot overlap - filtered constructor might not properly account for apply() method parameters
4. Scope boundary issue - variables declared within the closure body might get wrong slots

### Files Modified:

- **EmitVariable.java**: Added name-based lookup for variable indices (lines 376-383)
- **EmitSubroutine.java**: Variable filtering infrastructure in place but disabled (line 150-159)
- **ScopedSymbolTable.java**: Added getCurrentScopeEntry() method for current-scope-only lookup

### What Works:

- Name-based variable index lookup ✓
- All unit tests pass with filtering disabled ✓
- VariableReferenceCollector correctly identifies used variables ✓
- Infrastructure ready for when bytecode corruption is fixed ✓
