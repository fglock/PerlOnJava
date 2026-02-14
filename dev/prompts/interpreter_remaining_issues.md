# Interpreter Remaining Issues

## Current Status
- 50+ tests passing in demo.t
- 8 out of 9 subtests fully passing
- 1 subtest with minor failure

## Failing Tests

### 1. Splice scalar context (1 test failing)
**Issue**: `splice` in scalar context returns RuntimeList instead of last element
- Expected: `'7'` (last removed element)
- Got: `'97'` (stringified list of removed elements)
- **Root cause**: SLOWOP_SPLICE returns RuntimeList, needs context-aware conversion
- **Fix needed**: Compiler should track context and convert appropriately

Test code:
```perl
my @arr = (4, 8, 9, 7);
my $result = splice @arr, 2, 2;  # Should return 7, not '97'
```

### 2. done_testing() error
**Issue**: Test framework hits "Not a CODE reference" error at end
- Occurs in Test::Builder framework code (line 368)
- Error happens when calling `done_testing()` at line 295
- May be related to compiled code calling interpreter code or vice versa

## Successfully Passing
✅ Variable assignment (2/2)
✅ List assignment in scalar context (13/13)
✅ List assignment with lvalue array/hash (16/16)
✅ Basic syntax tests (13/13)
⚠️  Splice tests (8/9 - one scalar context issue)
✅ Map tests (2/2)
✅ Grep tests (2/2)
✅ Sort tests (5/5) - **FIXED!**
✅ Object tests (2/2)

## Recently Fixed
✅ **Sort without block** - Fixed package-qualified variable access in BytecodeCompiler
  - Issue: Auto-generated comparison block `{ $main::a cmp $main::b }` wasn't removing $ sigil
  - Fix: Always remove sigil before storing global variable name in string pool
  - Now matches codegen behavior: `GlobalVariable.getGlobalVariable("main::a")`

## Next Steps
1. Fix splice to be context-aware
2. Debug done_testing() CODE reference error

