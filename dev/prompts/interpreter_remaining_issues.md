# Interpreter Remaining Issues

## Current Status
- 50+ tests passing in demo.t
- 7 out of 9 subtests fully passing
- 2 subtests with minor failures

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

### 2. Sort without block (1 test failing)
**Issue**: `sort` without block doesn't sort at all
- Expected: `'apple monkey zebra'` (alphabetically sorted)
- Got: `'zebra apple monkey'` (original order)
- **Root cause**: Sort implementation doesn't handle default string comparison
- **Fix needed**: When no block provided, default to `$a cmp $b` behavior

Test code:
```perl
my @sorted = sort qw(zebra apple monkey);  # Should default to cmp
```

### 3. done_testing() error
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
⚠️  Sort tests (4/5 - sort without block issue)
✅ Object tests (2/2)

## Next Steps
1. Fix splice to be context-aware
2. Fix sort default comparison
3. Debug done_testing() CODE reference error
