# Line Number Reporting Investigation

## Problem Statement
Die messages report incorrect line numbers, especially in multi-line cases.

## Test Cases

### Simple one-liner (WORKS)
```bash
./jperl -e 'die "Here"'
# Reports: line 1 ✓ (correct)
```

### Multi-line -e (BROKEN)
```bash
./jperl -e '


die "Here"

'
# Reports: line 7 ✗ (should be line 4)
# Stack trace shows: line 4 ✓ (correct)
```

### Simple file (BROKEN)
```bash
# File contains just: die "Here"
./jperl /tmp/simple_die.pl
# Reports: line 3 ✗ (should be line 1)
# Stack trace shows: line 1 ✓ (correct)
```

## Root Cause Analysis

### The # line Directive Problem

All code (both `-e` and files) gets prepended with `# line 1\n` in ArgumentParser.java:981:
```java
parsedArgs.code = "# line 1\n" + parsedArgs.code;
```

The directive `# line 1\n` means "the NEXT line after this newline is line 1".

When `parseLineDirective()` processes this:
1. It parses the line number (1)
2. Sets `errorUtil.setLineNumber(0)` and `errorUtil.setTokenIndex(NUMBER_token_position)`
3. Later code asks for line number of a token by calling `errorUtil.getLineNumber(tokenIndex)`
4. `getLineNumber()` counts newlines from `errorUtil.tokenIndex + 1` to `tokenIndex`
5. **BUG**: It counts the NEWLINE that terminates the `# line` directive as part of the source!

### Why Stack Traces Are Correct

Stack traces use `ByteCodeSourceMapper` which relies on JVM line numbers that were set during compilation using `mv.visitLineNumber()`. This uses a different code path that gets the right answer.

### Why Die Messages Are Wrong

Die messages use:
- **Compiler**: `errorUtil.getLineNumber(node.tokenIndex)` in EmitOperator.java:354
- **Interpreter**: AST node annotations in BytecodeCompiler.java (which also use errorUtil)

Both paths use errorUtil which has the off-by-N counting bug.

## Attempted Fix

Modified `parseLineDirective()` in Whitespace.java to skip past the terminating newline before calling `setTokenIndex()`:

```java
// Skip to end of line
while (tokenIndex < tokens.size() && tokens.get(tokenIndex).type != LexerTokenType.NEWLINE) {
    tokenIndex++;
}
// Skip past the newline
if (tokenIndex < tokens.size() && tokens.get(tokenIndex).type == LexerTokenType.NEWLINE) {
    tokenIndex++;
}
// Now set errorUtil state AFTER the directive's newline
parser.ctx.errorUtil.setLineNumber(lineNumber - 1);
parser.ctx.errorUtil.setTokenIndex(tokenIndex - 1);
```

### Why This Fix Breaks Tests

The fix causes 110+ unit tests to fail with bizarre symptoms:
- Tests show: `got: '5' expected: '5'` but still fail
- All comparison tests fail even when values match
- Line numbers in test output appear correct

**Hypothesis**: The fix changes how errorUtil tracks position, which somehow breaks Test::More's internal state management or comparison logic. The exact mechanism is unclear.

## Alternative Approaches Considered

### 1. Don't Prepend `# line 1` for Files
Only prepend for `-e` code. Files could track line numbers naturally.
- **Pro**: Simpler, might avoid the bug
- **Con**: Requires separate code paths for files vs `-e`

### 2. Fix errorUtil.getLineNumber() Logic
Instead of fixing parseLineDirective, fix how getLineNumber counts.
- **Pro**: Might be less invasive
- **Con**: The counting logic is already complex

### 3. Use AST Annotations Everywhere
Have parser set correct line numbers in AST annotations, bypass errorUtil.
- **Pro**: Simpler model
- **Con**: Annotations are currently also wrong (they use errorUtil)

## Current Status

- Simple `-e` cases work correctly
- Multi-line cases are broken (off by 2-3 lines)
- Stack traces are always correct
- Fix attempt breaks tests mysteriously

## Recommendation

This requires more investigation:
1. Understand why the fix breaks Test::More
2. Consider refactoring the entire line tracking system
3. Or remove `# line 1` prepending for files
4. Add integration tests that verify line numbers in error messages
