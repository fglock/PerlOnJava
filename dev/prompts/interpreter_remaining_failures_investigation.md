# Investigation: Interpreter Mode Remaining Test Failures

## Current Status
- **Compiler mode**: 153/173 tests passing (88.5%)
- **Interpreter mode**: 147/173 tests passing (85.0%)
- **Gap**: 6 tests to reach compiler parity

## Test Failures Summary

### Group 1: Self-Recursive Eval with Lexical Variables (5 tests)
**Failing Tests**: 34, 37, 38, 59, 63

**Root Cause**: Critical bug in lexical variable capture when eval STRING contains:
1. A print statement with string interpolation referencing a lexical variable (e.g., `print "# level $l\n"`)
2. A recursive call to the same function passing that lexical variable (e.g., `recurse($l)`)

**Symptom**:
```perl
sub recurse {
    my $l = shift;
    eval 'print "# level $l\n"; recurse($l);';
}
```

Compiler output: `# level 42`
Interpreter output: `# level main::STDOUT`

The variable `$l` is being incorrectly resolved to "main::STDOUT" in the interpreter.

**Investigation Results**:
- Simple cases work fine: `eval 'print "l=$l\n"'` ✓
- Non-recursive cases work: `eval 'print "l=$l\n"; other_function($l);'` ✓
- Recursive cases WITHOUT print work: `eval 'recurse($l+1);'` ✓
- **FAILS**: `eval 'print "# level $l\n"; recurse($l);'` ✗

**Technical Analysis**:
The issue occurs during compilation of the eval STRING in interpreter mode. When both:
1. String interpolation uses `$l`
2. Function call argument uses `$l`
3. Function called is the currently executing function (self-recursion)

The variable lookup mechanism in BytecodeCompiler incorrectly resolves `$l`, possibly due to:
- Confusion between lexical scope variable and filehandle in print context
- Incorrect captured variable registry when the same eval STRING is compiled multiple times with different call frames
- Variable name resolution falling back to global lookup instead of captured lexicals

**Location**:
- `RuntimeCode.evalStringWithInterpreter()` - lines 754-796 (adjustedRegistry building)
- `BytecodeCompiler` variable resolution for captured variables

### Group 2: Strict Subs Error Not Setting $@ (1 test)
**Failing Test**: 168

**Test Code**:
```perl
use strict; use warnings;
$SIG{__DIE__} = sub { die "X" };
eval { eval "bar"; print "after eval $@"; };
if ($@) { print "outer eval $@" }
```

**Expected**: `after eval X at - line 1.`
**Got (interpreter)**: `after eval ` (empty $@)

**Root Cause**: The interpreter's `evalStringWithInterpreter()` is not catching and setting $@ for "Bareword not allowed while strict subs in use" errors.

**Investigation Results**:
- Syntax errors (e.g., `eval "1+;"`) properly set $@ ✓
- Bareword strict subs errors do NOT set $@ in interpreter ✗
- Compiler mode correctly sets $@ for bareword errors ✓

**Technical Analysis**:
The error handling in `RuntimeCode.evalStringWithInterpreter()` (lines 798-843) catches compilation exceptions and sets $@. However, strict subs violations may be:
1. Caught by a different exception path that doesn't set $@
2. Not being thrown as exceptions during BytecodeCompiler.compile()
3. Being silently ignored somewhere in the compilation pipeline

**Location**:
- `RuntimeCode.evalStringWithInterpreter()` - error handling block (lines 798-843)
- `BytecodeCompiler.compile()` - strict subs enforcement

### Group 3: Line Number Tracking in Eval (1 test)
**Failing Test**: 136

**Test Code**:
```perl
eval "\${\nfoobar\n} = 10; warn q{should be line 3}";
# Expected: "should be line 3 at (eval 1) line 3.\n"
# Got: undef
```

**Root Cause**: Line number tracking is not properly maintained when compiling multi-line eval STRING in interpreter mode.

**Technical Analysis**:
The interpreter needs to:
1. Track source line numbers during parsing of eval STRING
2. Map bytecode positions to source lines
3. Report correct line numbers in error messages

This likely requires enhancing `BytecodeCompiler` to store line number mapping information that can be used by error reporting.

**Location**:
- `BytecodeCompiler` - line number tracking during compilation
- `InterpretedCode.pcToTokenIndex` mapping
- Error message generation in BytecodeInterpreter

## Fixes Completed So Far

### Fix 1: Context Propagation (commit 4a4fa943)
- Fixed BytecodeCompiler to preserve context for last statement in blocks
- Only non-last statements use VOID context
- **Tests fixed**: 107-108

### Fix 2: Post-Increment/Decrement (commit 1248a54b)
- Removed incorrect STORE_GLOBAL_SCALAR after POST_AUTO* opcodes
- Fixed BytecodeInterpreter to store return values from post-increment/decrement
- **Tests fixed**: 12-13

### Fix 3: Local Variable Support (commit 93ce1df6)
- Added dynamic variable restoration in evalStringWithInterpreter
- Implemented local($var)=value assignment pattern support
- **Tests fixed**: 13 (additional improvement)

## Recommended Fix Priority

### Priority 1: Test 168 (Strict Subs Error Handling) - QUICKEST WIN
**Estimated Effort**: Low
**Impact**: 1 test

**Approach**:
1. Debug why bareword strict subs errors aren't being caught
2. Ensure all compilation errors properly set $@ in evalStringWithInterpreter
3. Add specific handling for PerlCompilerException from strict violations

**Files to Modify**:
- `RuntimeCode.evalStringWithInterpreter()` - error handling

### Priority 2: Test 136 (Line Number Tracking) - MEDIUM EFFORT
**Estimated Effort**: Medium
**Impact**: 1 test

**Approach**:
1. Enhance BytecodeCompiler to properly track source line numbers
2. Store line-to-bytecode mapping in InterpretedCode
3. Use mapping in error reporting

**Files to Modify**:
- `BytecodeCompiler` - add line tracking
- `InterpretedCode` - enhance pcToTokenIndex
- Error message generation code

### Priority 3: Tests 34, 37, 38, 59, 63 (Self-Recursive Eval) - COMPLEX
**Estimated Effort**: High
**Impact**: 5 tests (would exceed compiler parity)

**Approach** (complex - requires deep debugging):
1. Add extensive logging to variable capture mechanism
2. Debug adjustedRegistry building in evalStringWithInterpreter
3. Investigate why print + recursive call causes variable confusion
4. Possibly requires architectural change to how variables are captured per eval execution

**Files to Modify**:
- `RuntimeCode.evalStringWithInterpreter()` - variable capture (lines 762-796)
- `BytecodeCompiler` - variable resolution for captured variables
- Possibly: eval STRING compilation caching mechanism

## Path to Compiler Parity (153 tests)

To reach 153 tests passing (compiler parity), we need to fix **6 more tests**.

**Recommended Strategy**:
1. Fix Test 168 (strict subs $@ setting) - **+1 test** → 148/173
2. Fix Test 136 (line numbers) - **+1 test** → 149/173
3. Investigate and fix self-recursive eval bug for remaining tests

**Alternative Strategy**:
Since the self-recursive eval bug is complex, we could:
1. Fix simpler issues in other test categories (tests 45-46, 95-97, 99-102, 121-122, 125-126, 130-131, 146-151)
2. Look for quick wins in those 20 other failing tests
3. Pick the 4 easiest to reach 153 total

## Next Steps

1. **Immediate**: Fix test 168 by ensuring strict subs errors set $@ properly
2. **Short-term**: Fix test 136 line number tracking
3. **Medium-term**: Debug self-recursive eval variable capture or find 4 other easy wins
4. **Goal**: Reach 153 tests passing to achieve compiler parity and enable PR merge

## Notes

- The self-recursive eval bug is a fundamental issue with how the interpreter captures lexical variables in recursive eval contexts
- Fixing it may require significant refactoring of the variable capture mechanism
- Consider whether reaching parity via other simpler test fixes is more pragmatic
- All fixes should maintain existing passing tests (regression prevention)
