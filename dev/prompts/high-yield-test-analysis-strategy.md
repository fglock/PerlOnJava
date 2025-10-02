# High-Yield Test Analysis and Debugging Strategy

## Meta-Prompt Purpose
This is a **living document** that captures effective strategies for finding and fixing high-yield bugs in PerlOnJava. When you learn new debugging techniques or discover better ways to analyze test failures, **UPDATE THIS FILE** with your findings.

**Last Updated:** 2025-10-02  
**Recent Additions:** Time management strategy, debugging techniques, continuation prompts

## Time Management for Debugging Sessions

**START EVERY SESSION:** Note the current time and set expectations
```bash
# At session start
echo "Debug session started at: $(date '+%Y-%m-%d %H:%M')"
echo "Target: [describe what you're investigating]"
```

**Time Guidelines by Problem Complexity:**

| Complexity | Time Budget | When to Stop | Action if Exceeded |
|------------|-------------|--------------|-------------------|
| **Easy** | 15-30 min | Clear fix identified | Implement immediately |
| **Medium** | 30-60 min | Root cause found | Consider creating prompt if implementation > 1hr |
| **Hard** | 60-90 min | Still investigating | Create continuation prompt |
| **Unknown** | 30 min initial | No clear pattern | Re-evaluate or seek different approach |

**Time Check Points:**
- **15 minutes:** Have I reproduced the issue?
- **30 minutes:** Do I understand the root cause?
- **45 minutes:** Is the fix clear and simple?
- **60 minutes:** Should I create a continuation prompt?

**Red Flags to Stop and Create Prompt:**
- Requires changes to multiple subsystems
- Need to understand unfamiliar architecture
- Fix requires new Java methods/classes
- AST or compilation changes needed
- "Just 5 more minutes" said 3 times

**Quick Time Check:**
```bash
# Use this to track your debugging session
START_TIME=$(date +%s)
echo "Starting: investigating $1"

# ... after debugging work ...

END_TIME=$(date +%s)
ELAPSED=$((END_TIME - START_TIME))
echo "Time spent: $((ELAPSED / 60)) minutes"

if [ $ELAPSED -gt 3600 ]; then
    echo "WARNING: Over 1 hour - create continuation prompt!"
fi
```

**Decision Matrix:**
```
Time Spent | Root Cause Found | Fix Complexity | Action
-----------|------------------|----------------|--------
< 30 min   | Yes              | Simple         | Implement now
< 30 min   | Yes              | Complex        | Create prompt
< 60 min   | Yes              | Any            | Create prompt if > 1hr work
> 60 min   | No               | Unknown        | Create prompt & move on
> 90 min   | Any              | Any            | STOP - Create prompt
```

**Flexibility Rule: Upgrading Problem Complexity**

If you're working on an **Easy** or **Medium** problem and discover:
- The fix would have high impact (many tests fixed)
- You're close to a solution but need more time
- The learning value is significant

**You can upgrade the complexity classification:**
```
Easy (15-30 min) → Medium (30-60 min)
Medium (30-60 min) → Hard (60-90 min)
```

**When to upgrade:**
- At the checkpoint, you understand the problem well
- The fix affects more tests than initially estimated
- You've found the root cause but implementation is complex
- The issue reveals a systematic problem worth fixing

**Example:**
```
Started: Easy problem (15 min budget)
At 15 min: Found it affects 20 tests, not just 1
Decision: Upgrade to Medium, continue for 30 more minutes
At 45 min: Have working fix, just needs testing
Decision: Complete the fix (worth the time investment)
```

This flexibility prevents abandoning valuable fixes due to rigid time limits while still maintaining discipline.

## Quick Start: Finding High-Yield Targets

### Step 1: Use Automated Analysis Tools

**NEW: Failure Categorization Script**
```bash
# Analyze pack.t failures with automatic categorization
./dev/tools/analyze_pack_failures.pl
```

This script automatically:
- Runs pack.t and captures all test results
- Categorizes failures by type (endianness, formats, modifiers, etc.)
- Calculates priority scores based on test count and complexity
- Provides actionable recommendations sorted by impact

**Benefits:**
- Identifies patterns across thousands of tests
- Prioritizes fixes by impact (test count × complexity)
- Shows examples from each category
- Tracks progress as you fix issues

### Step 2: Analyze Test Results Data (Alternative Method)
```bash
# Get test results with pass rates
jq -r '.results | to_entries[] | select(.value.ok_count > 50 and .value.not_ok_count > 15 and .value.not_ok_count < 100) | "\(.value.not_ok_count) failures / \(.value.ok_count) passing (\(.value.ok_count * 100 / .value.total_tests | floor)%) - \(.key)"' out.json | sort -rn | head -20
```

**Look for:**
- **High pass rates (70-95%)** - Indicates focused bugs, not missing features
- **Moderate failure counts (15-100)** - Sweet spot for impact vs. complexity
- **Avoid very low pass rates (<50%)** - Usually indicates unimplemented features

### Step 2: Investigate Failure Patterns
```bash
# Get first 20 failures to identify patterns
./jperl t/op/TESTFILE.t 2>&1 | grep "^not ok" | head -20

# Get error details for specific test
./jperl t/op/TESTFILE.t 2>&1 | grep -A 5 "^not ok TEST_NUMBER"
```

**Look for:**
- **Repeated keywords** - Same error message across many tests
- **Numeric patterns** - Tests with similar numbers (e.g., all negative offsets)
- **Format patterns** - Tests with similar format strings (e.g., %lld, %lli)

### Step 3: Verify with Minimal Test Case
Always create a minimal test case to verify the bug:
```perl
#!/usr/bin/perl
use strict;
use warnings;

# Minimal reproduction of the bug
my $result = some_operation();
print "Result: $result\n";
print "Expected: EXPECTED_VALUE\n";
print "Test: ", ($result eq "EXPECTED_VALUE" ? "PASS" : "FAIL"), "\n";
```

Test with both Perl and PerlOnJava:
```bash
perl test_minimal.pl
./jperl test_minimal.pl
```

## Proven High-Yield Strategies

### Strategy 1: Pattern Recognition
**When to use:** Many similar test failures

**Example from this session:**
- op/infnan.t: All failures had "Inf" or "NaN" in test names
- op/read.t: All failures had "offset" in test names (offset=3, offset=-1, etc.)
- op/hashassign.t: All failures mentioned "scalar context" or "list context"

**Action:** Group failures by pattern, fix the root cause once

### Strategy 2: Context-Aware Operations
**When to use:** Tests fail in one context but pass in another

**Key insight:** Many Perl operations behave differently in scalar vs. list context.

**Check for:**
- Methods that should accept `int ctx` parameter
- Bytecode generation using `pushCallContext()`
- Return values that differ based on context

**Example:** Hash assignment returns different values in scalar vs. list context.

### Strategy 3: Verify with perldoc First
**When to use:** Implementing or fixing pack/unpack formats or any Perl built-in

**Critical lesson from W format fix:**
- **Don't assume format similarity** - W and U formats look similar but have critical differences
- **Check `perldoc -f pack` first** - Documentation reveals W accepts values >0x10FFFF, U doesn't
- **Test with edge cases** - Values beyond normal ranges (>0x10FFFF for Unicode)
- **Compare side-by-side** - Run `perl` and `./jperl` with same test case

**Example:**
```bash
# Check documentation
perldoc -f pack | grep -A 10 "W  "

# Test edge case with standard Perl
perl -e 'my $p = pack("W", 305419896); print length($p), "\n";'

# Compare with PerlOnJava
./jperl -e 'my $p = pack("W", 305419896); print length($p), "\n";'
```

**Key insight:** W format wraps values to valid range, U format throws exceptions. This difference prevented using shared code path.

### Strategy 4: EOF and Boundary Conditions
**When to use:** Tests fail at end-of-file or with edge case inputs

**Common issues:**
- EOF handling without trailing newlines
- Empty delimiters or empty inputs
- Negative offsets or indices
- Integer overflow at boundaries (2^31, 2^32)

**Example from this session:** read() with offset at EOF was clearing buffer instead of padding.

### Strategy 4: Existing Helper Methods
**When to use:** Before implementing new validation logic

**Action:** Search for existing methods that might already handle your case.

**Example from this session:** 
- Found `handleInfinity()` method already existed for pack Inf/NaN validation
- Just needed to call it in the right place

**Search techniques:**
```bash
# Search for method names
grep -r "methodName" src/main/java/

# Search for similar functionality
grep -r "Infinity\|NaN" src/main/java/
```

### Strategy 5: Create Prompt Documents for Complex Issues
**When to use:** Bug requires >1 hour of refactoring or affects many files

**Pattern:** Document the issue comprehensively and move to simpler targets

**When to create a prompt document:**
- Bug requires changing 10+ files
- Bug requires architectural refactoring
- Bug needs context-aware changes across entire hierarchy
- Estimated effort >1 hour of careful work

**What to include in prompt document:**
1. **Objective** - Clear statement of what needs fixing
2. **Root Cause Analysis** - Detailed technical investigation
3. **Why Simple Fixes Don't Work** - Document attempted solutions
4. **Implementation Strategy** - Step-by-step plan with phases
5. **Testing Strategy** - How to verify the fix
6. **Expected Impact** - Test count improvement estimate
7. **Recommendation** - Priority and effort estimate

**Example from this session:**
- Hash assignment bug: 26 tests, requires 16+ files, 1-2 hours
- Created `dev/prompts/fix-hash-assignment-scalar-context.md` with full analysis
- Moved to simpler high-yield target instead
- ROI: 26 tests / 2 hours = 13 tests/hour (moderate)

**Where to save prompt documents:**
- **Directory:** `dev/prompts/` (all problem-specific prompts go here)
- **Naming:** Use descriptive names like `fix-[problem-description].md`
- **Format:** Follow the template structure shown above
- **Update this strategy:** Add lessons learned to this document

**Benefits:**
- Preserves investigation work for future
- Allows focusing on higher ROI targets
- Provides clear implementation plan when time allows
- Prevents wasting time on complex issues during high-velocity sessions
- Centralizes all problem-specific documentation in one location

### Strategy 6: Bytecode Disassembly
**When to use:** Understanding how code is compiled and executed

**Command:**
```bash
./jperl --disassemble -e 'CODE_HERE' 2>&1 | grep -A 10 "METHOD_NAME"
```

**Look for:**
- Method signatures (parameter types)
- Call sequences (what gets called when)
- Context handling (pushCallContext calls)

**Example:** Used to discover how `setFromList()` is called and what context information is available.

## Debugging Techniques Learned

### Technique 1: Strategic Debug Logging
**Best practice:** Add targeted logging at decision points, not everywhere.

**Template:**
```java
if (CONDITION_OF_INTEREST) {
    System.err.println("DEBUG: key_variable=" + keyVariable + 
                     ", state=" + currentState + 
                     ", expected=" + expectedValue);
}
```

**Remove after debugging** - Don't commit debug statements.

### Technique 2: Comparative Testing
**Pattern:** Test the same code with both Perl and PerlOnJava

```bash
# Test with standard Perl
perl -e 'TEST_CODE'

# Test with PerlOnJava  
./jperl -e 'TEST_CODE'

# Compare outputs
diff <(perl -e 'TEST_CODE') <(./jperl -e 'TEST_CODE')
```

### Technique 3: Incremental Verification
**Pattern:** Test each hypothesis immediately

1. Form hypothesis about bug
2. Create minimal test case
3. Verify hypothesis
4. If wrong, form new hypothesis
5. Repeat until root cause found

**Don't:** Make multiple changes without testing each one.

### Technique 4: Extracting Failing Tests from Large Suites
**When to use:** Your minimal test passes but the full test suite fails with the same feature.

**The Problem:**
- Simple standalone tests work correctly 
- Full test suite (e.g., pack.t with 2000+ lines) fails 
- Debugging large files is difficult and confusing
- Easy to get misled by incorrect assumptions

**The Solution - Extract and Simplify:**

1. **Identify the exact failing test:**
   ```bash
   # Find which specific test is failing
   JPERL_LARGECODE=refactor ./jperl t/op/pack.t 2>&1 | grep -A 3 "^not ok NUMBER"
   ```

2. **Find the test code in the source:**
   ```bash
   # Locate the test function or data
   grep -n "test_data_or_function" t/op/pack.t
   ```

3. **Extract minimal reproduction:**
   - Copy the test file header (use strict, imports, etc.)
   - Copy the relevant test function(s)
   - Copy the specific test data that fails
   - Copy helper functions (like `is()`, `ok()`, `pass()`, `fail()`)
   - Remove everything else

4. **Test the extraction:**
   ```bash
   # Should reproduce the same failure
   ./jperl test_extracted.t
   ```

5. **Iteratively simplify:**
   - Remove unneeded helper functions
   - Simplify test data if possible
   - Remove extra test cases
   - Keep only what's needed to reproduce the bug

**Example from pack.t checksum debugging:**

```perl
# Original: t/op/pack.t (2068 lines) - fails
# Extracted: test_pack_checksum_full.t (135 lines) - still fails!
# This proves the bug is in the checksum logic, not test file size

# The extraction included:
# - Helper functions (is, ok, pass, fail)
# - numbers_with_total() function
# - numbers() function  
# - The exact failing test: numbers('l!', -2147483648, -1, 0, 1, 2147483647)
```

**Benefits:**
- Faster iteration (small file compiles quickly)
- Easier to add debug output
- No interference from unrelated tests
- Can compare extracted test vs minimal test to find differences
- Proves the bug is reproducible outside full suite

**Red Flags:**
- If extraction works but full suite fails → test interaction or state issue
- If extraction fails differently → you extracted the wrong test
- If extraction passes → file size/compilation issue (e.g., JPERL_LARGECODE)

**Template for extraction:**
```perl
#!/usr/bin/perl
use strict;
use warnings;
use Config;  # If needed

print "1..N\n";  # TAP output

# Helper functions
sub is { ... }
sub ok { ... }
sub pass { ... }
sub fail { ... }

# Test-specific helpers
sub test_helper { ... }

# The actual failing test
test_function_that_fails();

print "# Tests completed\n";
```

**Remember:** 
- PerlOnJava doesn't cache code between runs (no stale bytecode)
- Always rebuild after code changes: `./gradlew clean shadowJar`
- Use `JPERL_LARGECODE=refactor` if original test needed it

### Technique 5: Test Count Analysis
**Pattern:** Track test improvements to verify fixes

```bash
# Before fix
./jperl t/op/TESTFILE.t 2>&1 | grep -c "^ok"
./jperl t/op/TESTFILE.t 2>&1 | grep -c "^not ok"

# After fix
./jperl t/op/TESTFILE.t 2>&1 | grep -c "^ok"
./jperl t/op/TESTFILE.t 2>&1 | grep -c "^not ok"

# Calculate improvement
```

### Technique 6: Grep for Implementation
**Pattern:** Find where functionality is implemented

```bash
# Find method definitions
grep -r "public.*methodName" src/main/java/

# Find method calls
grep -r "methodName\(" src/main/java/

# Find with context
grep -B 5 -A 10 "pattern" file.java
```

### Technique 7: Killing Hanging Test Processes
**Pattern:** When a test hangs (infinite loop or deadlock), find and kill the process

```bash
# Find hanging jperl processes
ps aux | grep "jperl test_name"

# Find Java processes running tests (more reliable)
ps aux | grep -E "java.*test_name|PerlOnJava.*test"

# Kill the process (use PID from ps output)
kill -9 <PID>

# Alternative: Kill by pattern (be careful!)
pkill -f "jperl test_name"
```

**Red Flags of a hanging test:**
- Test doesn't complete after several seconds
- High CPU usage (>100% in ps output)
- List context operations that depend on `undef` to terminate
- Infinite loops in regex or string operations

**Common causes:**
- Changing `undef` returns to empty strings breaks list context loops
- Incorrect EOF handling in IO operations
- Regex engine infinite loops with certain patterns

## Testing Best Practices

### Creating Permanent Tests
**IMPORTANT:** When creating permanent test files in `src/test/resources`, always follow these guidelines:

1. **Use Test::More** - All permanent tests should use the Test::More module
2. **Use subtests** - Organize related tests into subtests for better structure
3. **Example structure:**
```perl
#!/usr/bin/perl
use strict;
use warnings;
use Test::More;

subtest 'basic functionality' => sub {
    plan tests => 3;
    is($result, $expected, 'description');
    ok($condition, 'another test');
    like($string, qr/pattern/, 'regex test');
};

subtest 'edge cases' => sub {
    plan tests => 2;
    # edge case tests here
};

done_testing();
```

**Benefits:**
- Better test organization and reporting
- Easier to identify which group of tests failed
- Compatible with Perl's test infrastructure
- Professional test structure

### Temporary Debug Tests
For quick debugging (files in project root), simple scripts without Test::More are fine. These should be cleaned up after use.

## Advanced Debugging Techniques

### Technique 8: Using --disassemble for Deep Analysis
**Pattern:** Understand how operators are compiled to bytecode

```bash
# Create minimal test case
echo 'my $x; $x *= 2;' > test.pl

# Examine bytecode generation
./jperl --disassemble test.pl | grep -A 10 "multiply\|divide\|INVOKESTATIC"
```

**What to look for:**
- AST macro expansions (operators expanded at compile time)
- Direct method calls vs expanded operations
- Missing method invocations that should handle warnings

### Technique 9: Rapid Hypothesis Testing Workflow
**Pattern:** Quickly test theories about bugs

```bash
# Step 1: Create minimal reproduction
cat > test_hypothesis.pl << 'EOF'
#!/usr/bin/perl
use strict;
use warnings;
# Minimal code to test hypothesis
EOF

# Step 2: Compare Perl vs PerlOnJava
perl test_hypothesis.pl > perl_output.txt
./jperl test_hypothesis.pl > jperl_output.txt
diff perl_output.txt jperl_output.txt

# Step 3: Clean up
rm test_hypothesis.pl *_output.txt
```

### Technique 10: Pattern Matching Across Failures
**Pattern:** Find common root causes

```bash
# Extract all error messages from failing tests
./jperl t/op/test.t 2>&1 | grep -oE "at .* line [0-9]+" | sort | uniq -c

# Find tests with similar failure patterns
grep -l "specific_error_pattern" t/op/*.t

# Check if multiple tests fail at similar operations
for test in t/op/*.t; do
    echo "=== $test ==="
    ./jperl $test 2>&1 | grep "assignment\|chop\|chomp" | head -3
done
```

### Technique 11: Self-Assignment and Circular Reference Detection
**Pattern:** Identify operations that reference themselves

Common bug patterns:
- `@arr = @arr` - Array clearing itself before copying
- `@x = (@x, 1, 2)` - Array appearing in its own assignment
- `$x = chop($x = $y)` - Nested self-modifying operations

Test template:
```perl
# Test self-assignment
my @arr = (1, 2, 3);
@arr = @arr;  # Should preserve values

# Test circular reference
my @x = (1, 2);
@x = (@x, 3, 4);  # Should append, not clear
```

### Technique 12: Warning Capture Pattern
**Pattern:** Test if warnings are properly generated

```perl
my $warning = '';
local $SIG{__WARN__} = sub { $warning = $_[0]; };

# Operation that should warn
my $x;
$x *= 2;

if ($warning =~ /uninitialized/) {
    print "Warning correctly generated\n";
} else {
    print "MISSING WARNING - bug found!\n";
}
```

### Technique 13: AST vs Runtime Issues
**How to identify:**
- **AST/Compile-time:** Error happens even with `perl -c` or during parsing
- **Runtime:** Error only happens during execution
- **Macro expansion:** Check with `--disassemble` if operator becomes multiple operations

```bash
# Test if compile-time or runtime
./jperl -c test.pl  # Compile only
./jperl test.pl     # Full execution

# If -c succeeds but execution fails: runtime issue
# If -c fails: parsing/AST issue
```

### Technique 14: Finding Implementation Locations
**Pattern:** Quickly locate where operators/features are implemented

```bash
# Find operator implementation
grep -r "\"multiply\|\"divide" src/main/java --include="*.java"

# Find method that handles specific Perl function
grep -r "public.*chop\|public.*chomp" src/main/java --include="*.java"

# Find where error messages are generated
grep -r "exact_error_text" src/main/java --include="*.java"

# Find AST node handlers
grep -r "visit.*OperatorNode\|emit.*Operator" src/main/java --include="*.java"
```

### Technique 15: Perl Reference Behavior Testing
**Pattern:** Always verify behavior against standard Perl

```bash
# Create comparison script
cat > compare.pl << 'EOF'
#!/usr/bin/perl
use strict;
use warnings;
use Data::Dumper;

# Test case here
my @arr = (1, 2, 3);
@arr = @arr;
print Dumper(\@arr);
EOF

# Run both and compare
perl compare.pl 2>&1 | tee perl_behavior.txt
./jperl compare.pl 2>&1 | tee jperl_behavior.txt

# Visual diff
diff -u perl_behavior.txt jperl_behavior.txt
```

### Technique 16: Progressive Test Reduction
**Pattern:** Reduce complex failing tests to minimal reproductions

```perl
# Start with failing test from test suite
# Progressively remove code until minimal failure remains

# Original complex test
sub complex_test {
    my @data = generate_test_data();
    my $result = process_data(@data);
    validate_result($result);
}

# Reduced to essence
my @arr = (1, 2, 3);
@arr = @arr;  # This line fails
print "@arr\n";  # Shows the bug
```

### Technique 17: Quick Operator Testing Template
**Pattern:** Rapid operator behavior verification

```bash
# Create reusable template
cat > test_operator_template.pl << 'EOF'
#!/usr/bin/perl -w
use strict;
use warnings;

print "=== Testing OPERATOR_NAME ===\n\n";

# Test with defined value
{
    my $x = 5;
    # OPERATION HERE
    print "Defined: result=$x\n";
}

# Test with undefined value
{
    my $warning = '';
    local $SIG{__WARN__} = sub { $warning = $_[0]; };
    
    my $x;
    # OPERATION HERE
    
    print "Undefined: result=" . ($x // 'undef') . "\n";
    print "Warning: " . ($warning ? "YES" : "NO") . "\n";
    if ($warning) { print "  $warning"; }
}
EOF
```

### Technique 18: Memory and Performance Debugging
**Pattern:** Identify performance issues or memory leaks

```bash
# Check for ConcurrentModificationException or memory issues
./jperl -Xmx256m test.pl  # Limit memory to find leaks faster

# Time comparison
time perl test.pl
time ./jperl test.pl

# Profile which tests are slowest
for t in t/op/*.t; do
    echo -n "$t: "
    time ./jperl $t 2>&1 | tail -1
done | sort -t: -k2 -n
```

### Technique 19: Creating Continuation Prompts
**CRITICAL:** When a debugging session reveals a complex issue that will take significant time to fix, create a prompt document to enable seamless continuation later.

**When to create a prompt:**
- Issue requires architectural changes (like AST modifications)
- Fix would take more than 1-2 hours
- Multiple interconnected systems need changes
- You've identified the root cause but implementation is complex

**Prompt document structure (`dev/prompts/fix-[issue-name].md`):**
```markdown
# Fix [Issue Name]

## Objective
Clear statement of what needs to be fixed and why.

## Problem Statement
What's broken, how many tests affected, user-visible impact.

## Current Status
- Test file: path/to/test.t
- Failures: X tests
- Pass rate: Y%

## Root Cause Analysis
### Investigation Done
- What you discovered
- Why it's happening
- Evidence (test outputs, code snippets)

### Current Implementation
- How it works now (with code examples)
- Why this approach causes the problem

## Proposed Solution
Step-by-step fix approach with code examples.

## Test Cases to Verify
Minimal reproductions and how to test the fix.

## Implementation Checklist
- [ ] Specific tasks to complete
- [ ] Files to modify
- [ ] Tests to run

## Expected Impact
- Tests that will be fixed
- Potential side effects
- Performance implications
```

**Examples from this session:**
- `fix-compound-assignment-operators.md` - Architecture change needed
- `fix-dynamic-eval-warning-handler.md` - Complex compilation issue
- `fix-empty-file-slurp-mode.md` - Requires investigation

**Benefits:**
- No context loss between sessions
- Anyone can pick up where you left off
- Documents the "why" along with the "how"
- Creates a knowledge base of complex issues

### Temporary Debug Tests
For quick debugging (files in project root), simple scripts without Test::More are fine. These should be cleaned up after use.

### Technique 20: Preserving Test Files Between Sessions
**IMPORTANT:** If you need to preserve test files for continuation in another session:

```bash
# Save important test files to dev/sandbox
mv test_*.pl dev/sandbox/
cp important_test.pl dev/sandbox/issue_name_test.pl

# Document in the continuation prompt
echo "Test files saved in dev/sandbox/:
- issue_name_test.pl - reproduces the bug
- debug_output.txt - contains diagnostic output" >> dev/prompts/fix-issue.md
```

**Best Practice:**
1. Use `dev/sandbox/` directory for files that need to persist
2. Name files descriptively: `issue_readonly_undef_test.pl`
3. **Add to continuation prompt:** "Clean up dev/sandbox/issue_* files after fix"
4. Don't commit sandbox files unless they become permanent tests

**Example in continuation prompt:**
```markdown
## Cleanup Checklist
- [ ] Remove dev/sandbox/readonly_undef_*.pl test files
- [ ] Move useful tests to src/test/resources/ if needed
- [ ] Clear any debug output files in dev/sandbox/
```

This prevents accumulation of temporary files while preserving important debugging context.

## Common PerlOnJava Patterns

### Pattern 1: Context Parameters
Most operators accept `int ctx` for context-aware behavior:
```java
public ReturnType operationName(Parameters..., int ctx) {
    return switch(ctx) {
        case RuntimeContextType.SCALAR -> scalarBehavior();
        case RuntimeContextType.LIST -> listBehavior();
        case RuntimeContextType.VOID -> voidBehavior();
        default -> defaultBehavior();
    };
}
```

### Pattern 2: Type Checking
Check scalar type before operations:
```java
if (value.type == RuntimeScalarType.DOUBLE) {
    double d = value.getDouble();
    // Handle double-specific logic
}
```

### Pattern 3: StringBuilder Operations
**Important:** `StringBuilder.replace()` doesn't auto-truncate!
```java
StringBuilder sb = new StringBuilder("original");
sb.replace(start, end, newData);
sb.setLength(desiredLength);  // Must explicitly truncate!
```

### Pattern 4: EOF Handling
Always handle EOF separately, especially with offsets:
```java
if (charsRead == 0) {
    // EOF case - handle offset specially
    if (offset != 0) {
        // Pad or truncate buffer based on offset
    }
}
```

## Test File Complexity Assessment

### Simple (Good targets for quick wins)
- **Characteristics:** 
  - High pass rate (>85%)
  - Focused failure patterns
  - Small number of failures (15-50)
- **Examples:** op/hashassign.t, op/assignwarn.t

### Moderate (Good for learning)
- **Characteristics:**
  - Medium pass rate (60-85%)
  - Multiple related issues
  - Medium failures (50-100)
- **Examples:** op/heredoc.t, op/range.t

### Complex (Defer or break down)
- **Characteristics:**
  - Low pass rate (<60%)
  - Unimplemented features
  - Large failures (>100)
- **Examples:** re/regex_sets_compat.t, run/switches.t

### Feature Gaps (Usually skip)
- **Characteristics:**
  - Very low pass rate (<30%)
  - Missing entire subsystems
  - Require architectural changes
- **Examples:** sprintf %lld formats, warning system

## Session Success Metrics

### Excellent Session (This session achieved this!)
- **Fixes:** 5-7 major bugs
- **Tests improved:** 200-400
- **Documentation:** 1-2 comprehensive prompts
- **Learning:** New debugging techniques discovered

### Good Session
- **Fixes:** 3-5 bugs
- **Tests improved:** 100-200
- **Documentation:** 1 prompt or detailed investigation

### Productive Session
- **Fixes:** 1-2 bugs
- **Tests improved:** 50-100
- **Documentation:** Clear notes on findings

## Lessons Learned (Update This Section!)

### Session 2025-09-30 (Part 4): Complete Session - 5 Fixes + 2 Prompt Docs (+48 tests)

**Session Overview:**
- **Duration:** ~3 hours
- **Fixes:** 5 high-impact fixes
- **Tests improved:** +48 tests
- **ROI:** 16 tests/hour
- **Prompt documents:** 2 comprehensive analysis documents
- **Strategy:** Mix of quick wins and deep analysis with documentation

**Key Discoveries:**

1. **Bytecode analysis is invaluable:**
   - Used `--disassemble` to debug reverse() flattening issue
   - Revealed that arrays passed as RuntimeArray objects, not flattened at compile time
   - **Lesson:** When behavior is unclear, check the bytecode to see what's actually happening

2. **Overload handling requires special checks:**
   - XOR with blessed objects was using numeric XOR instead of string XOR
   - `isString()` returns false for blessed objects even though they stringify
   - **Solution:** Check `isBlessed()` in addition to `isString()`
   - **Lesson:** Blessed objects need explicit type checking in operators

3. **Iterator flattening happens AFTER operations:**
   - RuntimeList iterator flattens nested structures automatically
   - But for reverse(), need to flatten BEFORE reversing to get correct order
   - **Lesson:** Understand when flattening happens in the execution flow

4. **Prompt documents preserve investigation value:**
   - Created docs for hash.t (~1400 tests) and pack.t (~5787 tests)
   - Documents complex issues without blocking progress
   - **Lesson:** When complexity > 2 hours, document and move on

5. **Balance quick wins with deep dives:**
   - Quick fixes: Foldcase escape (+6), XOR overload (+6)
   - Deep dives: Range (+6), Sprintf quad (+24), Reverse flattening (+6)
   - **Lesson:** Mix of both keeps momentum while solving complex issues

**Strategic Decisions:**

- **Know when to document vs fix:** Complex issues (hash buckets, pack.t) → prompt docs
- **Use comparative testing:** perl vs ./jperl side-by-side reveals exact differences
- **Leverage memories:** Previous pack.t work context helped understand current state
- **Clean commits:** Each fix committed separately with detailed messages

**Productivity Factors:**

- **Bytecode analysis:** `--disassemble` revealed runtime behavior
- **Overload debugging:** Created minimal test cases with blessed objects
- **Pattern recognition:** Grouped similar failures for bulk analysis
- **Documentation discipline:** Prompt docs for future high-value work

**New Techniques Discovered:**

1. **Bytecode disassembly for debugging:**
   ```bash
   ./jperl --disassemble -e 'CODE' 2>&1 | grep -A 20 "methodName"
   ```
   - Shows exact bytecode generation
   - Reveals how arguments are passed
   - Essential for understanding runtime behavior

2. **Overload testing pattern:**
   ```perl
   package MyClass;
   use overload '""' => 'stringify', fallback => 1;
   # Test operators with blessed objects
   ```
   - Create minimal overloaded class
   - Test operator behavior with blessed objects
   - Compare with standard Perl

3. **Prompt document structure:**
   - Objective and current status
   - Problem analysis with patterns
   - Implementation strategy (phased)
   - Success criteria and complexity assessment
   - Preserves investigation value for future work

**Recommendations for Future Sessions:**

1. **Start with bytecode when behavior is unclear**
2. **Always test with overloaded objects for operators**
3. **Create prompt docs for issues > 2 hours complexity**
4. **Mix quick wins with deep dives for sustained momentum**
5. **Use memories to understand previous work context**

### Session 2025-09-30 (Part 3): Range + Sprintf Deep Dives (+30 tests)

**Key Discoveries:**

1. **Range operator length-based semantics:**
   - Perl ranges continue until next increment would increase string length
   - Not based on lexicographic comparison alone
   - Example: '09'..'08' continues to '99' because '99'++ = '100' (3 chars > 2 chars)
   - **Lesson:** Deep dive with minimal test cases reveals subtle operator semantics

2. **Sprintf quad format inconsistency:**
   - PerlOnJava supports `pack "q"` but rejected `%lld` in sprintf
   - Single validation check blocked 24 tests
   - **Lesson:** When one feature works (pack), related features should work (sprintf)

3. **Single-line fixes can have massive impact:**
   - Sprintf fix: 1 line removed = +24 tests
   - **Lesson:** Validation/parsing bugs often have high ROI

**Strategic Decisions:**

- **Deep dive approach:** Create minimal test cases to understand exact behavior
- **Pattern recognition:** All 24 failures had same root cause (quad format rejection)
- **Commit discipline:** Clean commits with only essential files (no test garbage)

**Productivity Factors:**

- **Test-driven investigation:** Compare perl vs ./jperl behavior systematically
- **Focused targets:** 98% pass rate = focused bugs, not missing features
- **Quick wins:** Range (+6 tests) + Sprintf (+24 tests) = 30 tests in one session

### Session 2025-09-30 (Part 2): Hash Assignment Investigation

**Key Discoveries:**

1. **Complex bugs need dedicated prompt documents:**
   - Hash assignment bug affects 26 tests (high-yield target)
   - Investigation revealed it requires large refactoring (16+ files)
   - Created detailed prompt document for future work: `fix-hash-assignment-scalar-context.md`
   - **Lesson:** When a bug requires >1 hour of refactoring, document it and move on

2. **Initial assumptions can be wrong:**
   - Document assumed scalar context was broken
   - Investigation revealed list context was actually broken
   - Scalar context worked correctly all along
   - **Lesson:** Always verify assumptions with minimal test cases

3. **Bytecode disassembly reveals execution flow:**
   - Used `--disassemble` to understand how hash assignment works
   - Discovered `RuntimeList.add(RuntimeArray)` doesn't flatten arrays
   - Traced exact bytecode flow: setFromList → add → scalar/getList
   - **Lesson:** Bytecode analysis is essential for understanding runtime behavior

4. **Simple fixes can have hidden costs:**
   - Tried flattening arrays in `RuntimeList.add(RuntimeArray)`
   - Fixed list context but broke scalar context
   - Single return value cannot satisfy both contexts
   - **Lesson:** Test both contexts when fixing context-aware operations

5. **Architecture matters for maintainability:**
   - Context parameter is the clean solution
   - Alternative hacks (thread-local, special wrappers) are fragile
   - Large refactoring is sometimes necessary for correctness
   - **Lesson:** Choose architectural solutions over quick hacks

**Strategic Decisions:**

- **DEFER complex refactoring:** 26 tests vs 1-2 hours of careful work
- **Document for future:** Comprehensive prompt with technical analysis
- **Move to next target:** Focus on simpler high-yield bugs
- **ROI calculation:** 26 tests / 2 hours = 13 tests/hour (moderate ROI)

**Productivity Factors:**

- **Minimal test cases:** Created 5 test files to isolate the bug
- **Comparative testing:** perl vs ./jperl side-by-side
- **Bytecode analysis:** Used --disassemble to understand flow
- **Attempted fixes:** Tried simple solutions before accepting complexity
- **Documentation:** Created detailed prompt for future work

### Session 2025-09-30 (Part 1): 7 Fixes, ~320 Tests

**Key Discoveries:**

1. **read() offset bug:** Both positive AND negative offsets need EOF handling
   - Symptom: Tests with offset=0 pass, offset>0 fail
   - Root cause: Early return at EOF cleared buffer instead of padding
   - Fix: Handle offset in EOF case before returning

2. **pack W/U Inf/NaN:** Existing `handleInfinity()` method wasn't being called
   - Symptom: pack('W', Inf) returned 'I' instead of error
   - Root cause: Inf string doesn't start with digit, went to character branch
   - Fix: Call `handleInfinity()` BEFORE character/number branching

3. **Hash assignment context:** `setFromList()` needs context awareness
   - Symptom: Scalar context returns hash size, not source list size
   - Root cause: Method doesn't know if it's in scalar or list context
   - Solution: Add `int ctx` parameter (requires RuntimeBase hierarchy refactoring)

4. **Prototype (&) unwrapping:** Needed at validation level, not parser level
   - Symptom: `\(&code)` rejected when prototype expects `(&)`
   - Root cause: Parser created REF node, prototype validation didn't unwrap
   - Fix: Auto-unwrap REF to CODE in prototype validation

5. **For loop $$f:** Complex double dereference needs while-loop transformation
   - Symptom: JVM bytecode generation error
   - Root cause: $$f too complex for direct for-loop bytecode
   - Fix: Transform to while loop with explicit assignment

**Debugging Insights:**

- **Strategic logging beats guessing:** Add logging at decision points to trace execution
- **Test paradoxes have explanations:** Isolated tests passing but suite failing = environmental difference
- **Grep for existing solutions:** Search codebase before implementing new code
- **Bytecode disassembly reveals truth:** Use `--disassemble` to understand compilation
- **Context parameters are common:** Most operators accept `int ctx` for context-aware behavior

**Productivity Factors:**

- **High-yield targeting:** Focus on 70-95% pass rates
- **Pattern recognition:** Group similar failures, fix root cause once
- **Minimal test cases:** Always verify with simple reproduction
- **Incremental testing:** Test each hypothesis immediately
- **Documentation:** Create prompts for complex issues to defer

## Tools and Commands Reference

### Test Analysis
```bash
# Get test statistics
jq '.results["TESTFILE.t"]' out.json

# Run specific test
./jperl t/op/TESTFILE.t

# Count passes/failures
./jperl t/op/TESTFILE.t 2>&1 | grep -c "^ok"
./jperl t/op/TESTFILE.t 2>&1 | grep -c "^not ok"

# Get failure details
./jperl t/op/TESTFILE.t 2>&1 | grep -A 3 "^not ok NUMBER"

# Run with special environment variables
JPERL_LARGECODE=refactor ./jperl t/op/pack.t      # Handle large methods by refactoring blocks
JPERL_UNIMPLEMENTED=warn ./jperl t/op/TESTFILE.t  # Warn on unimplemented features instead of dying
```

### Code Search
```bash
# Find method definitions
grep -r "public.*methodName" src/main/java/

# Find method calls
grep -r "methodName\(" src/main/java/

# Search with regex
grep -r "pattern.*regex" src/main/java/

# Search in specific file types
grep -r "pattern" --include="*.java" src/
```

### Bytecode Analysis
```bash
# Disassemble code
./jperl --disassemble -e 'CODE'

# Find specific method in bytecode
./jperl --disassemble -e 'CODE' 2>&1 | grep -A 20 "methodName"
```

### Git Operations

**⚠️ IMPORTANT: Clean Commit Practices**
- **NEVER use `git add -A` or `git add .`** - This adds ALL files including test garbage
- **ALWAYS stage only the files you actually modified**
- **ALWAYS check what's staged with `git status` before committing**
- **Remove temporary test files before committing**

```bash
# CORRECT way to stage files
git add src/main/java/path/to/Modified.java
git add src/main/java/path/to/AnotherModified.java

# WRONG way (adds everything including test files)
# git add -A  # DON'T DO THIS
# git add .   # DON'T DO THIS

# Check what's staged
git status

# If you accidentally staged everything
git reset  # Unstages all files

# Then stage only what you need
git add specific/file.java

# Commit with descriptive message
git commit -m "[Component] Fix description (+N tests)

Detailed explanation..."

# Check current changes
git diff FILE

# Revert changes
git checkout FILE
```

## Future Improvements to This Prompt

**When you discover new techniques, add them here:**

### Template for New Discoveries
```markdown
### Technique N: [Name]
**When to use:** [Situation]

**Pattern:** [Description]

**Example:** [Concrete example from your session]

**Commands:**
```bash
# Relevant commands
```
```

### Template for New Lessons
```markdown
### Session YYYY-MM-DD: X Fixes, ~Y Tests

**Key Discoveries:**
1. [Bug description and fix]
2. [Bug description and fix]

**Debugging Insights:**
- [New technique or insight]

**Productivity Factors:**
- [What worked well]
```

## Efficiency Recommendations (Based on Today's Session)

### Quick Decision Framework

**When to fix immediately:**
- Pass rate > 90% with < 30 failures
- Clear error pattern (same message repeated)
- Estimated effort < 1 hour
- Single root cause identified
- Similar to previously fixed issues

**When to create prompt document:**
- Estimated effort > 2 hours
- Multiple root causes (requires systematic approach)
- Requires architectural changes
- Complex runtime/memory management issues
- High potential impact but unclear path forward

**When to skip:**
- Pass rate < 50% (likely missing features)
- Requires unimplemented subsystems
- Edge cases with minimal impact
- Already documented in existing prompt

### Optimal Session Structure

**Hour 1: Quick Wins (Target: 2-3 fixes, 10-20 tests)**
- Start with 90%+ pass rate tests
- Look for validation/parsing bugs
- Fix obvious inconsistencies
- Build momentum with early successes

**Hour 2: Deep Dive (Target: 1-2 fixes, 20-30 tests)**
- Tackle one complex issue with high impact
- Use bytecode analysis if needed
- Create minimal test cases
- Verify fix thoroughly

**Hour 3: Documentation (Target: 1-2 prompt docs)**
- Analyze remaining high-value targets
- Create prompt documents for complex issues
- Update strategy document with learnings
- Commit all work with clean messages

### Essential Tools Checklist

Before starting a fix, have these ready:
- `jq` for analyzing out.json test results
- `--disassemble` for bytecode analysis
- Minimal test case template
- Comparative testing (perl vs ./jperl)
- grep/search for finding implementations
- Memory context from previous work

### Pattern Recognition Shortcuts

**Validation bugs (High ROI):**
- Error: "Invalid type", "can't use", "not allowed"
- Fix: Add/remove validation checks
- Impact: Often 10-100 tests per fix

**Parsing bugs (High ROI):**
- Error: "unsupported format", "syntax error"
- Fix: Add missing parser cases
- Impact: Often 5-50 tests per fix

**Type checking bugs (Medium ROI):**
- Error: Wrong behavior with blessed objects
- Fix: Add `isBlessed()` checks
- Impact: Often 5-10 tests per fix

**Iterator bugs (Medium ROI):**
- Error: NoSuchElementException, wrong order
- Fix: Add bounds checking, fix flattening
- Impact: Often 2-10 tests per fix

### Commit Message Template

```
[Component] Brief description (+N tests)

Fixed [specific bug] that caused [symptom].

Root Cause:
- [What was wrong]
- [Why it was wrong]

Solution:
- [What was changed]
- [Why this fixes it]

Test Results:
- Before: X passing / Y failing (Z% pass rate)
- After: X2 passing / Y2 failing (Z2% pass rate)
- Improvement: +N tests

Files Modified:
- path/to/file.java - [what changed]
```

### Productivity Multipliers

1. **Use memories effectively:** Check for previous work on the same area
2. **Batch tool calls:** Read multiple files in parallel when possible
3. **Create test artifacts:** Save minimal test cases for future reference
4. **Update strategy immediately:** Don't wait until end of session
5. **Clean commits:** 
   - Remove test files before committing
   - NEVER use `git add -A` or `git add .`
   - Always stage specific files: `git add path/to/file.java`
   - Check with `git status` before committing

## Key Learnings from Recent Fixes

### x[template] Fix: The Power of Leveraging Existing Code (+541 tests!)

**Problem:** Needed to calculate packed size for templates in `x[template]` construct.

**Failed Approach:** Static size calculation
- Tried to calculate sizes for each format type manually
- Failed for bit/hex strings where count means bits/digits, not bytes
- Failed for variable-length formats
- Complex, error-prone, caused regressions

**Brilliant Solution:** Pack dummy data and measure the result!
```java
// Instead of calculating size statically...
RuntimeList args = new RuntimeList();
args.add(new RuntimeScalar(template));
// Add dummy values...
RuntimeScalar result = Pack.pack(args);
return result.toString().length(); // Measure actual size!
```

**Why This Works:**
- Leverages existing pack logic (no reimplementation)
- Handles ALL format types automatically
- Perfect accuracy for all cases
- Handles bit strings (b,B), hex strings (h,H), groups, modifiers, etc.
- Simple, maintainable, correct

**Key Insight:** When you need to calculate something complex, check if you can **use the actual operation** instead of reimplementing its logic. This is especially powerful when:
- The operation already exists and is well-tested
- The calculation would be complex and error-prone
- You need to handle many edge cases
- Accuracy is critical

**Result:** +541 tests improvement (9,593 → 10,134 passing tests)

### Pack Group Bug: Return Multiple Values from Methods

**Problem:** `handleGroup()` was incrementing `valueIndex` locally but not returning it, causing values to be lost.

**Solution:** Created `GroupResult` record to return both position and valueIndex:
```java
public record GroupResult(int position, int valueIndex) {}

public static GroupResult handleGroup(...) {
    // ... process group ...
    return new GroupResult(position, valueIndex);
}
```

**Key Insight:** When a method needs to return multiple related values, use a record or result object instead of:
- Modifying parameters (doesn't work with primitives in Java)
- Using arrays or lists (type-unsafe)
- Making multiple method calls (inefficient)

Records are perfect for this pattern in modern Java.

### Regression Investigation: Compare Before/After Logs

**Strategy:**
1. Generate baseline test log (before changes)
2. Generate current test log (after changes)
3. Use `comm` to find newly failing and newly passing tests
4. Analyze patterns in the differences

**Commands:**
```bash
# Find newly failing tests
comm -13 /tmp/baseline_failures.txt /tmp/current_failures.txt

# Find newly passing tests  
comm -23 /tmp/baseline_failures.txt /tmp/current_failures.txt

# Count differences
wc -l on each
```

**Key Insight:** Don't just look at the net change in test count. A -131 regression might actually be:
- 2,208 newly failing tests
- 2,128 newly passing tests
- Net: -80 tests

Understanding the full picture helps identify the root cause.

## Remember

1. **Update this file** when you learn something new
2. **Test incrementally** - verify each hypothesis
3. **Document complex issues** - create prompts for future sessions
4. **Focus on high-yield targets** - 70-95% pass rates
5. **Use existing code** - search before implementing
6. **Strategic logging** - targeted, not everywhere
7. **Commit often** - small, focused commits
   - Stage ONLY modified source files, never use `git add -A`
   - Check `git status` before every commit
   - Remove test files before committing
8. **Use bytecode analysis** - when behavior is unclear
9. **Test with overloaded objects** - for operator implementations
10. **Balance quick wins and deep dives** - maintain momentum
11. **Leverage existing operations** - pack dummy data instead of reimplementing logic
12. **Use records for multiple return values** - cleaner than arrays or multiple calls
13. **Compare before/after logs** - understand full regression picture, not just net change

---

**This is a living document. Keep it updated with your learnings!** 
