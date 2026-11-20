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

## 🐛 Debugging Strategy - TRACE Flag Pattern

**New Pattern (2025-10-18):** Add trace flags for systematic debugging:

```java
// Add to class being debugged:
private static final boolean TRACE_PACK = false;  // Toggle for debugging

// Use throughout code:
if (TRACE_PACK) {
    System.err.println("TRACE: method entry, param=" + param);
    System.err.flush();  // Force output
}
```

**Benefits:**
- Leave trace code in place, toggle with flag
- No need to remove/re-add debug statements
- Forces you to understand code flow
- Easy to re-enable when bugs resurface

**Example from pack.t fix:**
```java
// Pack.java
private static final boolean TRACE_PACK = false;

public static RuntimeScalar pack(RuntimeList args) {
    if (TRACE_PACK) {
        System.err.println("TRACE Pack.pack() called:");
        System.err.println("  template: [" + template + "]");
        System.err.flush();
    }
    // ... rest of code
}
```

**When prints don't appear:**
1. Check if jar was rebuilt: `./gradlew build -x test`
2. Verify trace flag is true
3. Check stderr redirection: `2>&1`
4. Try System.err.flush() after each print

## 🔍 Quick Start: Finding High-Yield Targets

### 1. Blocked Tests (Highest ROI - 100+ tests)
```bash
perl dev/tools/perl_test_runner.pl t 2>&1 | tee logs/baseline.log
grep -A 15 "incomplete test files" logs/baseline.log
```

**Common blockers:**
- `"operator not implemented"` → Add to OperatorHandler.java
- `"Variable does not contain"` → Parser issue, use `--parse`
- `"Not implemented: X"` → Missing feature

### 2. Systematic Patterns (10-50 tests each)
```bash
# Find tests with moderate failure rates (good ROI)
jq -r '.results | to_entries[] | select(.value.ok_count > 50 and .value.not_ok_count > 15 and .value.not_ok_count < 100) | "\(.value.not_ok_count) failures - \(.key)"' out.json | sort -rn | head -20

# Look for repeated error messages
./jperl t/op/TESTFILE.t 2>&1 | grep "^not ok" | head -20
```

## ⏱️ Time Management

| Complexity | Budget | Stop When |
|------------|--------|-----------|
| Easy | 15-30 min | Clear fix found |
| Medium | 30-60 min | Root cause found |
| Hard | 60-90 min | Create prompt doc |

**Checkpoints:** 15 min (reproduced?), 30 min (root cause?), 60 min (document & move on?)

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

### Thread-Safe Recursion Depth Tracking (New Pattern 2025-10-18)
```java
// Prevent stack overflow in recursive operations
private static final ThreadLocal<Integer> nestingDepth = ThreadLocal.withInitial(() -> 0);
private static final int MAX_NESTING_DEPTH = 100;

public static Result handleRecursive(...) {
    int currentDepth = nestingDepth.get() + 1;
    if (currentDepth > MAX_NESTING_DEPTH) {
        throw new PerlCompilerException("Too deeply nested in operation");
    }
    nestingDepth.set(currentDepth);
    try {
        return handleRecursiveInternal(...);
    } finally {
        nestingDepth.set(currentDepth - 1);  // Always decrement
    }
}
```

## 🔬 Debug Commands

### AST & Parser Issues
```bash
# View AST structure
./jperl --parse -e 'code'

# Compare with Perl's parse tree
perl -MO=Concise -e 'code'

# View bytecode
./jperl --disassemble -e 'code' 2>&1 | grep -A 20 "methodName"

# Test systematically
./jperl -e 'minimal'       # Simplest case
./jperl -e 'with_context'  # Add complexity
```

### Extract Failing Tests
```bash
# Find specific failure
./jperl t/op/test.t 2>&1 | grep -A 3 "^not ok NUMBER"

# Test in isolation
./jperl -e 'extracted_code'
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
- [ ] Clean temp files: `rm -f test_*.pl debug_*.pl *.tmp`
- [ ] Check for garbage: `git status --short | grep "^??"`
- [ ] Stage specific files only (no `git add .`)
- [ ] Verify commit: `git show --name-only`

### Emergency Cleanup
```bash
# Unstage garbage files
git reset HEAD test_*.pl debug_*.pl *.tmp

# Undo last commit (keep changes)
git reset --soft HEAD~1
```

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

## 🏗️ Known Architectural Issues (2025-10-18)

### W Format UTF-8/Binary Mixing (Tests 5072-5154)

**Problem:** Mixing Unicode formats (W/U) with binary formats (N/V/I) in pack/unpack creates alignment issues.

**Example:**
```perl
$p = pack("W N4", 0x1FFC, 0x12345678, 0x23456781, 0x34567812, 0x45678123);
@v = unpack("x[W] N4", $p);  # Expects: skip 1 char, read 4 ints
```

**What Happens:**
1. `pack("W", 0x1FFC)` produces character U+1FFC (internally: UTF-8 bytes 0xE1 0x9F 0xBC)
2. `pack("N", 0x12345678)` produces bytes 0x12 0x34 0x56 0x78
3. Result: 5-character string with UTF-8 flag set
4. Internal bytes: [0xE1, 0x9F, 0xBC, 0x12, 0x34, 0x56, 0x78, ...]

**Unpack Issue:**
- `x[W]` should skip 1 character (logical view)
- But how many **bytes** is that?
  - In char mode: 1 character = 1 position
  - In byte mode: 1 character = 3 UTF-8 bytes
- `N4` reads from physical UTF-8 bytes, so if we skip wrong amount, misalignment!

**Current Status:**
- `PackParser.calculatePackedSize()` uses ISO-8859-1 encoding to measure byte length
- For W with default dummy value (0), this returns 1 byte
- For high Unicode (e.g., 0x1FFC), returns 1 byte (low byte only) not 3 (UTF-8 bytes)
- **Result:** Tests with default/low values pass, tests with high Unicode fail

**Documented In:**
- `PackParser.calculatePackedSize()` - comprehensive Javadoc
- `docs/PACK_UNPACK_ARCHITECTURE.md` - full explanation

**Potential Solutions:**
1. Make x[W] always work in character mode (skip 1 char position)
2. Make x[W] calculate true UTF-8 byte length (complex!)
3. Document as known limitation and suggest workarounds

**Current Approach:** Option 3 - documented limitation with test analysis.

### Group-Relative Positioning in Pack (Tests 14677+)

**Problem:** The `.` format in pack doesn't support group-relative positioning yet.

**Example:**
```perl
pack("(a)5 .2", 1..5, -3)  # Should work but doesn't
```

**Status:** Not implemented. Unpack has full support, pack needs similar baseline tracking.

**Requires:** Adding group baseline management to PackGroupHandler (similar to UnpackGroupProcessor).

---

## 💡 Key Takeaways

1. **Always rebuild jar:** `./gradlew build` before testing with `./jperl`
2. **Always run `make test`** before committing - catches regressions
3. **Use TRACE flags** instead of ad-hoc debug statements
4. **Target systematic errors** - one fix = many tests
5. **Never `git add .`** - adds garbage temp files
6. **Time-box investigations** - document if >60 minutes
7. **Document architectural limitations** - some issues are complex, document them!

---

**Remember:** Testing your specific file is NOT enough. Unit tests catch subtle bugs that integration tests miss. Always run `make test` before committing!

**New:** See `dev/design/PACK_UNPACK_ARCHITECTURE.md` for comprehensive architecture documentation.
