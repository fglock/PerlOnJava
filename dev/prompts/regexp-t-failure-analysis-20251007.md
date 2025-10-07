# t/re/regexp.t Failure Analysis - Deep Dive (2025-10-07)

## Current Status
- **Total tests**: 2177
- **Passing**: 1676 (77%)
- **Failing**: 501 (23%)

## High-Level Failure Categories

Based on error message analysis of all 501 failures:

| Category | Count | % of Failures | Impact |
|----------|-------|---------------|--------|
| **Features Not Implemented** | 117 | 23% | Various regex features |
| **Sequences Not Recognized** | 84 | 17% | (?|...), (?1), (?&...), etc. |
| **Regex Control Verbs** | 43 | 9% | (*ACCEPT), (*PRUNE), (*THEN), etc. |
| **Conditional Patterns** | 29 | 6% | (?(condition)yes\|no) |
| **Illegal Syntax** | 26 | 5% | Various compilation errors |
| **Unmatched Delimiters** | 14 | 3% | Parentheses, brackets |
| **Other/Mixed** | ~188 | 37% | Match behavior, edge cases |

## Detailed Breakdown by Feature

### 1. Branch Reset Groups - (?|...) [38 failures]
**Priority: HIGH** - Most common unrecognized sequence

Pattern: `(?|pattern1|pattern2)`
- Resets capture group numbers for each alternative
- Example: `(?|(a)|(b))` - both alternatives capture to $1

Occurrences:
- 38 instances of `Sequence (?|...) not recognized`
- Affects tests with alternation patterns that share capture groups

**Fix Complexity**: Medium - Requires preprocessor modification to handle branch reset logic

**Example failing patterns**:
```perl
(?|(a)|(b))                           # Simple branch reset
(?|(?<a>a)|(?<b>b))(?(<a>)x|y)\1    # With named captures and conditionals
(?<pre>pre)(?|(?<a>a)(?<b>b)(?<c>c)|(?<d>d)(?<e>e)|(?<f>f))(?<post>post)
(?|a(.)b|d(.(o).)d|i(.)(.)j)(.)     # Complex nested
```

### 2. Regex Control Verbs - (*ACCEPT), (*PRUNE), etc. [43 failures]
**Priority: MEDIUM** - Requires regex engine modifications

Control verbs alter matching behavior:
- `(*ACCEPT)` - Immediately succeeds and ends match
- `(*PRUNE)` - Prevents backtracking past this point
- `(*THEN)` - Causes alternation to skip to next alternative
- `(*FAIL)` - Forces immediate failure
- `(*SKIP)` - Skip ahead in string

**Fix Complexity**: HIGH - Requires deep regex engine integration

**Example failing patterns**:
```perl
(A(A|B(*ACCEPT)|C)+D)(E)           # 4 failures
(A(A|B(*ACCEPT)|C)D)(E)            # 6 failures  
a?(*ACCEPT)b                        # 3 failures
(?:a?(*ACCEPT))b                    # 3 failures
A+?(*PRUNE)BC                       # Multiple PRUNE/THEN variants
```

### 3. Conditional Patterns - (?(condition)yes|no) [29 failures]
**Priority: HIGH** - Already documented in fix-conditional-regex-3000-tests.md

Pattern: `(?(condition)yes|no)`
- Matches 'yes' if condition is true, 'no' otherwise
- Condition can be: group number, named capture, assertion

**Fix Complexity**: HIGH - Requires conditional evaluation in regex engine

**Example failing patterns**:
```perl
^(\(+)?blah(?(1)(\)))$            # 4 failures - Test if group 1 matched
^(\()?blah(?(1)(\)))$             # 4 failures - Similar
^(a(?(1)\1)){4}$                  # 3 failures - Self-referential
```

**Note**: This affects ~3000 tests across 6 regexp files (see fix-conditional-regex-3000-tests.md)

### 4. Recursive Patterns - (?1), (?&name), (?P>name) [33 failures]
**Priority: MEDIUM** - Perl regex extension for recursion

Patterns:
- `(?1)` - Recurse to group 1
- `(?&name)` - Recurse to named group
- `(?P>name)` - Python-style recursion syntax
- `(? +1)` - Relative recursion

**Fix Complexity**: VERY HIGH - Requires recursion engine

**Example failing patterns**:
```perl
^((\w|<(\s)*(?1)(?3)*>)(?:(?3)*\+(?3)*(?2))*)(?3)*\+  # 2 failures - (?1)
^(?<PAL>(?<CHAR>.)((?&PAL)|.?)\k<CHAR>)$              # 2 failures - (?&name)
^(?<PAL>(?<CHAR>.)((?P>PAL)|.?)\k<CHAR>)$             # 2 failures - (?P>name)
(a)(?:(?-1)|(?+1))(b)                                  # 3 failures - (?+1)
```

### 5. Illegal Syntax / Compilation Errors [26 failures]
**Priority: VARIES** - Mix of issues

Breakdown:
- Illegal/unsupported escape sequences: ~13
- Illegal repetition: ~4
- Named capture redefinition: ~3
- Unmatched closing ')': ~5
- Other: ~1

**Fix Complexity**: LOW to MEDIUM - Mostly error handling improvements

Some may be legitimate errors that should fail, others may need special handling.

### 6. Lookbehind Limitations [6 failures]
**Priority: LOW** - Java regex limitation

Pattern: `Lookbehind longer than 255 not implemented`

Java's regex engine has a 255-character limit for lookbehinds. This is a Java limitation, not easily fixable.

Example:
```perl
(?<=([cd](*ACCEPT)|x)gggg)blrph    # 3 failures
(?<!([cd](*ACCEPT)|x)gggg)blrph    # 3 failures
```

## Recommended Fix Priority (High-Yield Strategy)

### Tier 1: Immediate High-Yield Fixes (38-50 tests)
1. **Branch Reset Groups (?|...)** - 38 failures
   - Single preprocessor feature
   - Well-defined semantics
   - No regex engine changes needed
   - **Estimated effort**: 4-6 hours
   - **ROI**: ~6-10 tests/hour

### Tier 2: Medium-Yield Features (29-43 tests)
2. **Conditional Patterns (?(...)...)** - 29 direct failures
   - Affects ~3000 tests across 6 files (see fix-conditional-regex-3000-tests.md)
   - Requires regex engine modifications
   - **Estimated effort**: 16-24 hours
   - **ROI**: ~125-190 tests/hour (across all regexp files)

3. **Regex Control Verbs (*ACCEPT, etc.)** - 43 failures
   - Requires deep regex engine integration
   - Multiple verbs with different semantics
   - **Estimated effort**: 24-40 hours
   - **ROI**: ~1-2 tests/hour
   - **Recommendation**: LOW priority due to complexity

### Tier 3: Complex Features (33+ tests)
4. **Recursive Patterns** - 33 failures
   - Very complex implementation
   - Requires recursion engine
   - **Estimated effort**: 40+ hours
   - **Recommendation**: LOW priority - defer to later

### Tier 4: Remaining Issues (~360 tests)
- Match behavior differences
- Edge cases
- Special variable handling
- Unicode property issues
- Other systematic issues

**Requires**: Detailed case-by-case analysis

## Recommended Next Actions

### Immediate (This Session)
1. **Implement Branch Reset Groups (?|...)**
   - Create test cases from failing patterns
   - Modify RegexPreprocessor to handle (?|...)
   - Map capture groups correctly for each alternative
   - **Target**: Fix 38 tests in 4-6 hours

### Short-term (Next Session)
2. **Analyze "Other/Mixed" failures** (~188 tests)
   - Extract actual vs expected values
   - Look for systematic patterns
   - Identify low-hanging fruit (error messages, edge cases)
   - **Target**: Find 50-100 easy fixes

### Medium-term (Future Sessions)
3. **Conditional Patterns** - High ROI across all regexp files
4. **Continue systematic analysis** of remaining failures

## Analysis Commands

```bash
# Count test results
./jperl t/re/regexp.t 2>&1 | grep -c "^ok"
./jperl t/re/regexp.t 2>&1 | grep -c "^not ok"

# Extract error categories
LC_ALL=C grep "^# " /tmp/regexp_full_output.txt | \
  LC_ALL=C grep -oE "(Conditional patterns|Sequence \(\?[|+&P1]|Regex control verb|not implemented|not recognized|Illegal|Unmatched)" | \
  LC_ALL=C sort | LC_ALL=C uniq -c | LC_ALL=C sort -rn

# Find specific patterns
grep "Sequence (?|" /tmp/regexp_full_output.txt
grep "Conditional patterns" /tmp/regexp_full_output.txt
```

## Files to Investigate
- `/src/main/java/org/perlonjava/regex/RegexPreprocessor.java` - Main preprocessing logic
- `/src/main/java/org/perlonjava/regex/RuntimeRegex.java` - Runtime regex execution
- `/src/main/java/org/perlonjava/regex/RegexPreprocessorHelper.java` - Helper methods

## Success Metrics
- **Current**: 1676/2177 passing (77%)
- **After branch reset**: ~1714/2177 (79%) - +38 tests
- **After conditional patterns**: ~1743/2177 (80%) - +29 tests
- **Ultimate goal**: >95% pass rate (>2068/2177)
