# Parser Bug: `scalar @array[indices]` Precedence

## Problem
`scalar @c[()]` is parsed incorrectly, causing VerifyError in t/op/list.t

## Root Cause
Parser treats `scalar @c[()]` as `(scalar @c)[()]` instead of `scalar(@c[()])`

**AST shows:**
```
BinaryOperatorNode: [
  OperatorNode: scalar
    OperatorNode: @
      IdentifierNode: 'c'
  ArrayLiteralNode: (empty)
```

Should be:
```
OperatorNode: scalar
  BinaryOperatorNode: [
    OperatorNode: @
      IdentifierNode: 'c'
    ArrayLiteralNode: (empty)
```

## Impact
- Blocks t/op/list.t from running (crashes at line 140)
- Test expects: `scalar @c[()]` returns count of empty slice (0)
- We get: `(scalar @c)` returns count, then tries to slice that scalar

## Workaround
Use parentheses: `scalar(@c[()])`

## Status
**Parser issue** - requires fix in operator precedence handling, not codegen.

## Test Case
```perl
my @c = (1,2,3);
my $x = scalar @c[()];  # Should be 0, causes VerifyError
my $y = scalar(@c[()]); # Works, returns 0
```
