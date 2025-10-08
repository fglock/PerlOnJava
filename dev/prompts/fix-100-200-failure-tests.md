# Fix High-Yield Tests (100-200 Failures)

## Objective

Fix test files with 100-200 failing tests identified from `logs/test_20251008_145600`. These represent high-impact opportunities where systematic fixes can unlock many tests at once.

## Target Test Files (Priority Order)

### Priority 1: op/heredoc.t - 137 failures (0% pass rate) ⭐
- **Status:** 1/138 tests passing
- **Pass rate:** 0% - Almost complete failure
- **Category:** Heredoc syntax parsing
- **Potential:** VERY HIGH - Likely a single blocking parser/syntax issue
- **Expected Impact:** Could unlock 137 tests with one fix

### Priority 2: run/switches.t - 102 failures (2% pass rate) ⭐
- **Status:** 3/105 tests passing
- **Pass rate:** 2% - Almost complete failure
- **Category:** Command-line switches
- **Potential:** HIGH - Systematic command-line parsing issues
- **Expected Impact:** Could unlock 102 tests with switch handling fixes

### Priority 3: op/packagev.t - 115 failures (62% pass rate)
- **Status:** 192/307 tests passing
- **Pass rate:** 62% - Moderate foundation
- **Category:** Package version handling
- **Potential:** Medium - Version-related operations
- **Expected Impact:** Systematic version parsing/comparison fixes

### Priority 4: op/utf8decode.t - 186 failures (73% pass rate)
- **Status:** 527/713 tests passing
- **Pass rate:** 73% - Good foundation
- **Category:** UTF-8 decoding operations
- **Potential:** Medium-High - UTF-8 edge cases
- **Expected Impact:** UTF-8 decoding boundary conditions

### Priority 5: re/regex_sets_compat.t - 129 failures (94% pass rate)
- **Status:** 2048/2177 tests passing
- **Pass rate:** 94% - Excellent foundation
- **Category:** Regex character sets compatibility
- **Potential:** Medium - Edge cases only
- **Expected Impact:** Regex set edge cases

## Investigation Protocol

### Step 1: Safe Initial Investigation (15 min)

**CRITICAL: Always use timeout to prevent hanging!**

```bash
# 1. Run test with timeout (30 second limit)
timeout 30 ./jperl t/op/heredoc.t 2>&1 | tee test_heredoc_output.txt

# If it times out, try with smaller test:
timeout 30 ./jperl t/run/switches.t 2>&1 | tee test_switches_output.txt

# 2. Check first few failures
head -50 test_heredoc_output.txt | grep -A 5 "^not ok"

# 3. Look for error patterns
grep -E "error|Error|ERROR|died|Died" test_heredoc_output.txt | head -20

# 4. Check if it's a blocker (stops early)
tail -20 test_heredoc_output.txt
```

### Step 2: Pattern Analysis (15 min)

```bash
# Extract error patterns
grep "^not ok" test_heredoc_output.txt | head -20

# Look for repeated error messages
grep -oP "(?<=# ).*" test_heredoc_output.txt | sort | uniq -c | sort -rn | head -10

# Check for compilation errors
grep -E "compilation failed|syntax error|parse error" test_heredoc_output.txt
```

### Step 3: Minimal Reproduction (15 min)

Create minimal test case based on first failure:

```perl
#!/usr/bin/perl
# test_minimal_heredoc.pl
use strict;
use warnings;

# Extract simplest failing case from test file
# Example for heredoc:
my $text = <<'END';
Hello World
END

print "Result: '$text'\n";
print "PASS\n" if $text eq "Hello World\n";
```

Test with timeout:
```bash
timeout 10 ./jperl test_minimal_heredoc.pl
```

### Step 4: Root Cause Analysis (30 min)

Based on test category:

#### For op/heredoc.t (Parser Issue)
```bash
# Check AST parsing
timeout 10 ./jperl --parse -e 'my $x = <<END;
test
END
'

# Look for heredoc handling in parser
grep -r "heredoc\|HERE_DOC\|HEREDOC" src/main/java/org/perlonjava/parser/ --include="*.java"

# Check string parsing
grep -r "parseHeredoc\|parseQuotedString" src/main/java/org/perlonjava/parser/ --include="*.java"
```

#### For run/switches.t (Command-line Issue)
```bash
# Test individual switches with timeout
timeout 5 ./jperl -e 'print "OK\n"'
timeout 5 ./jperl -n -e 'print'
timeout 5 ./jperl -p -e 's/a/b/'
timeout 5 ./jperl -a -F: -e 'print $F[0]'

# Check switch handling
grep -r "parseCommandLine\|processSwitch" src/main/java/org/perlonjava/ --include="*.java"
```

#### For op/packagev.t (Version Handling)
```bash
# Test version operations
timeout 10 ./jperl -e 'package Foo 1.23; print $Foo::VERSION'
timeout 10 ./jperl -e 'use v5.10; print "OK\n"'

# Check version parsing
grep -r "parseVersion\|VERSION" src/main/java/org/perlonjava/parser/ --include="*.java"
```

#### For op/utf8decode.t (UTF-8 Decoding)
```bash
# Test UTF-8 operations
timeout 10 ./jperl -e 'use utf8; my $x = "\x{100}"; print length($x)'
timeout 10 ./jperl -e 'my $x = "\xc4\x80"; utf8::decode($x); print $x'

# Check UTF-8 handling
grep -r "utf8Decode\|decodeUtf8" src/main/java/org/perlonjava/ --include="*.java"
```

## Common Fix Patterns

### Parser Issues (heredoc.t)
- Missing token type in lexer
- Incorrect state machine in string parser
- Heredoc delimiter matching issues
- Line ending handling

**Key Files:**
- `src/main/java/org/perlonjava/lexer/Lexer.java`
- `src/main/java/org/perlonjava/parser/StringParser.java`
- `src/main/java/org/perlonjava/parser/ParsePrimary.java`

### Command-line Switch Issues (switches.t)
- Missing switch implementation
- Incorrect switch combination handling
- Switch argument parsing

**Key Files:**
- `src/main/java/org/perlonjava/Main.java`
- `src/main/java/org/perlonjava/runtime/GlobalContext.java`

### Version Handling Issues (packagev.t)
- Version comparison operators
- Version string parsing
- Package version declaration

**Key Files:**
- `src/main/java/org/perlonjava/parser/PackageParser.java`
- `src/main/java/org/perlonjava/runtime/RuntimeScalar.java`

### UTF-8 Decoding Issues (utf8decode.t)
- Boundary conditions in decode
- Invalid UTF-8 sequence handling
- Character encoding conversions

**Key Files:**
- `src/main/java/org/perlonjava/operators/StringOperators.java`
- `src/main/java/org/perlonjava/runtime/RuntimeScalar.java`

## Time Budget

| Phase | Time | Decision Point |
|-------|------|----------------|
| Initial investigation | 15 min | Can reproduce? |
| Pattern analysis | 15 min | Pattern identified? |
| Minimal reproduction | 15 min | Root cause clear? |
| Root cause analysis | 30 min | Fix approach clear? |
| **CHECKPOINT** | **75 min** | **Implement or document?** |
| Implementation | 30-60 min | Tests passing? |
| Verification | 15 min | No regressions? |

**Stop and document if:**
- Can't reproduce issue in 15 minutes
- Pattern unclear after 30 minutes
- Root cause requires architectural changes
- Multiple subsystems involved

## Safety Checklist

### Before Starting
- [ ] Clean environment: `rm -f test_*.pl debug_*.pl`
- [ ] Fresh build: `make`
- [ ] Kill old processes: `pkill -f "java.*org.perlonjava"`

### During Investigation
- [ ] **ALWAYS use timeout** for test runs: `timeout 30 ./jperl ...`
- [ ] Monitor for hanging: Check process with `ps aux | grep jperl`
- [ ] Save output to files for analysis
- [ ] Create minimal test cases in separate files

### Before Committing
- [ ] Remove all test files: `rm -f test_*.pl debug_*.pl`
- [ ] Run full test suite: `make test`
- [ ] Verify target test: `timeout 60 ./jperl t/op/heredoc.t`
- [ ] Check git status: `git status --short`
- [ ] Commit specific files only: `git add src/main/java/specific/File.java`

## Emergency Procedures

### If Test Hangs
```bash
# Kill hanging process
pkill -f "test_heredoc\|test_switches"
pkill -f "java.*org.perlonjava"

# Check for zombie processes
ps aux | grep -E "jperl|java.*org.perlonjava" | grep -v grep

# Force kill if needed
ps aux | grep -E "java.*org.perlonjava" | grep -v grep | awk '{print $2}' | xargs kill -9
```

### If Out of Memory
```bash
# Clean up
./gradlew clean
rm -rf ~/.gradle/caches/

# Rebuild
./gradlew clean shadowJar
```

## Expected Outcomes

### Success Metrics
- **Excellent:** Fix unlocks 100+ tests (1-2 hours)
- **Good:** Fix unlocks 50+ tests (2-3 hours)
- **Acceptable:** Fix unlocks 20+ tests (3-4 hours)
- **Document:** Complex issue requiring architectural changes

### Deliverables
1. **If fixed:**
   - Commit with clear message: "Fix: [issue] (+N tests in [file])"
   - Update this document with solution
   - Note any patterns for future fixes

2. **If documented:**
   - Create detailed analysis document
   - Include minimal reproduction
   - Propose solution approach
   - Estimate effort required

## Notes Section

### Investigation Log

**Date:** 2025-10-08

**Test File:** [To be filled]

**Initial Findings:**
- [To be filled]

**Root Cause:**
- [To be filled]

**Solution:**
- [To be filled]

**Outcome:**
- [To be filled]

---

## Quick Reference: Timeout Commands

```bash
# Run test with 30s timeout
timeout 30 ./jperl t/op/heredoc.t 2>&1 | tee output.txt

# Run with 60s timeout for slower tests
timeout 60 ./jperl t/run/switches.t 2>&1 | tee output.txt

# Kill if still running
pkill -f "heredoc\|switches"

# Check for hanging processes
ps aux | grep jperl | grep -v grep
```

**REMEMBER: ALWAYS USE TIMEOUT!** Tests may hang indefinitely.
