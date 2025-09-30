# Fix Heredoc Empty Delimiter Bug

## Objective
Fix the bug where indented heredocs with empty delimiters (`<<~ ''` or `<<~ ""`) fail when there's no trailing newline after the content.

## Background Context

### Current Status
- **Indented heredoc (`<<~`) IS implemented** and works correctly for non-empty delimiters
- The indentation stripping logic in `ParseHeredoc.java` is correct
- Regular heredocs with empty delimiters (`<< ''`) work fine
- The bug is specific to the combination of `<<~` (tilde) + empty delimiter + no trailing newline

### The Bug

**Failing Case:**
```perl
print <<~ '';
  some data
```
Error: "Can't find string terminator "" anywhere before EOF"

**Working Case:**
```perl
print <<~ '';
  some data

```
(Note the extra blank line - this works!)

### Expected Behavior
According to Perl documentation and standard Perl behavior:
- Empty delimiter heredocs terminate at the first empty line
- With `<<~`, the terminating line's indentation determines what to strip
- Should work with or without trailing newlines

### Test Impact
- **~50 tests failing** in `t/op/heredoc.t`
- All failures are variations of empty delimiter with `<<~`
- Test examples: `<<~ ''`, `<<~''`, `<<~ ""`, `<<~""`

## Technical Details

### Key Files
- **Primary:** `src/main/java/org/perlonjava/parser/ParseHeredoc.java`
  - Line 168: Where the error is thrown (`heredocError`)
  - Lines 130-150: Empty line detection and delimiter matching
  - Lines 178-185: Indentation stripping logic (working correctly)

### Root Cause Analysis
The error occurs in `parseHeredocAfterNewline()` when:
1. Parser encounters `<<~ ''` (tilde + empty delimiter)
2. Reads content lines successfully
3. Reaches EOF without finding the terminating empty line
4. Throws "Can't find string terminator" error

**Hypothesis:** The empty delimiter matching logic doesn't correctly handle the EOF case when the last line has no trailing newline.

### Relevant Code Sections

**Delimiter Matching (lines 136-150):**
```java
// Check if this line is the end marker
String lineToCompare = line;
if (indent) {
    // Left-trim the line if indentation is enabled
    lineToCompare = line.stripLeading();
}

if (lineToCompare.equals(identifier)) {
    // End of heredoc - remove the end marker line from the content
    lines.removeLast();
    
    // Determine the indentation of the end marker
    indentWhitespace = line.substring(0, line.length() - lineToCompare.length());
    foundTerminator = true;
    break;
}
```

**EOF Check (lines 163-168):**
```java
if (!foundTerminator) {
    if (currentIndex >= tokens.size() ||
            (currentIndex < tokens.size() && tokens.get(currentIndex).type == LexerTokenType.EOF)) {
        heredocError(parser, heredocNode);
    }
    // ...
}
```

## Debugging Strategy

### Step 1: Reproduce and Understand
1. Create minimal test cases:
   ```perl
   # Test 1: Fails
   ./jperl -e 'print <<~ "";
     data
   '
   
   # Test 2: Works
   ./jperl -e 'print <<~ "";
     data
   
   '
   ```

2. Compare with standard Perl behavior:
   ```bash
   perl -e 'print <<~ "";
     data
   '
   ```

### Step 2: Add Strategic Debug Logging
Add logging to `ParseHeredoc.java` to trace:
- When empty delimiter is detected
- Each line being processed
- Empty line detection logic
- EOF handling

Example debug points:
```java
System.err.println("DEBUG: identifier='" + identifier + "', isEmpty=" + identifier.isEmpty());
System.err.println("DEBUG: Processing line: '" + line + "', lineToCompare: '" + lineToCompare + "'");
System.err.println("DEBUG: At EOF, foundTerminator=" + foundTerminator);
```

### Step 3: Identify the Issue
Focus on:
- How empty lines are detected when delimiter is empty
- Whether the last line (without trailing newline) is being processed correctly
- If EOF is being reached before the empty delimiter match logic runs

### Step 4: Implement Fix
Likely fixes might involve:
- Special handling for empty delimiters at EOF
- Treating EOF as an implicit empty line for empty delimiters
- Adjusting the line-by-line parsing to handle the no-trailing-newline case

### Step 5: Test Thoroughly
Run the full test suite:
```bash
./jperl t/op/heredoc.t 2>&1 | grep -E "^(ok|not ok)" | wc -l
./jperl t/op/heredoc.t 2>&1 | grep -c "^ok"
./jperl t/op/heredoc.t 2>&1 | grep -c "^not ok"
```

Expected improvement: ~50 tests should change from "not ok" to "ok"

## Success Criteria
1. ✅ `<<~ ''` works without trailing newline
2. ✅ `<<~ ""` works without trailing newline  
3. ✅ All variations (`<<~''`, `<<~ ''`, etc.) work
4. ✅ No regression in existing heredoc tests
5. ✅ ~50 tests in `t/op/heredoc.t` now passing

## Reference Documentation
- Perl documentation: `perldoc perlop` (search for "Indented Here-docs")
- Key behavior: Terminating line's indentation determines what to strip
- Empty delimiter: Terminates at first empty line

## Previous Session Context
This bug was identified during a high-yield bug hunting session where 7 major fixes were implemented (~320 tests improved). The heredoc issue was deferred due to its complexity, warranting a dedicated debugging session.

## Tips for Success
1. **Use strategic debug logging** - Don't guess, trace the execution
2. **Test incrementally** - Verify each hypothesis with a test case
3. **Compare with Perl** - When in doubt, check what standard Perl does
4. **Focus on EOF handling** - The bug is specifically about EOF + empty delimiter
5. **Don't break existing functionality** - Test both empty and non-empty delimiters after changes

Good luck! This should be a satisfying fix with significant test impact. 🚀
