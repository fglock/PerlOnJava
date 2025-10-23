# High-Yield Test Analysis Strategy

## 🎯 Core Principle
**Target systematic errors for exponential impact.** One fix can unlock hundreds of tests.

## 🚨 CRITICAL: Build & Test Before Commit

**YOU WILL BREAK THE BUILD if you skip this!**

```bash
# ❌ WRONG - Will break the build!
./jperl t/op/test.t    # passes
git commit             # BREAKS OTHER TESTS!

# ✅ CORRECT - Safe workflow
./jperl t/op/test.t    # passes
make test              # MUST pass! Catches regressions
git commit             # Safe
```

**Recent incidents:** local() fix (2025-10-13), pack mode fix (2025-10-08), PackBuffer fix (2025-10-07) - all broke unit tests despite passing integration tests.

## 🔧 Build Commands - CRITICAL for Different Changes

```bash
# Pack/Unpack operator changes → MUST rebuild jar!
./gradlew build -x test         # Rebuilds jar (jperl uses this)
# OR
./gradlew clean shadowJar       # Full clean build

# Parser/AST changes → Full clean build
./gradlew clean shadowJar installDist

# Regex changes → Full clean + install
./gradlew clean shadowJar installDist

# Simple operator changes → Quick build (may miss jar updates!)
make                            # Incremental - use for iteration only

# When in doubt → Full rebuild
./gradlew clean shadowJar
```

**⚠️ CRITICAL LEARNING (2025-10-18):**
- When debugging pack/unpack and prints don't show: **REBUILD THE JAR!**
- `make` or `./gradlew compileJava` only compile classes
- `./jperl` uses the jar file, not loose classes
- **Always run `./gradlew build` after changes before testing with `./jperl`**

## 🐛 Debugging Strategy

**TRACE flags:** Add `private static final boolean TRACE_X = false;` + conditional prints
**When prints don't appear:** Rebuild jar! (`./gradlew build`)

## 🔍 Quick Start: Finding High-Yield Targets

### 1. Compare Test Runs (NEW - 2025-10-22)
```bash
# Run full test suite
perl dev/tools/perl_test_runner.pl t 2>&1 | tee logs/current_run.log

# Compare with previous run to find regressions/progress
./dev/tools/compare_test_logs.pl logs/previous.log logs/current_run.log

# Quick summary only
./dev/tools/compare_test_logs.pl --summary-only logs/previous.log logs/current_run.log

# Focus on regressions in large test files
./dev/tools/compare_test_logs.pl --min-total 1000 --no-show-progress logs/previous.log logs/current_run.log
```

**Key Features:**
- Identifies which test files regressed/improved
- Shows exact test count differences
- Filters by file size or test count difference
- Catches regressions early (like the 27K test drop we found)

### 2. Blocked Tests (Highest ROI - 100+ tests)
```bash
# Find incomplete/errored test files
grep -A 15 "incomplete test files" logs/current_run.log
```

**Common blockers:**
- `"operator not implemented"` → Add to OperatorHandler.java
- `"Variable does not contain"` → Parser issue, use `--parse`
- `"Not implemented: X"` → Missing feature

### 3. Systematic Patterns (10-50 tests each)
```bash
# Find tests with moderate failure rates (good ROI)
jq -r '.results | to_entries[] | select(.value.ok_count > 50 and .value.not_ok_count > 15 and .value.not_ok_count < 100) | "\(.value.not_ok_count) failures - \(.key)"' out.json | sort -rn | head -20
```

## ⏱️ Time Management

| Complexity | Budget | Stop When |
|------------|--------|-----------|
| Easy | 15-30 min | Clear fix found |
| Medium | 30-60 min | Root cause found |
| Hard | 60-90 min | Create prompt doc |

**Checkpoints:** 15 min (reproduced?), 30 min (root cause?), 60 min (document & move on?)

### Regression Response Protocol

**When test count drops unexpectedly:**

1. **Compare logs immediately:**
   ```bash
   perl dev/tools/compare_test_logs.pl logs/baseline.log logs/current.log --summary
   ```

2. **Identify breaking commit:**
   ```bash
   git log --oneline -10
   ```

3. **Revert the commit:**
   ```bash
   # Option A: Clean revert (recommended)
   git revert <breaking-commit-hash>
   
   # Option B: Hard reset (if not pushed)
   git reset --hard <last-good-commit>
   ```

4. **CRITICAL: Rebuild and test:**
   ```bash
   make                        # Must rebuild!
   ./jperl t/op/pack.t         # Verify key files work
   ./jperl t/re/pat.t
   ./jperl t/op/hash.t
   ```

5. **Verify restoration:**
   ```bash
   # Run full suite
   perl dev/tools/perl_test_runner.pl t > logs/after_revert.log
   # Compare
   perl dev/tools/compare_test_logs.pl logs/baseline.log logs/after_revert.log --summary
   ```

**⚠️ CRITICAL LESSON (2025-10-22):**
- `git reset`/`git revert` removes COMMITS from history
- **BUT CODE CHANGES MAY STILL BE IN WORKING TREE!**
- `git reset --soft` keeps changes staged
- Always check: `git diff` and `git status`
- **Always rebuild after ANY git operation**
- **Always test after rebuild**

## 🎨 High-Impact Fix Patterns

### Missing Operators (Often 100+ tests)
```java
// 1. Add to OperatorHandler.java
put("operator_name", "operator_name", "org/perlonjava/operators/ClassName", 
    "(I[Lorg/perlonjava/runtime/RuntimeBase;)Lorg/perlonjava/runtime/RuntimeScalar;");

// 2. Implement in appropriate operator class
public static RuntimeScalar operator_name(int ctx, RuntimeBase... args) {
    // Leverage existing infrastructure
}
```

### Context-Aware Operations
```java
public ReturnType operation(Parameters..., int ctx) {
    return switch(ctx) {
        case RuntimeContextType.SCALAR -> scalarBehavior();
        case RuntimeContextType.LIST -> listBehavior();
        default -> defaultBehavior();
    };
}
```

### Validation/Error Messages
Small validation fixes unlock many tests:
- Error message format must match Perl exactly
- Check error order/precedence
- One validation often fixes multiple tests

### Thread-Safe Recursion (use ThreadLocal + try/finally)

## 🔬 Debug Commands

```bash
# AST/Parser
./jperl --parse -e 'code'
perl -MO=Concise -e 'code'
./jperl --disassemble -e 'code'

# Extract failures
./jperl t/op/test.t 2>&1 | grep -A 3 "^not ok"
```

## 📍 Key Code Locations

- **Missing operators:** `OperatorHandler.java` + implementation class
- **Parser issues:** `OperatorParser.java`, `ParsePrimary.java`
- **Context bugs:** Look for missing `int ctx` parameters
- **Pack/unpack validation:** `operators/pack/`, `operators/unpack/`
- **Special variables:** `GlobalContext.java`, `SpecialVariables.java`

## ✅ Testing Strategy

### Incremental Verification
1. Create minimal test case
2. Test with `--parse` if parser issue
3. Apply fix
4. Test minimal case
5. Test full test file: `./jperl t/op/test.t`
6. **Run `make test` (MANDATORY - catches regressions!)**
7. Review unit test failures - they reveal edge cases

### Minimal Test Template
```perl
#!/usr/bin/perl
use strict;
my $result = operation();
print ($result eq "expected" ? "PASS" : "FAIL: got '$result'\n");
```

## 🚧 Critical Insights

### Parser Changes
- **MUST use `./gradlew clean shadowJar`** - incremental won't work
- AST transformations needed, not just annotations
- Continue parsing for infix operators (., +, -)
- Use `B::Concise` to understand Perl's parse order

### Performance Patterns
- One missing operator can block entire test files
- Validation fixes often affect multiple tests
- Context bugs create systematic failures

### Common Pitfalls
- **Not rebuilding jar** - `./jperl` won't see your changes!
- Not testing after every commit
- Using wrong build command for change type
- Fixing symptoms instead of root causes
- Not creating minimal test cases

## 📝 Environment Setup (Quick)

```bash
# Kill hanging processes
pkill -f "java.*org.perlonjava" || true

# Clean and rebuild
./gradlew clean shadowJar

# Verify
echo 'print "Ready\n"' | ./jperl
```

## 🔒 Git Safety (Condensed)

### Files to NEVER Commit
Add to `.gitignore`: `test_*.pl`, `debug_*.pl`, `*.tmp`, `*.log`, `logs/`

### Safe Commit Workflow
```bash
# 1. Clean garbage
rm -f test_*.pl debug_*.pl *.tmp test_*.tmp

# 2. Check status (look for untracked files!)
git status --short | grep "^??"

# 3. Test
make test                    # MANDATORY

# 4. Stage SPECIFIC files only (NEVER use git add . or -A)
git add src/main/java/specific/File.java

# 5. Commit
git commit -m "Fix: description (+N tests)"

# 6. Verify (check for garbage)
git show --name-only | head -20
```

### Pre-Commit Checklist
- [ ] Run `make test` (catches regressions)
- [ ] Clean temp files
- [ ] Check for garbage
- [ ] Stage specific files only

### Emergency: Reverting Regressions

**CRITICAL: git reset/revert removes COMMITS, not CODE!**

```bash
# When regression detected:

# 1. Find the breaking commit
git log --oneline -10

# 2. OPTION A: Revert the commit (keeps history, creates new commit)
git revert <commit-hash>    # Creates anti-commit
make                         # REBUILD!
./jperl t/op/pack.t         # TEST!

# 3. OPTION B: Reset to before breaking commit (rewrites history)
git reset --hard <good-commit-hash>
make                         # REBUILD!
./jperl t/op/pack.t         # TEST!

# 4. CRITICAL: If you used git rebase/reset to clean history:
#    YOU MUST MANUALLY FIX THE CODE!
#    The commits are gone but code changes remain in working tree.

# 5. Verify the fix restored baseline:
perl dev/tools/compare_test_logs.pl logs/baseline.log logs/current.log --summary
```

**Today's lesson (2025-10-22):**
- Cleaned git history with `git reset --soft` + rebase
- But CODE still had smart chunking enabled!
- Git history ≠ Working tree
- Always REBUILD and TEST after any git operation

## 🎯 Success Metrics

### ROI Guide
- **Exceptional (100+ tests):** Blocked test unblocked, missing operator
- **Good (10-50 tests):** Validation fix, context bug
- **Consider documenting (<10 tests/hour):** Complex architectural changes

### Time Investment
- 50+ tests/hour: Excellent, prioritize
- 10-50 tests/hour: Good investment  
- <10 tests/hour: Consider documenting for later

## 📋 Quick Reference

### When to Create Prompt Documents
- Investigation exceeds 60 minutes
- Multiple interconnected systems
- Architectural changes needed
- High value but complex

### Template (Keep under 400 words!)
```markdown
# Fix [Issue]

## Problem
[1-2 sentences]

## Root Cause
[2-3 sentences, evidence]

## Solution
[Bullet points, concise]

## Expected Impact
[1 sentence, test count]
```

## 🔥 Recent Patterns (2025-10-18)

### TRACE Flag Debugging Pattern
1. Add `private static final boolean TRACE_X = false;` to class
2. Wrap debug code: `if (TRACE_X) { System.err.println(...); System.err.flush(); }`
3. Toggle flag to enable/disable
4. **CRITICAL:** Rebuild jar after changes: `./gradlew build`

### Jar Rebuild Requirement
When debugging and output doesn't appear:
- `./jperl` uses jar, not loose classes
- `make` compiles but doesn't rebuild jar
- **Solution:** `./gradlew build -x test` or `./gradlew clean shadowJar`

### ThreadLocal Depth Tracking
For recursive operations that need depth limits:
- Use `ThreadLocal<Integer>` for thread safety
- Increment on entry, decrement in `finally` block
- Throw exception when depth exceeds limit

## 🏗️ Known Architectural Issues

### Pack/Unpack Complex Cases
Some advanced pack/unpack patterns have documented limitations:
- **W format UTF-8/binary mixing:** Character vs byte alignment issues
- **Group-relative positioning (`.`):** Not fully implemented in pack
- **See:** `dev/design/PACK_UNPACK_ARCHITECTURE.md` for details

---

## 💡 Key Takeaways

1. **Use compare_test_logs.pl** to track regressions between runs
2. **Always rebuild jar:** `./gradlew build` before testing with `./jperl`
3. **Always run `make test`** before committing - catches regressions
4. **Use TRACE flags** instead of ad-hoc debug statements
5. **Target systematic errors** - one fix = many tests
6. **Never `git add .`** - adds garbage temp files
7. **Time-box investigations** - document if >60 minutes

**Remember:** Testing your specific file is NOT enough. Unit tests catch subtle bugs. Always run `make test` before committing!