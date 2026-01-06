# Control Flow Fix Instructions

## Problem Statement

The Perl control flow for labeled blocks with `last LABEL` is broken in scalar context when called through multiple stack frames. This affects Test::More's `skip()` function and other labeled block control flow.

## Current Status

### What Works
- Single frame control flow: `last LABEL` works when called directly in the same frame
- Void context: `last LABEL` works through multiple frames in void context
- Test::More has been updated to use `last SKIP` directly (no TestMoreHelper workaround)

### What's Broken
- **Scalar context control flow**: `last LABEL` fails when called through 2+ frames in scalar context
- Tests 2, 5, 8 in `skip_control_flow.t` demonstrate this failure

## Baseline Expectations

Based on logs from 2026-01-06 09:27:
- **op/pack.t**: 14579/14726 tests passing
- **uni/variables.t**: 66683/66880 tests passing  
- **op/lc.t**: 2710/2716 tests passing

Current state shows regressions:
- **op/pack.t**: 245/14726 (regression of -14334 tests)
- **uni/variables.t**: 56/66880 (regression of -66627 tests)

## Test Files

### Unit Test
`src/test/resources/unit/skip_control_flow.t` - 11 tests demonstrating control flow issues

**Backup location**: `/tmp/skip_control_flow.t.backup`

Run with:
```bash
./jperl src/test/resources/unit/skip_control_flow.t
```

Expected results:
- Tests 1, 3, 4, 6, 7, 9, 10, 11: PASS
- **Tests 2, 5, 8: FAIL** (scalar context issue - these demonstrate the problem)

### Integration Tests

Test `uni/variables.t`:
```bash
cd perl5_t/t && ../../jperl uni/variables.t 2>&1 | grep "Looks like"
# Should show: planned 66880, ran 66880 (or close to it)
# Currently shows: planned 66880, ran 56
```

Test `op/pack.t`:
```bash
cd perl5_t/t && ../../jperl op/pack.t 2>&1 | grep "Looks like"
# Should show: planned 14726, ran ~14579
# Currently shows: planned 14726, ran 249
```

Use test runner for batch testing:
```bash
perl dev/tools/perl_test_runner.pl perl5_t/t/op/pack.t perl5_t/t/uni/variables.t
```

## Build Process

**CRITICAL**: Always build with `make` and wait for completion:

```bash
make
# Wait for "BUILD SUCCESSFUL" message
# Do NOT interrupt the build
```

The `make` command runs:
- `./gradlew classes testUnitParallel --parallel shadowJar`
- Compiles Java code
- Runs unit tests
- Builds the JAR file

**Do NOT use**:
- `./gradlew shadowJar` alone (skips tests)
- `./gradlew clean shadowJar` (unless specifically needed)

## Development Workflow

### 1. BEFORE Making Changes

Add failing tests to `skip_control_flow.t` that demonstrate the problem:

```perl
# Example: Add a test that shows the issue
# Test X) Description of what should work but doesn't
{
    my $out = '';
    sub test_func { last SOMELABEL }
    SOMELABEL: {
        $out .= 'A';
        my $result = test_func();  # Scalar context
        $out .= 'B';
    }
    $out .= 'C';
    ok_tap($out eq 'AC', 'description of expected behavior');
}
```

Run the test to confirm it fails:
```bash
./jperl src/test/resources/unit/skip_control_flow.t
```

### 2. Make Your Changes

Focus areas:
- `src/main/java/org/perlonjava/codegen/EmitBlock.java` - Block code generation
- `src/main/java/org/perlonjava/runtime/RuntimeControlFlowRegistry.java` - Control flow marker registry
- Loop constructs in `EmitForeach.java`, `EmitStatement.java`

### 3. Build and Test

```bash
# Build (wait for completion)
make

# Test unit tests
./jperl src/test/resources/unit/skip_control_flow.t

# Test integration
cd perl5_t/t && ../../jperl uni/variables.t 2>&1 | grep "Looks like"
cd perl5_t/t && ../../jperl op/pack.t 2>&1 | grep "Looks like"
```

### 4. Verify No Regressions

Compare test counts to baseline:
- `uni/variables.t`: Should run ~66683/66880 tests (not stop at 56)
- `op/pack.t`: Should run ~14579/14726 tests (not stop at 245)
- `skip_control_flow.t`: All 11 tests should pass

## Key Technical Details

### Control Flow Registry

`RuntimeControlFlowRegistry` manages non-local control flow markers:
- `register(marker)` - Sets a control flow marker
- `hasMarker()` - Checks if marker exists
- `checkLoopAndGetAction(label)` - Checks and clears matching marker
- `markerMatchesLabel(label)` - Checks if marker matches without clearing
- `clear()` - Clears the marker

### The Problem

When `last LABEL` is called in scalar context through multiple frames:
1. The marker is registered correctly
2. The registry check happens
3. But the control flow doesn't properly exit the labeled block
4. This causes tests to continue executing when they should stop

### Previous Attempts

Several approaches were tried and caused regressions:
1. Adding registry checks in `EmitBlock.java` after each statement
   - Caused `op/pack.t` to stop at test 245
2. Unconditional registry clearing at block exit
   - Caused `op/pack.t` to stop at test 245  
3. Conditional registry clearing (only if marker matches)
   - Still caused `op/pack.t` to stop at test 245

The issue is that bare labeled blocks (not actual loops) need special handling.

## Success Criteria

1. All 11 tests in `skip_control_flow.t` pass (including tests 2, 5, 8)
2. `uni/variables.t` runs to completion (~66683/66880 tests)
3. `op/pack.t` runs to completion (~14579/14726 tests)
4. No new test failures introduced

## Files to Review

- `src/main/java/org/perlonjava/codegen/EmitBlock.java`
- `src/main/java/org/perlonjava/runtime/RuntimeControlFlowRegistry.java`
- `src/main/java/org/perlonjava/codegen/EmitForeach.java`
- `src/main/java/org/perlonjava/codegen/EmitStatement.java`
- `src/main/perl/lib/Test/More.pm`
- `src/test/resources/unit/skip_control_flow.t`

## Notes

- Work in a branch
- The JAR builds correctly - test results are real, not build issues
- The baseline (14579/14726 for op/pack.t) may be from a specific branch/commit
- Focus on making the unit tests pass first, then verify integration tests
- Real loops (for/while/foreach) have their own registry checks that work correctly
- The issue is specific to bare labeled blocks in scalar context

Good luck!
