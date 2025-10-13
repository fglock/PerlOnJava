# High-Yield Test Analysis Strategy

## 📏 DOCUMENTATION RULE: Keep It Concise

**All analysis documents and status reports MUST be:**
- **Maximum 2kB or 400 words**
- Focus on: Problem → Root Cause → Solution
- Avoid: Verbose explanations, redundant context, excessive code blocks

## 🚨 CRITICAL: NEVER COMMIT WITHOUT `make test`

**YOU WILL BREAK THE BUILD if you skip this!**

Even if your target test passes perfectly, you **MUST** run `make test` before committing. Unit tests catch regressions that integration tests miss.

**The Rule (NO EXCEPTIONS):**
```bash
# ❌ WRONG
./jperl t/op/yourtest.t  # passes
git commit               # BREAKS BUILD!

# ✅ CORRECT
./jperl t/op/yourtest.t  # passes
make test                # MUST pass!
git commit               # Safe
```

**Recent incidents:**
- 2025-10-08: Character/byte mode fix broke unit tests (unpack.t, pack_c0_u0.t)
- 2025-10-07: PackBuffer fix broke pack_utf8.t

## Core Principle
**Target systematic errors for exponential impact.** One fix can unlock hundreds of tests.

## Critical Workflow

```bash
## Test Execution Summary

Run all tests and collect results:
git commit -m "Fix: description (+N tests)"
```

## Safety Rules

1. **NEVER use `git add .` or `git add -A`** - add specific files only
2. **NEVER commit test files** (`test_*.pl`, `debug_*.pl`)  
3. **NEVER commit temporary files** (`*.tmp`, `test_*.tmp`, etc.)
4. **ALWAYS check `git status` before staging** - look for untracked garbage files
5. **ALWAYS run `make test`** before committing
6. **ALWAYS use `make`** for quick builds during development

# Before each session: Safe environment setup  
./dev/tools/safe_analysis_setup.sh

# Before every commit: Full safety check
./dev/tools/pre_commit_check.sh
```

### Method 3: Manual Process (Expert Only)
Follow the detailed steps below, but you MUST complete the "Essential Prerequisites" section first.

## Essential Prerequisites: Clean Environment Setup

### 1. Kill Old Java Processes (Critical!)
```bash
# Kill any hanging Java processes from previous test runs
pkill -f "java.*org.perlonjava" || true
ps aux | grep -E "java.*org.perlonjava|jperl" | grep -v grep | awk '{print $2}' | xargs -r kill -9 2>/dev/null || true

# Verify no lingering processes
ps aux | grep -E "java.*org.perlonjava|jperl" | grep -v grep
```

### 2. Clean Build Environment
```bash
# Clean all previous build artifacts
./gradlew clean

# Remove any temporary test files (NEVER commit these!)
rm -f test_*.pl debug_*.pl 2>/dev/null || true
rm -rf logs/*.log 2>/dev/null || true

# Verify git status is clean before starting
git status --short | grep -E "^\?\?" && echo "WARNING: Untracked files found - clean before starting!"
```

### 3. Build Fresh Binaries
```bash
# Full clean build with all components
./gradlew clean shadowJar

# Verify build success
echo 'print "Build OK\n"' | ./jperl || (echo "Build failed!" && exit 1)
```

### 4. Run Baseline Tests
```bash
# Create logs directory if needed
mkdir -p logs

# Run quick sanity check
./gradlew test || echo "Note: Some tests may fail - this is baseline"

# Generate initial test report
perl dev/tools/perl_test_runner.pl t 2>&1 | tee logs/baseline_$(date +%Y%m%d_%H%M%S).log
```

### 5. Verify Clean State
```bash
# Ensure no resource leaks or hanging processes
lsof | grep -E "jperl|org.perlonjava" | wc -l  # Should be 0 or very low
df -h .  # Check disk space
free -h  # Check memory
```

### Common Environment Issues & Solutions

**Problem: Java processes won't die**
```bash
# Force kill with stronger signal
sudo kill -9 $(ps aux | grep -E "java.*org.perlonjava" | grep -v grep | awk '{print $2}')
# If still stuck, may need system restart
```

**Problem: Build fails with "out of memory"**
```bash
# Increase heap size
export GRADLE_OPTS="-Xmx4g -XX:MaxMetaspaceSize=1g"
./gradlew clean shadowJar
```

**Problem: Port already in use (for network tests)**
```bash
# Find and kill process using port
lsof -i :8080 | grep LISTEN | awk '{print $2}' | xargs kill -9
```

**Problem: Disk space issues**
```bash
# Clean Gradle cache
rm -rf ~/.gradle/caches/
# Clean test artifacts
find . -name "*.class" -delete
find . -name "test_*.pl" -delete
find . -name "debug_*.pl" -delete
```

## Quick Start: Finding Targets

### 0. VERIFY SETUP (Required Before Analysis)
```bash
# Check that environment setup was completed
if [ ! -f .perlonjava_env_ready ]; then
    echo "❌ ERROR: Environment not set up!"
    echo "Run: ./dev/tools/safe_analysis_setup.sh"
    exit 1
fi

# Verify setup is recent (within 4 hours)
if [ $(find . -name ".perlonjava_env_ready" -mmin +240 | wc -l) -gt 0 ]; then
    echo "⚠️  Setup is stale (>4 hours old)"
    echo "Run: ./dev/tools/safe_analysis_setup.sh"
fi
```

### 💡 OPTIMIZATION TIP: Cache Analysis Results
```bash
# Save the heavy analysis output to avoid re-running every time
ANALYSIS_FILE="logs/analysis_$(date +%Y%m%d).json"

# Check if today's analysis exists
if [ -f "$ANALYSIS_FILE" ]; then
    echo "✅ Using cached analysis from: $ANALYSIS_FILE"
    cp "$ANALYSIS_FILE" out.json
else
    echo "📊 Running fresh analysis (this may take a while)..."
    perl dev/tools/perl_test_runner.pl t 2>&1 | tee logs/test_$(date +%Y%m%d_%H%M%S).log
    cp out.json "$ANALYSIS_FILE"  # Save for later use
    echo "💾 Analysis saved to: $ANALYSIS_FILE"
fi

# Now use out.json for all subsequent queries
# This avoids re-running the heavy test runner multiple times
```

### 1. Check Blocked Tests First (Highest ROI)
```bash
# Use cached analysis if available (see Optimization Tip above)
if [ ! -f out.json ]; then
    perl dev/tools/perl_test_runner.pl t 2>&1 | tee logs/test_$(date +%Y%m%d_%H%M%S).log
fi
grep -A 15 "incomplete test files" logs/test_*
```

**Common blockers:**
- `"operator not implemented"` → Implement in OperatorHandler.java (can unlock 400+ tests)
- `"Variable does not contain"` → Syntax parsing issue, use `--parse` to debug AST
- `"compilation failed"` → AST/parser problem
- `"Not implemented: X"` → Missing feature implementation

### 2. Analyze Pass Rates
```bash
# Uses cached out.json from analysis (see Optimization Tip above)
jq -r '.results | to_entries[] | select(.value.ok_count > 50 and .value.not_ok_count > 15 and .value.not_ok_count < 100) | "\(.value.not_ok_count) failures / \(.value.ok_count) passing (\(.value.ok_count * 100 / .value.total_tests | floor)%) - \(.key)"' out.json | sort -rn | head -20
```

### 3. Pattern Recognition
```bash
# Look for repeated error messages
./jperl t/op/TESTFILE.t 2>&1 | grep "^not ok" | head -20
```

## Time Management

| Complexity | Budget | Stop When |
|------------|--------|-----------|
| Easy | 15-30 min | Clear fix found |
| Medium | 30-60 min | Root cause found |
| Hard | 60-90 min | Create prompt doc |

**Checkpoints:** 15 min (reproduced?), 30 min (root cause?), 45 min (fix clear?), 60 min (document?)

**Create prompt doc if:**
- Multiple subsystems involved
- Unfamiliar architecture
- New Java classes needed
- AST/compilation changes required

## High-Impact Fix Patterns

### Missing Operators (Often 100+ tests)
```java
// Add to OperatorHandler.java
put("operator_name", "operator_name", "org/perlonjava/operators/ClassName", 
    "(I[Lorg/perlonjava/runtime/RuntimeBase;)Lorg/perlonjava/runtime/RuntimeScalar;");

// Implement in appropriate operator class
public static RuntimeScalar operator_name(int ctx, RuntimeBase... args) {
    // Often can leverage existing infrastructure
}
```

### Context-Aware Operations
Many failures are due to missing context handling:
```java
// Add int ctx parameter
public ReturnType operation(Parameters..., int ctx) {
    return switch(ctx) {
        case RuntimeContextType.SCALAR -> scalarBehavior();
        case RuntimeContextType.LIST -> listBehavior();
        default -> defaultBehavior();
    };
}
```

### Parser AST Transformations
**Critical insight:** When fixing parser issues with special syntax:
1. AST must be transformed, not just annotated
2. Symbol table and AST must stay in sync
3. Emitter uses AST structure, not symbol table

```java
// Example: Transform nodes in parser
if (hasTransformation) {
    listNode.elements.clear();
    listNode.elements.addAll(transformedElements);
}
```

### Validation/Error Messages
Small validation fixes can unlock many tests:
- Check error message format matches Perl exactly
- Validation order matters (precedence)
- One validation fix often addresses multiple test failures

## Critical Build & Debug Commands

### Build Properly
```bash
# Quick compilation during development (incremental - may miss changes!)
make

# CRITICAL: For regex/parser changes, ALWAYS use clean shadowJar
# Incremental builds (make) often don't pick up regex preprocessing changes!
./gradlew clean shadowJar installDist

# Alternative: Use make dev for forced recompilation
make dev

# Test immediately after changes
./gradlew test
```

**⚠️ IMPORTANT BUILD RULE:**
- **Regex changes** (RegexPreprocessor, RuntimeRegex, etc.) → MUST use `./gradlew clean shadowJar installDist`
- **Parser/AST changes** → MUST use `./gradlew clean shadowJar`
- **Simple operator changes** → `make` is usually sufficient
- **When in doubt** → Use `./gradlew clean shadowJar installDist` to be safe

### Debug AST Issues
```bash
# Examine AST structure
echo 'code here' | ./jperl --parse

# Compare with Perl
perl -e 'code' > perl_out
./jperl -e 'code' > jperl_out
diff perl_out jperl_out
```

### Bytecode Analysis
```bash
# Understand compilation
./jperl --disassemble -e 'code' 2>&1 | grep -A 20 "methodName"
```

### Extract Failing Tests
When debugging large test files:
1. Find exact failing test: `./jperl t/op/test.t 2>&1 | grep -A 3 "^not ok NUMBER"`
2. Extract minimal reproduction
3. Test in isolation
4. Add debug output only to extracted version

## Key Code Locations

### Common Fix Points
- **Missing operators:** `OperatorHandler.java` + implementation class
- **Parser issues:** `OperatorParser.java`, `ParsePrimary.java`
- **Context bugs:** Look for missing `int ctx` parameters
- **Validation:** Pack/unpack in `operators/pack/`, regex in `regex/`
- **Special variables:** `GlobalContext.java`, `SpecialVariables.java`

### Where to Look for Existing Code
```bash
# Find similar functionality
grep -r "similar_feature" src/main/java --include="*.java"

# Find method implementations
grep -r "public.*methodName" src/main/java/

# Find where errors originate
grep -r "exact_error_message" src/main/java/
```

## Proven High-Yield Targets

### Quick Wins (< 30 min, high impact)
- Missing operators in blocked tests
- Validation/error message format fixes
- Context parameter additions
- Boundary condition handling (EOF, negative indices)

### Medium Effort (30-60 min, good ROI)
- Parser syntax transformations
- Pack/unpack format validations
- Regex error conversions
- Special variable implementations

### Document for Later (> 60 min)
- Multi-file refactoring
- New subsystem implementation
- Complex runtime behaviors
- Threading/concurrency issues

## Testing Strategy

### Minimal Test Template
```perl
#!/usr/bin/perl
use strict;
use warnings;

# Minimal reproduction
my $result = operation();
my $expected = "expected_value";
print ($result eq $expected ? "PASS" : "FAIL: got '$result', expected '$expected'"), "\n";
```

### Incremental Verification
1. Create minimal test case
2. Verify with `--parse` if parser issue
3. Test fix with minimal case
4. Test with full test file
5. **ALWAYS run `make test` for regressions** (not optional!)
6. Review unit test failures carefully - they often reveal edge cases

## Critical Insights

### Parser Changes
- **MUST use `./gradlew clean shadowJar`** - `compileJava` alone won't work
- AST transformations needed, not just annotations
- Test with `--parse` flag to verify

### Performance Patterns
- One missing operator can block entire test files
- Validation fixes often affect multiple tests
- Context bugs create systematic failures

### Common Pitfalls
- Not testing after every commit
- Using wrong build command for parser changes
- Fixing symptoms instead of root causes
- Not creating minimal test cases

## When to Create Prompt Documents

Create a prompt document in `dev/prompts/` when:
- Investigation exceeds 60 minutes
- Fix requires architectural changes
- Multiple interconnected systems involved
- High value but complex implementation

### Prompt Document Template

**CRITICAL: Keep analysis documents concise!**
- **Maximum length**: 2kB or 400 words
- **Focus**: Problem, root cause, solution only
- **Avoid**: Verbose explanations, redundant context, long code blocks

```markdown
# Fix [Issue Name]

## Objective
Clear problem statement and impact. (1-2 sentences)

## Current Status
- Test file: path/to/test.t
- Failures: X tests
- Pass rate: Y%

## Root Cause Analysis
Technical investigation and evidence. (2-3 sentences max)

## Proposed Solution
Step-by-step implementation plan. (Bullet points, concise)

## Test Cases
Minimal reproductions. (Code only, no explanations)

## Expected Impact
Tests that will be fixed. (1 sentence)
```

## Success Metrics

### Exceptional ROI
- Blocked test unblocked (often 100+ tests)
- Missing operator implemented
- Syntax issue fixed

### Good ROI
- Validation fix (10-50 tests)
- Context bug fix (20-40 tests)
- Boundary condition fix (5-20 tests)

### Time Investment Guide
- < 10 tests/hour: Consider documenting instead
- 10-50 tests/hour: Good investment
- 50+ tests/hour: Excellent, prioritize these
- 100+ tests/hour: Exceptional, drop everything else

## Process Monitoring During Analysis

### Watch for Issues
```bash
# Monitor memory usage (run in separate terminal)
watch -n 5 'ps aux | grep -E "java.*org.perlonjava|jperl" | grep -v grep'

# Check for hanging tests
timeout 30 ./jperl test_file.pl || echo "Test hung - investigate"

# Kill hanging process if needed
pkill -f "test_file.pl"
```

### Quick Reset Script
Save as `reset_env.sh`:
```bash
#!/bin/bash
echo "Killing old processes..."
pkill -f "java.*org.perlonjava" || true
ps aux | grep -E "java.*org.perlonjava|jperl" | grep -v grep | awk '{print $2}' | xargs -r kill -9 2>/dev/null || true

echo "Cleaning build..."
./gradlew clean
rm -f test_*.pl debug_*.pl 2>/dev/null || true

echo "Rebuilding..."
./gradlew clean shadowJar

echo "Verification..."
echo 'print "Environment ready!\n"' | ./jperl || exit 1
```

## Git Safety & Pre-Commit Protocol

### CRITICAL: Files to NEVER Commit
```bash
# Add to .gitignore if not already there:
test_*.pl
debug_*.pl
*.tmp
test_*.tmp
*.log
logs/
tmp/
*.class
.DS_Store
*.swp
*~
.perlonjava_env_ready

# Common garbage patterns from test runs:
test_io_pipe_*.tmp
test_read_operator_*.tmp
```

### Before ANY Commit - Mandatory Steps
```bash
#!/bin/bash
# Save as pre_commit_check.sh

echo "=== PRE-COMMIT SAFETY CHECK ==="

# 1. Check for test files and temporary garbage
TEST_FILES=$(git status --porcelain | grep -E "test_.*\.(pl|tmp)|debug_.*\.pl|\.tmp$")
if [ ! -z "$TEST_FILES" ]; then
    echo "❌ ABORT: Test/temporary files detected in git staging:"
    echo "$TEST_FILES"
    echo "Run: git reset HEAD test_*.pl debug_*.pl *.tmp"
    exit 1
fi

# 2. Check for untracked garbage files
GARBAGE=$(git status --short | grep "^??" | grep -E "test_.*\.(pl|tmp)|\.tmp$")
if [ ! -z "$GARBAGE" ]; then
    echo "⚠️  WARNING: Untracked garbage files found:"
    echo "$GARBAGE"
    echo "Clean with: rm -f test_*.pl debug_*.pl *.tmp test_*.tmp"
    read -p "Clean now? (y/n) " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        rm -f test_*.pl debug_*.pl *.tmp test_*.tmp
        echo "✓ Cleaned"
    fi
fi

# 3. Clean test artifacts
rm -f test_*.pl debug_*.pl *.tmp test_*.tmp 2>/dev/null
rm -rf logs/*.log 2>/dev/null

# 3. Full rebuild
echo "Building..."
./gradlew clean shadowJar || exit 1

# 4. Run FULL test suite (MANDATORY - catches regressions!)
echo "Running full test suite..."
./gradlew test || {
    echo "❌ ABORT: Test suite failed!"
    echo "Fix failures before committing."
    exit 1
}

# 5. Run specific test if provided (in addition to full suite)
if [ ! -z "$1" ]; then
    echo "Running specific test: $1"
    ./jperl "$1" || exit 1
fi

# 6. Check for regressions
echo "Checking key functionality..."
echo 'print "Hello World\n"' | ./jperl || exit 1
echo '$x = 42; print "$x\n"' | ./jperl | grep -q "42" || exit 1

# 7. Final git status check
echo "=== Git Status ==="
git status

echo "✅ Pre-commit checks passed!"
echo "Safe to commit with: git commit -m 'your message'"
```

### Safe Commit Workflow
```bash
# 1. Always check what you're committing (look for garbage!)
git status
git status --short | grep "^??"  # Check for untracked files

# 2. Clean any temporary files
rm -f test_*.pl debug_*.pl *.tmp test_*.tmp

# 3. Verify only intended files remain
git status --short

# 4. Run pre-commit check
./pre_commit_check.sh

# 5. Commit ONLY intended changes
git add src/main/java/specific/File.java  # Add specific files, not everything!
git commit -m "Fix: specific issue description"

# 6. Verify commit doesn't include garbage
git show --name-only | head -20

# 7. NEVER use these dangerous commands:
# git add .                    # DON'T - adds everything including garbage
# git add -A                   # DON'T - adds all changes including temp files
# git commit -a                # DON'T - commits all modified files
```

### Automated Git Hook (RECOMMENDED)
```bash
# Install as .git/hooks/pre-commit (make executable)
#!/bin/bash
# Prevents committing test files and temporary garbage

# Check for test files and temp files in staging
if git diff --cached --name-only | grep -E "test_.*\.(pl|tmp)|debug_.*\.pl|\.tmp$"; then
    echo "❌ ERROR: Attempting to commit test/debug/temp files!"
    echo "Remove with: git reset HEAD test_*.pl debug_*.pl *.tmp"
    exit 1
fi

# Warn about logs
if git diff --cached --name-only | grep -E "\.log$"; then
    echo "⚠️  WARNING: Log files detected in commit"
    echo "Consider removing with: git reset HEAD *.log"
fi

# Check for common garbage patterns
if git diff --cached --name-only | grep -E "test_io_pipe_.*\.tmp|test_read_operator_.*\.tmp"; then
    echo "❌ ERROR: Test-generated temporary files detected!"
    echo "These are garbage files from test runs."
    echo "Remove with: git reset HEAD *.tmp && rm -f *.tmp"
    exit 1
fi

exit 0
```

### Emergency Cleanup
```bash
# If you accidentally staged test/temp files:
git reset HEAD test_*.pl debug_*.pl *.tmp
git clean -f test_*.pl debug_*.pl *.tmp

# If you accidentally committed garbage files:
git reset --soft HEAD~1  # Undo last commit, keep changes
rm -f test_*.pl debug_*.pl *.tmp test_*.tmp  # Clean garbage
git status --short  # Verify only intended files remain
# Then recommit properly with only intended files

# If garbage made it into the commit:
git reset --hard HEAD~1  # DESTRUCTIVE: Undo commit and discard changes
# Then redo your work without the garbage

# Nuclear option - remove ALL untracked files (VERY CAREFUL!)
git clean -fd  # Removes all untracked files and directories
git clean -fdn  # DRY RUN - see what would be deleted first
```

## 📋 TL;DR - The Foolproof Workflow

### For New Users (Safest Path)
```bash
# 1. Start here EVERY time:
./dev/tools/start_analysis.sh

# 2. When ready to commit:
./dev/tools/pre_commit_check.sh
git add src/main/java/specific/File.java  # Add SPECIFIC files only
git commit -m "Fix: specific issue"
```

### For Experienced Users
```bash
# 1. Setup (required each session):
./dev/tools/safe_analysis_setup.sh

# 2. Analysis:
# ... do your work ...

# 3. Before commit - CRITICAL STEPS:
rm -f test_*.pl debug_*.pl *.tmp test_*.tmp  # Clean ALL garbage
git status --short | grep "^??"              # Check for untracked files
./dev/tools/pre_commit_check.sh              # Run safety check
git add [specific files only]                # NEVER use git add . or -A
git status --short                            # Verify staging
git commit -m "Fix: description"
git show --name-only | head -20               # Verify no garbage in commit
```

### The Golden Rules
1. **ALWAYS** use `start_analysis.sh` or run setup first
2. **NEVER** skip the environment setup
3. **NEVER** use `git add .` or `git add -A` - they add garbage files
4. **ALWAYS** check `git status` for untracked files before staging
5. **ALWAYS** clean test/temp files before committing: `rm -f test_*.pl debug_*.pl *.tmp`
6. **ALWAYS** run pre-commit check
7. **ALWAYS** verify commit with `git show --name-only` after committing

## Final Checklist

Before starting ANY analysis session:
- [ ] Run environment setup (Section: Essential Prerequisites)
- [ ] Kill all old Java processes
- [ ] Clean and rebuild with `./gradlew clean shadowJar`
- [ ] Verify clean state with test run

Before starting a fix:
- [ ] Check if it's a blocked test (highest priority)
- [ ] Estimate impact (# of tests affected)
- [ ] Set time budget
- [ ] Have minimal test ready

After implementing:
- [ ] Test with minimal case
- [ ] Test with full test file (e.g., `./jperl t/op/pack.t`)
- [ ] **CRITICAL: Run `make test` to catch ALL regressions** (don't skip this!)
- [ ] Review any unit test failures - they reveal edge cases
- [ ] Check for memory leaks or hanging processes
- [ ] Clean up ALL test/temp files: `rm -f test_*.pl debug_*.pl *.tmp test_*.tmp`
- [ ] Check for untracked garbage: `git status --short | grep "^??"`
- [ ] Verify git status clean: `git status --short`
- [ ] Run pre-commit check: `./pre_commit_check.sh`
- [ ] Add ONLY specific files: `git add src/main/java/specific/File.java`
- [ ] Verify staging: `git status --short`
- [ ] Commit with clear message: `git commit -m "Fix: description"`
- [ ] **CRITICAL: Verify no garbage in commit**: `git show --name-only | head -20`
- [ ] Update docs if new pattern discovered

**Remember**: 
1. Testing only your target file is NOT enough! Unit tests (`make test`) catch subtle bugs that integration tests miss.
2. NEVER use `git add .` or `git add -A` - they will add temporary garbage files from test runs!

## Understanding 0/0 Test Results

Tests showing `0/0` (no tests run) fall into three categories:

### 1. **Correctly Skipped Tests** ✓
Tests that detect missing features and skip themselves gracefully:
- **Missing extensions**: Fcntl, Encode, IPC modules, PerlIO layers
- **Platform incompatibilities**: Detected at runtime via Config checks
- **Output format**: `1..0 # Skip [reason]` with exit code 0
- **Examples**: io/eintr.t, io/layers.t, io/msg.t, io/sem.t, io/semctl.t, io/shm.t

### 2. **Compilation Failures** !
Tests that fail during compilation before any tests can run:
- **Bytecode errors**: Method too large, invalid bytecode generation
- **Parser errors**: Syntax errors, unimplemented features
- **Cannot self-skip**: Failure happens before skip_all() can execute
- **Examples**: 
  - io/pipe.t - Bytecode generation failure (requires fork)
  - io/fs.t - Bareword filehandle issue (FIXED 2025-10-13)

### 3. **TAP Format**
The `0/0` output is correct TAP format: "0 tests run out of 0 planned" signals to the test harness that the entire test file was skipped. This is expected behavior, not an error.
