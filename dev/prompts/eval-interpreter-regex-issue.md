# Regex Brace Escaping: Interpreter Mode Issue

## Status

✅ **FIXED** in JVM compiler mode
❌ **NOT FIXED** in interpreter mode

## Problem Summary

When a regex pattern with escaped braces like `/(.*?)\{(.*?)\}/g` is interpolated into an eval STRING via heredoc or `qq{}`, the pattern fails to match in interpreter mode.

## Test Results

### Test Case
```perl
my $rx = q{/(.*?)\{(.*?)\}/g};
my $i = 0;
my $input = "a{b}c{d}";
eval <<"--";
    while (\$input =~ $rx) {
        \$i++;
        last if \$i > 10;
    }
--
print "i=$i\n";
```

### Results

| Mode | Expected | Actual | Status |
|------|----------|--------|--------|
| Real Perl | i=2 | i=2 | ✅ Reference |
| JVM Compiler | i=2 | i=2 | ✅ Fixed |
| Interpreter | i=2 | i=0 | ❌ **Broken** |

With inner `eval $input`:
| Mode | Expected | Actual | Status |
|------|----------|--------|--------|
| Real Perl | i=2 | i=2 | ✅ Reference |
| JVM Compiler | i=2 | i=2 | ✅ Fixed |
| Interpreter | i=2 | i=11 | ❌ **Infinite loop** |

## Root Cause

The fix in `RegexPreprocessor.escapeInvalidQuantifierBraces()` is correctly applied when `RuntimeRegex.compile()` is called from generated JVM bytecode.

However, the interpreter mode appears to have a different code path for handling regex compilation within eval'd code that either:
1. Bypasses `RegexPreprocessor.preProcessRegex()`, OR
2. Caches the regex differently, OR
3. Handles the pattern string differently during eval compilation

## Investigation Needed

Need to trace the exact code path in interpreter mode:

1. How does `BytecodeInterpreter` handle eval'd code containing regex patterns?
2. Does `BytecodeCompiler` create regex objects differently than the parser?
3. Is there regex caching at the interpreter level that bypasses preprocessing?
4. Are regex patterns compiled at parse time vs runtime in the interpreter?

## Related Code

- `RegexPreprocessor.escapeInvalidQuantifierBraces()` - The fix (works in JVM mode)
- `RuntimeRegex.compile()` - Calls preprocessor (line 103)
- `BytecodeInterpreter.MATCH_REGEX` - Interpreter regex matching (line 1552)
- `BytecodeCompiler` - Compiles AST to interpreter bytecode
- Eval handling in interpreter mode

## Test Files

- `src/test/resources/unit/regex/unescaped_braces.t` - Basic test (passes in JVM mode)
- `perl5_t/t/re/pat_rt_report.t` - Comprehensive test (test 21 fails in interpreter mode)

## Next Steps

1. **Understand interpreter eval path**: Trace how eval'd code with regex is compiled
2. **Find the bypass**: Identify where the preprocessor is being skipped
3. **Apply fix**: Ensure preprocessor is called in interpreter mode too
4. **Test**: Verify both modes work correctly

## Temporary Workaround

Use JVM compiler mode (default) instead of interpreter mode for code with interpolated regex patterns in eval strings.
