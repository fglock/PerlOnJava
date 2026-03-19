# caller() Line Number Fix Plan

## Overview

This document describes the fix for incorrect line numbers reported by `caller()` when accessing stack frames. The issue affects Log::Log4perl and other modules that rely on `caller($level)` with level > 1.

## Problem Statement

When `caller($level)` is called with higher levels to look up the call stack, it reports incorrect line numbers. Instead of the actual source line where the call was made, it reports a line near the end of the file.

### Expected vs Actual Behavior

```perl
# File: test.pl (51 lines total)
package App;
sub handler {
    my $logger = Logger->new();
    $logger->log("Test");  # LINE 35 - this is the call site we want
}

package main;
App::handler();

# Inside Logger->log():
my @c = caller(2);
# Expected: $c[2] == 35 (the line where $logger->log was called)
# Actual:   $c[2] == 48 (near end of file)
```

### Affected Tests

- **Log::Log4perl t/024WarnDieCarp.t**: 8 failing tests (51-53, 58, 60, 62, 67, 69)
- Any module using `caller()` with level > 1 to find "real" callers

## Root Cause Analysis

### The Architecture

PerlOnJava maps Perl source locations to JVM bytecode using:

1. **Token Index**: Each token in the source has a unique index
2. **ByteCodeSourceMapper**: Maps tokenIndex → (lineNumber, package, subroutine, sourceFile)
3. **JVM Line Number Table**: Stores tokenIndex (not actual line) in bytecode metadata
4. **ExceptionFormatter**: Converts JVM stack traces to Perl stack traces using the mapping

### The Bug

In `ByteCodeSourceMapper.saveSourceLocation()` (line 166):

```java
int lineNumber = ctx.errorUtil.getLineNumber(tokenIndex);
```

The `getLineNumber()` method uses a **forward-only cache**:

```java
public int getLineNumber(int index) {
    // If requesting a PAST index, return the STALE cached value!
    if (index <= tokenIndex) {
        return lastLineNumber;  // BUG: Returns wrong value
    }
    // Count forward from cache position...
}
```

**The problem occurs when:**

1. Main code is parsed first (tokenIndex advances to end of file, `lastLineNumber` = 48)
2. Subroutine bodies are compiled later (closure capture deferred compilation)
3. `saveSourceLocation()` is called for subroutine code at tokenIndex 345
4. Since 345 < current cache position, `getLineNumber(345)` returns 48 (cached end-of-file value)
5. TokenIndex 345 is incorrectly mapped to line 48 instead of line 35

### Debug Evidence

```
# First call during parse (CORRECT):
DEBUG saveSourceLocation: STORE tokenIndex=327 line=35 pkg=App sub=handler

# Second call during emit (WRONG - line should be ~35, not 48):
DEBUG saveSourceLocation: STORE tokenIndex=345 line=48 pkg=App sub=handler

# Lookup during caller():
DEBUG parseStackTraceElement: lookupTokenIndex=345 foundTokenIndex=345 line=48 pkg=App
# Returns line=48 (WRONG) instead of line=35 (CORRECT)
```

## Solution

### Fix 1: Use Accurate Line Number Calculation

Replace `getLineNumber()` with `getLineNumberAccurate()` in `saveSourceLocation()`:

**File:** `src/main/java/org/perlonjava/backend/jvm/ByteCodeSourceMapper.java`

```java
// Line 166: Change from
int lineNumber = ctx.errorUtil.getLineNumber(tokenIndex);

// To:
int lineNumber = ctx.errorUtil.getLineNumberAccurate(tokenIndex);
```

The `getLineNumberAccurate()` method always counts from the beginning of the file, so it's safe for out-of-order access:

```java
public int getLineNumberAccurate(int index) {
    int lineNumber = 1;
    for (int i = 0; i <= index && i < tokens.size(); i++) {
        LexerToken tok = tokens.get(i);
        if (tok.type == LexerTokenType.EOF) break;
        if (tok.type == LexerTokenType.NEWLINE) {
            lineNumber++;
        }
    }
    return lineNumber;
}
```

### Fix 2: Performance Optimization (Optional)

If the O(n) counting becomes a performance concern, we can optimize by:

1. Pre-computing line numbers for all tokens during lexing
2. Building a TreeMap<tokenIndex, lineNumber> during the first pass
3. Using binary search for lookups

However, this is likely unnecessary since:
- `saveSourceLocation()` is only called once per bytecode instruction
- The token list is small for most files
- This code path is only hit during compilation, not runtime

## How to Reproduce

### Minimal Test Case

```perl
#!/usr/bin/perl
use strict;
use warnings;

package Logger;
sub format_line {
    my ($level) = @_;
    my @c = caller($level);
    return defined($c[2]) ? $c[2] : "undef";
}

sub log_msg {
    my $msg = shift;
    my $line = format_line(2);
    return "$msg at line $line";
}

package Logger::Logger;
sub new { bless {}, shift }
sub log {
    my ($self, $msg) = @_;
    return Logger::log_msg($msg);
}

package App;
sub handler {
    my $logger = Logger::Logger->new();
    my $result = $logger->log("Test message");  # LINE 28
    return $result;
}

package main;
my $output = App::handler();
print "Output: $output\n";
print "Expected: 'Test message at line 28'\n";
```

### Running the Test

```bash
# With Perl (correct):
perl test.pl
# Output: Test message at line 28

# With PerlOnJava (wrong before fix):
./jperl test.pl
# Output: Test message at line XX (near end of file)

# Debug mode:
DEBUG_CALLER=1 ./jperl test.pl 2>&1 | grep "STORE\|adding frame"
```

## Unit Test Location

Create test file: `src/test/resources/unit/caller_line_number.t`

```perl
use strict;
use warnings;
use Test::More tests => 6;

# Test 1: Basic caller(0) - same function
sub test_caller_0 {
    my @c = caller(0);
    return $c[2];  # Return line number
}
my $line1 = __LINE__ + 1;
my $result1 = test_caller_0();
# caller(0) returns info about where test_caller_0 is being called FROM
# which is the current context (main), not inside the function
# Actually, caller(0) inside the function returns the caller OF that function
is($result1, $line1, "caller(0) returns correct line");

# Test 2: caller(1) - one level up
sub inner { my @c = caller(1); return $c[2]; }
sub outer { inner(); }
my $line2 = __LINE__ + 1;
my $result2 = outer();
is($result2, $line2, "caller(1) returns correct line");

# Test 3: caller(2) - two levels up (the bug case)
sub level3 { my @c = caller(2); return $c[2]; }
sub level2 { level3(); }
sub level1 { level2(); }
my $line3 = __LINE__ + 1;
my $result3 = level1();
is($result3, $line3, "caller(2) returns correct line");

# Test 4: caller(3) - three levels up
sub d4 { my @c = caller(3); return $c[2]; }
sub d3 { d4(); }
sub d2 { d3(); }
sub d1 { d2(); }
my $line4 = __LINE__ + 1;
my $result4 = d1();
is($result4, $line4, "caller(3) returns correct line");

# Test 5: Different packages (like Log4perl)
package Logger;
sub format_line {
    my @c = caller(2);
    return $c[2];
}
sub log_call { format_line(); }

package Wrapper;
sub wrap { Logger::log_call(); }

package main;
my $line5 = __LINE__ + 1;
my $result5 = Wrapper::wrap();
is($result5, $line5, "caller(2) correct across packages");

# Test 6: Line number should NOT be near end of file
# This specifically tests the bug where end-of-file line was returned
my $file_end = __LINE__ + 20;  # Approximate end of file
ok($result3 < $file_end - 10, "caller() line is not near EOF (was: $result3, EOF ~$file_end)");
```

## Implementation Steps

1. **Apply the fix** in `ByteCodeSourceMapper.java` line 166
2. **Run the unit test** to verify the fix:
   ```bash
   ./jperl src/test/resources/unit/caller_line_number.t
   ```
3. **Run Log::Log4perl tests** to verify improvement:
   ```bash
   ./jcpan -t Log::Log4perl 2>&1 | grep "024WarnDieCarp"
   ```
4. **Run full test suite** to check for regressions:
   ```bash
   make
   ```

## Files to Modify

| File | Change |
|------|--------|
| `src/main/java/org/perlonjava/backend/jvm/ByteCodeSourceMapper.java` | Line 166: Use `getLineNumberAccurate()` |
| `src/test/resources/unit/caller_line_number.t` | New unit test file |

## Expected Results After Fix

| Test | Before | After |
|------|--------|-------|
| Log::Log4perl t/024WarnDieCarp.t | 8 failures | 0 failures |
| caller_line_number.t | N/A | 6/6 pass |
| Full test suite | No regression | No regression |

## Related Documentation

- `dev/design/log4perl-compatibility.md` - Log::Log4perl compatibility tracking
- `dev/design/caller_stack_fix_plan.md` - Previous caller() fixes

## Progress Tracking

### Status: FIXED

### Checklist
- [x] Root cause identified
- [x] Minimal reproduction case created
- [x] Fix designed and documented
- [x] Unit test written
- [x] Fix implemented
- [x] Unit test passing
- [x] Log::Log4perl tests improved (8→1 failures in t/024WarnDieCarp.t)
- [x] Full test suite passing (no regressions)
- [ ] Code committed and merged
