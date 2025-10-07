# TODO: Parser Fixes

## High Priority Parser Issues

### 1. Package::->method() Syntax Not Supported
**Status**: Identified, not yet fixed
**Impact**: Test failures in pack.t (test 24) and potentially other tests

**Problem**:
```perl
Math::BigInt::->new(5000000000)  # Fails - not parsed correctly
Math::BigInt->new(5000000000)     # Works - standard syntax
```

The `Package::->method()` syntax is valid Perl (equivalent to `Package->method()`) 
but PerlOnJava's parser doesn't recognize it.

**Example Failure**:
- File: t/op/pack.t, line 160
- Code: `$y = pack('w*', Math::BigInt::->new(5000000000));`
- Error: `Undefined subroutine &Math::BigInt:: called`

**Test Files Affected**:
- t/op/pack.t (at least test 24)
- Potentially other tests using this syntax pattern

**Priority**: Medium (workaround exists - use standard `->` syntax)

**Location to Fix**: 
- Likely in the parser/lexer handling of `::->` token sequence
- Need to treat `Package::->` as equivalent to `Package->`

**Workaround**: 
Tests can be modified to use standard `Package->method()` syntax, but proper
support would improve Perl compatibility.

---

## Future Parser Enhancements

(Add more parser issues as they are discovered)
