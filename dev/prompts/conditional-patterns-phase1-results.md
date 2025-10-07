# Conditional Patterns (?(...)...) - Phase 1 Implementation Results

## Implementation Date
2025-10-07

## Summary
Implemented Phase 1 transformation for simple conditional patterns `(?(N)yes|no)` that can be converted to alternations, with graceful fallback to `regexUnimplemented` for complex cases.

## Changes Made

### Files Modified
1. `/src/main/java/org/perlonjava/regex/RegexPreprocessor.java`
   - Added `transformSimpleConditionals()` method (215 lines)
   - Modified `preProcessRegex()` to call transformation before main processing
   - Changed `handleConditionalPattern()` to use `regexUnimplemented` instead of `regexError`

### Transformation Strategy

**Simple Pattern**: `(group)?(?(N)yes|no)`  
**Transforms to**: `(?:(group)yes|no)`

**How it works:**
- First alternative `(group)yes`: group captures AND yes branch executes
- Second alternative `no`: group doesn't capture AND no branch executes

**Example transformations:**
```
Perl:   ^(a)?(?(1)b|c)$
Java:   ^(?:(a)b|c)$

Match "ab": First alt (a)b matches - $1='a' ✓
Match "c":  Second alt c matches - $1=undef ✓
Match "ac": Neither matches ✓
```

### Graceful Degradation

**Complex patterns** that can't be transformed reach `handleConditionalPattern()` which:
1. Appends `(?!)` placeholder (always-fail negative lookahead)
2. Throws `regexUnimplemented` (catchable with `JPERL_UNIMPLEMENTED=warn`)
3. Allows tests to continue without hard crash

**Complex cases include:**
- Self-referential: `(a(?(1)\1)){4}`
- Named group conditions: `(?<foo>a)(?(foo)b|c)`
- Assertion conditions: `(?(?=a)ab|cd)`
- Multiple conditionals
- Non-optional groups

## Test Results

### t/re/regexp.t
| Metric | Before | After | Change |
|--------|--------|-------|--------|
| Passing tests | 1687 | 1700 | **+13** ✅ |
| Failing tests | 490 | 477 | **-13** ✅ |
| "Conditional patterns" errors | 33 | 18 | **-15** ✅ |

### Breakdown
- **15 patterns transformed successfully** - simple cases now work
- **18 patterns remain unimplemented** - complex cases with graceful error

### Error Category Changes
| Category | Before | After | Change |
|----------|--------|-------|--------|
| Conditional patterns | 33 | 18 | -15 ✅ |
| Total "not implemented" | 125 | 110 | -15 ✅ |
| Branch reset (?|...) | 38 → 0 | 0 | (previous fix) |

## What Works

✅ **Simple optional group with conditional**
```perl
^(a)?(?(1)b|c)$          # Transforms to: ^(?:(a)b|c)$
^(\(+)?blah(?(1)(\)))$   # Transforms to: ^(?:(\(+)blah(\))|blah)$
```

✅ **Conditional without else branch**
```perl
^(a)?(?(1)b)$            # Transforms to: ^(?:(a)b|)$
```

✅ **With simple text between group and conditional**
```perl
(a)?x(?(1)b|c)           # Transforms to: (?:(a)xb|xc)
```

## What Doesn't Work (Falls Back to Unimplemented)

❌ **Self-referential conditionals**
```perl
(a(?(1)\1)){4}           # References group 1 inside group 1
```

❌ **Named group conditions**
```perl
(?<foo>a)(?(foo)b|c)     # Need name-to-number mapping
```

❌ **Assertion conditions**
```perl
(?(?=a)ab|cd)            # Condition is lookahead, not group check
```

❌ **Non-optional groups**
```perl
(a)(?(1)b|c)             # Group always matches, can't be optional
```

## Transformation Algorithm

### Phase 1: Find Conditionals
Scan for `(?(digit)` patterns and parse yes/no branches

### Phase 2: Locate Referenced Group
- Count capturing groups from start to conditional
- Find group N and verify it's followed by `?`, `*`, or `{0,n}`

### Phase 3: Validate Transformability
- Check if only simple text between group and conditional
- Reject if self-referential, named, or assertion-based

### Phase 4: Build Alternation
```
prefix + (?:(group)middle+yes|middle+no)
```

## ROI Analysis

### Effort vs. Return
- **Time invested**: 3 hours (analysis + implementation + testing)
- **Direct test fixes**: +13 tests
- **Patterns enabled**: 15 transformed, 18 with graceful error
- **ROI**: 4.3 tests/hour

### Strategic Value
- ✅ Foundation for more sophisticated conditional handling
- ✅ Graceful degradation with `regexUnimplemented`
- ✅ Tests can use `JPERL_UNIMPLEMENTED=warn` to continue
- ✅ Error messages guide users on unsupported patterns

## Cumulative Session Results

### Combined with Branch Reset Fix
| Metric | Start | After Branch | After Conditional | Total Gain |
|--------|-------|--------------|-------------------|------------|
| Passing | 1676 | 1687 | 1700 | **+24** ✅ |
| Failing | 501 | 490 | 477 | **-24** ✅ |

### Error Elimination
- Branch reset `(?|...)`: 38 errors → 0 (all eliminated)
- Conditional `(?(...)...)`: 33 errors → 18 (15 transformed)
- Total impact: **53 patterns now handle gracefully**

## Phase 2 Possibilities

### Advanced Transformations
1. **Multiple conditionals**: `(a)?(b)?(?(1)x)(?(2)y)`
2. **Named group tracking**: Map names to numbers during preprocessing
3. **Assertion conditions**: Handle `(?(?=...)yes|no)` with nested groups
4. **Complex between-text**: Allow more complex patterns between group and conditional

### Runtime Solutions
For patterns that truly can't be transformed:
1. Custom matcher that evaluates conditions at runtime
2. Bytecode transformation of compiled patterns
3. Alternative regex engine with full Perl compatibility

**Estimated effort**: 20-40 hours for full Phase 2

## Conclusion

Phase 1 successfully transforms common simple conditional patterns and provides graceful degradation for complex cases. This pragmatic approach:
- Fixes 13 tests immediately
- Enables 15 patterns to compile and work correctly  
- Provides clear error messages for unsupported patterns
- Establishes foundation for future enhancements

Combined with branch reset implementation, this represents **+24 tests in ~7 hours** with solid architectural improvements.
