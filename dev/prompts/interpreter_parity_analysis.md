# Interpreter Parity Analysis

## Current Status with JPERL_EVAL_USE_INTERPRETER=1

| Test File | Interpreter | Baseline (Compiler) | Gap | Target Met? |
|-----------|-------------|---------------------|-----|-------------|
| uni/variables.t | 66761/66880 (99.8%) | 66880/66880 (100%) | -119 | ❌ |
| perf/benchmarks.t | 1869/1960 (95.4%) | 1960/1960 (100%) | -91 | ❌ |
| comp/retainedlines.t | 27/109 (24.8%) | 92/109 (84.4%) | -65 | ❌ |
| re/regexp.t | 1738/2210 (78.6%) | 1786/2210 (80.8%) | -48 | ❌ |

## Issue Analysis

### Compound Assignment Operators (✅ COMPLETED in PR #211)
The operators (x=, **=, <<=, >>=, &&=, ||=) were added but they benefited the **baseline** 
(compiler mode), not the **interpreter**. The baseline was already passing these tests.

### Actual Interpreter Issues

#### 1. perf/benchmarks.t failures (-91 tests)
**Pattern:** `call::goto::*`, `call::sub::*` tests
**Cause:** `goto &sub` syntax not supported in BytecodeCompiler
**Error:** "syntax error at (eval) line X"
**Example:**
```perl
sub f { goto &g }  # Tail-call optimization
sub g { my ($a, $b, $c) = @_ };
f(1,2,3);
```

#### 2. uni/variables.t failures (-119 tests)  
**Pattern:** Block labels, strict mode checks
**Cause:** `${label:with:colons}` not handled properly
**Error:** "Assignment to unsupported operator: $"
**Example:**
```perl
${single:colon} = "label, not var";  # Should work as label
```

#### 3. comp/retainedlines.t failures (-65 tests)
**Pattern:** Eval source retention for debugger
**Cause:** Missing $^P debugger support
- Needs eval source stored in @{"::_<eval N"} arrays
- Not critical for functionality

#### 4. re/regexp.t failures (-48 tests)
**Pattern:** Regex compile-time errors
**Cause:** Runtime vs compile-time error detection
**Example:**  
```perl
/a[b-a]/;  # Should fail at compile time, not runtime
```

## Priority Fixes Needed

### High Priority (fixes most tests)
1. **Implement `goto &sub` in BytecodeCompiler** - Would fix ~91 perf/benchmarks.t tests
2. **Fix bareword label parsing** - Would fix ~119 uni/variables.t tests

### Medium Priority  
3. **Add debugger support ($^P)** - Would fix ~65 comp/retainedlines.t tests

### Low Priority
4. **Compile-time regex validation** - Would fix ~48 re/regexp.t tests

## Recommended Next Steps

Focus on BytecodeCompiler enhancements:
1. Add support for `goto &sub` (tail call)
2. Fix bareword label handling in `${label:name}`
3. Add missing AST visitor methods as needed

This would achieve parity on the two largest test gaps (perf/benchmarks.t and uni/variables.t).
