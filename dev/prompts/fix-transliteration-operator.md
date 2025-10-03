# Fix Transliteration Operator Issues in op/tr.t

## Objective
Fix the remaining issues in the transliteration operator (`tr///` and `y///`) to unblock tests in `t/op/tr.t`.

## Current Status
- **Test file:** `t/op/tr.t`
- **Tests passing:** 245/318 (77%)
- **Tests blocked:** 73
- **Previously:** 131/318 passing (41%)
- **Improvement:** +114 tests unblocked

## Issues Fixed (Session 2025-10-03)

### 1. ✅ Missing compile-time error for `!~ with tr///r`
**Problem:** No error when using `!~` with non-destructive transliteration (`/r` modifier)
```perl
$_ !~ y/a/b/r  # Should error: "Using !~ with tr///r doesn't make sense"
```

**Solution:** Added validation in `EmitRegex.handleNotBindRegex()`
```java
if (node.right instanceof OperatorNode operatorNode 
        && (operatorNode.operator.equals("tr") || operatorNode.operator.equals("transliterate"))
        && operatorNode.operand instanceof ListNode listNode
        && listNode.elements.size() >= 3) {
    Node modifiersNode = listNode.elements.get(2);
    if (modifiersNode instanceof StringNode stringNode) {
        String modifiers = stringNode.value;
        if (modifiers.contains("r")) {
            throw new PerlCompilerException(node.tokenIndex, 
                "Using !~ with tr///r doesn't make sense", 
                emitterVisitor.ctx.errorUtil);
        }
    }
}
```

**Impact:** Test 129 now passes

### 2. ✅ Read-only value modification with `tr///`
**Problem:** `$1 =~ tr/A-Z//` threw "Modification of a read-only value" error even when only counting

**Perl Behavior:**
- `tr/A-Z//` on read-only = OK (counting only, no replacement)
- `tr/a/b/` on read-only = ERROR (has replacement)
- Empty string with replacement = ERROR
- Empty string without replacement = OK

**Solution:** Modified `RuntimeTransliterate.transliterate()` to only call `set()` when needed:
```java
// Determine if we need to call set() which will trigger read-only error if applicable
// We must call set() if:
// 1. The string actually changed, OR
// 2. It's an empty string AND we have a replacement operation (not just counting)
boolean hasReplacement = !replacementChars.isEmpty() || deleteUnmatched;
boolean needsSet = !input.equals(resultString) || (input.isEmpty() && hasReplacement);

if (needsSet) {
    originalString.set(resultString);
}
```

**Impact:** Tests 131-245 unblocked (+114 tests)

## Remaining Issues

### 1. ❌ `Internals::SvREADONLY` not working
**Problem:** `Internals::SvREADONLY($var, 1)` doesn't actually make variables read-only in PerlOnJava

**Test case:**
```perl
my $x = "";
Internals::SvREADONLY($x, 1);
$x =~ tr/a/b/;  # Should error but doesn't
```

**Impact:** Tests 250-251 failing (expecting read-only errors)

**Investigation needed:**
- Find where `Internals::SvREADONLY` is implemented
- Check if it sets the read-only flag correctly
- Verify RuntimeScalarReadOnly is used

### 2. ❌ Named sequences in `tr///` 
**Problem:** `\N{name}` named sequences not supported in transliteration
```perl
$s =~ tr/\N{LATIN SMALL LETTER A WITH ACUTE}/A/;  # Errors
```

**Error:** `\N{LATIN SMALL LETTER A WITH ACUTE} must not be a named sequence in transliteration operator`

**Impact:** Test 256 blocks remaining tests (62 tests blocked)

**Investigation needed:**
- Check where `\N{}` is parsed in tr/// context
- May need to expand named sequences to actual characters
- Look at `expandRangesAndEscapes()` in RuntimeTransliterate

### 3. ⚠️ Warning for `tr///r` in void context
**Problem:** No warning for useless non-destructive transliteration in void context
```perl
y///r;  # Should warn: "Useless use of non-destructive transliteration (tr///r)"
```

**Impact:** Test 130 failing

**Implementation needed:**
- Add void context detection in EmitRegex.handleTransliterate()
- Generate warning when `/r` modifier used in void context

## Files Modified
1. `/Users/fglock/projects/PerlOnJava/src/main/java/org/perlonjava/codegen/EmitRegex.java`
   - Added `!~ with tr///r` validation
   
2. `/Users/fglock/projects/PerlOnJava/src/main/java/org/perlonjava/operators/RuntimeTransliterate.java`
   - Fixed read-only handling logic

## Test Commands
```bash
# Run full test
./jperl t/op/tr.t

# Count passing tests
./jperl t/op/tr.t 2>&1 | grep -c "^ok"

# Check specific test
./jperl t/op/tr.t 2>&1 | grep -A 3 "not ok 250"

# Test read-only behavior
echo 'my $x = ""; Internals::SvREADONLY($x, 1); $x =~ tr/a/b/' | ./jperl

# Test named sequences
echo '$s = "á"; $s =~ tr/\N{LATIN SMALL LETTER A WITH ACUTE}/A/' | ./jperl
```

## Implementation Checklist
- [x] Add compile-time error for `!~ with tr///r`
- [x] Fix read-only value handling for counting operations
- [ ] Fix `Internals::SvREADONLY` implementation
- [ ] Add support for `\N{name}` in transliteration
- [ ] Add void context warning for `tr///r`
- [ ] Handle more complex transliteration edge cases

## Priority Assessment
**High Priority:** Named sequences issue (blocks 62 tests)
**Medium Priority:** Internals::SvREADONLY (2 tests)
**Low Priority:** Void context warning (1 test)

## Commits
- `ab480e0d`: Added compile-time error for !~ with tr///r
- `ff614096`: Fixed tr/// on read-only values

## Next Steps
1. Investigate where `\N{}` sequences are parsed/expanded
2. Check if similar issue exists in regex patterns
3. Consider if named sequences should be expanded at parse time or runtime
4. Look for existing Unicode name resolution code to leverage

## Success Criteria
- All 318 tests in op/tr.t passing
- Proper read-only handling matching Perl behavior
- Named sequence support in transliteration
- Appropriate warnings generated
