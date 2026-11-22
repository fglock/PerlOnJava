# Fix Dynamic Eval String Warning Handler Inheritance

## Objective
Fix 30 test failures in op/infnan.t where dynamically constructed eval strings don't inherit the `$SIG{__WARN__}` handler, preventing numeric warnings from being captured.

## Current Status
- **Test file:** t/op/infnan.t
- **Failures:** 30 tests (all "numify warn" tests)
- **Pass rate:** Currently 90% (would be ~93% after fix)
- **Pattern:** All failures are for malformed Inf/NaN strings like "infxy", "nanxy", "nan34"

## Root Cause Analysis

### The Problem
Warnings are not captured when eval strings are **dynamically constructed** (using string concatenation), even though they work correctly for:
1. Literal eval strings: `eval 'my $x = "infxy" + 1'` ✅
2. Eval blocks: `eval { my $x = "infxy" + 1 }` ✅
3. Dynamically constructed strings: `eval '$a = "' . $value . '" + 1'` ❌

### Evidence
Test case demonstrates the issue clearly:
```perl
$SIG{__WARN__} = sub { $w = shift };

# Works - captures warning
eval 'my $x = "infxy" + 1';

# Fails - doesn't capture warning
my $str = '$a = "infxy" + 1';
eval $str;
```

### Technical Investigation
1. Warnings ARE being generated correctly (seen with --disassemble)
2. The WarnDie.warn() method IS being called
3. The issue is that dynamically compiled eval code doesn't inherit the current `$SIG{__WARN__}` handler

## Why Simple Fixes Don't Work

### Attempt 1: Check evalStringHelper context
- The evalStringHelper method creates a new EmitterContext from a snapshot
- The symbolTable snapshot might not include the current signal handlers
- Signal handlers are stored in GlobalVariable, not in the symbol table

### Attempt 2: Copy signal handlers
- Would require passing current %SIG state to evalStringHelper
- This breaks the caching mechanism (cacheKey would need to include %SIG state)
- Performance impact would be significant

## Implementation Strategy

### Phase 1: Understand Current Architecture
1. How are signal handlers stored? (GlobalVariable.getGlobalHash("main::SIG"))
2. How does eval string compilation access globals?
3. Why do literal eval strings work but dynamic ones don't?

### Phase 2: Implement Fix
**Option A: Runtime Context Inheritance**
- Pass current runtime context to dynamically compiled code
- Ensure %SIG is accessible from compiled eval code
- Maintain compatibility with caching

**Option B: Compile-time Binding**
- Bind signal handlers at eval compilation time
- Store handler references in compiled code
- May require changes to EmitterContext

### Phase 3: Testing
1. Verify all 30 infnan.t tests pass
2. Check for regressions in other eval-related tests
3. Test performance impact on eval string compilation

## Testing Strategy

### Minimal Test Case
```perl
#!/usr/bin/perl
use strict;
use warnings;

my $captured = '';
$SIG{__WARN__} = sub { $captured = $_[0] };

# Test dynamic eval
my $code = '$x = "infxy" + 1';
eval $code;

if ($captured =~ /isn't numeric/) {
    print "PASS\n";
} else {
    print "FAIL: Warning not captured\n";
}
```

## Expected Impact
- **Tests fixed:** 30 tests in op/infnan.t
- **Pass rate improvement:** 90% → 93% (+3%)
- **Side benefits:** All dynamic eval string warning capture would be fixed
- **Potential regressions:** None expected if implemented correctly

## Complexity Assessment
- **Estimated effort:** 2-4 hours
- **Risk level:** Medium (affects core eval mechanism)
- **Files to modify:** 
  - RuntimeCode.java (evalStringHelper)
  - EmitterContext.java (context passing)
  - Possibly WarnDie.java (signal handler lookup)

## Recommendation
**Priority:** HIGH (30 tests, clear pattern)
**Approach:** Defer for now and move to simpler targets
**Reason:** Complex architectural issue requiring deep understanding of eval compilation

This is a valuable fix but requires careful implementation to avoid breaking the eval caching mechanism or causing performance regressions. The issue is well-documented here for future implementation.

## Alternative Quick Fix
If we just want the tests to pass without fixing the underlying issue:
- Modify the test to use literal eval strings instead of dynamic construction
- This would be a test-only change, not fixing the actual bug
- Not recommended as it masks a real compatibility issue
