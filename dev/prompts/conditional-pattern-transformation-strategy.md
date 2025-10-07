# Conditional Pattern Transformation Strategy

## Discovery
Java regex does NOT support `(?(condition)yes|no)` syntax - it throws "Unknown inline modifier" error.

However, for certain common patterns, we can transform them into equivalent alternations!

## Transformation Rules

### Rule 1: Optional Group with Conditional
```
Pattern: (group)?(?(N)yes|no)
Transform to: (?:(group)yes|no)
```

**How it works:**
- First alternative: `(group)yes` - group captures AND yes branch matches
- Second alternative: `no` - group doesn't capture AND no branch matches

**Examples:**
```
Perl:   ^(a)?(?(1)b|c)$
Java:   ^(?:(a)b|c)$

Match "ab": First alt (a)b matches - group 1 = 'a' ✓
Match "c":  Second alt c matches - group 1 = undef ✓
Match "ac": Neither matches ✓
Match "b":  Neither matches ✓
```

```
Perl:   ^(\(+)?blah(?(1)(\)))$
Java:   ^(?:(\(+)blah(\))|blah)$

Match "blah":     Second alt matches ✓
Match "(blah)":   First alt matches - groups 1='(', 2=')' ✓
Match "((blah))": First alt matches - groups 1='((', 2=')' (WRONG!)
```

### Rule 2: Multiple Conditionals
```
Pattern: (a)?(?(1)b)(?(1)c)
Transform to: (?:(a)bc|)
```

If there are multiple conditionals referring to the same group, they must all be moved into the same alternative.

### Rule 3: Non-optional Group with Conditional
```
Pattern: (a)(?(1)b|c)
Cannot transform - group always matches, so condition is always true
Result: (a)b
```

## Limitations

### Cannot Transform:
1. **Self-referential conditionals**
   ```
   (a(?(1)\1)){4}  - Refers to group 1 inside group 1
   ```

2. **Named group conditions** (unless we track name-to-number mapping)
   ```
   (?<foo>a)(?(foo)b|c)  - Need to know which number 'foo' is
   ```

3. **Assertion conditions**
   ```
   (?(?=a)ab|cd)  - Condition is a lookahead, not a group check
   ```

4. **Backward references to groups defined later**
   ```
   (?(2)a|b)(x)  - Refers to group 2 which comes later
   ```

5. **Complex group dependencies**
   ```
   (a)?(b)?(?(1)x)(?(2)y)  - Multiple independent conditionals
   ```

## Implementation Strategy

### Phase 1: Handle Simple Cases
Focus on the most common pattern from tests:
```
^(optional)?pattern(?(N)yes)$
^(optional)?pattern(?(N)yes|no)$
```

Where:
- The group referenced by condition appears earlier in the pattern
- The group is optional (has ? or * or {0,n})
- Only one conditional per pattern
- Condition is a numeric group reference

### Phase 2: Validation
For patterns we can't transform:
- Keep throwing "not implemented" error
- But provide specific error message about what's not supported

## Test Case Analysis

From regexp.t failures, most common patterns:
1. `^(\(+)?blah(?(1)(\)))$` - 4 failures
2. `^(\()?blah(?(1)(\)))$` - 4 failures  
3. `^(a(?(1)\1)){4}$` - 3 failures (self-referential - CANNOT transform)

**Transformable**: ~50-60% of conditional pattern tests
**Not transformable**: ~40-50%

## Expected Impact

**Optimistic estimate:**
- Transform 15-20 of 29 failures
- Remaining 9-14 still fail (complex cases)

**Conservative estimate:**
- Transform 10-15 tests
- Remaining 14-19 fail

**Across all regexp files:**
- If 50% of ~3000 failures are simple conditionals: +1500 tests
- More realistically ~30-40%: +900-1200 tests

## Implementation Plan

### Step 1: Pattern Detection
Detect `(?(N)` where N is a digit and the group is earlier in the pattern.

### Step 2: Find the Optional Group
Scan backwards to find group N and verify it's optional (`?`, `*`, `{0,n}`).

### Step 3: Extract Parts
- Extract: prefix (before group)
- Extract: the optional group
- Extract: middle (between group and conditional)
- Extract: yes branch
- Extract: no branch (if present)

### Step 4: Reconstruct
```
prefix + (?:(group)middle+yes|middle+no)
```

### Step 5: Handle Edge Cases
- No 'no' branch: `middle+yes|middle`
- Multiple groups between: track carefully
- Nested conditionals: reject as unsupported

## Code Location

Modify `/src/main/java/org/perlonjava/regex/RegexPreprocessor.java`:
- Update `handleConditionalPattern()` method
- Instead of always throwing error, attempt transformation
- Fall back to error for untransformable cases

## Risk Assessment

**Low risk:**
- Transformation is done at preprocessing
- If transformation is wrong, tests will fail (we'll see it)
- Can validate transformation logic with test cases
- Falls back to error for complex cases

**Medium reward:**
- +10-20 tests immediate
- Foundation for more sophisticated handling
- Better than throwing errors

## Next Steps

1. Implement simple case transformer
2. Test with known patterns from regexp.t
3. Measure impact
4. Document limitations clearly
5. Add TODO for Phase 2 (complex cases)
