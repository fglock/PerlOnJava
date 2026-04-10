# Regex Preprocessing Fixes

## Overview

This document tracks regex preprocessing issues discovered while running `re/pat.t`, `re/pat_advanced.t`, and related tests with `JPERL_UNIMPLEMENTED=warn`.

## Completed Fixes

### 1. Invalid quantifier brace handling consuming regex metacharacters

**Root cause:** `handleQuantifier()` in `RegexPreprocessor.java` used `s.indexOf('}', start)` to find the closing brace of a potential quantifier, but this search crossed character class boundaries and regex groups. For example, in `{  (?> [^{}]+ | (??{...}) )* }`, the `{` at the start was treated as a potential quantifier, and `indexOf('}')` found the `}` inside the character class `[^{}]`, consuming everything in between (including `(?>` and `[^{`) as literal text.

**Fix:** When `handleQuantifier` determines that braces don't form a valid quantifier (content contains non-numeric characters), it now only escapes the opening `{` as `\{` and returns immediately, letting the main regex loop process subsequent characters normally. Previously it consumed and escaped the entire `{...}` range.

**Files changed:** `RegexPreprocessor.java` — `handleQuantifier()` method

### 2. `\x{...}` hex escape with non-hex characters

**Root cause:** The hex escape handler used `Integer.parseInt(hexStr, 16)` which throws `NumberFormatException` for strings containing non-hex characters (e.g., `\x{9bq}`). Inside character classes, this was caught and re-thrown as a fatal `PerlCompilerException`, killing the test run. Outside character classes, the escape was passed through to Java's regex engine which also rejected it.

**Perl behavior:** `\x{9bq}` extracts the valid hex prefix `9b` (value 0x9B) and ignores the remaining characters. `\x{x9b}` has no valid prefix, so the value is 0. Underscores are allowed (removed by preprocessing) but other non-hex chars terminate the hex number.

**Fix:** All three `\x{...}` handlers now extract the valid hex prefix instead of requiring the entire content to be valid hex:
- `handleRegexCharacterClassEscape()` — inside `[...]` (was the fatal crash)
- `handleEscapeSequences()` — outside `[...]`
- Range endpoint parser — for character class ranges

**Files changed:** `RegexPreprocessorHelper.java`

### 3. Bare `\xNN` with non-hex characters

**Root cause:** Bare `\x` (without braces) was passed through to Java's regex engine, which expects exactly 2 hex digits after `\x`. Patterns like `\xk` or `\x4j` caused `PatternSyntaxException`.

**Perl behavior:** `\x` takes up to 2 hex digits. `\xk` = `\x00` followed by literal `k`. `\x4j` = `\x04` followed by literal `j`.

**Fix:** Added explicit bare `\x` handling that parses up to 2 hex digits and emits `\x{HH}` format when fewer than 2 valid hex digits are found.

**Files changed:** `RegexPreprocessorHelper.java` — `handleEscapeSequences()` method

### 4. NullPointerException when regex fails with JPERL_UNIMPLEMENTED=warn

**Root cause:** When regex compilation fails and gets downgraded to a warning, the catch block in `RuntimeRegex.compile()` set the error pattern but didn't set `regex.patternString`. Downstream code (e.g., `replaceRegex()`) checked `regex.patternString == null` and triggered recompilation with a null pattern, causing NPE in `convertPythonStyleGroups(null).replaceAll(...)`.

**Fix:**
1. Set `regex.patternString` in the catch block when downgrading to warning
2. Added null guard in `preProcessRegex()` to treat null input as empty string

**Files changed:** `RuntimeRegex.java`, `RegexPreprocessor.java`

## Known Remaining Issues

### Unimplemented Features (not fixable without major work)

| Feature | Impact | Test Files |
|---------|--------|------------|
| `(?{...})` code blocks | Cannot execute Perl code inside regex. Replaced with no-op group. | pat.t, reg_eval_scope.t |
| `(??{...})` recursive patterns | Non-constant recursive patterns replaced with empty group. Works for constant expressions. | pat.t |
| `(*ACCEPT)`, `(*FAIL)`, etc. | Regex control verbs not implemented in Java's regex engine. | pat.t |
| Lookbehind > 255 chars | Java's regex limits lookbehind length. | pat.t |

### Test Pass Rates

| Test | Master | After fixes | Change |
|------|--------|-------------|--------|
| `re/pat.t` | 428/1298 | **533**/1298 (ran 632) | **+105** |
| `re/pat_advanced.t` | 63/1298 | **731**/838 | **+668** |
| `re/pat_rt_report.t` | 2397/2515 | 2397/2515 (ran 2470) | same |
| `re/reg_eval_scope.t` | 6/49 | 7/49 | +1 |
| `uni/variables.t` | 66880/66880 | 66880/66880 | same |

**Notes:**
- pat_advanced.t: The massive improvement (+668) is from fixing `\x{...}` hex escapes, which unblocked the test run past line 321
- pat.t: +105 improvement from fixing the brace quantifier issue (unblocked `(?>...)` inside `{...}` patterns)
- pat.t still crashes at lookbehind > 255 (line ~1250), blocking remaining 666 tests
- pat_rt_report.t now runs 73 more tests (2470 vs 2397) before hitting a `(?{...})` code block

## Progress Tracking

### Current Status: All fixes implemented and verified (2026-04-10)

### Completed
- [x] Fix 1: handleQuantifier brace consumption (2025-04-10)
- [x] Fix 2: \x{...} hex escape with non-hex chars (2025-04-10)
- [x] Fix 3: Bare \xNN with non-hex chars (2025-04-10)
- [x] Fix 4: NPE on failed regex with JPERL_UNIMPLEMENTED=warn (2025-04-10)

### Files Modified
- `src/main/java/org/perlonjava/runtime/regex/RegexPreprocessor.java`
- `src/main/java/org/perlonjava/runtime/regex/RegexPreprocessorHelper.java`
- `src/main/java/org/perlonjava/runtime/regex/RuntimeRegex.java`
