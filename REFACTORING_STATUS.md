# Depth-First Literal Refactoring Investigation

## Status

**Branch**: `depth-first-literal-refactor`

### What Works ✅
1. **Depth-first refactoring eliminates "Method too large" errors**
   - ExifTool compiles successfully (without refactoring: compilation fails)
   - All unit tests pass
   - Large structures refactored: 1190, 14376, 11698 element ListNodes

2. **Refactoring approach is sound**
   - Uses Visitor pattern for clean AST traversal
   - Refactors innermost structures first (depth-first)
   - Simple test cases work correctly

### Current Issue ❌

**Runtime error during ExifTool test #2**:
```
Can't use an undefined value as a SCALAR reference
at /Users/fglock/projects/PerlOnJava/Image-ExifTool-13.44/lib/Image/ExifTool.pm line 229
```

**Key Finding**:
- Without depth-first refactoring: "Method too large" (compilation fails)
- With depth-first refactoring: Runtime error (compilation succeeds, execution fails)

This confirms refactoring FIXES compilation but introduces a semantic bug.

## Investigation Attempts

### Tested Patterns
1. ✅ Simple hashes with closures: `%h = (key1 => 'val', sub { ... }->())`
2. ✅ Nested closures work correctly
3. ✅ Hash references preserved through refactoring
4. ✅ Package-level hashes work
5. ✅ Large hashes (600+ entries) work when refactored
6. ❌ **Cannot reproduce error with simplified tests**

### Hypothesis
The error occurs in a specific context during ExifTool execution:
- Line 229: `%fileTypeLookup = (` (hash assignment)
- Called from line 1274: `Groups => \%allGroupsExifTool,` (hash ref value)
- During image processing, not module loading

The refactored code may have issues when:
- Hash is accessed/modified after creation
- Hash contains mixed value types (array refs, strings, hash refs)
- Hash is used in specific runtime contexts

## Next Steps

1. **Create reproducer**: Need to isolate exact code pattern that fails
   - Copy failing file section by section
   - Strip down until error disappears
   - Identify minimal failing case

2. **Debug refactored output**:
   - Print generated AST structure
   - Compare with expected structure
   - Use `--disassemble` to check bytecode

3. **Check closure semantics**:
   - Verify `sub { ... }->(@_)` behavior in all contexts
   - Check if `@_` handling is correct
   - Verify list flattening works correctly

## Configuration

Current thresholds:
- `MIN_ELEMENTS_FOR_REFACTORING = 500` (only refactor massive structures)
- `LARGE_BYTECODE_SIZE = 40000` (from BlockRefactor)
- MAX_CHUNK_SIZE = 200 (in LargeNodeRefactorer)

## Files Modified

- `DepthFirstLiteralRefactorVisitor.java` - New depth-first visitor
- `LargeNodeRefactorer.java` - Added `forceRefactorElements()` method
- `EmitterMethodCreator.java` - Calls depth-first refactoring on error

## Test Commands

```bash
# Test ExifTool (shows runtime error)
cd Image-ExifTool-13.44 && ../jperl t/ExifTool.t

# Test simple cases (work fine)
./jperl /tmp/test_hash_simple.pl
./jperl /tmp/test_closure_pattern.pl

# Check refactoring debug output
./jperl <script> 2>&1 | grep "DEBUG:"
```
