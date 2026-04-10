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

### Test Pass Rates (after all fixes)

| Test | Before fixes | After fixes | Remaining failures |
|------|-------------|-------------|-------------------|
| `re/pat.t` | 428/1298 | **533**/1298 (ran 632) | 99 fail + 666 not reached |
| `re/pat_advanced.t` | 63/1298 | **731**/838 | 107 fail |
| `re/pat_rt_report.t` | 2397/2515 | **2431**/2515 (ran 2508) | 77 fail + 7 not reached |
| `re/reg_eval_scope.t` | 6/49 | 7/49 | 42 fail |
| `uni/variables.t` | 66880/66880 | 66880/66880 | 0 |

### Early Termination (crashes blocking remaining tests)

| Test | Crash point | Cause | Tests blocked |
|------|------------|-------|---------------|
| pat.t | Line 1247 (test 632) | `\p{isAlpha}` — POSIX-style Unicode property alias not supported | 666 tests |
| pat_advanced.t | Line 1122 (test 838) | `(?1)` — numbered group recursion not supported | 0 (near end) |
| pat_rt_report.t | Line 1158 (test 2508) | `(?1)` — numbered group recursion not supported | 7 tests |

### Failure Categories

#### A. `\G` anchor (26 failures in pat.t)

The `\G` assertion (match at pos()) has significant issues:
- **Floating `\G`** patterns like `/a+\G/` fail — Java doesn't support `\G` except at pattern start
- **`\G` in loops** (`/\G.../gc` iteration) doesn't maintain position correctly
- Tests: pat.t 455-518

**Difficulty: Medium-High.** Requires custom `\G` tracking in the match engine; Java's `\G` only works at the start of a match attempt.

#### B. `(?{...})` code blocks (36 failures in pat.t, 5 in pat_advanced.t, 5 in pat_rt_report.t)

Regex embedded code blocks are replaced with no-op groups. This breaks:
- **`$^R`** — result of last `(?{...})` (tests 308-310)
- **`pos()` inside `(?{...})`** (tests 470-494)
- **Package/lexical variable access** inside `(?{...})` (tests 522-525)
- **Eval-group runtime checks** — "Eval-group not allowed at runtime" (tests 300-304)

**Difficulty: Very High.** Would require integrating the Perl compiler into the regex engine to execute code at match time.

#### C. `$^N` — last successful capture (20 failures in pat_advanced.t)

`$^N` is not updated after successful group captures. Tests 69-88 all fail.
- Both outside regex and inside `(?{...})` usage fails
- `$^N` is automatically localized — not implemented

**Difficulty: Medium.** Requires tracking the last successfully matched group in the match result.

#### D. `(??{...})` recursive patterns (5 failures in pat.t)

Non-constant recursive patterns are replaced with empty groups. Tests 293-297 (complicated backtracking, recursion with `(??{})`) all fail.

**Difficulty: Very High.** Same as `(?{...})` — requires runtime code execution.

#### E. `(*ACCEPT)`, `(*FAIL)` control verbs (5 failures in pat.t)

Regex control verbs are not supported by Java's regex engine. Tests 357-373 (ACCEPT and CLOSE buffer tests).

**Difficulty: High.** Would require a custom regex engine or post-processing layer.

#### F. `@-` / `@+` / `@{^CAPTURE}` arrays (12 failures in pat.t, 5 in pat_rt_report.t)

The match position arrays have bugs:
- **Wrong values** for capture group positions (tests 381-438)
- **Stale values** not cleared after new match (tests 439-441)
- **Read-only protection** throws wrong exception type: `UnsupportedOperationException` instead of `Modification of a read-only value attempted` (test 449)
- **Interpolation in patterns** — `@-` and `@+` should not be interpolated (pat_rt_report.t 151-154)
- **Undefined values** in `@-`/`@+` after match (pat_rt_report.t 213)

**Difficulty: Medium.** The data is available from Java's `Matcher`; needs more careful mapping to Perl semantics.

#### G. `qr//` stringification and modifiers (4 failures in pat.t)

- `qr/\b\v$/xism` stringifies as `(?^imsx:\b\v$)` but should be `(?^msix:\\b\\v$)` — backslashes not escaped in stringification (test 315)
- **`/u` modifier** not tracked: `use feature 'unicode_strings'` should add `/u` flag (tests 323-327)

**Difficulty: Low-Medium.** Stringification fix is straightforward; `/u` modifier tracking needs scope awareness.

#### H. `\N{name}` charnames (25 failures in pat_advanced.t)

Named character escapes have extensive issues:
- **Empty `\N{}`** not handled correctly (tests 794-809)
- **`\N{PLUS SIGN}`** — named characters not expanded in regex (tests 831-833)
- **`\N{U+0041}`** in character class — `[\N{SPACE}\N{U+0041}]` fails (test 836)
- **Charname validation** — leading digit, comma, latin1 symbol errors not produced (tests 821-828)
- **Charname caching** with `$1` — not implemented (tests 798-801)
- **Cedilla/NO-BREAK SPACE** in names — error handling missing (tests 816-819)

**Difficulty: Medium-High.** `\N{U+XXXX}` is partially implemented; full charnames support needs the `charnames` module.

#### I. Useless `(?c)` / `(?g)` / `(?o)` warnings (13 failures in pat_advanced.t)

Perl warns about useless regex modifiers (`/c`, `/g`, `/o` are match-operator flags, not regex flags). PerlOnJava silently ignores them without producing warnings.

**Difficulty: Low.** Add warning emission in the regex flag parser.

#### J. Bare `\x` hex escape edge cases (5 failures in pat_advanced.t)

Our fix handles the crash but the test strings don't match correctly:
- `\x4j` produces `\004j` but regex `[\x4j]{2}` doesn't match it (test 101)
- `\xk` produces `\000k` but regex `[\xk]{2}` doesn't match it (test 102)
- `\xx`, `\xxa`, `\x9_b` — regex character class expansion doesn't match the test string (tests 103-105)

The issue is that the test string and the regex pattern both use `\x` escapes, but the regex preprocessor and the string processor handle them differently. The test expects both to produce the same character.

**Difficulty: Low-Medium.** The regex-side `\x` handling needs to produce character classes that match what the string-side produces.

#### K. Conditional `(?(1)...)` with `$` anchor — Bug 41010 (48 failures in pat_rt_report.t)

The largest single failure category. Patterns like `/([ ]*$)(?(1))/` don't match correctly. This is a systematic issue with conditionals referencing a group that ends with `$` anchor.

**Difficulty: Medium.** Likely a subtle difference in how Java handles the interaction between `$` anchor in a group and conditional backreference.

#### L. `$REGMARK` / `${^PREMATCH}` etc. (6 failures in pat_rt_report.t)

`$REGMARK` (set by `(*MARK:name)`) is not implemented. Tests 2458-2463.

**Difficulty: High.** Requires `(*MARK)` verb support.

#### M. `(?1)` numbered group recursion (1 failure in pat_advanced.t, 1 in pat_rt_report.t)

`(?1)` syntax for recursing into capture group 1 is not recognized. Causes fatal "Sequence (?1...) not recognized" error. This is what crashes pat_advanced.t at test 838 and pat_rt_report.t at test 2508.

**Difficulty: Very High.** Java's regex engine has no recursion support. Would need a custom engine or PCRE/JNI bridge.

#### N. `\p{isAlpha}` POSIX-style Unicode property (crash in pat.t)

The POSIX-style Unicode property syntax `\p{isAlpha}`, `\p{isSpace}` is not recognized. This causes the fatal error that stops pat.t at line 1247, blocking 666 remaining tests.

**Difficulty: Low-Medium.** Map POSIX-style aliases (`isAlpha` → `Alpha`, `isSpace` → `Space`, etc.) in the Unicode property handler.

#### O. Empty clause in alternation (3 failures in pat.t)

Empty alternatives in patterns like `/(|a)/` or the "0 match in alternation" test don't work correctly.

**Difficulty: Low-Medium.** Likely a regex preprocessing issue.

#### P. Miscellaneous (small counts)

| Issue | Tests | Difficulty |
|-------|-------|-----------|
| Look around edge cases | pat.t 332-333 | Medium |
| REG_INFTY (quantifier limit) | pat.t 250 | Low |
| POSIX class error message format | pat.t 348 | Low |
| Lookbehind limit (Java) | pat.t 252 | Hard (engine limit) |
| Empty pattern pmop flags | pat_rt_report.t 44 | Medium |
| Nested split | pat_rt_report.t 85 | Medium |
| Ill-formed UTF-8 in class | pat_rt_report.t 140 | Medium |
| Pattern in loop (prev success) | pat_rt_report.t 2469-2470 | Medium |
| Long string patterns | pat_advanced.t 805-813 | Medium |
| `/d` to `/u` modifier change | pat_advanced.t 807-808 | Low-Medium |

### Priority Recommendations

**Quick wins (Low difficulty, high impact):**
1. **`\p{isAlpha}` aliases** — unblocks 666 pat.t tests (category N)
2. **Useless `(?c)`/`(?g)`/`(?o)` warnings** — fixes 13 pat_advanced.t tests (category I)
3. **POSIX class error message** — fix message format (category P)
4. **REG_INFTY error** — add quantifier limit check (category P)

**Medium effort, significant impact:**
5. **`(?(1)...)` with `$` anchor** — fixes 48 pat_rt_report.t tests (category K)
6. **`@-`/`@+` position arrays** — fixes 17 tests across files (category F)
7. **`$^N` last capture** — fixes 20 pat_advanced.t tests (category C)
8. **Bare `\x` edge cases** — fixes 5 pat_advanced.t tests (category J)
9. **`\N{name}` charnames** — fixes 25 pat_advanced.t tests (category H)

**Hard / architectural (major work):**
10. **`\G` anchor** — 26 pat.t tests (category A)
11. **`(?{...})` code blocks** — 46 tests total (category B)
12. **`(?1)` recursion / `(*ACCEPT)` / `(*MARK)`** — engine limitations (categories E, L, M)

## Progress Tracking

### Current Status: All preprocessing fixes done; remaining issues catalogued (2026-04-10)

### Completed
- [x] Fix 1: handleQuantifier brace consumption (2026-04-10)
- [x] Fix 2: \x{...} hex escape with non-hex chars (2026-04-10)
- [x] Fix 3: Bare \xNN with non-hex chars (2026-04-10)
- [x] Fix 4: NPE on failed regex with JPERL_UNIMPLEMENTED=warn (2026-04-10)
- [x] Failure analysis and categorization (2026-04-10)

### Files Modified
- `src/main/java/org/perlonjava/runtime/regex/RegexPreprocessor.java`
- `src/main/java/org/perlonjava/runtime/regex/RegexPreprocessorHelper.java`
- `src/main/java/org/perlonjava/runtime/regex/RuntimeRegex.java`
