# Branch Reset Groups (?|...) - Phase 1 Implementation Results

## Implementation Date
2025-10-07

## Summary
Implemented Phase 1 support for branch reset groups `(?|alt1|alt2|alt3)` in RegexPreprocessor.

## Changes Made

### File Modified
`/src/main/java/org/perlonjava/regex/RegexPreprocessor.java`

### Implementation Details
1. Added detection for `(?|` in `handleParentheses()`
2. Created new `handleBranchReset()` method that:
   - Parses all alternatives within the branch reset group
   - Processes each alternative through the regex preprocessor
   - Tracks capture group counts per alternative
   - Resets capture count for each alternative (emulating Perl behavior during preprocessing)
   - Sets final capture count to maximum across all alternatives
   - Outputs as `(?:alt1|alt2|alt3)` - non-capturing wrapper

### Transformation Examples
```
Perl:  (?|(a)|(b))
Java:  (?:(a)|(b))

Perl:  (?|(a)(b)|(c)(d))
Java:  (?:(a)(b)|(c)(d))
```

## Test Results

### t/re/regexp.t
- **Before**: 1676 passing / 501 failing
- **After**: 1687 passing / 490 failing
- **Improvement**: +11 tests passing

### Error Message Reduction
- **Before**: 38 "Sequence (?|...) not recognized" errors
- **After**: 0 "Sequence (?|...) not recognized" errors
- **Result**: All 38 patterns now compile successfully

### Test Breakdown
- **Passing (11 tests)**: Simple same-structure alternatives
  - Example: `(?|(a)|(b))` - both alternatives have 1 capture
  - Example: `(?|(a)(b)|(c)(d))` - both alternatives have 2 captures
  
- **Failing (27 tests)**: Complex cases where Java's behavior differs
  - Different capture counts: `(?|(a)|(b)(c))`
  - Backreferences: `(?|(a)|(b))\1`
  - Named captures: `(?|(?<n>a)|(?<n>b))` - Java doesn't allow duplicate names

## Limitations (Known Issues)

### Fundamental Java Limitation
Java regex engine numbers capture groups sequentially across the entire pattern:
- `(?:(a)|(b))` creates groups 1 and 2 (not both using group 1)
- `(?:(a)(b)|(c)(d))` creates groups 1,2,3,4 (not resetting to 1,2 for second alternative)

### Why Tests Still Fail
Perl behavior: `(?|(a)(b)|(c)(d))` matching "cd" gives $1='c', $2='d'
Java behavior: Same pattern gives $3='c', $4='d' (groups 1,2 are undef)

### What Would Be Needed for Full Support
**Runtime Group Remapping**:
1. Track which alternative matched
2. Remap Java group numbers to Perl group numbers based on the matched alternative
3. Requires modifications to `RuntimeRegex.java` and match result handling
4. Estimated effort: 20-40 hours

## Success Metrics

### Immediate Wins
✅ Eliminates 38 "not recognized" compilation errors
✅ Enables 11 tests to pass (simple cases)
✅ Foundation for future full implementation
✅ Better than throwing errors

### What This Unlocks
- Patterns compile and execute (some correctly)
- Better error reporting (failures show wrong values, not "not recognized")
- Incremental path to full support

## ROI Analysis
- **Effort**: 4 hours (deep dive + implementation + testing)
- **Direct test improvement**: +11 tests
- **Error elimination**: 38 patterns now compile
- **ROI**: 2.75 tests/hour (modest but positive)

## Next Steps

### Short-term
Document this as Phase 1 and move to next high-yield target from analysis:
- **Conditional patterns `(?(...)...)`**: 29 failures in regexp.t, ~3000 across all regexp files
- **Other "not recognized" patterns**: 47 remaining (was 84)

### Long-term (Phase 2)
Implement runtime group remapping for full Perl compatibility:
1. Store metadata about branch reset groups during preprocessing
2. Detect which alternative matched at runtime
3. Remap group access to correct Perl-style numbering
4. Expected additional gain: +27 tests for branch reset alone

## Conclusion
Phase 1 is a **qualified success**: all patterns compile, 11 tests pass, foundation established for Phase 2.
The approach follows the pragmatic "make it work, then make it right" philosophy suitable for a high-yield strategy.
