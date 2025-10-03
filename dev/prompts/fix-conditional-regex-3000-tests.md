# Fix Conditional Regex Pattern (?(condition)yes|no) - Unlock ~3000 Tests

## Problem Statement
The PerlOnJava regex preprocessor incorrectly handles conditional regex patterns `(?(condition)yes|no)`, causing 499 identical test failures across 6 regexp test files (re/regexp.t, re/regexp_noamp.t, re/regexp_notrie.t, re/regexp_qr.t, re/regexp_qr_embed.t, re/regexp_trielist.t), totaling approximately 3000 test failures.

## Root Cause Analysis

### The Bug
The `handleConditionalPattern` method in `RegexPreprocessor.java` has an incomplete implementation that:
1. Converts `(?(1)yes|no)` to `(?:yes|no)` (losing conditional semantics but allowing parsing)
2. When called from `handleParentheses`, uses `return handleConditionalPattern(...)` which exits the entire function
3. This breaks nested groups like `((?(1)a|b))` because the outer group's processing is cut short

### Specific Issue
In `RegexPreprocessor.java` line 350:
```java
} else if (c3 == '(') {
    // Handle (?(condition)yes|no) conditionals
    return handleConditionalPattern(s, offset, length, sb, regexFlags);
```

The `return` statement exits `handleParentheses` entirely, which means:
- For `((?(1)a|b))`, the outer `(` never finds its matching `)`
- Results in "Unmatched (" errors

## Investigation Process

### Test Cases Created
1. `test_regexp_issues.pl` - Tests various conditional patterns
2. `test_conditional_debug.pl` - Focused debugging of nested conditionals

### Key Findings
- Simple `(?(1)a|b)` converts correctly to `(?:a|b)`
- Nested `((?(1)a|b))` fails with "Unmatched (" error
- Debug output shows `handleConditionalPattern` returns position 10, skipping the outer group's `)`

## Solution

### Immediate Fix
Change line 350 from:
```java
return handleConditionalPattern(s, offset, length, sb, regexFlags);
```
To follow the pattern used by other constructs:
```java
offset = handleConditionalPattern(s, offset, length, sb, regexFlags);
```

However, this alone won't work because `handleConditionalPattern` was designed to be called directly, not nested.

### Complete Fix
The issue is more complex because `handleConditionalPattern`:
1. Processes the entire conditional pattern including its closing `)`
2. Returns `pos + 1` which skips past the conditional's closing `)`
3. When nested, this causes the outer group to miss its closing `)`

The fix requires careful handling of the return position to ensure nested groups work correctly.

## Implementation Notes

### Current Workaround
The current implementation converts `(?(condition)yes|no)` to `(?:yes|no)`, which:
- Allows the pattern to parse without errors
- Loses the conditional semantics (always matches both branches)
- Is marked with TODO for proper implementation

### Proper Implementation (Future)
To fully support conditional regex, we would need:
1. Track capture group states during matching
2. Implement conditional branching logic in the regex engine
3. This is a significant undertaking requiring regex engine modifications

## Testing

### Before Fix
- re/regexp.t: 499 failures (1678/2177 passing)
- Same 499 failures in 5 other regexp test files
- Total: ~3000 test failures

### Expected After Fix
- All 6 regexp test files should have 499 fewer failures
- Total improvement: ~3000 tests

## File Modifications
- `/Users/fglock/projects/PerlOnJava/src/main/java/org/perlonjava/regex/RegexPreprocessor.java`
  - Line 350: Change return to assignment
  - `handleConditionalPattern` method: Adjust return position handling

## Debug Commands
```bash
# Test simple and nested conditionals
./jperl test_conditional_debug.pl

# Count failures in regexp tests
./jperl t/re/regexp.t 2>&1 | grep "^not ok" | wc -l

# Run all 6 affected test files
for test in regexp regexp_noamp regexp_notrie regexp_qr regexp_qr_embed regexp_trielist; do
    echo "Testing t/re/$test.t"
    ./jperl t/re/$test.t 2>&1 | grep "^not ok" | wc -l
done
```

## Priority
**HIGH** - This single fix will resolve ~3000 test failures, representing one of the highest-impact fixes possible in the project.

## Current Status
- Root cause identified
- Solution approach determined
- Implementation in progress
- Debug output added for verification

## Notes
- The conditional regex pattern `(?(condition)yes|no)` is a Perl 5.10+ feature
- Java's Pattern class doesn't natively support conditionals
- Current workaround loses conditional semantics but prevents parsing errors
- Full support would require significant regex engine enhancements
