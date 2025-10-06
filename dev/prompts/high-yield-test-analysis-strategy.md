# High-Yield Test Analysis Strategy

## Core Principle
**Target blocked tests and systematic errors for exponential impact.** One fix can unlock hundreds of tests.

## Quick Start: Finding Targets

### 1. Check Blocked Tests First (Highest ROI)
```bash
# Find incomplete test runs
perl dev/tools/perl_test_runner.pl t 2>&1 | tee logs/test_$(date +%Y%m%d_%H%M%S)
grep -A 15 "incomplete test files" logs/test_*
```

**Common blockers:**
- `"operator not implemented"` → Implement in OperatorHandler.java (can unlock 400+ tests)
- `"Variable does not contain"` → Syntax parsing issue, use `--parse` to debug AST
- `"compilation failed"` → AST/parser problem
- `"Not implemented: X"` → Missing feature implementation

### 2. Analyze Pass Rates
```bash
# Find tests with 70-95% pass rate (focused bugs, not missing features)
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
# ALWAYS use shadowJar for parser/AST changes
./gradlew clean shadowJar

# Test immediately after changes
./gradlew test
```

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
5. Run `./gradlew test` for regressions

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
```markdown
# Fix [Issue Name]

## Objective
Clear problem statement and impact.

## Current Status
- Test file: path/to/test.t
- Failures: X tests
- Pass rate: Y%

## Root Cause Analysis
Technical investigation and evidence.

## Proposed Solution
Step-by-step implementation plan.

## Test Cases
Minimal reproductions.

## Expected Impact
Tests that will be fixed.
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

## Final Checklist

Before starting a fix:
- [ ] Check if it's a blocked test (highest priority)
- [ ] Estimate impact (# of tests affected)
- [ ] Set time budget
- [ ] Have minimal test ready

After implementing:
- [ ] Test with minimal case
- [ ] Test with full test suite
- [ ] Run `./gradlew test`
- [ ] Commit with clear message
- [ ] Update docs if new pattern discovered
